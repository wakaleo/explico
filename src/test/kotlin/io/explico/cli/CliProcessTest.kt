/**
 * Process-level tests for the CLI (spec §8.2): the real, packaged `render`/`diff`/`version`
 * commands invoked as a separate OS process against the acceptance pack (or purpose-built
 * fixtures) in a temp directory, proving the exit-code contract end-to-end -- not just that the
 * underlying library functions throw the right exception types. Requires `opa` on PATH.
 */
package io.explico.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CliProcessTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(io.explico.opa.OpaRunner.isAvailable(), "opa binary not on PATH")
        }
    }

    private val policiesDir = Path.of("src/test/resources/acceptance/policies").toAbsolutePath()
    private val duplicateControlIdsDir = Path.of("src/test/resources/diff/policydiff/duplicate-control-ids").toAbsolutePath()

    @Test
    fun renderSucceedsAndPrintsCoverageSummary(@TempDir tempDir: Path) {
        val out = tempDir.resolve("out")
        val result = runCli(listOf("render", policiesDir.toString(), "--out", out.toString()), tempDir)

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.stdout).contains("Rendering coverage:")
        assertThat(Files.exists(out.resolve("index.md"))).isTrue()
        assertThat(Files.exists(out.resolve("release-approvals.md"))).isTrue()
    }

    @Test
    fun renderOutIsFullyReplacedDeletingStaleFilesFromAPreviousRun(@TempDir tempDir: Path) {
        val out = tempDir.resolve("out")
        Files.createDirectories(out.resolve("stale-subdir"))
        Files.writeString(out.resolve("stale-file.md"), "leftover from a previous run")
        Files.writeString(out.resolve("stale-subdir/nested.md"), "leftover nested file")

        val result = runCli(listOf("render", policiesDir.toString(), "--out", out.toString()), tempDir)

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(Files.exists(out.resolve("stale-file.md"))).isFalse()
        assertThat(Files.exists(out.resolve("stale-subdir"))).isFalse()
        assertThat(Files.exists(out.resolve("index.md"))).isTrue()
    }

    @Test
    fun missingOpaExitsThreeWithAnActionableMessageAndNoStackTrace(@TempDir tempDir: Path) {
        val out = tempDir.resolve("out")
        val result = runCli(
            listOf("render", policiesDir.toString(), "--out", out.toString()),
            tempDir,
            opaBin = "/nonexistent/opa-binary-that-does-not-exist",
        )

        assertThat(result.exitCode).isEqualTo(3)
        assertThat(result.stderr).contains("opa").contains("1.x")
        assertThat(result.stderr).doesNotContain("Exception").doesNotContain("\tat ")
        assertThat(Files.exists(out)).isFalse()
    }

    @Test
    fun regoSyntaxErrorExitsTwoWithOpaStderrPassedThroughVerbatim(@TempDir tempDir: Path) {
        val badPolicyDir = tempDir.resolve("bad-policy")
        Files.createDirectories(badPolicyDir)
        Files.writeString(badPolicyDir.resolve("broken.rego"), "package broken\n\nthis is not valid rego {{{\n")
        val out = tempDir.resolve("out")

        val result = runCli(listOf("render", badPolicyDir.toString(), "--out", out.toString()), tempDir)

        assertThat(result.exitCode).isEqualTo(2)
        assertThat(result.stderr).isNotBlank()
        // opa's own syntax-error stderr mentions the file and a parse/rego error -- passed through
        // verbatim, not paraphrased.
        assertThat(result.stderr).contains("broken.rego")
        assertThat(result.stderr).doesNotContain("\tat io.explico")
    }

    @Test
    fun duplicateControlIdsOnDiffExitsFourListingThem(@TempDir tempDir: Path) {
        val reportFile = tempDir.resolve("report.md")
        val result = runCli(
            listOf("diff", duplicateControlIdsDir.toString(), policiesDir.toString(), "--out", reportFile.toString()),
            tempDir,
        )

        assertThat(result.exitCode).isEqualTo(4)
        assertThat(result.stderr).contains("DUP-001")
        assertThat(Files.exists(reportFile)).isFalse()
    }

    @Test
    fun duplicateFixtureNamesOnRenderExitsFour(@TempDir tempDir: Path) {
        val examplesDir = tempDir.resolve("examples")
        Files.createDirectories(examplesDir)
        Files.writeString(examplesDir.resolve("a.json"), """{"name": "dup", "input": {}}""")
        Files.writeString(examplesDir.resolve("b.json"), """{"name": "dup", "input": {}}""")
        val out = tempDir.resolve("out")

        val result = runCli(
            listOf("render", policiesDir.toString(), "--out", out.toString(), "--examples", examplesDir.toString()),
            tempDir,
        )

        assertThat(result.exitCode).isEqualTo(4)
        assertThat(result.stderr).contains("dup")
    }

    @Test
    fun diffSucceedsWritesTheReportAndPrintsTheSummaryTable(@TempDir tempDir: Path) {
        val reportFile = tempDir.resolve("report.md")
        val newDir = Path.of("src/test/resources/diff/diff-all-categories/new").toAbsolutePath()
        val oldDir = Path.of("src/test/resources/diff/diff-all-categories/old").toAbsolutePath()

        val result = runCli(listOf("diff", oldDir.toString(), newDir.toString(), "--out", reportFile.toString()), tempDir)

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.stdout).contains("## Summary").contains("| REMOVED | 1 |")
        assertThat(Files.readString(reportFile)).contains("# Policy diff report")
    }

    @Test
    fun versionCommandReportsTheActualBuildVersion(@TempDir tempDir: Path) {
        // Reads the same explico-version.properties resource Main.kt reads (the test classpath
        // includes main's processed resources), so this fails if the version ever falls back to
        // "unknown" or otherwise stops actually reflecting build.gradle.kts's `version`.
        val expectedVersion = javaClass.getResourceAsStream("/explico-version.properties")!!.use { stream ->
            java.util.Properties().apply { load(stream) }.getProperty("version")
        }
        val result = runCli(listOf("version"), tempDir)

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.stdout.trim()).isEqualTo("explico $expectedVersion")
        assertThat(expectedVersion).isNotEqualTo("unknown")
    }

    @Test
    fun missingRequiredOutOptionExitsOneAsAUsageError(@TempDir tempDir: Path) {
        val result = runCli(listOf("render", policiesDir.toString()), tempDir)

        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.stderr).doesNotContain("\tat io.explico")
    }

    @Test
    fun nonexistentPolicyDirExitsOneAsAUsageError(@TempDir tempDir: Path) {
        val out = tempDir.resolve("out")
        val result = runCli(listOf("render", tempDir.resolve("does-not-exist").toString(), "--out", out.toString()), tempDir)

        assertThat(result.exitCode).isEqualTo(1)
    }
}
