/**
 * Process-level tests for the output-directory guard (spec §14.80 step 1): the real packaged CLI as a child process, because the exit code, the single stderr line and "nothing was
 * deleted" are process-boundary facts. `render ... --out DIR` used to delete DIR recursively; `--out` set to a directory holding the policies destroyed them.
 *
 * Pinned (exact wording chosen here; /tdd must match): a refusal is exit 1 with EXACTLY ONE non-blank stderr line (so `failWith`, not Clikt's multi-line usage error) that names the
 * directory as the user typed it and the reason -- `not empty` plus `--force` when force would help; `input` (policies, examples or data), `working directory`, `home directory` or
 * `filesystem root` when it would not; `is a directory` for `diff --out`. The guard runs BEFORE opa is needed and before anything is deleted, and the real deletion happens only when the
 * write itself does. `--force` is new on `render` and `diff`.
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

class CliOutputGuardProcessTest {

    companion object {
        private const val MARKER = ".explico-output"
        private val noOpa = "/nonexistent/opa"

        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(io.explico.opa.OpaRunner.isAvailable(), "opa binary not on PATH")
        }
    }

    private val acceptance = Path.of("src/test/resources/acceptance").toAbsolutePath()
    private val diffBase = Path.of("src/test/resources/diff/diff-all-categories").toAbsolutePath()

    private fun tree(dir: Path): Map<String, String> = Files.walk(dir).use { paths ->
        paths.filter { it != dir }.sorted().toList().associate { p ->
            val rel = dir.relativize(p).toString().replace('\\', '/')
            if (Files.isDirectory(p)) "$rel/" to "" else rel to Files.readString(p)
        }
    }

    private fun copy(from: Path, to: Path): Path {
        Files.walk(from).use { paths ->
            paths.forEach { p ->
                val target = to.resolve(from.relativize(p).toString())
                if (Files.isDirectory(p)) Files.createDirectories(target) else { Files.createDirectories(target.parent); Files.copy(p, target) }
            }
        }
        return to
    }

    /** project/{policies, examples, data, NOTES.txt}: what a user's directory looks like. */
    private fun project(root: Path): Path {
        val project = root.resolve("project")
        copy(acceptance.resolve("policies"), project.resolve("policies"))
        copy(acceptance.resolve("examples"), project.resolve("examples"))
        copy(acceptance.resolve("data"), project.resolve("data"))
        Files.writeString(project.resolve("NOTES.txt"), "my notes\n")
        return project
    }

    private fun nonBlank(stderr: String) = stderr.lines().filter { it.isNotBlank() }

    private fun assertRefusedLine(result: CliResult, vararg parts: String) {
        assertThat(result.exitCode).describedAs(result.stderr).isEqualTo(1)
        assertThat(nonBlank(result.stderr)).describedAs("exactly one stderr line, no usage text").hasSize(1)
        for (part in parts) assertThat(result.stderr).contains(part)
    }

    @Test
    @DisplayName("the reproduced bug: render --out <the directory that holds the policies> is refused and nothing in it is deleted")
    fun outIsTheParentOfThePolicies(@TempDir tempDir: Path) {
        val project = project(tempDir)
        val before = tree(project)

        val result = runCli(listOf("render", project.resolve("policies").toString(), "--out", project.toString()), tempDir)

        assertRefusedLine(result, project.toString(), "input")
        assertThat(tree(project)).describedAs("policies, examples, data and notes survive; nothing written").isEqualTo(before)
    }

    @Test
    @DisplayName("--out holding an unrelated file is refused without --force: one line saying not empty and naming --force; the file survives")
    fun unrelatedFile(@TempDir tempDir: Path) {
        val out = Files.createDirectories(tempDir.resolve("out"))
        Files.writeString(out.resolve("keep.txt"), "my work")

        val result = runCli(listOf("render", acceptance.resolve("policies").toString(), "--out", out.toString()), tempDir)

        assertRefusedLine(result, out.toString(), "not empty", "--force")
        assertThat(tree(out)).containsOnlyKeys("keep.txt")
        assertThat(Files.readString(out.resolve("keep.txt"))).isEqualTo("my work")
    }

    @Test
    @DisplayName("the same directory with --force is replaced: the file goes, the pages and the marker are there")
    fun forced(@TempDir tempDir: Path) {
        val out = Files.createDirectories(tempDir.resolve("out"))
        Files.writeString(out.resolve("keep.txt"), "my work")

        val result = runCli(listOf("render", acceptance.resolve("policies").toString(), "--out", out.toString(), "--force"), tempDir)

        assertThat(result.exitCode).describedAs(result.stderr).isEqualTo(0)
        assertThat(Files.exists(out.resolve("keep.txt"))).isFalse()
        assertThat(Files.exists(out.resolve("index.md"))).isTrue()
        assertThat(Files.readString(out.resolve(MARKER))).isEqualTo("Generated by explico. This directory is replaced on every run.\n")
    }

    @Test
    @DisplayName("a second run into the same --out needs no --force (the marker is there) and replaces everything, including a file added in between")
    fun secondRun(@TempDir tempDir: Path) {
        val out = tempDir.resolve("out")
        val args = listOf("render", acceptance.resolve("policies").toString(), "--out", out.toString())
        assertThat(runCli(args, tempDir).exitCode).isEqualTo(0)
        assertThat(Files.exists(out.resolve(MARKER))).isTrue()
        Files.writeString(out.resolve("stale.md"), "leftover")

        val second = runCli(args, tempDir)

        assertThat(second.exitCode).describedAs(second.stderr).isEqualTo(0)
        assertThat(Files.exists(out.resolve("stale.md"))).isFalse()
        assertThat(Files.exists(out.resolve("index.md"))).isTrue()
    }

    @Test
    @DisplayName("--out equal to the policies directory is refused even with --force")
    fun outIsThePolicies(@TempDir tempDir: Path) {
        val project = project(tempDir)
        val policies = project.resolve("policies")
        val before = tree(project)

        val result = runCli(listOf("render", policies.toString(), "--out", policies.toString(), "--force"), tempDir)

        assertRefusedLine(result, policies.toString(), "input")
        assertThat(tree(project)).isEqualTo(before)
    }

    @Test
    @DisplayName("--out holding the --examples directory, or the --data file, is refused even with --force")
    fun outHoldsExamplesOrData(@TempDir tempDir: Path) {
        val project = project(tempDir)
        val elsewhere = copy(acceptance.resolve("policies"), tempDir.resolve("pol"))
        val before = tree(project)

        val withExamples = runCli(listOf("render", elsewhere.toString(), "--out", project.toString(), "--examples", project.resolve("examples").toString(), "--force"), tempDir)
        val withData = runCli(listOf("render", elsewhere.toString(), "--out", project.toString(), "--data", project.resolve("data/release/data.json").toString(), "--force"), tempDir)

        assertRefusedLine(withExamples, project.toString(), "input")
        assertRefusedLine(withData, project.toString(), "input")
        assertThat(tree(project)).isEqualTo(before)
    }

    @Test
    @DisplayName("the working directory and its parent are refused even with --force (the inputs are elsewhere, so only this rule can fire)")
    fun workingDirectory(@TempDir tempDir: Path) {
        val elsewhere = copy(acceptance.resolve("policies"), tempDir.resolve("pol"))
        val work = Files.createDirectories(tempDir.resolve("work"))
        Files.writeString(work.resolve("mine.txt"), "mine")
        val before = tree(tempDir)

        val here = runCli(listOf("render", elsewhere.toString(), "--out", ".", "--force"), work)
        val parent = runCli(listOf("render", elsewhere.toString(), "--out", "..", "--force"), work)

        assertRefusedLine(here, "working directory")
        assertRefusedLine(parent, "working directory")
        assertThat(tree(tempDir)).isEqualTo(before)
    }

    @Test
    @DisplayName("the guard runs before opa is needed: with no opa a refused directory is exit 1, not 3, and nothing is deleted")
    fun refusedBeforeOpa(@TempDir tempDir: Path) {
        val out = Files.createDirectories(tempDir.resolve("out"))
        Files.writeString(out.resolve("keep.txt"), "my work")

        val result = runCli(listOf("render", acceptance.resolve("policies").toString(), "--out", out.toString()), tempDir, opaBin = noOpa)

        assertRefusedLine(result, "not empty")
        assertThat(Files.readString(out.resolve("keep.txt"))).isEqualTo("my work")
    }

    @Test
    @DisplayName("with --force and no opa the run fails with 3 and the directory is still intact: nothing is deleted until the write itself")
    fun forcedButNoOpaDeletesNothing(@TempDir tempDir: Path) {
        val out = Files.createDirectories(tempDir.resolve("out"))
        Files.writeString(out.resolve("keep.txt"), "my work")

        val result = runCli(listOf("render", acceptance.resolve("policies").toString(), "--out", out.toString(), "--force"), tempDir, opaBin = noOpa)

        assertThat(result.exitCode).describedAs(result.stderr).isEqualTo(3)
        assertThat(Files.readString(out.resolve("keep.txt"))).isEqualTo("my work")
    }

    @Test
    @DisplayName("diff --out an existing directory is refused (is a directory), with or without --force and before opa is needed; its contents survive")
    fun diffOutIsADirectory(@TempDir tempDir: Path) {
        val dir = Files.createDirectories(tempDir.resolve("report.md"))
        Files.writeString(dir.resolve("mine.txt"), "mine")
        val args = listOf("diff", diffBase.resolve("old").toString(), diffBase.resolve("new").toString(), "--out", dir.toString())

        val plain = runCli(args, tempDir, opaBin = noOpa)
        val forced = runCli(args + "--force", tempDir, opaBin = noOpa)

        assertRefusedLine(plain, dir.toString(), "is a directory")
        assertRefusedLine(forced, dir.toString(), "is a directory")
        assertThat(tree(dir)).containsOnlyKeys("mine.txt")
    }

    @Test
    @DisplayName("diff --out an existing regular file overwrites it, with or without --force")
    fun diffOverwritesAFile(@TempDir tempDir: Path) {
        val file = tempDir.resolve("report.md")
        val args = listOf("diff", diffBase.resolve("old").toString(), diffBase.resolve("new").toString(), "--out", file.toString())

        Files.writeString(file, "an older report")
        val plain = runCli(args, tempDir)
        val afterPlain = Files.readString(file)
        Files.writeString(file, "an older report")
        val forced = runCli(args + "--force", tempDir)

        assertThat(plain.exitCode).describedAs(plain.stderr).isEqualTo(0)
        assertThat(forced.exitCode).describedAs(forced.stderr).isEqualTo(0)
        assertThat(afterPlain).contains("## Summary").doesNotContain("an older report")
        assertThat(Files.readString(file)).isEqualTo(afterPlain)
    }

    @Test
    @DisplayName("--force is documented in the help of render and diff")
    fun helpMentionsForce(@TempDir tempDir: Path) {
        for (command in listOf("render", "diff")) {
            val help = runCli(listOf(command, "--help"), tempDir).stdout.replace(Regex("\\s+"), " ")
            assertThat(help).describedAs("$command --help").contains("--force")
        }
    }
}
