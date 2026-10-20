/**
 * Process-level tests for the CI gates (exit code 5 = "gate failed"): `render --fail-under <percent>`, `render --fail-on-gaps`
 * and `diff --fail-on <categories>`. A gate never suppresses output -- the files are written and the normal stdout printed first --
 * and its message goes to stderr with no stack trace. Runs the real packaged CLI like [CliProcessTest]. Requires `opa` on PATH.
 */
package io.explico.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CliGatesProcessTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(io.explico.opa.OpaRunner.isAvailable(), "opa binary not on PATH")
        }
    }

    private val pack = Path.of("src/test/resources/acceptance/policies").toAbsolutePath().toString()
    // Not fully rendered: two `every` blocks stay source (18 of 20 conditions, 90%). The pack itself is 100% since `every` renders (spec §14.49).
    private val partial = Path.of("src/test/resources/realistic/every/policies").toAbsolutePath().toString()
    private val quality = Path.of("src/test/resources/realistic/quality/policies").toAbsolutePath().toString()
    private val req = Path.of("src/test/resources/realistic/requirements").toAbsolutePath()
    private val oldDir = Path.of("src/test/resources/diff/diff-all-categories/old").toAbsolutePath().toString()
    private val newDir = Path.of("src/test/resources/diff/diff-all-categories/new").toAbsolutePath().toString()

    private fun render(tempDir: Path, dir: String, vararg extra: String) =
        runCli(listOf("render", dir, "--out", tempDir.resolve("out").toString()) + extra, tempDir)

    private fun noStackTrace(stderr: String) = assertThat(stderr).doesNotContain("Exception").doesNotContain("\tat ")

    @Nested
    @DisplayName("render --fail-under")
    inner class FailUnder {

        @Test
        @DisplayName("18 of 20 (90%) passes 90 and 0")
        fun passes(@TempDir tempDir: Path) {
            assertThat(render(tempDir, partial, "--fail-under", "90").exitCode).isEqualTo(0)
            assertThat(render(tempDir, partial, "--fail-under", "0").exitCode).isEqualTo(0)
        }

        @Test
        @DisplayName("fails 91 with exit 5, the exact message on stderr, and the output still written and printed")
        fun failsAt96(@TempDir tempDir: Path) {
            val result = render(tempDir, partial, "--fail-under", "91")

            assertThat(result.exitCode).isEqualTo(5)
            assertThat(result.stderr.trim()).isEqualTo("explico: gate failed (--fail-under 91): rendering coverage is 90% (18 of 20 conditions)")
            assertThat(result.stdout).contains("Rendering coverage: 18 of 20 conditions (90%)")
            assertThat(Files.exists(tempDir.resolve("out/index.md"))).isTrue()
        }

        @Test
        @DisplayName("100 fails (90 < 100), and the fully rendered pack passes it")
        fun failsAt100(@TempDir tempDir: Path) {
            assertThat(render(tempDir, partial, "--fail-under", "100").exitCode).isEqualTo(5)
            assertThat(render(tempDir, pack, "--fail-under", "100").exitCode).isEqualTo(0)
        }

        @Test
        @DisplayName("a fully rendered set passes 100")
        fun fullPasses100(@TempDir tempDir: Path) {
            assertThat(render(tempDir, quality, "--fail-under", "100").exitCode).isEqualTo(0)
        }

        @Test
        @DisplayName("101, -1 and abc are usage errors: exit 1, no stack trace, nothing written")
        fun usageErrors(@TempDir tempDir: Path) {
            // A valid value is accepted by the same option (so the failures below are about the VALUE, not an unknown option).
            assertThat(render(tempDir, pack, "--fail-under", "95").exitCode).isEqualTo(0)
            for (bad in listOf("101", "-1", "abc")) {
                val out = tempDir.resolve("bad-$bad")
                val result = runCli(listOf("render", pack, "--out", out.toString(), "--fail-under", bad), tempDir)
                assertThat(result.exitCode).describedAs(bad).isEqualTo(1)
                assertThat(result.stderr).describedAs(bad).contains(bad)
                noStackTrace(result.stderr)
                assertThat(Files.exists(out)).describedAs(bad).isFalse()
            }
        }
    }

    @Nested
    @DisplayName("render --fail-on-gaps")
    inner class FailOnGaps {

        @Test
        @DisplayName("complete documentation passes, with and without a fully covered requirements list")
        fun passes(@TempDir tempDir: Path) {
            assertThat(render(tempDir, pack, "--fail-on-gaps").exitCode).isEqualTo(0)
            assertThat(render(tempDir, pack, "--fail-on-gaps", "--requirements", req.resolve("covered.txt").toString()).exitCode).isEqualTo(0)
        }

        @Test
        @DisplayName("the quality fixture fails with a count header and one line per gap, in order")
        fun quality(@TempDir tempDir: Path) {
            val result = render(tempDir, quality, "--fail-on-gaps")

            assertThat(result.exitCode).isEqualTo(5)
            assertThat(result.stderr.trim().lines()).containsExactly(
                "explico: gate failed (--fail-on-gaps): 3 gap(s)",
                "  - control Q-003 has no description",
                "  - control Q-002 names no framework",
                "  - controls Q-001, Q-002 share the title \"Shared\"",
            )
            assertThat(Files.exists(tempDir.resolve("out/index.md"))).isTrue()
            noStackTrace(result.stderr)
        }

        @Test
        @DisplayName("a requirement with no control fails it")
        fun requirement(@TempDir tempDir: Path) {
            val result = render(tempDir, pack, "--fail-on-gaps", "--requirements", req.resolve("requirements.txt").toString())

            assertThat(result.exitCode).isEqualTo(5)
            assertThat(result.stderr.trim().lines()).containsExactly(
                "explico: gate failed (--fail-on-gaps): 1 gap(s)",
                "  - requirement NIST 800-53 CM-3 has no control",
            )
        }

        @Test
        @DisplayName("without the flag the same gaps do not fail the build")
        fun flagOff(@TempDir tempDir: Path) {
            assertThat(render(tempDir, quality).exitCode).isEqualTo(0)
        }
    }

    @Test
    @DisplayName("both gates failing give ONE exit 5 and both messages")
    fun bothGates(@TempDir tempDir: Path) {
        val result = render(tempDir, partial, "--fail-under", "91", "--fail-on-gaps", "--requirements", req.resolve("requirements.txt").toString())

        assertThat(result.exitCode).isEqualTo(5)
        assertThat(result.stderr).contains("gate failed (--fail-under 91)").containsPattern("gate failed \\(--fail-on-gaps\\): [1-9][0-9]* gap\\(s\\)")
    }

    @Nested
    @DisplayName("diff --fail-on")
    inner class DiffFailOn {

        private fun diff(tempDir: Path, old: String, new: String, vararg extra: String) =
            runCli(listOf("diff", old, new, "--out", tempDir.resolve("report.md").toString()) + extra, tempDir)

        @Test
        @DisplayName("LOGIC_CHANGED fails and names the control")
        fun logicChanged(@TempDir tempDir: Path) {
            val result = diff(tempDir, oldDir, newDir, "--fail-on", "LOGIC_CHANGED")

            assertThat(result.exitCode).isEqualTo(5)
            assertThat(result.stderr.trim()).isEqualTo("explico: gate failed (--fail-on LOGIC_CHANGED): 1 control(s): REL-003")
            assertThat(result.stdout).contains("## Summary").contains("| LOGIC_CHANGED | 1 |")
            assertThat(Files.readString(tempDir.resolve("report.md"))).startsWith("# Policy diff report")
        }

        @Test
        @DisplayName("categories are case-insensitive, echoed upper-cased in the order given; controls listed in report order")
        fun caseInsensitiveAndOrder(@TempDir tempDir: Path) {
            val lower = diff(tempDir, oldDir, newDir, "--fail-on", "logic_changed,docs_changed")
            assertThat(lower.exitCode).isEqualTo(5)
            assertThat(lower.stderr.trim()).isEqualTo("explico: gate failed (--fail-on LOGIC_CHANGED,DOCS_CHANGED): 2 control(s): REL-003, REL-004")

            val mixed = diff(tempDir, oldDir, newDir, "--fail-on", "LOGIC_CHANGED,REMOVED")
            assertThat(mixed.stderr.trim()).isEqualTo("explico: gate failed (--fail-on LOGIC_CHANGED,REMOVED): 2 control(s): REL-001, REL-003")
        }

        @Test
        @DisplayName("no matching change passes: ADDED on an unchanged comparison")
        fun noChange(@TempDir tempDir: Path) {
            assertThat(diff(tempDir, oldDir, oldDir, "--fail-on", "ADDED,REMOVED,LOGIC_CHANGED,DOCS_CHANGED").exitCode).isEqualTo(0)
        }

        @Test
        @DisplayName("UNCHANGED and an unknown name are usage errors naming the valid values")
        fun usageErrors(@TempDir tempDir: Path) {
            for (bad in listOf("UNCHANGED", "bogus", "LOGIC_CHANGED,bogus")) {
                val result = diff(tempDir, oldDir, newDir, "--fail-on", bad)
                assertThat(result.exitCode).describedAs(bad).isEqualTo(1)
                assertThat(result.stderr).describedAs(bad).contains("ADDED").contains("REMOVED").contains("LOGIC_CHANGED").contains("DOCS_CHANGED")
                noStackTrace(result.stderr)
            }
        }

        @Test
        @DisplayName("without --fail-on a changed diff still exits 0")
        fun flagOff(@TempDir tempDir: Path) {
            assertThat(diff(tempDir, oldDir, newDir).exitCode).isEqualTo(0)
        }
    }
}
