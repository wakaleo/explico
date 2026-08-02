/**
 * Tier-1 acceptance tests for `release-evidence.md`, transcribed verbatim from
 * `src/test/resources/acceptance/README.md` ("REL-002 (`release-evidence.md`)").
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

@DisplayName("release-evidence.md")
class ReleaseEvidenceAcceptanceIT {

    companion object {
        private const val POLICIES_DIR = "src/test/resources/acceptance/policies"
        private lateinit var markdown: String

        @JvmStatic
        @BeforeAll
        fun renderDocument() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            val policySet = Explico.load(Path.of(POLICIES_DIR))
            val rendered = Explico.render(policySet)
            markdown = rendered.files["release-evidence.md"] ?: ""
        }
    }

    @Nested
    @DisplayName("REL-002 — Pipeline evidence")
    inner class Rel002PipelineEvidence {

        @Test
        @DisplayName("Contains a rendered `for some stage in` line referencing `pipeline ▸ stages` and a var-rooted condition rendering as `pipeline ▸ stages ▸ [each stage] ▸ status` is not `\"passed\"`")
        fun someInAndVarRootedStatusCondition() {
            assertThat(markdown).contains("for some stage in `pipeline ▸ stages`")
            assertThat(markdown).contains("`pipeline ▸ stages ▸ [each stage] ▸ status` is not `\"passed\"`")
        }

        @Test
        @DisplayName("Contains: `deployment ▸ environment` is one of `\"production\"`, `\"staging\"` (inside the `is_release_candidate` card)")
        fun environmentIsOneOfProductionOrStaging() {
            assertThat(markdown).contains("`deployment ▸ environment` is one of `\"production\", \"staging\"`")
        }

        @Test
        @DisplayName("Contains a cross-package link: rule [`exempt_service`](release-exemptions.md#…) does not match")
        fun crossPackageLinkToExemptService() {
            assertThat(markdown).contains("[`exempt_service`](release-exemptions.md#")
            assertThat(markdown).contains(") does not match")
        }

        @Test
        @DisplayName("No ⚠ fallback blocks")
        fun noFallbackBlocks() {
            assertThat(markdown).doesNotContain("⚠")
        }
    }
}
