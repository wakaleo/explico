/**
 * Process-level test for `explico render --generate-examples`: the real CLI as a separate OS process (the flag is
 * opt-in, and the default output must not change). Requires `opa` on PATH.
 */
package io.explico.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class GenerateExamplesCliProcessTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(io.explico.opa.OpaRunner.isAvailable(), "opa binary not on PATH")
        }
    }

    private val policies = Path.of("src/test/resources/realistic/generated-examples/policies").toAbsolutePath()

    @Test
    fun theFlagAddsGeneratedExamplesAndASummaryLineWithoutChangingTheCoverageLine(@TempDir tempDir: Path) {
        val out = tempDir.resolve("out")
        val result = runCli(listOf("render", policies.toString(), "--out", out.toString(), "--generate-examples"), tempDir)

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.stdout).contains("Rendering coverage:")
        assertThat(result.stdout + result.stderr).containsPattern("Generated examples for \\d+ of \\d+ rules")
        val doc = Files.readString(out.resolve("genex.md"))
        assertThat(doc)
            .describedAs("child process output -- stdout: %s | stderr: %s", result.stdout, result.stderr)
            .contains("**Generated examples**").contains("**satisfies every condition**")
    }

    @Test
    fun withoutTheFlagNothingIsGeneratedAndNoSummaryIsPrinted(@TempDir tempDir: Path) {
        val out = tempDir.resolve("out")
        val result = runCli(listOf("render", policies.toString(), "--out", out.toString()), tempDir)

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.stdout + result.stderr).doesNotContain("Generated examples")
        assertThat(Files.readString(out.resolve("genex.md"))).doesNotContain("Generated examples")
    }

    @Test
    fun aPolicyWithNothingDerivableStillSucceedsAndSaysSo(@TempDir tempDir: Path) {
        val unsolvable = tempDir.resolve("policies")
        Files.createDirectories(unsolvable)
        Files.writeString(
            unsolvable.resolve("p.rego"),
            "package nothing\n\nimport rego.v1\n\nbig(n) if n > 10\n\nflagged contains msg if {\n\tbig(input.n)\n\tmsg := \"big\"\n}\n",
        )
        val out = tempDir.resolve("out")
        val result = runCli(listOf("render", unsolvable.toString(), "--out", out.toString(), "--generate-examples"), tempDir)

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.stdout).contains("Generated examples for 0 of 1 rules")
        assertThat(Files.readString(out.resolve("nothing.md"))).doesNotContain("**Generated examples**")
    }
}
