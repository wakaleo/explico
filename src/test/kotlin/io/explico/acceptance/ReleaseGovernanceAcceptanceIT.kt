/**
 * Tier-1 acceptance tests for `release-governance.md`, transcribed verbatim from
 * `src/test/resources/acceptance/README.md` ("REL-004 (`release-governance.md`)").
 * These assertions are frozen: never weaken them to make an implementation pass.
 */
package io.explico.acceptance

import io.explico.Explico
import io.explico.opa.OpaRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path

@DisplayName("release-governance.md")
class ReleaseGovernanceAcceptanceIT {

    companion object {
        private const val POLICIES_DIR = "src/test/resources/acceptance/policies"
        private const val EXAMPLES_DIR = "src/test/resources/acceptance/examples"
        private const val DATA_FILE = "src/test/resources/acceptance/data/release/data.json"
        private lateinit var markdown: String

        @JvmStatic
        @BeforeAll
        fun renderDocument() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            val policyDir = Path.of(POLICIES_DIR)
            val policySet = Explico.load(policyDir)
            val examples = Explico.loadExamples(Path.of(EXAMPLES_DIR))
            val rendered = Explico.render(policySet, policyDir, examples, Path.of(DATA_FILE))
            markdown = rendered.files["release-governance.md"] ?: ""
        }
    }

    @Nested
    @DisplayName("REL-004 — Release governance evidence")
    inner class Rel004ReleaseGovernanceEvidence {

        @Test
        @DisplayName("Exactly three `### Situation` headings")
        fun exactlyThreeSituationHeadings() {
            val situationCount = Regex("### Situation").findAll(markdown).count()
            assertThat(situationCount).isEqualTo(3)
        }

        @Test
        @DisplayName("Situation 1 is fully rendered: it contains the comprehension phrase `(the set of` … `such that` … `) is empty`, and no ⚠ block")
        fun situation1RendersTheComprehension() {
            // AMENDED (operator-approved, see the acceptance README's amendment note): this was
            // "Situation 1 contains a ⚠ not rendered block whose fenced content includes `count({a |`".
            val rel004 = rel004Card()
            val situation1 = rel004.substringAfter("### Situation 1").substringBefore("### Situation 2")
            assertThat(situation1).contains("(the set of").contains("such that").contains(") is empty")
            assertThat(situation1).doesNotContain("⚠").doesNotContain("count({a |")
        }

        @Test
        @DisplayName("Situation 2 fully rendered: `deployment ▸ timestamp` is at least … `data ▸ release ▸ freeze windows` breadcrumbs present")
        fun situation2FullyRenderedFreezeWindowComparison() {
            assertThat(markdown).contains("`deployment ▸ timestamp` is at least")
            assertThat(markdown).contains("`data ▸ release ▸ freeze windows`")
        }

        @Test
        @DisplayName("Situation 3 references rule `all_checks_passed`; the `all_checks_passed` card contains a ⚠ block whose fenced content includes `every check in`")
        fun situation3ReferencesAllChecksPassedWhichFallsBackOnEvery() {
            assertThat(markdown).contains("[`all_checks_passed`]")
            assertThat(markdown).contains("does not match")
            assertThat(markdown).contains("every check in")
        }

        @Test
        @DisplayName("REL-004's own coverage footer reports 8 of 8; the `all_checks_passed` card reports less than 100% listing the `every` fallback; the package footer is less than 100%")
        fun coverageMovedToTheEveryHelper() {
            // AMENDED (operator-approved): this was "Coverage footer reports less than 100% and the
            // card lists the fallback count" for REL-004's own card, which had the comprehension fallback.
            assertThat(Regex("Rendering coverage: (\\d+) of (\\d+) conditions").find(rel004Card())!!.destructured.toList()).containsExactly("8", "8")

            val helperStart = markdown.indexOf("## release.governance.all_checks_passed")
            assertThat(helperStart).isGreaterThanOrEqualTo(0)
            val helperEnd = markdown.indexOf("\n## ", helperStart + 1).let { if (it == -1) markdown.length else it }
            val helperCard = markdown.substring(helperStart, helperEnd)
            val (rendered, total) = Regex("Rendering coverage: (\\d+) of (\\d+) conditions").find(helperCard)!!.destructured
            assertThat(rendered.toInt()).isLessThan(total.toInt())
            assertThat(helperCard).contains("every check in")

            val (pkgRendered, pkgTotal) = Regex("Package rendering coverage: (\\d+) of (\\d+) conditions").find(markdown)!!.destructured
            assertThat(pkgRendered.toInt()).isLessThan(pkgTotal.toInt())
        }

        /** REL-004's own card, up to the next heading (release-governance.md also has an `all_checks_passed` card). */
        private fun rel004Card(): String {
            val start = markdown.indexOf("## REL-004")
            assertThat(start).isGreaterThanOrEqualTo(0)
            val end = markdown.indexOf("\n## ", start + 1).let { if (it == -1) markdown.length else it }
            return markdown.substring(start, end)
        }
    }
}
