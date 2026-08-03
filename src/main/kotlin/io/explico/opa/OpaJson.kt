/**
 * `@Serializable` DTOs for the `opa parse` / `opa inspect` JSON output (spec §4).
 * Only the fields explico consumes are modeled; everything else is dropped via
 * [ignoreUnknownKeys] so OPA upgrades don't break deserialization.
 *
 * `opa parse --format json` only emits source locations when passed
 * `--json-include locations` (undocumented in the original spec text, confirmed
 * empirically — the flag is required, or every location is simply absent).
 *
 * A Rego expression's `terms` field is not one shape: it's a single [OpaTerm]
 * for a bare/negated reference, a `List<OpaTerm>` for a call-shaped expression
 * (the first element is the operator), an object with `symbols` for `some ... in`,
 * or an object with `domain`/`body`/`key`/`value` for `every`. Modeling it as raw
 * [JsonElement] lets the mapper (a later milestone) discriminate on shape rather
 * than forcing a premature, possibly-wrong sealed hierarchy here.
 */
package io.explico.opa

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal val opaJson = Json { ignoreUnknownKeys = true }

@Serializable
internal data class OpaLocation(
    val file: String? = null,
    val row: Int = 0,
    val col: Int = 0,
    /** Base64-encoded verbatim source text of this node, per opa's own AST. */
    val text: String? = null,
)

/** One Rego term. `value`'s shape depends on `type` (string/number/boolean scalar, or a `List<OpaTerm>` for ref/array/call/set). */
@Serializable
internal data class OpaTerm(
    val type: String,
    val value: JsonElement? = null,
    val location: OpaLocation? = null,
)

/** One expression within a rule body. */
@Serializable
internal data class OpaExpr(
    val index: Int = 0,
    val negated: Boolean = false,
    val terms: JsonElement? = null,
    val location: OpaLocation? = null,
    /**
     * Presence-only (spec §14): a `with input.x as ...` / `with data.x as ...` override attaches
     * as a sibling `with` array on the expr, structurally unrelated to `terms` -- before this field
     * existed, `ignoreUnknownKeys` silently dropped it, so the mapper never knew an override was in
     * play and rendered the expression as if it evaluated against the real input. Only used to
     * detect non-null and demote to `Condition.Unrendered`; the override's own target/value is never
     * decoded, since faithfully rendering "evaluated against a modified input" is out of scope.
     */
    val with: JsonElement? = null,
)

@Serializable
internal data class OpaCustomAnnotation(
    @SerialName("control-id") val controlId: String? = null,
    val frameworks: List<String> = emptyList(),
)

/** A METADATA annotation body. Used both for `opa parse`'s package/rule annotations and nested inside [OpaInspectAnnotationEntry]. */
@Serializable
internal data class OpaAnnotationBody(
    val scope: String? = null,
    val title: String? = null,
    val description: String? = null,
    val custom: OpaCustomAnnotation? = null,
    val location: OpaLocation? = null,
)

@Serializable
internal data class OpaRuleHead(
    val name: String? = null,
    /** The head key, for partial set/object rules, e.g. `deny contains msg`. */
    val key: OpaTerm? = null,
    /** The head value, for complete rules and `default` declarations. */
    val value: OpaTerm? = null,
    val assign: Boolean = false,
    val ref: List<OpaTerm> = emptyList(),
    val location: OpaLocation? = null,
)

@Serializable
internal data class OpaRule(
    val head: OpaRuleHead,
    val body: List<OpaExpr> = emptyList(),
    val annotations: List<OpaAnnotationBody> = emptyList(),
    val default: Boolean = false,
    val location: OpaLocation? = null,
    /**
     * Presence-only (spec §14): an `else := ... if {...}` branch is a nested, rule-shaped sibling
     * structure under the `else` key, entirely separate from `body` -- before this field existed,
     * `ignoreUnknownKeys` silently dropped it, and the else-branch's logic simply vanished from the
     * rendered card with no fallback marker at all, not even "shown as source". Only used to detect
     * non-null and demote the whole rule body to `Condition.Unrendered`; the branch's own nested
     * body/head is never decoded, since correctly modeling "which branch's value applies" (a
     * priority-ordered alternative, not a simple OR of situations) is out of scope.
     */
    @SerialName("else") val elseBranch: JsonElement? = null,
)

@Serializable
internal data class OpaPackage(
    val path: List<OpaTerm> = emptyList(),
    val location: OpaLocation? = null,
)

@Serializable
internal data class OpaImport(
    val path: OpaTerm,
    val location: OpaLocation? = null,
)

/** Top-level result of `opa parse --format json --json-include locations <file>`. */
@Serializable
internal data class OpaModule(
    @SerialName("package") val pkg: OpaPackage,
    val imports: List<OpaImport> = emptyList(),
    val annotations: List<OpaAnnotationBody> = emptyList(),
    val rules: List<OpaRule> = emptyList(),
)

@Serializable
internal data class OpaInspectAnnotationEntry(
    val annotations: OpaAnnotationBody,
    val location: OpaLocation,
    val path: List<OpaTerm> = emptyList(),
)

/** Top-level result of `opa inspect --annotations --format json <dir>`. */
@Serializable
internal data class OpaInspectResult(
    val annotations: List<OpaInspectAnnotationEntry> = emptyList(),
)

@Serializable
internal data class OpaEvalExpression(
    val value: JsonElement? = null,
)

@Serializable
internal data class OpaEvalResultEntry(
    val expressions: List<OpaEvalExpression> = emptyList(),
)

/** Top-level result of `opa eval --format json --input <file> [--data <dir>]... "data.<package>"` (spec §6.7). */
@Serializable
internal data class OpaEvalResult(
    val result: List<OpaEvalResultEntry> = emptyList(),
)
