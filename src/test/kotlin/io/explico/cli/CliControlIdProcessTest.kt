/**
 * Process-level test for `render --control-id-from <regex>` / `--control-id-template <tpl>` (spec §14.55): a control id derived from the
 * source file name appears on the cards; a mistake in either option is a usage error (exit 1, a message that says how to fix it, no stack
 * trace, nothing written); `diff` does not take the options. Runs the real packaged CLI like [CliSourceUrlProcessTest]. Requires `opa`.
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

class CliControlIdProcessTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(io.explico.opa.OpaRunner.isAvailable(), "opa binary not on PATH")
        }
    }

    private val policies = Path.of("src/test/resources/realistic/control-id-from-filename/policies").toAbsolutePath().toString()
    private val pattern = "ctrl_(?<id>\\d+)"

    private fun render(tempDir: Path, vararg extra: String): Pair<CliResult, Path> {
        val out = tempDir.resolve("out")
        return runCli(listOf("render", policies, "--out", out.toString()) + extra, tempDir) to out
    }

    @Test
    @DisplayName("the two options derive the id: the card is headed 'CTRL-1123 — deny' and stdout is the usual coverage line")
    fun derives(@TempDir tempDir: Path) {
        val (result, out) = render(tempDir, "--control-id-from", pattern, "--control-id-template", "CTRL-{id}")

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.stdout).startsWith("Rendering coverage:")
        assertThat(Files.readString(out.resolve("fname_one.md"))).contains("## CTRL-1123 — deny")
        assertThat(Files.readString(out.resolve("index.md"))).contains("| CTRL-19 |")
    }

    @Test
    @DisplayName("with the pattern alone the id is the captured text")
    fun patternAlone(@TempDir tempDir: Path) {
        val (result, out) = render(tempDir, "--control-id-from", pattern)

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(Files.readString(out.resolve("fname_one.md"))).contains("## 1123 — deny")
    }

    @Test
    @DisplayName("without the options nothing is derived")
    fun off(@TempDir tempDir: Path) {
        val (result, out) = render(tempDir)

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(Files.readString(out.resolve("fname_one.md"))).contains("## fname_one.deny").doesNotContain("CTRL-1123")
    }

    @Test
    @DisplayName("the template is literal end to end: `\$1` and a backslash reach the heading as written")
    fun literalTemplate(@TempDir tempDir: Path) {
        val (result, out) = render(tempDir, "--control-id-from", pattern, "--control-id-template", "X\$1\\{id}")

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(Files.readString(out.resolve("fname_one.md"))).contains("## X\$1\\1123 — deny")
    }

    private fun assertUsageError(result: CliResult, out: Path, vararg messageParts: String) {
        assertThat(result.exitCode).isEqualTo(1)
        for (part in messageParts) assertThat(result.stderr).contains(part)
        assertThat(result.stderr).doesNotContain("Exception").doesNotContain("\tat ")
        assertThat(Files.exists(out)).describedAs("nothing is written").isFalse()
    }

    @Test
    @DisplayName("first, a good value is accepted (so the failures below are about the value)")
    fun goodValueAccepted(@TempDir tempDir: Path) {
        assertThat(render(tempDir, "--control-id-from", pattern, "--control-id-template", "CTRL-{id}").first.exitCode).isEqualTo(0)
    }

    @Test
    @DisplayName("a pattern that is not a regular expression is a usage error")
    fun invalidRegex(@TempDir tempDir: Path) {
        val (result, out) = render(tempDir, "--control-id-from", "ctrl_(?<id>\\d+")
        assertUsageError(result, out, "regular expression", "--control-id-from")
    }

    @Test
    @DisplayName("a pattern without the named group `id` says how to write it")
    fun missingGroup(@TempDir tempDir: Path) {
        val (result, out) = render(tempDir, "--control-id-from", "ctrl_(\\d+)")
        assertUsageError(result, out, "(?<id>", "--control-id-from")
    }

    @Test
    @DisplayName("a template without {id} is a usage error naming {id}")
    fun templateWithoutId(@TempDir tempDir: Path) {
        val (result, out) = render(tempDir, "--control-id-from", pattern, "--control-id-template", "CTRL-")
        assertUsageError(result, out, "{id}", "--control-id-template")
    }

    @Test
    @DisplayName("a template with no pattern is a usage error: it would silently do nothing")
    fun templateWithoutPattern(@TempDir tempDir: Path) {
        val (result, out) = render(tempDir, "--control-id-template", "CTRL-{id}")
        assertUsageError(result, out, "--control-id-template", "--control-id-from")
    }

    @Test
    @DisplayName("--help documents both options and says that METADATA wins")
    fun help(@TempDir tempDir: Path) {
        val help = runCli(listOf("render", "--help"), tempDir).stdout.replace(Regex("\\s+"), " ")

        assertThat(help).contains("--control-id-from").contains("--control-id-template").contains("(?<id>").contains("METADATA")
    }

    @Test
    @DisplayName("`diff` does not take the options (render only): a usage error, exit 1")
    fun diffDoesNotTakeThem(@TempDir tempDir: Path) {
        val result = runCli(listOf("diff", policies, policies, "--out", tempDir.resolve("r.md").toString(), "--control-id-from", pattern), tempDir)

        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.stderr).contains("--control-id-from")
        assertThat(Files.exists(tempDir.resolve("r.md"))).isFalse()
    }
}
