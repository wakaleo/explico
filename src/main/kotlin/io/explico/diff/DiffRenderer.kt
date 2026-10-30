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

    private val SECTION_ORDER = listOf(DiffCategory.REMOVED, DiffCategory.ADDED, DiffCategory.LOGIC_CHANGED, DiffCategory.DOCS_CHANGED)
    private val SUMMARY_ORDER = SECTION_ORDER + DiffCategory.UNCHANGED

    fun render(entries: List<DiffEntry>, oldPolicySet: PolicySet, newPolicySet: PolicySet): String {
        val sb = StringBuilder()
        sb.appendLine("# Policy diff report")
        sb.appendLine()
        sb.appendLine("*$DISCLAIMER*")
        sb.appendLine()
        appendSummary(sb, entries)
        val changed = entries.filter { it.category != DiffCategory.UNCHANGED }
            .sortedWith(compareBy({ SECTION_ORDER.indexOf(it.category) }, { it.controlId == null }, { it.controlId }))
        val qualified = ChangeSummary.qualifiedIds(entries)
        val anchors = ChangeAnchors(changed, qualified)
        appendWhatChanged(sb, changed, qualified, anchors)
        val isLinkable = linkableTargetsOf(changed, anchors)
        appendChanges(sb, changed, oldPolicySet, newPolicySet, isLinkable, qualified, anchors)
        appendCoverageWarning(sb, entries)
        return sb.toString()
    }

    /** Spec §14.42: a short, plain list before the full cards -- see [ChangeSummary]. Absent when [changed] is empty, same as `## Changes`. */
    private fun appendWhatChanged(sb: StringBuilder, changed: List<DiffEntry>, qualified: Set<String>, anchors: ChangeAnchors) {
        if (changed.isEmpty()) return
        sb.appendLine("## What changed")
        sb.appendLine()
        for (entry in changed) {
            sb.appendLine(ChangeSummary.bulletFor(entry, qualified, anchors))
            for (sub in ChangeSummary.subBulletsFor(entry)) sb.appendLine("  - $sub")
        }
        sb.appendLine()
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

    private fun appendChanges(sb: StringBuilder, changed: List<DiffEntry>, oldPolicySet: PolicySet, newPolicySet: PolicySet, isLinkable: (String, String) -> Boolean, qualified: Set<String>, anchors: ChangeAnchors) {
        if (changed.isEmpty()) return

        sb.appendLine("## Changes")
        sb.appendLine()
        for (entry in changed) {
            appendSection(sb, entry, oldPolicySet, newPolicySet, isLinkable, qualified, anchors)
            sb.appendLine("---")
            sb.appendLine()
        }
    }

    private fun appendSection(sb: StringBuilder, entry: DiffEntry, oldPolicySet: PolicySet, newPolicySet: PolicySet, isLinkable: (String, String) -> Boolean, qualified: Set<String>, anchors: ChangeAnchors) {
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

    private fun appendDocsTable(sb: StringBuilder, entry: DiffEntry) {
        val oldMeta = entry.oldRule?.metadata
        val newMeta = entry.newRule?.metadata
        sb.appendLine("| | Old | New |")
        sb.appendLine("|---|---|---|")
        sb.appendLine("| Title | ${cellText(oldMeta?.title)} | ${cellText(newMeta?.title)} |")
        sb.appendLine("| Description | ${cellText(oldMeta?.description)} | ${cellText(newMeta?.description)} |")
        sb.appendLine("| Frameworks | ${frameworksText(oldMeta?.frameworks)} | ${frameworksText(newMeta?.frameworks)} |")
        // Spec §14.43: rows for the passthrough fields only when they differ, so an edit that touches none of them renders
        // exactly the three rows it always did.
        val oldAuthors = oldMeta?.authors ?: emptyList()
        val newAuthors = newMeta?.authors ?: emptyList()
        if (oldAuthors != newAuthors) sb.appendLine("| Authors | ${authorsText(oldAuthors)} | ${authorsText(newAuthors)} |")
        val oldResources = oldMeta?.relatedResources ?: emptyList()
        val newResources = newMeta?.relatedResources ?: emptyList()
        if (oldResources != newResources) sb.appendLine("| Related resources | ${resourcesText(oldResources)} | ${resourcesText(newResources)} |")
        val oldCustom = oldMeta?.customFields ?: emptyMap()
        val newCustom = newMeta?.customFields ?: emptyMap()
        if (oldCustom != newCustom) sb.appendLine("| Custom fields | ${customText(oldCustom)} | ${customText(newCustom)} |")
        // Spec §14.53: the declared verdict, only when it differs (same rule as the passthrough rows above).
        val oldVerdict = oldMeta?.projection?.verdict
        val newVerdict = newMeta?.projection?.verdict
        if (oldVerdict != newVerdict) sb.appendLine("| Verdict | ${verdictText(oldVerdict)} | ${verdictText(newVerdict)} |")
        // Spec §14.54: likewise one row each for the declared actions, annotations and timing.
        val oldActions = oldMeta?.projection?.actions
        val newActions = newMeta?.projection?.actions
        if (oldActions != newActions) sb.appendLine("| Actions | ${actionsText(oldActions)} | ${actionsText(newActions)} |")
        val oldAnnotations = oldMeta?.projection?.annotations
        val newAnnotations = newMeta?.projection?.annotations
        if (oldAnnotations != newAnnotations) sb.appendLine("| Annotations | ${annotationsText(oldAnnotations)} | ${annotationsText(newAnnotations)} |")
        val oldTiming = oldMeta?.projection?.timing
        val newTiming = newMeta?.projection?.timing
        if (oldTiming != newTiming) sb.appendLine("| Timing | ${timingText(oldTiming)} | ${timingText(newTiming)} |")
        // Spec §14.61: the masked input paths, only when they differ.
        val oldMask = oldMeta?.projection?.mask
        val newMask = newMeta?.projection?.mask
        if (oldMask != newMask) sb.appendLine("| Mask | ${maskText(oldMask)} | ${maskText(newMask)} |")
    }

    private fun authorsText(authors: List<io.explico.model.Author>): String =
        authors.takeIf { it.isNotEmpty() }?.joinToString(", ") { a -> if (a.name != null && a.email != null) "${a.name} <${a.email}>" else a.name ?: a.email.orEmpty() } ?: "—"

    private fun resourcesText(resources: List<io.explico.model.RelatedResource>): String =
        resources.takeIf { it.isNotEmpty() }?.joinToString(", ") { r -> if (r.description != null) "${r.description} (${r.ref})" else r.ref } ?: "—"

    private fun verdictText(verdict: io.explico.model.VerdictProjection?): String =
        verdict?.let { "outcome `${it.outcome}`" + (it.reason?.let { r -> ", reason `$r`" } ?: "") } ?: "—"

    private fun actionsText(actions: io.explico.model.ActionsProjection?): String = actions?.let { "at `${it.at}`, label `${it.label}`" } ?: "—"

    private fun annotationsText(annotations: io.explico.model.AnnotationsProjection?): String = annotations?.let { "at `${it.at}`" } ?: "—"

    private fun maskText(mask: List<String>?): String = mask?.takeIf { it.isNotEmpty() }?.joinToString(", ") { "`$it`" } ?: "—"

    private fun timingText(timing: io.explico.model.TimingProjection?): String =
        timing?.let { listOfNotNull(it.dueBy?.let { f -> "dueBy `$f`" }, it.startBefore?.let { f -> "startBefore `$f`" }).joinToString(", ") } ?: "—"

    private fun customText(fields: Map<String, String>): String =
        fields.takeIf { it.isNotEmpty() }?.entries?.joinToString(", ") { "${it.key}: ${it.value}" } ?: "—"

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
