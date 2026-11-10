/**
 * Spec §14.81: how a rule's (or `else` branch's) yielded value reads on a card, including an object-literal function inlined by the mapper.
 */
package io.explico.render

import io.explico.model.DeclaredProjection
import io.explico.model.ObjectField
import io.explico.model.Operand

internal object ThenLines {

    /** What the verdict-view of one inlined object shows; every part is read from the literal and the call's arguments, never evaluated. */
    internal data class StaticVerdict(val outcome: Boolean, val text: String, val reason: Operand?, val remediation: List<Operand>?)

    /**
     * The declared verdict ([DeclaredProjection.verdict]) read STATICALLY from [object]: its outcome field must be a literal `true`/`false` (a
     * parameter bound to one counts, the mapper already put the argument in place), a declared reason a field holding an operand, and a
     * declared `actions.at` a list built from an argument whose elements are known. Null -- the caller then lists the fields -- when no verdict is
     * declared or any declared part is not exactly that: never a half-projection. Field paths are top-level names only (a dot means nesting, which
     * is not modelled).
     */
    fun staticVerdict(inlined: Operand.InlinedObject, projection: DeclaredProjection?): StaticVerdict? {
        val verdict = projection?.verdict ?: return null
        if ('.' in verdict.outcome) return null
        val outcomeField = inlined.fields.firstOrNull { it.key == verdict.outcome } ?: return null
        val outcome = when ((outcomeField.value as? Operand.Literal)?.rendered) {
            "true" -> true
            "false" -> false
            else -> return null
        }
        val reason = verdict.reason?.let { path ->
            if ('.' in path) return null
            inlined.fields.firstOrNull { it.key == path }?.value?.takeIf { it !is Operand.InlinedObject } ?: return null
        }
        val remediation = projection.actions?.at?.takeIf { '.' !in it }?.let { at ->
            inlined.fields.firstOrNull { it.key == at && it.wrapKey != null }?.items
        }
        return StaticVerdict(outcome, if (outcome) verdict.outcome else "not ${verdict.outcome}", reason, remediation)
    }

    /**
     * The lines for a yielded [value] after [prefix] (`*Then:*`, `**Value:**`, ...): ONE line for everything but an inlined object. For one:
     * the verdict view (glyph + declared outcome, then `Reason`/remediation sub-bullets) when [projection] declares a verdict that reads
     * statically, else the object's fields as sub-bullets. Sub-bullets are list items on purpose: indented plain lines would run together into
     * one paragraph.
     */
    fun lines(prefix: String, value: Operand, projection: DeclaredProjection?, ruleValueAnchorFor: (Operand.RuleValueReference) -> String?): List<String> {
        if (value !is Operand.InlinedObject) return listOf("$prefix ${ExpressionRenderer.renderValue(value, ruleValueAnchorFor)}")
        fun render(operand: Operand) = ExpressionRenderer.renderValue(operand, ruleValueAnchorFor)
        val verdict = staticVerdict(value, projection)
        if (verdict != null) {
            val out = mutableListOf("$prefix ${if (verdict.outcome) "✅" else "❌"} ${verdict.text}")
            verdict.reason?.let { out += "- *Reason:* ${render(it)}" }
            verdict.remediation?.let { items ->
                out += if (items.isEmpty()) "- *No remediation offered.*" else "- *Remediation offered:* ${items.joinToString(", ") { render(it) }}"
            }
            return out
        }
        val function = ruleValueAnchorFor(Operand.RuleValueReference(value.packagePath, value.functionName))
            ?.let { "[`${value.functionName}`]($it)" } ?: "`${value.functionName}`"
        return listOf("$prefix an object, as returned by function $function, with:") + value.fields.map { "- ${fieldPhrase(it, ::render)}" }
    }

    /** One field of an inlined object, e.g. `` `compliant`: `false` ``. */
    fun fieldPhrase(field: ObjectField, render: (Operand) -> String): String {
        val name = "`${field.key}`"
        val wrap = field.wrapKey
        if (wrap == null) return "$name: ${render(field.value!!)}"
        val items = field.items
        return when {
            items == null -> "$name: a list of objects, one for each element of ${render(field.value!!)}, holding it as `$wrap`"
            items.isEmpty() -> "$name: an empty list"
            else -> "$name: a list of objects, one for each of ${items.joinToString(", ") { render(it) }}, holding it as `$wrap`"
        }
    }
}
