/**
 * Process-level test for `render --require <properties>` (spec §14.64): which documentation properties of a control `--fail-on-gaps` fails on. The default is
 * unchanged, a refused option is a usage error (exit 1, nothing written, no stack trace), and `--require` alone does nothing so it is refused too. Runs the real
 * packaged CLI like [CliGatesProcessTest]. Requires `opa`.
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

class CliRequiredSetProcessTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(io.explico.opa.OpaRunner.isAvailable(), "opa binary not on PATH")
        }
    }

    private val policies = Path.of("src/test/resources/realistic/required-set/policies").toAbsolutePath().toString()
    private val examples = Path.of("src/test/resources/realistic/required-set/examples").toAbsolutePath().toString()

    private fun render(tempDir: Path, vararg extra: String) = runCli(listOf("render", policies, "--out", tempDir.resolve("out").toString()) + extra, tempDir)

    private fun assertRefused(tempDir: Path, result: CliResult) {
        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.stderr).doesNotContain("Exception").doesNotContain("\tat ")
        assertThat(Files.exists(tempDir.resolve("out"))).describedAs("nothing is written").isFalse()
    }

    @Test
    @DisplayName("without --require, --fail-on-gaps fails on exactly what it always did: description, framework and the shared title")
    fun defaultUnchanged(@TempDir tempDir: Path) {
        val result = render(tempDir, "--fail-on-gaps")

        assertThat(result.exitCode).isEqualTo(5)
        assertThat(result.stderr).contains("3 gap(s)").contains("control RS-3 has no description").contains("control RS-4 names no framework").contains("share the title")
    }

    @Test
    @DisplayName("--require title,verdict --examples: those two (and the shared title) fail the gate; description and framework no longer do; the output is still written")
    fun titleAndVerdict(@TempDir tempDir: Path) {
        val result = render(tempDir, "--examples", examples, "--fail-on-gaps", "--require", "title,verdict")

        assertThat(result.exitCode).isEqualTo(5)
        assertThat(result.stderr).contains("3 gap(s)").contains("control RS-2 has no title").contains("control RS-6 returns an object but declares no custom.verdict")
            .contains("share the title").doesNotContain("has no description").doesNotContain("names no framework")
        assertThat(Files.exists(tempDir.resolve("out/index.md"))).isTrue()
    }

    @Test
    @DisplayName("whitespace around the commas is ignored")
    fun whitespace(@TempDir tempDir: Path) {
        val result = render(tempDir, "--examples", examples, "--fail-on-gaps", "--require", " fixture , verdict ")

        assertThat(result.exitCode).isEqualTo(5)
        assertThat(result.stderr).contains("control RS-5 has no matching example").contains("control RS-6 returns an object")
    }

    @Test
    @DisplayName("fixture without --examples is refused (it would pass vacuously): exit 1, the reason on stderr, nothing written")
    fun fixtureNeedsExamples(@TempDir tempDir: Path) {
        val result = render(tempDir, "--fail-on-gaps", "--require", "fixture")

        assertRefused(tempDir, result)
        assertThat(result.stderr).contains("'fixture'").contains("example fixtures")
    }

    @Test
    @DisplayName("an unknown property, an empty list and an empty element are refused naming the valid properties")
    fun unknownNames(@TempDir tempDir: Path) {
        for (bad in listOf("titel", "Title", "", "title,", ",title")) {
            val result = render(tempDir, "--fail-on-gaps", "--require", bad)
            assertRefused(tempDir, result)
            assertThat(result.stderr).describedAs("--require '$bad'").contains("title, description, frameworks, fixture, verdict")
        }
    }

    @Test
    @DisplayName("--require without --fail-on-gaps is refused: it only changes what that gate fails on")
    fun needsTheGate(@TempDir tempDir: Path) {
        val result = render(tempDir, "--require", "title")

        assertRefused(tempDir, result)
        assertThat(result.stderr).contains("--require").contains("--fail-on-gaps")
    }

    @Test
    @DisplayName("an examples directory with no fixtures counts as no examples: verdict is refused, with exit 1")
    fun emptyExamplesDirectory(@TempDir tempDir: Path) {
        val empty = Files.createDirectory(tempDir.resolve("no-examples"))
        val result = render(tempDir, "--examples", empty.toString(), "--fail-on-gaps", "--require", "verdict")

        assertRefused(tempDir, result)
        assertThat(result.stderr).contains("example fixtures")
    }
}
