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

    fun render(entries: List<DiffEntry>, dataChanges: DataChangeIndex = DataChangeIndex.NONE, exampleChanges: ExampleChangeIndex? = null, header: DiffHeader? = null): String {
        val counts = entries.groupingBy { it.category }.eachCount()
        val changed = entries.filter { it.category != DiffCategory.UNCHANGED }
            .sortedWith(ChangeSummary.REPORT_ORDER)
        val qualified = ChangeSummary.qualifiedIds(entries)
        val anchors = ChangeAnchors(changed, qualified)
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
            put("changes", buildJsonArray { changed.forEach { add(change(it, qualified, anchors, dataChanges, exampleChanges)) } })
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

    private fun change(entry: DiffEntry, qualified: Set<String>, anchors: ChangeAnchors, dataChanges: DataChangeIndex, exampleChanges: ExampleChangeIndex?) = buildJsonObject {
        val (packagePath, rule) = ChangeSummary.displayedTarget(entry)
        put("category", entry.category.name)
        put("controlId", entry.controlId?.let { JsonPrimitive(it) } ?: JsonNull)
        put("label", ChangeSummary.plainLabelOf(entry, qualified))
        put("package", packagePath)
        put("rule", rule.name)
        put("anchor", anchors.of(entry)?.let { JsonPrimitive(it) } ?: JsonNull)
        when (entry.category) {
            DiffCategory.LOGIC_CHANGED -> {
                put("details", buildJsonArray { ChangeSummary.subBulletsFor(entry, dataChanges.of(entry)).forEach { add(JsonPrimitive(it)) } })
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
