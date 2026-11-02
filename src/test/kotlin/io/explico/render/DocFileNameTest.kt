/**
 * Security review (INFO): a package name becomes a file name. Odd names (a bracket key with `/`, `:` or `..`, a name like `con`) must stay one top-level, portable file, and two different
 * package names must never share a file. Ordinary names keep their file names exactly (every golden depends on it).
 */
package io.explico.render

import io.explico.Explico
import io.explico.opa.OpaRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DocFileNameTest {
    private fun name(p: String) = MarkdownRenderer.docFileName(p)

    @Test
    @DisplayName("an ordinary package path keeps its name: dots become dashes")
    fun ordinary() {
        assertThat(name("release.approvals")).isEqualTo("release-approvals.md")
        assertThat(name("databases")).isEqualTo("databases.md")
        assertThat(name("a_b.c1")).isEqualTo("a_b-c1.md")
    }

    @Test
    @DisplayName("names that would have collided get different files: a.b.c, a.b-c and a['b.c']")
    fun noCollisions() {
        val names = listOf("a.b.c", "a.b-c", "a.b_c", "a[\"b.c\"]", "a[\"b\"].c", "a.b c", "A.b.c").map(::name)
        assertThat(names).doesNotHaveDuplicates()
    }

    @Test
    @DisplayName("no file name carries a separator, a colon, a quote, a bracket or a space, and none is a subdirectory")
    fun portable() {
        for (p in listOf("a[\"../../x\"]", "b[\"/tmp/abs\"]", "c[\"d:e\"]", "f.g\\h", "i[\"j k\"]", "l[\"m\\\"n\"]", "o.é")) {
            val n = name(p)
            assertThat(n).describedAs(p).matches("[A-Za-z0-9_-]+\\.md")
        }
    }

    @Test
    @DisplayName("a Windows device name is not used as a file name, whatever its case")
    fun devices() {
        for (p in listOf("con", "nul", "aux", "prn", "com1", "lpt9", "Con", "NUL")) {
            assertThat(name(p).removeSuffix(".md").lowercase()).describedAs(p).isNotIn("con", "nul", "aux", "prn", "com1", "lpt9")
        }
        assertThat(name("con")).isNotEqualTo(name("con-package")).isNotEqualTo(name("con.x"))
    }

    @Test
    @DisplayName("the name is a function of the path alone")
    fun deterministic() {
        assertThat(name("a[\"b-c\"]")).isEqualTo(name("a[\"b-c\"]"))
    }

    @Test
    @DisplayName("rendered through opa: odd package names stay top-level files and no two packages share one")
    fun endToEnd(@TempDir tmp: Path) {
        assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
        val pol = Files.createDirectories(tmp.resolve("pol"))
        val pkgs = listOf("a.b.c", "a[\"b-c\"]", "a[\"../x\"]", "con", "a[\"d:e\"]")
        pkgs.forEachIndexed { i, p -> Files.writeString(pol.resolve("p$i.rego"), "package $p\n\nr$i := 1 if { input.v == $i }\n") }
        val set = Explico.load(pol)
        val files = Explico.render(set).files.keys.filter { it != "index.md" }
        assertThat(files).noneMatch { it.contains('/') || it.contains(':') }
        assertThat(files).hasSize(set.packages.size).doesNotHaveDuplicates()
    }
}
