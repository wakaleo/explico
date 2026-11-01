/**
 * Process-level test for the diff report header (`**Compared**`, `--old-label`, `--new-label`; see RealisticDiffHeaderAcceptanceIT for the contract): the CLI always
 * passes the policy directories, so its report always names the two digests; labels appear in the bullets; an invalid label is a usage error (exit 1, the option
 * named, no stack trace, nothing written). Runs the real packaged CLI like [CliGroupByControlDiffProcessTest]. Requires `opa`.
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

class CliDiffHeaderProcessTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(io.explico.opa.OpaRunner.isAvailable(), "opa binary not on PATH")
        }

        private val base = Path.of("src/test/resources/realistic/metadata-with-logic")
        private val old = base.resolve("old").toAbsolutePath().toString()
        private val new = base.resolve("new").toAbsolutePath().toString()
        private val sha = Regex("- (Old|New)( \\([^)]*\\))?: `sha256:[0-9a-f]{64}`")
    }

    private fun diff(tempDir: Path, vararg extra: String): Pair<CliResult, Path> {
        val out = tempDir.resolve("report.md")
        return runCli(listOf("diff", old, new, "--out", out.toString()) + extra, tempDir) to out
    }

    @Test
    @DisplayName("without any option the report names both digests under a Compared heading")
    fun alwaysThere(@TempDir tempDir: Path) {
        val (result, out) = diff(tempDir)

        assertThat(result.exitCode).isEqualTo(0)
        val report = Files.readString(out)
        assertThat(report).contains("**Compared**")
        assertThat(sha.findAll(report).map { it.groupValues[1] }.toList()).containsExactly("Old", "New")
        assertThat(report.lines().indexOf("**Compared**")).isLessThan(report.lines().indexOf("## Summary"))
    }

    @Test
    @DisplayName("--old-label and --new-label name the versions in the bullets, and --json carries them")
    fun labels(@TempDir tempDir: Path) {
        val json = tempDir.resolve("report.md.json")
        val (result, out) = diff(tempDir, "--old-label", "v1.2", "--new-label", "v1.3", "--json")

        assertThat(result.exitCode).isEqualTo(0)
        val report = Files.readString(out)
        assertThat(report).contains("- Old (v1.2): `sha256:").contains("- New (v1.3): `sha256:")
        assertThat(Files.readString(json)).contains("\"label\": \"v1.2\"").contains("\"label\": \"v1.3\"")
    }

    @Test
    @DisplayName("one label alone is fine")
    fun oneLabel(@TempDir tempDir: Path) {
        val (result, out) = diff(tempDir, "--new-label", "candidate")

        assertThat(result.exitCode).isEqualTo(0)
        val report = Files.readString(out)
        assertThat(report).contains("- New (candidate): `sha256:").contains("- Old: `sha256:")
    }

    @Test
    @DisplayName("an invalid label is a usage error: exit 1, the option named, no stack trace, nothing written")
    fun invalidLabel(@TempDir tempDir: Path) {
        // A good label is accepted by the same option, so the failures below are about the VALUE.
        assertThat(diff(tempDir, "--old-label", "v1.2").first.exitCode).isEqualTo(0)
        for ((option, value) in listOf("--old-label" to "bad`tick", "--new-label" to " leading", "--old-label" to "x".repeat(81))) {
            val out = tempDir.resolve("bad-${option.removePrefix("--")}-${value.length}.md")
            val result = runCli(listOf("diff", old, new, "--out", out.toString(), option, value), tempDir)

            assertThat(result.exitCode).describedAs("$option '$value'").isEqualTo(1)
            assertThat(result.stderr).describedAs("$option '$value'").contains(option).doesNotContain("\tat ")
            assertThat(Files.exists(out)).describedAs("$option '$value'").isFalse()
        }
    }
}
