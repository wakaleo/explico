/**
 * Review finding (spec §14.80 step 1): when the write itself fails -- an `--out` that is a dangling symlink, or one whose parent is a file -- the CLI says so in ONE stderr line with
 * exit 1, never a JVM stack trace (spec §8.2). The guard cannot foresee these; the failure arrives from the file system.
 */
package io.explico.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.abort
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CliOutputFailureProcessTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(io.explico.opa.OpaRunner.isAvailable(), "opa binary not on PATH")
        }
    }

    private val policies = Path.of("src/test/resources/acceptance/policies").toAbsolutePath().toString()
    private val diffBase = Path.of("src/test/resources/diff/diff-all-categories").toAbsolutePath()

    private fun assertOneLineFailure(result: CliResult, vararg parts: String) {
        assertThat(result.exitCode).describedAs(result.stderr).isEqualTo(1)
        assertThat(result.stderr.lines().filter { it.isNotBlank() }).describedAs("one line, no stack trace").hasSize(1)
        assertThat(result.stderr).doesNotContain("\tat ").doesNotContain("Exception in thread")
        for (part in parts) assertThat(result.stderr).contains(part)
    }

    @Test
    @DisplayName("render --out a dangling symlink: one line naming it, exit 1, no stack trace")
    fun danglingSymlink(@TempDir tempDir: Path) {
        val link = tempDir.resolve("dangling")
        try { Files.createSymbolicLink(link, tempDir.resolve("nowhere")) } catch (e: Exception) { abort("symbolic links are not available here: ${e.message}") }

        val result = runCli(listOf("render", policies, "--out", link.toString()), tempDir)

        assertOneLineFailure(result, "could not write", link.toString())
    }

    @Test
    @DisplayName("render --out below a regular file: one line naming it, exit 1, and the file is untouched")
    fun parentIsAFile(@TempDir tempDir: Path) {
        val file = tempDir.resolve("a-file")
        Files.writeString(file, "mine")
        val out = file.resolve("sub")

        val result = runCli(listOf("render", policies, "--out", out.toString()), tempDir)

        assertOneLineFailure(result, "could not write", out.toString())
        assertThat(Files.readString(file)).isEqualTo("mine")
    }

    @Test
    @DisplayName("diff --out below a regular file, and --json below one: the same single line")
    fun diffParentIsAFile(@TempDir tempDir: Path) {
        val file = tempDir.resolve("a-file")
        Files.writeString(file, "mine")
        val old = diffBase.resolve("old").toString()
        val new = diffBase.resolve("new").toString()

        val report = runCli(listOf("diff", old, new, "--out", file.resolve("r.md").toString()), tempDir)
        val json = runCli(listOf("diff", old, new, "--out", tempDir.resolve("r.md").toString(), "--json", file.resolve("r.json").toString()), tempDir)

        assertOneLineFailure(report, "could not write", file.resolve("r.md").toString())
        assertOneLineFailure(json, "could not write", file.resolve("r.json").toString())
        assertThat(Files.readString(file)).isEqualTo("mine")
    }
}
