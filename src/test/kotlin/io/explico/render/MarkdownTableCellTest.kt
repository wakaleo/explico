/** Security review L1: text written by a policy author goes into table cells; a `|` must not add a column and a line break must not end the row. */
package io.explico.render

import io.explico.Explico
import io.explico.RenderOptions
import io.explico.opa.OpaRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class MarkdownTableCellTest {
    @Test
    @DisplayName("tableCell escapes a pipe, turns a line break into a space and leaves everything else alone")
    fun cell() {
        assertThat(MarkdownText.tableCell("C|1")).isEqualTo("C\\|1")
        assertThat(MarkdownText.tableCell("a\nb\r\nc")).isEqualTo("a b c")
        assertThat(MarkdownText.tableCell("SOC 2 A1.2 — plain")).isEqualTo("SOC 2 A1.2 — plain")
    }

    @Test
    @DisplayName("a pipe in a control id, title, framework or requirement keeps every index row at its own column count")
    fun indexRows(@TempDir tmp: Path) {
        assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
        val pol = Files.createDirectories(tmp.resolve("pol"))
        Files.writeString(pol.resolve("a.rego"), """
            package a

            # METADATA
            # title: Left | right
            # custom:
            #   control-id: C|1
            #   frameworks:
            #     - F|1
            deny contains msg if {
                input.x == 1
                msg := "no"
            }
        """.trimIndent())
        val docs = Explico.render(Explico.load(pol), RenderOptions(requirements = listOf("R|1")))
        val index = docs.files.getValue("index.md")
        assertThat(index).contains("C\\|1").contains("Left \\| right").contains("F\\|1").contains("R\\|1")
        // every row of one table has the same number of unescaped pipes as its separator line
        val tables = index.split("\n\n").map { it.lines().filter { l -> l.startsWith("|") } }.filter { it.size >= 2 }
        for (t in tables) {
            val expected = Regex("""(?<!\\)\|""").findAll(t[1]).count()
            for (row in t) assertThat(Regex("""(?<!\\)\|""").findAll(row).count()).describedAs(row).isEqualTo(expected)
        }
    }
}
