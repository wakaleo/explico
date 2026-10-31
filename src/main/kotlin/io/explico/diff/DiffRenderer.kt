/**
 * Renders a [DiffEntry] list into the single Markdown change report (spec §7.3). Reuses
 * [MarkdownRenderer.renderCard] for REMOVED/ADDED/LOGIC_CHANGED so a control's rendering here is
 * never a second, divergent implementation of the same card.
 */
package io.explico.diff

import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RuleGroup
import io.explico.render.Coverage
import io.explico.render.MarkdownRenderer

internal object DiffRenderer {

    private const val DISCLAIMER = "This report shows structural changes only. It does not " +
        "evaluate whether the policy became stricter or more permissive."

    /** Spec §14.73: with data supplied the report also compares the reference data each control reads, and says so. */
    private const val DISCLAIMER_WITH_DATA = "This report shows structural changes and changes to the reference data the controls read. It does not " +
        "evaluate whether the policy became stricter or more permissive."

    private val SECTION_ORDER = listOf(DiffCategory.REMOVED, DiffCategory.ADDED, DiffCategory.LOGIC_CHANGED, DiffCategory.DOCS_CHANGED)
    private val SUMMARY_ORDER = SECTION_ORDER + DiffCategory.UNCHANGED

    fun render(
        entries: List<DiffEntry>,
        oldPolicySet: PolicySet,
        newPolicySet: PolicySet,
        dataChanges: DataChangeIndex = DataChangeIndex.NONE,
        dataSupplied: Boolean = false,
        /** Null = no example fixtures were supplied: there is no `## Behaviour on your examples` section at all (spec §14.74). */
        exampleChanges: ExampleChangeIndex? = null,
        /** Null = no policy directories were given: no `**Compared**` block (spec §14.76). */
        header: DiffHeader? = null,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("# Policy diff report")
        sb.appendLine()
        sb.appendLine("*${if (dataSupplied) DISCLAIMER_WITH_DATA else DISCLAIMER}*")
        sb.appendLine()
        if (header != null) appendCompared(sb, header)
        appendSummary(sb, entries)
        val changed = entries.filter { it.category != DiffCategory.UNCHANGED }
            .sortedWith(ChangeSummary.REPORT_ORDER)
        val qualified = ChangeSummary.qualifiedIds(entries)
        val anchors = ChangeAnchors(changed, qualified)
        appendWhatChanged(sb, changed, qualified, anchors, dataChanges)
        if (exampleChanges != null) appendBehaviour(sb, changed, qualified, anchors, exampleChanges)
        val isLinkable = linkableTargetsOf(changed, anchors)
        appendChanges(sb, changed, oldPolicySet, newPolicySet, isLinkable, qualified, anchors, dataChanges)
        appendCoverageWarning(sb, entries)
        return sb.toString()
    }

    /** Spec §14.42: a short, plain list before the full cards -- see [ChangeSummary]. Absent when [changed] is empty, same as `## Changes`. */
    private fun appendWhatChanged(sb: StringBuilder, changed: List<DiffEntry>, qualified: Set<String>, anchors: ChangeAnchors, dataChanges: DataChangeIndex) {
        if (changed.isEmpty()) return
        sb.appendLine("## What changed")
        sb.appendLine()
        for (entry in changed) {
            sb.appendLine(ChangeSummary.bulletFor(entry, qualified, anchors))
            for (sub in ChangeSummary.subBulletsFor(entry, dataChanges.of(entry))) sb.appendLine("  - $sub")
        }
        sb.appendLine()
    }

    /** Spec §14.76: the digest of each compared version, so a report can be tied to a release and to the `index.md` of the same directory. */
    private fun appendCompared(sb: StringBuilder, header: DiffHeader) {
        fun named(side: String, label: String?) = if (label == null) side else "$side ($label)"
        sb.appendLine("**Compared**")
        sb.appendLine()
        sb.appendLine("- ${named("Old", header.oldLabel)}: `${header.oldDigest}`")
        sb.appendLine("- ${named("New", header.newLabel)}: `${header.newDigest}`${if (header.samePolicies) " (same policies as Old)" else ""}")
        sb.appendLine()
    }

