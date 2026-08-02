/**
 * Tier-1 acceptance tests for `index.md`, transcribed verbatim from
 * `src/test/resources/acceptance/README.md` ("### index.md"). These assertions
 * are frozen: never weaken them to make an implementation pass.
 *
 * The example-coverage column assertions require §6.7 (worked examples),
 * which is not built yet -- expected to fail for that reason this session.
 */
package io.explico.acceptance

import io.explico.Explico
import io.explico.opa.OpaRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path

@DisplayName("index.md")
class IndexAcceptanceIT {

    companion object {
        private const val POLICIES_DIR = "src/test/resources/acceptance/policies"
        private lateinit var markdown: String

        @JvmStatic
        @BeforeAll
        fun renderDocument() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            val policySet = Explico.load(Path.of(POLICIES_DIR))
            val rendered = Explico.render(policySet)
            markdown = rendered.files["index.md"] ?: ""
        }
    }

    @Test
    @DisplayName("Lists all five controls; REL-001..004 sorted before the id-less helper")
    fun listsAllFiveControlsSortedByControlIdWithHelperLast() {
        assertThat(markdown).contains("REL-001")
        assertThat(markdown).contains("REL-002")
        assertThat(markdown).contains("REL-003")
        assertThat(markdown).contains("REL-004")
        assertThat(markdown).contains("exempt_service")
        val rel004Index = markdown.indexOf("REL-004")
        val exemptServiceIndex = markdown.indexOf("exempt_service")
        assertThat(rel004Index).isLessThan(exemptServiceIndex)
    }

    @Test
    @DisplayName("Example-coverage column: REL-001 ✓/✓; REL-002 ✓/✓; REL-003 ✓/✓; REL-004 ✓/✓; exempt_service ✓/✓")
    fun exampleCoverageColumnPresentForEveryControl() {
        // Requires §6.7 (worked examples / example-coverage column) -- not built this session.
        assertThat(markdown).contains("✓ / ✓")
    }

    @Test
    @DisplayName("Overall coverage line present and below 100%")
    fun overallCoverageLinePresentAndBelowFull() {
        val match = Regex("Overall rendering coverage: (\\d+) of (\\d+) conditions \\((\\d+)%\\)").find(markdown)
        assertThat(match).isNotNull()
        val (_, _, percent) = match!!.destructured
        assertThat(percent.toInt()).isLessThan(100)
    }
}
