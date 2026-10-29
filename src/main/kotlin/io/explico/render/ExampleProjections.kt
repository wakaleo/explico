/**
 * Spec §14.53, §14.54: everything a rule DECLARED about its object value, read out of one example's evaluated value.
 */
package io.explico.render

import io.explico.model.ActionsProjection
import io.explico.model.AnnotationsProjection
import io.explico.model.DeclaredProjection
import io.explico.model.TimingProjection
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Walks a declared field path (dot-separated object keys); a dot is ALWAYS a separator, so a literal key containing one cannot be named. */
internal object FieldPath {
    /** The element at [path] below [root]; null when any step is missing or passes through something that is not an object. */
    fun at(root: JsonObject, path: String): JsonElement? =
        path.split('.').fold(root as JsonElement?) { current, key -> (current as? JsonObject)?.get(key) }

    /** The non-empty string at [path], or null (missing, null, a number, a boolean, an object, an empty string). */
    fun nonEmptyString(root: JsonObject, path: String): String? =
        (at(root, path) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
}

/** The instants a declared `timing` found; each null when its value was unusable. Present (not null) whenever the value was an object. */
internal data class ProjectedTiming(val dueBy: String?, val startBefore: String?)

/**
 * One example's projections. Every part is null when not declared or when this example's value does not fully support it, so the card and
 * `explico.json` (which both read this) cannot disagree about what applies. [remediation] and [annotations] may be EMPTY (the value said so).
 */
internal data class ProjectedExample(
    val verdict: ProjectedVerdict? = null,
    val remediation: List<String>? = null,
    val annotations: List<String>? = null,
    val timing: ProjectedTiming? = null,
)

internal object ExampleProjections {

    /** All projections of [example] under [projection]; nothing at all for an example the rule did not match or that has no value. */
    fun of(projection: DeclaredProjection?, example: WorkedExample): ProjectedExample {
        if (projection == null || !example.matched) return ProjectedExample()
        val value = example.value
        return ProjectedExample(
            verdict = VerdictProjector.project(projection.verdict, value),
            remediation = remediation(projection.actions, value),
            annotations = annotations(projection.annotations, value),
            timing = timing(projection.timing, value),
        )
    }

    /**
     * The label of EVERY action, in array order. STRICT: [value] must be an object whose `at` is an array, and every element must have a
     * non-empty string at `label` -- otherwise null, never a partial list (a list with a hole would claim that is all there is).
     */
    fun remediation(projection: ActionsProjection?, value: JsonElement?): List<String>? {
        if (projection == null || value !is JsonObject) return null
        val actions = FieldPath.at(value, projection.at) as? JsonArray ?: return null
        return actions.map { element -> (element as? JsonObject)?.let { FieldPath.nonEmptyString(it, projection.label) } ?: return null }
    }

    /** The status strings: `at` must be an array of non-empty strings (nothing is coerced), else null. */
    fun annotations(projection: AnnotationsProjection?, value: JsonElement?): List<String>? {
        if (projection == null || value !is JsonObject) return null
        val statuses = FieldPath.at(value, projection.at) as? JsonArray ?: return null
        return statuses.map { element -> (element as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content ?: return null }
    }

    /** The declared instants as the strings they are, verbatim; an unusable or undeclared one is null. Null overall unless [value] is an object. */
    fun timing(projection: TimingProjection?, value: JsonElement?): ProjectedTiming? {
        if (projection == null || value !is JsonObject) return null
        return ProjectedTiming(
            dueBy = projection.dueBy?.let { FieldPath.nonEmptyString(value, it) },
            startBefore = projection.startBefore?.let { FieldPath.nonEmptyString(value, it) },
        )
    }
}
