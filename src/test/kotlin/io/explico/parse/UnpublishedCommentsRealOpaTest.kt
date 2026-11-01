/**
 * Spec §14.65 against real `opa parse` output, for the shapes a hand-built list cannot vouch for: Windows line endings, no final newline, a block at the end
 * of the file, a `#` inside a string, tab-indented comments, and Rego v0. Needs `opa` (guarded like the other real-parser tests).
 */
package io.explico.parse

import io.explico.Explico
import io.explico.model.CommentBlock
import io.explico.model.RegoVersion
import io.explico.opa.OpaRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class UnpublishedCommentsRealOpaTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
        }
    }

    private fun blocks(dir: Path, name: String, text: String, version: RegoVersion = RegoVersion.V1): List<CommentBlock> {
        Files.writeString(dir.resolve(name), text)
        return Explico.load(dir, version).packages.single().unpublishedComments
    }

    @Test
    @DisplayName("a bare `#` line (opa reports its text as null) loads, and is part of its block: found by the real-world corpus benchmark, it crashed every file that had one")
    fun bareHashLine(@TempDir dir: Path) {
        assertThat(blocks(dir, "p.rego", "# one\n#\n# two\npackage p\n\nr if {\n\tinput.a == 1\n}\n")).containsExactly(CommentBlock("p.rego", 1, 3))
    }

    @Test
    @DisplayName("a bare `#` line inside a METADATA block, and one that is the only comment of a file, load without a finding or a crash")
    fun bareHashInMetadataAndAlone(@TempDir dir: Path) {
        val metadata = "# METADATA\n# title: T\n#\n# description: D\n# custom:\n#   control-id: C-1\npackage p\n\nr if {\n\tinput.a == 1\n}\n"
        assertThat(blocks(dir, "m.rego", metadata)).isEmpty()
        val alone = dir.resolve("alone")
        Files.createDirectories(alone)
        assertThat(blocks(alone, "a.rego", "package q\n\n#\nr if {\n\tinput.a == 1\n}\n")).isEmpty()
    }

    @Test
    @DisplayName("Windows line endings give the same rows as Unix ones")
    fun crlf(@TempDir dir: Path) {
        val unix = "# one\n# two\npackage p\n\nr if {\n\tinput.a == 1\n}\n"
        assertThat(blocks(dir, "p.rego", unix.replace("\n", "\r\n"))).containsExactly(CommentBlock("p.rego", 1, 2))
    }

    @Test
    @DisplayName("a block at the very end of the file, with no final newline, is found; so is one directly after the package line")
    fun endOfFileAndAfterPackage(@TempDir dir: Path) {
        val text = "package p\n# right after the package line\n# second line\n\nr if {\n\tinput.a == 1\n}\n\n# closing note\n# last line, no newline after it"
        assertThat(blocks(dir, "p.rego", text)).containsExactly(CommentBlock("p.rego", 2, 2), CommentBlock("p.rego", 9, 2))
    }

    @Test
    @DisplayName("a `#` inside a string is not a comment: opa says so, and nothing here second-guesses it")
    fun hashInAString(@TempDir dir: Path) {
        val text = "package p\n\nr if {\n\tinput.a == \"a # b\"\n\tinput.c == \"# not a comment\"\n}\n"
        assertThat(blocks(dir, "p.rego", text)).isEmpty()
    }

    @Test
    @DisplayName("a comment indented with a tab or spaces is not column 1, so a block of them is not reported; two blocks with one blank row between are two")
    fun indentation(@TempDir dir: Path) {
        val indented = "package p\n\n\t# tab one\n\t# tab two\n  # space one\n  # space two\n\n# real one\n# real two\n\n# second block one\n# second block two\n"
        assertThat(blocks(dir, "p.rego", indented)).containsExactly(CommentBlock("p.rego", 8, 2), CommentBlock("p.rego", 11, 2))
    }

    @Test
    @DisplayName("a METADATA block with no plain lines before it, and one directly above `package` (package scope), are not blocks")
    fun metadataOnly(@TempDir dir: Path) {
        val text = "# METADATA\n# scope: package\n# title: A package\npackage p\n\n# METADATA\n# title: R\nr if {\n\tinput.a == 1\n}\n"
        assertThat(blocks(dir, "p.rego", text)).isEmpty()
    }

    @Test
    @DisplayName("Rego v0 files are read the same way")
    fun regoV0(@TempDir dir: Path) {
        val text = "# note one\n# note two\npackage p\n\nr {\n\tinput.a == 1\n}\n"
        assertThat(blocks(dir, "p.rego", text, RegoVersion.V0)).containsExactly(CommentBlock("p.rego", 1, 2))
    }

    @Test
    @DisplayName("a policy directory with no comments at all has no blocks")
    fun none(@TempDir dir: Path) {
        assertThat(blocks(dir, "p.rego", "package p\n\nr if {\n\tinput.a == 1\n}\n")).isEmpty()
    }

    @Test
    @DisplayName("comments are documentation-neutral for diff: adding, editing or removing a plain comment block leaves every control UNCHANGED")
    fun commentsDoNotChangeTheDiff(@TempDir old: Path, @TempDir new: Path) {
        val body = "package p\n\n# METADATA\n# title: T\n# custom:\n#   control-id: C-1\nr if {\n\tinput.a == 1\n}\n"
        Files.writeString(old.resolve("p.rego"), body)
        Files.writeString(new.resolve("p.rego"), "# an explanation\n# of two lines\n$body# trailing block\n# of two lines\n")
        val report = Explico.diff(Explico.load(old), Explico.load(new))
        assertThat(report.entries.map { it.category.name }.distinct()).containsExactly("UNCHANGED")
        assertThat(Explico.load(new).packages.single().unpublishedComments).hasSize(2)
    }
}
