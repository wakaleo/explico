/**
 * The exit code per failure through the real CLI on every command that needs opa (spec §14.80 step 3): the mapping is by exception TYPE in one place, so each command must give the same
 * code for the same failure -- 3 for a missing opa, 2 for a Rego syntax error -- and one stderr message with no stack trace.
 */
package io.explico.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CliExitCodeProcessTest {

    private val pack = Path.of("src/test/resources/acceptance/policies").toAbsolutePath().toString()

    private fun assertCleanFailure(result: CliResult, exitCode: Int) {
        assertThat(result.exitCode).describedAs(result.stderr).isEqualTo(exitCode)
        assertThat(result.stderr).doesNotContain("\tat ").doesNotContain("Exception in thread")
        assertThat(result.stderr).isNotBlank()
    }

    @Test
    @DisplayName("render and diff with no usable opa: exit 3 and the actionable message, no stack trace")
    fun missingOpa(@TempDir dir: Path) {
        assertCleanFailure(runCli(listOf("render", pack, "--out", dir.resolve("out").toString()), dir, opaBin = "/nonexistent/opa"), 3)
        assertCleanFailure(runCli(listOf("diff", pack, pack, "--out", dir.resolve("r.md").toString()), dir, opaBin = "/nonexistent/opa"), 3)
        assertThat(runCli(listOf("render", pack, "--out", dir.resolve("out2").toString()), dir, opaBin = "/nonexistent/opa").stderr).contains("OPA_BIN")
    }

    @Test
    @DisplayName("a Rego syntax error is exit 2 with opa's own text on both render and diff")
    fun syntaxError(@TempDir dir: Path) {
        assumeTrue(io.explico.opa.OpaRunner.isAvailable(), "opa binary not on PATH")
        val bad = Files.createDirectories(dir.resolve("bad"))
        Files.writeString(bad.resolve("broken.rego"), "package broken\n\nthis is not valid rego {{{\n")

        val render = runCli(listOf("render", bad.toString(), "--out", dir.resolve("out").toString()), dir)
        val diff = runCli(listOf("diff", bad.toString(), pack, "--out", dir.resolve("r.md").toString()), dir)

        assertCleanFailure(render, 2)
        assertCleanFailure(diff, 2)
        assertThat(render.stderr).contains("broken.rego")
        assertThat(diff.stderr).contains("broken.rego")
    }

    @Test
    @DisplayName("a malformed custom.mask is exit 1, not caught as a usage error by accident, and nothing is written")
    fun malformedMask(@TempDir dir: Path) {
        assumeTrue(io.explico.opa.OpaRunner.isAvailable(), "opa binary not on PATH")
        val malformed = Path.of("src/test/resources/realistic/input-masking/malformed-scalar").toAbsolutePath()

        val result = runCli(listOf("render", malformed.toString(), "--out", dir.resolve("out").toString()), dir)

        assertCleanFailure(result, 1)
        assertThat(result.stderr).contains("custom.mask").contains("badmask.leaky")
        assertThat(Files.exists(dir.resolve("out"))).isFalse()
    }
}
