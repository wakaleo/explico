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

import io.explico.model.ActionsProjection
import io.explico.model.AnnotationsProjection
import io.explico.model.TimingProjection
import io.explico.model.VerdictProjection
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

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

/**
 * A rule's `custom:` METADATA, held RAW so no key is lost to `ignoreUnknownKeys`. `custom` is free-form in OPA, so any
 * field can arrive in a shape explico does not model (found by benchmarking real-world Rego: Trivy's checks declare
 * `frameworks` as an OBJECT of framework -> control ids, which strict list decoding turned into a whole-load abort for
 * 47 files). It is interpreted leniently: a wrongly-shaped field is treated exactly like an unrecognised key (ignored),
 * never fatal; a list keeps only its string entries, which are exactly what it declares. A `custom` that is not an object
 * at all reads as empty.
 *
 * Reads `control-id`, `frameworks`, `verdict` (spec §14.53), `actions`, `annotations` and `timing` (spec §14.54); [customFields] (spec §14.43) surfaces every OTHER key whose value is a scalar or a
 * list of scalars, and silently skips anything with structure -- never a guess at what a nested object means.
 */
@Serializable(with = OpaCustomAnnotationSerializer::class)
internal data class OpaCustomAnnotation(val raw: JsonObject = JsonObject(emptyMap())) {
    val controlId: String? get() = (raw["control-id"] as? JsonPrimitive)?.takeIf { it.isString }?.content

