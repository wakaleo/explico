/**
 * Process-level test for the CLI's JSON options: `render --json` writes `explico.json` next to the Markdown, `diff --json <file>`
 * writes the machine-readable change report, and neither changes what the commands already print. Runs the real packaged CLI like
 * [CliProcessTest]. Requires `opa` on PATH.
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

class CliJsonProcessTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(io.explico.opa.OpaRunner.isAvailable(), "opa binary not on PATH")
        }
    }

    private val policies = Path.of("src/test/resources/acceptance/policies").toAbsolutePath()
    private val oldDir = Path.of("src/test/resources/diff/diff-all-categories/old").toAbsolutePath()
    private val newDir = Path.of("src/test/resources/diff/diff-all-categories/new").toAbsolutePath()

    @Test
    @DisplayName("render --json writes explico.json beside the Markdown and still prints the coverage line")
    fun renderJson(@TempDir tempDir: Path) {
        val out = tempDir.resolve("out")
        val result = runCli(listOf("render", policies.toString(), "--out", out.toString(), "--json"), tempDir)

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.stdout).contains("Rendering coverage:")
        assertThat(Files.readString(out.resolve("explico.json"))).startsWith("{").contains("\"schema\": \"explico/render/1\"")
        assertThat(Files.exists(out.resolve("index.md"))).isTrue()
    }

    @Test
    @DisplayName("render without --json writes no explico.json")
    fun renderWithoutJson(@TempDir tempDir: Path) {
        val out = tempDir.resolve("out")
        val result = runCli(listOf("render", policies.toString(), "--out", out.toString()), tempDir)

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(Files.exists(out.resolve("explico.json"))).isFalse()
    }

    @Test
    @DisplayName("diff --json <file> writes the change report JSON; stdout is still the Summary table")
    fun diffJson(@TempDir tempDir: Path) {
        val report = tempDir.resolve("report.md")
        val json = tempDir.resolve("report.json")
        val result = runCli(listOf("diff", oldDir.toString(), newDir.toString(), "--out", report.toString(), "--json", json.toString()), tempDir)

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.stdout).contains("## Summary").contains("| REMOVED | 1 |").doesNotContain("explico/diff/1")
        assertThat(Files.readString(json)).contains("\"schema\": \"explico/diff/1\"").contains("\"REL-003\"")
        assertThat(Files.readString(report)).startsWith("# Policy diff report")
    }

    @Test
    @DisplayName("diff without --json writes no JSON file")
    fun diffWithoutJson(@TempDir tempDir: Path) {
        val report = tempDir.resolve("report.md")
        val result = runCli(listOf("diff", oldDir.toString(), newDir.toString(), "--out", report.toString()), tempDir)

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(Files.list(tempDir).use { it.map { p -> p.fileName.toString() }.toList() }).containsExactly("report.md")
    }
}
