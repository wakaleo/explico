/**
 * Security review L2 and L4: a `--force` run must not delete the requirements file it was given, and an input file that cannot be read (binary content, a dangling link) is exit 1 with
 * ONE stderr line, never a JVM stack trace (spec §8.2).
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

class CliSecurityReviewProcessTest {
    companion object {
        @JvmStatic @BeforeAll fun requireOpa() = assumeTrue(io.explico.opa.OpaRunner.isAvailable(), "opa binary not on PATH")
    }

    private val policies = Path.of("src/test/resources/acceptance/policies").toAbsolutePath().toString()

    private fun assertOneLineFailure(result: CliResult, vararg parts: String) {
        assertThat(result.exitCode).describedAs(result.stderr).isEqualTo(1)
        assertThat(result.stderr.lines().filter { it.isNotBlank() }).describedAs("one line, no stack trace").hasSize(1)
        assertThat(result.stderr).doesNotContain("\tat ").doesNotContain("Exception in thread")
        for (part in parts) assertThat(result.stderr).contains(part)
    }

    @Test
    @DisplayName("L2: --force never deletes the requirements file, even when it lives in the output directory")
    fun requirementsFileIsProtected(@TempDir tmp: Path) {
        val out = Files.createDirectories(tmp.resolve("out"))
        val reqs = Files.writeString(out.resolve("requirements.txt"), "SOC 2 A1.2\n")
        Files.writeString(out.resolve("other.txt"), "x")

        val result = runCli(listOf("render", policies, "--out", out.toString(), "--force", "--requirements", reqs.toString()), tmp)

        assertOneLineFailure(result, "refusing to write to", "requirements.txt")
        assertThat(reqs).exists()
        assertThat(out.resolve("other.txt")).exists()
    }

    @Test
    @DisplayName("L4: a requirements file that is not text is one line, exit 1")
    fun binaryRequirements(@TempDir tmp: Path) {
        val reqs = Files.write(tmp.resolve("requirements.bin"), byteArrayOf(0x80.toByte(), 0xC0.toByte(), 0xFF.toByte(), 0x00))

        val result = runCli(listOf("render", policies, "--out", tmp.resolve("o").toString(), "--requirements", reqs.toString()), tmp)

        assertOneLineFailure(result, "could not read", reqs.toString())
    }

    @Test
    @DisplayName("L4: a fixture that is a dangling symlink is one line, exit 1 (render and diff)")
    fun danglingFixture(@TempDir tmp: Path) {
        val examples = Files.createDirectories(tmp.resolve("examples"))
        try { Files.createSymbolicLink(examples.resolve("gone.json"), tmp.resolve("nowhere.json")) } catch (e: Exception) { abort("symbolic links are not available here: ${e.message}") }

        val render = runCli(listOf("render", policies, "--out", tmp.resolve("o").toString(), "--examples", examples.toString()), tmp)
        assertOneLineFailure(render, "could not read", "gone.json")

        val diff = runCli(listOf("diff", policies, policies, "--out", tmp.resolve("r.md").toString(), "--examples", examples.toString()), tmp)
        assertOneLineFailure(diff, "could not read", "gone.json")
    }
}
