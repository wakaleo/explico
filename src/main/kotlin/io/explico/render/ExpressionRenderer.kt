/**
 * Renders one [Condition] into its spec §6.3 phrase. Operates purely on the
 * already-built domain model -- the two-position rule (operand-position
 * builtins never appearing as a condition, and vice versa) is enforced
 * upstream by AstMapper's classification, not here: by construction, a
 * `Condition.BuiltinCall` this renderer receives always has a name from the
 * recognised condition-position set, and a misused condition-only builtin in
 * operand position already became `Operand.Unrendered` before reaching this
 * class. `count`/`lower`/`upper`/`time.now_ns`/arithmetic (`plus`/`minus`/
 * `mul`/`div`/`rem`, spec §14 promotion) are operand-position only and render
 * via [Operand.BuiltinCall]'s own template maps; `object.get`/`concat` render
 * via their own dedicated logic ([renderObjectGet]/[renderConcat]). Anything
 * else remains a documented gap and still falls back to `Operand.Unrendered`.
 *
 * `Condition.Comparison.negated` (spec §14 amendment: `not x == y` is real, valid Rego) picks
 * [NEGATED_COMPARISON_VERBS] instead of [COMPARISON_VERBS] -- a literal negation of the positive
 * wording ("is not greater than", not the different operator "is at most"), the same
 * undefined-propagation simplification already accepted for the positive form (spec §6.3's own
 * frozen template doesn't describe it either).
 *
 * `Condition.Unrendered` is deliberately NOT part of spec §6.3's phrasing table
 * -- it gets a structurally different, multi-line "shown as source" block
 * (spec §6.2), which is a card-assembly concern for a later renderer. Passing
 * one here is a caller bug, not a fallback case, so it throws rather than
 * silently mis-rendering.
 *
 * `Operand.RuleValueReference` (review item 10) can appear anywhere an operand can -- a bare
 * comparison side, nested inside a builtin call's arguments, a function-call argument -- so its
 * own anchor callback, [renderOperand]'s `ruleValueAnchorFor` parameter, is threaded through
 * every function in the operand-rendering chain, the same convention [anchorFor]/[functionAnchorFor]
 * already established for condition-level links.
 */
package io.explico.render

import io.explico.model.Condition
import io.explico.model.Operand
import io.explico.model.Operator
import io.explico.model.PathSegment

internal object ExpressionRenderer {

    /**
     * [anchorFor] resolves a RuleReference's link target -- anchor computation is spec §6.5, not
     * built yet; injected so phrasing can be tested independently of it. [functionAnchorFor]
     * (review item 8) does the same for a [Condition.FunctionCall]'s link to the called function's
     * own card. [ruleValueAnchorFor] (review item 10) does the same for an
     * [Operand.RuleValueReference]. All three default to throw rather than silently render a
     * broken link, since any call site that never produces the corresponding shape needn't supply
     * one.
     */
    fun render(
        condition: Condition,
        anchorFor: (Condition.RuleReference) -> String,
        functionAnchorFor: (Condition.FunctionCall) -> String = { throw IllegalStateException("functionAnchorFor not supplied for a Condition.FunctionCall") },
        ruleValueAnchorFor: (Operand.RuleValueReference) -> String = { throw IllegalStateException("ruleValueAnchorFor not supplied for an Operand.RuleValueReference") },
    ): String = when (condition) {
        is Condition.Comparison -> emptinessPhraseOrNull(condition, ruleValueAnchorFor) ?: renderOperandPair(condition.left, condition.right, ruleValueAnchorFor) { l, r ->
            val verbs = if (condition.negated) NEGATED_COMPARISON_VERBS else COMPARISON_VERBS
            "$l ${verbs.getValue(condition.op)} $r"
        }
        is Condition.Membership -> renderOperandPair(condition.member, condition.collection, ruleValueAnchorFor) { m, c ->
            "$m ${if (condition.negated) "is not one of" else "is one of"} $c"
        }
        is Condition.Truthy -> {
            // spec §14 amendment: a bare reference in condition position tests "defined and not
            // equal to boolean false" regardless of the value's actual type -- "is true" (the
            // original §6.3 wording) is only accurate when the field happens to be boolean, and
            // reads as flatly wrong for e.g. a non-empty string field. "is present and not false"
            // is the exact type-agnostic mirror of the already-correct negated phrasing below.
            val (text, hasAnyIndex) = renderOperand(condition.operand, ruleValueAnchorFor)
            withAnyOfPrefix(if (condition.negated) "$text is absent or false" else "$text is present and not false", hasAnyIndex)
        }
        is Condition.BuiltinCall -> renderBuiltinCall(condition, ruleValueAnchorFor)
        is Condition.SomeIn -> {
            val (text, hasAnyIndex) = renderOperand(condition.collection, ruleValueAnchorFor)
            val vars = if (condition.key != null) "${condition.key}, ${condition.variable}" else condition.variable
            withAnyOfPrefix("for some $vars in $text", hasAnyIndex)
        }
        is Condition.RuleReference -> renderRuleReference(condition, anchorFor)
        is Condition.FunctionCall -> renderFunctionCall(condition, functionAnchorFor, ruleValueAnchorFor)
        is Condition.Unrendered -> throw IllegalArgumentException(
            "Condition.Unrendered is not part of the §6.3 phrasing table; render it as a ⚠ source block instead."
        )
    }

