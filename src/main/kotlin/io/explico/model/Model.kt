/**
 * Domain model for a parsed policy set. Immutable data classes and sealed
 * interfaces only, per spec §5. This model, together with the render/diff
 * entry points in [io.explico.Explico], is the library's public API surface.
 */
package io.explico.model

/** A parsed set of Rego packages, e.g. everything under one policy directory. */
public data class PolicySet(val packages: List<PolicyPackage>)

/** One Rego package: its path, the rules it defines, and the files it came from. */
public data class PolicyPackage(
    val path: String,
    val rules: List<RuleGroup>,
    val sourceFiles: List<String>,
    /** Package-scope METADATA (review item 4), e.g. a `# METADATA\n# scope: package` block above the `package` declaration. Null when none is present. */
    val metadata: PackageMetadata? = null,
)

/** METADATA annotations attached at package scope -- title/description only, since `custom`/control-id/frameworks are rule-level concepts (spec review item 4). */
public data class PackageMetadata(val title: String?, val description: String?)

/** All rule definitions sharing one name in one package. Bodies are OR-ed. */
public data class RuleGroup(
    val name: String,
    val metadata: RuleMetadata?,
    val default: DefaultValue?,
    val bodies: List<RuleBody>,
    /** Declared parameter names, in order, for a user-defined function (review item 8); empty for an ordinary rule. */
    val parameters: List<String> = emptyList(),
    /**
     * True when this [RuleGroup] exists ONLY to carry a `default` declaration that would
     * otherwise be repeated across several sibling cards sharing the same Rego-level [name]
     * (review item 14's follow-up) -- e.g. three separately-annotated `allow if {...}` bodies
     * plus one `default allow := false`. Rendered with its own "Default for `<pkg>.<name>`"
     * heading rather than the ordinary id-less `<pkg>.<name>` one, and excluded from `default` on
     * every sibling card -- repeating it on each, and diffing a changed default as a
     * `LOGIC_CHANGED` entry per sibling with an EMPTY source diff (the default isn't part of any
     * sibling's own body source), was both noisy and misleading.
     *
     * A rule name with only ONE card keeps its default attached to that card (`isSplitDefaultEntry`
     * stays false there) -- nothing split, so no separate entry. Same for a name that's only ever
     * a `default` with no sibling body at all: that case predates this field and is unaffected.
     */
    val isSplitDefaultEntry: Boolean = false,
    /**
     * A complete rule's unconditional constant value, e.g. `max_items := 10` (review item 10,
     * finding 4) -- opa represents a bodyless `name := value` as a single synthetic `true` body
     * expr, which otherwise rendered as a bogus "⚠ not rendered" card. Distinct from
     * [DefaultValue]: a default is the fallback when nothing else matches a DIFFERENT rule; this
     * is the rule's own, only, unconditional value. Null for every other rule shape -- in
     * particular, a rule with a real body (even one that always evaluates true, like `if true`
     * written explicitly) keeps the ordinary `bodies`/Situations rendering, since opa's own
     * location-text for the synthetic case is the one mechanical, non-guessing signal available
     * (see `AstMapper.constantValueOrNull`'s own KDoc) and this is the only shape it fires for.
     * `bodies` is empty whenever this is non-null.
     */
    val constantValue: ConstantValue? = null,
)

/**
 * A complete rule's unconditional constant value (review item 10) -- see [RuleGroup.constantValue].
 * [sourceLocation] is the rule's own row, used for the card's "defined in" line since `bodies` is
 * empty for this shape (the same need [DefaultValue.sourceLocation] was added for).
 */
public data class ConstantValue(val rendered: String, val sourceLocation: SourceRef)

/** METADATA annotations attached to a rule, at rule or document scope. */
public data class RuleMetadata(
    val title: String?,
    val description: String?,
    val controlId: String?,
    val frameworks: List<String>,
)

/**
 * A rule's `default` declaration, e.g. `default allow := false`. [sourceLocation] is the
 * `default` statement's own row -- needed once a split-rule's default became a real card of its
 * own (review item 14's follow-up) rather than only ever riding along on a sibling card that
 * already had a body to source its own "defined in" line from.
 */
public data class DefaultValue(val rendered: String, val sourceLocation: SourceRef)

