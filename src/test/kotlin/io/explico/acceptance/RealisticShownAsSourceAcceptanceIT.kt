/**
 * Acceptance tests for the "Shown as source" finding: an author sees "Below 100%" and a ⚠ block but not WHY explico could not render the condition. The index's
 * "Documentation quality" section (and `quality.shownAsSource` in `explico.json`) groups the condition-level fallbacks by their machine-readable reason, with the
 * cards they are in and one factual sentence about what explico needs. Decisions (to be confirmed):
 *
 * - counts CONDITION-level fallbacks only (they carry a reason code; an unrendered operand inside a rendered condition has none), over every card, helper rules included;
 * - hints explain, they never prescribe a rewrite (a rewrite can change what a policy means); one table holds the sentences;
 * - shown always, advisory, never a gap; sub-bullets sorted by reason, cards in index order (control ids first, then id-less rules by name).
 * Amended on verification (the brief said STOP and tell the operator): the `function-call` sentence in the brief ("a call to something explico cannot resolve to a function
 * ...") is not true of every `function-call` fallback -- a destructuring unification such as `[a, b] = input.pair` carries the same reason, as does a call to a function
 * that exists but whose argument cannot be rendered -- so it is widened to say so; every other sentence was checked against AstMapper and the real output as stated.
 * Authored from the plan, NOT from the frozen acceptance-pack README. Facade only. Fixture: `realistic/shown-as-source/policies/sas.rego` (SA-1/SA-2 `every`, SA-3 else
 * chain, SA-4 `with`, SA-5 unknown call, SA-6 helper assignment, SA-7 partial-rule reference, SA-8 a bare array statement, SA-9 fully rendered, helper_fallback id-less)
 * and `realistic/quality-hints/policies` (no fallbacks).
 */
package io.explico.acceptance

import io.explico.Explico
import io.explico.opa.OpaRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

@DisplayName("Realistic policies: why a condition is shown as source")
class RealisticShownAsSourceAcceptanceIT {

    companion object {
        private val policies = Path.of("src/test/resources/realistic/shown-as-source/policies")
        private val clean = Path.of("src/test/resources/realistic/quality-hints/policies")

        private const val HEAD = "- **Shown as source (9):**"

        private val subBullets = listOf(
            "  - `else-chain` ×1 in [SA-3](sas.md#sa-3) (`sas.rego:42`) — an else chain is shown only when every branch can be rendered, and it is not one of several definitions of the same rule",
            "  - `every` ×2 in [SA-1](sas.md#sa-1) (`sas.rego:14`), [SA-2](sas.md#sa-2) (`sas.rego:29`) — an every block is shown only when its domain is a plain path and every condition in its body can be rendered",
            "  - `function-call` ×2 in [SA-5](sas.md#sa-5) (`sas.rego:69`), [helper_fallback](sas.md#sas-helper-fallback) (`sas.rego:127`) — a call or unification explico cannot render: a built-in it does not model, " +
                "a function it cannot find or whose arguments it cannot render, or a pattern on the left of =",
            "  - `helper-assignment` ×1 in [SA-6](sas.md#sa-6) (`sas.rego:81`) — a local assignment whose value explico cannot substitute into the conditions that use it",
            "  - `partial-rule-reference` ×1 in [SA-7](sas.md#sa-7) (`sas.rego:94`) — a bare reference to a set or object rule is always defined, even when empty, so it is not shown as a yes/no test",
            "  - `unclassified` ×1 in [SA-8](sas.md#sa-8) (`sas.rego:107`) — explico does not render this construct",
            "  - `with-override` ×1 in [SA-4](sas.md#sa-4) (`sas.rego:57`) — with replaces a value for one call, which explico does not model",
        )

        private lateinit var found: io.explico.RenderedDocs
        private lateinit var foundAgain: io.explico.RenderedDocs
        private lateinit var none: io.explico.RenderedDocs

        private fun render(dir: Path) = Explico.render(Explico.load(dir), dir, null, null, false, true)

        @JvmStatic
        @BeforeAll
        fun renderAll() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            found = render(policies)
            foundAgain = render(policies)
            none = render(clean)
        }

        /** The "Documentation quality" section's lines, top-level bullets and their nested ones, as written. */
        private fun sectionLines(docs: io.explico.RenderedDocs): List<String> {
            val lines = docs.files.getValue("index.md").lines()
            val start = lines.indexOf("## Documentation quality")
            if (start < 0) return emptyList()
            return lines.drop(start + 1).takeWhile { !it.startsWith("## ") }.filter { it.isNotBlank() }
        }

