/**
 * The CLI flag renames of the 1.0 freeze (spec §14.80 step 5), through the real packaged CLI. A COMPLETE rename, no aliases (operator decision 2026-11-01): the old spellings are simply unknown
 * options, exit 1. Authored from the 1.0 API review, NOT from the frozen acceptance-pack README. Decisions pinned here (/tdd must match them):
 *
 * - `diff --json` is a FLAG like `render --json`: it writes the report's JSON to `<out>.json`, the --out path with `.json` APPENDED (`r.md` -> `r.md.json`, never a swapped extension, so it can never
 *   collide with --out). The content is exactly what the library's `DiffReport.json` holds. Without the flag nothing is written. The old `--json <file>` is a usage error (the file is an unexpected
 *   extra argument). If `<out>.json` is an existing directory it is refused like --out: exit 1, one stderr line, before opa is needed, nothing written.
 * - `render --require` is renamed `--must-have`; same semantics and messages, now naming `--must-have`. `--require` is an unknown option; `--requirements` (a file) is a different option and untouched.
 * - `diff --fail-on-example-changes` is gone; `diff --fail-on` takes `EXAMPLE_CHANGES` next to ADDED, REMOVED, LOGIC_CHANGED, DOCS_CHANGED. It means what the old flag did (exit 5 when a fixture of
 *   --examples changed outcome; the report is still written) and needs --examples (an examples directory with no fixture is refused, as before). The example gate prints
 *   `explico: gate failed (--fail-on EXAMPLE_CHANGES): ...` (the former counts-and-controls text, new spelling); the category gate echoes only the DIFF categories given, so
 *   `--fail-on LOGIC_CHANGED,EXAMPLE_CHANGES` prints `(--fail-on LOGIC_CHANGED)` first and `(--fail-on EXAMPLE_CHANGES)` second, as the two gates did. Unknown names (and UNCHANGED) are refused.
 * - `diff --force` is gone (it did nothing); `render --force` is unchanged.
 * - Everything else (exit codes 0-5, the --out guard, --fail-under, --fail-on-gaps, --requirements) is as before; the existing process tests of those keep their meaning.
 *
 * Needs opa (guarded). The help texts are CliFlagFreezeHelpProcessTest's.
 */
package io.explico.cli

