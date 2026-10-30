/**
 * Acceptance tests for making the quality findings visible and actionable. `RenderedDocs.findings` is one line per kind of finding the index's *Documentation quality*
 * section shows, worded once in the library (like `gaps`), so a caller -- the CLI prints them after the coverage line -- can tell an author what is suboptimal without
 * opening `index.md`; and the three older bullets (missing description, no framework, duplicate title) now end with a fix hint like the newer ones. Decisions (to be confirmed):
 *
 * - order = the section's bullet order; each line's number is the bullet's N (controls, blocks, total conditions) -- except shared titles, where it is the NUMBER OF TITLES
 *   (a "Duplicate title" bullet per title, whose own N counts the controls sharing it);
 * - singular and plural worded properly; empty when the section is absent; not gaps; example-based kinds only when examples were supplied (as the bullets);
 * - this DELIBERATELY changes the text of three existing non-Tier-1 tests that pin the old bullets (IndexLayoutTest, RealisticQualityAndRequirementsAcceptanceIT,
 *   RealisticGroupByControlAcceptanceIT); they are repointed in /tdd, not here.
 * Authored from the plan, NOT from the frozen acceptance-pack README. Facade only. Fixtures: `realistic/findings-summary/policies` (one of each kind: FS-1 no description,
 * FS-2 no framework, FS-3 never matched, FS-4 raw object, FS-5 a `with`, FS-6/FS-7 one shared title, one comment block), `.../plural/policies` (two of each kind, two shared
 * titles) and `samples/policies` (clean: nothing to report).
 */
package io.explico.acceptance

import io.explico.Explico
import io.explico.opa.OpaRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

@DisplayName("Realistic policies: findings you can see and act on")
class RealisticFindingsSummaryAcceptanceIT {

    companion object {
        private val base = Path.of("src/test/resources/realistic/findings-summary")
        private val clean = Path.of("samples/policies") // the acceptance-pack sample: nothing to report (spec §14.71: quality-hints has one unrendered value, QH-7's `name`)

        private lateinit var single: io.explico.RenderedDocs
        private lateinit var singleAgain: io.explico.RenderedDocs
        private lateinit var singleNoExamples: io.explico.RenderedDocs
        private lateinit var plural: io.explico.RenderedDocs
        private lateinit var cleanDocs: io.explico.RenderedDocs

        private fun render(dir: Path, examples: Path?): io.explico.RenderedDocs {
            val set = Explico.load(dir)
            return Explico.render(set, dir, examples?.let { Explico.loadExamples(it) })
        }

        @JvmStatic
        @BeforeAll
        fun renderAll() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            single = render(base.resolve("policies"), base.resolve("examples"))
            singleAgain = render(base.resolve("policies"), base.resolve("examples"))
            singleNoExamples = render(base.resolve("policies"), null)
            plural = render(base.resolve("plural/policies"), base.resolve("plural/examples"))
            cleanDocs = render(clean, null)
        }

        /** The top-level bullets of the index's quality section, as written. */
        private fun bullets(docs: io.explico.RenderedDocs): List<String> {
            val lines = docs.files.getValue("index.md").lines()
            val start = lines.indexOf("## Documentation quality")
            if (start < 0) return emptyList()
            return lines.drop(start + 1).takeWhile { !it.startsWith("## ") }.filter { it.startsWith("- ") }
        }

        private fun leadingNumber(text: String): Int = Regex("^\\d+").find(text)!!.value.toInt()
    }

    @Test
    @DisplayName("one of each kind: the findings read as singular sentences, in the section's order")
    fun singular() {
        assertThat(single.findings).containsExactly(
            "1 control has no description",
            "1 control names no framework",
            "1 control has no matching example",
            "1 control returns an object but declares no custom.verdict",
            "1 comment block is not published",
            "1 condition is shown as source",
            "1 title is shared by several controls",
        )
    }

    @Test
    @DisplayName("two of each kind: the same sentences in the plural, and two shared titles")
    fun pluralWording() {
        assertThat(plural.findings).containsExactly(
            "2 controls have no description",
            "2 controls name no framework",
            "2 controls have no matching example",
            "2 controls return an object but declare no custom.verdict",
            "2 comment blocks are not published",
            "2 conditions are shown as source",
            "2 titles are shared by several controls",
        )
    }

    @Test
    @DisplayName("without example fixtures the example-based kinds are left out, exactly as in the index")
    fun withoutExamples() {
        assertThat(singleNoExamples.findings).containsExactly(
            "1 control has no description",
            "1 control names no framework",
            "1 comment block is not published",
            "1 condition is shown as source",
            "1 title is shared by several controls",
        )
    }

    @Test
    @DisplayName("nothing to report: no findings, and no Documentation quality section")
    fun clean() {
        assertThat(cleanDocs.findings).isEmpty()
        assertThat(cleanDocs.files.getValue("index.md")).doesNotContain("## Documentation quality")
    }

    @Test
    @DisplayName("each line's number is the one the index shows: the bullet's N, or for shared titles the number of such bullets")
    fun numbersMatchTheIndex() {
        for (docs in listOf(single, plural, singleNoExamples)) {
            val shown = bullets(docs)
            val perKind = shown.filter { !it.startsWith("- **Duplicate title") }.map { Regex("\\((\\d+)\\)").find(it)!!.groupValues[1].toInt() } +
                listOf(shown.count { it.startsWith("- **Duplicate title") }).filter { it > 0 }
            assertThat(docs.findings.map { leadingNumber(it) }).isEqualTo(perKind)
        }
    }

    @Test
    @DisplayName("the three older bullets end with a fix hint, like the newer ones")
    fun hintedBullets() {
        assertThat(bullets(single)).contains(
            "- **Missing description (1):** [FS-1](fs.md#fs-1) — add a description to its METADATA",
            "- **No framework (1):** [FS-2](fs.md#fs-2) — list the requirements it supports under custom.frameworks in its METADATA",
            "- **Duplicate title (2):** [FS-6](fs.md#fs-6), [FS-7](fs.md#fs-7) -- \"Shared title\" — give each control its own title",
        )
        val titles = bullets(plural).filter { it.startsWith("- **Duplicate title") }
        assertThat(titles).hasSize(2)
        assertThat(titles).allSatisfy { assertThat(it).endsWith(" — give each control its own title") }
    }

    @Test
    @DisplayName("findings are advisory: gaps stay what they were (a description, a framework, the shared title) whatever the findings say")
    fun gapsUnchanged() {
        assertThat(single.gaps).containsExactly(
            "control FS-1 has no description", "control FS-2 names no framework", "controls FS-6, FS-7 share the title \"Shared title\"",
        )
        assertThat(cleanDocs.gaps).isEmpty()
    }

    @Test
    @DisplayName("two renders give the same findings")
    fun deterministic() {
        assertThat(singleAgain.findings).isEqualTo(single.findings)
        assertThat(singleAgain.files.toList()).isEqualTo(single.files.toList())
    }

    @Test
    @DisplayName("the five-package sample (docs/sample-output) renders byte-for-byte as committed, with no findings")
    fun sampleOutputUntouched() {
        val dir = Path.of("samples/policies")
        val docs = Explico.render(Explico.load(dir), dir, Explico.loadExamples(Path.of("samples/examples")), Path.of("samples/data/release/data.json"))
        assertThat(docs.findings).isEmpty()
        for ((name, text) in docs.files) {
            assertThat(text).describedAs("docs/sample-output/$name").isEqualTo(Files.readString(Path.of("docs/sample-output").resolve(name)))
        }
    }
}
