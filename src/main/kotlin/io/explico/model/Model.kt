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
)

/** All rule definitions sharing one name in one package. Bodies are OR-ed. */
public data class RuleGroup(
    val name: String,
    val metadata: RuleMetadata?,
    val default: DefaultValue?,
    val bodies: List<RuleBody>,
)

/** METADATA annotations attached to a rule, at rule or document scope. */
public data class RuleMetadata(
    val title: String?,
    val description: String?,
    val controlId: String?,
    val frameworks: List<String>,
)

/** A rule's `default` declaration, e.g. `default allow := false`. */
public data class DefaultValue(val rendered: String)

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
)

/** A single leaf condition within a rule body. */
public sealed interface Condition {
    /** `left <op> right`, e.g. `input.deployment.environment == "production"`. */
    public data class Comparison(val left: Operand, val op: Operator, val right: Operand) : Condition

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

    /** Anything the mapper cannot classify. Rendered as marked, verbatim source — never guessed. */
    public data class Unrendered(val sourceText: String, val reason: String) : Condition
}

/** A comparison operator (spec §5). */
public enum class Operator { EQ, NEQ, GT, GTE, LT, LTE }

/** One side of a comparison, membership test, or builtin call. */
public sealed interface Operand {
    /** An `input`/`data` field-chain reference, e.g. `input.deployment.environment`. */
    public data class Path(val segments: List<PathSegment>) : Operand

    /** A scalar (or small array/set) literal, already rendered as Rego source, e.g. `"production"`. */
    public data class Literal(val rendered: String) : Operand

    /** A local variable bound to something other than a plain path (spec §5). */
    public data class Variable(val name: String) : Operand

    /** A recognised operand-position builtin call with a faithful template, e.g. `count(x)` (spec §6.3/§14). */
    public data class BuiltinCall(val name: String, val args: List<Operand>) : Operand

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
}

/** A location in a source file, for slicing verbatim fallback text and diff extraction. */
public data class SourceRef(val file: String, val row: Int)
