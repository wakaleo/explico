/**
 * Spec §14.63: the two documentation-quality hints -- a control no worked example matches, and a control whose evaluated value is a raw JSON object with no
 * declared verdict. Hand-built domain objects and outcomes -- no opa; the real index output is pinned by RealisticQualityHintsAcceptanceIT.
 */
package io.explico.render

import io.explico.model.DeclaredProjection
import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RuleBody
import io.explico.model.RuleGroup
import io.explico.model.RuleMetadata
import io.explico.model.SourceRef
import io.explico.model.VerdictProjection
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class QualityHintsTest {

    private val fixture = Fixture("f", null, JsonObject(emptyMap()))
    private val body = RuleBody(emptyList(), null, null, SourceRef("f.rego", 1), "")

    private fun rule(
        name: String, id: String? = "C-$name", projection: DeclaredProjection? = null, parameters: List<String> = emptyList(), split: Boolean = false,
    ) = RuleGroup(
        name, id?.let { RuleMetadata("T $name", "d", it, listOf("F"), projection = projection) }, null, if (split) emptyList() else listOf(body),
        parameters = parameters, isSplitDefaultEntry = split,
    )

    private fun set(vararg rules: RuleGroup) = PolicySet(listOf(PolicyPackage("p", rules.toList(), listOf("f.rego"))))

    private fun outcome(matched: Boolean, value: JsonElement? = null) = WorkedExample(fixture, matched, emptyList(), emptyList(), value)

    private fun examples(vararg byRule: Pair<String, List<WorkedExample>>): Map<String, Map<String, List<WorkedExample>>> = mapOf("p" to byRule.toMap())

    private val decision: JsonElement = buildJsonObject { put("compliant", true); put("commentary", "x") }

    private fun noMatch(set: PolicySet, ex: Map<String, Map<String, List<WorkedExample>>>?, groups: ControlGroups? = null) =
        Quality.findingsOf(set, groups, ex).noMatchingExample.map { it.label }

    private fun raw(set: PolicySet, ex: Map<String, Map<String, List<WorkedExample>>>?, groups: ControlGroups? = null) =
        Quality.findingsOf(set, groups, ex).rawDecisionObject.map { it.label }

    @Nested
    @DisplayName("Only when example fixtures were supplied")
    inner class WhenSupplied {

        @Test
        @DisplayName("without examples (null, or an empty map) neither list is filled, and the findings stay empty")
        fun noExamples() {
            val s = set(rule("a"))
            for (ex in listOf<Map<String, Map<String, List<WorkedExample>>>?>(null, emptyMap())) {
                val f = Quality.findingsOf(s, null, ex)
                assertThat(f.noMatchingExample).isEmpty()
                assertThat(f.rawDecisionObject).isEmpty()
                assertThat(f.isEmpty()).isTrue()
            }
        }

        @Test
        @DisplayName("either new list makes the findings non-empty, so the section is shown")
        fun isEmptyCountsTheNewLists() {
            val s = set(rule("a"))
            assertThat(Quality.findingsOf(s, null, examples("a" to listOf(outcome(false)))).isEmpty()).isFalse()
            assertThat(Quality.findingsOf(s, null, examples("a" to listOf(outcome(true, decision)))).isEmpty()).isFalse()
        }
    }

    @Nested
    @DisplayName("No matching example")
    inner class NoMatchingExample {

        @Test
        @DisplayName("a control with a matched outcome is not listed; one whose outcomes are all not-matched is")
        fun matchedOrNot() {
            val s = set(rule("a"), rule("b"))
            assertThat(noMatch(s, examples("a" to listOf(outcome(false), outcome(true)), "b" to listOf(outcome(false))))).containsExactly("C-b")
        }

        @Test
        @DisplayName("CHALLENGE: a rule absent from the example map (fixtures were supplied) has no outcomes, so it is listed")
        fun absentRule() {
            assertThat(noMatch(set(rule("a"), rule("b")), examples("a" to listOf(outcome(true))))).containsExactly("C-b")
        }

        @Test
        @DisplayName("CHALLENGE: fixtures were supplied but the control's whole package is missing from the example map: no outcomes, so it is listed")
        fun absentPackage() {
            assertThat(noMatch(set(rule("a")), mapOf("other" to mapOf("a" to listOf(outcome(true)))))).containsExactly("C-a")
        }

        @Test
        @DisplayName("CHALLENGE: a name shared by several cards whose messages cannot be attributed gets no examples, so each card is listed")
        fun ambiguousSharedName() {
            val s = set(rule("deny", id = "C-1"), rule("deny", id = "C-2"))
            assertThat(noMatch(s, examples("deny" to listOf(outcome(true))))).containsExactly("C-1", "C-2")
        }

        @Test
        @DisplayName("a function, an id-less rule and a split-default entry are never listed")
        fun neverListed() {
            val s = set(rule("f", parameters = listOf("x")), rule("helper", id = null), rule("d", split = true))
            assertThat(noMatch(s, examples("f" to listOf(outcome(false)), "helper" to listOf(outcome(false))))).isEmpty()
        }

        @Test
        @DisplayName("a grouped control is ONE entry, matched if any of its rules has a matched outcome")
        fun grouped() {
            val matchedInOne = set(rule("a", id = "C-G"), rule("b", id = "C-G"))
            assertThat(noMatch(matchedInOne, examples("a" to listOf(outcome(true)), "b" to listOf(outcome(false))), ControlGroups.of(matchedInOne))).isEmpty()
            val matchedInNone = set(rule("a", id = "C-G"), rule("b", id = "C-G"))
            assertThat(noMatch(matchedInNone, examples("a" to listOf(outcome(false)), "b" to listOf(outcome(false))), ControlGroups.of(matchedInNone))).containsExactly("C-G")
        }
    }

    @Nested
    @DisplayName("Raw decision object")
    inner class RawDecisionObject {

        @Test
        @DisplayName("an object value with no declared verdict is listed, even when it appears for only one of several fixtures")
        fun listed() {
            assertThat(raw(set(rule("a")), examples("a" to listOf(outcome(false), outcome(true, decision))))).containsExactly("C-a")
        }

        @Test
        @DisplayName("a declared verdict (any other declared projection is irrelevant) keeps it out; a projection without a verdict does not")
        fun declaredVerdict() {
            val declared = DeclaredProjection(verdict = VerdictProjection("compliant", "commentary"))
            val onlyMask = DeclaredProjection(mask = listOf("a"))
            val s = set(rule("a", projection = declared), rule("b", projection = onlyMask))
            assertThat(raw(s, examples("a" to listOf(outcome(true, decision)), "b" to listOf(outcome(true, decision))))).containsExactly("C-b")
        }

        @Test
        @DisplayName("CHALLENGE: a wrongly shaped verdict declaration is not carried by the model (it warns and is ignored), so the examples DO show raw JSON and the hint is accurate")
        fun malformedVerdictIsUndeclared() {
            assertThat(raw(set(rule("a", projection = DeclaredProjection())), examples("a" to listOf(outcome(true, decision))))).containsExactly("C-a")
        }

        @Test
        @DisplayName("an array, a scalar, a null, undefined and an unmatched value are not decision objects")
        fun notObjects() {
            val s = set(rule("a"), rule("b"), rule("c"), rule("d"), rule("e"))
            val ex = examples(
                "a" to listOf(outcome(true, JsonArray(listOf(JsonPrimitive("m"))))),
                "b" to listOf(outcome(true, JsonPrimitive(true))),
                "c" to listOf(outcome(true, JsonNull)),
                "d" to listOf(outcome(false, null)),
                "e" to listOf(outcome(true, JsonPrimitive("text"))),
            )
            assertThat(raw(s, ex)).isEmpty()
        }

        @Test
        @DisplayName("CHALLENGE: a partial-object rule (every value true) and an empty object are not decision objects; one false value makes it one")
        fun partialObjects() {
            val allTrue = buildJsonObject { put("a", true); put("b", true) }
            val mixed = buildJsonObject { put("a", true); put("b", false) }
            val s = set(rule("a"), rule("b"), rule("c"))
            val ex = examples("a" to listOf(outcome(true, allTrue)), "b" to listOf(outcome(true, JsonObject(emptyMap()))), "c" to listOf(outcome(true, mixed)))
            assertThat(raw(s, ex)).containsExactly("C-c")
        }

        @Test
        @DisplayName("a function, an id-less rule and a split-default entry are never listed")
        fun neverListed() {
            val s = set(rule("f", parameters = listOf("x")), rule("helper", id = null), rule("d", split = true))
            assertThat(raw(s, examples("f" to listOf(outcome(true, decision)), "helper" to listOf(outcome(true, decision))))).isEmpty()
        }

        @Test
        @DisplayName("a grouped control is listed ONCE, when any of its rules qualifies; a member that declares a verdict does not hide a sibling that does not")
        fun grouped() {
            val declared = DeclaredProjection(verdict = VerdictProjection("compliant"))
            val s = set(rule("a", id = "C-G", projection = declared), rule("b", id = "C-G"))
            val ex = examples("a" to listOf(outcome(true, decision)), "b" to listOf(outcome(true, decision)))
            assertThat(raw(s, ex, ControlGroups.of(s))).containsExactly("C-G")
        }

        @Test
        @DisplayName("CHALLENGE: a name shared by several cards loses its value, so none of them is listed (never a guess)")
        fun sharedNameHasNoValue() {
            val s = set(rule("deny", id = "C-1"), rule("deny", id = "C-2"))
            assertThat(raw(s, examples("deny" to listOf(outcome(true, decision))))).isEmpty()
        }
    }
}