    /**
     * `count(x) == 0` / `count(x) != 0` / `count(x) > 0` (review item 15) read "`x` is empty" /
     * "`x` is not empty". Exact for everything `count` accepts (string, array, object, set): the
     * condition can only hold when `x` is countable, and then "zero elements" IS "empty".
     * Deliberately narrow, never widened: only a non-negated comparison of a bare promoted `count`
     * against exactly the literal `0`, and only those three operators -- `>= 1`, `> 1`, `< 1`, a
     * negated form, or a `count` on the right all keep the ordinary "the number of" wording rather
     * than risk a phrase that doesn't mean exactly the same thing.
     */
    private fun emptinessPhraseOrNull(condition: Condition.Comparison, ruleValueAnchorFor: (Operand.RuleValueReference) -> String): String? {
        if (condition.negated) return null
        val count = condition.left as? Operand.BuiltinCall ?: return null
        if (count.name != "count" || count.args.size != 1) return null
        if ((condition.right as? Operand.Literal)?.rendered != "0") return null
        val phrase = when (condition.op) {
            Operator.EQ -> "is empty"
            Operator.NEQ, Operator.GT -> "is not empty"
            else -> return null
        }
        val (text, hasAnyIndex) = renderOperand(count.args.single(), ruleValueAnchorFor)
        return withAnyOfPrefix("$text $phrase", hasAnyIndex)
    }

    private fun renderRuleReference(condition: Condition.RuleReference, anchorFor: (Condition.RuleReference) -> String): String {
        val link = "[`${condition.ruleName}`](${anchorFor(condition)})"
        return if (condition.negated) "rule $link does not match" else "see rule $link"
    }

    /**
     * A call to a user-defined function (review item 8): a link to its own card (same convention
     * as [renderRuleReference]'s), followed by its already-humanized arguments -- "mechanical and
     * honest" rather than attempting to describe what the function actually checks (which would
     * mean inlining its body, out of scope here, same as a RuleReference never inlines either).
     * CHALLENGE edge case: a zero-argument function call drops the "for ..." clause entirely
     * rather than trailing off with nothing after "for".
     */
    private fun renderFunctionCall(
        condition: Condition.FunctionCall,
        anchorFor: (Condition.FunctionCall) -> String,
        ruleValueAnchorFor: (Operand.RuleValueReference) -> String,
    ): String {
        val link = "[`${condition.functionName}`](${anchorFor(condition)})"
        val verb = if (condition.negated) "is not satisfied" else "is satisfied"
        if (condition.args.isEmpty()) return "function $link $verb"
        val rendered = condition.args.map { renderOperand(it, ruleValueAnchorFor) }
        val argsText = rendered.joinToString(", ") { it.first }
        val hasAnyIndex = rendered.any { it.second }
        return withAnyOfPrefix("function $link $verb for $argsText", hasAnyIndex)
    }

    private fun renderBuiltinCall(condition: Condition.BuiltinCall, ruleValueAnchorFor: (Operand.RuleValueReference) -> String): String {
        // The seven `is_*` type checks (review item 11) take exactly ONE argument -- a genuinely
        // different phrase shape from every other condition-position builtin here (all two-arg,
        // "<subject> <verb> <other>"), so they get their own unary dispatch rather than being
        // forced into BuiltinTemplate's two-operand shape.
        UNARY_BUILTIN_TEMPLATES[condition.name]?.let { template ->
            val (text, hasAnyIndex) = renderOperand(condition.args.single(), ruleValueAnchorFor)
            val verb = if (condition.negated) template.negatedVerb else template.verb
            return withAnyOfPrefix("$text $verb", hasAnyIndex)
        }
        val template = BUILTIN_TEMPLATES[condition.name]
            ?: throw IllegalArgumentException("Unrecognised condition-position builtin '${condition.name}'; AstMapper should never produce this.")
        val (subject, other) = template.argOrder(condition.args)
        val (subjectText, subjectAny) = renderOperand(subject, ruleValueAnchorFor)
        val (otherText, otherAny) = renderOperand(other, ruleValueAnchorFor)
        val verb = if (condition.negated) template.negatedVerb else template.verb
        return withAnyOfPrefix("$subjectText $verb $otherText", subjectAny || otherAny)
    }

