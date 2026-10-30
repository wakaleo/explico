/**
 * Process-level test for `custom.mask` (spec §14.61): the CLI renders a masked example with `***`, and a malformed declaration is exit 1 with a message naming
 * `custom.mask` and the rule -- no stack trace, nothing written. Runs the real packaged CLI like [CliCompanionUrlProcessTest]. Requires `opa`.
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

class CliInputMaskProcessTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(io.explico.opa.OpaRunner.isAvailable(), "opa binary not on PATH")
        }
    }

    private val base = Path.of("src/test/resources/realistic/input-masking").toAbsolutePath()

    @Test
    @DisplayName("a masked example reads *** in the written card and the JSON, and the real values are nowhere in the masked rules")
    fun masks(@TempDir tempDir: Path) {
        val out = tempDir.resolve("out")
        val result = runCli(listOf("render", base.resolve("policies").toString(), "--out", out.toString(), "--examples", base.resolve("examples").toString(), "--json"), tempDir)

        assertThat(result.exitCode).isEqualTo(0)
        val markdown = Files.readString(out.resolve("mask.md"))
        assertThat(markdown).contains("\\*\\*\\* (subject \\*\\*\\*) has no ticket").contains("`ticket`: `***`")
        assertThat(Files.readString(out.resolve("explico.json"))).doesNotContain("S-12345").doesNotContain("987654321")
    }

    @Test
    @DisplayName("a mask path that hides nothing (a leading 'input.', the wrong case) is warned about once each on stderr, the valid one is not; the render still succeeds")
    fun unmatchedPathsWarn(@TempDir tempDir: Path) {
        val dir = Path.of("src/test/resources/realistic/input-masking-typo").toAbsolutePath()
        val result = runCli(listOf("render", dir.resolve("policies").toString(), "--out", tempDir.resolve("out").toString(), "--examples", dir.resolve("examples").toString()), tempDir)

        assertThat(result.exitCode).isEqualTo(0)
        val warnings = result.stderr.lines().filter { it.startsWith("explico: warning: custom.mask path") }
        assertThat(warnings).hasSize(2)
        assertThat(warnings[0]).contains("'Requester.Email'").contains("masktypo.leaky").contains("check the spelling and case")
        assertThat(warnings[1]).contains("'input.subjectId'").contains("drop the leading 'input.'")
        assertThat(result.stderr).doesNotContain("'requester.email'")
    }

    @Test
    @DisplayName("a malformed custom.mask is exit 1 naming custom.mask and the rule, with nothing written and no stack trace")
    fun malformed(@TempDir tempDir: Path) {
        val out = tempDir.resolve("out")
        val result = runCli(listOf("render", base.resolve("malformed-scalar").toString(), "--out", out.toString()), tempDir)

        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.stderr).contains("custom.mask").contains("badmask.leaky").doesNotContain("Exception").doesNotContain("\tat ")
        assertThat(Files.exists(out)).describedAs("nothing is written").isFalse()
    }
}
