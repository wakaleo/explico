/**
 * Acceptance tests for the first quality-hints increment: the index's "Documentation quality" section (and the `quality` object of `explico.json`) gains TWO findings
 * that point at the fix, both computed from the model and the evaluated worked examples only, for CONTROLS only (a grouped control counts once):
 *
 * - "No matching example": a control no fixture makes match -- none of its worked examples is a match (or it has none at all). Hint: add a fixture that makes it match.
 * - "Raw decision object": a control that declares no `custom.verdict` and whose evaluated value in at least one fixture is a JSON object, so its examples show raw JSON.
 *   Hint: declare `custom.verdict`. Not for a rule that declares one (even a wrongly shaped one), not for a function, not for a partial-object rule (all-`true` values).
 * Both appear only when example fixtures were supplied (without them every control would be listed), are NOT gaps (`--fail-on-gaps` / `RenderedDocs.gaps` are unchanged), the
 * existing bullets keep their wording, and a policy set with nothing to report still has no section at all. Output of an unaffected render is byte-identical.
 * Decided here (to be confirmed): "No matching example" rather than "no example", because a rule shown against fixtures always has examples (a "not matched" one) -- what a
 * reader is missing is a fixture that makes it hold; the index's own "Example coverage" column already uses the same two sides.
 * Authored from the plan, NOT from the frozen acceptance-pack README. Facade only. Fixtures: `realistic/quality-hints/` (QH-1 matched, QH-2 never matched, QH-3 raw object,
 * QH-4 declared verdict, QH-5 boolean, QH-6 function, QH-7 partial object; `grouped/`: two rules of control QH-G).
 */
package io.explico.acceptance

import io.explico.Explico
import io.explico.RenderOptions
import io.explico.opa.OpaRunner
import kotlinx.serialization.json.Json
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

@DisplayName("Realistic policies: documentation-quality hints (no matching example, raw decision object)")
class RealisticQualityHintsAcceptanceIT {

    companion object {
        private val base = Path.of("src/test/resources/realistic/quality-hints")
        private val policies = base.resolve("policies")
        private val grouped = base.resolve("grouped/policies")

        private const val NO_MATCH = "**No matching example"
        private const val RAW = "**Raw decision object"
        private const val NO_MATCH_HINT = "— add a fixture that makes it match"
        private const val RAW_HINT = "— declare custom.verdict (outcome, reason) in its METADATA so its examples read as verdicts instead of raw JSON"

        private lateinit var withExamples: Map<String, String>
        private lateinit var withExamplesAgain: Map<String, String>
        private lateinit var withoutExamples: Map<String, String>
        private lateinit var groupedFiles: Map<String, String>
        private lateinit var groupedGaps: List<String>
        private lateinit var withExamplesGaps: List<String>

        private fun render(dir: Path, examples: Path?, group: Boolean = false): io.explico.RenderedDocs {
            val set = Explico.load(dir)
            val ex = examples?.let { Explico.loadExamples(it) }
            return Explico.render(set, RenderOptions(examples = ex, json = true, groupByControl = group))
        }

        @JvmStatic
        @BeforeAll
        fun renderAll() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            val first = render(policies, base.resolve("examples"))
            withExamples = first.files
            withExamplesGaps = first.gaps
            withExamplesAgain = render(policies, base.resolve("examples")).files
            withoutExamples = render(policies, null).files
            val g = render(grouped, base.resolve("grouped/examples"), group = true)
            groupedFiles = g.files
            groupedGaps = g.gaps
        }

        /** The "Documentation quality" bullets of [files]' index (empty when the section is absent). */
        private fun qualityLines(files: Map<String, String>): List<String> {
            val lines = files.getValue("index.md").lines()
            val start = lines.indexOf("## Documentation quality")
            if (start < 0) return emptyList()
            return lines.drop(start + 1).takeWhile { !it.startsWith("## ") }.filter { it.startsWith("- ") }
        }

        private fun bullet(files: Map<String, String>, marker: String): String? = qualityLines(files).singleOrNull { it.startsWith("- $marker") }

        private fun quality(files: Map<String, String>): JsonObject =
            Json.parseToJsonElement(files.getValue("explico.json")).jsonObject.getValue("quality").jsonObject

