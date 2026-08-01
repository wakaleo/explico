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
    val sourceLocation: SourceRef,
)

/** A single leaf condition within a rule body. */
public sealed interface Condition {
    public data class Comparison(val left: Operand, val op: Operator, val right: Operand) : Condition
    public data class Membership(val negated: Boolean, val member: Operand, val collection: Operand) : Condition
    public data class Truthy(val operand: Operand, val negated: Boolean) : Condition
    public data class BuiltinCall(val name: String, val args: List<Operand>, val negated: Boolean) : Condition
    public data class SomeIn(val variable: String, val collection: Operand) : Condition
    public data class RuleReference(val packagePath: String, val ruleName: String, val negated: Boolean) : Condition

    /** Anything the mapper cannot classify. Rendered as marked, verbatim source — never guessed. */
    public data class Unrendered(val sourceText: String, val reason: String) : Condition
}

public enum class Operator { EQ, NEQ, GT, GTE, LT, LTE }

/** One side of a comparison, membership test, or builtin call. */
public sealed interface Operand {
    public data class Path(val segments: List<PathSegment>) : Operand
    public data class Literal(val rendered: String) : Operand
    public data class Variable(val name: String) : Operand
    public data class Unrendered(val sourceText: String) : Operand
}

/** One segment of an `input`/`data` path reference. */
public sealed interface PathSegment {
    public data class Field(val name: String) : PathSegment
    public object AnyIndex : PathSegment
    public data class VarIndex(val name: String) : PathSegment
    public data class KeyLiteral(val key: String) : PathSegment
}

/** A location in a source file, for slicing verbatim fallback text and diff extraction. */
public data class SourceRef(val file: String, val row: Int)
