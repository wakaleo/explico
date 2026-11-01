/**
 * Process-level test for the terminal block with the per-situation example coverage finding (see RealisticSituationCoverageAcceptanceIT for the contract): `render
 * --examples` prints the summary line inside `Documentation quality (see index.md):`, and nothing about it changes a gate. Runs the real packaged CLI. Requires `opa`.
 */
package io.explico.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CliSituationCoverageProcessTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(io.explico.opa.OpaRunner.isAvailable(), "opa binary not on PATH")
        }

        private val base = Path.of("src/test/resources/realistic/situation-coverage")
        private val policies = base.resolve("policies").toAbsolutePath().toString()
        private val examples = base.resolve("examples").toAbsolutePath().toString()
    }

    private fun render(tempDir: Path, vararg extra: String) = runCli(listOf("render", policies, "--out", tempDir.resolve("out").toString()) + extra, tempDir)

    @Test
    @DisplayName("the terminal block lists the situation line right after the no-matching-example line")
    fun terminalBlock(@TempDir tempDir: Path) {
        val result = render(tempDir, "--examples", examples)

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.stdout.lines().filter { it.isNotBlank() }.dropWhile { it != "Documentation quality (see index.md):" }).containsExactly(
            "Documentation quality (see index.md):",
            "  - 1 control has no matching example",
            "  - 2 controls have a situation no example matches",
        )
    }

    @Test
    @DisplayName("without --examples neither line is printed")
    fun withoutExamples(@TempDir tempDir: Path) {
        val result = render(tempDir)

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.stdout).doesNotContain("situation no example")
    }

    @Test
    @DisplayName("it is not a gate: --fail-on-gaps still exits 0 for the complete pack, and exit 5 only for the property that is required and missing")
    fun notAGate(@TempDir tempDir: Path) {
        assertThat(render(tempDir, "--examples", examples, "--fail-on-gaps").exitCode).isEqualTo(0)
        val required = render(tempDir, "--examples", examples, "--fail-on-gaps", "--must-have", "title,description,frameworks,fixture,verdict")

        assertThat(required.exitCode).isEqualTo(5)
        assertThat(required.stderr).contains("control SC-4 has no matching example").doesNotContain("situation")
    }
}
