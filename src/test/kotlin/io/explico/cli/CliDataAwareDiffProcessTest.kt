/**
 * Process-level test for `diff --old-data <file> --new-data <file>` (the data-aware diff, see RealisticDataAwareDiffAcceptanceIT for the contract): a change to
 * the reference data a control reads is a LOGIC_CHANGED control, so it reaches the report, the stdout summary and `--fail-on` (exit 5); a change to data no control
 * reads does not; giving only one of the two options is a usage error (exit 1, no stack trace, nothing written). Runs the real packaged CLI like
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

class CliDataAwareDiffProcessTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(io.explico.opa.OpaRunner.isAvailable(), "opa binary not on PATH")
        }

        private val base = Path.of("src/test/resources/realistic/data-aware-diff")
        private val policies = base.resolve("policies/old").toAbsolutePath().toString()
        private fun data(name: String) = base.resolve("data/$name.json").toAbsolutePath().toString()
    }

    private fun diff(tempDir: Path, vararg extra: String): Pair<CliResult, Path> {
        val out = tempDir.resolve("report.md")
        return runCli(listOf("diff", policies, policies, "--out", out.toString()) + extra, tempDir) to out
    }

    @Test
    @DisplayName("a changed limit: the report and the stdout summary count it as LOGIC_CHANGED, and --fail-on LOGIC_CHANGED exits 5 after writing the report")
    fun gateOnADataOnlyChange(@TempDir tempDir: Path) {
        val (result, out) = diff(tempDir, "--old-data", data("old"), "--new-data", data("limit-changed"), "--fail-on", "LOGIC_CHANGED")

        assertThat(result.exitCode).isEqualTo(5)
        assertThat(result.stdout).contains("| LOGIC_CHANGED | 1 |")
        assertThat(result.stderr).contains("BANK-LIM-001").doesNotContain("\tat ")
        assertThat(Files.readString(out)).contains("reference data changed: `data ▸ limits ▸ single approver minor`")
    }

    @Test
    @DisplayName("the same policies and the same gate WITHOUT the data options pass: it is the data that trips the gate")
    fun withoutDataTheGatePasses(@TempDir tempDir: Path) {
        val (result, _) = diff(tempDir, "--fail-on", "LOGIC_CHANGED")

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.stdout).contains("| LOGIC_CHANGED | 0 |")
    }

    @Test
    @DisplayName("a change to a data path no control reads passes the gate")
    fun unreadChangePasses(@TempDir tempDir: Path) {
        val (result, _) = diff(tempDir, "--old-data", data("old"), "--new-data", data("unread-changed"), "--fail-on", "LOGIC_CHANGED,DOCS_CHANGED")

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.stdout).contains("| UNCHANGED | 3 |")
    }

    @Test
    @DisplayName("--json carries the data line in the control's details")
    fun json(@TempDir tempDir: Path) {
        val json = tempDir.resolve("report.md.json")
        val (result, _) = diff(tempDir, "--old-data", data("old"), "--new-data", data("list-added"), "--json")

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(Files.readString(json)).contains("reference data changed: `data ▸ high risk countries`")
    }

    @Test
    @DisplayName("only one of --old-data / --new-data is a usage error: exit 1, both options named, no stack trace, nothing written")
    fun oneSideOnly(@TempDir tempDir: Path) {
        // Both together are accepted by the same options, so the failures below are about the MISSING one.
        assertThat(diff(tempDir, "--old-data", data("old"), "--new-data", data("old")).first.exitCode).isEqualTo(0)
        for (only in listOf(listOf("--old-data", data("old")), listOf("--new-data", data("old")))) {
            val out = tempDir.resolve("only-${only.first().removePrefix("--")}.md")
            val result = runCli(listOf("diff", policies, policies, "--out", out.toString()) + only, tempDir)

            assertThat(result.exitCode).describedAs(only.first()).isEqualTo(1)
            assertThat(result.stderr).describedAs(only.first()).contains("--old-data").contains("--new-data").doesNotContain("\tat ")
            assertThat(Files.exists(out)).describedAs(only.first()).isFalse()
        }
    }
}
