/**
 * Acceptance tests for the places of the fallbacks: "Shown as source" said which cards and why, but an author still had to open each card to find the line. Each
 * condition-level fallback now reports its place, `file:row` -- the file of its rule body (relative to the policy directory, `/` separators) and the 1-based row of the
 * fallback statement itself (for a whole construct such as an `every` block, an else chain or a `with`, its FIRST row). Decisions (to be confirmed):
 *
 * - the index puts the places in parentheses right after each card's link, rows ascending, comma-separated, in code spans; `explico.json` `quality.shownAsSource`
 *   entries gain `places` (`{rule, file, row}`); the header, counts, reasons, sentences and card order are exactly what RealisticShownAsSourceAcceptanceIT pins;
 * - a grouped control lists the places of all its member rules; advisory, no new CLI line.
 * Every row below was computed from the numbered fixture files and checked against the verbatim `source` of the real `explico.json` (it starts on that row).
 * RealisticShownAsSourceAcceptanceIT.theBullet pins the old, place-less sub-bullets and is repointed in /tdd. Authored from the plan, NOT from the frozen acceptance-pack
 * README. Facade only. Fixtures: `realistic/fallback-places/policies` (`top.rego`, `sub/x.rego`) and `.../grouped/policies` (`g1.rego`, `sub/g2.rego`, one control).
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

@DisplayName("Realistic policies: where a condition is shown as source")
class RealisticFallbackPlacesAcceptanceIT {

    companion object {
        private val policies = Path.of("src/test/resources/realistic/fallback-places/policies")
        private val grouped = Path.of("src/test/resources/realistic/fallback-places/grouped/policies")
        private val clean = Path.of("src/test/resources/realistic/quality-hints/policies")

        private const val CALL = "a call or unification explico cannot render: a built-in it does not model, a function it cannot find or whose arguments it cannot render, or a pattern on the left of ="

        private lateinit var found: io.explico.RenderedDocs
        private lateinit var foundAgain: io.explico.RenderedDocs
        private lateinit var groupedDocs: io.explico.RenderedDocs
        private lateinit var none: io.explico.RenderedDocs

        private fun render(dir: Path, group: Boolean = false) = Explico.render(Explico.load(dir), dir, null, null, false, true, emptyList(), null, null, "{id}", group)

        @JvmStatic
        @BeforeAll
        fun renderAll() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            found = render(policies)
            foundAgain = render(policies)
            groupedDocs = render(grouped, group = true)
            none = render(clean)
        }

        /** The 'Shown as source' bullet and its sub-bullets; an ASSERTION failure when there is none. */
        private fun shownAsSource(docs: io.explico.RenderedDocs): List<String> {
            val lines = docs.files.getValue("index.md").lines()
            val start = lines.indexOf("## Documentation quality")
            assertThat(start).describedAs("a Documentation quality section").isGreaterThanOrEqualTo(0)
            val section = lines.drop(start + 1).takeWhile { !it.startsWith("## ") }.filter { it.isNotBlank() }
            val head = section.indexOfFirst { it.startsWith("- **Shown as source") }
            assertThat(head).describedAs("a 'Shown as source' bullet in $section").isGreaterThanOrEqualTo(0)
            return section.drop(head + 1).takeWhile { it.startsWith("  - ") }
        }

        private fun places(docs: io.explico.RenderedDocs, reason: String): List<Triple<String, String, Int>> {
            val quality = Json.parseToJsonElement(docs.files.getValue("explico.json")).jsonObject.getValue("quality").jsonObject
            val entry = quality.getValue("shownAsSource").jsonArray.map { it.jsonObject }.firstOrNull { it.getValue("reason").jsonPrimitive.content == reason }
            assertThat(entry).describedAs("a shownAsSource entry for $reason").isNotNull()
            assertThat(entry!!).describedAs("the $reason entry").containsKey("places")
            return entry.getValue("places").jsonArray.map {
                val o = it.jsonObject
                Triple(o.getValue("rule").jsonPrimitive.content, o.getValue("file").jsonPrimitive.content, o.getValue("row").jsonPrimitive.int)
            }
        }
    }

    @Test
    @DisplayName("each card entry carries its places in parentheses after its link: two calls in one control, an every block, a with, an else chain, a subdirectory file, an id-less helper")
    fun theSubBullets() {
        assertThat(shownAsSource(found)).containsExactly(
            "  - `else-chain` ×1 in [FP-4](fp.md#fp-4) (`top.rego:56`) — an else chain is shown only when every branch can be rendered, and it is not one of several definitions of the same rule",
            "  - `every` ×1 in [FP-2](fp.md#fp-2) (`top.rego:30`) — an every block is shown only when its domain is a plain path and every condition in its body can be rendered",
            "  - `function-call` ×4 in [FP-1](fp.md#fp-1) (`top.rego:15`, `top.rego:17`), [FP-5](fpsub.md#fp-5) (`sub/x.rego:14`), [helper_fallback](fp.md#fp-helper-fallback) (`top.rego:68`) — $CALL",
            "  - `with-override` ×1 in [FP-3](fp.md#fp-3) (`top.rego:45`) — with replaces a value for one call, which explico does not model",
        )
    }

    @Test
    @DisplayName("a construct spanning several rows reports its FIRST row: the every block (rows 30-34) is 30, the else chain (56-60) is 56")
    fun firstRowOfAWholeConstruct() {
        assertThat(places(found, "every")).containsExactly(Triple("FP-2", "top.rego", 30))
        assertThat(places(found, "else-chain")).containsExactly(Triple("FP-4", "top.rego", 56))
    }

    @Test
    @DisplayName("a grouped control lists the places of all its rules, from both files, each under the control's page")
    fun groupedControl() {
        assertThat(shownAsSource(groupedDocs)).containsExactly(
            "  - `function-call` ×1 in [FPG-1](controls/fpg-1.md#fpg-1) (`g1.rego:15`) — $CALL",
            "  - `with-override` ×1 in [FPG-1](controls/fpg-1.md#fpg-1) (`sub/g2.rego:15`) — with replaces a value for one call, which explico does not model",
        )
    }

    @Test
    @DisplayName("explico.json quality.shownAsSource entries gain places {rule, file, row}, in the order the card entries come, rows ascending within a card")
    fun json() {
        assertThat(places(found, "function-call")).containsExactly(
            Triple("FP-1", "top.rego", 15), Triple("FP-1", "top.rego", 17), Triple("FP-5", "sub/x.rego", 14), Triple("helper_fallback", "top.rego", 68),
        )
        assertThat(places(found, "with-override")).containsExactly(Triple("FP-3", "top.rego", 45))
        assertThat(places(groupedDocs, "with-override")).containsExactly(Triple("FPG-1", "sub/g2.rego", 15))
    }

    @Test
    @DisplayName("without any fallback there is no bullet, and the JSON list is empty")
    fun nothingToReport() {
        assertThat(none.files.getValue("index.md")).doesNotContain("Shown as source")
        val quality = Json.parseToJsonElement(none.files.getValue("explico.json")).jsonObject.getValue("quality").jsonObject
        assertThat(quality.getValue("shownAsSource").jsonArray).isEmpty()
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
        for ((name, text) in docs) {
            assertThat(text).describedAs("docs/sample-output/$name").isEqualTo(Files.readString(Path.of("docs/sample-output").resolve(name)))
        }
    }
}
