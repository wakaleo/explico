/**
 * Deterministic breadcrumb-style path humanisation (spec §6.4). No natural-language
 * grammar -- just structural rendering of an already-resolved [PathSegment] list.
 *
 * Note: rule 7's "a var-rooted path whose variable has no SomeIn binding -> Operand.Unrendered"
 * is enforced by AstMapper at mapping time, not here -- an unbound var-rooted path never
 * becomes an `Operand.Path` in the first place, so this humaniser never sees it.
 */
package io.explico.render

import io.explico.model.PathSegment

/** [rendered] is the final backtick-wrapped breadcrumb; [hasAnyIndex] tells the caller whether to prefix the whole condition with "any of:" (spec §6.4 rule 5). */
internal data class HumanizedPath(val rendered: String, val hasAnyIndex: Boolean)

internal object PathHumanizer {

    fun humanize(segments: List<PathSegment>): HumanizedPath {
        var hasAnyIndex = false
        val crumbs = mutableListOf<String>()

        segments.forEachIndexed { index, segment ->
            when (segment) {
                is PathSegment.Field -> {
                    val isRoot = index == 0
                    when {
                        isRoot && segment.name == "input" -> Unit // rule 1: drop
                        isRoot && segment.name == "data" -> crumbs += "data" // rule 1: keep
                        else -> crumbs += wordsOf(segment.name).joinToString(" ") // rule 2
                    }
                }
                is PathSegment.KeyLiteral -> crumbs += "\"${segment.key}\"" // rule 3: verbatim, not split
                is PathSegment.AnyIndex -> hasAnyIndex = true // rule 5: dropped from breadcrumb, one flag regardless of count
                is PathSegment.VarIndex -> crumbs += "[each ${segment.name}]" // rules 6/7
            }
        }

        return HumanizedPath("`${crumbs.joinToString(" ▸ ")}`", hasAnyIndex)
    }

    /**
     * Splits on camelCase/snake_case/kebab-case boundaries and lowercases (spec §6.4 rule 2).
     * Exposed (not private) so AstMapper's producesValue placeholder formatting -- the one piece
     * of humanisation approved for mapping time, before this class existed -- can share it instead
     * of maintaining its own copy.
     */
    fun wordsOf(name: String): List<String> {
        val split = name
            .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2") // acronym run -> next word, e.g. URLPath -> URL Path
            .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2") // camelCase boundary, e.g. approvedBy -> approved By
            .replace(Regex("[_-]"), " ") // snake_case / kebab-case
        return split.split(" ").filter { it.isNotEmpty() }.map { it.lowercase() }
    }
}
