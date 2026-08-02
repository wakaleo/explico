/**
 * Tier-1 acceptance tests for `release-approvals.md`, transcribed verbatim from
 * `src/test/resources/acceptance/README.md` ("REL-001 (`release-approvals.md`)").
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

@DisplayName("release-approvals.md")
class ReleaseApprovalsAcceptanceIT {

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
            markdown = rendered.files["release-approvals.md"] ?: ""
        }
    }

    @Nested
    @DisplayName("REL-001 — Production change approval")
    inner class Rel001ProductionChangeApproval {

        @Test
        @DisplayName("Heading `## REL-001 — Production change approval`; frameworks line lists SOC 2 CC8.1 and ISO 27001 A.8.32")
        fun headingAndFrameworksLine() {
            assertThat(markdown).contains("## REL-001 — Production change approval")
            assertThat(markdown).contains("SOC 2 CC8.1")
            assertThat(markdown).contains("ISO 27001 A.8.32")
        }

        @Test
        @DisplayName("Exactly two `### Situation` headings")
        fun exactlyTwoSituationHeadings() {
            val situationCount = Regex("### Situation").findAll(markdown).count()
            assertThat(situationCount).isEqualTo(2)
        }

        @Test
        @DisplayName("Contains: `deployment ▸ environment` is `\"production\"`")
        fun deploymentEnvironmentIsProduction() {
            assertThat(markdown).contains("`deployment ▸ environment` is `\"production\"`")
        }

        @Test
        @DisplayName("Contains: `change ▸ ticket ▸ approved` is absent or false")
        fun changeTicketApprovedIsAbsentOrFalse() {
            assertThat(markdown).contains("`change ▸ ticket ▸ approved` is absent or false")
        }

        @Test
        @DisplayName("Contains: `change ▸ author` is `change ▸ approver` (path on BOTH sides)")
        fun changeAuthorIsChangeApprover() {
            assertThat(markdown).contains("`change ▸ author` is `change ▸ approver`")
        }

        @Test
        @DisplayName("Produces lines: the two denial messages")
        fun producesBothDenialMessages() {
            assertThat(markdown).contains("release [deployment id] has no approved change ticket")
            assertThat(markdown).contains("change [change id] was approved by its author")
        }

        @Test
        @DisplayName("Coverage footer reports 4 of 4; no ⚠ fallback blocks anywhere in the file")
        fun fullCoverageAndNoFallbackBlocks() {
            assertThat(markdown).contains("4 of 4")
            assertThat(markdown).doesNotContain("⚠")
        }
    }
}
