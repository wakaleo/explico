/**
 * Spec §14.76: the ONE definition of a label that is plain text. A label is written into a Markdown line (a bullet, an italic line, a link, a table cell), so it must not be
 * able to break out of it: not blank, no control character (a line break, a tab), no backtick, no leading or trailing space, at most [MAX_LENGTH] characters. A caller
 * whose label sits somewhere stricter (the companion link's `[label](url)`) adds characters through `extraForbidden`.
 */
package io.explico.render

internal object PlainLabel {

    const val MAX_LENGTH = 80

    /** Throws [IllegalArgumentException] naming [what] unless [value] is a plain-text label; [extraForbidden] are further characters this caller cannot allow. */
    fun validate(value: String, what: String, extraForbidden: String = "") {
        val forbidden = "`$extraForbidden"
        val valid = value.isNotBlank() && value == value.trim() && value.none { it.isISOControl() || it in forbidden } && value.codePointCount(0, value.length) <= MAX_LENGTH
        require(valid) {
            "$what must be plain text: not blank, at most $MAX_LENGTH characters, no leading or trailing space, and without a line break, a tab" +
                forbidden.map { ", $it" }.joinToString("") + " (got '${escaped(value)}')"
        }
    }

    private fun escaped(value: String): String = buildString {
        for (c in value) when {
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c.isISOControl() -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
}
