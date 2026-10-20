/**
 * Links from the documentation to the Rego source (spec §14.48): an auditor is one click from the exact line under review. Built from a
 * URL template with `{file}` (required: the policy file's path relative to the policy directory, each segment percent-encoded) and
 * `{line}` (optional: a 1-based line number). One helper for cards, package pages, the index and the JSON so they cannot disagree.
 */
package io.explico.render

internal class SourceLinks private constructor(private val template: String) {

    /** [template] with this [file] (relative, `/`-separated) and [line] substituted at every occurrence of the placeholders. */
    fun url(file: String, line: Int): String =
        template.replace(FILE, encodePath(file)).replace(LINE, line.toString())

    /** Percent-encodes every byte but the RFC 3986 unreserved set, per segment, keeping `/`: no file name can break out of the URL. */
    private fun encodePath(file: String): String = file.split('/').joinToString("/") { segment ->
        buildString {
            for (byte in segment.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt().toChar()
                if (byte >= 0 && (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-._~")) append(c) else append("%%%02X".format(byte.toInt() and 0xFF))
            }
        }
    }

    companion object {
        private const val FILE = "{file}"
        private const val LINE = "{line}"

        /** Null for no template; throws [IllegalArgumentException] naming `{file}` when the template lacks it. */
        fun of(template: String?): SourceLinks? {
            if (template == null) return null
            require(template.contains(FILE)) { "the source URL template must contain {file} (the policy file's path), e.g. https://host/org/repo/blob/COMMIT/policies/{file}#L{line}" }
            return SourceLinks(template)
        }
    }
}
