/**
 * Acceptance tests for typed facts in the diff JSON: the `details` of a LOGIC_CHANGED change are English sentences, so an approval or ticketing workflow cannot filter on
 * "a threshold changed" or "an exemption removed" without parsing them. Authored from the control-owner journey review (item 6), NOT from the frozen acceptance-pack README.
 * Decisions (to be confirmed; pre-1.0, no known JSON consumer, so this is the moment to settle the shape):
 *
 * - ADDITIVE: the schema stays `explico/diff/1`; `details` and every other key are unchanged. A LOGIC_CHANGED change (and only that category) gains `facts`, an array PARALLEL to
 *   `details`: same length, same order, `facts[i].sentence == details[i]`, each fact an object with a `kind` from a CLOSED set of 15.
 * - Kind-specific fields: `condition-*` {condition} and `message-*` {message} (the phrase alone, no prefix); `bodies-changed` {old, new} (numbers); `reference-data-changed`
 *   {path (breadcrumb without backticks), old, new (real JSON values; a key is OMITTED when that version has the document undefined)}; `documentation-changed` {fields}.
 *   The four situation kinds, `structure-changed`, `default-outcome-changed`, `value-changed` and `source-fallback-changed` carry the sentence only.
 * - The Markdown report is byte-identical (the committed diff-all-categories golden pins it).
 * Fixtures (reused, not copied): `diff/change-summary`, `diff/diff-all-categories`, `realistic/metadata-with-logic` (+ data), `realistic/diff-situations`, `realistic/data-aware-diff`
 * (an undefined side), `realistic/fixture-verdict-diff` (examples) and the new small `realistic/diff-facts` (the two shapes no other pair produces). Facade only.
 */
package io.explico.acceptance

import io.explico.DiffReport
import io.explico.Explico
import io.explico.opa.OpaRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

@DisplayName("Realistic policies: typed facts in the diff JSON")
class RealisticDiffFactsAcceptanceIT {

    companion object {
        private val resources = Path.of("src/test/resources")

        private val closedSet = setOf(
            "condition-removed", "condition-added", "message-removed", "message-added", "bodies-changed", "default-outcome-changed", "value-changed",
            "source-fallback-changed", "situations-reordered", "situation-value-changed", "situation-removed", "situation-added", "structure-changed",
            "reference-data-changed", "documentation-changed",
        )

        private fun diff(old: Path, new: Path, dataOld: Path? = null, dataNew: Path? = null): DiffReport =
            Explico.diff(Explico.load(old), Explico.load(new), dataOld = dataOld, dataNew = dataNew)

        private lateinit var reports: Map<String, DiffReport>
        private lateinit var metadataAgain: DiffReport
        private lateinit var withExamples: DiffReport

        @JvmStatic
        @BeforeAll
        fun renderAll() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            val meta = resources.resolve("realistic/metadata-with-logic")
            val dataAware = resources.resolve("realistic/data-aware-diff")
            reports = linkedMapOf(
                "change-summary" to diff(resources.resolve("diff/change-summary/old"), resources.resolve("diff/change-summary/new")),
                "diff-all-categories" to diff(resources.resolve("diff/diff-all-categories/old"), resources.resolve("diff/diff-all-categories/new")),
                "metadata-with-logic" to diff(meta.resolve("old"), meta.resolve("new"), meta.resolve("data/old.json"), meta.resolve("data/new.json")),
                "diff-situations" to diff(resources.resolve("realistic/diff-situations/old"), resources.resolve("realistic/diff-situations/new")),
                "diff-facts" to diff(resources.resolve("realistic/diff-facts/old"), resources.resolve("realistic/diff-facts/new")),
                "undefined-side" to diff(dataAware.resolve("policies/old"), dataAware.resolve("policies/old"), dataAware.resolve("data/old.json"), dataAware.resolve("data/list-removed-entirely.json")),
            )
            metadataAgain = diff(meta.resolve("old"), meta.resolve("new"), meta.resolve("data/old.json"), meta.resolve("data/new.json"))
            val verdict = resources.resolve("realistic/fixture-verdict-diff")
            withExamples = Explico.diff(
                Explico.load(verdict.resolve("policies/old")), Explico.load(verdict.resolve("policies/new")),
                examples = Explico.loadExamples(verdict.resolve("examples")), oldPolicyDir = verdict.resolve("policies/old"), newPolicyDir = verdict.resolve("policies/new"),
            )
        }

