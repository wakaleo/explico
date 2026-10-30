/**
 * Acceptance tests for the links on the places: with a source URL template a card's "defined in" line already links to the source, but the `file:row` places in the index
 * ("Shown as source" and "Comments not published") were plain text. With a template each place is now a link -- the text stays the `file:row` code span, the row is the
 * link's line -- built by the same `SourceLinks.url` as every other source link; without one nothing changes. `explico.json` places and comment blocks gain `url`, only
 * when a template was given. Decisions (to be confirmed): only these two index places; no companion links in the index. Authored from the plan, NOT from the frozen
 * acceptance-pack README. Facade only. Fixtures reused: `realistic/fallback-places/policies` (+ `grouped/policies`) and `realistic/unpublished-comments/policies`.
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

@DisplayName("Realistic policies: links on the places in the index")
class RealisticPlaceLinksAcceptanceIT {

    companion object {
        private val fallbacks = Path.of("src/test/resources/realistic/fallback-places/policies")
        private val grouped = Path.of("src/test/resources/realistic/fallback-places/grouped/policies")
        private val comments = Path.of("src/test/resources/realistic/unpublished-comments/policies")

        private const val BASE = "https://git.example.com/org/repo/blob/abc123/policies"
        private const val TEMPLATE = "$BASE/{file}#L{line}"
        private const val CALL = "a call or unification explico cannot render: a built-in it does not model, a function it cannot find or whose arguments it cannot render, or a pattern on the left of ="

        private lateinit var linked: io.explico.RenderedDocs
        private lateinit var linkedAgain: io.explico.RenderedDocs
        private lateinit var linkedGrouped: io.explico.RenderedDocs
        private lateinit var linkedComments: io.explico.RenderedDocs
        private lateinit var plain: io.explico.RenderedDocs
        private lateinit var plainComments: io.explico.RenderedDocs

        private fun render(dir: Path, template: String?, group: Boolean = false) =
            Explico.render(Explico.load(dir), dir, null, null, false, true, emptyList(), template, null, "{id}", group)

        @JvmStatic
        @BeforeAll
        fun renderAll() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            linked = render(fallbacks, TEMPLATE)
            linkedAgain = render(fallbacks, TEMPLATE)
            linkedGrouped = render(grouped, TEMPLATE, group = true)
            linkedComments = render(comments, TEMPLATE)
            plain = render(fallbacks, null)
            plainComments = render(comments, null)
        }

        private fun link(file: String, row: Int) = "[`$file:$row`]($BASE/$file#L$row)"

        private fun section(docs: io.explico.RenderedDocs): List<String> {
            val lines = docs.files.getValue("index.md").lines()
            val start = lines.indexOf("## Documentation quality")
            assertThat(start).describedAs("a Documentation quality section").isGreaterThanOrEqualTo(0)
            return lines.drop(start + 1).takeWhile { !it.startsWith("## ") }.filter { it.isNotBlank() }
        }

        private fun quality(docs: io.explico.RenderedDocs): JsonObject = Json.parseToJsonElement(docs.files.getValue("explico.json")).jsonObject.getValue("quality").jsonObject

        private fun placeEntries(docs: io.explico.RenderedDocs): List<JsonObject> =
            quality(docs).getValue("shownAsSource").jsonArray.flatMap { it.jsonObject.getValue("places").jsonArray }.map { it.jsonObject }
    }

    @Test
    @DisplayName("with a template every place is a link, text unchanged: two places of one card, a subdirectory file and an id-less helper in the function-call line")
    fun fallbackPlaces() {
        assertThat(section(linked)).contains(
            "  - `function-call` ×4 in [FP-1](fp.md#fp-1) (${link("top.rego", 15)}, ${link("top.rego", 17)}), [FP-5](fpsub.md#fp-5) (${link("sub/x.rego", 14)}), " +
                "[helper_fallback](fp.md#fp-helper-fallback) (${link("top.rego", 68)}) — $CALL",
        )
    }

    @Test
    @DisplayName("the other reasons' places are linked too, to their own rows: the else chain 56, the every block 30, the with 45")
    fun otherReasons() {
        val lines = section(linked).joinToString("\n")
        assertThat(lines).contains("in [FP-4](fp.md#fp-4) (${link("top.rego", 56)})")
        assertThat(lines).contains("in [FP-2](fp.md#fp-2) (${link("top.rego", 30)})")
        assertThat(lines).contains("in [FP-3](fp.md#fp-3) (${link("top.rego", 45)})")
    }

    @Test
    @DisplayName("a grouped control's places are linked, each to its own file")
    fun groupedControl() {
        val lines = section(linkedGrouped).joinToString("\n")
        assertThat(lines).contains("[FPG-1](controls/fpg-1.md#fpg-1) (${link("g1.rego", 15)})")
        assertThat(lines).contains("[FPG-1](controls/fpg-1.md#fpg-1) (${link("sub/g2.rego", 15)})")
    }

    @Test
    @DisplayName("the comment blocks are links too, with their length and the hint unchanged")
    fun commentBlocks() {
        assertThat(section(linkedComments)).contains(
            "- **Comments not published (4):** ${link("sub/nested.rego", 5)} (2 lines), ${link("uc.rego", 1)} (3 lines), ${link("uc.rego", 23)} (2 lines), " +
                "${link("uc.rego", 40)} (3 lines) — move what readers need into a rule's description in its METADATA",
        )
    }

    @Test
    @DisplayName("explico.json: every place and every comment block has a url, the very one the Markdown links to")
    fun jsonUrls() {
        val places = placeEntries(linked)
        assertThat(places).isNotEmpty()
        for (place in places) {
            assertThat(place).describedAs("a place of ${place.getValue("rule")}").containsKey("url")
            val file = place.getValue("file").jsonPrimitive.content
            val row = place.getValue("row").jsonPrimitive.int
            assertThat(place.getValue("url").jsonPrimitive.content).isEqualTo("$BASE/$file#L$row")
            assertThat(linked.files.getValue("index.md")).contains("($BASE/$file#L$row)")
        }
        val blocks = quality(linkedComments).getValue("unpublishedComments").jsonArray.map { it.jsonObject }
        assertThat(blocks).hasSize(4)
        for (block in blocks) {
            assertThat(block).describedAs("a comment block").containsKey("url")
            assertThat(block.getValue("url").jsonPrimitive.content).isEqualTo("$BASE/${block.getValue("file").jsonPrimitive.content}#L${block.getValue("row").jsonPrimitive.int}")
        }
    }

    @Test
    @DisplayName("without a template nothing changes: plain file:row code spans, and no url key anywhere in the quality object")
    fun withoutATemplate() {
        assertThat(section(plain).joinToString("\n")).contains("in [FP-1](fp.md#fp-1) (`top.rego:15`, `top.rego:17`), [FP-5](fpsub.md#fp-5) (`sub/x.rego:14`)")
        assertThat(section(plainComments)).contains(
            "- **Comments not published (4):** `sub/nested.rego:5` (2 lines), `uc.rego:1` (3 lines), `uc.rego:23` (2 lines), `uc.rego:40` (3 lines) — move what readers need into a rule's description in its METADATA",
        )
        assertThat(plain.files.getValue("index.md")).doesNotContain(BASE)
        assertThat(quality(plain).toString()).doesNotContain("\"url\"")
        assertThat(quality(plainComments).toString()).doesNotContain("\"url\"")
    }

    @Test
    @DisplayName("the template changes only the links: with the links stripped back to their text, the index is the plain index")
    fun onlyTheLinksDiffer() {
        val stripped = linked.files.getValue("index.md").replace(Regex("\\[(`[^`]+`)\\]\\(" + Regex.escape(BASE) + "[^)]*\\)"), "$1")
        // other source links (the Source file column) are links too; compare only the quality section, where the places live
        fun qualitySection(text: String) = text.lines().let { l -> l.drop(l.indexOf("## Documentation quality")).takeWhile { !it.startsWith("## How") } }
        assertThat(qualitySection(stripped)).isEqualTo(qualitySection(plain.files.getValue("index.md")))
    }

    @Test
    @DisplayName("two renders are byte-identical")
    fun deterministic() {
        assertThat(linkedAgain.files.toList()).isEqualTo(linked.files.toList())
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
