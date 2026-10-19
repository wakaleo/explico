/**
 * Unit tests for the orchestration of generated worked examples: candidate inputs come from the solver, are VERIFIED by an
 * injected evaluator (standing in for `opa eval`), and are kept only when the verdict agrees; gap filling, caps, de-duplication,
 * per-group attribution and failure handling are pinned here without opa.
 */
package io.explico.generate

import io.explico.model.Condition
import io.explico.model.Operand
import io.explico.model.Operator
import io.explico.model.PathSegment
import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RuleBody
import io.explico.model.RuleGroup
import io.explico.model.SourceRef
import io.explico.opa.OpaInvocationException
import io.explico.render.Fixture
import io.explico.render.WorkedExample
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ExampleGeneratorTest {

    // ---- builders --------------------------------------------------------------------------------------------------
    private fun path(vararg fields: String) = Operand.Path(listOf<PathSegment>(PathSegment.Field("input")) + fields.map { PathSegment.Field(it) })
    private fun eq(field: String, literal: String) = Condition.Comparison(path(field), Operator.EQ, Operand.Literal(literal))

    private fun body(vararg conditions: Condition, template: String? = null) =
        RuleBody(conditions.toList(), null, template, SourceRef("f.rego", 1), "")

    private fun group(name: String, vararg bodies: RuleBody, parameters: List<String> = emptyList(), split: Boolean = false) =
        RuleGroup(name, null, null, bodies.toList(), parameters = parameters, isSplitDefaultEntry = split)

    private fun policy(vararg groups: RuleGroup) = PolicySet(listOf(PolicyPackage("p", groups.toList(), listOf("f.rego"))))

    private fun input(text: String): JsonObject = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject

    private fun author(matched: Boolean) = WorkedExample(Fixture("author", null, buildJsonObject { }), matched, if (matched) listOf("m") else emptyList(), if (matched) listOf(null) else emptyList())

    /** A fake `opa eval`: each rule name maps to the messages it produces for an input (empty list = rule undefined/not matching). */
    private fun evaluator(vararg rules: Pair<String, (JsonObject) -> List<String>>): (String, Fixture) -> JsonElement = { _, fixture ->
        JsonObject(rules.associate { (name, f) -> name to JsonArray(f(fixture.input).map { JsonPrimitive(it) }) })
    }

    private fun xIsOne(message: String = "m"): (JsonObject) -> List<String> =
        { i -> if (i["x"]?.jsonPrimitive?.content == "1") listOf(message) else emptyList() }

    private fun generate(
        set: PolicySet,
        authors: Map<String, Map<String, List<WorkedExample>>> = emptyMap(),
        warnings: MutableList<String> = mutableListOf(),
        eval: (String, Fixture) -> JsonElement,
    ) = ExampleGenerator.generate(set, authors, warn = { warnings += it }, evalPackage = eval)

    private fun GenerationResult.forRule(index: Int = 0): GeneratedExamples? = byPackage["p"]?.get(index)

    // ---- verification -----------------------------------------------------------------------------------------------
    @Nested
    inner class Verification {

        @Test
        fun aMatchingCandidateAndANearMissAreKeptWhenTheEvaluatorAgrees() {
            val result = generate(policy(group("r", body(eq("x", "1")))), eval = evaluator("r" to xIsOne()))
            val generated = result.forRule()!!
            assertThat(generated.matching).hasSize(1)
            assertThat(generated.matching.single().outcome.matched).isTrue()
            assertThat(generated.matching.single().outcome.messages).containsExactly("m")
            assertThat(generated.matching.single().outcome.fixture.input).isEqualTo(input("""{"x": 1}"""))
            assertThat(generated.nearMisses).hasSize(1)
            assertThat(generated.nearMisses.single().outcome.matched).isFalse()
            assertThat(generated.nearMisses.single().brokenConditionIndex).isEqualTo(0)
            assertThat(generated.nearMisses.single().outcome.fixture.input).isEqualTo(input("""{"x": 2}"""))
        }

        @Test
        fun aMatchingCandidateTheEvaluatorRejectsIsDiscarded() {
            val neverMatches = evaluator("r" to { _: JsonObject -> emptyList<String>() })
            val generated = generate(policy(group("r", body(eq("x", "1")))), eval = neverMatches).forRule()!!
            assertThat(generated.matching).isEmpty()
            assertThat(generated.nearMisses).hasSize(1) // "does not match" is still true of the near-miss
        }

        @Test
        fun aNearMissThatStillMatchesIsDiscarded() {
            val alwaysMatches = evaluator("r" to { _: JsonObject -> listOf("m") })
            val generated = generate(policy(group("r", body(eq("x", "1")))), eval = alwaysMatches).forRule()!!
            assertThat(generated.nearMisses).isEmpty()
            assertThat(generated.matching).hasSize(1)
        }

        @Test
        fun anEvaluationFailureDiscardsTheCandidateWithAWarningAndNeverAborts() {
            val warnings = mutableListOf<String>()
            val failing: (String, Fixture) -> JsonElement = { _, _ -> throw OpaInvocationException("boom: rego_type_error") }
            val result = generate(policy(group("r", body(eq("x", "1")))), warnings = warnings, eval = failing)
            assertThat(result.forRule()).isNull()
            assertThat(warnings).isNotEmpty().allSatisfy { assertThat(it).contains("boom: rego_type_error") }
        }
    }

    // ---- gap filling -----------------------------------------------------------------------------------------------
    @Nested
    inner class GapFilling {
        private val set = policy(group("r", body(eq("x", "1"))))
        private val eval = evaluator("r" to xIsOne())

        private fun with(vararg outcomes: WorkedExample) = generate(set, mapOf("p" to mapOf("r" to outcomes.toList())), eval = eval).forRule()

        @Test
        fun noAuthorExamplesGeneratesBothKinds() {
            val generated = with()!!
            assertThat(generated.matching).hasSize(1)
            assertThat(generated.nearMisses).hasSize(1)
        }

        @Test
        fun onlyAMatchingAuthorExampleGeneratesOnlyNearMisses() {
            val generated = with(author(true))!!
            assertThat(generated.matching).isEmpty()
            assertThat(generated.nearMisses).hasSize(1)
        }

        @Test
        fun onlyANonMatchingAuthorExampleGeneratesOnlyMatching() {
            val generated = with(author(false))!!
            assertThat(generated.matching).hasSize(1)
            assertThat(generated.nearMisses).isEmpty()
        }

        @Test
        fun bothAuthorKindsGenerateNothingAtAll() {
            assertThat(with(author(true), author(false))).isNull()
        }
    }

    // ---- caps and dedupe -------------------------------------------------------------------------------------------
    @Nested
    inner class CapsAndDedupe {

        @Test
        fun atMostThreeMatchingExamplesOnePerBody() {
            val bodies = (1..5).map { body(eq("x$it", "1")) }.toTypedArray()
            val eval = evaluator("r" to { i: JsonObject -> if (i.keys.any { it.startsWith("x") && i[it]?.jsonPrimitive?.content == "1" }) listOf("m") else emptyList() })
            val generated = generate(policy(group("r", *bodies)), eval = eval).forRule()!!
            assertThat(generated.matching).hasSize(3)
            assertThat(generated.matching.map { it.bodyIndex }).containsExactly(0, 1, 2)
        }

        @Test
        fun atMostTwoNearMisses() {
            val one = body(eq("a", "1"), eq("b", "1"), eq("c", "1"))
            val eval = evaluator("r" to { i: JsonObject -> if (listOf("a", "b", "c").all { i[it]?.jsonPrimitive?.content == "1" }) listOf("m") else emptyList() })
            val generated = generate(policy(group("r", one)), eval = eval).forRule()!!
            assertThat(generated.nearMisses).hasSize(2)
            assertThat(generated.nearMisses.map { it.brokenConditionIndex }).containsExactly(0, 1)
        }

        @Test
        fun identicalCandidateInputsAreKeptOnce() {
            val twins = arrayOf(body(eq("x", "1")), body(eq("x", "1")))
            val generated = generate(policy(group("r", *twins)), eval = evaluator("r" to xIsOne())).forRule()!!
            assertThat(generated.matching).hasSize(1)
        }

        @Test
        fun theResultIsDeterministic() {
            val set = policy(group("r", body(eq("x", "1"), eq("y", "2"))), group("s", body(eq("x", "1"))))
            val eval = evaluator("r" to xIsOne(), "s" to xIsOne())
            assertThat(generate(set, eval = eval)).isEqualTo(generate(set, eval = eval))
        }
    }

    // ---- which rules take part -------------------------------------------------------------------------------------
    @Nested
    inner class WhichRules {

        @Test
        fun aBodyTheSolverCannotModelGetsNothingAndIsStillCountedAsConsidered() {
            val unsolvable = body(Condition.RuleReference("p", "other", false))
            val result = generate(policy(group("r", body(eq("x", "1"))), group("u", unsolvable)), eval = evaluator("r" to xIsOne(), "u" to xIsOne()))
            assertThat(result.forRule(1)).isNull()
            assertThat(result.rulesConsidered).isEqualTo(2)
            assertThat(result.rulesWithExamples).isEqualTo(1)
        }

        @Test
        fun functionsDefaultOnlyRulesAndSplitDefaultEntriesAreSkipped() {
            val set = policy(
                group("fn", body(eq("x", "1")), parameters = listOf("a")),
                group("defaultOnly"),
                group("split", split = true),
            )
            val result = generate(set, eval = evaluator())
            assertThat(result.byPackage).isEmpty()
            assertThat(result.rulesConsidered).isEqualTo(0)
        }

        @Test
        fun aBodyWithNoConditionsGetsAMatchingExampleOnlyWhenTheRuleReallyMatchesAnEmptyInput() {
            val always = generate(policy(group("r", body())), eval = evaluator("r" to { _: JsonObject -> listOf("m") })).forRule()!!
            assertThat(always.matching).hasSize(1)
            assertThat(always.matching.single().outcome.fixture.input).isEqualTo(input("{}"))
            assertThat(always.nearMisses).isEmpty() // nothing to break

            val never = generate(policy(group("r", body())), eval = evaluator("r" to { _: JsonObject -> emptyList<String>() }))
            assertThat(never.forRule()).isNull()
        }

        @Test
        fun aGeneratedCandidateIsAttributedToTheGroupItCameFromNeverTheWholeName() {
            // Two same-named groups with distinct message templates (as split by differing METADATA).
            val a = group("r", body(eq("x", "1"), template = "from A"))
            val b = group("r", body(eq("y", "1"), template = "from B"))
            val rule: (JsonObject) -> List<String> = { i ->
                buildList {
                    if (i["x"]?.jsonPrimitive?.content == "1") add("from A")
                    if (i["y"]?.jsonPrimitive?.content == "1") add("from B")
                }
            }
            val result = generate(policy(a, b), eval = evaluator("r" to rule))
            val forA = result.forRule(0)!!
            val forB = result.forRule(1)!!
            assertThat(forA.matching.single().outcome.messages).containsExactly("from A")
            assertThat(forB.matching.single().outcome.messages).containsExactly("from B")
            assertThat(forA.matching.single().outcome.fixture.input).isEqualTo(input("""{"x": 1}"""))
            assertThat(forB.matching.single().outcome.fixture.input).isEqualTo(input("""{"y": 1}"""))
        }
    }
}
