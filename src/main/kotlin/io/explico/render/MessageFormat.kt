/**
 * Spec §14.81: a `sprintf` message shown with its arguments as bracketed placeholders -- the same convention the *Produces* line already uses.
 */
package io.explico.render

import io.explico.model.Operand
import io.explico.model.PathSegment

internal object MessageFormat {

    /**
     * The number of verbs in [format] when every one is `%v`/`%s` (`%%` is a literal percent sign and takes no argument), else null: any other verb
     * (`%d`, `%q`, a trailing lone `%`) means the message is not understood, so it is never shown.
     */
    fun verbCount(format: String): Int? {
        var verbs = 0
        var i = 0
        while (i < format.length) {
            if (format[i] != '%') {
                i++
                continue
            }
            when (format.getOrNull(i + 1)) {
                'v', 's' -> verbs++
                '%' -> Unit
                else -> return null
            }
            i += 2
        }
        return verbs
    }

    /**
     * How one argument reads inside the message: `[deployment id]` for `input.deployment.id`, `[parameter label]` for a function parameter. Only a plain
     * field chain qualifies (`input` is dropped, as in the *Produces* line); an index, a key, a literal or anything computed has no established phrase and
     * is null.
     */
    fun placeholderOf(operand: Operand): String? {
        val words = when (operand) {
            is Operand.Path -> {
                val fields = operand.segments.map { fieldName(it) ?: return null }
                if (fields.firstOrNull() != "input") return null
                fields.drop(1).flatMap { PathHumanizer.wordsOf(it) }
            }
            is Operand.Parameter -> listOf("parameter") + PathHumanizer.wordsOf(operand.name)
            is Operand.ParameterPath -> {
                val fields = operand.segments.map { fieldName(it) ?: return null }
                listOf("parameter") + PathHumanizer.wordsOf(operand.name) + fields.flatMap { PathHumanizer.wordsOf(it) }
            }
            else -> return null
        }
        return if (words.isEmpty()) null else "[${words.joinToString(" ")}]"
    }

    /** A segment that is a real field NAME. An array index is mapped as a field too (`items[0]` -> `0`), and "[items 0]" would read as a field called 0: not a name, so no phrase. */
    private fun fieldName(segment: PathSegment): String? =
        (segment as? PathSegment.Field)?.name?.takeIf { identifier.matches(it) }

    private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** The message text: [format] with each verb replaced by its argument's placeholder. [verbCount] and the argument count are checked by the mapper. */
    fun text(format: String, args: List<Operand>): String {
        val result = StringBuilder()
        var index = 0
        var i = 0
        while (i < format.length) {
            val c = format[i]
            if (c != '%') {
                result.append(c)
                i++
                continue
            }
            when (format.getOrNull(i + 1)) {
                'v', 's' -> result.append(placeholderOf(args[index++]) ?: "?")
                else -> result.append('%')
            }
            i += 2
        }
        return result.toString()
    }
}
