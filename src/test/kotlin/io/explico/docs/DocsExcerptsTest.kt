/**
 * A documentation page may quote a repository file with `<!-- excerpt: path/to/file -->` directly above a fenced block. The block must be an
 * exact, contiguous run of lines from that file, so a quoted policy, fixture or generated card cannot drift from the real one. (The quoted
 * generated documents are themselves drift-checked in CI, so this chains: policy -> generated card -> the page that shows it.)
 */
package io.explico.docs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.toList

class DocsExcerptsTest {

    private data class Excerpt(val page: Path, val source: String, val lines: List<String>)

    private fun pages(): List<Path> =
        (Files.walk(Path.of("docs")).use { s -> s.filter { it.toString().endsWith(".md") }.toList() } + Path.of("README.md")).sorted()

    private fun excerptsIn(page: Path): List<Excerpt> {
        val lines = Files.readAllLines(page)
        val found = mutableListOf<Excerpt>()
        var i = 0
        while (i < lines.size) {
            val marker = Regex("^<!-- excerpt: (\\S+) -->$").find(lines[i].trim())
            if (marker != null) {
                check(i + 1 < lines.size && lines[i + 1].startsWith("```")) { "$page:${i + 1}: an excerpt marker must be followed by a fenced block" }
                val body = mutableListOf<String>()
                var j = i + 2
                while (j < lines.size && !lines[j].startsWith("```")) body += lines[j++].trimEnd()
                found += Excerpt(page, marker.groupValues[1], body)
                i = j
            }
            i++
        }
        return found
    }

    @Test
    @DisplayName("every quoted excerpt is an exact, contiguous run of lines from the file it names")
    fun excerptsMatchTheirSource() {
        val all = pages().flatMap { excerptsIn(it) }
        // Not vacuous: the guide and the home page really do quote files.
        assertThat(all.size).isGreaterThanOrEqualTo(5)

        val problems = all.filterNot { e ->
            val source = Files.readAllLines(Path.of(e.source)).map { it.trimEnd() }
            e.lines.isNotEmpty() && (0..source.size - e.lines.size).any { start -> source.subList(start, start + e.lines.size) == e.lines }
        }.map { "${it.page} quotes ${it.source}, but the block is not a contiguous run of its lines:\n${it.lines.joinToString("\n")}" }
        assertThat(problems).isEmpty()
    }
}
