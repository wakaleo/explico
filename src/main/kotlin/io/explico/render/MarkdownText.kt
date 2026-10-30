/**
 * Spec §14.54: showing free text from a policy's evaluated value on one Markdown line.
 */
package io.explico.render

internal object MarkdownText {

    private val lineBreaks = Regex("\r\n|\r|\n")
    private val backtickRun = Regex("`+")
    private val emphasisSpecials = Regex("""([\\*`\[\]<])""")
    private val wordBoundaryUnderscore = Regex("""(?<![\p{L}\p{N}])_|_(?![\p{L}\p{N}])""")

    /**
     * [text] as an inline code span that cannot be broken by what is inside it: the fence is one backtick longer than the longest run inside, padded
     * with a space where the text starts or ends with a backtick, and a line break becomes the space CommonMark itself would render it as.
     */
    fun codeSpan(text: String): String {
        val oneLine = text.replace(lineBreaks, " ")
        val fence = "`".repeat((backtickRun.findAll(oneLine).maxOfOrNull { it.value.length } ?: 0) + 1)
        val pad = if (oneLine.startsWith("`") || oneLine.endsWith("`")) " " else ""
        return "$fence$pad$oneLine$pad$fence"
    }

    /**
     * Spec §14.62: [text] (a string literal from the policy, already decoded from its Rego escapes) with every control character written as an escape --
     * `\t`, `\n`, `\r`, any other as `\u00xx` -- so a literal such as `" \t\n\r"` reads as written and can never break the line it sits on. The ONE place
     * this is decided: every rendered literal goes through it. Nothing else changes: a backslash, a quote and non-ASCII letters are returned as they were.
     */
    fun controlCharacters(text: String): String {
        if (text.none { it.isISOControl() }) return text
        val out = StringBuilder(text.length + 8)
        for (c in text) {
            when {
                c == '\t' -> out.append("\\t")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c.isISOControl() -> out.append("\\u").append(String.format(java.util.Locale.ROOT, "%04x", c.code))
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /** [text] for use inside `*emphasis*` on one line: line breaks become spaces and anything that would change how it renders is escaped. */
    fun emphasisText(text: String): String =
        text.replace(lineBreaks, " ").replace(emphasisSpecials, "\\\\$1").replace(wordBoundaryUnderscore, "\\\\_")
}
