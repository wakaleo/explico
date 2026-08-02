/** Unit tests for LineDiffer (spec §7.3): no opa needed, pure algorithm. */
package io.explico.diff

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LineDifferTest {

    @Test
    fun bothEmptyProducesNoLines() {
        assertThat(LineDiffer.diff(emptyList(), emptyList())).isEmpty()
    }

    @Test
    fun emptyOldAllNewLinesAreAdded() {
        val result = LineDiffer.diff(emptyList(), listOf("a", "b"))
        assertThat(result).containsExactly(DiffLine.Added("a"), DiffLine.Added("b"))
    }

    @Test
    fun emptyNewAllOldLinesAreRemoved() {
        val result = LineDiffer.diff(listOf("a", "b"), emptyList())
        assertThat(result).containsExactly(DiffLine.Removed("a"), DiffLine.Removed("b"))
    }

    @Test
    fun identicalFilesAreAllUnchanged() {
        val lines = listOf("package foo", "", "deny if { input.x == 1 }")
        val result = LineDiffer.diff(lines, lines)
        assertThat(result).containsExactly(*lines.map { DiffLine.Unchanged(it) as DiffLine }.toTypedArray())
    }

    @Test
    fun singleLineChangeSurroundedByUnchangedContext() {
        val old = listOf("line one", "line two", "line three")
        val new = listOf("line one", "line TWO", "line three")
        val result = LineDiffer.diff(old, new)
        assertThat(result).containsExactly(
            DiffLine.Unchanged("line one"),
            DiffLine.Removed("line two"),
            DiffLine.Added("line TWO"),
            DiffLine.Unchanged("line three"),
        )
    }

    @Test
    fun singleLineInsertionKeepsSurroundingLinesUnchanged() {
        val old = listOf("line one", "line three")
        val new = listOf("line one", "line two", "line three")
        val result = LineDiffer.diff(old, new)
        assertThat(result).containsExactly(
            DiffLine.Unchanged("line one"),
            DiffLine.Added("line two"),
            DiffLine.Unchanged("line three"),
        )
    }

    @Test
    fun singleLineDeletionKeepsSurroundingLinesUnchanged() {
        val old = listOf("line one", "line two", "line three")
        val new = listOf("line one", "line three")
        val result = LineDiffer.diff(old, new)
        assertThat(result).containsExactly(
            DiffLine.Unchanged("line one"),
            DiffLine.Removed("line two"),
            DiffLine.Unchanged("line three"),
        )
    }

    @Test
    fun fullRewriteWithNoCommonLinesRemovesAllOldThenAddsAllNew() {
        val old = listOf("alpha", "beta")
        val new = listOf("gamma", "delta", "epsilon")
        val result = LineDiffer.diff(old, new)

        assertThat(result.filterIsInstance<DiffLine.Unchanged>()).isEmpty()
        assertThat(result.filterIsInstance<DiffLine.Removed>().map { it.text }).isEqualTo(old)
        assertThat(result.filterIsInstance<DiffLine.Added>().map { it.text }).isEqualTo(new)
    }

    @Test
    fun blankLinesAreComparedLikeAnyOtherLine() {
        val old = listOf("a", "", "b")
        val new = listOf("a", "b")
        val result = LineDiffer.diff(old, new)
        assertThat(result).containsExactly(
            DiffLine.Unchanged("a"),
            DiffLine.Removed(""),
            DiffLine.Unchanged("b"),
        )
    }
}