    /**
     * Spec §14.74: for each control whose outcome on one of the author's fixtures changed, those fixtures. Controls that changed without moving any outcome are not
     * listed; when none moved, one sentence says so. Labels and anchors are the ones "What changed" uses.
     */
    private fun appendBehaviour(sb: StringBuilder, changed: List<DiffEntry>, qualified: Set<String>, anchors: ChangeAnchors, exampleChanges: ExampleChangeIndex) {
        sb.appendLine("## Behaviour on your examples")
        sb.appendLine()
        val affected = changed.filter { exampleChanges.of(it).isNotEmpty() }
        if (affected.isEmpty()) {
            sb.appendLine(ChangeSummary.noExampleChangedSentence(exampleChanges.comparison))
        }
        for (entry in affected) {
            sb.appendLine("- ${ChangeSummary.labelMarkupFor(entry, qualified, anchors)}")
            for (change in exampleChanges.of(entry)) sb.appendLine("  - ${ChangeSummary.exampleLine(change)}")
        }
        sb.appendLine()
        // Spec §14.74: what the section is based on, so a short list (or "no change") is never read as more than was compared.
        ChangeSummary.comparisonLine(exampleChanges.comparison)?.let { line ->
            sb.appendLine(line)
            sb.appendLine()
        }
    }

    /**
     * Follow-up to review item 18: a diff report only gives a card (and therefore an anchor) to a
     * REMOVED/ADDED/LOGIC_CHANGED control -- never to an UNCHANGED or DOCS_CHANGED one, which
     * render no card at all. A reference whose target isn't in this set has nowhere in THIS
     * document to link to, so [MarkdownRenderer.renderCard] renders it unlinked instead of a dead
     * `](#anchor)`. REMOVED renders the OLD side's card; ADDED/LOGIC_CHANGED render the NEW side's
     * -- the set is keyed by whichever side's (packagePath, ruleName) that rendered card actually
     * uses, since a reference FROM that card is resolved against the SAME policy version.
     */
    private fun linkableTargetsOf(entries: List<DiffEntry>, anchors: ChangeAnchors): (String, String) -> Boolean {
        val targets = entries.filter { anchors.of(it) != null }.mapNotNull { entry ->
            when (entry.category) {
                DiffCategory.REMOVED -> entry.oldPackage!!.path to entry.oldRule!!.name
                DiffCategory.ADDED, DiffCategory.LOGIC_CHANGED -> entry.newPackage!!.path to entry.newRule!!.name
                DiffCategory.DOCS_CHANGED, DiffCategory.UNCHANGED -> null
            }
        }.toSet()
        return { packagePath, ruleName -> (packagePath to ruleName) in targets }
    }

    private fun appendSummary(sb: StringBuilder, entries: List<DiffEntry>) {
        val counts = entries.groupingBy { it.category }.eachCount()
        sb.appendLine("## Summary")
        sb.appendLine()
        sb.appendLine("| Category | Count |")
        sb.appendLine("|---|---|")
        for (category in SUMMARY_ORDER) {
            sb.appendLine("| $category | ${counts[category] ?: 0} |")
        }
        sb.appendLine()
    }

    private fun appendChanges(sb: StringBuilder, changed: List<DiffEntry>, oldPolicySet: PolicySet, newPolicySet: PolicySet, isLinkable: (String, String) -> Boolean, qualified: Set<String>, anchors: ChangeAnchors, dataChanges: DataChangeIndex) {
        if (changed.isEmpty()) return

        sb.appendLine("## Changes")
        sb.appendLine()
        for (entry in changed) {
            appendSection(sb, entry, oldPolicySet, newPolicySet, isLinkable, qualified, anchors, dataChanges.of(entry), dataChanges.wasRaised(entry))
            sb.appendLine("---")
            sb.appendLine()
        }
    }

    private fun appendSection(sb: StringBuilder, entry: DiffEntry, oldPolicySet: PolicySet, newPolicySet: PolicySet, isLinkable: (String, String) -> Boolean, qualified: Set<String>, anchors: ChangeAnchors, dataChanges: List<DataChange>, raisedByData: Boolean) {
        sb.appendLine("**${entry.category}**")
        sb.appendLine()
        when (entry.category) {
            DiffCategory.REMOVED -> {
                sb.appendLine("**⚠ This control has been removed.**")
                sb.appendLine()
                sb.append(card(entry.oldRule!!, entry.oldPackage!!, oldPolicySet, isLinkable, qualified, anchors.of(entry)))
                sb.appendLine()
            }
            DiffCategory.ADDED -> {
                sb.append(card(entry.newRule!!, entry.newPackage!!, newPolicySet, isLinkable, qualified, anchors.of(entry)))
                sb.appendLine()
            }
            DiffCategory.LOGIC_CHANGED -> {
                sb.append(card(entry.newRule!!, entry.newPackage!!, newPolicySet, isLinkable, qualified, anchors.of(entry)))
                sb.appendLine()
                if (dataChanges.isNotEmpty()) {
                    sb.appendLine("**Reference data**")
                    sb.appendLine()
                    for (change in dataChanges) sb.appendLine("- ${ChangeSummary.dataChangeLine(change)}")
                    sb.appendLine()
                }
                // Spec §14.75: documentation that changed together with the logic -- only the rows that differ, after the card and before the source.
                val documentation = MetadataFields.of(entry.oldRule!!.metadata, entry.newRule.metadata).filter { it.differs }
                if (documentation.isNotEmpty()) {
                    sb.appendLine("**Documentation changes**")
                    sb.appendLine()
                    appendDocsRows(sb, documentation)
                    sb.appendLine()
                }
                sb.appendLine("**Source diff**")
                sb.appendLine()
                // Spec §14.73: a change that is only in the data has no - or + line to show; say so rather than print an unchanged rule as if it were a diff.
                // Only for an entry the DATA raised (its logic hash is equal): for one that changed on its own the hash may differ for a reason the source text does not show.
                if (raisedByData && diffableLines(entry.oldRule!!) == diffableLines(entry.newRule)) {
                    sb.appendLine("*The rule's source is unchanged: only the reference data it reads changed.*")
                } else {
                    sb.appendLine("```rego")
                    appendUnifiedDiff(sb, entry.oldRule!!, entry.newRule)
                    sb.appendLine("```")
                }
                sb.appendLine()
            }
            DiffCategory.DOCS_CHANGED -> {
                appendDocsTable(sb, entry)
                sb.appendLine()
            }
            DiffCategory.UNCHANGED -> Unit
        }
    }

