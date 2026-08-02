/**
 * Tier-2 golden tests (spec §9): byte-exact comparison of every rendered document
 * against its file under `src/test/resources/acceptance/expected/`. `release-approvals.md`'s
 * golden was the ahead-of-time proposal, reconciled ONCE (session 5) -- the only
 * difference was a stray blank line from a YAML block-literal's trailing newline
 * (cosmetic, fixed in MarkdownRenderer), confirmed against real `opa` output to
 * now match byte-for-byte. The remaining five goldens are generated FROM that
 * approved renderer, not authored ahead of time.
 *
 * `-Dexplico.updateGolden=true` (see build.gradle.kts) regenerates every golden
 * instead of comparing -- a deliberate, reviewed act, never a side effect of
 * getting the build green.
 */
package io.explico.acceptance

import io.explico.Explico
import io.explico.opa.OpaRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class AcceptancePackGoldenIT {

    companion object {
        private const val POLICIES_DIR = "src/test/resources/acceptance/policies"
        private const val EXAMPLES_DIR = "src/test/resources/acceptance/examples"
        private const val DATA_FILE = "src/test/resources/acceptance/data/release/data.json"
        private const val EXPECTED_DIR = "src/test/resources/acceptance/expected"
        private lateinit var rendered: Map<String, String>

        @JvmStatic
        @BeforeAll
        fun renderAllDocuments() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            val policyDir = Path.of(POLICIES_DIR)
            val policySet = Explico.load(policyDir)
            val examples = Explico.loadExamples(Path.of(EXAMPLES_DIR))
            rendered = Explico.render(policySet, policyDir, examples, Path.of(DATA_FILE)).files
        }
    }

    private val updateGolden = System.getProperty("explico.updateGolden") == "true"

    private fun assertMatchesGolden(documentName: String) {
        val expectedFile = Path.of(EXPECTED_DIR, documentName)
        val actual = rendered.getValue(documentName)
        if (updateGolden) {
            Files.writeString(expectedFile, actual)
            return
        }
        assertThat(actual).isEqualTo(Files.readString(expectedFile))
    }

    @Test
    fun releaseApprovalsMatchesGolden() = assertMatchesGolden("release-approvals.md")

    @Test
    fun releaseEvidenceMatchesGolden() = assertMatchesGolden("release-evidence.md")

    @Test
    fun releaseProvenanceMatchesGolden() = assertMatchesGolden("release-provenance.md")

    @Test
    fun releaseGovernanceMatchesGolden() = assertMatchesGolden("release-governance.md")

    @Test
    fun releaseExemptionsMatchesGolden() = assertMatchesGolden("release-exemptions.md")

    @Test
    fun indexMatchesGolden() = assertMatchesGolden("index.md")
}
