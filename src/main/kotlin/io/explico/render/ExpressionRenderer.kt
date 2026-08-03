/**
 * Renders one [Condition] into its spec §6.3 phrase. Operates purely on the
 * already-built domain model -- the two-position rule (operand-position
 * builtins like `count`/`lower`/`upper`/`object.get`/`time.now_ns` never
 * appearing as a condition, and vice versa) is enforced upstream by AstMapper's
 * classification, not here: by construction, a `Condition.BuiltinCall` this
 * renderer receives always has a name from the recognised condition-position
 * set, and a misused condition-only builtin in operand position already became
 * `Operand.Unrendered` before reaching this class.
 *
 * `Condition.Unrendered` is deliberately NOT part of spec §6.3's phrasing table
 * -- it gets a structurally different, multi-line "shown as source" block
 * (spec §6.2), which is a card-assembly concern for a later renderer. Passing
 * one here is a caller bug, not a fallback case, so it throws rather than
 * silently mis-rendering.
 */
package io.explico.render

import io.explico.model.Condition
import io.explico.model.Operand
import io.explico.model.Operator

internal object ExpressionRenderer {

    /** [anchorFor] resolves a RuleReference's link target -- anchor computation is spec §6.5, not built yet; injected so phrasing can be tested independently of it. */
    fun render(condition: Condition, anchorFor: (Condition.RuleReference) -> String): String = when (condition) {
        is Condition.Comparison -> renderOperandPair(condition.left, condition.right) { l, r -> "$l ${COMPARISON_VERBS.getValue(condition.op)} $r" }
        is Condition.Membership -> renderOperandPair(condition.member, condition.collection) { m, c ->
            "$m ${if (condition.negated) "is not one of" else "is one of"} $c"
        }
        is Condition.Truthy -> {
            // spec §14 amendment: a bare reference in condition position tests "defined and not
            // equal to boolean false" regardless of the value's actual type -- "is true" (the
            // original §6.3 wording) is only accurate when the field happens to be boolean, and
            // reads as flatly wrong for e.g. a non-empty string field. "is present and not false"
            // is the exact type-agnostic mirror of the already-correct negated phrasing below.
            val (text, hasAnyIndex) = renderOperand(condition.operand)
            withAnyOfPrefix(if (condition.negated) "$text is absent or false" else "$text is present and not false", hasAnyIndex)
        }
        is Condition.BuiltinCall -> renderBuiltinCall(condition)
        is Condition.SomeIn -> {
            val (text, hasAnyIndex) = renderOperand(condition.collection)
            withAnyOfPrefix("for some ${condition.variable} in $text", hasAnyIndex)
        }
        is Condition.RuleReference -> renderRuleReference(condition, anchorFor)
        is Condition.Unrendered -> throw IllegalArgumentException(
            "Condition.Unrendered is not part of the §6.3 phrasing table; render it as a ⚠ source block instead."
        )
    }

    private fun renderRuleReference(condition: Condition.RuleReference, anchorFor: (Condition.RuleReference) -> String): String {
        val link = "[`${condition.ruleName}`](${anchorFor(condition)})"
        return if (condition.negated) "rule $link does not match" else "see rule $link"
    }

    private fun renderBuiltinCall(condition: Condition.BuiltinCall): String {
        val template = BUILTIN_TEMPLATES[condition.name]
            ?: throw IllegalArgumentException("Unrecognised condition-position builtin '${condition.name}'; AstMapper should never produce this.")
        val (subject, other) = template.argOrder(condition.args)
        val (subjectText, subjectAny) = renderOperand(subject)
        val (otherText, otherAny) = renderOperand(other)
        val verb = if (condition.negated) template.negatedVerb else template.verb
        return withAnyOfPrefix("$subjectText $verb $otherText", subjectAny || otherAny)
    }

    private inline fun renderOperandPair(left: Operand, right: Operand, phrase: (String, String) -> String): String {
        val (leftText, leftAny) = renderOperand(left)
        val (rightText, rightAny) = renderOperand(right)
        return withAnyOfPrefix(phrase(leftText, rightText), leftAny || rightAny)
    }

    private fun renderOperand(operand: Operand): Pair<String, Boolean> = when (operand) {
        is Operand.Path -> PathHumanizer.humanize(operand.segments).let { it.rendered to it.hasAnyIndex }
        is Operand.Literal -> "`${operand.rendered}`" to false
        is Operand.Variable -> "`${operand.name}`" to false
        // Never guessed prose: the operand's own verbatim source, in backticks. Not part of spec's
        // explicit examples (only Condition-level Unrendered's block format is spec'd) -- this
        // session's interpretation for the inline case, kept as visible source rather than hidden.
        is Operand.Unrendered -> "`${operand.sourceText}`" to false
    }

    private fun withAnyOfPrefix(phrase: String, hasAnyIndex: Boolean): String = if (hasAnyIndex) "any of: $phrase" else phrase
}

private val COMPARISON_VERBS = mapOf(
    Operator.EQ to "is",
    Operator.NEQ to "is not",
    Operator.GT to "is greater than",
    Operator.GTE to "is at least",
    Operator.LT to "is less than",
    Operator.LTE to "is at most",
)

/** [argOrder] picks (subject, other) from the raw args list -- e.g. regex.match(pattern, value) renders subject-first: "<value> matches pattern <pattern>". */
private class BuiltinTemplate(val verb: String, val negatedVerb: String, val argOrder: (List<Operand>) -> Pair<Operand, Operand>)

private val BUILTIN_TEMPLATES = mapOf(
    "startswith" to BuiltinTemplate("starts with", "does not start with") { it[0] to it[1] },
    "endswith" to BuiltinTemplate("ends with", "does not end with") { it[0] to it[1] },
    "contains" to BuiltinTemplate("contains", "does not contain") { it[0] to it[1] },
    // pattern is always first, value always last per spec's signatures -- regex.match(p, v),
    // glob.match(p, _, v) -- so .first()/.last() is robust regardless of whether AstMapper
    // includes glob.match's ignored middle delimiter argument. Neither builtin is exercised by
    // the acceptance pack; this is untested against real captured JSON (see CLAUDE.md).
    "regex.match" to BuiltinTemplate("matches pattern", "does not match pattern") { it.last() to it.first() },
    "glob.match" to BuiltinTemplate("matches glob", "does not match glob") { it.last() to it.first() },
)
