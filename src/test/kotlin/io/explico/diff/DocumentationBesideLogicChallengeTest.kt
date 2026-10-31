/**
 * CHALLENGE cases for documentation changes beside a logic change (spec §14.75) that need the real `opa` binary and a small policy each: `--group-by-control` entries and a
 * real multi-line block-scalar description. The main contract is RealisticMetadataWithLogicDiffAcceptanceIT.
 */
package io.explico.diff

import io.explico.Explico
import io.explico.opa.OpaRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DocumentationBesideLogicChallengeTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
        }

        private fun policy(pkg: String, id: String, description: String, limit: Int) = """
            package $pkg

            import rego.v1

            # METADATA
            # scope: document
            # title: Title $id
            # description: $description
            # custom:
            #   control-id: $id
            #   frameworks:
            #     - F 1
            deny contains msg if {
            	input.amount > $limit
            	msg := "too much"
            }
        """.trimIndent() + "\n"

        private fun write(dir: Path, name: String, text: String) = dir.resolve(name).also { Files.createDirectories(it.parent); Files.writeString(it, text) }
    }

    @Test
    @DisplayName("--group-by-control: each rule of a shared control gets its own sub-bullet and its own block under its own qualified card")
    fun groupedControl(@TempDir dir: Path) {
        write(dir, "old/a.rego", policy("a", "SHARED", "old a", 100))
        write(dir, "old/b.rego", policy("b", "SHARED", "old b", 100))
        write(dir, "new/a.rego", policy("a", "SHARED", "new a", 50))
        write(dir, "new/b.rego", policy("b", "SHARED", "new b", 50))

        val report = Explico.diff(Explico.load(dir.resolve("old")), Explico.load(dir.resolve("new")), groupByControl = true)

        assertThat(report.markdown.lines().count { it == "  - documentation changed: description" }).isEqualTo(2)
        assertThat(report.markdown).contains("| Description | old a | new a |", "| Description | old b | new b |")
        assertThat(report.markdown.lines().count { it == "**Documentation changes**" }).isEqualTo(2)
    }

    @Test
    @DisplayName("a real block-scalar description of several lines shows with <br> in the old and the new cell")
    fun multiLine(@TempDir dir: Path) {
        fun block(first: String, limit: Int) = "package a\n\nimport rego.v1\n\n# METADATA\n# scope: document\n# title: T\n# description: |\n#   $first\n#   second line\n" +
            "# custom:\n#   control-id: C-1\n#   frameworks:\n#     - F 1\ndeny contains msg if {\n\tinput.amount > $limit\n\tmsg := \"too much\"\n}\n"
        write(dir, "old/a.rego", block("first", 100))
        write(dir, "new/a.rego", block("changed", 50))

        val report = Explico.diff(Explico.load(dir.resolve("old")), Explico.load(dir.resolve("new")))

        assertThat(report.entries.single().category).isEqualTo(DiffCategory.LOGIC_CHANGED)
        assertThat(report.markdown).contains("**Documentation changes**").contains("| Description | first<br>second line | changed<br>second line |")
    }
}
