/**
 * Orchestrates generated worked examples (spec §14.39, `--generate-examples`): for each rule whose author fixtures leave a
 * gap, derive candidate inputs with [InputSynthesizer], VERIFY each one by evaluating the package (the real `opa eval` in
 * production, an injected fake in unit tests) and keep it only when the verdict agrees with what the candidate is for.
 *
 * Verdicts and messages are computed by the same code that produces the author's own worked examples
 * ([WorkedExamples.evaluatePackage] / [WorkedExamples.forGroup]) -- there is deliberately no second verdict implementation.
 * So a generated example can be unhelpful or absent, but never wrong: what is shown is what OPA said.
 */
package io.explico.generate

import io.explico.model.Condition
import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RuleGroup
import io.explico.render.Fixture
import io.explico.render.WorkedExample
import io.explico.render.WorkedExamples
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** One verified generated example. Its display name is derived from these fields by the renderer, never invented here. */
internal data class GeneratedExample(
    val kind: Kind,
    /** Which body of the rule the candidate was derived from (0-based). */
    val bodyIndex: Int,
    /** For a near-miss, the index (in that body's conditions) of the single condition that was broken. */
    val brokenConditionIndex: Int?,
    /** The verdict, messages and situation labels as OPA reported them for this rule alone. */
    val outcome: WorkedExample,
) {
    enum class Kind { MATCHING, NEAR_MISS }
}

/** The verified examples for ONE rule card. */
internal data class GeneratedExamples(val matching: List<GeneratedExample>, val nearMisses: List<GeneratedExample>)

/**
 * [byPackage] maps package path -> index into that package's `rules` -> its examples; only cards that received at least one
 * example appear. [rulesConsidered] counts the cards that could take part (a body, not a function, not a split default).
 */
internal data class GenerationResult(
    val byPackage: Map<String, Map<Int, GeneratedExamples>>,
    val rulesConsidered: Int,
    val rulesWithExamples: Int,
)

internal object ExampleGenerator {
    const val MAX_MATCHING = 3
    const val MAX_NEAR_MISSES = 2

    private class Candidate(val kind: GeneratedExample.Kind, val bodyIndex: Int, val brokenConditionIndex: Int?, val fixture: Fixture)

    /**
     * @param authorOutcomes the author's worked-example outcomes, by package path then rule name (as evaluated for the render).
     * @param evalPackage evaluates one fixture against a package, returning that package's value (`opa eval data.<package>`).
     */
    fun generate(
        policySet: PolicySet,
        authorOutcomes: Map<String, Map<String, List<WorkedExample>>>,
        warn: (String) -> Unit = { System.err.println(it) },
        evalPackage: (packagePath: String, fixture: Fixture) -> JsonElement,
    ): GenerationResult {
        val byPackage = LinkedHashMap<String, Map<Int, GeneratedExamples>>()
        var considered = 0
        var withExamples = 0
        for (pkg in policySet.packages) {
            val perRule = generateForPackage(pkg, authorOutcomes[pkg.path].orEmpty(), warn, evalPackage)
            considered += pkg.rules.count { takesPart(it) }
            withExamples += perRule.size
            if (perRule.isNotEmpty()) byPackage[pkg.path] = perRule
        }
        return GenerationResult(byPackage, considered, withExamples)
    }

    private fun takesPart(rule: RuleGroup) = !rule.isSplitDefaultEntry && rule.parameters.isEmpty() && rule.bodies.isNotEmpty()

    private fun generateForPackage(
        pkg: PolicyPackage,
        authorByRule: Map<String, List<WorkedExample>>,
        warn: (String) -> Unit,
        evalPackage: (String, Fixture) -> JsonElement,
    ): Map<Int, GeneratedExamples> {
        // 1. Candidate inputs, per rule index, in a fixed order (bodies, then conditions).
        val candidatesByRule = LinkedHashMap<Int, List<Candidate>>()
        val fixtureByInput = LinkedHashMap<JsonObject, Fixture>() // one evaluation per distinct input
        pkg.rules.forEachIndexed { index, rule ->
            if (!takesPart(rule)) return@forEachIndexed
            val authored = WorkedExamples.forGroup(rule, pkg, authorByRule[rule.name].orEmpty())
            val needMatching = authored.none { it.matched }
            val needNearMisses = authored.none { !it.matched }
            val candidates = mutableListOf<Candidate>()
            fun fixtureFor(input: JsonObject) = fixtureByInput.getOrPut(input) { Fixture("generated-${fixtureByInput.size + 1}", null, input) }
            rule.bodies.forEachIndexed { bodyIndex, body ->
                if (needMatching) {
                    (InputSynthesizer.synthesize(body) as? Synthesis.Solved)?.let {
                        candidates += Candidate(GeneratedExample.Kind.MATCHING, bodyIndex, null, fixtureFor(it.input))
                    }
                }
                if (needNearMisses) {
                    body.conditions.forEachIndexed { conditionIndex, condition ->
                        if (condition is Condition.SomeIn) return@forEachIndexed
                        (InputSynthesizer.synthesize(body, violate = conditionIndex) as? Synthesis.Solved)?.let {
                            candidates += Candidate(GeneratedExample.Kind.NEAR_MISS, bodyIndex, conditionIndex, fixtureFor(it.input))
                        }
                    }
                }
            }
            if (candidates.isNotEmpty()) candidatesByRule[index] = candidates
        }
        if (fixtureByInput.isEmpty()) return emptyMap()

        // 2. Verify: one evaluation per distinct input, through the same code path as the author's examples.
        val outcomes = WorkedExamples.evaluatePackage(
            fixtureByInput.values.toList(),
            pkg,
            warn = { warn("explico: warning: a generated example was discarded -- $it") },
        ) { fixture -> evalPackage(pkg.path, fixture) }

        // 3. Keep only candidates whose verdict agrees with their purpose; dedupe; cap.
        val result = LinkedHashMap<Int, GeneratedExamples>()
        for ((index, candidates) in candidatesByRule) {
            val rule = pkg.rules[index]
            val seenInputs = HashSet<JsonObject>()
            val matching = mutableListOf<GeneratedExample>()
            val nearMisses = mutableListOf<GeneratedExample>()
            for (candidate in candidates) {
                val outcome = outcomes[rule.name].orEmpty().firstOrNull { it.fixture.name == candidate.fixture.name } ?: continue
                val scoped = WorkedExamples.forGroup(rule, pkg, listOf(outcome)).firstOrNull() ?: continue
                val agrees = if (candidate.kind == GeneratedExample.Kind.MATCHING) scoped.matched else !scoped.matched
                if (!agrees || !seenInputs.add(candidate.fixture.input)) continue
                val example = GeneratedExample(candidate.kind, candidate.bodyIndex, candidate.brokenConditionIndex, scoped)
                if (candidate.kind == GeneratedExample.Kind.MATCHING) { if (matching.size < MAX_MATCHING) matching += example }
                else if (nearMisses.size < MAX_NEAR_MISSES) nearMisses += example
            }
            if (matching.isNotEmpty() || nearMisses.isNotEmpty()) result[index] = GeneratedExamples(matching, nearMisses)
        }
        return result
    }
}