    private inline fun renderOperandPair(
        left: Operand,
        right: Operand,
        noinline ruleValueAnchorFor: (Operand.RuleValueReference) -> String,
        phrase: (String, String) -> String,
    ): String {
        val (leftText, leftAny) = renderOperand(left, ruleValueAnchorFor)
        val (rightText, rightAny) = renderOperand(right, ruleValueAnchorFor)
        return withAnyOfPrefix(phrase(leftText, rightText), leftAny || rightAny)
    }

    private fun renderOperand(operand: Operand, ruleValueAnchorFor: (Operand.RuleValueReference) -> String): Pair<String, Boolean> = when (operand) {
        is Operand.Path -> PathHumanizer.humanize(operand.segments).let { it.rendered to it.hasAnyIndex }
        is Operand.Literal -> "`${operand.rendered}`" to false
        is Operand.Variable -> "`${operand.name}`" to false
        // Review item 8: a declared function parameter, named explicitly as such -- distinguished
        // from a bare Variable's plain backtick-wrapped name specifically so a reader inside the
        // function's own card can tell "this is a known input the caller supplies" apart from
        // "this was assigned to something we couldn't resolve."
        is Operand.Parameter -> "parameter `${operand.name}`" to false
        is Operand.BuiltinCall -> renderOperandBuiltinCall(operand, ruleValueAnchorFor)
        // Review item 10: a link to the referenced rule's own card, the same "rule [`name`](anchor)"
        // convention regardless of whether the source spelled it bare, via an import alias, or as
        // a full data path -- all three resolve to the identical shape by the time AstMapper hands
        // it to this renderer.
        is Operand.RuleValueReference -> "rule [`${operand.ruleName}`](${ruleValueAnchorFor(operand)})" to false
        // Never guessed prose: the operand's own verbatim source, in backticks. Not part of spec's
        // explicit examples (only Condition-level Unrendered's block format is spec'd) -- this
        // session's interpretation for the inline case, kept as visible source rather than hidden.
        is Operand.Unrendered -> "`${operand.sourceText}`" to false
    }

    private fun renderOperandBuiltinCall(operand: Operand.BuiltinCall, ruleValueAnchorFor: (Operand.RuleValueReference) -> String): Pair<String, Boolean> {
        if (operand.name == "object.get") return renderObjectGet(operand, ruleValueAnchorFor)
        // time.now_ns() takes no arguments -- its template is a fixed phrase, not one built around
        // a rendered argument, so it's handled separately from the single-argument templates below.
        if (operand.name == "time.now_ns") return "the current time" to false
        if (operand.name == "concat") return renderConcat(operand, ruleValueAnchorFor)
        ARITHMETIC_TEMPLATES[operand.name]?.let { template ->
            val (leftText, leftAny) = renderOperand(operand.args[0], ruleValueAnchorFor)
            val (rightText, rightAny) = renderOperand(operand.args[1], ruleValueAnchorFor)
            return template(leftText, rightText) to (leftAny || rightAny)
        }
        val template = OPERAND_BUILTIN_TEMPLATES[operand.name]
            ?: throw IllegalArgumentException("Unrecognised operand-position builtin '${operand.name}'; AstMapper should never produce this.")
        val (argText, hasAnyIndex) = renderOperand(operand.args.single(), ruleValueAnchorFor)
        return template(argText) to hasAnyIndex
    }

    /**
     * `object.get(o, k, d)` (spec §6.3/§14): AstMapper only ever promotes this shape when `o` is a
     * real [Operand.Path] and `k` a plain string literal (its own KDoc explains why), so both casts
     * here are safe by construction. `k` extends `o`'s own breadcrumb as a [PathSegment.KeyLiteral]
     * -- the exact same segment a real bracket-string path (`labels["signed-off-by"]`) already
     * renders with -- rather than being shown as a second, separately backtick-wrapped operand.
     */
    private fun renderObjectGet(operand: Operand.BuiltinCall, ruleValueAnchorFor: (Operand.RuleValueReference) -> String): Pair<String, Boolean> {
        val (objectOperand, keyOperand, defaultOperand) = operand.args
        val key = (keyOperand as Operand.Literal).rendered.removeSurrounding("\"")
        val extendedPath = (objectOperand as Operand.Path).segments + PathSegment.KeyLiteral(key)
        val humanized = PathHumanizer.humanize(extendedPath)
        val (defaultText, defaultAnyIndex) = renderOperand(defaultOperand, ruleValueAnchorFor)
        return "${humanized.rendered} (default $defaultText)" to (humanized.hasAnyIndex || defaultAnyIndex)
    }

