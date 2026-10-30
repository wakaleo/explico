/**
 * Spec §14.61: a rule's declared `custom.mask` -- paths inside a fixture's input whose values must not appear in the documentation. The real input is still
 * what OPA evaluates (so every verdict is true); what is MASKED is what an example shows. Pure: [view] turns one example into the example as it may be
 * shown -- the input with each masked value replaced by [MASK], and every other place that value could be echoed (a produced message, a verdict reason, a
 * remediation label, the raw value, the fixture name) scrubbed of the values that were masked -- and everything downstream (the card, `explico.json`) reads
 * only that view, so no consumer can forget.
 *
 * Only values that are IN THE FIXTURE INPUT are known, hence maskable. A value a rule derives, or reads from `data`, that never appears in the input is not.
 */
package io.explico.render

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class InputMask private constructor(private val paths: List<List<Step>>) {

    private sealed interface Step {
        data class Key(val name: String) : Step
        data object Each : Step
    }

    /** One value that was masked: a string (hidden wherever it occurs as text) or a number (hidden as a whole token only). */
    class Secret(val text: String, val isNumber: Boolean)

    /** An example's input with the masked values replaced by [MASK], and the values that were hidden, longest first. */
    class Applied(val input: JsonObject, val secrets: List<Secret>) {
        val isActive: Boolean get() = secrets.isNotEmpty()

        /** [text] with every secret replaced by `***`. A string matches in any case; a secret that contains another is hidden whole (longest first); a number only when it stands alone (`1` never corrupts `11` or `1.5`). */
        fun scrub(text: String): String {
            var result = text
            for (secret in secrets) {
                result = if (secret.isNumber) result.replace(numberToken(secret.text), "***") else result.replace(stringToken(secret.text), "***")
            }
            return result
        }

        /** [element] with every string scrubbed and every number that is a secret replaced; objects and arrays are walked, keys are left alone. */
        fun scrub(element: JsonElement?): JsonElement? = when (element) {
            null, JsonNull -> element
            is JsonPrimitive ->
                if (element.isString) JsonPrimitive(scrub(element.content))
                else if (secrets.any { it.isNumber && it.text == element.content }) JsonPrimitive("***") else element
            is JsonArray -> JsonArray(element.map { scrub(it)!! })
            is JsonObject -> JsonObject(element.mapValues { scrub(it.value)!! })
        }

        /** A string secret matches whatever its case: a rule may print it through `lower(...)` or `upper(...)`. Over-masking is the safe side. */
        private fun stringToken(text: String) = Regex(Regex.escape(text), setOf(RegexOption.IGNORE_CASE))

        private fun numberToken(number: String) =
            Regex("(?<![\\p{L}\\p{N}_]|\\p{N}\\.)" + Regex.escape(number) + "(?![\\p{L}\\p{N}_]|\\.\\p{N})")
    }

    /** Masks [input]: the values at the declared paths become [MASK]; a path that is absent, or runs through something that is not what it names, is ignored. */
    fun apply(input: JsonObject): Applied {
        val found = mutableListOf<Secret>()
        var masked: JsonElement = input
        for (path in paths) masked = mask(masked, path, found)
        val ordered = found.distinctBy { it.isNumber to it.text }.sortedWith(compareByDescending<Secret> { it.text.length }.thenBy { it.text })
        return Applied(masked as JsonObject, ordered)
    }

    private fun mask(element: JsonElement, path: List<Step>, found: MutableList<Secret>): JsonElement {
        if (path.isEmpty()) return hide(element, found)
        return when (val step = path.first()) {
            is Step.Key -> if (element is JsonObject && element.containsKey(step.name))
                JsonObject(element.mapValues { (key, value) -> if (key == step.name) mask(value, path.drop(1), found) else value })
            else element
            Step.Each -> if (element is JsonArray) JsonArray(element.map { mask(it, path.drop(1), found) }) else element
        }
    }

    /** The marker for a string, a number, an object or an array (recording every string and number inside as secret); a boolean or null stays as it is. */
    private fun hide(element: JsonElement, found: MutableList<Secret>): JsonElement {
        if (!isMaskable(element)) return element
        collect(element, found)
        return MASK
    }

    /** A string, a number, an object or an array; a boolean or null has nothing to hide. */
    private fun isMaskable(element: JsonElement): Boolean = when (element) {
        is JsonNull -> false
        is JsonPrimitive -> element.isString || element.content.toDoubleOrNull() != null
        else -> true
    }

    private fun collect(element: JsonElement, found: MutableList<Secret>) {
        when (element) {
            is JsonPrimitive -> if (element !is JsonNull) {
                if (element.isString) { if (element.content.isNotEmpty()) found += Secret(element.content, isNumber = false) }
                else if (element.content.toDoubleOrNull() != null) found += Secret(element.content, isNumber = true)
            }
            is JsonArray -> element.forEach { collect(it, found) }
            is JsonObject -> element.values.forEach { collect(it, found) }
        }
    }

    companion object {
        /** The marker shown instead of a masked value. A single instance: the path-value bullet recognises it by identity (so it prints as plain `***`, not a quoted string). */
        val MASK: JsonPrimitive = JsonPrimitive("***")

        /** Null when nothing is declared. [paths] are already validated (`custom.mask` reading); each is split into keys and `[]` steps. */
        fun of(paths: List<String>?): InputMask? = paths?.takeIf { it.isNotEmpty() }?.let { InputMask(it.map(::steps)) }

        private fun steps(path: String): List<Step> = path.split('.').flatMap { segment ->
            val name = segment.substringBefore('[')
            listOf<Step>(Step.Key(name)) + List(segment.count { it == '[' }) { Step.Each }
        }
    }
}

/** One example as it may be SHOWN under a rule's declared mask (spec §14.61): the single place the masking of an example is applied, for the card and `explico.json` alike. */
internal object ExampleMasking {

    /** [example] and its display [name] with the masked values hidden; [active] is true when at least one value was masked (the Markdown then writes the marker so it cannot open emphasis). */
    class View(val example: WorkedExample, val name: String, val active: Boolean)

    fun view(mask: List<String>?, name: String, example: WorkedExample): View {
        val inputMask = InputMask.of(mask) ?: return View(example, name, false)
        val applied = inputMask.apply(example.fixture.input)
        if (!applied.isActive) return View(example, name, false)
        val fixture = example.fixture.copy(
            name = applied.scrub(example.fixture.name),
            description = example.fixture.description?.let { applied.scrub(it) },
            input = applied.input,
        )
        return View(
            example.copy(fixture = fixture, messages = example.messages.map { applied.scrub(it) }, value = applied.scrub(example.value)),
            applied.scrub(name),
            active = true,
        )
    }

    /** The marker as the Markdown writes it inside an italic span, where a bare `***` would open emphasis. */
    fun italic(text: String, active: Boolean): String = if (active) text.replace("***", "\\*\\*\\*") else text
}

/** A rule's `custom.mask` is malformed (spec §14.61): the render refuses rather than show the values it was written to hide. An [IllegalArgumentException] so a library caller needs no new type; the CLI reports it as exit 1. */
internal class InvalidMaskException(message: String) : IllegalArgumentException(message)
