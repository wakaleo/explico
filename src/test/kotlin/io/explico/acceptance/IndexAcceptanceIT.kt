/**
 * Tier-1 acceptance tests for `index.md`, transcribed verbatim from
 * `src/test/resources/acceptance/README.md` ("### index.md"). These assertions
 * are frozen: never weaken them to make an implementation pass.
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
        for (control in listOf("REL-001", "REL-002", "REL-003", "REL-004", "exempt_service")) {
            val row = markdown.lines().firstOrNull { it.contains(control) }
            assertThat(row).describedAs("index.md row for %s", control).isNotNull()
            assertThat(row).describedAs("example-coverage column for %s", control).contains("✓ / ✓")
        }
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
