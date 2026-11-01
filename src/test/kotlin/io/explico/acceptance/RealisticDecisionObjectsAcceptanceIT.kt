/**
 * Acceptance tests for FR-1 of the decision-object feature requests (specs/decision-object-rendering-spec.md): an `else` chain whose
 * every branch is renderable is shown as ORDERED situations -- a preamble saying the first matching situation wins, `### Situation N`
 * blocks (each later one saying that no earlier situation applies), a `*Then:*` line with the value the branch yields, and a closing
 * `### Otherwise` for a bodiless final `else`. ALL-OR-NOTHING, like `every` and comprehensions: one unrenderable condition anywhere in
 * the chain keeps the whole chain as verbatim source.
 *
 * Authored from the feature request and the implementation plan (operator-accepted wording questions Q1 and Q2), NOT from the frozen
 * acceptance-pack README, like the other `Realistic*AcceptanceIT` classes. Fixtures: `realistic/decision-objects/{policies,examples}`
 * (the requester's two anonymised policies plus their shared stubs, and one extra chain with an unrenderable branch).
 *
 * Scope: ONLY the else-chain rendering. The declared verdict / actions / annotations / timing projections (FR-2 to FR-5) are separate
 * increments with their own tests.
 */
package io.explico.acceptance

import io.explico.Explico
import io.explico.RenderOptions
import io.explico.opa.OpaRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path

@DisplayName("Realistic policies: decision objects -- else chains as ordered situations (FR-1)")
class RealisticDecisionObjectsAcceptanceIT {

    companion object {
        private const val PREAMBLE = "This rule is decided in order; the first matching situation wins."
        private const val NO_EARLIER = "no earlier situation applies and all of the following are true"

        private val policies = Path.of("src/test/resources/realistic/decision-objects/policies")
        private val examples = Path.of("src/test/resources/realistic/decision-objects/examples")

        private lateinit var files: Map<String, String>
        private lateinit var json: JsonObject

        @JvmStatic
        @BeforeAll
        fun renderAll() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            val set = Explico.load(policies)
            files = Explico.render(set, RenderOptions(examples = Explico.loadExamples(examples), json = true)).files
            json = Json.parseToJsonElement(files.getValue("explico.json")).jsonObject
        }

        /** The card whose anchor is [id]: from its `<a id>` to the next card's anchor (or the end of the document). */
        private fun card(doc: String, id: String): String =
            files.getValue(doc).substringAfter("<a id=\"$id\"></a>").substringBefore("<a id=\"")

        /** A card's `### ` sections in order: heading line (without the `### `) to the section's text. */
        private fun sections(card: String): List<Pair<String, String>> {
            val lines = card.lines()
            val starts = lines.indices.filter { lines[it].startsWith("### ") }
            return starts.mapIndexed { n, at ->
                val end = starts.getOrNull(n + 1) ?: lines.size
                lines[at].removePrefix("### ") to lines.subList(at, end).joinToString("\n")
            }
        }

        /** The text of the section whose heading starts with [prefix]; an ASSERTION failure (not an exception) when the card has none. */
        private fun sectionOf(card: String, prefix: String): String {
            val found = sections(card).firstOrNull { it.first.startsWith(prefix) }
            assertThat(found).describedAs("a '### $prefix' section in the card").isNotNull
            return found!!.second
        }

        /** The heading text before the separator dash, so the test pins the numbering and not the dash character. */
        private fun headingKeys(card: String) = sections(card).map { it.first.substringBefore(" —") }

        private fun coverageLine(card: String) = card.lines().single { it.startsWith("*Rendering coverage:") }

        private fun rule(controlId: String): JsonObject =
            json.getValue("packages").jsonArray.flatMap { it.jsonObject.getValue("rules").jsonArray }
                .first { it.jsonObject["controlId"]?.jsonPrimitive?.content == controlId }.jsonObject

