/**
 * Documentation-quality findings and requirement coverage (spec §14.46), computed from the domain model only -- never guessed.
 * A compliance owner asks "is the documentation complete?" (a control with no description, no framework, or a title shared with
 * another) and "which requirements have no control?" (the list of requirements is supplied by the caller; matching is EXACT
 * string equality with the framework strings METADATA names, the same strings the framework matrix groups on).
 */
package io.explico.render

import io.explico.model.CommentBlock
import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RuleGroup
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
        /**
         * Spec §14.63, only when example fixtures were supplied: controls none of whose worked examples is a match (or that have none) -- a reader is missing
         * a fixture that makes the control hold. A finding, never a gap.
         */
        val noMatchingExample: List<ControlRef> = emptyList(),
        /** Spec §14.63, only when example fixtures were supplied: controls with no declared verdict whose evaluated value is a JSON object, so their examples show raw JSON. */
        val rawDecisionObject: List<ControlRef> = emptyList(),
        /**
         * Spec §14.64: controls whose title is null or blank. NOT a displayed finding (the index and `explico.json` never show it, and [isEmpty] ignores it): it
         * exists so a caller can require titles, which makes it a gap.
         */
        val missingTitle: List<ControlRef> = emptyList(),
        /** Spec §14.65: plain comment blocks of every package that never reach the documentation, sorted by file then row. Shown (advisory), never a gap. */
        val unpublishedComments: List<CommentBlock> = emptyList(),
    ) {
        fun isEmpty(): Boolean =
            missingDescription.isEmpty() && noFramework.isEmpty() && duplicateTitles.isEmpty() && noMatchingExample.isEmpty() && rawDecisionObject.isEmpty() &&
                unpublishedComments.isEmpty()
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
        /** The rules this entry stands for with their packages: one, or every member of a grouped control. */
        val members: List<Pair<PolicyPackage, RuleGroup>>,
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
                    listOf(pkg to rule),
                )
            }
        }
        val grouped = groups?.groups.orEmpty().map { group ->
            val first = group.members.first()
            Entry(
                group.controlId, group.title, group.members.any { !it.rule.metadata?.description.isNullOrBlank() },
                group.members.flatMap { it.rule.metadata?.frameworks.orEmpty() }.distinct(), false,
                ControlRef(group.controlId, first.packagePath, group.anchor, group.page), first.packagePath, first.rule.name,
                group.members.map { member -> policySet.packages.first { it.path == member.packagePath } to member.rule },
            )
        }
        return (ungrouped + grouped).sortedWith(compareBy({ it.controlId == null }, { it.controlId }, { it.packagePath }, { it.ruleName }))
    }

    /** Findings are about CONTROLS only: a rule with a control id that is not a split-default entry (with [groups], a grouped control counts once). */
    fun findingsOf(
        policySet: PolicySet,
        groups: ControlGroups? = null,
        /** The evaluated worked examples by package and rule name (the map the cards and the index read); null or empty = no fixtures were supplied. */
        examplesByPackage: Map<String, Map<String, List<WorkedExample>>>? = null,
    ): Findings {
        val controls = entries(policySet, groups).filter { it.controlId != null && !it.isSplitDefault }
        val byTitle = controls.filter { it.title != null }.groupBy { it.title!! }
        val supplied = !examplesByPackage.isNullOrEmpty()
        // The SAME outcomes the cards and the index's "Example coverage" column use (so a name shared by several cards, whose examples cannot be attributed, has none).
        fun outcomesOf(pkg: PolicyPackage, rule: RuleGroup) = WorkedExamples.forGroup(rule, pkg, examplesByPackage?.get(pkg.path)?.get(rule.name) ?: emptyList())
        // A function is not matched by an input and has no examples (spec §14.62): never a finding of these two kinds.
        val ofRules = controls.filter { supplied && it.members.any { (_, rule) -> rule.parameters.isEmpty() } }
        return Findings(
            missingTitle = controls.filter { it.title.isNullOrBlank() }.map { it.ref },
            unpublishedComments = policySet.packages.flatMap { it.unpublishedComments }.sortedWith(compareBy({ it.file }, { it.row })),
            missingDescription = controls.filter { !it.hasDescription }.map { it.ref },
            noFramework = controls.filter { it.frameworks.isEmpty() }.map { it.ref },
            duplicateTitles = byTitle.filterValues { it.size > 1 }.entries.sortedBy { it.key }.map { (title, group) -> title to group.map { it.ref } },
            noMatchingExample = ofRules.filter { entry -> entry.members.none { (pkg, rule) -> outcomesOf(pkg, rule).any { it.matched } } }.map { it.ref },
            rawDecisionObject = ofRules.filter { entry -> entry.members.any { (pkg, rule) -> isRawDecisionObject(pkg, rule, ::outcomesOf) } }.map { it.ref },
        )
    }

    /**
     * Spec §14.63: [rule] declares no verdict and at least one example's evaluated value is a decision object -- a JSON object with some value that is not `true`
     * (an object whose values are all `true`, or an empty one, is a partial-object rule's set of keys, shown as messages). A wrongly shaped `custom.verdict` is
     * not carried by the model (it is warned about and ignored), so it counts as undeclared -- and its examples do show raw JSON.
     */
    private fun isRawDecisionObject(pkg: PolicyPackage, rule: RuleGroup, outcomesOf: (PolicyPackage, RuleGroup) -> List<WorkedExample>): Boolean {
        if (rule.parameters.isNotEmpty() || rule.metadata?.projection?.verdict != null) return false
        return outcomesOf(pkg, rule).any { outcome ->
            val value = outcome.value
            value is JsonObject && value.values.any { it != JsonPrimitive(true) }
        }
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
     * One line per documentation gap (spec §14.47, §14.64), worded here ONCE so the CLI gate, a pipeline script and a dashboard agree. For each property in
     * [required] (default: description and frameworks, as always) a line per control that lacks it, in the order description, frameworks, title, a matching
     * example, a declared verdict; then -- whatever is required -- controls sharing a title, then (when a requirements list was given) each requirement with no
     * control in list order and each framework string not on the list. The example-based kinds need [findings] computed WITH the examples.
     */
    fun gapDescriptions(findings: Findings, coverage: RequirementsCoverage?, required: RequiredSet = RequiredSet.DEFAULT): List<String> = buildList {
        if ("description" in required) findings.missingDescription.forEach { add("control ${it.label} has no description") }
        if ("frameworks" in required) findings.noFramework.forEach { add("control ${it.label} names no framework") }
        if ("title" in required) findings.missingTitle.forEach { add("control ${it.label} has no title") }
        if ("fixture" in required) findings.noMatchingExample.forEach { add("control ${it.label} has no matching example") }
        if ("verdict" in required) findings.rawDecisionObject.forEach { add("control ${it.label} returns an object but declares no custom.verdict") }
        findings.duplicateTitles.forEach { (title, controls) -> add("controls ${controls.joinToString(", ") { it.label }} share the title \"$title\"") }
        coverage?.items?.filter { it.controls.isEmpty() }?.forEach { add("requirement ${it.requirement} has no control") }
        coverage?.unlisted?.forEach { add("framework string $it is not in the requirements list") }
    }

    fun normalise(requirements: List<String>): List<String> = requirements.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