    val frameworks: List<String>
        get() = (raw["frameworks"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()

    /**
     * The declared `verdict` (spec §14.53), read leniently: absent -> nothing declared; a wrongly-shaped one -> no projection and a
     * [VerdictDeclaration.problem] for the caller to warn about once. Never fatal, never guessed at.
     */
    val verdict: VerdictDeclaration
        get() {
            if (!raw.containsKey("verdict")) return VerdictDeclaration()
            val declared = raw["verdict"] as? JsonObject ?: return VerdictDeclaration(problem = "`verdict` must be a mapping with an `outcome` field")
            val outcome = fieldPathOrNull(declared["outcome"]) ?: return VerdictDeclaration(problem = "`verdict.outcome` is required and must name a field (a non-empty string)")
            if (!declared.containsKey("reason")) return VerdictDeclaration(VerdictProjection(outcome))
            val reason = fieldPathOrNull(declared["reason"]) ?: return VerdictDeclaration(problem = "`verdict.reason` must name a field (a non-empty string) when given")
            return VerdictDeclaration(VerdictProjection(outcome, reason))
        }

    /** The declared `actions` (spec §14.54): `at` (an array) and `label` (within each element) are both required field paths. */
    val actions: Declaration<ActionsProjection>
        get() {
            val declared = mappingOrNull("actions") ?: return absentOrNotAMapping("actions", "`actions` must be a mapping with `at` and `label` fields")
            val at = fieldPathOrNull(declared["at"]) ?: return Declaration(problem = "`actions.at` is required and must name a field (a non-empty string)")
            val label = fieldPathOrNull(declared["label"]) ?: return Declaration(problem = "`actions.label` is required and must name a field (a non-empty string)")
            return Declaration(ActionsProjection(at, label))
        }

    /** The declared `annotations` (spec §14.54): `at` is a required field path. */
    val annotations: Declaration<AnnotationsProjection>
        get() {
            val declared = mappingOrNull("annotations") ?: return absentOrNotAMapping("annotations", "`annotations` must be a mapping with an `at` field")
            val at = fieldPathOrNull(declared["at"]) ?: return Declaration(problem = "`annotations.at` is required and must name a field (a non-empty string)")
            return Declaration(AnnotationsProjection(at))
        }

    /** The declared `timing` (spec §14.54): `dueBy` and/or `startBefore`, at least one; a given field must be a valid path (no half a declaration). */
    val timing: Declaration<TimingProjection>
        get() {
            val declared = mappingOrNull("timing") ?: return absentOrNotAMapping("timing", "`timing` must be a mapping with `dueBy` and/or `startBefore`")
            val fields = listOf("dueBy", "startBefore").map { key ->
                if (!declared.containsKey(key)) null
                else fieldPathOrNull(declared[key]) ?: return Declaration(problem = "`timing.$key` must name a field (a non-empty string) when given")
            }
            if (fields.all { it == null }) return Declaration(problem = "`timing` needs at least one of `dueBy` and `startBefore`")
            return Declaration(TimingProjection(fields[0], fields[1]))
        }

    /**
     * The declared `mask` (spec §14.61): a list of input paths. Unlike the projections above a malformed one is NOT merely ignored -- it is reported as a
     * [MaskDeclaration.problem] the render turns into an error, because ignoring it would show the very values it was written to hide.
     */
    val mask: MaskDeclaration
        get() {
            if (!raw.containsKey("mask")) return MaskDeclaration()
            val declared = raw["mask"] as? JsonArray ?: return MaskDeclaration(problem = "`mask` must be a list of input paths")
            val paths = declared.map { entry ->
                val text = (entry as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return MaskDeclaration(problem = "`mask` entry $entry is not a string")
                if (!MASK_PATH.matches(text)) return MaskDeclaration(problem = "`mask` entry '$text' is not a path (dotted keys, each non-empty; `[]` after a key means every array element)")
                text
            }
            return MaskDeclaration(paths)
        }

    private fun mappingOrNull(key: String): JsonObject? = raw[key] as? JsonObject

    /** Not a mapping: undeclared when the key is absent, otherwise a wrongly-shaped declaration worth a warning. */
    private fun <T> absentOrNotAMapping(key: String, problem: String): Declaration<T> =
        if (raw.containsKey(key)) Declaration(problem = problem) else Declaration()

    /** A field path: a non-empty string whose dot-separated segments are all non-empty; anything else is null. */
    private fun fieldPathOrNull(value: JsonElement?): String? =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { path -> path.split('.').all { it.isNotEmpty() } }

    /** Every other key with a scalar / scalar-list value, as display text (a list comma-joined), sorted by key. */
    val customFields: Map<String, String>
        get() = raw.entries
            .filter { it.key !in RESERVED_KEYS }
            .mapNotNull { (key, value) -> scalarText(value)?.let { key to it } }
            .sortedBy { it.first }
            .toMap(LinkedHashMap())

    private fun scalarText(value: JsonElement): String? = when (value) {
        is JsonPrimitive -> if (value is JsonNull) null else value.content
        is JsonArray -> if (value.isNotEmpty() && value.all { it is JsonPrimitive && it !is JsonNull }) value.joinToString(", ") { (it as JsonPrimitive).content } else null
        else -> null
    }
}

/** Keys explico itself interprets (§14.43, §14.53, §14.54): never repeated in the 'Custom:' passthrough line, even when malformed. */
private val RESERVED_KEYS = setOf("control-id", "frameworks", "verdict", "actions", "annotations", "timing", "mask")

/** A mask path: dot-separated keys, each non-empty and free of `[`, `]`, `.`, `|`, a backtick and line breaks (it is echoed in tables and code spans), optionally followed by `[]` (once or more) for every element of an array. */
private val MASK_PATH = Regex("""[^.\[\]|`\r\n]+(\[\])*(\.[^.\[\]|`\r\n]+(\[\])*)*""")

/** The outcome of reading `custom.mask`: the [paths], a [problem] (declared but malformed), or neither (not declared). */
internal data class MaskDeclaration(val paths: List<String>? = null, val problem: String? = null)

/** The outcome of reading `custom.verdict`: a [projection], a [problem] (declared but wrongly shaped), or neither (not declared). */
internal data class VerdictDeclaration(val projection: VerdictProjection? = null, val problem: String? = null)

/** The same outcome for `custom.actions`, `custom.annotations` and `custom.timing` (spec §14.54). */
internal data class Declaration<T>(val projection: T? = null, val problem: String? = null)

internal object OpaCustomAnnotationSerializer : KSerializer<OpaCustomAnnotation> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor
    override fun deserialize(decoder: Decoder): OpaCustomAnnotation =
        OpaCustomAnnotation((decoder as JsonDecoder).decodeJsonElement() as? JsonObject ?: JsonObject(emptyMap()))
    override fun serialize(encoder: Encoder, value: OpaCustomAnnotation) = encoder.encodeSerializableValue(JsonObject.serializer(), value.raw)
}

/** One entry of a rule's `authors:`; opa itself parses a bare `"Name <email>"` string into both fields. */
@Serializable
internal data class OpaAuthor(val name: String? = null, val email: String? = null)

/** One entry of a rule's `related_resources:`; a bare URL string arrives as `{ref}` with no description. */
@Serializable
internal data class OpaRelatedResource(val ref: String? = null, val description: String? = null)

/** A METADATA annotation body. Used both for `opa parse`'s package/rule annotations and nested inside [OpaInspectAnnotationEntry]. */
@Serializable
internal data class OpaAnnotationBody(
    val scope: String? = null,
    val title: String? = null,
    val description: String? = null,
    val custom: OpaCustomAnnotation? = null,
    val authors: List<OpaAuthor> = emptyList(),
    @SerialName("related_resources") val relatedResources: List<OpaRelatedResource> = emptyList(),
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
    /**
     * Declared parameter names, for a user-defined FUNCTION (review item 8) -- null for an
     * ordinary rule, a (possibly empty) list of `"var"` terms for a function, confirmed via a real
     * `opa parse` run against `is_exempt(ns) if {...}`.
     */
    val args: List<OpaTerm>? = null,
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
     * The next branch of an `else` chain (spec §14.52). Confirmed against a real `opa parse` capture
     * (`src/test/resources/ast/else-chain.json`): the branch is a nested, rule-shaped object -- its own
     * `head` (value, no annotations), `body` and `location`, and its own `else` for the branch after it, so
     * a chain is a linked list. A bodiless final `else = v` has a synthetic single `true` body expr whose
     * location equals the branch's own. Before spec §14 this was presence-only (and before that silently
     * dropped by `ignoreUnknownKeys`).
     */
    @SerialName("else") val elseBranch: OpaRule? = null,
)

@Serializable
internal data class OpaPackage(
    val path: List<OpaTerm> = emptyList(),
    val location: OpaLocation? = null,
)

@Serializable
internal data class OpaImport(
    val path: OpaTerm,
    /** The `as <alias>` name; absent for a plain import (the alias is then the path's last segment). */
    val alias: String? = null,
    val location: OpaLocation? = null,
)

/** Top-level result of `opa parse --format json --json-include locations <file>`. */
@Serializable
internal data class OpaModule(
    @SerialName("package") val pkg: OpaPackage,
    val imports: List<OpaImport> = emptyList(),
    val annotations: List<OpaAnnotationBody> = emptyList(),
    val rules: List<OpaRule> = emptyList(),
    /** Every comment in the file (spec §14.65), METADATA lines included; absent when the file has none. */
    val comments: List<OpaComment> = emptyList(),
)

/**
 * One comment as `opa parse` reports it: its text WITHOUT the `#` (note the capitalised keys) and where it is. A bare `#` line has NO text: opa reports it as `null`
 * (found by the real-world corpus benchmark -- it crashed the load of every file that had one), which reads as an empty comment.
 */
@Serializable
internal data class OpaComment(
    @SerialName("Text") val text: String? = null,
    @SerialName("Location") val location: OpaLocation,
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
