/**
 * Process-level test for `render --group-by-control` (spec §14.56): the control pages are written into a `controls/` subdirectory of `--out` (which the
 * CLI must create), a re-render replaces the whole tree, and without the flag nothing about the output changes. Runs the real packaged CLI like
 * [CliControlIdProcessTest]. Requires `opa`.
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

class CliGroupByControlProcessTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(io.explico.opa.OpaRunner.isAvailable(), "opa binary not on PATH")
        }
    }

    private val policies = Path.of("src/test/resources/realistic/group-by-control/policies").toAbsolutePath().toString()

    private fun render(tempDir: Path, vararg extra: String): Pair<CliResult, Path> {
        val out = tempDir.resolve("out")
        return runCli(listOf("render", policies, "--out", out.toString()) + extra, tempDir) to out
    }

    private fun filesUnder(out: Path): List<String> =
        Files.walk(out).use { paths -> paths.filter { Files.isRegularFile(it) }.map { out.relativize(it).toString().replace('\\', '/') }.sorted().toList() }

    @Test
    @DisplayName("--group-by-control writes controls/<id>.md into a subdirectory it creates, next to the package pages and the index")
    fun writesControlPages(@TempDir tempDir: Path) {
        val (result, out) = render(tempDir, "--group-by-control")

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.stderr).doesNotContain("Exception").doesNotContain("\tat ")
        assertThat(filesUnder(out)).contains("controls/sec-28.md", "controls/x-1.md", "controls/x-1-2.md", "index.md", "grp_signoff.md")
        assertThat(Files.readString(out.resolve("controls/sec-28.md"))).contains("# Control SEC-28 — Evidence is attached")
        assertThat(Files.readString(out.resolve("index.md"))).contains("controls/sec-28.md#sec-28")
    }

    @Test
    @DisplayName("without the flag there is no controls/ directory at all")
    fun off(@TempDir tempDir: Path) {
        val (result, out) = render(tempDir)

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(filesUnder(out).none { it.startsWith("controls/") }).isTrue()
        assertThat(Files.exists(out.resolve("controls"))).isFalse()
    }

    @Test
    @DisplayName("--out is replaced as a whole: a stale control page from an earlier grouped render does not survive an ungrouped one")
    fun staleControlPagesAreReplaced(@TempDir tempDir: Path) {
        render(tempDir, "--group-by-control")
        val (result, out) = render(tempDir)

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(Files.exists(out.resolve("controls"))).describedAs("the controls/ directory of the earlier render").isFalse()
    }

    @Test
    @DisplayName("grouping and --json together: explico.json lists the controls")
    fun withJson(@TempDir tempDir: Path) {
        val (result, out) = render(tempDir, "--group-by-control", "--json")

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(Files.readString(out.resolve("explico.json"))).contains("\"controls\"").contains("\"page\": \"controls/sec-28.md\"")
    }

    @Test
    @DisplayName("--fail-on-gaps counts a grouped control once: SEC-28 is complete on some rule, so only X-1 and x_1 are gaps")
    fun gapsCountControls(@TempDir tempDir: Path) {
        val (result, _) = render(tempDir, "--group-by-control", "--fail-on-gaps")

        assertThat(result.exitCode).isEqualTo(5)
        assertThat(result.stderr).contains("control X-1 has no description").doesNotContain("SEC-28")
        assertThat(result.stderr.lines().count { it.contains("control X-1 has no description") }).isEqualTo(1)
    }
}
