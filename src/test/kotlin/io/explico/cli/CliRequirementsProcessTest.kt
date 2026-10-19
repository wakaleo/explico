/**
 * Process-level test for `render --requirements <file>`: one requirement per line, blank lines and `#` comments ignored, lines
 * trimmed, duplicates collapsed; a missing file is a usage error (exit 1, no stack trace). Runs the real packaged CLI like
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

class CliRequirementsProcessTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(io.explico.opa.OpaRunner.isAvailable(), "opa binary not on PATH")
        }
    }

    private val policies = Path.of("src/test/resources/acceptance/policies").toAbsolutePath()
    private val requirements = Path.of("src/test/resources/realistic/requirements/requirements.txt").toAbsolutePath()
    private val typos = Path.of("src/test/resources/realistic/requirements/typo.txt").toAbsolutePath()

    @Test
    @DisplayName("comments, blanks and a duplicate are ignored: 4 of 5, one SOC 2 row, in file order; stdout is unchanged")
    fun requirementsFile(@TempDir tempDir: Path) {
        val out = tempDir.resolve("out")
        val result = runCli(listOf("render", policies.toString(), "--out", out.toString(), "--requirements", requirements.toString()), tempDir)

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.stdout).contains("Rendering coverage:").doesNotContain("requirements")
        val index = Files.readString(out.resolve("index.md"))
        assertThat(index).contains("*4 of 5 requirements have at least one control.*").contains("| NIST 800-53 CM-3 | **No control** |")
        assertThat(index.lines().count { it.startsWith("| SOC 2 CC8.1 |") }).describedAs("SOC 2 rows (matrix + requirements)").isEqualTo(2)
    }

    @Test
    @DisplayName("a trailing space is trimmed (the ISO row still links its controls) and the typo is reported")
    fun typoFile(@TempDir tempDir: Path) {
        val out = tempDir.resolve("out")
        val result = runCli(listOf("render", policies.toString(), "--out", out.toString(), "--requirements", typos.toString()), tempDir)

        assertThat(result.exitCode).isEqualTo(0)
        val index = Files.readString(out.resolve("index.md"))
        assertThat(index).contains("*3 of 4 requirements have at least one control.*")
        assertThat(index).contains("| ISO 27001 A.8.32 | [REL-001](release-approvals.md#rel-001), [REL-003](release-provenance.md#rel-003) |")
        assertThat(index).contains("*Framework strings not in the requirements list: DORA Art. 9.*")
    }

    @Test
    @DisplayName("without --requirements there is no requirements section")
    fun absentByDefault(@TempDir tempDir: Path) {
        val out = tempDir.resolve("out")
        assertThat(runCli(listOf("render", policies.toString(), "--out", out.toString()), tempDir).exitCode).isEqualTo(0)
        assertThat(Files.readString(out.resolve("index.md"))).doesNotContain("Requirements coverage")
    }

    @Test
    @DisplayName("a missing requirements file is a usage error: exit 1, a message, no stack trace, nothing written")
    fun missingFile(@TempDir tempDir: Path) {
        val out = tempDir.resolve("out")
        val result = runCli(listOf("render", policies.toString(), "--out", out.toString(), "--requirements", tempDir.resolve("nope.txt").toString()), tempDir)

        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.stderr).contains("nope.txt")
        assertThat(result.stderr).doesNotContain("Exception").doesNotContain("\tat ")
        assertThat(Files.exists(out)).isFalse()
    }
}