    /**
     * One rule's card. For a control id in [qualified] (spec §14.57) the heading also names `package.rule`, the anchor is rule-qualified, and links from the
     * card to such a rule go to the target's rule-qualified anchor; any other card is exactly the ordinary one.
     */
    private fun card(rule: RuleGroup, pkg: PolicyPackage, policySet: PolicySet, isLinkable: (String, String) -> Boolean, qualified: Set<String>, anchorId: String?): String {
        val controlId = rule.metadata?.controlId
        return MarkdownRenderer.renderCard(
            rule, pkg, policySet,
            anchorId = anchorId,
            isLinkable = isLinkable,
            heading = if (controlId != null && controlId in qualified) ChangeSummary.labelFor(controlId, pkg.path, rule, qualified) else null,
            qualifiedIds = qualified,
            singleDocument = true,
        )
    }

    /** A `rego` fenced unified text diff of the two rule sources (spec §7.3), body-by-body verbatim source, LCS-diffed line by line. */
    private fun appendUnifiedDiff(sb: StringBuilder, oldRule: RuleGroup, newRule: RuleGroup) {
        val oldLines = diffableLines(oldRule)
        val newLines = diffableLines(newRule)
        for (line in LineDiffer.diff(oldLines, newLines)) {
            when (line) {
                is DiffLine.Unchanged -> sb.appendLine("  ${line.text}")
                is DiffLine.Removed -> sb.appendLine("- ${line.text}")
                is DiffLine.Added -> sb.appendLine("+ ${line.text}")
            }
        }
    }

    /**
     * Body source lines, PLUS a synthesized `default <name> := <value>` line when [rule] has one
     * (review item 14's follow-up) or a synthesized `<name> := <value>` line when it's a constant
     * rule (review item 10, finding 4) -- neither a `default` nor a constant rule's value is part
     * of any body's own source text (a constant rule has no body at all), so without this a
     * changed default/constant produced a LOGIC_CHANGED entry with an EMPTY diff.
     */
    private fun diffableLines(rule: RuleGroup): List<String> {
        val bodyLines = if (rule.bodies.isEmpty()) emptyList() else rule.bodies.joinToString("\n\n") { it.sourceText }.lines()
        val defaultLine = rule.default?.let { listOf("default ${rule.name} := ${it.rendered}") } ?: emptyList()
        val constantLine = rule.constantValue?.let { listOf("${rule.name} := ${it.rendered}") } ?: emptyList()
        return defaultLine + constantLine + bodyLines
    }

    /** The old/new table a DOCS_CHANGED shows: the three base rows always, the optional rows only when they differ. */
    private fun appendDocsTable(sb: StringBuilder, entry: DiffEntry) =
        appendDocsRows(sb, MetadataFields.of(entry.oldRule?.metadata, entry.newRule?.metadata).filter { it.base || it.differs })

    private fun appendDocsRows(sb: StringBuilder, fields: List<DocField>) {
        sb.appendLine("| | Old | New |")
        sb.appendLine("|---|---|---|")
        for (field in fields) sb.appendLine("| ${field.label} | ${field.oldCell} | ${field.newCell} |")
    }

    private fun appendCoverageWarning(sb: StringBuilder, entries: List<DiffEntry>) {
        val incomplete = entries.count { entry ->
            entry.category != DiffCategory.UNCHANGED &&
                (entry.newRule ?: entry.oldRule)?.let { Coverage.of(it).percent < 100 } == true
        }
        if (incomplete > 0) {
            sb.appendLine("⚠ $incomplete changed controls contain conditions that could not be rendered; review source diffs directly.")
        }
    }
}
