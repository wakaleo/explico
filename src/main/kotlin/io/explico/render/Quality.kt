/**
 * Documentation-quality findings and requirement coverage (spec §14.46), computed from the domain model only -- never guessed.
 * A compliance owner asks "is the documentation complete?" (a control with no description, no framework, or a title shared with
 * another) and "which requirements have no control?" (the list of requirements is supplied by the caller; matching is EXACT
 * string equality with the framework strings METADATA names, the same strings the framework matrix groups on).
 */
package io.explico.render

import io.explico.model.PolicySet
import io.explico.model.RuleGroup

internal object Quality {

    /** A control (or, for requirement coverage, any rule naming a framework) as the index links it. */
    data class ControlRef(val label: String, val packagePath: String, val anchorId: String?) {
        /** `[label](file#anchor)`, or the bare label when the card claimed no anchor (a duplicate name) -- never a dead link. */
        fun link(): String = anchorId?.let { "[$label](${MarkdownRenderer.docFileName(packagePath)}#$it)" } ?: label
    }

    data class Findings(
        val missingDescription: List<ControlRef>,
        val noFramework: List<ControlRef>,
        /** (title, controls sharing it), sorted by title. */
        val duplicateTitles: List<Pair<String, List<ControlRef>>>,
    ) {
        fun isEmpty(): Boolean = missingDescription.isEmpty() && noFramework.isEmpty() && duplicateTitles.isEmpty()
    }

    data class RequirementItem(val requirement: String, val controls: List<ControlRef>)

    data class RequirementsCoverage(val items: List<RequirementItem>, val unlisted: List<String>) {
        val total: Int get() = items.size
        val covered: Int get() = items.count { it.controls.isNotEmpty() }
    }

    private class Entry(val rule: RuleGroup, val ref: ControlRef, val packagePath: String)

    /** Every rule with its anchor, in index order: control id (ids first), then package, then rule name. */
    private fun entries(policySet: PolicySet): List<Entry> =
        policySet.packages.flatMap { pkg ->
            val anchors = MarkdownRenderer.cardAnchorIds(pkg)
            pkg.rules.mapIndexed { index, rule ->
                Entry(rule, ControlRef(rule.metadata?.controlId ?: rule.name, pkg.path, anchors[index]), pkg.path)
            }
        }.sortedWith(compareBy({ it.rule.metadata?.controlId == null }, { it.rule.metadata?.controlId }, { it.packagePath }, { it.rule.name }))

    /** Findings are about CONTROLS only: a rule with a control id that is not a split-default entry. */
    fun findingsOf(policySet: PolicySet): Findings {
        val controls = entries(policySet).filter { it.rule.metadata?.controlId != null && !it.rule.isSplitDefaultEntry }
        val byTitle = controls.filter { it.rule.metadata?.title != null }.groupBy { it.rule.metadata!!.title!! }
        return Findings(
            missingDescription = controls.filter { it.rule.metadata?.description.isNullOrBlank() }.map { it.ref },
            noFramework = controls.filter { it.rule.metadata?.frameworks.isNullOrEmpty() }.map { it.ref },
            duplicateTitles = byTitle.filterValues { it.size > 1 }.entries.sortedBy { it.key }.map { (title, group) -> title to group.map { it.ref } },
        )
    }

    /**
     * [requirements] is normalised (trimmed, blanks dropped, duplicates collapsed keeping first position) so the library and the CLI
     * agree. A rule counts when its METADATA names the requirement exactly; an id-less rule is labelled by its rule name, like the matrix.
     */
    fun coverageOf(policySet: PolicySet, requirements: List<String>): RequirementsCoverage {
        val listed = normalise(requirements)
        val all = entries(policySet).filter { !it.rule.isSplitDefaultEntry }
        val items = listed.map { requirement ->
            RequirementItem(requirement, all.filter { requirement in it.rule.metadata?.frameworks.orEmpty() }.map { it.ref })
        }
        val unlisted = all.flatMap { it.rule.metadata?.frameworks.orEmpty() }.distinct().filterNot { it in listed }
            .sortedWith(compareBy({ it.lowercase() }, { it }))
        return RequirementsCoverage(items, unlisted)
    }

    fun normalise(requirements: List<String>): List<String> = requirements.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