        private fun labels(json: JsonObject, key: String): List<String> {
            assertThat(json).describedAs("the quality object").containsKey(key)
            return json.getValue(key).jsonArray.map { it.jsonPrimitive.content }
        }
    }

    @Nested
    @DisplayName("No matching example")
    inner class NoMatchingExample {

        @Test
        @DisplayName("QH-2, which no fixture makes match, is listed with a link to its card and the fix hint")
        fun listed() {
            val line = bullet(withExamples, "$NO_MATCH (1):")
            assertThat(line).isNotNull()
            assertThat(line).contains("[QH-2](qh.md#qh-2)").endsWith(NO_MATCH_HINT)
        }

        @Test
        @DisplayName("a control with a matching example, a boolean rule, a function and a partial-object rule are not listed")
        fun othersNotListed() {
            val line = bullet(withExamples, "$NO_MATCH (1):")
            assertThat(line).isNotNull()
            for (id in listOf("QH-1", "QH-3", "QH-4", "QH-5", "QH-6", "QH-7")) assertThat(line).describedAs(id).doesNotContain(id)
        }
    }

    @Nested
    @DisplayName("Raw decision object")
    inner class RawDecisionObject {

        @Test
        @DisplayName("QH-3 (an object value, no declared verdict) is listed with a link and the fix hint")
        fun listed() {
            val line = bullet(withExamples, "$RAW (1):")
            assertThat(line).isNotNull()
            assertThat(line).contains("[QH-3](qh.md#qh-3)").endsWith(RAW_HINT)
        }

        @Test
        @DisplayName("QH-4 declares custom.verdict, QH-1/QH-5 are not objects, QH-6 is a function and QH-7 is a partial-object rule: none is listed")
        fun othersNotListed() {
            val line = bullet(withExamples, "$RAW (1):")
            assertThat(line).isNotNull()
            for (id in listOf("QH-1", "QH-2", "QH-4", "QH-5", "QH-6", "QH-7")) assertThat(line).describedAs(id).doesNotContain(id)
        }

        @Test
        @DisplayName("a control split over two rules (--group-by-control) that both return an object is listed ONCE")
        fun groupedCountsOnce() {
            val line = bullet(groupedFiles, "$RAW (1):")
            assertThat(line).isNotNull()
            assertThat(line!!.split("QH-G").size - 1).describedAs("mentions of the label QH-G").isEqualTo(1)
            assertThat(line).contains("[QH-G](controls/qh-g.md#qh-g)")
        }
    }

    @Nested
    @DisplayName("Boundaries")
    inner class Boundaries {

        @Test
        @DisplayName("without example fixtures neither finding appears; the one thing left to report is QH-7's unrendered value (spec §14.71), not an example finding")
        fun noExamplesNoFindings() {
            val index = withoutExamples.getValue("index.md")
            assertThat(index).doesNotContain("No matching example").doesNotContain("Raw decision object")
            assertThat(qualityLines(withoutExamples)).hasSize(1)
            assertThat(qualityLines(withoutExamples).single()).startsWith("- **Values shown as source (1):**")
        }

        @Test
        @DisplayName("the hints are findings, not gaps: --fail-on-gaps / RenderedDocs.gaps list nothing for them")
        fun notGaps() {
            assertThat(withExamplesGaps).isEmpty()
            assertThat(groupedGaps).isEmpty()
        }

        @Test
        @DisplayName("the section starts with the new bullets only when there is something to report, and keeps the existing ones above them (QH-7's unrendered value, spec §14.71, follows)")
        fun existingBulletsKeepTheirPlace() {
            val lines = qualityLines(withExamples)
            assertThat(lines).hasSize(3)
            assertThat(lines[0]).startsWith("- $NO_MATCH")
            assertThat(lines[1]).startsWith("- $RAW")
            assertThat(lines[2]).startsWith("- **Values shown as source (1):**")
        }

        @Test
        @DisplayName("two renders are byte-identical")
        fun deterministic() {
            assertThat(withExamplesAgain.toList()).isEqualTo(withExamples.toList())
        }

        @Test
        @DisplayName("the five-package sample (docs/sample-output) renders byte-for-byte as committed")
        fun sampleOutputUntouched() {
            val dir = Path.of("samples/policies")
            val docs = Explico.render(Explico.load(dir), RenderOptions(examples = Explico.loadExamples(Path.of("samples/examples")), dataDir = Path.of("samples/data/release/data.json"))).files
            assertThat(docs).isNotEmpty()
            for ((name, text) in docs) {
                assertThat(text).describedAs("docs/sample-output/$name").isEqualTo(Files.readString(Path.of("docs/sample-output").resolve(name)))
            }
        }
    }

    @Nested
    @DisplayName("explico.json")
    inner class InTheJson {

        @Test
        @DisplayName("quality.noMatchingExample and quality.rawDecisionObject list the same controls as the index")
        fun sameAsTheIndex() {
            val q = quality(withExamples)
            assertThat(labels(q, "noMatchingExample")).containsExactly("QH-2")
            assertThat(labels(q, "rawDecisionObject")).containsExactly("QH-3")
            assertThat(labels(quality(groupedFiles), "rawDecisionObject")).containsExactly("QH-G")
        }

        @Test
        @DisplayName("both keys are always present: empty arrays when no example fixtures were given")
        fun alwaysPresent() {
            val q = quality(withoutExamples)
            assertThat(q).containsKeys("noMatchingExample", "rawDecisionObject")
            assertThat(labels(q, "noMatchingExample")).isEmpty()
            assertThat(labels(q, "rawDecisionObject")).isEmpty()
        }
    }
}
