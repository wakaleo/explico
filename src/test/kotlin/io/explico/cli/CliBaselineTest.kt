/**
 * The committed CLI baseline `api/explico.cli` (spec §14.80 step 6): every command, argument, option and exit code as text, taken from the real command tree (Clikt's registered options, not the
 * help prose, whose wording may change). A change to the CLI is therefore a visible diff of this file, regenerated only by `-Dexplico.updateCli=true` (a deliberate, reviewed act, like
 * `-Dexplico.updateGolden=true`). No opa.
 *
 * FORMAT (pinned by the exact expected text below): LF line endings, a trailing newline, no timestamp or version. One block per command -- `explico`, then `demo`, `diff`, `render`, `version` --
 * separated by one blank line; a block is the command line (`explico <sub> <arguments in declaration order as <name>>`) then one line per option sorted by its first long name, indented two spaces,
 * `  --name  KIND`, where KIND is `flag`, `<metavar>` for a value option, `required <metavar>` when the option is required, or the choice set `(v1|v0)`. `-h/--help` is not listed. A last block,
 * `exit codes`, lists `  N  meaning` for 0-5 (spec §8.2).
 *
 * The expected text is the CLI of 2026-11-01 after the §14.80 flag freeze; a later flag change must edit it, deliberately. The cross-check against the real `--help` output keeps `CliSurface` honest
 * (it cannot silently drift from the packaged CLI). `build.gradle.kts` must pass `explico.updateCli` to the test JVM, like `explico.updateGolden`.
 */
package io.explico.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CliBaselineTest {

    private val baseline = Path.of("api/explico.cli")
    private val updateCli = System.getProperty("explico.updateCli") == "true"

    private val expected = listOf(
        "explico",
        "",
        "explico demo",
        "  --fetch-opa  flag",
        "",
        "explico diff <olddir> <newdir>",
        "  --examples  <path>",
        "  --fail-on  <value>",
        "  --group-by-control  flag",
        "  --json  flag",
        "  --new-data  <path>",
        "  --new-label  <text>",
        "  --old-data  <path>",
        "  --old-label  <text>",
        "  --out  required <path>",
        "  --rego-version  (v1|v0)",
        "",
        "explico render <policydir>",
        "  --companion-label  <value>",
        "  --companion-url  <value>",
        "  --control-id-from  <value>",
        "  --control-id-template  <value>",
        "  --data  <path>",
        "  --examples  <path>",
        "  --fail-on-gaps  flag",
        "  --fail-under  <int>",
        "  --force  flag",
        "  --generate-examples  flag",
        "  --group-by-control  flag",
        "  --json  flag",
        "  --must-have  <text>",
        "  --out  required <path>",
        "  --rego-version  (v1|v0)",
        "  --requirements  <path>",
        "  --source-url  <value>",
        "",
        "explico version",
        "",
        "exit codes",
        "  0  success",
        "  1  usage or refused output",
        "  2  opa rejected the input",
        "  3  opa missing or not 1.x",
        "  4  duplicate control id or fixture name",
        "  5  a CI gate failed",
    ).joinToString("\n", postfix = "\n")

    /** The blocks of [dump] by command line, e.g. `explico diff <olddir> <newdir>` -> its option lines. */
    private fun blocks(dump: String): Map<String, List<String>> =
        dump.trimEnd('\n').split("\n\n").associate { block -> block.lines().let { it.first() to it.drop(1) } }

    @Test
    @DisplayName("the dump is the whole CLI surface, in the pinned format: five command blocks and the exit codes")
    fun wholeSurface() {
        assertThat(CliSurface.dump()).isEqualTo(expected)
    }

    @Test
    @DisplayName("LF only, one trailing newline, deterministic")
    fun shape() {
        val dump = CliSurface.dump()

        assertThat(dump).isNotEmpty().doesNotContain("\r").endsWith("\n").doesNotEndWith("\n\n")
        assertThat(CliSurface.dump()).isEqualTo(dump)
    }

    @Test
    @DisplayName("the removed spellings are gone and the renamed ones are there: diff has no --force, --require or --fail-on-example-changes; render keeps --force and has --must-have")
    fun freezeIsReflected() {
        val blocks = blocks(CliSurface.dump())
        assertThat(blocks).containsKeys("explico diff <olddir> <newdir>", "explico render <policydir>")
        val diff = blocks.getValue("explico diff <olddir> <newdir>").joinToString("\n")
        val render = blocks.getValue("explico render <policydir>").joinToString("\n")

        assertThat(diff).contains("  --json  flag").contains("  --fail-on  <value>").doesNotContain("--force").doesNotContain("--require").doesNotContain("--fail-on-example-changes")
        assertThat(render).contains("  --must-have  <text>").contains("  --force  flag").contains("  --requirements  <path>").doesNotContain("  --require ")
    }

    @Test
    @DisplayName("every option appears once per command, and the options are sorted by name")
    fun optionsUniqueAndSorted() {
        val all = blocks(CliSurface.dump())
        assertThat(all.keys).describedAs("the command blocks").containsExactly("explico", "explico demo", "explico diff <olddir> <newdir>", "explico render <policydir>", "explico version", "exit codes")
        for ((command, options) in all.filterKeys { it != "exit codes" }) {
            val names = options.map { it.trim().substringBefore("  ") }
            assertThat(names).describedAs(command).doesNotHaveDuplicates().isSorted()
        }
    }

    @Test
    @DisplayName("it matches the packaged CLI: the options each command's --help lists are exactly the block's options (CliSurface cannot drift from the real tree)")
    fun matchesTheRealHelp(@TempDir tempDir: Path) {
        val blocks = blocks(CliSurface.dump())
        assertThat(blocks.keys.filter { it.startsWith("explico ") }).describedAs("the subcommand blocks").hasSize(4)
        for (command in listOf("demo", "diff", "render", "version")) {
            val help = runCli(listOf(command, "--help"), tempDir).stdout
            val inHelp = help.lines().mapNotNull { Regex("^ {2}(?:-[a-z], )?(--[a-z][a-z-]*)").find(it)?.groupValues?.get(1) }.filter { it != "--help" }
            val block = blocks.entries.single { it.key == "explico $command" || it.key.startsWith("explico $command ") }.value
            val inBaseline = block.map { it.trim().substringBefore("  ") }

            assertThat(inBaseline).describedAs("explico $command").containsExactlyInAnyOrderElementsOf(inHelp)
        }
    }

    @Test
    @DisplayName("the committed baseline api/explico.cli exists and equals the dump (regenerate deliberately with -Dexplico.updateCli=true)")
    fun committedBaselineIsCurrent() {
        val dump = CliSurface.dump()
        if (updateCli) {
            Files.createDirectories(baseline.parent)
            Files.writeString(baseline, dump)
            return
        }
        assertThat(baseline).describedAs("the committed CLI baseline api/explico.cli").exists()
        assertThat(Files.readString(baseline)).isEqualTo(dump).isEqualTo(expected)
    }
}
