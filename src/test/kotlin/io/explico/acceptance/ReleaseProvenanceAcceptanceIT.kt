/**
 * Tier-1 acceptance tests for `release-provenance.md`, transcribed verbatim from
 * `src/test/resources/acceptance/README.md` ("REL-003 (`release-provenance.md`)").
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

@DisplayName("release-provenance.md")
class ReleaseProvenanceAcceptanceIT {

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
            markdown = rendered.files["release-provenance.md"] ?: ""
        }
    }

    @Nested
    @DisplayName("REL-003 — Artifact provenance")
    inner class Rel003ArtifactProvenance {

        @Test
        @DisplayName("Contains: `artifact ▸ source branch` and `does not start with `\"release/\"`` (negated builtin phrasing)")
        fun negatedStartswithPhrasing() {
            assertThat(markdown).contains("`artifact ▸ source branch`")
            assertThat(markdown).contains("does not start with `\"release/\"`")
        }

        @Test
        @DisplayName("Contains quoted key-literal breadcrumb: `artifact ▸ labels ▸ \"signed-off-by\"`")
        fun quotedKeyLiteralBreadcrumb() {
            assertThat(markdown).contains("`artifact ▸ labels ▸ \"signed-off-by\"`")
        }

        @Test
        @DisplayName("Situation 2 *Produces:* the literal string `artifact carries no signed-off-by label`")
        fun situation2ProducesLiteralString() {
            assertThat(markdown).contains("*Produces:* \"artifact carries no signed-off-by label\"")
        }
    }
}
