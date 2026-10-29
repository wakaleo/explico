/**
 * Documentation-quality findings and requirement coverage (spec §14.46), computed from the domain model only -- never guessed.
 * A compliance owner asks "is the documentation complete?" (a control with no description, no framework, or a title shared with
 * another) and "which requirements have no control?" (the list of requirements is supplied by the caller; matching is EXACT
 * string equality with the framework strings METADATA names, the same strings the framework matrix groups on).
 */
package io.explico.render

import io.explico.model.PolicySet

internal object Quality {

    /** A control (or, for requirement coverage, any rule naming a framework) as the index links it. */
    data class ControlRef(val label: String, val packagePath: String, val anchorId: String?, val page: String? = null) {
        /**
         * `[label](file#anchor)`, or the bare label when the card claimed no anchor (a duplicate name) -- never a dead link. [page] is the file the
         * anchor lives on when that is not the package's own page (spec §14.56: a grouped control's page, `controls/<slug>.md`; the index is top-level).
         */
        fun link(): String = anchorId?.let { "[$label](${page ?: MarkdownRenderer.docFileName(packagePath)}#$it)" } ?: label
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

    /**
     * What a finding is about: one control. Without grouping that is one rule; with it (spec §14.56) the rules of a grouped control are ONE entry --
     * described if any of its rules is, naming a framework if any of its rules does -- so a control split over three files is not reported three times.
     */
    private class Entry(
        val controlId: String?,
        val title: String?,
        val hasDescription: Boolean,
        val frameworks: List<String>,
        val isSplitDefault: Boolean,
        val ref: ControlRef,
        val packagePath: String,
        val ruleName: String,
    )

    /** Every control with its anchor, in index order: control id (ids first), then package, then rule name. */
    private fun entries(policySet: PolicySet, groups: ControlGroups?): List<Entry> {
        val ungrouped = policySet.packages.flatMap { pkg ->
            val anchors = MarkdownRenderer.cardAnchorIds(pkg, groups)
            pkg.rules.mapIndexedNotNull { index, rule ->
                if (groups?.placementOf(pkg.path, index) != null) return@mapIndexedNotNull null
                Entry(
                    rule.metadata?.controlId, rule.metadata?.title, !rule.metadata?.description.isNullOrBlank(), rule.metadata?.frameworks.orEmpty(),
                    rule.isSplitDefaultEntry, ControlRef(rule.metadata?.controlId ?: rule.name, pkg.path, anchors[index]), pkg.path, rule.name,
                )
            }
        }
        val grouped = groups?.groups.orEmpty().map { group ->
            val first = group.members.first()
            Entry(
                group.controlId, group.title, group.members.any { !it.rule.metadata?.description.isNullOrBlank() },
                group.members.flatMap { it.rule.metadata?.frameworks.orEmpty() }.distinct(), false,
                ControlRef(group.controlId, first.packagePath, group.anchor, group.page), first.packagePath, first.rule.name,
            )
        }
        return (ungrouped + grouped).sortedWith(compareBy({ it.controlId == null }, { it.controlId }, { it.packagePath }, { it.ruleName }))
    }

    /** Findings are about CONTROLS only: a rule with a control id that is not a split-default entry (with [groups], a grouped control counts once). */
    fun findingsOf(policySet: PolicySet, groups: ControlGroups? = null): Findings {
        val controls = entries(policySet, groups).filter { it.controlId != null && !it.isSplitDefault }
        val byTitle = controls.filter { it.title != null }.groupBy { it.title!! }
        return Findings(
            missingDescription = controls.filter { !it.hasDescription }.map { it.ref },
            noFramework = controls.filter { it.frameworks.isEmpty() }.map { it.ref },
            duplicateTitles = byTitle.filterValues { it.size > 1 }.entries.sortedBy { it.key }.map { (title, group) -> title to group.map { it.ref } },
        )
    }

    /**
     * [requirements] is normalised (trimmed, blanks dropped, duplicates collapsed keeping first position) so the library and the CLI
     * agree. A rule counts when its METADATA names the requirement exactly; an id-less rule is labelled by its rule name, like the matrix.
     */
    fun coverageOf(policySet: PolicySet, requirements: List<String>, groups: ControlGroups? = null): RequirementsCoverage {
        val listed = normalise(requirements)
        val all = entries(policySet, groups).filter { !it.isSplitDefault }
        val items = listed.map { requirement ->
            RequirementItem(requirement, all.filter { requirement in it.frameworks }.map { it.ref })
        }
        val unlisted = all.flatMap { it.frameworks }.distinct().filterNot { it in listed }
            .sortedWith(compareBy({ it.lowercase() }, { it }))
        return RequirementsCoverage(items, unlisted)
    }

    /**
     * One line per documentation gap (spec §14.47), worded here ONCE so the CLI gate, a pipeline script and a dashboard agree: a control
     * with no description, one naming no framework, controls sharing a title, then (when a requirements list was given) each requirement with
     * no control in list order and each framework string not on the list.
     */
    fun gapDescriptions(findings: Findings, coverage: RequirementsCoverage?): List<String> = buildList {
        findings.missingDescription.forEach { add("control ${it.label} has no description") }
        findings.noFramework.forEach { add("control ${it.label} names no framework") }
        findings.duplicateTitles.forEach { (title, controls) -> add("controls ${controls.joinToString(", ") { it.label }} share the title \"$title\"") }
        coverage?.items?.filter { it.controls.isEmpty() }?.forEach { add("requirement ${it.requirement} has no control") }
        coverage?.unlisted?.forEach { add("framework string $it is not in the requirements list") }
    }

    fun normalise(requirements: List<String>): List<String> = requirements.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