    /**
     * `concat(separator, collection)` (spec §6.3/§14): `operand.args[0]` is always the separator;
     * `args.drop(1)` is either N element operands (a literal array/set was exploded at mapping
     * time) or a single whole-collection operand (a path/var reference) -- [mapConcatOperand]'s own
     * KDoc explains why both shapes reach here identically. Either way the renderer just
     * comma-joins whatever already-rendered operand text it's given; a single element naturally
     * needs no comma.
     */
    private fun renderConcat(operand: Operand.BuiltinCall, ruleValueAnchorFor: (Operand.RuleValueReference) -> String): Pair<String, Boolean> {
        val (separatorText, separatorAny) = renderOperand(operand.args[0], ruleValueAnchorFor)
        val rendered = operand.args.drop(1).map { renderOperand(it, ruleValueAnchorFor) }
        val listText = rendered.joinToString(", ") { it.first }
        val hasAnyIndex = separatorAny || rendered.any { it.second }
        return "$listText joined with $separatorText" to hasAnyIndex
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

/** `not <comparison>` (spec §14 amendment) -- the literal negation of [COMPARISON_VERBS]'s wording, not a different operator's positive form (e.g. "is not greater than", never "is at most"). */
private val NEGATED_COMPARISON_VERBS = mapOf(
    Operator.EQ to "is not",
    Operator.NEQ to "is",
    Operator.GT to "is not greater than",
    Operator.GTE to "is not at least",
    Operator.LT to "is not less than",
    Operator.LTE to "is not at most",
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
    // net.cidr_contains(cidr, ip) (review item 11, confirmed arg order via a real `opa parse` run)
    // renders subject-first, same convention as regex.match/glob.match above: "<ip> is within <cidr>".
    "net.cidr_contains" to BuiltinTemplate("is within", "is not within") { it[1] to it[0] },
)

/** [verb]/[negatedVerb] alone -- a unary type check (review item 11) has no second operand to interpolate. */
private class UnaryBuiltinTemplate(val verb: String, val negatedVerb: String)

private val UNARY_BUILTIN_TEMPLATES = mapOf(
    "is_string" to UnaryBuiltinTemplate("is a string", "is not a string"),
    "is_number" to UnaryBuiltinTemplate("is a number", "is not a number"),
    "is_boolean" to UnaryBuiltinTemplate("is a boolean", "is not a boolean"),
    "is_array" to UnaryBuiltinTemplate("is an array", "is not an array"),
    "is_object" to UnaryBuiltinTemplate("is an object", "is not an object"),
    "is_null" to UnaryBuiltinTemplate("is null", "is not null"),
    "is_set" to UnaryBuiltinTemplate("is a set", "is not a set"),
)

/** Operand-position builtin templates (spec §6.3/§14 promotion) -- each takes the already-rendered single argument text and produces the final phrase. */
private val OPERAND_BUILTIN_TEMPLATES: Map<String, (String) -> String> = mapOf(
    "count" to { a -> "the number of $a" },
    "lower" to { a -> "$a lowercased" },
    "upper" to { a -> "$a uppercased" },
    // review item 11, all confirmed single-argument via a real `opa parse` run.
    "sum" to { a -> "the sum of $a" },
    "max" to { a -> "the largest of $a" },
    "min" to { a -> "the smallest of $a" },
    "to_number" to { a -> "$a as a number" },
    "trim_space" to { a -> "$a trimmed" },
    "object.keys" to { a -> "the keys of $a" },
    "time.parse_rfc3339_ns" to { a -> "the time $a" },
)

/**
 * Arithmetic infix operators (spec §14 promotion) -- each takes the already-rendered LEFT and
 * RIGHT argument text and interpolates them into a prose phrase, the same pattern
 * [OPERAND_BUILTIN_TEMPLATES] already uses for a single argument. This is what makes nesting
 * (e.g. `count(x) + 1`, or a chained `a + b + c` -- opa's own left-associative parse tree,
 * `plus(plus(a, b), c)`) render correctly with no extra logic: each level's already-rendered text
 * (which may itself be non-backtick-wrapped prose, e.g. "the number of `x`") is interpolated as
 * plain text, never re-wrapped or concatenated at the character level.
 */
private val ARITHMETIC_TEMPLATES: Map<String, (String, String) -> String> = mapOf(
    "plus" to { a, b -> "$a plus $b" },
    "minus" to { a, b -> "$a minus $b" },
    "mul" to { a, b -> "$a times $b" },
    "div" to { a, b -> "$a divided by $b" },
    "rem" to { a, b -> "$a modulo $b" },
)
