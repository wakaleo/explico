/**
 * Process-level test for the CLI's `--help` text: every command shows a one-line description next to its name, so a first-time
 * user can tell `render` from `diff` from `demo` without reading the docs. Runs the real packaged CLI as a child process, like
 * [CliProcessTest]. No opa needed: help never touches it.
 */
package io.explico.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CliHelpProcessTest {

    /** Help wraps at the terminal width; collapse every run of whitespace so a wrapped description still matches. */
    private fun flat(text: String) = text.replace(Regex("\\s+"), " ")

    @Test
    @DisplayName("explico --help describes the tool and each command, exit code 0")
    fun rootHelpDescribesEveryCommand(@TempDir tempDir: Path) {
        val result = runCli(listOf("--help"), tempDir)
        val help = flat(result.stdout)

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(help).contains("Turn Rego policies into documentation a reviewer can read -- every statement derived mechanically, never guessed")
        assertThat(help).contains("render Render a directory of Rego policies into Markdown documentation")
        assertThat(help).contains("diff Compare two policy versions and write a change report")
        assertThat(help).contains("version Print explico's version")
        assertThat(help).contains("demo Render the bundled sample policies into ./explico-demo to see what explico produces")
    }

    @Test
    @DisplayName("explico render --help still lists its options")
    fun renderHelpStillListsItsOptions(@TempDir tempDir: Path) {
        val result = runCli(listOf("render", "--help"), tempDir)
        val help = flat(result.stdout)

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(help).contains("--out").contains("--examples").contains("--data").contains("--generate-examples").contains("--rego-version")
        assertThat(help).contains("Render a directory of Rego policies into Markdown documentation")
    }
}
