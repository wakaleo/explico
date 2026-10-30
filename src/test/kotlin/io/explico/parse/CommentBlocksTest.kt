/**
 * Spec §14.65: which plain comment blocks never reach the documentation. Pure -- a list of comments (row, column, text as opa reports it: without the `#`)
 * and the row spans of the file's rules in, [CommentBlock]s out. No opa.
 */
package io.explico.parse

import io.explico.model.CommentBlock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class CommentBlocksTest {

    private fun c(row: Int, text: String = " text", col: Int = 1) = CommentBlocks.Comment(row, col, text)

    private fun blocks(vararg comments: CommentBlocks.Comment, spans: List<IntRange> = emptyList()) = CommentBlocks.of("f.rego", comments.toList(), spans)

    private fun block(row: Int, lines: Int) = CommentBlock("f.rego", row, lines)

    @Test
    @DisplayName("a run of two or more consecutive column-1 comment lines is one block: its first row and its length")
    fun aRun() {
        assertThat(blocks(c(1), c(2), c(3))).containsExactly(block(1, 3))
    }

    @Test
    @DisplayName("a single comment line is not reported, and a blank row between two lines makes two runs (each of one line here)")
    fun singlesAndBlankSeparation() {
        assertThat(blocks(c(1))).isEmpty()
        assertThat(blocks(c(1), c(3))).isEmpty()
        assertThat(blocks(c(1), c(2), c(4), c(5), c(6))).containsExactly(block(1, 2), block(4, 3))
    }

    @Test
    @DisplayName("a comment after code (column > 1) is never part of a block and breaks a run")
    fun trailingAndIndented() {
        assertThat(blocks(c(1, col = 5), c(2, col = 5))).isEmpty()
        assertThat(blocks(c(1), c(2, col = 3), c(3))).isEmpty()
    }

    @Test
    @DisplayName("a run is cut at its first METADATA line: only the lines before it count, the METADATA block itself and anything after never do")
    fun metadataCuts() {
        assertThat(blocks(c(1), c(2), c(3), c(4, " METADATA"), c(5, " title: x"), c(6, " description: y"))).containsExactly(block(1, 3))
        assertThat(blocks(c(1, " METADATA"), c(2, " title: x"), c(3, " description: y"))).isEmpty()
        assertThat(blocks(c(1), c(2, " METADATA"), c(3, " a"), c(4, " METADATA"), c(5, " b"))).isEmpty() // one plain line before the first marker
        assertThat(blocks(c(1), c(2), c(3, " METADATA"), c(4, " a"), c(5, " METADATA"), c(6, " b"))).containsExactly(block(1, 2))
    }

    @Test
    @DisplayName("the METADATA marker is matched on its trimmed text, exactly: other words containing it are plain text")
    fun markerIsExact() {
        assertThat(blocks(c(1, " METADATA"), c(2))).isEmpty()
        assertThat(blocks(c(1, "METADATA   "), c(2))).isEmpty()
        assertThat(blocks(c(1, " METADATA is described below"), c(2))).containsExactly(block(1, 2))
    }

    @Test
    @DisplayName("a run inside a rule's source span is never reported, even in column 1; one directly after the span is")
    fun insideASpan() {
        assertThat(blocks(c(5), c(6), spans = listOf(3..8))).isEmpty()
        assertThat(blocks(c(9), c(10), spans = listOf(3..8))).containsExactly(block(9, 2))
        assertThat(blocks(c(2), c(3), spans = listOf(4..8))).containsExactly(block(2, 2))
    }

    @Test
    @DisplayName("comments are taken in row order whatever order opa lists them in")
    fun unsortedInput() {
        assertThat(blocks(c(3), c(1), c(2))).containsExactly(block(1, 3))
    }

    @Test
    @DisplayName("no comments, no blocks")
    fun empty() {
        assertThat(blocks()).isEmpty()
    }
}
