/**
 * Acceptance tests for the "Values shown as source" finding. A condition that renders can still contain a value explico could not render (shown inline as verbatim source);
 * until now that was only the card footer "contains N unrendered value(s)": never in the index, never with a place. The index's *Documentation quality* section (and
 * `quality.valuesShownAsSource` in `explico.json`, and a finding line) now lists each distinct value with how many times, on which cards and where. Decisions (to be confirmed):
 *
 * - counted: the very values the card footer counts (an unrendered operand inside a condition that is itself rendered, nested ones included), over every card;
 * - one sub-bullet per DISTINCT verbatim source text, ordered by count descending then text ascending, cards in index order, each card's places ascending (linked when a
 *   source URL template is given, like every other place); at most 25 are listed, then `… and N more distinct values (all are in explico.json)`; the head's N is always
 *   the total number of occurrences; the JSON lists them all; a backtick in a source text must not break the code span;
 * - advisory, never a gap, needs no examples.
 * Existing fixtures with the footer note: free-index, quality-hints and real-world-fixes -- /tdd repoints whatever they pin. The "nothing to report" case here is the
 * acceptance-pack sample, not quality-hints (which has one such value). Authored from the plan, NOT from the frozen acceptance-pack README. Facade only.
 * Fixtures: `realistic/values-as-source/{policies, grouped/policies, many/policies, ticks/policies}`; every row was read from the numbered files.
 */
package io.explico.acceptance

import io.explico.Explico
import io.explico.opa.OpaRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

@DisplayName("Realistic policies: values shown as source inside rendered conditions")
class RealisticValuesShownAsSourceAcceptanceIT {

    companion object {
        private val base = Path.of("src/test/resources/realistic/values-as-source")
        private const val TEMPLATE = "https://git.example.com/org/repo/blob/abc123/policies/{file}#L{line}"
        private const val HEAD_TEXT = "explico renders the condition around each of these but cannot render the value itself"

        private lateinit var main: io.explico.RenderedDocs
        private lateinit var mainAgain: io.explico.RenderedDocs
        private lateinit var linked: io.explico.RenderedDocs
        private lateinit var grouped: io.explico.RenderedDocs
        private lateinit var many: io.explico.RenderedDocs
        private lateinit var ticks: io.explico.RenderedDocs
        private lateinit var clean: io.explico.RenderedDocs

        private fun render(dir: Path, template: String? = null, group: Boolean = false) =
            Explico.render(Explico.load(dir), dir, null, null, false, true, emptyList(), template, null, "{id}", group)

        @JvmStatic
        @BeforeAll
        fun renderAll() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            main = render(base.resolve("policies"))
            mainAgain = render(base.resolve("policies"))
            linked = render(base.resolve("policies"), TEMPLATE)
            grouped = render(base.resolve("grouped/policies"), group = true)
            many = render(base.resolve("many/policies"))
            ticks = render(base.resolve("ticks/policies"))
            clean = render(Path.of("samples/policies"))
        }

        /** The 'Values shown as source' bullet followed by its nested lines; an ASSERTION failure when there is none. */
        private fun values(docs: io.explico.RenderedDocs): List<String> {
            val lines = docs.files.getValue("index.md").lines()
            val start = lines.indexOf("## Documentation quality")
            assertThat(start).describedAs("a Documentation quality section").isGreaterThanOrEqualTo(0)
            val section = lines.drop(start + 1).takeWhile { !it.startsWith("## ") }.filter { it.isNotBlank() }
            val head = section.indexOfFirst { it.startsWith("- **Values shown as source") }
            assertThat(head).describedAs("a 'Values shown as source' bullet in $section").isGreaterThanOrEqualTo(0)
            return listOf(section[head]) + section.drop(head + 1).takeWhile { it.startsWith("  - ") }
        }

