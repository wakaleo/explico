/**
 * Process-level test for `render --source-url <template>`: links appear in the cards, stdout is unchanged, and a template without
 * `{file}` is a usage error (exit 1, no stack trace, nothing written). Runs the real packaged CLI like [CliProcessTest]. Requires `opa`.
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

class CliSourceUrlProcessTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(io.explico.opa.OpaRunner.isAvailable(), "opa binary not on PATH")
        }
    }

    private val policies = Path.of("src/test/resources/acceptance/policies").toAbsolutePath().toString()

    @Test
    @DisplayName("spec §14.70: --source-url also links the places in the index's Documentation quality section (a fallback's file:row), and the same render without it has them as plain text")
    fun linksThePlacesInTheIndex(@TempDir tempDir: Path) {
        val withFallbacks = Path.of("src/test/resources/realistic/fallback-places/policies").toAbsolutePath().toString()
        val linked = tempDir.resolve("linked")
        val plain = tempDir.resolve("plain")
        val result = runCli(listOf("render", withFallbacks, "--out", linked.toString(), "--source-url", "https://git.example.com/r/blob/abc/{file}#L{line}"), tempDir)
        runCli(listOf("render", withFallbacks, "--out", plain.toString()), tempDir)

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(Files.readString(linked.resolve("index.md")))
            .contains("[`sub/x.rego:14`](https://git.example.com/r/blob/abc/sub/x.rego#L14)")
            .contains("[`top.rego:15`](https://git.example.com/r/blob/abc/top.rego#L15)")
        assertThat(Files.readString(plain.resolve("index.md"))).contains("(`sub/x.rego:14`)").doesNotContain("git.example.com")
    }

    @Test
    @DisplayName("--source-url links the cards; stdout is the same coverage line as without it")
    fun links(@TempDir tempDir: Path) {
        val out = tempDir.resolve("out")
        val result = runCli(listOf("render", policies, "--out", out.toString(), "--source-url", "https://git.example.com/r/blob/abc/{file}#L{line}"), tempDir)

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.stdout.trim()).isEqualTo("Rendering coverage: 24 of 24 conditions (100%)")
        assertThat(Files.readString(out.resolve("release-approvals.md")))
            .contains("defined in [`change_approval.rego:16`](https://git.example.com/r/blob/abc/approvals/change_approval.rego#L16)*")
    }

    @Test
    @DisplayName("without the flag there are no links")
    fun off(@TempDir tempDir: Path) {
        val out = tempDir.resolve("out")
        assertThat(runCli(listOf("render", policies, "--out", out.toString()), tempDir).exitCode).isEqualTo(0)
        assertThat(Files.readString(out.resolve("release-approvals.md"))).contains("defined in `change_approval.rego`").doesNotContain("](http")
    }

    @Test
    @DisplayName("a template without {file} is a usage error naming {file}: exit 1, no stack trace, nothing written")
    fun missingFilePlaceholder(@TempDir tempDir: Path) {
        val out = tempDir.resolve("out")
        val result = runCli(listOf("render", policies, "--out", out.toString(), "--source-url", "https://git.example.com/no-placeholder"), tempDir)

        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.stderr).contains("{file}")
        assertThat(result.stderr).doesNotContain("Exception").doesNotContain("\tat ")
        assertThat(Files.exists(out)).isFalse()
    }
}
