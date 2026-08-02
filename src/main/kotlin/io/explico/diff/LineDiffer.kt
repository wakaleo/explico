/**
 * Plain LCS-based line differ (spec §7.3): no dependency, used to render a unified text diff of
 * two rule bodies' verbatim source for a LOGIC_CHANGED control.
 */
package io.explico.diff

/** One line of a two-file comparison, in output order. */
internal sealed interface DiffLine {
    data class Unchanged(val text: String) : DiffLine
    data class Removed(val text: String) : DiffLine
    data class Added(val text: String) : DiffLine
}

internal object LineDiffer {

    /**
     * Longest-common-subsequence line diff: lines present in both [old] and [new], in the same
     * relative order, are [DiffLine.Unchanged]; everything else is [DiffLine.Removed] (old-only)
     * or [DiffLine.Added] (new-only).
     */
    fun diff(old: List<String>, new: List<String>): List<DiffLine> {
        val m = old.size
        val n = new.size
        val lcsLength = Array(m + 1) { IntArray(n + 1) }
        for (i in m - 1 downTo 0) {
            for (j in n - 1 downTo 0) {
                lcsLength[i][j] = if (old[i] == new[j]) {
                    lcsLength[i + 1][j + 1] + 1
                } else {
                    maxOf(lcsLength[i + 1][j], lcsLength[i][j + 1])
                }
            }
        }

        val result = mutableListOf<DiffLine>()
        var i = 0
        var j = 0
        while (i < m && j < n) {
            when {
                old[i] == new[j] -> {
                    result += DiffLine.Unchanged(old[i])
                    i++
                    j++
                }
                lcsLength[i + 1][j] >= lcsLength[i][j + 1] -> {
                    result += DiffLine.Removed(old[i])
                    i++
                }
                else -> {
                    result += DiffLine.Added(new[j])
                    j++
                }
            }
        }
        while (i < m) {
            result += DiffLine.Removed(old[i])
            i++
        }
        while (j < n) {
            result += DiffLine.Added(new[j])
            j++
        }
        return result
    }
}
