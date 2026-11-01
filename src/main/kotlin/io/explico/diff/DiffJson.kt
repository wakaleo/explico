/**
 * The machine-readable change report (spec §14.45, schema `explico/diff/1`). Built from the same [DiffEntry] list and the same
 * [ChangeSummary] strings the Markdown "What changed" section uses, so the two cannot disagree.
 */
package io.explico.diff

import io.explico.render.JsonExport
import io.explico.render.PathHumanizer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object DiffJson {

    const val SCHEMA = "explico/diff/1"

    private val SECTION_ORDER = listOf(DiffCategory.REMOVED, DiffCategory.ADDED, DiffCategory.LOGIC_CHANGED, DiffCategory.DOCS_CHANGED)

    fun render(entries: List<DiffEntry>, dataChanges: DataChangeIndex = DataChangeIndex.NONE, exampleChanges: ExampleChangeIndex? = null, header: DiffHeader? = null): String {
        val counts = entries.groupingBy { it.category }.eachCount()
        val changed = ChangedSet.of(entries)
        val document = buildJsonObject {
            put("schema", SCHEMA)
            // Spec §14.76: which two versions were compared (only when the policy directories were given).
            if (header != null) {
                put("compared", buildJsonObject {
                    put("old", buildJsonObject { put("digest", header.oldDigest); header.oldLabel?.let { put("label", it) } })
                    put("new", buildJsonObject { put("digest", header.newDigest); header.newLabel?.let { put("label", it) } })
                })
            }
            put("summary", buildJsonObject {
                for (category in SECTION_ORDER + DiffCategory.UNCHANGED) put(category.name, counts[category] ?: 0)
            })
            put("changes", buildJsonArray { changed.entries.forEach { add(change(it, changed.changeOf(it), dataChanges, exampleChanges)) } })
            // Spec §14.74: what the example comparison covered (only when examples were supplied).
            if (exampleChanges != null) {
                val c = exampleChanges.comparison
                put("exampleComparison", buildJsonObject {
                    put("compared", c.compared); put("total", c.total); put("functions", c.functions)
                    put("unattributable", c.unattributable); put("fixturesOnOneVersion", c.fixturesOnOneVersion)
                })
            }
        }
        return JsonExport.prettyJson(document)
    }

    /** Spec §14.78: `kind` and `sentence`, plus the typed fields of the kind. */
    private fun factJson(fact: ChangeFact): JsonObject = buildJsonObject {
        put("kind", fact.kind.wire)
        put("sentence", fact.sentence)
        when (fact.kind) {
            FactKind.CONDITION_REMOVED, FactKind.CONDITION_ADDED -> put("condition", fact.text.orEmpty())
            FactKind.MESSAGE_REMOVED, FactKind.MESSAGE_ADDED -> put("message", fact.text.orEmpty())
            FactKind.BODIES_CHANGED -> { put("old", fact.oldCount!!); put("new", fact.newCount!!) }
            FactKind.REFERENCE_DATA_CHANGED -> {
                val change = fact.data!!
                // The breadcrumb as the Reference data block shows it, without its code-span backticks. A version in which the document is undefined has NO key.
                put("path", PathHumanizer.humanize(change.path.segments).rendered.removeSurrounding("`"))
                change.old?.let { put("old", it) }
                change.new?.let { put("new", it) }
            }
            FactKind.DOCUMENTATION_CHANGED -> put("fields", buildJsonArray { fact.fields.forEach { add(JsonPrimitive(it)) } })
            else -> Unit
        }
    }

    private fun change(entry: DiffEntry, change: DiffChange, dataChanges: DataChangeIndex, exampleChanges: ExampleChangeIndex?) = buildJsonObject {
        put("category", change.category.name)
        put("controlId", change.controlId?.let { JsonPrimitive(it) } ?: JsonNull)
        put("label", change.label)
        put("package", change.packagePath)
        put("rule", change.ruleName)
        put("anchor", change.anchor?.let { JsonPrimitive(it) } ?: JsonNull)
        when (entry.category) {
            DiffCategory.LOGIC_CHANGED -> {
                // Spec §14.78: `details` are the sentences of the typed `facts`, one source.
                val facts = ChangeSummary.factsFor(entry, dataChanges.of(entry))
                put("details", buildJsonArray { facts.forEach { add(JsonPrimitive(it.sentence)) } })
                put("facts", buildJsonArray { facts.forEach { add(factJson(it)) } })
                // Spec §14.75: the documentation fields that changed with the logic -- only when some did.
                val fields = ChangeSummary.changedMetadataFields(entry)
                if (fields.isNotEmpty()) put("fields", buildJsonArray { fields.forEach { add(JsonPrimitive(it)) } })
            }
            DiffCategory.DOCS_CHANGED -> put("fields", buildJsonArray { ChangeSummary.changedMetadataFields(entry).forEach { add(JsonPrimitive(it)) } })
            else -> Unit
        }
        // Spec §14.74: only when example fixtures were supplied (an empty array when none of this control's outcomes moved).
        if (exampleChanges != null) {
            put("examples", buildJsonArray {
                exampleChanges.of(entry).forEach { c -> add(buildJsonObject { put("fixture", c.fixture); put("old", c.old); put("new", c.new) }) }
            })
        }
    }
}
