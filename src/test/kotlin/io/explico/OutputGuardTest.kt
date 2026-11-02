/**
 * Unit tests for the PURE output-directory guard (spec §14.80 step 1): it decides from real paths and never touches anything, so home and the working directory are injected and no real
 * directory is at risk. The facade/process behaviour is pinned by `RealisticOutputGuardAcceptanceIT` and `CliOutputGuardProcessTest`.
 */
package io.explico

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assumptions.abort
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class OutputGuardTest {

    private fun refusal(dir: Path, force: Boolean = false, protect: List<Path> = emptyList(), home: Path? = null, cwd: Path): String {
        val e = assertThrows(OutputDirectoryException::class.java) { OutputGuard.checkDirectory(dir, force, protect, home, cwd) }
        assertThat(e.path).describedAs("the path as given").isEqualTo(dir)
        assertThat(e.message!!.lines()).describedAs("a refusal is one line").hasSize(1)
        return e.message!!
    }

    private fun allowed(dir: Path, force: Boolean = false, protect: List<Path> = emptyList(), home: Path? = null, cwd: Path): Path =
        OutputGuard.checkDirectory(dir, force, protect, home, cwd)

    private fun dirWith(root: Path, name: String, vararg files: String): Path {
        val dir = Files.createDirectories(root.resolve(name))
        for (f in files) Files.writeString(dir.resolve(f), "x")
        return dir
    }

    @Test
    @DisplayName("a missing directory, an empty one and a marked one are all fine")
    fun fine(@TempDir t: Path) {
        val cwd = Files.createDirectories(t.resolve("cwd"))
        allowed(t.resolve("missing/deeper"), cwd = cwd)
        allowed(dirWith(t, "empty"), cwd = cwd)
        allowed(dirWith(t, "marked", OutputGuard.MARKER, "page.md"), cwd = cwd)
        allowed(dirWith(t, "marker-only", OutputGuard.MARKER), cwd = cwd)
    }

    @Test
    @DisplayName("a non-empty directory without the marker is refused: not empty, force; force lets it through")
    fun unmarked(@TempDir t: Path) {
        val cwd = Files.createDirectories(t.resolve("cwd"))
        val dir = dirWith(t, "out", "keep.txt")

        assertThat(refusal(dir, cwd = cwd)).contains("not empty").contains("force")
        allowed(dir, force = true, cwd = cwd)
    }

    @Test
    @DisplayName("a directory holding only a .gitkeep is not empty (strict): refused without force")
    fun gitkeepOnly(@TempDir t: Path) {
        val cwd = Files.createDirectories(t.resolve("cwd"))
        val dir = dirWith(t, "out", ".gitkeep")

        assertThat(refusal(dir, cwd = cwd)).contains("not empty")
        allowed(dir, force = true, cwd = cwd)
    }

    @Test
    @DisplayName("force on a missing or empty directory changes nothing")
    fun forceIsANoOpHere(@TempDir t: Path) {
        val cwd = Files.createDirectories(t.resolve("cwd"))
        allowed(t.resolve("missing"), force = true, cwd = cwd)
        allowed(dirWith(t, "empty"), force = true, cwd = cwd)
    }

    @Test
    @DisplayName("an existing file is not a directory, with or without force")
    fun file(@TempDir t: Path) {
        val cwd = Files.createDirectories(t.resolve("cwd"))
        val file = t.resolve("out"); Files.writeString(file, "x")

        assertThat(refusal(file, cwd = cwd)).contains("not a directory")
        assertThat(refusal(file, force = true, cwd = cwd)).contains("not a directory")
    }

    @Test
    @DisplayName("a directory that is, or contains, an input is refused even with force; a neighbour and a child of the input are allowed")
    fun inputs(@TempDir t: Path) {
        val cwd = Files.createDirectories(t.resolve("cwd"))
        val project = dirWith(t, "project", "NOTES.txt")
        val policies = dirWith(project, "policies", "a.rego")
        val data = project.resolve("data.json"); Files.writeString(data, "{}")

        assertThat(refusal(policies, force = true, protect = listOf(policies), cwd = cwd)).contains("input")
        assertThat(refusal(project, force = true, protect = listOf(policies), cwd = cwd)).contains("input")
        assertThat(refusal(project, force = true, protect = listOf(data), cwd = cwd)).contains("input")
        assertThat(refusal(project, force = true, protect = listOf(project.resolve("not-there")), cwd = cwd)).describedAs("a protected path that does not exist is still contained").contains("input")
        allowed(project.resolve("docs"), force = true, protect = listOf(policies), cwd = cwd)
        allowed(policies.resolve("docs"), protect = listOf(policies), cwd = cwd)
    }

    @Test
    @DisplayName("the input check does not depend on the marker")
    fun inputsBeatTheMarker(@TempDir t: Path) {
        val cwd = Files.createDirectories(t.resolve("cwd"))
        val project = dirWith(t, "project", OutputGuard.MARKER)
        val policies = dirWith(project, "policies", "a.rego")

        assertThat(refusal(project, protect = listOf(policies), cwd = cwd)).contains("input")
    }

    @Test
    @DisplayName("by real path: a `..` segment and symlinks (on the directory or on the protected path) do not get round it")
    fun realPaths(@TempDir t: Path) {
        val cwd = Files.createDirectories(t.resolve("cwd"))
        val project = dirWith(t, "project", "NOTES.txt")
        val policies = dirWith(project, "policies", "a.rego")
        Files.createDirectories(project.resolve("other"))

        assertThat(refusal(project.resolve("other").resolve(".."), force = true, protect = listOf(policies), cwd = cwd)).contains("input")
        assertThat(refusal(project.resolve("missing").resolve(".."), force = true, protect = listOf(policies), cwd = cwd)).describedAs("`..` through a missing segment").contains("input")

        val link = t.resolve("link")
        val policiesLink = t.resolve("policies-link")
        try {
            Files.createSymbolicLink(link, project)
            Files.createSymbolicLink(policiesLink, policies)
        } catch (e: Exception) { abort("symbolic links are not available here: ${e.message}") }
        assertThat(refusal(link, force = true, protect = listOf(policies), cwd = cwd)).contains("input")
        assertThat(refusal(project, force = true, protect = listOf(policiesLink), cwd = cwd)).contains("input")
        assertThat(refusal(link.resolve("policies"), force = true, protect = listOf(policies), cwd = cwd)).describedAs("the input reached through the link").contains("input")
    }

    @Test
    @DisplayName("the working directory and any parent of it are refused even with force; a child is fine")
    fun workingDirectory(@TempDir t: Path) {
        val parent = dirWith(t, "parent", "mine.txt")
        val cwd = Files.createDirectories(parent.resolve("work"))

        assertThat(refusal(cwd, force = true, cwd = cwd)).contains("working directory")
        assertThat(refusal(parent, force = true, cwd = cwd)).contains("working directory")
        assertThat(refusal(t, force = true, cwd = cwd)).contains("working directory")
        assertThat(refusal(cwd.resolve("."), force = true, cwd = cwd)).describedAs("`.`").contains("working directory")
        assertThat(refusal(cwd.resolve("..").resolve("work"), force = true, cwd = cwd)).describedAs("a roundabout way to the same directory").contains("working directory")
        allowed(cwd.resolve("docs"), cwd = cwd)
        allowed(parent.resolve("sibling"), cwd = cwd)
    }

    @Test
    @DisplayName("a relative path is resolved against the real working directory: `.` is refused when that is the injected one")
    fun relative() {
        val cwd = Path.of("").toAbsolutePath()

        assertThat(refusal(Path.of("."), force = true, cwd = cwd)).contains("working directory")
        assertThat(refusal(Path.of(".."), force = true, cwd = cwd)).contains("working directory")
        allowed(Path.of("build/tmp/output-guard-relative-test"), cwd = cwd)
    }

    @Test
    @DisplayName("the home directory and any parent of it are refused even with force; a child is fine; an unknown home refuses nothing")
    fun home(@TempDir t: Path) {
        val cwd = Files.createDirectories(t.resolve("cwd"))
        val home = dirWith(t, "a/home", ".bashrc")

        assertThat(refusal(home, force = true, home = home, cwd = cwd)).contains("home directory")
        assertThat(refusal(home.parent, force = true, home = home, cwd = cwd)).contains("home directory")
        allowed(home.resolve("docs"), home = home, cwd = cwd)
        allowed(dirWith(t, "elsewhere"), home = home, cwd = cwd)
        allowed(dirWith(t, "unrelated"), force = true, home = null, cwd = cwd)
    }

    @Test
    @DisplayName("a filesystem root is refused even with force")
    fun root(@TempDir t: Path) {
        val cwd = Files.createDirectories(t.resolve("cwd"))
        val root = t.toAbsolutePath().root

        assertThat(refusal(root, force = true, cwd = cwd)).contains("filesystem root")
        assertThat(refusal(t.resolve("..").resolve("..").resolve("..").resolve("..").resolve("..").resolve("..").resolve("..").resolve(".."), force = true, cwd = cwd)).describedAs("`..` up to the root").contains("filesystem root")
    }

    @Test
    @DisplayName("the check returns the real path the writer must use, so a symlinked --out is replaced at its target")
    fun returnsTheRealPath(@TempDir t: Path) {
        val cwd = Files.createDirectories(t.resolve("cwd"))
        val target = dirWith(t, "target", OutputGuard.MARKER)
        val link = t.resolve("link")
        try { Files.createSymbolicLink(link, target) } catch (e: Exception) { abort("symbolic links are not available here: ${e.message}") }

        assertThat(allowed(link, cwd = cwd)).isEqualTo(target.toRealPath())
        assertThat(allowed(t.resolve("missing/x"), cwd = cwd)).isEqualTo(t.toRealPath().resolve("missing/x"))
    }

    @Test
    @DisplayName("a file target: a regular file or a missing path is fine, an existing directory is refused (is a directory) even though the caller may pass force")
    fun fileTarget(@TempDir t: Path) {
        val dir = dirWith(t, "report.md", "mine.txt")
        val file = t.resolve("old.md"); Files.writeString(file, "x")

        val e = assertThrows(OutputDirectoryException::class.java) { OutputGuard.checkFile(dir) }
        assertThat(e.path).isEqualTo(dir)
        assertThat(e.message).contains("is a directory")
        OutputGuard.checkFile(file)
        OutputGuard.checkFile(t.resolve("missing/new.md"))
    }

    @Test
    @DisplayName("security review L3: a marker that is a symlink (or not a regular file) does not prove the directory is explico's")
    fun markerMustBeARegularFile(@TempDir t: Path) {
        val cwd = Files.createDirectories(t.resolve("cwd"))
        val target = Files.writeString(t.resolve("elsewhere.txt"), OutputGuard.MARKER_TEXT)
        val linked = dirWith(t, "linked", "data.txt")
        try { Files.createSymbolicLink(linked.resolve(OutputGuard.MARKER), target) } catch (e: Exception) { abort("symbolic links are not available here: ${e.message}") }
        assertThat(refusal(linked, cwd = cwd)).contains("not empty")

        val asDirectory = dirWith(t, "as-dir", "data.txt")
        Files.createDirectories(asDirectory.resolve(OutputGuard.MARKER))
        assertThat(refusal(asDirectory, cwd = cwd)).contains("not empty")
    }

    @Test
    @DisplayName("security review L5: a dangling symlink is refused with a plain reason, even with force")
    fun danglingSymlink(@TempDir t: Path) {
        val cwd = Files.createDirectories(t.resolve("cwd"))
        val link = t.resolve("dangling")
        try { Files.createSymbolicLink(link, t.resolve("nowhere")) } catch (e: Exception) { abort("symbolic links are not available here: ${e.message}") }
        assertThat(refusal(link, cwd = cwd)).contains("symbolic link")
        assertThat(refusal(link, force = true, cwd = cwd)).contains("symbolic link")
    }
}
