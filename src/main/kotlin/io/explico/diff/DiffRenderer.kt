/**
 * Renders a [DiffEntry] list into the single Markdown change report (spec §7.3). Reuses
 * [MarkdownRenderer.renderCard] for REMOVED/ADDED/LOGIC_CHANGED so a control's rendering here is
 * never a second, divergent implementation of the same card.
 */
package io.explico.diff

import io.explico.model.PolicySet
import io.explico.model.RuleGroup
import io.explico.render.Coverage
import io.explico.render.MarkdownRenderer

internal object DiffRenderer {

    private const val DISCLAIMER = "This report shows structural changes only. It does not " +
        "evaluate whether the policy became stricter or more permissive."

    private val SECTION_ORDER = listOf(DiffCategory.REMOVED, DiffCategory.ADDED, DiffCategory.LOGIC_CHANGED, DiffCategory.DOCS_CHANGED)
    private val SUMMARY_ORDER = SECTION_ORDER + DiffCategory.UNCHANGED

    fun render(entries: List<DiffEntry>, oldPolicySet: PolicySet, newPolicySet: PolicySet): String {
        val sb = StringBuilder()
        sb.appendLine("# Policy diff report")
        sb.appendLine()
        sb.appendLine("*$DISCLAIMER*")
        sb.appendLine()
        appendSummary(sb, entries)
        appendChanges(sb, entries, oldPolicySet, newPolicySet)
        appendCoverageWarning(sb, entries)
        return sb.toString()
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

    private fun appendChanges(sb: StringBuilder, entries: List<DiffEntry>, oldPolicySet: PolicySet, newPolicySet: PolicySet) {
        val changed = entries.filter { it.category != DiffCategory.UNCHANGED }
            .sortedWith(compareBy({ SECTION_ORDER.indexOf(it.category) }, { it.controlId == null }, { it.controlId }))
        if (changed.isEmpty()) return

        sb.appendLine("## Changes")
        sb.appendLine()
        for (entry in changed) {
            appendSection(sb, entry, oldPolicySet, newPolicySet)
            sb.appendLine("---")
            sb.appendLine()
        }
    }

    private fun appendSection(sb: StringBuilder, entry: DiffEntry, oldPolicySet: PolicySet, newPolicySet: PolicySet) {
        sb.appendLine("**${entry.category}**")
        sb.appendLine()
        when (entry.category) {
            DiffCategory.REMOVED -> {
                sb.appendLine("**⚠ This control has been removed.**")
                sb.appendLine()
                sb.append(MarkdownRenderer.renderCard(entry.oldRule!!, entry.oldPackage!!, oldPolicySet))
                sb.appendLine()
            }
            DiffCategory.ADDED -> {
                sb.append(MarkdownRenderer.renderCard(entry.newRule!!, entry.newPackage!!, newPolicySet))
                sb.appendLine()
            }
            DiffCategory.LOGIC_CHANGED -> {
                sb.append(MarkdownRenderer.renderCard(entry.newRule!!, entry.newPackage!!, newPolicySet))
                sb.appendLine()
                sb.appendLine("**Source diff**")
                sb.appendLine()
                sb.appendLine("```rego")
                appendUnifiedDiff(sb, entry.oldRule!!, entry.newRule)
                sb.appendLine("```")
                sb.appendLine()
            }
            DiffCategory.DOCS_CHANGED -> {
                appendDocsTable(sb, entry)
                sb.appendLine()
            }
            DiffCategory.UNCHANGED -> Unit
        }
    }

    /** A `rego` fenced unified text diff of the two rule sources (spec §7.3), body-by-body verbatim source, LCS-diffed line by line. */
    private fun appendUnifiedDiff(sb: StringBuilder, oldRule: RuleGroup, newRule: RuleGroup) {
        val oldLines = oldRule.bodies.joinToString("\n\n") { it.sourceText }.lines()
        val newLines = newRule.bodies.joinToString("\n\n") { it.sourceText }.lines()
        for (line in LineDiffer.diff(oldLines, newLines)) {
            when (line) {
                is DiffLine.Unchanged -> sb.appendLine("  ${line.text}")
                is DiffLine.Removed -> sb.appendLine("- ${line.text}")
                is DiffLine.Added -> sb.appendLine("+ ${line.text}")
            }
        }
    }

    private fun appendDocsTable(sb: StringBuilder, entry: DiffEntry) {
        val oldMeta = entry.oldRule?.metadata
        val newMeta = entry.newRule?.metadata
        sb.appendLine("| | Old | New |")
        sb.appendLine("|---|---|---|")
        sb.appendLine("| Title | ${cellText(oldMeta?.title)} | ${cellText(newMeta?.title)} |")
        sb.appendLine("| Description | ${cellText(oldMeta?.description)} | ${cellText(newMeta?.description)} |")
        sb.appendLine("| Frameworks | ${frameworksText(oldMeta?.frameworks)} | ${frameworksText(newMeta?.frameworks)} |")
    }

    private fun cellText(text: String?): String = text?.trimEnd()?.replace("\n", "<br>") ?: "—"

    private fun frameworksText(frameworks: List<String>?): String =
        frameworks?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "—"

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
