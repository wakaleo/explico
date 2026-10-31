/**
 * Process-level test for `diff --examples <dir>` and `--fail-on-example-changes` (the per-fixture verdict diff, see RealisticFixtureVerdictDiffAcceptanceIT for the
 * contract): the report gains the `## Behaviour on your examples` section; the gate exits 5 when any example changed outcome, with the report still written; nothing
 * flipping passes it; the flag without `--examples` is a usage error (exit 1, no stack trace, nothing written). Runs the real packaged CLI like
 * [CliGroupByControlDiffProcessTest]. Requires `opa`.
 */
package io.explico.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CliFixtureVerdictDiffProcessTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(io.explico.opa.OpaRunner.isAvailable(), "opa binary not on PATH")
        }

        private val base = Path.of("src/test/resources/realistic/fixture-verdict-diff")
        private val old = base.resolve("policies/old").toAbsolutePath().toString()
        private val new = base.resolve("policies/new").toAbsolutePath().toString()
        private val examples = base.resolve("examples").toAbsolutePath().toString()
    }

    private fun diff(tempDir: Path, oldDir: String, newDir: String, vararg extra: String): Pair<CliResult, Path> {
        val out = tempDir.resolve("report.md")
        return runCli(listOf("diff", oldDir, newDir, "--out", out.toString()) + extra, tempDir) to out
    }

    @Test
    @DisplayName("--examples adds the Behaviour section to the report and does not gate by itself: flipped fixtures still exit 0")
    fun examplesAlone(@TempDir tempDir: Path) {
        val (result, out) = diff(tempDir, old, new, "--examples", examples)

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(Files.readString(out)).contains("## Behaviour on your examples").contains("standing order pre-approved by Treasury: ✅ allowed -> ❌ denied")
    }

    @Test
    @DisplayName("--fail-on-example-changes: exit 5 when examples flipped, naming the counts and the controls on stderr, after writing the report")
    fun gateFails(@TempDir tempDir: Path) {
        val (result, out) = diff(tempDir, old, new, "--examples", examples, "--fail-on-example-changes")

        assertThat(result.exitCode).isEqualTo(5)
        assertThat(result.stderr).contains("gate failed (--fail-on-example-changes)").contains("3 example(s)").contains("3 control(s)")
            .contains("BANK-EXM-001").contains("BANK-TGT-001").contains("BANK-MSG-001").doesNotContain("\tat ")
        assertThat(result.stdout).contains("| LOGIC_CHANGED |")
        assertThat(Files.readString(out)).contains("## Behaviour on your examples")
    }

    @Test
    @DisplayName("--fail-on-example-changes passes (exit 0) when no example changed outcome")
    fun gatePasses(@TempDir tempDir: Path) {
        val (result, out) = diff(tempDir, old, old, "--examples", examples, "--fail-on-example-changes")

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(Files.readString(out)).contains("No example changed outcome.")
    }

    @Test
    @DisplayName("--fail-on-example-changes without --examples is a usage error: exit 1, both options named, no stack trace, nothing written")
    fun flagNeedsExamples(@TempDir tempDir: Path) {
        // The flag is accepted together with --examples, so the failure below is about the MISSING --examples.
        assertThat(diff(tempDir, old, old, "--examples", examples, "--fail-on-example-changes").first.exitCode).isEqualTo(0)
        val out = tempDir.resolve("only-flag.md")
        val result = runCli(listOf("diff", old, new, "--out", out.toString(), "--fail-on-example-changes"), tempDir)

        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.stderr).contains("--examples").contains("--fail-on-example-changes").doesNotContain("\tat ")
        assertThat(Files.exists(out)).isFalse()
    }

    @Test
    @DisplayName("data is used per side: identical policies and a lower limit in --new-data flip two examples of BANK-LIM-001, and the gate follows")
    fun dataDriven(@TempDir tempDir: Path) {
        val (result, out) = diff(
            tempDir, old, old, "--examples", examples, "--old-data", base.resolve("data/old.json").toAbsolutePath().toString(),
            "--new-data", base.resolve("data/new.json").toAbsolutePath().toString(), "--fail-on-example-changes",
        )

        assertThat(result.exitCode).isEqualTo(5)
        assertThat(result.stderr).contains("2 example(s)").contains("1 control(s)").contains("BANK-LIM-001")
        assertThat(Files.readString(out)).contains("mid-value payment without a second approver: ✅ allowed -> ❌ denied")
    }
}
