/**
 * The diff report's `## What changed` section (spec §14.42): a short, mechanically derived list for a change
 * approver who shouldn't have to read a full card and a source diff to learn what moved. Every line is derived
 * from the domain model the same way a card is -- never a guess, and never prose for something this can't
 * actually tell apart (see [subBulletsFor]'s fallback).
 */
package io.explico.diff

import io.explico.model.Condition
import io.explico.model.Operand
import io.explico.model.RuleGroup
import io.explico.render.ExpressionRenderer
import io.explico.render.MarkdownRenderer

internal object ChangeSummary {

    /** `- <label> — <what>` (spec §14.42): [DiffCategory.UNCHANGED] never reaches here -- the caller filters it out. */
    fun bulletFor(entry: DiffEntry): String {
        val (packagePath, rule) = targetOf(entry)
        val label = labelFor(entry.controlId, packagePath, rule)
        val text = when (entry.category) {
            DiffCategory.REMOVED -> "removed"
            DiffCategory.ADDED -> "added"
            DiffCategory.LOGIC_CHANGED -> "logic changed"
            DiffCategory.DOCS_CHANGED -> "documentation changed: ${changedMetadataFields(entry).joinToString(", ")}"
            DiffCategory.UNCHANGED -> error("UNCHANGED never reaches the change summary")
        }
        // REMOVED/ADDED/LOGIC_CHANGED each render a real card in this report (DiffRenderer.appendSection) at
        // this exact anchor; DOCS_CHANGED renders no card at all, so its label is plain text, never a dead link.
        val labelMarkup = if (entry.category == DiffCategory.DOCS_CHANGED) label else "[$label](#${MarkdownRenderer.anchorIdOf(rule, packagePath)})"
        return "- $labelMarkup — $text"
    }

    /** Indented LOGIC_CHANGED sub-bullets (spec §14.42); empty for every other category. */
    fun subBulletsFor(entry: DiffEntry): List<String> {
        if (entry.category != DiffCategory.LOGIC_CHANGED) return emptyList()
        val old = entry.oldRule!!
        val new = entry.newRule!!
        val bullets = mutableListOf<String>()

        val oldConditions = conditionPhrasesOf(old)
        val newConditions = conditionPhrasesOf(new)
        val newConditionSet = newConditions.toSet()
        val oldConditionSet = oldConditions.toSet()
        oldConditions.filterNot { it in newConditionSet }.forEach { bullets += "condition removed: $it" }
        newConditions.filterNot { it in oldConditionSet }.forEach { bullets += "condition added: $it" }

        val oldMessages = messagesOf(old)
        val newMessages = messagesOf(new)
        val newMessageSet = newMessages.toSet()
        val oldMessageSet = oldMessages.toSet()
        oldMessages.filterNot { it in newMessageSet }.forEach { bullets += "message removed: \"$it\"" }
        newMessages.filterNot { it in oldMessageSet }.forEach { bullets += "message added: \"$it\"" }

        if (old.bodies.size != new.bodies.size) bullets += "bodies: ${old.bodies.size} -> ${new.bodies.size}"
        if (old.default?.rendered != new.default?.rendered) bullets += "default outcome changed"
        if (old.constantValue?.rendered != new.constantValue?.rendered) bullets += "value changed"
        // A multiset comparison (sorted, not the original order): reordering the SAME fallback blocks between
        // old and new is not itself a change worth naming -- only a genuinely different set of them is.
        if (unrenderedSourceTextsOf(old).sorted() != unrenderedSourceTextsOf(new).sorted()) {
            bullets += "code shown as source changed (see the source diff below)"
        }

        // The canonical hash DID change (that's why this is LOGIC_CHANGED at all), but none of the mechanical
        // checks above named anything -- e.g. the same conditions regrouped across bodies. Never guess at what
        // moved; point at the one place that actually shows it.
        return bullets.ifEmpty { listOf("the rule's structure changed; see the source diff below") }
    }