/** One rule body: conditions are AND-ed. */
public data class RuleBody(
    val conditions: List<Condition>,
    val producesValue: String?,
    /**
     * The raw message source used for worked-example body attribution (spec §6.7): a string
     * literal verbatim, or an sprintf format string with `%v`/`%s` placeholders left untouched
     * (not humanised). Distinct from [producesValue] -- that one is display text and is null
     * whenever a placeholder can't be confidently humanised (e.g. a var-rooted path), but
     * attribution only needs the template's literal/wildcard shape, not a display rendering of
     * its arguments, so this stays populated even when [producesValue] is null.
     */
    val messageTemplate: String?,
    val sourceLocation: SourceRef,
    /**
     * The whole body's verbatim source (spec §7.3): base64-decoded from the rule's own AST
     * `location.text`, the same mechanism as a [Condition.Unrendered]'s fallback text -- never a
     * second file read sliced by [sourceLocation]'s row. Used only by the diff report's
     * LOGIC_CHANGED unified text diff.
     */
    val sourceText: String,
    /**
     * The value THIS body yields when it matches, for a CONDITIONAL complete rule (follow-up to
     * review item 10), e.g. `"high"` for `tier := "high" if input.risk > 80`. Reuses the ordinary
     * [Operand] model and its rendering (humanized paths, links, builtin templates, fallback) --
     * the value is mechanically the same thing an operand already is, just read from the rule's
     * own `head.value` rather than from a condition. Null for a partial rule (`head.key != null`,
     * no single scalar value to show) and for a plain boolean rule with no EXPLICIT value written
     * (`flag if input.enabled` -- opa synthesizes an implicit `true` with no source location,
     * confirmed via a real `opa parse` run, the mechanical signal that distinguishes it from an
     * explicit `:= true`). Distinct from [RuleGroup.constantValue] (session 10/14.20): that one is
     * for a rule with NO body at all; this is for a rule whose body has real conditions.
     */
    val yieldsValue: Operand? = null,
)

/** A single leaf condition within a rule body. */
public sealed interface Condition {
    /**
     * `left <op> right`, e.g. `input.deployment.environment == "production"`, or its negation
     * (`not input.a == input.b`, spec §14 amendment) when [negated] is true. Rego allows `not` to
     * wrap any comparison operator, not just a bare reference or membership test.
     */
    public data class Comparison(val left: Operand, val op: Operator, val right: Operand, val negated: Boolean = false) : Condition

    /** `member in collection` (or its negation `not ... in ...`), e.g. a value-in-set test. */
    public data class Membership(val negated: Boolean, val member: Operand, val collection: Operand) : Condition

    /** A bare reference used as a boolean condition, e.g. `not input.change.ticket.approved`. */
    public data class Truthy(val operand: Operand, val negated: Boolean) : Condition

    /** A recognised builtin predicate call, e.g. `startswith(x, "release/")`. */
    public data class BuiltinCall(val name: String, val args: List<Operand>, val negated: Boolean) : Condition

    /**
     * `some <variable> in <collection>`, introducing a loop variable later path segments can
     * reference. The two-variable form (`some <key>, <variable> in <collection>`, spec §14
     * promotion) additionally binds [key] -- both names resolve later var-rooted paths against
     * the same [collection], since Rego makes no distinction in how a key vs. value variable can
     * be used once bound. `key` is null for the single-variable form.
     */
    public data class SomeIn(val variable: String, val collection: Operand, val key: String? = null) : Condition

    /** A reference to another rule defined in the policy set (same or an imported package). */
    public data class RuleReference(val packagePath: String, val ruleName: String, val negated: Boolean) : Condition

    /**
     * A call to a user-defined function (review item 8; same package, an import alias or a full `data.` path -- [packagePath] is the DEFINING package), e.g.
     * `not is_exempt(input.request.namespace)`. [args] are the call's own arguments, each resolved
     * through the same operand mapping every other position uses.
     */
    public data class FunctionCall(val packagePath: String, val functionName: String, val args: List<Operand>, val negated: Boolean) : Condition

    /** Anything the mapper cannot classify. Rendered as marked, verbatim source — never guessed. */
    public data class Unrendered(val sourceText: String, val reason: String) : Condition
}

/** A comparison operator (spec §5). */
public enum class Operator { EQ, NEQ, GT, GTE, LT, LTE }

/** Which Rego collection syntax a promoted literal collection used (spec §14 promotion); see [Operand.Literal]. */
public enum class LiteralCollection { ARRAY, SET }

/** One side of a comparison, membership test, or builtin call. */
public sealed interface Operand {
    /** An `input`/`data` field-chain reference, e.g. `input.deployment.environment`. */
    public data class Path(val segments: List<PathSegment>) : Operand

