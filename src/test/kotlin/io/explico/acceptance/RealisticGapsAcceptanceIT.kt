/**
 * Acceptance tests for `RenderedDocs.gaps`: the documentation gaps (a control with no description or no framework, a title shared
 * by several controls, a listed requirement with no control, a framework string not on the list) as one-line descriptions that the
 * library words ONCE, so the CLI gate (`--fail-on-gaps`), a pipeline script and a dashboard all say the same thing. Always computed;
 * empty for complete documentation. Authored from a compliance-owner request for CI gates, not from the frozen README contract.
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

@DisplayName("Realistic policies: documentation gaps")
class RealisticGapsAcceptanceIT {

    companion object {
        private val pack = Path.of("src/test/resources/acceptance/policies")
        private val quality = Path.of("src/test/resources/realistic/quality/policies")

        private val noControlForNist = listOf("SOC 2 CC8.1", "ISO 27001 A.8.32", "DORA Art. 9", "SLSA v1.0 L2", "NIST 800-53 CM-3")
        private val typos = listOf("SOC 2 CC8.1", "ISO 27001 A.8.32", "DORA Art 9", "SLSA v1.0 L2")
        private val covered = listOf("SOC 2 CC8.1", "ISO 27001 A.8.32", "DORA Art. 9", "SLSA v1.0 L2")

        private fun gaps(dir: Path, requirements: List<String> = emptyList()): List<String> {
            val set = Explico.load(dir)
            return Explico.render(set, dir, null, null, false, false, requirements).gaps
        }
    }

    @Nested
    @DisplayName("Documentation quality gaps")
    inner class QualityGaps {

        @Test
        @DisplayName("the quality fixture: a missing description, a missing framework, then the shared title, in that order")
        fun qualityFixture() {
            assertThat(gaps(quality)).containsExactly(
                "control Q-003 has no description",
                "control Q-002 names no framework",
                "controls Q-001, Q-002 share the title \"Shared\"",
            )
        }

        @Test
        @DisplayName("the acceptance pack is fully documented: no gaps")
        fun cleanPack() {
            assertThat(gaps(pack)).isEmpty()
        }
    }

    @Nested
    @DisplayName("Requirement gaps")
    inner class RequirementGaps {

        @Test
        @DisplayName("a listed requirement no control names is a gap")
        fun requirementWithNoControl() {
            assertThat(gaps(pack, noControlForNist)).containsExactly("requirement NIST 800-53 CM-3 has no control")
        }

        @Test
        @DisplayName("a typo shows up twice: the misspelt requirement has no control, and the real string is not on the list")
        fun typo() {
            assertThat(gaps(pack, typos)).containsExactly(
                "requirement DORA Art 9 has no control",
                "framework string DORA Art. 9 is not in the requirements list",
            )
        }

        @Test
        @DisplayName("when every requirement is covered and every framework string listed, there are no gaps")
        fun allCovered() {
            assertThat(gaps(pack, covered)).isEmpty()
        }

        @Test
        @DisplayName("requirement gaps come AFTER the quality gaps")
        fun orderQualityThenRequirements() {
            val all = gaps(quality, listOf("SOC 2 CC8.1", "NIST 800-53 CM-3"))
            assertThat(all.take(3)).allMatch { it.startsWith("control") }
            assertThat(all.drop(3)).containsExactly(
                "requirement NIST 800-53 CM-3 has no control",
                "framework string ISO 27001 A.8.32 is not in the requirements list",
            )
        }
    }

    @Nested
    @DisplayName("Properties")
    inner class Properties {

        @Test
        @DisplayName("deterministic: two runs give the same list")
        fun deterministic() {
            val first = gaps(quality, noControlForNist)
            assertThat(first).isNotEmpty()
            assertThat(gaps(quality, noControlForNist)).isEqualTo(first)
        }
    }
}