        private fun entries(docs: io.explico.RenderedDocs): List<JsonObject> {
            val quality = Json.parseToJsonElement(docs.files.getValue("explico.json")).jsonObject.getValue("quality").jsonObject
            assertThat(quality).describedAs("the quality object").containsKey("valuesShownAsSource")
            return quality.getValue("valuesShownAsSource").jsonArray.map { it.jsonObject }
        }
    }

    @Test
    @DisplayName("one sub-bullet per distinct value, count descending then text; the same value twice in a card and in two cards, one nested in count(), one in a function and one in a helper")
    fun theBullet() {
        assertThat(values(main)).containsExactly(
            "- **Values shown as source (7):** $HEAD_TEXT",
            "  - `json.marshal(input.a)` ×3 in [VS-1](vs.md#vs-1) (`vs.rego:15`, `vs.rego:17`), [VS-2](vs.md#vs-2) (`vs.rego:30`)",
            "  - `json.marshal(input.b)` ×1 in [VS-1](vs.md#vs-1) (`vs.rego:18`)",
            "  - `json.marshal(input.c)` ×1 in [VS-2](vs.md#vs-2) (`vs.rego:31`)",
            "  - `json.marshal(input.h)` ×1 in [helper_v](vs.md#vs-helper-v) (`vs.rego:39`)",
            "  - `json.marshal(x)` ×1 in [vs_fn](vs.md#vs-vs-fn) (`vs.rego:35`)",
        )
    }

    @Test
    @DisplayName("a grouped control is ONE card with the places of all its rules, from both files")
    fun groupedControl() {
        assertThat(values(grouped)).containsExactly(
            "- **Values shown as source (3):** $HEAD_TEXT",
            "  - `json.marshal(input.a)` ×2 in [VSG-1](controls/vsg-1.md#vsg-1) (`g1.rego:15`, `sub/g2.rego:14`)",
            "  - `json.marshal(input.z)` ×1 in [VSG-1](controls/vsg-1.md#vsg-1) (`sub/g2.rego:15`)",
        )
    }

    @Test
    @DisplayName("the cap: 27 distinct values list the first 25 and say how many more; the head still counts all 27")
    fun cap() {
        val lines = values(many)
        assertThat(lines.first()).isEqualTo("- **Values shown as source (27):** $HEAD_TEXT")
        assertThat(lines).hasSize(1 + 25 + 1)
        assertThat(lines[1]).isEqualTo("  - `json.marshal(input.v01)` ×1 in [VM-1](vm.md#vm-1) (`vm.rego:14`)")
        assertThat(lines[25]).isEqualTo("  - `json.marshal(input.v25)` ×1 in [VM-1](vm.md#vm-1) (`vm.rego:38`)")
        assertThat(lines.last()).isEqualTo("  - … and 2 more distinct values (all are in explico.json)")
        assertThat(lines.joinToString("\n")).doesNotContain("input.v26").doesNotContain("input.v27")
    }

    @Test
    @DisplayName("a source text with backticks does not break its code span")
    fun backticks() {
        assertThat(values(ticks)).containsExactly(
            "- **Values shown as source (1):** $HEAD_TEXT",
            "  - ``json.marshal(`a`)`` ×1 in [VT-1](vt.md#vt-1) (`vt.rego:15`)",
        )
    }

    @Test
    @DisplayName("with a source template every place is a link, like the other places in the index")
    fun linkedPlaces() {
        val base = "https://git.example.com/org/repo/blob/abc123/policies"
        assertThat(values(linked)).contains(
            "  - `json.marshal(input.a)` ×3 in [VS-1](vs.md#vs-1) ([`vs.rego:15`]($base/vs.rego#L15), [`vs.rego:17`]($base/vs.rego#L17)), [VS-2](vs.md#vs-2) ([`vs.rego:30`]($base/vs.rego#L30))",
        )
    }

    @Test
    @DisplayName("the finding line is worded once: singular for one value, plural otherwise, right after the shown-as-source line (or where it would be)")
    fun findingLine() {
        assertThat(main.findings).containsExactly("1 rule has no control id and is not used by another rule", "7 values are shown as source inside rendered conditions")
        assertThat(ticks.findings).containsExactly("1 value is shown as source inside a rendered condition")
        assertThat(many.findings).containsExactly("27 values are shown as source inside rendered conditions")
    }

    @Test
    @DisplayName("explico.json lists ALL values with their places, same order, whatever the cap; urls only with a template")
    fun json() {
        val all = entries(many)
        assertThat(all).hasSize(27)
        assertThat(all.first().getValue("source").jsonPrimitive.content).isEqualTo("json.marshal(input.v01)")
        assertThat(all.last().getValue("source").jsonPrimitive.content).isEqualTo("json.marshal(input.v27)")
        val first = entries(main).first()
        assertThat(first.getValue("source").jsonPrimitive.content).isEqualTo("json.marshal(input.a)")
        assertThat(first.getValue("count").jsonPrimitive.int).isEqualTo(3)
        val places = first.getValue("places").jsonArray.map { it.jsonObject }
        assertThat(places.map { Triple(it.getValue("rule").jsonPrimitive.content, it.getValue("file").jsonPrimitive.content, it.getValue("row").jsonPrimitive.int) })
            .containsExactly(Triple("VS-1", "vs.rego", 15), Triple("VS-1", "vs.rego", 17), Triple("VS-2", "vs.rego", 30))
        assertThat(places).allSatisfy { assertThat(it).doesNotContainKey("url") }
        val linkedPlaces = entries(linked).first().getValue("places").jsonArray.map { it.jsonObject }
        assertThat(linkedPlaces.map { it.getValue("url").jsonPrimitive.content }).containsExactly(
            "https://git.example.com/org/repo/blob/abc123/policies/vs.rego#L15", "https://git.example.com/org/repo/blob/abc123/policies/vs.rego#L17",
            "https://git.example.com/org/repo/blob/abc123/policies/vs.rego#L30",
        )
    }

    @Test
    @DisplayName("nothing to report (the acceptance-pack sample has no such value): no bullet, no line, an empty JSON list, no gaps")
    fun nothingToReport() {
        assertThat(clean.files.getValue("index.md")).doesNotContain("Values shown as source")
        assertThat(clean.findings.none { "shown as source inside" in it }).isTrue()
        assertThat(entries(clean)).isEmpty()
        assertThat(main.gaps).isEmpty()
    }

    @Test
    @DisplayName("two renders are byte-identical")
    fun deterministic() {
        assertThat(mainAgain.files.toList()).isEqualTo(main.files.toList())
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
