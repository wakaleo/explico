/**
 * Spec §14.65: the plain comment blocks of one file that never reach the documentation. Pure: comments and rule spans in, blocks out -- everything about
 * WHERE a comment is comes from opa's own locations (`opa parse`), nothing is read from the source text here.
 */
package io.explico.parse

import io.explico.model.CommentBlock

internal object CommentBlocks {

    /** One comment as opa reports it: 1-based [row] and [col], and its [text] without the `#`. */
    data class Comment(val row: Int, val col: Int, val text: String)

    /** The shortest run worth reporting: a lone comment line is a remark, not an explanation. */
    private const val MIN_LINES = 2

    /**
     * A block is a run of consecutive rows of column-1 comments, cut at its first `METADATA` line (the METADATA block, and anything after it in that run,
     * is published and never counts), of at least [MIN_LINES] lines, that does not start inside a rule's source span ([ruleSpans], row ranges: a column-1
     * comment inside a body is part of the rule). A comment after code (column > 1) is never in a block and breaks a run.
     */
    fun of(file: String, comments: List<Comment>, ruleSpans: List<IntRange>): List<CommentBlock> {
        val lineComments = comments.filter { it.col == 1 }.sortedBy { it.row }
        val runs = mutableListOf<MutableList<Comment>>()
        for (comment in lineComments) {
            val last = runs.lastOrNull()
            if (last != null && last.last().row + 1 == comment.row) last += comment else runs += mutableListOf(comment)
        }
        return runs.mapNotNull { run ->
            val plain = run.takeWhile { it.text.trim() != "METADATA" }
            val first = plain.firstOrNull() ?: return@mapNotNull null
            if (plain.size < MIN_LINES || ruleSpans.any { first.row in it }) null else CommentBlock(file, first.row, plain.size)
        }
    }
}
