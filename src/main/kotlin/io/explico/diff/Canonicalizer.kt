/**
 * Canonical hashing of a rule group's logic and metadata (spec §7.1) -- the basis
 * for classifying a control as UNCHANGED/DOCS_CHANGED/LOGIC_CHANGED across two
 * policy versions. Two rule groups that differ only in source formatting, comments,
 * or local variable names must hash identically; any change to the actual
 * conditions, operators, operands, or produced messages must hash differently.
 */
package io.explico.diff

import io.explico.model.Condition
import io.explico.model.Operand
import io.explico.model.PathSegment
import io.explico.model.RuleBody
import io.explico.model.RuleGroup
import io.explico.model.RuleMetadata
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.security.MessageDigest
import java.util.HexFormat

internal object Canonicalizer {

    /**
     * SHA-256 of the rule group's logic (spec §7.1): [io.explico.model.SourceRef]s and
     * [RuleMetadata] excluded, local variables renamed positionally per body (`v1`, `v2`, ... in
     * order of first occurrence), serialised to JSON with keys in a fixed (alphabetical) order.
     * Unrendered fallback text is hashed verbatim -- the tool can't verify equivalence for a
     * construct it didn't understand, so it deliberately doesn't try to canonicalize inside it.
     */
    fun logicHash(rule: RuleGroup): String = sha256(canonicalJsonString(canonicalLogic(rule)))

    /** SHA-256 of the rule's [RuleMetadata], nulls included (spec §7.1). Absent metadata hashes as JSON `null`. */
    fun metadataHash(rule: RuleGroup): String = sha256(canonicalJsonString(canonicalMetadata(rule.metadata)))

