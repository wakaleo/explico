/**
 * Worked-example fixtures (spec §6.7): example inputs evaluated against the policy
 * set via `opa eval` and rendered as observed verdicts on each control card. The
 * tool never predicts what a rule would do -- every verdict here came from a real
 * `opa eval` invocation.
 */
package io.explico.render

import io.explico.model.Condition
import io.explico.model.Operand
import io.explico.model.PathSegment
import io.explico.model.PolicyPackage
import io.explico.model.RuleGroup
import io.explico.opa.OpaInvocationException
import io.explico.opa.opaJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One fixture: a named example input, as loaded from `--examples <dir>` (spec §6.7's fixture format). */
@Serializable
public data class Fixture(
    val name: String,
    val description: String? = null,
    val input: JsonObject,
)

/** All fixtures loaded from one examples directory, in filename order. */
public data class ExampleSet(val fixtures: List<Fixture>)

/** Two fixtures share a `name` (spec §6.7: "must be unique across the set"). */
public class DuplicateFixtureNameException(public val name: String) :
    RuntimeException("Duplicate fixture name across the examples set: '$name'")

/** Parses fixture JSON texts (already read from disk, in filename order) into an [ExampleSet]. */
internal fun parseExampleSet(fixtureJsonTexts: List<String>): ExampleSet {
    val fixtures = fixtureJsonTexts.map { opaJson.decodeFromString(Fixture.serializer(), it) }
    val duplicateName = fixtures.groupBy { it.name }.entries.firstOrNull { it.value.size > 1 }?.key
    if (duplicateName != null) throw DuplicateFixtureNameException(duplicateName)
    return ExampleSet(fixtures)
}

/** One fixture's evaluated outcome for one rule group. [messages] is empty when not matched. [situationLabels] is parallel to [messages]; an entry is null when body attribution isn't possible for this rule. */
internal data class WorkedExample(
    val fixture: Fixture,
    val matched: Boolean,
    val messages: List<String>,
    val situationLabels: List<Int?>,
)

internal object WorkedExamples {

    /**
     * Evaluates every fixture against [pkg] (one [evalPackage] call per fixture, not per rule --
     * spec §6.7) and returns each rule's outcomes in filename order. A fixture whose evaluation
     * throws is skipped for the whole package after a stderr warning -- never silently.
     */
    fun evaluatePackage(
        fixtures: List<Fixture>,
        pkg: PolicyPackage,
        warn: (String) -> Unit = { System.err.println(it) },
        evalPackage: (Fixture) -> JsonElement,
    ): Map<String, List<WorkedExample>> {
        val outcomesByRule = pkg.rules.associate { it.name to mutableListOf<WorkedExample>() }
        val attributionByRule = pkg.rules.associate { it.name to attributionRegexes(it) }

        for (fixture in fixtures) {
            val packageValue = try {
                evalPackage(fixture).jsonObject
            } catch (e: OpaInvocationException) {
                warn("Skipping fixture '${fixture.name}' for package '${pkg.path}': opa eval failed:\n${e.stderr}")
                continue
            }
            for (rule in pkg.rules) {
                val (matched, messages) = verdictFor(rule.name, packageValue)
                val labels = messages.map { situationFor(attributionByRule.getValue(rule.name), it) }
                outcomesByRule.getValue(rule.name).add(WorkedExample(fixture, matched, messages, labels))
            }
        }
        return outcomesByRule
    }

    /** Up to 3 matching then up to 2 non-matching, each group in filename order (spec §6.7 -- confirmed against the release-approvals.md golden, which shows matched examples before non-matched ones even when a non-matched fixture's filename sorts earlier). */
    fun select(outcomes: List<WorkedExample>): List<WorkedExample> =
        outcomes.filter { it.matched }.take(3) + outcomes.filter { !it.matched }.take(2)

    /** Spec §6.7: name-based mapping, "documented in the README" -- simple, not configurable. */
    fun outcomeWord(ruleName: String, matched: Boolean): String = when (ruleName) {
        "deny", "violation" -> if (matched) "❌ denied" else "✅ allowed"
        "allow" -> if (matched) "✅ allowed" else "❌ denied"
        else -> if (matched) "matched" else "not matched"
    }

    /** Dedupe `Operand.Path` across all bodies, order by first appearance, `input.`-rooted only (spec §6.7: "resolve each against the fixture input" -- a `data.*` path isn't part of the fixture). */
    fun referencedPaths(rule: RuleGroup): List<Operand.Path> {
        val seen = LinkedHashSet<Operand.Path>()
        for (body in rule.bodies) {
            for (condition in body.conditions) {
                // A SomeIn's own collection (e.g. `pipeline ▸ stages`) resolves to a raw array of
                // objects, not a scalar -- there's no sensible display value for it, and it's
                // already shown structurally via the "for some ... in" bullet. The per-element
                // access via VarIndex (`pipeline ▸ stages ▸ [each stage] ▸ status`) is what's
                // actually useful, and that's a separate Path already covered below.
                if (condition is Condition.SomeIn) continue
                for (operand in Coverage.operandsOf(condition)) {
                    if (operand is Operand.Path && operand.segments.firstOrNull() == PathSegment.Field("input")) {
                        seen.add(operand)
                    }
                }
            }
        }
        return seen.toList()
    }

    /** Resolves [path] against the fixture's `input` object. Null = absent. `AnyIndex`/`VarIndex` collect across elements, capped at 3 with "…". */
    fun resolvePathValue(path: Operand.Path, input: JsonObject): String? {
        val results = resolveSegments(path.segments.drop(1), input)
        if (results.isEmpty()) return null
        val rendered = results.take(3).joinToString(", ") { renderJsonScalar(it) }
        return if (results.size > 3) "$rendered, …" else rendered
    }

    private fun resolveSegments(segments: List<PathSegment>, current: JsonElement): List<JsonElement> {
        if (segments.isEmpty()) return listOf(current)
        val rest = segments.drop(1)
        return when (val segment = segments.first()) {
            is PathSegment.Field -> (current as? JsonObject)?.get(segment.name)?.let { resolveSegments(rest, it) } ?: emptyList()
            is PathSegment.KeyLiteral -> (current as? JsonObject)?.get(segment.key)?.let { resolveSegments(rest, it) } ?: emptyList()
            is PathSegment.AnyIndex, is PathSegment.VarIndex ->
                (current as? JsonArray)?.flatMap { resolveSegments(rest, it) } ?: emptyList()
        }
    }

    private fun renderJsonScalar(element: JsonElement): String {
        val primitive = element as? JsonPrimitive ?: return element.toString()
        return if (primitive.isString) "\"${primitive.content}\"" else primitive.content
    }

    /** Spec §6.7's three verdict shapes: set rules (JSON array), boolean/complete rules, or an undefined/missing key (not matched, per spec, same as boolean false). */
    private fun verdictFor(ruleName: String, packageValue: JsonObject): Pair<Boolean, List<String>> {
        val value = packageValue[ruleName] ?: return false to emptyList()
        return when {
            value is JsonArray -> value.isNotEmpty() to value.map { it.jsonPrimitive.content }
            value is JsonPrimitive && value.booleanOrNull != null -> value.jsonPrimitive.booleanOrNull!! to emptyList()
            // Any other value type (spec §6.7): render verbatim. Unexercised by the acceptance pack
            // (every pack rule is a set rule or a boolean rule) -- a minimal, disclosed simplification.
            else -> true to listOf(value.toString())
        }
    }

    /** Null if any body's [io.explico.model.RuleBody.messageTemplate] is missing or templates aren't all distinct (spec §6.7: "best effort, never guessed"). */
    private fun attributionRegexes(rule: RuleGroup): List<Regex>? {
        val templates = rule.bodies.map { it.messageTemplate }
        if (templates.any { it == null } || templates.toSet().size != templates.size) return null
        return templates.map { templateToRegex(it!!) }
    }

    private fun templateToRegex(template: String): Regex {
        val literalParts = template.split(Regex("%[vs]"))
        return Regex("^" + literalParts.joinToString(".*") { Regex.escape(it) } + "$")
    }

    private fun situationFor(regexes: List<Regex>?, message: String): Int? =
        regexes?.withIndex()?.firstOrNull { (_, regex) -> regex.matches(message) }?.let { it.index + 1 }
}
