/**
 * Spec §14.78: one thing that changed in a LOGIC_CHANGED rule, as a TYPED fact. The "What changed" sentences, the Markdown and the JSON `details` are all rendered FROM a rule's
 * facts (one source, built by [ChangeSummary.factsFor]), so a workflow that filters on `kind` and a person who reads the sentence cannot disagree. A flat data class and a closed
 * [FactKind] -- no hierarchy; the factories below are the only places a sentence is worded.
 */
package io.explico.diff

/** The closed set of fact kinds; [wire] is the kebab-case name in the JSON (`facts[].kind`) and in docs/json-output.md. */
internal enum class FactKind(val wire: String) {
    CONDITION_REMOVED("condition-removed"),
    CONDITION_ADDED("condition-added"),
    MESSAGE_REMOVED("message-removed"),
    MESSAGE_ADDED("message-added"),
    BODIES_CHANGED("bodies-changed"),
    DEFAULT_OUTCOME_CHANGED("default-outcome-changed"),
    VALUE_CHANGED("value-changed"),
    SOURCE_FALLBACK_CHANGED("source-fallback-changed"),
    SITUATIONS_REORDERED("situations-reordered"),
    SITUATION_VALUE_CHANGED("situation-value-changed"),
    SITUATION_REMOVED("situation-removed"),
    SITUATION_ADDED("situation-added"),
    STRUCTURE_CHANGED("structure-changed"),
    REFERENCE_DATA_CHANGED("reference-data-changed"),
    DOCUMENTATION_CHANGED("documentation-changed"),
}

/**
 * [sentence] is the exact "What changed" line. The typed content, by kind: [text] is the condition phrase or the message alone; [oldCount]/[newCount] the body counts; [data]
 * the reference-data change (its `old`/`new` are `null` for a version in which the document is undefined, [kotlinx.serialization.json.JsonNull] for a real JSON null);
 * [fields] the documentation fields. The situation kinds and the plain ones carry the sentence only.
 */
internal data class ChangeFact(
    val kind: FactKind,
    val sentence: String,
    val text: String? = null,
    val oldCount: Int? = null,
    val newCount: Int? = null,
    val data: DataChange? = null,
    val fields: List<String> = emptyList(),
) {
    companion object {
        fun conditionRemoved(phrase: String) = ChangeFact(FactKind.CONDITION_REMOVED, "condition removed: $phrase", text = phrase)
        fun conditionAdded(phrase: String) = ChangeFact(FactKind.CONDITION_ADDED, "condition added: $phrase", text = phrase)
        fun messageRemoved(message: String) = ChangeFact(FactKind.MESSAGE_REMOVED, "message removed: \"$message\"", text = message)
        fun messageAdded(message: String) = ChangeFact(FactKind.MESSAGE_ADDED, "message added: \"$message\"", text = message)
        fun bodiesChanged(old: Int, new: Int) = ChangeFact(FactKind.BODIES_CHANGED, "bodies: $old -> $new", oldCount = old, newCount = new)
        fun defaultOutcomeChanged() = ChangeFact(FactKind.DEFAULT_OUTCOME_CHANGED, "default outcome changed")
        fun valueChanged() = ChangeFact(FactKind.VALUE_CHANGED, "value changed")
        fun sourceFallbackChanged() = ChangeFact(FactKind.SOURCE_FALLBACK_CHANGED, "code shown as source changed (see the source diff below)")
        fun structureChanged() = ChangeFact(FactKind.STRUCTURE_CHANGED, "the rule's structure changed; see the source diff below")
        fun referenceDataChanged(change: DataChange) = ChangeFact(FactKind.REFERENCE_DATA_CHANGED, ChangeSummary.dataChangeLine(change), data = change)
        fun documentationChanged(fields: List<String>) = ChangeFact(FactKind.DOCUMENTATION_CHANGED, "documentation changed: ${fields.joinToString(", ")}", fields = fields)

        // The situation kinds (spec §14.59) carry the sentence only: their parameters are free-form phrases not worth typing yet (disclosed).
        fun situationsReordered(sentence: String) = ChangeFact(FactKind.SITUATIONS_REORDERED, sentence)
        fun situationValueChanged(sentence: String) = ChangeFact(FactKind.SITUATION_VALUE_CHANGED, sentence)
        fun situationRemoved(sentence: String) = ChangeFact(FactKind.SITUATION_REMOVED, sentence)
        fun situationAdded(sentence: String) = ChangeFact(FactKind.SITUATION_ADDED, sentence)
    }
}
