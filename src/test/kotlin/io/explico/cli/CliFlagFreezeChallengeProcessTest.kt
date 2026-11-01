/**
 * CHALLENGE cases for the §14.80 step 5 flag renames, through the real CLI: the gate messages keep their old order whatever order `--fail-on` lists them in, a repeated `--fail-on` does not crash,
 * and a `--out` that already ends in `.json` gets `.json.json` (documented: the extension is appended, never swapped, so the JSON can never be the report itself). Needs opa (guarded).
 */
package io.explico.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CliFlagFreezeChallengeProcessTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(io.explico.opa.OpaRunner.isAvailable(), "opa binary not on PATH")
        }

        private val fsd = Path.of("src/test/resources/realistic/fixture-verdict-diff")
        private val old = fsd.resolve("policies/old").toAbsolutePath().toString()
        private val new = fsd.resolve("policies/new").toAbsolutePath().toString()
        private val examples = fsd.resolve("examples").toAbsolutePath().toString()
    }

    private fun diff(tempDir: Path, out: String, vararg extra: String) = runCli(listOf("diff", old, new, "--out", tempDir.resolve(out).toString(), "--examples", examples) + extra, tempDir)

    @Test
    @DisplayName("--fail-on EXAMPLE_CHANGES,LOGIC_CHANGED (the other way round) still prints the category gate first, then the example gate")
    fun otherOrder(@TempDir tempDir: Path) {
        val result = diff(tempDir, "r.md", "--fail-on", "EXAMPLE_CHANGES,LOGIC_CHANGED")

        assertThat(result.exitCode).isEqualTo(5)
        assertThat(result.stderr.indexOf("(--fail-on LOGIC_CHANGED)")).isGreaterThanOrEqualTo(0).isLessThan(result.stderr.indexOf("(--fail-on EXAMPLE_CHANGES)"))
    }

    @Test
    @DisplayName("--fail-on given twice does not crash: the later one is used, as for every option")
    fun repeated(@TempDir tempDir: Path) {
        val result = diff(tempDir, "r.md", "--fail-on", "ADDED", "--fail-on", "EXAMPLE_CHANGES")

        assertThat(result.exitCode).isEqualTo(5)
        assertThat(result.stderr).contains("(--fail-on EXAMPLE_CHANGES)").doesNotContain("\tat ")
    }

    @Test
    @DisplayName("an --out that already ends in .json gets .json.json: appended, never swapped, so the JSON is never the report")
    fun jsonExtension(@TempDir tempDir: Path) {
        val result = diff(tempDir, "report.json", "--json")

        assertThat(result.exitCode).describedAs(result.stderr).isEqualTo(0)
        assertThat(tempDir.resolve("report.json")).exists()
        assertThat(java.nio.file.Files.readString(tempDir.resolve("report.json"))).startsWith("# Policy diff report")
        assertThat(java.nio.file.Files.readString(tempDir.resolve("report.json.json"))).contains("\"schema\": \"explico/diff/1\"")
    }
}
