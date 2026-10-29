/**
 * Spec §14.55: deriving a rule's control id from its source file name, for a corpus that names its controls by file (`ctrl_1123.rego`)
 * instead of in METADATA. Opt-in, `render` only.
 */
package io.explico.render

import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RuleGroup
import io.explico.model.RuleMetadata
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * A validated pattern + template. [pattern] is a regex with a named group `id`, applied with `find` to the BASE name of the rule's source
 * file (a directory called `ctrl_99/` does not count); [template] writes the id (`CTRL-{id}`). An id is derived only when the match
 * succeeded, the `id` group took part and captured something, so a rule is never given an empty or invented id. METADATA's own `control-id`
 * always wins, and ONLY the id is derived -- never a title or any other field.
 */
internal class ControlIdFromFilename private constructor(private val pattern: Pattern, private val template: String) {

    /**
     * The id for a rule defined in [sourceFile] (a path with `/` or `\` separators), or null when none can be derived. An id containing a pipe
     * or a line break would break the index table or the card heading, so it is refused -- and [warn]ed about, never silently dropped.
     */
    fun idFor(sourceFile: String, warn: (String) -> Unit = {}): String? {
        val baseName = baseNameOf(sourceFile)
        val matcher = pattern.matcher(baseName)
        if (!matcher.find()) return null
        val captured = matcher.group("id")?.takeIf { it.isNotEmpty() } ?: return null
        val id = template.replace(PLACEHOLDER, captured) // String.replace is literal: `$1` and backslashes in either part stay as written
        if (id.any { it == '|' || it == '\n' || it == '\r' }) {
            warn("explico: warning: not deriving a control id from '$sourceFile': the id '${id.replace("\n", "\\n").replace("\r", "\\r")}' contains a '|' or a line break, which would break the Markdown tables")
            return null
        }
        return id
    }

    /**
     * [policySet] with a derived id on every rule that has none. The file is the one the card shows as "defined in"
     * ([MarkdownRenderer.ruleLocation], the single definition). A split-default entry is left alone: it has no body of its own, and an id would
     * replace its "Default for ..." heading. Warns once per file, however many rules it holds.
     */
    fun applyTo(policySet: PolicySet, warn: (String) -> Unit = { System.err.println(it) }): PolicySet {
        val idByFile = LinkedHashMap<String, String?>()
        fun derivedFor(file: String): String? {
            if (file !in idByFile) idByFile[file] = idFor(file, warn)
            return idByFile[file]
        }
        fun withId(rule: RuleGroup): RuleGroup {
            if (rule.isSplitDefaultEntry || rule.metadata?.controlId != null) return rule
            val file = MarkdownRenderer.ruleLocation(rule)?.file ?: return rule
            val id = derivedFor(file) ?: return rule
            val derivedFrom = baseNameOf(file)
            return rule.copy(
                metadata = rule.metadata?.copy(controlId = id, controlIdDerivedFrom = derivedFrom)
                    ?: RuleMetadata(null, null, id, emptyList(), controlIdDerivedFrom = derivedFrom),
            )
        }
        return policySet.copy(packages = policySet.packages.map { pkg: PolicyPackage -> pkg.copy(rules = pkg.rules.map { withId(it) }) })
    }

    companion object {
        const val DEFAULT_TEMPLATE = "{id}"

        /** The part of [sourceFile] after its last `/` or `\`: the only part a pattern is matched against. */
        private fun baseNameOf(sourceFile: String): String = sourceFile.substringAfterLast('/').substringAfterLast('\\')
        private const val PLACEHOLDER = "{id}"

        /** The validated options, or null when no [pattern] was given (nothing to derive). A [template] without a pattern is a mistake, not a no-op. */
        fun of(pattern: String?, template: String = DEFAULT_TEMPLATE): ControlIdFromFilename? {
            if (pattern == null) {
                require(template == DEFAULT_TEMPLATE) { "the control id template '$template' has no effect without a pattern: also give the pattern (the --control-id-from option, or controlIdFrom in the library)" }
                return null
            }
            return ControlIdFromFilename(compile(pattern), validateTemplate(template))
        }

        /** [pattern] if it is a regular expression with a named group `id`; otherwise an [IllegalArgumentException] saying how to fix it. */
        fun validatePattern(pattern: String): String {
            compile(pattern)
            return pattern
        }

        /** [template] if it contains `{id}` and nothing that would break a table; otherwise an [IllegalArgumentException]. */
        fun validateTemplate(template: String): String {
            require(template.contains(PLACEHOLDER)) { "the control id template must contain {id}, which stands for the captured id, e.g. CTRL-{id}: '$template'" }
            require(template.none { it == '|' || it == '\n' || it == '\r' }) { "the control id template must not contain '|' or a line break (it would break the Markdown tables): '$template'" }
            return template
        }

        private fun compile(pattern: String): Pattern {
            val compiled = try {
                Pattern.compile(pattern)
            } catch (e: PatternSyntaxException) {
                throw IllegalArgumentException("the control id pattern is not a valid regular expression (${e.description}): $pattern")
            }
            // A back-reference to \k<id> compiles only if the pattern defines that group (Pattern.namedGroups() is Java 20+, past our Java 17 target). The
            // newline ends a `#` comment of (?x) mode, so the suffix can never be swallowed by the pattern's own text; this copy is never matched against anything.
            try {
                Pattern.compile("(?:$pattern\n)\\k<id>")
            } catch (e: PatternSyntaxException) {
                throw IllegalArgumentException("the control id pattern needs a named group `id` around the part to use, written (?<id>...), e.g. ctrl_(?<id>\\d+): $pattern")
            }
            return compiled
        }
    }
}
