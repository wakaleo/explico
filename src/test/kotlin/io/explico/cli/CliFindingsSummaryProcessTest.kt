/**
 * Process-level test for the terminal summary of the quality findings (spec §14.67): after the coverage line `render` prints `Documentation quality (see index.md):`
 * and one `  - ` line per finding, on stdout, only when there is one. A gate never suppresses it, and a clean policy prints exactly what it always did. Runs the real
 * packaged CLI like [CliGatesProcessTest]. Requires `opa`.
 */
package io.explico.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CliFindingsSummaryProcessTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(io.explico.opa.OpaRunner.isAvailable(), "opa binary not on PATH")
        }
    }

    private val withFindings = Path.of("src/test/resources/realistic/findings-summary/policies").toAbsolutePath().toString()
    private val examples = Path.of("src/test/resources/realistic/findings-summary/examples").toAbsolutePath().toString()
    private val samples = Path.of("samples/policies").toAbsolutePath().toString()

    private fun render(tempDir: Path, dir: String, vararg extra: String) = runCli(listOf("render", dir, "--out", tempDir.resolve("out").toString()) + extra, tempDir)

    private val full = listOf(
        "Documentation quality (see index.md):",
        "  - 1 control has no description",
        "  - 1 control names no framework",
        "  - 1 control has no matching example",
        "  - 1 control returns an object but declares no custom.verdict",
        "  - 1 comment block is not published",
        "  - 1 condition is shown as source",
        "  - 1 title is shared by several controls",
    )

    @Test
    @DisplayName("a policy with findings prints the block on stdout after the coverage line, one line per finding, and exits 0")
    fun printsTheBlock(@TempDir tempDir: Path) {
        val result = render(tempDir, withFindings, "--examples", examples)

        assertThat(result.exitCode).isEqualTo(0)
        val lines = result.stdout.lines().filter { it.isNotBlank() }
        assertThat(lines.first()).startsWith("Rendering coverage:")
        assertThat(lines.drop(1)).isEqualTo(full)
        assertThat(result.stderr).isEmpty()
    }

    @Test
    @DisplayName("without --examples the example-based lines are absent")
    fun withoutExamples(@TempDir tempDir: Path) {
        val result = render(tempDir, withFindings)

        assertThat(result.stdout.lines().filter { it.isNotBlank() }.drop(1)).containsExactly(
            full[0], full[1], full[2], full[5], full[6], full[7],
        )
    }

    @Test
    @DisplayName("a clean policy prints no block at all: the output is the coverage line only, as it always was")
    fun cleanPrintsNothing(@TempDir tempDir: Path) {
        val result = render(tempDir, samples)

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.stdout.lines().filter { it.isNotBlank() }).hasSize(1)
        assertThat(result.stdout).doesNotContain("Documentation quality")
    }

    @Test
    @DisplayName("--fail-on-gaps: exit 5, the gap lines on stderr as before, and the block still on stdout")
    fun gateDoesNotSuppress(@TempDir tempDir: Path) {
        val result = render(tempDir, withFindings, "--examples", examples, "--fail-on-gaps")

        assertThat(result.exitCode).isEqualTo(5)
        assertThat(result.stdout).contains(full.joinToString("\n"))
        assertThat(result.stderr).contains("control FS-1 has no description").contains("3 gap(s)")
        assertThat(result.stderr).doesNotContain("Documentation quality")
    }

    @Test
    @DisplayName("with --generate-examples the block comes after the generated-examples line")
    fun afterGeneratedExamples(@TempDir tempDir: Path) {
        val result = render(tempDir, withFindings, "--examples", examples, "--generate-examples")

        val lines = result.stdout.lines().filter { it.isNotBlank() }
        assertThat(lines.indexOfFirst { it.startsWith("Generated examples for") }).isGreaterThan(0).isLessThan(lines.indexOf(full[0]))
    }

    @Test
    @DisplayName("--json does not change what is printed, and --group-by-control counts a control once")
    fun withJsonAndGrouping(@TempDir tempDir: Path) {
        assertThat(render(tempDir, withFindings, "--examples", examples, "--json").stdout).contains(full.joinToString("\n"))
        val grouped = Path.of("src/test/resources/realistic/quality-hints/grouped/policies").toAbsolutePath().toString()
        val groupedExamples = Path.of("src/test/resources/realistic/quality-hints/grouped/examples").toAbsolutePath().toString()
        val result = render(tempDir, grouped, "--examples", groupedExamples, "--group-by-control")
        assertThat(result.stdout).contains("  - 1 control returns an object but declares no custom.verdict")
    }

    @Test
    @DisplayName("a policy whose only finding is a comment block prints just that line")
    fun onlyComments(@TempDir tempDir: Path) {
        val dir = Path.of("src/test/resources/realistic/unpublished-comments/policies").toAbsolutePath().toString()
        val result = render(tempDir, dir)

        assertThat(result.stdout.lines().filter { it.isNotBlank() }.drop(1)).containsExactly("Documentation quality (see index.md):", "  - 4 comment blocks are not published")
    }

    @Test
    @DisplayName("diff is unaffected: its stdout is still the summary table, with no quality block")
    fun diffUnaffected(@TempDir tempDir: Path) {
        val old = Path.of("src/test/resources/diff/diff-all-categories/old").toAbsolutePath().toString()
        val new = Path.of("src/test/resources/diff/diff-all-categories/new").toAbsolutePath().toString()
        val result = runCli(listOf("diff", old, new, "--out", tempDir.resolve("report.md").toString()), tempDir)

        assertThat(result.stdout).doesNotContain("Documentation quality")
    }
}