        private fun changes(report: DiffReport): List<JsonObject> = Json.parseToJsonElement(report.json).jsonObject.getValue("changes").jsonArray.map { it.jsonObject }

        private fun change(report: DiffReport, id: String): JsonObject {
            val found = changes(report).singleOrNull { it["controlId"]?.jsonPrimitive?.content == id }
            assertThat(found).describedAs("a JSON change for %s", id).isNotNull()
            return found!!
        }

        private fun field(obj: JsonObject, key: String): kotlinx.serialization.json.JsonElement {
            assertThat(obj).describedAs("the JSON object").containsKey(key)
            return obj.getValue(key)
        }

        private fun facts(change: JsonObject): List<JsonObject> = field(change, "facts").jsonArray.map { it.jsonObject }

        private fun kinds(report: DiffReport, id: String): List<String> = facts(change(report, id)).map { field(it, "kind").jsonPrimitive.content }

        private fun details(change: JsonObject): List<String> = field(change, "details").jsonArray.map { it.jsonPrimitive.content }

        private fun fact(report: DiffReport, id: String, kind: String): JsonObject {
            val matching = facts(change(report, id)).filter { field(it, "kind").jsonPrimitive.content == kind }
            assertThat(matching).describedAs("a '%s' fact for %s", kind, id).hasSize(1)
            return matching.single()
        }