    /**
     * A scalar (or small array/set) literal, already rendered as Rego source, e.g. `"production"`.
     * [collection] records which Rego collection syntax a promoted array/set literal used (spec
     * §14 promotion) -- null for a scalar or an object literal. [rendered] itself is always the
     * bare, comma-joined element list regardless of [collection]; the renderer adds delimiters
     * (`[...]`/`{...}`) outside the one position where a bare list is the established, frozen
     * phrasing (the collection side of a membership test, `` `x` is one of `"a", "b"` ``) -- an
     * array and a set of the SAME elements are different Rego values, so this also distinguishes
     * them for hashing (`Canonicalizer`), which a bare [rendered] string alone could not.
     */
    public data class Literal(val rendered: String, val collection: LiteralCollection? = null) : Operand

    /** A local variable bound to something other than a plain path (spec §5). */
    public data class Variable(val name: String) : Operand

    /** A declared function parameter used bare, e.g. `ns` inside `is_exempt(ns) if { ns in {...} }` (review item 8) -- distinguished from [Variable] so it renders as a known, named input rather than an assigned-but-opaque value. */
    public data class Parameter(val name: String) : Operand

    /** A recognised operand-position builtin call with a faithful template, e.g. `count(x)` (spec §6.3/§14). */
    public data class BuiltinCall(val name: String, val args: List<Operand>) : Operand

    /**
     * A reference to another COMPLETE rule's VALUE, used as an operand (review item 10) -- e.g.
     * `threshold` (same package), `config.max_items` (import alias), or
     * `data.rulevalues.config.region_prefix` (full data path): all three resolve to this same
     * shape, rendered as a link to that rule's own card, the same convention
     * [Condition.RuleReference] already uses. A PARTIAL (`contains`/object) rule is deliberately
     * excluded -- it has no single scalar value to point a reader at -- and stays
     * [Unrendered] instead, same as before this promotion.
     */
    public data class RuleValueReference(val packagePath: String, val ruleName: String) : Operand

    /**
     * The result of a same-package call to a user-defined function, used as an operand (coverage
     * audit rank 4), e.g. `severity_of(input.finding.level) >= 2`. The operand-position counterpart
     * of [Condition.FunctionCall]: rendered as a link to the function's own card plus its humanized
     * arguments, never inlining the function's logic. A call that names no loaded function of that arity, or
     * has an unmappable argument, is not promoted and stays [Unrendered].
     */
    public data class FunctionCall(val packagePath: String, val functionName: String, val args: List<Operand>) : Operand

    /**
     * A field chain rooted at a function PARAMETER, e.g. `finding.severity` inside
     * `risk_score(finding) := 10 if { finding.severity == "critical" }` (coverage audit probe 28's
     * remaining half, spec §14.26). Distinct from [Parameter] (a bare parameter use): the
     * [segments] after the parameter root map cleanly through the same [PathSegment] machinery any
     * other reference uses. A tail the mapper cannot resolve (e.g. an unbound dynamic index) is not
     * promoted and stays [Unrendered] whole, same conservative posture as every other promotion.
     */
    public data class ParameterPath(val name: String, val segments: List<PathSegment>) : Operand

    /** An operand the mapper cannot classify, e.g. an unrecognised operand-position builtin call. */
    public data class Unrendered(val sourceText: String) : Operand
}

/** One segment of an `input`/`data` path reference. */
public sealed interface PathSegment {
    /** A plain field/attribute name, e.g. `environment` in `input.deployment.environment`. */
    public data class Field(val name: String) : PathSegment

    /** A wildcard index, `[_]`. */
    public object AnyIndex : PathSegment

    /** A bracket or dot index bound by an enclosing `some ... in ...`, e.g. `stage` in `stage.status`. */
    public data class VarIndex(val name: String) : PathSegment

    /** A literal bracket key, e.g. `"signed-off-by"` in `labels["signed-off-by"]`. */
    public data class KeyLiteral(val key: String) : PathSegment

    /**
     * A dynamic bracket key that is itself a resolved `input`/`data` path (review item 7), e.g.
     * `input.user.id` in `data.grants[input.user.id]`. Rendered as its own bracketed
     * sub-breadcrumb (`[user ▸ id]`) rather than the raw dotted source text.
     */
    public data class KeyPath(val segments: List<PathSegment>) : PathSegment
}

/** A location in a source file, for slicing verbatim fallback text and diff extraction. */
public data class SourceRef(val file: String, val row: Int)
