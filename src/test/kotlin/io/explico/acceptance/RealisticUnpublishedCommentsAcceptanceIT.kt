/**
 * Acceptance tests for the "Comments not published" finding: authors explain a policy in plain `#` comments, which explico (METADATA only) never shows. The index's
 * "Documentation quality" section (and `quality.unpublishedComments` in `explico.json`) lists each such block so the author can move what readers need into a
 * description. Decisions (to be confirmed):
 *
 * - a block is a run of consecutive comment lines starting in column 1, of at least 2 lines, outside every rule's source span (so never inside a body, even at column 1),
 *   not trailing code, and not part of a `# METADATA` block (in a run that contains one, only the lines BEFORE it count);
 * - adjacent to a rule or not does not matter (the case that prompted this was a block detached from the rule it describes); no guessing of meaning, so a licence header
 *   is reported too (advisory);
 * - shown always (no examples needed), as a finding and NOT a gap; the bullet sits before duplicate titles; everything else is byte-identical.
 * Authored from the plan, NOT from the frozen acceptance-pack README. Facade only. Fixture: `realistic/unpublished-comments/policies`
 * (`uc.rego` rows 1-3 header, 23-24 detached note, 40-42 plain lines before a METADATA block; `sub/nested.rego` rows 5-6; `plain.rego` none) and
 * `realistic/quality-hints/policies` (METADATA only, nothing to report).
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

@DisplayName("Realistic policies: comments that never reach the documentation")
class RealisticUnpublishedCommentsAcceptanceIT {

    companion object {
        private val policies = Path.of("src/test/resources/realistic/unpublished-comments/policies")
        private val clean = Path.of("src/test/resources/realistic/quality-hints/policies")

        private const val MARKER = "- **Comments not published"
        private const val HINT = "— move what readers need into a rule's description in its METADATA"
        private const val BULLET =
            "- **Comments not published (4):** `sub/nested.rego:5` (2 lines), `uc.rego:1` (3 lines), `uc.rego:23` (2 lines), `uc.rego:40` (3 lines) $HINT"

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

        private fun qualityLines(docs: io.explico.RenderedDocs): List<String> {
            val lines = docs.files.getValue("index.md").lines()
            val start = lines.indexOf("## Documentation quality")
            if (start < 0) return emptyList()
            return lines.drop(start + 1).takeWhile { !it.startsWith("## ") }.filter { it.startsWith("- ") }
        }

        /** The one bullet of the section; an ASSERTION failure unless there is exactly one. */
        private fun theLine(docs: io.explico.RenderedDocs): String {
            val lines = qualityLines(docs)
            assertThat(lines).describedAs("the Documentation quality bullets").hasSize(1)
            return lines.single()
        }

        private fun jsonEntries(docs: io.explico.RenderedDocs): List<Triple<String, Int, Int>> {
            val quality = Json.parseToJsonElement(docs.files.getValue("explico.json")).jsonObject.getValue("quality").jsonObject
            assertThat(quality).describedAs("the quality object").containsKey("unpublishedComments")
            return quality.getValue("unpublishedComments").jsonArray.map {
                val o = it.jsonObject
                Triple(o.getValue("file").jsonPrimitive.content, o.getValue("row").jsonPrimitive.int, o.getValue("lines").jsonPrimitive.int)
            }
        }
    }

    @Test
    @DisplayName("one bullet lists every block with its file, first row and length, sorted by file then row, and ends with the hint")
    fun theBullet() {
        assertThat(qualityLines(found)).containsExactly(BULLET)
    }

    @Test
    @DisplayName("what is NOT reported: a single-line comment, a trailing comment, comments inside a body (even in column 1), a METADATA block, a file with none")
    fun notReported() {
        val line = theLine(found)
        for (row in listOf(18, 19, 29, 38, 43)) assertThat(line).describedAs("row $row").doesNotContain("uc.rego:$row")
        assertThat(line).doesNotContain("plain.rego").doesNotContain("nested.rego:7")
    }

    @Test
    @DisplayName("a block followed by a METADATA block in one run counts only its plain lines: 3, not the METADATA lines")
    fun plainLinesBeforeMetadata() {
        assertThat(theLine(found)).contains("`uc.rego:40` (3 lines)")
    }

    @Test
    @DisplayName("it is the only finding, so the section exists for it alone; the control cards are untouched and nothing is a gap")
    fun onlyFindingNotAGap() {
        assertThat(qualityLines(found)).hasSize(1)
        assertThat(found.gaps).isEmpty()
    }

    @Test
    @DisplayName("without any plain comment block there is no bullet and, with nothing else to report, no section")
    fun nothingToReport() {
        assertThat(none.files.getValue("index.md")).doesNotContain(MARKER).doesNotContain("## Documentation quality")
    }

    @Test
    @DisplayName("explico.json quality.unpublishedComments has the same blocks as {file, row, lines}; an empty array when there are none")
    fun json() {
        assertThat(jsonEntries(found)).containsExactly(
            Triple("sub/nested.rego", 5, 2), Triple("uc.rego", 1, 3), Triple("uc.rego", 23, 2), Triple("uc.rego", 40, 3),
        )
        assertThat(jsonEntries(none)).isEmpty()
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
