/**
 * The help text of the renamed and removed flags (spec §14.80 step 5), through the real packaged CLI. No opa: help never touches it. See CliFlagFreezeProcessTest for the behaviour.
 *
 * Pinned: `diff --help` shows `--json` as a BARE flag (no `=<value>` placeholder) that mentions `<out>.json`; `--fail-on` names EXAMPLE_CHANGES; it lists neither `--force` nor
 * `--fail-on-example-changes`; `--out` still says FILE. `render --help` lists `--must-have` and no longer `--require` (`--requirements`, the file option, remains), and `--out` still says DIRECTORY.
 */
package io.explico.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CliFlagFreezeHelpProcessTest {

    /** Help wraps at the terminal width; collapse every run of whitespace so a wrapped description still matches. */
    private fun help(tempDir: Path, command: String) = runCli(listOf(command, "--help"), tempDir).also { assertThat(it.exitCode).isEqualTo(0) }.stdout.replace(Regex("\\s+"), " ")

    @Test
    @DisplayName("diff --help: --json is a bare flag that mentions <out>.json; --fail-on names EXAMPLE_CHANGES; --out says FILE")
    fun diffHelp(@TempDir tempDir: Path) {
        val help = help(tempDir, "diff")

        assertThat(Regex("--json(?!=)").containsMatchIn(help)).describedAs("--json without a value placeholder").isTrue()
        assertThat(help).contains("<out>.json")
        assertThat(help).contains("--fail-on").contains("EXAMPLE_CHANGES")
        assertThat(help).contains("FILE")
    }

    @Test
    @DisplayName("diff --help no longer lists --force or --fail-on-example-changes")
    fun diffHelpWithoutTheRemovedOptions(@TempDir tempDir: Path) {
        val help = help(tempDir, "diff")

        assertThat(help).doesNotContain("--force").doesNotContain("--fail-on-example-changes")
    }

    @Test
    @DisplayName("render --help lists --must-have and --force, keeps --requirements, no longer lists --require, and --out still says DIRECTORY")
    fun renderHelp(@TempDir tempDir: Path) {
        val help = help(tempDir, "render")

        assertThat(help).contains("--must-have").contains("--force").contains("--requirements").contains("DIRECTORY")
        assertThat(Regex("--require(?!ments)").containsMatchIn(help)).describedAs("the old --require").isFalse()
    }
}
