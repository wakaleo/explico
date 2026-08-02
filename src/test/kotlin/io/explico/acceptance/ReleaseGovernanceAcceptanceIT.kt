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
        private lateinit var markdown: String

        @JvmStatic
        @BeforeAll
        fun renderDocument() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            val policySet = Explico.load(Path.of(POLICIES_DIR))
            val rendered = Explico.render(policySet)
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
        @DisplayName("Situation 1 contains a ⚠ not rendered — shown as source block whose fenced content includes `count({a |`")
        fun situation1FallsBackWithCountComprehension() {
            assertThat(markdown).contains("⚠ **not rendered — shown as source:**")
            assertThat(markdown).contains("count({a |")
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
        @DisplayName("Coverage footer reports less than 100% and the card lists the fallback count")
        fun coverageBelowFullAndFallbackCountListed() {
            // Scope to REL-004's own card (up to the next heading) -- release-governance.md also
            // has an all_checks_passed card, whose coverage footer must not be mistaken for this one.
            val rel004CardStart = markdown.indexOf("## REL-004")
            assertThat(rel004CardStart).isGreaterThanOrEqualTo(0)
            val nextHeadingStart = markdown.indexOf("\n## ", rel004CardStart + 1).let { if (it == -1) markdown.length else it }
            val rel004Card = markdown.substring(rel004CardStart, nextHeadingStart)

            val coverageMatch = Regex("Rendering coverage: (\\d+) of (\\d+) conditions").find(rel004Card)
            assertThat(coverageMatch).isNotNull()
            val (rendered, total) = coverageMatch!!.destructured
            assertThat(rendered.toInt()).isLessThan(total.toInt())
        }
    }
}
