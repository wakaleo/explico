/**
 * Unit tests for the one place explico deletes or writes its output (spec §14.80 step 1), beyond the acceptance tests: the CHALLENGE cases. No opa.
 */
package io.explico

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assumptions.abort
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class OutputWriterTest {

    private val files = mapOf("index.md" to "# index\n", "controls/c-1.md" to "# c-1\n")

    @Test
    @DisplayName("output inside the policies directory is allowed and never touches the policies")
    fun insideThePolicies(@TempDir t: Path) {
        val policies = Files.createDirectories(t.resolve("policies"))
        Files.writeString(policies.resolve("a.rego"), "package a\n")

        OutputWriter.writeDirectory(policies.resolve("docs"), files, force = false, protect = listOf(policies))
        OutputWriter.writeDirectory(policies.resolve("docs"), files, force = false, protect = listOf(policies))

        assertThat(Files.readString(policies.resolve("a.rego"))).isEqualTo("package a\n")
        assertThat(Files.readString(policies.resolve("docs/controls/c-1.md"))).isEqualTo("# c-1\n")
    }

    @Test
    @DisplayName("a symlinked output directory is replaced at its target and stays a link")
    fun symlinkedOut(@TempDir t: Path) {
        val target = Files.createDirectories(t.resolve("target"))
        Files.writeString(target.resolve(OutputGuard.MARKER), OutputGuard.MARKER_TEXT)
        Files.writeString(target.resolve("stale.md"), "old")
        val link = t.resolve("link")
        try { Files.createSymbolicLink(link, target) } catch (e: Exception) { abort("symbolic links are not available here: ${e.message}") }

        OutputWriter.writeDirectory(link, files, force = false, protect = emptyList())

        assertThat(Files.isSymbolicLink(link)).isTrue()
        assertThat(Files.exists(target.resolve("stale.md"))).isFalse()
        assertThat(Files.readString(target.resolve("index.md"))).isEqualTo("# index\n")
    }

    @Test
    @DisplayName("a relative output path works")
    fun relative() {
        val dir = Path.of("build/tmp/output-writer-relative-test")
        OutputWriter.writeDirectory(dir, files, force = false, protect = emptyList())
        OutputWriter.writeDirectory(dir, files, force = false, protect = emptyList())

        assertThat(Files.readString(dir.resolve("index.md"))).isEqualTo("# index\n")
        assertThat(Files.exists(dir.resolve(OutputGuard.MARKER))).isTrue()
    }

    @Test
    @DisplayName("an interrupted write leaves a directory the next run may replace: the marker is written first")
    fun interrupted(@TempDir t: Path) {
        val dir = t.resolve("out")
        // "a" is a file and "a/b" needs "a" to be a directory: the second file cannot be written.
        assertThrows(IOException::class.java) { OutputWriter.writeDirectory(dir, mapOf("a" to "x", "a/b" to "y"), force = false, protect = emptyList()) }
        assertThat(Files.exists(dir.resolve(OutputGuard.MARKER))).isTrue()

        OutputWriter.writeDirectory(dir, files, force = false, protect = emptyList())

        assertThat(Files.exists(dir.resolve("a"))).isFalse()
        assertThat(Files.readString(dir.resolve("index.md"))).isEqualTo("# index\n")
    }

    @Test
    @DisplayName("a refused write deletes and creates nothing, not even the missing parents")
    fun refusedLeavesNothing(@TempDir t: Path) {
        val home = Files.createDirectories(t.resolve("h"))
        val saved = System.getProperty("user.home")
        System.setProperty("user.home", home.toString())
        try {
            assertThrows(OutputDirectoryException::class.java) { OutputWriter.writeDirectory(home, files, force = true, protect = emptyList()) }
        } finally { System.setProperty("user.home", saved) }

        assertThat(Files.list(home).use { it.count() }).isZero()
    }

    @Test
    @DisplayName("a report file is written with its parents, replaced when it exists, and a directory is refused untouched")
    fun file(@TempDir t: Path) {
        val file = t.resolve("a/b/report.md")
        OutputWriter.writeFile(file, "one")
        OutputWriter.writeFile(file, "two")
        assertThat(Files.readString(file)).isEqualTo("two")

        val dir = Files.createDirectories(t.resolve("d"))
        assertThrows(OutputDirectoryException::class.java) { OutputWriter.writeFile(dir, "x") }
        assertThat(Files.list(dir).use { it.count() }).isZero()
    }
}
