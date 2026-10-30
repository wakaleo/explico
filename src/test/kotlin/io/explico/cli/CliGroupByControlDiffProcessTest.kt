/**
 * Process-level test for `diff --group-by-control` (spec §14.57): a control id shared by several rules is exit 4 without the flag (as always) and a
 * normal report with it -- written to `--out`, summary on stdout, `--json` and `--fail-on` working on the per-rule entries. Runs the real packaged CLI
 * like [CliGroupByControlProcessTest]. Requires `opa`.
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

class CliGroupByControlDiffProcessTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(io.explico.opa.OpaRunner.isAvailable(), "opa binary not on PATH")
        }
    }

    private val old = Path.of("src/test/resources/realistic/group-by-control-diff/old").toAbsolutePath().toString()
    private val new = Path.of("src/test/resources/realistic/group-by-control-diff/new").toAbsolutePath().toString()

    private fun diff(tempDir: Path, vararg extra: String): Pair<CliResult, Path> {
        val out = tempDir.resolve("report.md")
        return runCli(listOf("diff", old, new, "--out", out.toString()) + extra, tempDir) to out
    }

    @Test
    @DisplayName("without the flag a shared control id is exit 4 naming the ids, and nothing is written")
    fun withoutTheFlag(@TempDir tempDir: Path) {
        val (result, out) = diff(tempDir)

        assertThat(result.exitCode).isEqualTo(4)
        assertThat(result.stderr).contains("SEC-28").contains("SEC-40").doesNotContain("\tat ")
        assertThat(Files.exists(out)).isFalse()
    }

    @Test
    @DisplayName("the exit-4 message says how to proceed when the shared id is deliberate")
    fun hint(@TempDir tempDir: Path) {
        val (result, _) = diff(tempDir)

        assertThat(result.exitCode).isEqualTo(4)
        assertThat(result.stderr).contains("--group-by-control")
    }

    @Test
    @DisplayName("with the flag: exit 0, the report is written, and stdout is the summary table")
    fun withTheFlag(@TempDir tempDir: Path) {
        val (result, out) = diff(tempDir, "--group-by-control")

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.stderr).doesNotContain("Exception")
        assertThat(result.stdout).contains("| ADDED | 2 |").contains("| LOGIC_CHANGED | 3 |").contains("| UNCHANGED | 6 |")
        assertThat(Files.readString(out)).contains("## SEC-28 — Sign-off (grp_signoff.signed_off)")
    }

    @Test
    @DisplayName("--json carries one change per rule, naming package and rule")
    fun withJson(@TempDir tempDir: Path) {
        val json = tempDir.resolve("report.json")
        val (result, _) = diff(tempDir, "--group-by-control", "--json", json.toString())

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(Files.readString(json)).contains("\"rule\": \"signed_off\"").contains("\"package\": \"grp_signoff\"")
    }

    @Test
    @DisplayName("--fail-on still gates (exit 5, output written), listing each failing rule of a shared control by id and package.rule")
    fun failOn(@TempDir tempDir: Path) {
        val (result, out) = diff(tempDir, "--group-by-control", "--fail-on", "REMOVED,ADDED")

        assertThat(result.exitCode).isEqualTo(5)
        assertThat(result.stderr).contains("SEC-40 (grp_life.drop)").contains("SEC-40 (grp_life.fresh)").contains("SEC-50 (grp_grow.gate_extra)")
        assertThat(Files.exists(out)).isTrue()
    }
}