        private fun strings(array: JsonArray) = array.map { it.jsonPrimitive.content }
    }

    @Nested
    @DisplayName("The parallel array")
    inner class Parallel {

        @Test
        @DisplayName("every LOGIC_CHANGED change in every fixture pair has facts as long as its details, with facts[i].sentence == details[i] and a kind from the closed set")
        fun parallel() {
            var logicChanged = 0
            for ((name, report) in reports + ("fixture-verdict-diff" to withExamples)) for (change in changes(report).filter { it["category"]?.jsonPrimitive?.content == "LOGIC_CHANGED" }) {
                logicChanged++
                val label = "$name/${change["controlId"]?.jsonPrimitive?.content}"
                val facts = facts(change)
                assertThat(facts.map { field(it, "sentence").jsonPrimitive.content }).describedAs(label).isEqualTo(details(change))
                assertThat(facts.map { field(it, "kind").jsonPrimitive.content }).describedAs(label).isSubsetOf(closedSet)
            }
            assertThat(logicChanged).describedAs("LOGIC_CHANGED changes checked").isGreaterThan(15)
        }

        @Test
        @DisplayName("facts are only on LOGIC_CHANGED changes: ADDED, REMOVED and DOCS_CHANGED keep exactly their keys")
        fun onlyLogicChanged() {
            val all = reports.values.flatMap { changes(it) }
            assertThat(all.map { it["category"]?.jsonPrimitive?.content }).contains("ADDED", "REMOVED", "DOCS_CHANGED", "LOGIC_CHANGED")
            for (change in all.filter { it["category"]?.jsonPrimitive?.content != "LOGIC_CHANGED" }) assertThat(change).doesNotContainKey("facts")
            assertThat(change(reports.getValue("metadata-with-logic"), "PAY-DOC-001")).containsKey("fields").doesNotContainKey("details")
        }

        @Test
        @DisplayName("the other keys are untouched: details, fields, examples and compared stay where they were, and the schema is still explico/diff/1")
        fun otherKeys() {
            val rel = change(reports.getValue("metadata-with-logic"), "PAY-REL-001")
            assertThat(rel.keys).contains("details", "fields", "facts")
            assertThat(withExamples.json).contains("\"examples\"")
            val exm = change(withExamples, "BANK-EXM-001")
            assertThat(exm.keys).contains("details", "examples", "facts")
            for (report in reports.values + withExamples) assertThat(Json.parseToJsonElement(report.json).jsonObject.getValue("schema").jsonPrimitive.content).isEqualTo("explico/diff/1")
        }
    }

    @Nested
    @DisplayName("The kinds, per shape")
    inner class Kinds {

        @Test
        @DisplayName("change-summary: one rule per shape, with exactly its kinds in the order of its detail lines")
        fun changeSummaryShapes() {
            val r = reports.getValue("change-summary")
            assertThat(kinds(r, "CS-001")).containsExactly("condition-added", "message-added", "bodies-changed")
            assertThat(kinds(r, "CS-002")).containsExactly("message-removed", "message-added")
            assertThat(kinds(r, "CS-003")).containsExactly("default-outcome-changed")
            assertThat(kinds(r, "CS-004")).containsExactly("condition-removed", "condition-added")
            assertThat(kinds(r, "CS-005")).containsExactly("structure-changed")
        }

        @Test
        @DisplayName("diff-all-categories and metadata-with-logic: the condition, data and documentation facts, documentation always last")
        fun otherPairs() {
            assertThat(kinds(reports.getValue("diff-all-categories"), "REL-003")).containsExactly("condition-removed", "condition-added")
            val m = reports.getValue("metadata-with-logic")
            assertThat(kinds(m, "PAY-LIM-001")).containsExactly("reference-data-changed", "documentation-changed")
            assertThat(kinds(m, "PAY-REL-001")).containsExactly("condition-removed", "documentation-changed")
            assertThat(kinds(m, "PAY-SAN-001")).containsExactly("condition-added")
        }

        @Test
        @DisplayName("diff-situations: reordered, value changed (with and without a situation number), and a replaced branch; diff-facts: a constant value and code shown as source")
        fun situationsAndTheRest() {
            val s = reports.getValue("diff-situations")
            assertThat(kinds(s, "SIT-1")).containsExactly("situations-reordered")
            assertThat(kinds(s, "SIT-2")).containsExactly("situation-value-changed")
            assertThat(kinds(s, "SIT-5")).containsExactly("situation-value-changed")
            assertThat(kinds(s, "SIT-6")).containsExactly("situation-value-changed")
            assertThat(kinds(s, "SIT-3")).containsExactlyInAnyOrder("condition-removed", "condition-added", "situation-removed", "situation-added")
            val f = reports.getValue("diff-facts")
            assertThat(kinds(f, "DF-1")).containsExactly("value-changed")
            assertThat(kinds(f, "DF-2")).containsExactly("source-fallback-changed")
        }

        @Test
        @DisplayName("the closed set is exactly what the fixtures produce: every one of the 15 kinds is exercised, and no other kind appears")
        fun closedSetIsExercised() {
            val observed = (reports.values + withExamples).flatMap { report -> changes(report).flatMap { c -> if ("facts" in c) facts(c) else emptyList() } }
                .map { field(it, "kind").jsonPrimitive.content }.toSet()

            assertThat(observed).isEqualTo(closedSet)
        }
    }

    @Nested
    @DisplayName("The fields of a fact")
    inner class Fields {

        @Test
        @DisplayName("a condition or message fact carries the phrase alone: the sentence minus its prefix, no quotes around a message")
        fun phraseAlone() {
            val cs = reports.getValue("change-summary")
            assertThat(field(fact(cs, "CS-001", "condition-added"), "condition").jsonPrimitive.content).isEqualTo("`b` is `2`")
            assertThat(field(fact(cs, "CS-001", "message-added"), "message").jsonPrimitive.content).isEqualTo("b is two")
            assertThat(field(fact(cs, "CS-002", "message-removed"), "message").jsonPrimitive.content).isEqualTo("old wording")
            assertThat(field(fact(cs, "CS-002", "message-added"), "message").jsonPrimitive.content).isEqualTo("new wording")
            assertThat(field(fact(reports.getValue("diff-all-categories"), "REL-003", "condition-removed"), "condition").jsonPrimitive.content)
                .isEqualTo("`artifact ▸ source branch` does not start with `\"release/\"`")
            for (report in reports.values) for (c in changes(report).filter { "facts" in it }) for (f in facts(c)) {
                val kind = field(f, "kind").jsonPrimitive.content
                val sentence = field(f, "sentence").jsonPrimitive.content
                if (kind.startsWith("condition-")) assertThat(sentence).isEqualTo("condition ${kind.removePrefix("condition-")}: ${field(f, "condition").jsonPrimitive.content}")
                if (kind.startsWith("message-")) assertThat(sentence).isEqualTo("message ${kind.removePrefix("message-")}: \"${field(f, "message").jsonPrimitive.content}\"")
            }
        }

        @Test
        @DisplayName("bodies-changed carries the old and the new count as numbers, not strings")
        fun bodies() {
            val f = fact(reports.getValue("change-summary"), "CS-001", "bodies-changed")

            assertThat(field(f, "old").jsonPrimitive.isString).isFalse()
            assertThat(field(f, "old").jsonPrimitive.content).isEqualTo("1")
            assertThat(field(f, "new").jsonPrimitive.content).isEqualTo("2")
        }

        @Test
        @DisplayName("reference-data-changed: the breadcrumb without backticks, and the old and new documents as real JSON values")
        fun referenceData() {
            val f = fact(reports.getValue("metadata-with-logic"), "PAY-LIM-001", "reference-data-changed")

            assertThat(field(f, "path").jsonPrimitive.content).isEqualTo("data ▸ limits ▸ max")
            assertThat(field(f, "old")).isInstanceOf(JsonObject::class.java)
            assertThat(field(f, "old").toString()).isEqualTo("{\"EUR\":100000}")
            assertThat(field(f, "new").toString()).isEqualTo("{\"EUR\":50000}")
        }

        @Test
        @DisplayName("a document undefined in one version has no key for that side: old is the list, new is absent")
        fun undefinedSide() {
            val f = fact(reports.getValue("undefined-side"), "BANK-HRC-001", "reference-data-changed")

            assertThat(field(f, "path").jsonPrimitive.content).isEqualTo("data ▸ high risk countries")
            assertThat(field(f, "old")).isInstanceOf(JsonArray::class.java)
            assertThat(strings(field(f, "old").jsonArray)).containsExactly("IR", "KP")
            assertThat(f).doesNotContainKey("new")
        }

        @Test
        @DisplayName("documentation-changed carries the same field names as the change's fields, and is the LAST fact")
        fun documentation() {
            val m = reports.getValue("metadata-with-logic")
            val rel = change(m, "PAY-REL-001")
            val f = facts(rel).last()

            assertThat(field(f, "kind").jsonPrimitive.content).isEqualTo("documentation-changed")
            assertThat(strings(field(f, "fields").jsonArray)).containsExactly("description", "frameworks", "custom fields")
            assertThat(strings(field(f, "fields").jsonArray)).isEqualTo(strings(field(rel, "fields").jsonArray))
            assertThat(field(facts(change(m, "PAY-LIM-001")).last(), "kind").jsonPrimitive.content).isEqualTo("documentation-changed")
        }
    }

    @Nested
    @DisplayName("What stays the same")
    inner class Unchanged {

        @Test
        @DisplayName("the Markdown report is byte-identical: the committed diff-all-categories golden")
        fun markdownUnchanged() {
            val dir = resources.resolve("diff/diff-all-categories")

            assertThat(reports.getValue("diff-all-categories").markdown).isEqualTo(Files.readString(dir.resolve("expected/diff-report.md")))
        }

        @Test
        @DisplayName("two runs are byte-identical, Markdown and JSON")
        fun deterministic() {
            val first = reports.getValue("metadata-with-logic")

            assertThat(metadataAgain.json).isEqualTo(first.json)
            assertThat(metadataAgain.markdown).isEqualTo(first.markdown)
        }
    }
}