import io.explico.DiffOptions
import io.explico.Explico
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CliFlagFreezeProcessTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(io.explico.opa.OpaRunner.isAvailable(), "opa binary not on PATH")
        }

        private val allBase = Path.of("src/test/resources/diff/diff-all-categories")
        private val allOld = allBase.resolve("old").toAbsolutePath().toString()
        private val allNew = allBase.resolve("new").toAbsolutePath().toString()
        private val fsd = Path.of("src/test/resources/realistic/fixture-verdict-diff")
        private val fsdOld = fsd.resolve("policies/old").toAbsolutePath().toString()
        private val fsdNew = fsd.resolve("policies/new").toAbsolutePath().toString()
        private val fsdExamples = fsd.resolve("examples").toAbsolutePath().toString()
        private val requiredPolicies = Path.of("src/test/resources/realistic/required-set/policies").toAbsolutePath().toString()
        private val requiredExamples = Path.of("src/test/resources/realistic/required-set/examples").toAbsolutePath().toString()
    }

    private fun diff(tempDir: Path, old: String, new: String, vararg extra: String): Pair<CliResult, Path> {
        val out = tempDir.resolve("report.md")
        return runCli(listOf("diff", old, new, "--out", out.toString()) + extra, tempDir) to out
    }

    private fun render(tempDir: Path, vararg extra: String) = runCli(listOf("render", requiredPolicies, "--out", tempDir.resolve("out").toString()) + extra, tempDir)

    @Nested
    @DisplayName("diff --json is a flag that writes <out>.json")
    inner class DiffJson {

        @Test
        @DisplayName("writes the change report's JSON beside the report: report.md -> report.md.json; stdout is still the Summary")
        fun writesBesideTheReport(@TempDir tempDir: Path) {
            val (result, out) = diff(tempDir, allOld, allNew, "--json")

            assertThat(result.exitCode).describedAs(result.stderr).isEqualTo(0)
            assertThat(result.stdout).contains("## Summary").doesNotContain("explico/diff/1")
            assertThat(Files.readString(out)).startsWith("# Policy diff report")
            val json = tempDir.resolve("report.md.json")
            assertThat(json).exists()
            assertThat(Files.readString(json)).contains("\"schema\": \"explico/diff/1\"").contains("\"REL-003\"")
            assertThat(tempDir.resolve("report.json")).describedAs("the extension is appended, not swapped").doesNotExist()
        }

        @Test
        @DisplayName("is byte for byte what the library's DiffReport.json holds")
        fun sameAsTheLibrary(@TempDir tempDir: Path) {
            diff(tempDir, allOld, allNew, "--json")

            val expected = Explico.diff(Explico.load(Path.of(allOld)), Explico.load(Path.of(allNew)), DiffOptions(digests = true)).json
            assertThat(tempDir.resolve("report.md.json")).exists()
            assertThat(Files.readString(tempDir.resolve("report.md.json"))).isEqualTo(expected)
        }

        @Test
        @DisplayName("without the flag no JSON file is written")
        fun absentByDefault(@TempDir tempDir: Path) {
            val (result, _) = diff(tempDir, allOld, allNew)

            assertThat(result.exitCode).isEqualTo(0)
            assertThat(tempDir.resolve("report.md.json")).doesNotExist()
        }

        @Test
        @DisplayName("the parent directories of --out are created for both files")
        fun createsParents(@TempDir tempDir: Path) {
            val out = tempDir.resolve("a/b/r.md")

            val result = runCli(listOf("diff", allOld, allNew, "--out", out.toString(), "--json"), tempDir)

            assertThat(result.exitCode).describedAs(result.stderr).isEqualTo(0)
            assertThat(out).exists()
            assertThat(tempDir.resolve("a/b/r.md.json")).exists()
        }

        @Test
        @DisplayName("the old form `--json <file>` is a usage error: exit 1, the file named as an unexpected argument, nothing written")
        fun oldFormIsGone(@TempDir tempDir: Path) {
            val old = tempDir.resolve("x.json")
            val (result, out) = diff(tempDir, allOld, allNew, "--json", old.toString())

            assertThat(result.exitCode).isEqualTo(1)
            assertThat(result.stderr).contains("x.json").doesNotContain("\tat ")
            assertThat(out).doesNotExist()
            assertThat(old).doesNotExist()
        }

        @Test
        @DisplayName("an existing directory where <out>.json would go is refused: exit 1, one line naming it, before opa is needed, nothing written or deleted")
        fun jsonSiblingIsADirectory(@TempDir tempDir: Path) {
            val dir = Files.createDirectories(tempDir.resolve("report.md.json"))
            Files.writeString(dir.resolve("mine.txt"), "mine")

            val result = runCli(listOf("diff", allOld, allNew, "--out", tempDir.resolve("report.md").toString(), "--json"), tempDir, opaBin = "/nonexistent/opa")

            assertThat(result.exitCode).describedAs(result.stderr).isEqualTo(1)
            assertThat(result.stderr.lines().filter { it.isNotBlank() }).hasSize(1)
            assertThat(result.stderr).contains("is a directory").contains(dir.toString())
            assertThat(Files.readString(dir.resolve("mine.txt"))).isEqualTo("mine")
            assertThat(tempDir.resolve("report.md")).doesNotExist()
        }
    }

    @Nested
    @DisplayName("render --must-have replaces --require")
    inner class MustHave {

        @Test
        @DisplayName("--must-have title,verdict --examples: those two (and the shared title) fail the gate; description and framework no longer do; the output is still written")
        fun titleAndVerdict(@TempDir tempDir: Path) {
            val result = render(tempDir, "--examples", requiredExamples, "--fail-on-gaps", "--must-have", "title,verdict")

            assertThat(result.exitCode).isEqualTo(5)
            assertThat(result.stderr).contains("3 gap(s)").contains("control RS-2 has no title").contains("control RS-6 returns an object but declares no custom.verdict")
                .contains("share the title").doesNotContain("has no description").doesNotContain("names no framework")
            assertThat(tempDir.resolve("out/index.md")).exists()
        }

        @Test
        @DisplayName("whitespace around the commas is ignored, and fixture/verdict work with --examples")
        fun whitespace(@TempDir tempDir: Path) {
            val result = render(tempDir, "--examples", requiredExamples, "--fail-on-gaps", "--must-have", " fixture , verdict ")

            assertThat(result.exitCode).isEqualTo(5)
            assertThat(result.stderr).contains("control RS-5 has no matching example").contains("control RS-6 returns an object")
        }

        @Test
        @DisplayName("--must-have without --fail-on-gaps is refused naming both options: it only changes what that gate fails on; nothing written")
        fun needsTheGate(@TempDir tempDir: Path) {
            val result = render(tempDir, "--must-have", "title")

            assertThat(result.exitCode).isEqualTo(1)
            assertThat(result.stderr).contains("--must-have").contains("--fail-on-gaps").doesNotContain("\tat ")
            assertThat(tempDir.resolve("out")).doesNotExist()
        }

        @Test
        @DisplayName("an unknown property is refused naming the valid ones, and fixture without --examples is refused: exit 1, nothing written")
        fun invalidNames(@TempDir tempDir: Path) {
            val unknown = render(tempDir, "--fail-on-gaps", "--must-have", "titel")
            val fixture = render(tempDir, "--fail-on-gaps", "--must-have", "fixture")

            assertThat(unknown.exitCode).isEqualTo(1)
            assertThat(unknown.stderr).contains("title, description, frameworks, fixture, verdict")
            assertThat(fixture.exitCode).isEqualTo(1)
            assertThat(fixture.stderr).contains("'fixture'").contains("example fixtures")
            assertThat(tempDir.resolve("out")).doesNotExist()
        }

        @Test
        @DisplayName("--require is an unknown option now (exit 1, nothing written), while --requirements, the file option, still works")
        fun oldNameIsGone(@TempDir tempDir: Path) {
            val old = render(tempDir, "--fail-on-gaps", "--require", "title")
            val file = Files.writeString(tempDir.resolve("reqs.txt"), "SOC 2 CC8.1\n")
            val requirements = render(tempDir, "--requirements", file.toString())

            assertThat(old.exitCode).isEqualTo(1)
            assertThat(old.stderr).contains("no such option").contains("--require")
            assertThat(requirements.exitCode).describedAs(requirements.stderr).isEqualTo(0)
            assertThat(Files.readString(tempDir.resolve("out/index.md"))).contains("## Requirements coverage")
        }
    }

    @Nested
    @DisplayName("diff --fail-on EXAMPLE_CHANGES replaces --fail-on-example-changes")
    inner class ExampleChangesGate {

        @Test
        @DisplayName("exit 5 when examples flipped, naming the counts and the controls on stderr in the new spelling, after writing the report; the category gate stays silent")
        fun gateFails(@TempDir tempDir: Path) {
            val (result, out) = diff(tempDir, fsdOld, fsdNew, "--examples", fsdExamples, "--fail-on", "EXAMPLE_CHANGES")

            assertThat(result.exitCode).isEqualTo(5)
            assertThat(result.stderr).contains("gate failed (--fail-on EXAMPLE_CHANGES)").contains("3 example(s)").contains("3 control(s)")
                .contains("BANK-EXM-001").contains("BANK-TGT-001").contains("BANK-MSG-001").doesNotContain("--fail-on LOGIC_CHANGED").doesNotContain("\tat ")
            assertThat(result.stdout).contains("| LOGIC_CHANGED |")
            assertThat(Files.readString(out)).contains("## Behaviour on your examples")
        }

        @Test
        @DisplayName("passes (exit 0) when no example changed outcome, and the category name is case-insensitive like the others")
        fun passesWhenNothingFlipped(@TempDir tempDir: Path) {
            val (same, out) = diff(tempDir, fsdOld, fsdOld, "--examples", fsdExamples, "--fail-on", "EXAMPLE_CHANGES")
            val (lower, _) = diff(tempDir, fsdOld, fsdNew, "--examples", fsdExamples, "--fail-on", "example_changes")

            assertThat(same.exitCode).describedAs(same.stderr).isEqualTo(0)
            assertThat(out).exists()
            assertThat(lower.exitCode).isEqualTo(5)
        }

        @Test
        @DisplayName("without --examples it is a usage error naming EXAMPLE_CHANGES and --examples: exit 1, no stack trace, nothing written")
        fun needsExamples(@TempDir tempDir: Path) {
            val (result, out) = diff(tempDir, fsdOld, fsdNew, "--fail-on", "EXAMPLE_CHANGES")

            assertThat(result.exitCode).isEqualTo(1)
            assertThat(result.stderr).contains("EXAMPLE_CHANGES").contains("--examples").doesNotContain("\tat ")
            assertThat(out).doesNotExist()
        }

        @Test
        @DisplayName("an examples directory that holds no fixture is refused, not a silent pass; without the gate the same directory is simply 'not supplied'")
        fun emptyExamplesDirectory(@TempDir tempDir: Path) {
            val examples = Files.createDirectories(tempDir.resolve("empty"))
            val (plain, _) = diff(tempDir, fsdOld, fsdNew, "--examples", examples.toString())
            val gated = runCli(listOf("diff", fsdOld, fsdNew, "--out", tempDir.resolve("gated.md").toString(), "--examples", examples.toString(), "--fail-on", "EXAMPLE_CHANGES"), tempDir)

            assertThat(plain.exitCode).isEqualTo(0)
            assertThat(gated.exitCode).isEqualTo(1)
            assertThat(gated.stderr).contains("holds no fixture").doesNotContain("\tat ")
            assertThat(tempDir.resolve("gated.md")).doesNotExist()
        }

        @Test
        @DisplayName("combined with a category (--fail-on LOGIC_CHANGED,EXAMPLE_CHANGES) both gates report, the category gate first, each echoing only its own names")
        fun bothGates(@TempDir tempDir: Path) {
            val (result, out) = diff(tempDir, fsdOld, fsdNew, "--examples", fsdExamples, "--fail-on", "LOGIC_CHANGED,EXAMPLE_CHANGES")

            assertThat(result.exitCode).isEqualTo(5)
            assertThat(result.stderr).contains("gate failed (--fail-on LOGIC_CHANGED)").contains("gate failed (--fail-on EXAMPLE_CHANGES)").doesNotContain("LOGIC_CHANGED,EXAMPLE_CHANGES")
            assertThat(result.stderr.indexOf("(--fail-on LOGIC_CHANGED)")).isLessThan(result.stderr.indexOf("(--fail-on EXAMPLE_CHANGES)"))
            assertThat(out).exists()
        }

        @Test
        @DisplayName("an unknown category is refused naming all five valid ones, and UNCHANGED is still not one of them: exit 1, nothing written")
        fun unknownCategory(@TempDir tempDir: Path) {
            for (bad in listOf("BOGUS", "UNCHANGED")) {
                val (result, out) = diff(tempDir, fsdOld, fsdNew, "--fail-on", bad)

                assertThat(result.exitCode).describedAs(bad).isEqualTo(1)
                for (valid in listOf("ADDED", "REMOVED", "LOGIC_CHANGED", "DOCS_CHANGED", "EXAMPLE_CHANGES")) assertThat(result.stderr).describedAs("$bad lists $valid").contains(valid)
                assertThat(out).doesNotExist()
            }
        }

        @Test
        @DisplayName("the old option --fail-on-example-changes is an unknown option: exit 1, nothing written")
        fun oldOptionIsGone(@TempDir tempDir: Path) {
            val (result, out) = diff(tempDir, fsdOld, fsdNew, "--examples", fsdExamples, "--fail-on-example-changes")

            assertThat(result.exitCode).isEqualTo(1)
            assertThat(result.stderr).contains("no such option").contains("--fail-on-example-changes")
            assertThat(out).doesNotExist()
        }
    }

    @Nested
    @DisplayName("--force belongs to render alone")
    inner class Force {

        @Test
        @DisplayName("diff --force is an unknown option (exit 1, nothing written); render --force still replaces an unmarked directory")
        fun diffForceIsGone(@TempDir tempDir: Path) {
            val (diff, out) = diff(tempDir, allOld, allNew, "--force")
            val outDir = Files.createDirectories(tempDir.resolve("out"))
            Files.writeString(outDir.resolve("keep.txt"), "x")
            val render = render(tempDir, "--force")

            assertThat(diff.exitCode).isEqualTo(1)
            assertThat(diff.stderr).contains("no such option").contains("--force")
            assertThat(out).doesNotExist()
            assertThat(render.exitCode).describedAs(render.stderr).isEqualTo(0)
            assertThat(outDir.resolve("keep.txt")).doesNotExist()
        }
    }
}
