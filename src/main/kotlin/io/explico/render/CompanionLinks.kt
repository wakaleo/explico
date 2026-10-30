/**
 * Spec §14.58: the second link on a card -- to the module that OWNS a policy -- built from a URL template with `{file}` (the policy file's path, each
 * segment percent-encoded, as for [SourceLinks]), `{package}` (the Rego package path, percent-encoded as one string) and `{line}`. One helper for the card,
 * the package page and the JSON so they cannot disagree. Substitution is a SINGLE pass over the template, so text inside a substituted value is never
 * itself substituted.
 */
package io.explico.render

internal class CompanionLinks private constructor(private val template: String, val label: String) {

    /** [template] with this package, file (relative, `/`-separated) and [line] substituted at every occurrence of the placeholders. */
    fun url(packagePath: String, file: String, line: Int): String =
        PLACEHOLDER.replace(template) { match ->
            when (match.value) {
                FILE -> file.split('/').joinToString("/") { percentEncode(it) }
                PACKAGE -> percentEncode(packagePath)
                else -> line.toString()
            }
        }

    /** `[label](url)`: the label is validated plain text, so the link cannot be broken or injected into. */
    fun link(packagePath: String, file: String, line: Int): String = "[$label](${url(packagePath, file, line)})"

    companion object {
        private const val FILE = "{file}"
        private const val PACKAGE = "{package}"
        /** Each would break the link, the table or the italic line the link sits in. */
        private const val FORBIDDEN_IN_LABEL = "[]|`*\n\r"
        private val PLACEHOLDER = Regex("""\{(?:file|package|line)\}""")

        /**
         * Null for no template. Throws [IllegalArgumentException] when the template names neither `{file}` nor `{package}` (it could not point at anything of
         * the policy), or when the [label] is blank or holds a line break, `[`, `]` or `|` (it would break the Markdown link or a table).
         */
        fun of(template: String?, label: String): CompanionLinks? {
            if (template == null) return null
            require(template.contains(FILE) || template.contains(PACKAGE)) {
                "the companion URL template must contain {file} (the policy file's path) or {package} (the Rego package), e.g. https://host/modules/{package}/{file}#L{line}"
            }
            validateLabel(label)
            return CompanionLinks(template, label)
        }

        /** Throws [IllegalArgumentException] unless [label] is plain text that cannot break the link, a table or the italic line it sits in. */
        fun validateLabel(label: String) {
            require(label.isNotBlank() && label.none { it in FORBIDDEN_IN_LABEL }) {
                "the companion link label must be plain text: not blank, and without [, ], |, `, * or a line break (got '${label.replace("\n", "\\n").replace("\r", "\\r")}')"
            }
        }
    }
}
