/**
 * Spec §14.56: with `groupByControl`, a control id shared by two or more rules (one control split over several files and packages) is given ONE page,
 * `controls/<slug>.md`. This is the pure grouping decision -- which ids are shared, where each rule then lives, what anchors it has -- and nothing else;
 * the renderers, the index, the quality findings and the JSON all read THIS, so they cannot disagree about where a card is.
 *
 * Presentation only: no rule changes and nothing is evaluated. Everything here is sorted, so the output is deterministic.
 */
package io.explico.render

import io.explico.model.PolicySet
import io.explico.model.RuleGroup

internal class ControlGroups private constructor(val groups: List<Group>) {

    /** One rule of a grouped control: [ruleIndex] is its position in its package's `rules`; [anchor] is null when another card on the page already claimed that id. */
    class Member(val packagePath: String, val ruleIndex: Int, val rule: RuleGroup, val anchor: String?)

    /** A grouped control: its page (`controls/<slug>.md`), the page-level [anchor], the title of its first titled rule, and its [members] in source-path order. */
    class Group(val controlId: String, val page: String, val anchor: String, val title: String?, val members: List<Member>)

    class Placement(val group: Group, val member: Member)

    private val placements: Map<Pair<String, Int>, Placement> =
        groups.flatMap { group -> group.members.map { (it.packagePath to it.ruleIndex) to Placement(group, it) } }.toMap()

    /** Where the rule at [ruleIndex] of package [packagePath] lives, or null when its card stays on the package page. */
    fun placementOf(packagePath: String, ruleIndex: Int): Placement? = placements[packagePath to ruleIndex]

    companion object {
        /** Directory (inside the output) the control pages are written to. */
        const val DIRECTORY = "controls"

        private val SOURCE_ORDER: Comparator<Member> = compareBy<Member>(
            { MarkdownRenderer.ruleLocation(it.rule) == null },
            { MarkdownRenderer.ruleLocation(it.rule)?.file.orEmpty() },
            { MarkdownRenderer.ruleLocation(it.rule)?.row ?: 0 },
            { it.packagePath },
            { it.rule.name },
            { it.ruleIndex },
        )

        fun of(policySet: PolicySet): ControlGroups {
            class Candidate(val controlId: String, val packagePath: String, val ruleIndex: Int, val rule: RuleGroup)

            // A split-default entry is never a control of its own (it carries no control id), so it can never be grouped.
            val sharedById = policySet.packages
                .flatMap { pkg ->
                    pkg.rules.mapIndexedNotNull { index, rule ->
                        rule.metadata?.controlId?.takeIf { !rule.isSplitDefaultEntry }?.let { Candidate(it, pkg.path, index, rule) }
                    }
                }
                .groupBy { it.controlId }
                .filterValues { it.size >= 2 }

            // File names are claimed in sorted-id order, so two ids that slug alike ("X-1", "x_1") get x-1.md and x-1-2.md, whatever order the files were read in.
            val claimedPages = mutableSetOf<String>()
            val groups = sharedById.keys.sorted().map { id ->
                val base = MarkdownRenderer.slug(id).ifEmpty { "control" }
                var name = base
                var suffix = 2
                while (!claimedPages.add(name)) name = "$base-${suffix++}"

                // Anchors are claimed in policy order (package, then rule), the order a link's target is resolved in, so the card a link points at is the card that owns the id.
                val claimedAnchors = mutableSetOf(base)
                val members = sharedById.getValue(id).map { c ->
                    val anchor = MarkdownRenderer.slug("$id-${c.packagePath}-${c.rule.name}").takeIf { claimedAnchors.add(it) }
                    Member(c.packagePath, c.ruleIndex, c.rule, anchor)
                }.sortedWith(SOURCE_ORDER)
                Group(id, "$DIRECTORY/$name.md", base, members.firstNotNullOfOrNull { it.rule.metadata?.title }, members)
            }
            return ControlGroups(groups)
        }
    }
}
