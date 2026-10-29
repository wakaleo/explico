/**
 * Spec §14.53: reading a rule's DECLARED verdict (`custom.verdict`) out of its evaluated object value.
 */
package io.explico.render

import io.explico.model.VerdictProjection
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * One example's verdict: the boolean [outcome], the words shown after the glyph ([text]: the declared field's own name, `not ` before it when
 * false -- nothing invented) and the declared [reason], null when none was declared.
 */
internal data class ProjectedVerdict(val outcome: Boolean, val text: String, val reason: String?) {
    /** The summary-line words: the glyph, then [text]. */
    val summary: String get() = "${if (outcome) "✅" else "❌"} $text"
}

internal object VerdictProjector {

    /**
     * Projects [value] through [projection], or returns null -- the caller then keeps the example as raw JSON, exactly as without a
     * declaration. STRICT: the outcome must be a real JSON boolean and a declared reason a real JSON string, and [value] a JSON object.
     * Any other shape (missing field, a string "true", a number, an array of objects) is null, never coerced and never half-projected.
     * A dot in a field path is always a separator, so a literal key containing a dot is not found.
     */
    fun project(projection: VerdictProjection?, value: JsonElement?): ProjectedVerdict? {
        if (projection == null || value !is JsonObject) return null
        val outcome = (FieldPath.at(value, projection.outcome) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: return null
        val reason = projection.reason?.let { path ->
            (FieldPath.at(value, path) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        }
        return ProjectedVerdict(outcome, if (outcome) projection.outcome else "not ${projection.outcome}", reason)
    }
}