    // rule.name is deliberately excluded: it's the rule's identity, not its logic -- a
    // control-id-preserving rename (spec §7.1/§7.2) must hash identically when the body is
    // otherwise unchanged, so PolicyDiff classifies it UNCHANGED rather than LOGIC_CHANGED.
    private fun canonicalLogic(rule: RuleGroup): JsonObject = buildJsonObject {
        put("bodies", buildJsonArray { rule.bodies.forEach { add(canonicalBody(it)) } })
        put("constantValue", rule.constantValue?.rendered?.let { JsonPrimitive(it) } ?: JsonNull)
        put("default", rule.default?.rendered?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    private fun canonicalBody(body: RuleBody): JsonObject {
        val aliases = mutableMapOf<String, String>()
        return buildJsonObject {
            put("conditions", buildJsonArray { body.conditions.forEach { add(canonicalCondition(it, aliases)) } })
            put("messageArguments", buildJsonArray { body.messageArguments.forEach { add(canonicalOperand(it, aliases)) } })
            put("messageTemplate", body.messageTemplate?.let { JsonPrimitive(it) } ?: JsonNull)
            // Follow-up to review item 10: the value a conditional rule's body yields is part of
            // its logic -- changing "high" to "medium" with identical conditions must be
            // LOGIC_CHANGED, the same reasoning already applied to messageTemplate above.
            put("yieldsValue", body.yieldsValue?.let { canonicalOperand(it, aliases) } ?: JsonNull)
        }
    }

    private fun canonicalCondition(condition: Condition, aliases: MutableMap<String, String>): JsonObject = when (condition) {
        is Condition.Comparison -> buildJsonObject {
            put("left", canonicalOperand(condition.left, aliases))
            put("negated", JsonPrimitive(condition.negated))
            put("op", JsonPrimitive(condition.op.name))
            put("right", canonicalOperand(condition.right, aliases))
            put("type", JsonPrimitive("Comparison"))
        }
        is Condition.Membership -> buildJsonObject {
            put("collection", canonicalOperand(condition.collection, aliases))
            put("member", canonicalOperand(condition.member, aliases))
            put("negated", JsonPrimitive(condition.negated))
            put("type", JsonPrimitive("Membership"))
        }
        is Condition.Truthy -> buildJsonObject {
            put("negated", JsonPrimitive(condition.negated))
            put("operand", canonicalOperand(condition.operand, aliases))
            put("type", JsonPrimitive("Truthy"))
        }
        is Condition.BuiltinCall -> buildJsonObject {
            put("args", buildJsonArray { condition.args.forEach { add(canonicalOperand(it, aliases)) } })
            put("name", JsonPrimitive(condition.name))
            put("negated", JsonPrimitive(condition.negated))
            put("type", JsonPrimitive("BuiltinCall"))
        }
        is Condition.SomeIn -> buildJsonObject {
            // Both bound variables are aliased first, key before variable: textually
            // `some k, v in ...` (or single-variable `some v in ...`) introduces them left-to-right
            // before the collection is even read, and later references must resolve to the same
            // aliases this assigns.
            val keyAlias = condition.key?.let { aliasFor(it, aliases) }
            val alias = aliasFor(condition.variable, aliases)
            put("collection", canonicalOperand(condition.collection, aliases))
            put("key", keyAlias?.let { JsonPrimitive(it) } ?: JsonNull)
            put("type", JsonPrimitive("SomeIn"))
            put("variable", JsonPrimitive(alias))
        }
        is Condition.RuleReference -> buildJsonObject {
            put("negated", JsonPrimitive(condition.negated))
            put("packagePath", JsonPrimitive(condition.packagePath))
            put("ruleName", JsonPrimitive(condition.ruleName))
            put("type", JsonPrimitive("RuleReference"))
        }
        is Condition.FunctionCall -> buildJsonObject {
            put("args", buildJsonArray { condition.args.forEach { add(canonicalOperand(it, aliases)) } })
            put("functionName", JsonPrimitive(condition.functionName))
            put("negated", JsonPrimitive(condition.negated))
            put("packagePath", JsonPrimitive(condition.packagePath))
            put("type", JsonPrimitive("FunctionCall"))
        }
        is Condition.Unrendered -> buildJsonObject {
            put("reason", JsonPrimitive(condition.reason))
            put("sourceText", JsonPrimitive(condition.sourceText))
            put("type", JsonPrimitive("Unrendered"))
        }
    }

    private fun canonicalOperand(operand: Operand, aliases: MutableMap<String, String>): JsonObject = when (operand) {
        is Operand.Path -> buildJsonObject {
            put("segments", buildJsonArray { operand.segments.forEach { add(canonicalSegment(it, aliases)) } })
            put("type", JsonPrimitive("Path"))
        }
        is Operand.Literal -> buildJsonObject {
            // Spec §14 promotion: an array and a set literal of the SAME elements are different
            // Rego values, so a changed delimiter kind must change the hash, not just a changed
            // `rendered` string.
            put("collection", operand.collection?.let { JsonPrimitive(it.name) } ?: JsonNull)
            put("rendered", JsonPrimitive(operand.rendered))
            put("type", JsonPrimitive("Literal"))
        }
        is Operand.Variable -> buildJsonObject {
            put("name", JsonPrimitive(aliasFor(operand.name, aliases)))
            put("type", JsonPrimitive("Variable"))
        }
        is Operand.Parameter -> buildJsonObject {
            put("name", JsonPrimitive(aliasFor(operand.name, aliases)))
            put("type", JsonPrimitive("Parameter"))
        }
        is Operand.BuiltinCall -> buildJsonObject {
            put("args", buildJsonArray { operand.args.forEach { add(canonicalOperand(it, aliases)) } })
            put("name", JsonPrimitive(operand.name))
            put("type", JsonPrimitive("BuiltinCall"))
        }
        is Operand.FunctionCall -> buildJsonObject {
            put("args", buildJsonArray { operand.args.forEach { add(canonicalOperand(it, aliases)) } })
            put("functionName", JsonPrimitive(operand.functionName))
            put("packagePath", JsonPrimitive(operand.packagePath))
            put("type", JsonPrimitive("FunctionCall"))
        }
        is Operand.Comprehension -> buildJsonObject {
            // Keys are inserted alphabetically; variables are aliased in that traversal order (body
            // first), which is consistent for any renamed copy of the same comprehension.
            put("body", buildJsonArray { operand.body.forEach { add(canonicalCondition(it, aliases)) } })
            put("kind", JsonPrimitive(operand.kind.name))
            put("term", canonicalOperand(operand.term, aliases))
            put("type", JsonPrimitive("Comprehension"))
            put("valueTerm", operand.valueTerm?.let { canonicalOperand(it, aliases) } ?: JsonNull)
        }
        is Operand.ParameterPath -> buildJsonObject {
            put("name", JsonPrimitive(aliasFor(operand.name, aliases)))
            put("segments", buildJsonArray { operand.segments.forEach { add(canonicalSegment(it, aliases)) } })
            put("type", JsonPrimitive("ParameterPath"))
        }
        is Operand.RuleValueReference -> buildJsonObject {
            put("packagePath", JsonPrimitive(operand.packagePath))
            put("ruleName", JsonPrimitive(operand.ruleName))
            put("type", JsonPrimitive("RuleValueReference"))
        }
        is Operand.Unrendered -> buildJsonObject {
            put("sourceText", JsonPrimitive(operand.sourceText))
            put("type", JsonPrimitive("Unrendered"))
        }
    }

    private fun canonicalSegment(segment: PathSegment, aliases: MutableMap<String, String>): JsonObject = when (segment) {
        is PathSegment.Field -> buildJsonObject { put("name", JsonPrimitive(segment.name)); put("type", JsonPrimitive("Field")) }
        PathSegment.AnyIndex -> buildJsonObject { put("type", JsonPrimitive("AnyIndex")) }
        is PathSegment.VarIndex -> buildJsonObject {
            put("name", JsonPrimitive(aliasFor(segment.name, aliases)))
            put("type", JsonPrimitive("VarIndex"))
        }
        is PathSegment.KeyLiteral -> buildJsonObject { put("key", JsonPrimitive(segment.key)); put("type", JsonPrimitive("KeyLiteral")) }
        is PathSegment.KeyPath -> buildJsonObject {
            put("segments", buildJsonArray { segment.segments.forEach { add(canonicalSegment(it, aliases)) } })
            put("type", JsonPrimitive("KeyPath"))
        }
    }

    /** Positional rename (spec §7.1): the same original name always maps to the same alias within one body. */
    private fun aliasFor(name: String, aliases: MutableMap<String, String>): String =
        aliases.getOrPut(name) { "v${aliases.size + 1}" }

    private fun canonicalMetadata(metadata: RuleMetadata?): JsonElement {
        if (metadata == null) return JsonNull
        return buildJsonObject {
            put("controlId", metadata.controlId?.let { JsonPrimitive(it) } ?: JsonNull)
            put("description", metadata.description?.let { JsonPrimitive(it) } ?: JsonNull)
            put("frameworks", buildJsonArray { metadata.frameworks.forEach { add(JsonPrimitive(it)) } })
            // Spec §14.43: authors / related resources / custom fields are shown on the card, so they are documentation
            // that can change (DOCS_CHANGED). Keys in alphabetical order, like every other object here.
            put("authors", buildJsonArray {
                metadata.authors.forEach { add(buildJsonObject {
                    put("email", it.email?.let { e -> JsonPrimitive(e) } ?: JsonNull)
                    put("name", it.name?.let { n -> JsonPrimitive(n) } ?: JsonNull)
                }) }
            })
            put("customFields", buildJsonObject { metadata.customFields.entries.sortedBy { it.key }.forEach { put(it.key, JsonPrimitive(it.value)) } })
            put("relatedResources", buildJsonArray {
                metadata.relatedResources.forEach { add(buildJsonObject {
                    put("description", it.description?.let { d -> JsonPrimitive(d) } ?: JsonNull)
                    put("ref", JsonPrimitive(it.ref))
                }) }
            })
            put("title", metadata.title?.let { JsonPrimitive(it) } ?: JsonNull)
        }
    }

    private fun canonicalJsonString(element: JsonElement): String = element.toString()

    private fun sha256(text: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
}
