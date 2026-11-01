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

import io.explico.ExplicoException

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class InputMask private constructor(private val paths: List<List<Step>>, private val sources: List<String> = emptyList()) {

    private sealed interface Step {
        data class Key(val name: String) : Step
        data object Each : Step
    }

    /** One value that was masked: a string (hidden wherever it occurs as text) or a number (hidden as a whole token only). */
    class Secret(val text: String, val isNumber: Boolean)

    /** An example's input with the masked values replaced by [MASK], and the values that were hidden, longest first. */
    class Applied(val input: JsonObject, found: List<Secret>) {
        /** Every value that was hidden, plus the forms a rule may print it in beyond its plain text (JSON-escaped: `json.marshal`, `%q`), longest first. */
        val secrets: List<Secret> = withEscapedForms(found)

        val isActive: Boolean get() = secrets.isNotEmpty()

        /** Compiled once per example, not once per text. */
        private val tokens: List<Regex> = secrets.map { if (it.isNumber) numberToken(it.text) else stringToken(it.text) }

        /** [text] with every secret replaced by `***`. A string matches in any case; a secret that contains another is hidden whole (longest first); a number only when it stands alone (`1` never corrupts `11` or `1.5`). */
        fun scrub(text: String): String {
            var result = text
            for (token in tokens) result = result.replace(token, "***")
            return result
        }

        /** [element] with every string scrubbed and every number that is a secret replaced; objects and arrays are walked, and keys are scrubbed too. */
        fun scrub(element: JsonElement?): JsonElement? = when (element) {
            null, JsonNull -> element
            is JsonPrimitive ->
                if (element.isString) JsonPrimitive(scrub(element.content))
                else if (secrets.any { it.isNumber && it.text == element.content }) JsonPrimitive("***") else element
            is JsonArray -> JsonArray(element.map { scrub(it)!! })
            is JsonObject -> JsonObject(element.entries.associate { (key, value) -> scrub(key) to scrub(value)!! })
        }

        private fun withEscapedForms(found: List<Secret>): List<Secret> {
            val all = found.flatMap { secret ->
                if (secret.isNumber) listOf(secret)
                else {
                    // As JSON text would print it (escaped quote, backslash, line break) and as Go's encoder does (OPA's json.marshal also escapes < > &).
                    val json = JsonPrimitive(secret.text).toString().removeSurrounding("\"")
                    val go = json.replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
                    listOf(secret, Secret(json, false), Secret(go, false))
                }
            }
            return all.distinctBy { it.isNumber to it.text }.sortedWith(compareByDescending<Secret> { it.text.length }.thenBy { it.text })
        }

        /** A string secret matches whatever its case: a rule may print it through `lower(...)` or `upper(...)`. Over-masking is the safe side. */
        private fun stringToken(text: String) = Regex(Regex.escape(text), setOf(RegexOption.IGNORE_CASE))

        private fun numberToken(number: String) =
            Regex("(?<![\\p{L}\\p{N}_]|\\p{N}\\.)" + Regex.escape(number) + "(?![\\p{L}\\p{N}_]|\\.\\p{N})")
    }

    /** The declared paths of which NONE hides a string or number in any of [inputs]: a typo, the wrong case, or a leading `input.` -- they would hide nothing, and the values would be shown. */
    fun unmatchedPaths(inputs: List<JsonObject>): List<String> =
        paths.indices.filter { index -> inputs.none { InputMask(listOf(paths[index])).apply(it).isActive } }.map { sources[it] }

    /** Masks [input]: the values at the declared paths become [MASK]; a path that is absent, or runs through something that is not what it names, is ignored. */
    fun apply(input: JsonObject): Applied {
        val found = mutableListOf<Secret>()
        var masked: JsonElement = input
        for (path in paths) masked = mask(masked, path, found)
        return Applied(masked as JsonObject, found)
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
        fun of(paths: List<String>?): InputMask? = paths?.takeIf { it.isNotEmpty() }?.let { InputMask(it.map(::steps), it) }

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

/**
 * A rule's `custom.mask` is malformed (spec §14.61): the render refuses rather than show the values it was written to hide. The message lists every offending rule. An [io.explico.ExplicoException]
 * (the CLI reports it as exit 1), and since 1.0 no longer an [IllegalArgumentException]: one base for every failure a caller catches by type.
 */
public class InvalidMaskException(message: String) : ExplicoException(message)