        /** Every string stored under a `text` key anywhere below [element]: the JSON's rendered condition phrases, whatever its nesting. */
        private fun texts(element: JsonElement): List<String> = when (element) {
            is JsonObject -> element.flatMap { (key, value) ->
                if (key == "text" && value is JsonPrimitive && value.isString) listOf(value.content) else texts(value)
            }
            is JsonArray -> element.flatMap { texts(it) }
            else -> emptyList()
        }
    }

    @Nested
    @DisplayName("CTRL-WAIVER: a four-branch escalating decision with a bodiless final else")
    inner class Waiver {
        private val card get() = card("exception_waiver.md", "ctrl-waiver")
        private fun section(prefix: String) = sectionOf(card, prefix)

        @Test
        @DisplayName("states once, before the first situation, that the situations are decided in order")
        fun preamble() {
            assertThat(card.windowed(PREAMBLE.length).count { it == PREAMBLE }).isEqualTo(1)
            assertThat(card.indexOf(PREAMBLE)).isLessThan(card.indexOf("### Situation 1"))
        }

        @Test
        @DisplayName("four numbered situations in source order, then one Otherwise")
        fun headings() {
            assertThat(headingKeys(card)).containsExactly("Situation 1", "Situation 2", "Situation 3", "Situation 4", "Otherwise")
        }

        @Test
        @DisplayName("Situation 1 is its own conditions only; every later situation says that no earlier one applies")
        fun orderingIsStatedPerSituation() {
            assertThat(section("Situation 1")).doesNotContain("no earlier situation")
            for (n in 2..4) assertThat(section("Situation $n")).contains(NO_EARLIER)
        }

        @Test
        @DisplayName("Situation 1: rule `has_request` does not match")
        fun situation1() {
            assertThat(section("Situation 1")).contains("- rule [`has_request`](#exception-waiver-has-request) does not match")
        }

        @Test
        @DisplayName("Situation 2: rule `requires_board` matches; rule `all_signoffs_valid` does not match")
        fun situation2() {
            assertThat(section("Situation 2"))
                .contains("- see rule [`requires_board`](#exception-waiver-requires-board)")
                .contains("- rule [`all_signoffs_valid`](#exception-waiver-all-signoffs-valid) does not match")
        }

        @Test
        @DisplayName("Situation 3: rule `requires_senior` matches; rule `self_and_senior_valid` does not match")
        fun situation3() {
            assertThat(section("Situation 3"))
                .contains("- see rule [`requires_senior`](#exception-waiver-requires-senior)")
                .contains("- rule [`self_and_senior_valid`](#exception-waiver-self-and-senior-valid) does not match")
        }

        @Test
        @DisplayName("Situation 4: rule `self_valid` does not match")
        fun situation4() {
            assertThat(section("Situation 4")).contains("- rule [`self_valid`](#exception-waiver-self-valid) does not match")
        }

        @Test
        @DisplayName("every branch, including the bodiless Otherwise, has a *Then:* line carrying the commentary it yields")
        fun thenLines() {
            val expected = mapOf(
                "Situation 1" to "\"No waiver requested\"",
                "Situation 2" to "\"Board sign-off required\"",
                "Situation 3" to "\"Senior sign-off required\"",
                "Situation 4" to "\"Self-attestation required\"",
                "Otherwise" to "\"Non-compliance waived\"",
            )
            for ((heading, commentary) in expected) {
                val then = section(heading).lines().filter { it.startsWith("*Then:*") }
                assertThat(then).describedAs("$heading's *Then:* lines").hasSize(1)
                assertThat(then.single()).describedAs(heading).contains(commentary)
            }
        }

        @Test
        @DisplayName("no fallback block: nothing is shown as source and the generic 'all of the following' header is gone")
        fun noFallback() {
            assertThat(card).doesNotContain("⚠").doesNotContain("else chain").doesNotContain("```rego")
                .doesNotContain("**All of the following are true:**")
        }

        @Test
        @DisplayName("coverage counts every branch's conditions: 6 of 6")
        fun coverage() {
            assertThat(coverageLine(card)).startsWith("*Rendering coverage: 6 of 6 conditions")
        }

        @Test
        @DisplayName("the index shows the control as 100% rendered")
        fun index() {
            val row = files.getValue("index.md").lines().single { it.startsWith("| CTRL-WAIVER |") }
            assertThat(row).contains("| 100% |")
        }

        @Test
        @DisplayName("explico.json carries the four negated branch conditions as rendered phrases, each also a bullet in the Markdown")
        fun jsonParity() {
            val negated = texts(rule("CTRL-WAIVER")).filter { it.contains("does not match") }
            assertThat(negated).hasSize(4)
            // The JSON phrase is link-free; the card's bullet carries the link (same normalisation as RealisticJsonExportAcceptanceIT).
            val bullets = card.lines().filter { it.startsWith("- ") }.map { it.removePrefix("- ").replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1") }
            for (text in negated) assertThat(bullets).contains(text)
        }
    }

    @Nested
    @DisplayName("exception_waiver.self_valid: a boolean helper, `if {...} else := false`")
    inner class BooleanHelper {
        private val card get() = card("exception_waiver.md", "exception-waiver-self-valid")

        @Test
        @DisplayName("one situation and an Otherwise, under the in-order preamble")
        fun shape() {
            assertThat(card).contains(PREAMBLE)
            assertThat(headingKeys(card)).containsExactly("Situation 1", "Otherwise")
        }

        @Test
        @DisplayName("the implicit `true` of the first branch and the explicit `false` of the else are both stated")
        fun values() {
            val first = sectionOf(card, "Situation 1")
            val otherwise = sectionOf(card, "Otherwise")
            assertThat(first).contains("*Then:* `true`").contains("see rule [`within_self_window`](#exception-waiver-within-self-window)")
            assertThat(otherwise).contains("*Then:* `false`")
        }

        @Test
        @DisplayName("no fallback block, and both conditions are counted: 2 of 2")
        fun noFallback() {
            assertThat(card).doesNotContain("⚠").doesNotContain("else chain")
            assertThat(coverageLine(card)).startsWith("*Rendering coverage: 2 of 2 conditions")
        }
    }

    @Nested
    @DisplayName("periodic_attestation.base_decision: four situations with a function-call condition")
    inner class Attestation {
        private val card get() = card("periodic_attestation.md", "periodic-attestation-base-decision")

        @Test
        @DisplayName("four numbered situations and an Otherwise, with the later ones stating that no earlier situation applies")
        fun shape() {
            assertThat(card).contains(PREAMBLE)
            assertThat(headingKeys(card)).containsExactly("Situation 1", "Situation 2", "Situation 3", "Situation 4", "Otherwise")
            for (n in 2..4) assertThat(sectionOf(card, "Situation $n")).contains(NO_EARLIER)
        }

        @Test
        @DisplayName("each branch yields the commentary it declares")
        fun commentaries() {
            assertThat(sectionOf(card, "Situation 1")).contains("\"No attestation provided\"")
            assertThat(sectionOf(card, "Situation 2")).contains("\"Attestation has expired\"")
            assertThat(sectionOf(card, "Situation 3")).contains("\"No regulated functions in use\"")
            assertThat(sectionOf(card, "Situation 4")).contains("Regulated functions in use")
            assertThat(sectionOf(card, "Otherwise")).contains("\"Attestation is invalid\"")
        }

        @Test
        @DisplayName("no fallback block, and all four conditions are counted: 4 of 4")
        fun noFallback() {
            assertThat(card).doesNotContain("⚠").doesNotContain("else chain")
            assertThat(coverageLine(card)).startsWith("*Rendering coverage: 4 of 4 conditions")
        }
    }

    @Nested
    @DisplayName("CTRL-FALLBACK: a chain with ONE unrenderable branch stays whole, as source")
    inner class UnrenderableBranch {
        private val card get() = card("decision_fallback.md", "ctrl-fallback")

        @Test
        @DisplayName("the whole chain, every branch, is shown as verbatim source -- never half of it as situations")
        fun fallsBackWhole() {
            assertThat(card).contains("⚠").contains("else chain")
            assertThat(card).contains("input.score >= 90").contains("input.score >= 50 with input.score as 60").contains("} else = \"bronze\"")
            assertThat(card).doesNotContain("### Situation").doesNotContain("### Otherwise").doesNotContain(PREAMBLE)
        }

        @Test
        @DisplayName("it is one unrendered condition, with the machine-readable reason `else-chain`")
        fun reason() {
            assertThat(coverageLine(card)).isEqualTo("*Rendering coverage: 0 of 1 conditions*")
            val conditions = rule("CTRL-FALLBACK").getValue("bodies").jsonArray.flatMap { it.jsonObject.getValue("conditions").jsonArray }
            assertThat(conditions).hasSize(1)
            assertThat(conditions.single().jsonObject.getValue("reason").jsonPrimitive.content).isEqualTo("else-chain")
        }
    }
}