    private fun targetOf(entry: DiffEntry): Pair<String, RuleGroup> = when (entry.category) {
        DiffCategory.REMOVED -> entry.oldPackage!!.path to entry.oldRule!!
        DiffCategory.ADDED, DiffCategory.LOGIC_CHANGED -> entry.newPackage!!.path to entry.newRule!!
        DiffCategory.DOCS_CHANGED -> (entry.newPackage ?: entry.oldPackage)!!.path to (entry.newRule ?: entry.oldRule)!!
        DiffCategory.UNCHANGED -> error("UNCHANGED never reaches the change summary")
    }

    /** The entry's label without markdown (also the JSON export's `label`). */
    fun plainLabelOf(entry: DiffEntry): String = targetOf(entry).let { (packagePath, rule) -> labelFor(entry.controlId, packagePath, rule) }

    /** The displayed side's package path and rule (also the JSON export's `package`/`rule`). */
    fun displayedTarget(entry: DiffEntry): Pair<String, RuleGroup> = targetOf(entry)

    /** The card anchor this entry's label links to; null for DOCS_CHANGED, which has no card in the report. */
    fun anchorOf(entry: DiffEntry): String? =
        if (entry.category == DiffCategory.DOCS_CHANGED) null else targetOf(entry).let { (packagePath, rule) -> MarkdownRenderer.anchorIdOf(rule, packagePath) }

    private fun labelFor(controlId: String?, packagePath: String, rule: RuleGroup): String =
        controlId?.let { "$it — ${rule.metadata?.title ?: rule.name}" } ?: "$packagePath.${rule.name}"

    /**
     * [Canonicalizer.canonicalMetadata] also hashes `controlId` -- but [PolicyDiff]'s own identity resolution
     * (keyed by `controlId ?: "$package.$name"`) guarantees old and new control-ids are equal whenever a
     * DiffEntry carries both sides at all (a changed control-id is a different identity -- REMOVED+ADDED, never
     * this entry), so `controlId` never needs checking here: title/description/frameworks always account for
     * every real DOCS_CHANGED difference.
     */
    internal fun changedMetadataFields(entry: DiffEntry): List<String> {
        val old = entry.oldRule?.metadata
        val new = entry.newRule?.metadata
        val fields = mutableListOf<String>()
        if (old?.title != new?.title) fields += "title"
        if (old?.description != new?.description) fields += "description"
        if (old?.frameworks != new?.frameworks) fields += "frameworks"
        if ((old?.authors.orEmpty()) != (new?.authors.orEmpty())) fields += "authors"
        if ((old?.relatedResources.orEmpty()) != (new?.relatedResources.orEmpty())) fields += "related resources"
        if ((old?.customFields ?: emptyMap<String, String>()) != (new?.customFields ?: emptyMap<String, String>())) fields += "custom fields"
        return fields
    }

    private val NO_RULE_LINK: (Condition.RuleReference) -> String? = { null }
    private val NO_FUNCTION_LINK: (Condition.FunctionCall) -> String? = { null }
    private val NO_RULE_VALUE_LINK: (Operand.RuleValueReference) -> String? = { null }

    /**
     * Every CLASSIFIED condition across [rule]'s bodies, rendered exactly as its own card would word it but with
     * every link stripped (spec §14.42: "no links" -- a change approver compares text, not markup), deduped and
     * in first-appearance order. [Condition.Unrendered] is excluded -- it has no phrase ([ExpressionRenderer.render]
     * doesn't handle it at all -- see [unrenderedSourceTextsOf] for how that case is covered instead.
     */
    private fun conditionPhrasesOf(rule: RuleGroup): List<String> {
        val seen = LinkedHashSet<String>()
        for (body in rule.bodies) {
            for (condition in body.conditions) {
                if (condition is Condition.Unrendered) continue
                seen.add(ExpressionRenderer.render(condition, NO_RULE_LINK, NO_FUNCTION_LINK, NO_RULE_VALUE_LINK))
            }
        }
        return seen.toList()
    }

    private fun messagesOf(rule: RuleGroup): List<String> = rule.bodies.mapNotNull { it.producesValue }

    private fun unrenderedSourceTextsOf(rule: RuleGroup): List<String> =
        rule.bodies.flatMap { body -> body.conditions.filterIsInstance<Condition.Unrendered>().map { it.sourceText.trim() } }
}
