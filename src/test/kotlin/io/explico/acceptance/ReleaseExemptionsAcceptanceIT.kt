/**
 * Tier-1 acceptance tests for `release-exemptions.md`, transcribed verbatim from
 * `src/test/resources/acceptance/README.md` ("Helper with no metadata
 * (`release-exemptions.md`)"). These assertions are frozen: never weaken them to
 * make an implementation pass.
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

@DisplayName("release-exemptions.md")
class ReleaseExemptionsAcceptanceIT {

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
            markdown = rendered.files["release-exemptions.md"] ?: ""
        }
    }

    @Nested
    @DisplayName("Helper with no metadata")
    inner class HelperWithNoMetadata {

        @Test
        @DisplayName("Heading falls back to `## release.exemptions.exempt_service`")
        fun headingFallsBackToPackageDotRuleName() {
            assertThat(markdown).contains("## release.exemptions.exempt_service")
        }

        @Test
        @DisplayName("Contains the marker `*No description provided in policy metadata.*`")
        fun noDescriptionMarkerPresent() {
            assertThat(markdown).contains("*No description provided in policy metadata.*")
        }

        @Test
        @DisplayName("Contains: `deployment ▸ service` is one of … `data ▸ release ▸ exempt services`")
        fun deploymentServiceIsOneOfExemptServices() {
            assertThat(markdown).contains("`deployment ▸ service` is one of")
            assertThat(markdown).contains("`data ▸ release ▸ exempt services`")
        }
    }
}
