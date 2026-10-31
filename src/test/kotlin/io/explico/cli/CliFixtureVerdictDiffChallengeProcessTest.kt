/**
 * Process-level edge cases of `diff --examples` (spec §14.74): duplicate fixture names are exit 4 with the name and nothing written, as for `render --examples`; both gates at once
 * report both messages. Requires `opa`.
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

class CliFixtureVerdictDiffChallengeProcessTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(io.explico.opa.OpaRunner.isAvailable(), "opa binary not on PATH")
        }

        private val base = Path.of("src/test/resources/realistic/fixture-verdict-diff")
        private val old = base.resolve("policies/old").toAbsolutePath().toString()
        private val new = base.resolve("policies/new").toAbsolutePath().toString()
    }

    @Test
    @DisplayName("a fixture name used twice is exit 4 naming it, with a plain message and no report written")
    fun duplicateFixtureName(@TempDir tempDir: Path) {
        val examples = tempDir.resolve("examples")
        Files.createDirectories(examples)
        Files.writeString(examples.resolve("a.json"), """{"name": "dup", "input": {}}""")
        Files.writeString(examples.resolve("b.json"), """{"name": "dup", "input": {}}""")
        val out = tempDir.resolve("report.md")

        val result = runCli(listOf("diff", old, new, "--out", out.toString(), "--examples", examples.toString()), tempDir)

        assertThat(result.exitCode).isEqualTo(4)
        assertThat(result.stderr).contains("dup").doesNotContain("\tat ")
        assertThat(Files.exists(out)).isFalse()
    }

    @Test
    @DisplayName("--fail-on and --fail-on-example-changes together: one exit 5 whose stderr carries both messages")
    fun bothGates(@TempDir tempDir: Path) {
        val out = tempDir.resolve("report.md")
        val result = runCli(
            listOf("diff", old, new, "--out", out.toString(), "--examples", base.resolve("examples").toAbsolutePath().toString(), "--fail-on", "LOGIC_CHANGED", "--fail-on-example-changes"),
            tempDir,
        )

        assertThat(result.exitCode).isEqualTo(5)
        assertThat(result.stderr).contains("gate failed (--fail-on LOGIC_CHANGED)").contains("gate failed (--fail-on-example-changes)")
        assertThat(Files.exists(out)).isTrue()
    }

    @Test
    @DisplayName("the gate does not pass having compared nothing: controls on both sides but only functions is exit 5 with the reason, and the report is still written")
    fun vacuousGate(@TempDir tempDir: Path) {
        fun fn(limit: Int) = "package a\n\nimport rego.v1\n\n# METADATA\n# scope: document\n# title: T\n# description: D\n# custom:\n#   control-id: C-1\n#   frameworks:\n#     - F 1\nis_big(x) if x > $limit\n"
        val oldDir = tempDir.resolve("old").also { Files.createDirectories(it); Files.writeString(it.resolve("a.rego"), fn(100)) }
        val newDir = tempDir.resolve("new").also { Files.createDirectories(it); Files.writeString(it.resolve("a.rego"), fn(50)) }
        val examples = tempDir.resolve("examples").also { Files.createDirectories(it); Files.writeString(it.resolve("a.json"), """{"name": "seventy", "input": {"amount": 70}}""") }
        val out = tempDir.resolve("report.md")

        val withoutGate = runCli(listOf("diff", oldDir.toString(), newDir.toString(), "--out", out.toString(), "--examples", examples.toString()), tempDir)
        val result = runCli(listOf("diff", oldDir.toString(), newDir.toString(), "--out", out.toString(), "--examples", examples.toString(), "--fail-on-example-changes"), tempDir)

        assertThat(withoutGate.exitCode).isEqualTo(0)
        assertThat(result.exitCode).isEqualTo(5)
        assertThat(result.stderr).contains("no control could be compared").contains("1 function(s)")
        assertThat(Files.readString(out)).contains("No example could be compared.")
    }

    @Test
    @DisplayName("--fail-on-example-changes with an examples directory that holds no fixture is a usage error (exit 1), not a silent pass")
    fun emptyExamplesDirectory(@TempDir tempDir: Path) {
        val examples = tempDir.resolve("examples").also { Files.createDirectories(it) }
        val out = tempDir.resolve("report.md")
        // Without the gate the same empty directory is simply "not supplied".
        assertThat(runCli(listOf("diff", old, new, "--out", out.toString(), "--examples", examples.toString()), tempDir).exitCode).isEqualTo(0)
        val gated = tempDir.resolve("gated.md")

        val result = runCli(listOf("diff", old, new, "--out", gated.toString(), "--examples", examples.toString(), "--fail-on-example-changes"), tempDir)

        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.stderr).contains("holds no fixture").doesNotContain("\tat ")
        assertThat(Files.exists(gated)).isFalse()
    }
}