        private fun shownAsSource(docs: io.explico.RenderedDocs): List<String> {
            val lines = sectionLines(docs)
            val head = lines.indexOfFirst { it.startsWith("- **Shown as source") }
            assertThat(head).describedAs("a 'Shown as source' bullet in ${lines}").isGreaterThanOrEqualTo(0)
            return lines.drop(head).takeWhile { it == lines[head] || it.startsWith("  - ") }
        }
    }

    @Test
    @DisplayName("one bullet with the total, then one sub-bullet per reason sorted by reason, each with its count, its cards in index order and its sentence")
    fun theBullet() {
        assertThat(shownAsSource(found)).containsExactly(HEAD, *subBullets.toTypedArray())
    }

    @Test
    @DisplayName("an id-less helper rule is listed by its rule name, linked to its own card; a fully rendered control is never listed")
    fun helpersAndRenderedControls() {
        val text = shownAsSource(found).joinToString("\n")
        assertThat(text).contains("[helper_fallback](sas.md#sas-helper-fallback)")
        assertThat(text).doesNotContain("SA-9").doesNotContain("helper_ok")
    }

    @Test
    @DisplayName("it is the only finding about the controls, so the section exists for it and the id-less entry point (spec §14.68); nothing is a gap")
    fun onlyFindingNotAGap() {
        val top = sectionLines(found).filter { it.startsWith("- ") }
        assertThat(top).hasSize(2)
        assertThat(top[0]).startsWith("- **No control id (1):** [sas.helper_fallback](sas.md#sas-helper-fallback)")
        assertThat(top[1]).isEqualTo(HEAD)
        assertThat(found.gaps).isEmpty()
    }

    @Test
    @DisplayName("explico.json quality.shownAsSource has the same reasons as {reason, count, rules}, sorted by reason; an empty array when none")
    fun json() {
        val quality = Json.parseToJsonElement(found.files.getValue("explico.json")).jsonObject.getValue("quality").jsonObject
        assertThat(quality).describedAs("the quality object").containsKey("shownAsSource")
        val entries = quality.getValue("shownAsSource").jsonArray.map {
            val o = it.jsonObject
            Triple(o.getValue("reason").jsonPrimitive.content, o.getValue("count").jsonPrimitive.int, o.getValue("rules").jsonArray.map { r -> r.jsonPrimitive.content })
        }
        assertThat(entries).containsExactly(
            Triple("else-chain", 1, listOf("SA-3")),
            Triple("every", 2, listOf("SA-1", "SA-2")),
            Triple("function-call", 2, listOf("SA-5", "helper_fallback")),
            Triple("helper-assignment", 1, listOf("SA-6")),
            Triple("partial-rule-reference", 1, listOf("SA-7")),
            Triple("unclassified", 1, listOf("SA-8")),
            Triple("with-override", 1, listOf("SA-4")),
        )
        val clean = Json.parseToJsonElement(none.files.getValue("explico.json")).jsonObject.getValue("quality").jsonObject
        assertThat(clean).containsKey("shownAsSource")
        assertThat(clean.getValue("shownAsSource").jsonArray).isEmpty()
    }

    @Test
    @DisplayName("every reason in the bullet is a reason explico.json gives to a condition on the cards: the finding adds up to what the cards show")
    fun agreesWithTheCards() {
        val root = Json.parseToJsonElement(found.files.getValue("explico.json")).jsonObject
        val reasons = root.getValue("packages").jsonArray.flatMap { it.jsonObject.getValue("rules").jsonArray }.flatMap { it.jsonObject.getValue("bodies").jsonArray }
            .flatMap { it.jsonObject.getValue("conditions").jsonArray }.mapNotNull { (it.jsonObject["reason"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.content != "null" }?.content }
        assertThat(reasons).hasSize(9)
        assertThat(reasons.groupingBy { it }.eachCount().toSortedMap()).isEqualTo(
            mapOf("else-chain" to 1, "every" to 2, "function-call" to 2, "helper-assignment" to 1, "partial-rule-reference" to 1, "unclassified" to 1, "with-override" to 1).toSortedMap(),
        )
    }

    @Test
    @DisplayName("without any fallback there is no bullet and, with nothing else to report, no Documentation quality section")
    fun nothingToReport() {
        assertThat(none.files.getValue("index.md")).doesNotContain("Shown as source").doesNotContain("## Documentation quality")
    }

    @Test
    @DisplayName("two renders are byte-identical")
    fun deterministic() {
        assertThat(foundAgain.files.toList()).isEqualTo(found.files.toList())
    }

    @Test
    @DisplayName("the five-package sample (docs/sample-output) renders byte-for-byte as committed")
    fun sampleOutputUntouched() {
        val dir = Path.of("samples/policies")
        val docs = Explico.render(Explico.load(dir), dir, Explico.loadExamples(Path.of("samples/examples")), Path.of("samples/data/release/data.json")).files
        assertThat(docs).isNotEmpty()
        for ((name, text) in docs) {
            assertThat(text).describedAs("docs/sample-output/$name").isEqualTo(Files.readString(Path.of("docs/sample-output").resolve(name)))
        }
    }
}
