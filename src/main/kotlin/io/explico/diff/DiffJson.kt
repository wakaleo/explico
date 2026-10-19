/**
 * The machine-readable change report (spec §14.45, schema `explico/diff/1`). Built from the same [DiffEntry] list and the same
 * [ChangeSummary] strings the Markdown "What changed" section uses, so the two cannot disagree.
 */
package io.explico.diff

import io.explico.render.JsonExport
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object DiffJson {

    const val SCHEMA = "explico/diff/1"

    private val SECTION_ORDER = listOf(DiffCategory.REMOVED, DiffCategory.ADDED, DiffCategory.LOGIC_CHANGED, DiffCategory.DOCS_CHANGED)

    fun render(entries: List<DiffEntry>): String {
        val counts = entries.groupingBy { it.category }.eachCount()
        val changed = entries.filter { it.category != DiffCategory.UNCHANGED }
            .sortedWith(compareBy({ SECTION_ORDER.indexOf(it.category) }, { it.controlId == null }, { it.controlId }))
        val document = buildJsonObject {
            put("schema", SCHEMA)
            put("summary", buildJsonObject {
                for (category in SECTION_ORDER + DiffCategory.UNCHANGED) put(category.name, counts[category] ?: 0)
            })
            put("changes", buildJsonArray { changed.forEach { add(change(it)) } })
        }
        return JsonExport.prettyJson(document)
    }

    private fun change(entry: DiffEntry) = buildJsonObject {
        val (packagePath, rule) = ChangeSummary.displayedTarget(entry)
        put("category", entry.category.name)
        put("controlId", entry.controlId?.let { JsonPrimitive(it) } ?: JsonNull)
        put("label", ChangeSummary.plainLabelOf(entry))
        put("package", packagePath)
        put("rule", rule.name)
        put("anchor", ChangeSummary.anchorOf(entry)?.let { JsonPrimitive(it) } ?: JsonNull)
        when (entry.category) {
            DiffCategory.LOGIC_CHANGED -> put("details", buildJsonArray { ChangeSummary.subBulletsFor(entry).forEach { add(JsonPrimitive(it)) } })
            DiffCategory.DOCS_CHANGED -> put("fields", buildJsonArray { ChangeSummary.changedMetadataFields(entry).forEach { add(JsonPrimitive(it)) } })
            else -> Unit
        }
    }
}
