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

    /** [text] for use inside `*emphasis*` on one line: line breaks become spaces and anything that would change how it renders is escaped. */
    fun emphasisText(text: String): String =
        text.replace(lineBreaks, " ").replace(emphasisSpecials, "\\\\$1").replace(wordBoundaryUnderscore, "\\\\_")
}
