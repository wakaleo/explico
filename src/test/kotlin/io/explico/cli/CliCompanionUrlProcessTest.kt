/**
 * Process-level test for `render --companion-url <template>` / `--companion-label <text>` (spec §14.58): the second link appears on the cards, a mistake in
 * either option is a usage error (exit 1, a message that says how to fix it, no stack trace, nothing written), a label with no URL is one too, and `diff`
 * does not take the options. Runs the real packaged CLI like [CliSourceUrlProcessTest]. Requires `opa`.
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

class CliCompanionUrlProcessTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(io.explico.opa.OpaRunner.isAvailable(), "opa binary not on PATH")
        }
    }

    private val policies = Path.of("src/test/resources/realistic/companion-source/policies").toAbsolutePath().toString()
    private val template = "https://modules.example.com/{package}/src/{file}#L{line}"

    private fun render(tempDir: Path, vararg extra: String): Pair<CliResult, Path> {
        val out = tempDir.resolve("out")
        return runCli(listOf("render", policies, "--out", out.toString()) + extra, tempDir) to out
    }

    @Test
    @DisplayName("--companion-url adds the link to the cards and the package page; --companion-label names it; stdout is the usual coverage line")
    fun links(@TempDir tempDir: Path) {
        val (result, out) = render(tempDir, "--companion-url", template, "--companion-label", "Billing module")

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.stdout).startsWith("Rendering coverage:")
        val page = Files.readString(out.resolve("app-billing.md"))
        assertThat(page).contains("[Billing module](https://modules.example.com/app.billing/src/billing/charge.rego#L8)")
        assertThat(page).contains("[Billing module](https://modules.example.com/app.billing/src/billing/charge.rego#L1)")
    }

    @Test
    @DisplayName("the label defaults to 'module'; without the option no companion link and no companionUrl in the JSON")
    fun defaultsAndOff(@TempDir tempDir: Path) {
        val (_, out) = render(tempDir, "--companion-url", template)
        assertThat(Files.readString(out.resolve("app-ops.md"))).contains("[module](https://modules.example.com/app.ops/src/ops/audit.rego#L8)")

        val (off, offOut) = render(tempDir.resolve("off").also { Files.createDirectories(it) }, "--json")
        assertThat(off.exitCode).isEqualTo(0)
        assertThat(Files.readString(offOut.resolve("app-ops.md"))).doesNotContain("[module]")
        assertThat(Files.readString(offOut.resolve("explico.json"))).doesNotContain("companionUrl")
    }

    private fun assertUsageError(result: CliResult, out: Path, vararg messageParts: String) {
        assertThat(result.exitCode).isEqualTo(1)
        for (part in messageParts) assertThat(result.stderr).contains(part)
        assertThat(result.stderr).doesNotContain("Exception").doesNotContain("\tat ")
        assertThat(Files.exists(out)).describedAs("nothing is written").isFalse()
    }

    @Test
    @DisplayName("a template with neither {file} nor {package} is a usage error naming both")
    fun badTemplate(@TempDir tempDir: Path) {
        val (result, out) = render(tempDir, "--companion-url", "https://x.example.com/#L{line}")
        assertUsageError(result, out, "{file}", "{package}")
    }

    @Test
    @DisplayName("a label that would break the link is a usage error")
    fun badLabel(@TempDir tempDir: Path) {
        val (result, out) = render(tempDir, "--companion-url", template, "--companion-label", "a]b")
        assertUsageError(result, out, "label")
    }

    @Test
    @DisplayName("a label with no --companion-url is a usage error (it would silently do nothing)")
    fun labelAlone(@TempDir tempDir: Path) {
        val (result, out) = render(tempDir, "--companion-label", "module")
        assertUsageError(result, out, "--companion-label", "--companion-url")
    }

    @Test
    @DisplayName("diff does not take the options")
    fun diffRejects(@TempDir tempDir: Path) {
        val result = runCli(listOf("diff", policies, policies, "--out", tempDir.resolve("r.md").toString(), "--companion-url", template), tempDir)
        assertThat(result.exitCode).isEqualTo(1)
        assertThat(Files.exists(tempDir.resolve("r.md"))).isFalse()
    }
}
