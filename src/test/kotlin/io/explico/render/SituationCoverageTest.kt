/**
 * Unit tests for the per-situation example coverage finding (spec §14.77): which situations of a control no fixture matches, and ONLY when that can be claimed.
 * Hand-built domain objects and outcomes -- no opa; the real output is pinned by RealisticSituationCoverageAcceptanceIT.
 */
package io.explico.render

import io.explico.model.ElseBranch
import io.explico.model.Operand
import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RuleBody
import io.explico.model.RuleGroup
import io.explico.model.RuleMetadata
import io.explico.model.SourceRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SituationCoverageTest {

    private val fixture = Fixture("f", null, JsonObject(emptyMap()))

    private fun body(template: String? = "m", elseBranches: List<ElseBranch> = emptyList()) =
        RuleBody(emptyList(), null, template, SourceRef("f.rego", 1), "", elseBranches = elseBranches)

    private fun rule(bodies: Int, name: String = "deny", id: String? = "C-1", parameters: List<String> = emptyList(), split: Boolean = false, elseChain: Boolean = false) = RuleGroup(
        name, id?.let { RuleMetadata("T $it", "d", it, listOf("F")) }, null,
        List(bodies) { body(template = "m${it + 1}", elseBranches = if (elseChain && it == 0) listOf(ElseBranch(emptyList(), Operand.Literal("1"), SourceRef("f.rego", 2))) else emptyList()) },
        parameters = parameters, isSplitDefaultEntry = split,
    )

    // The message of situation N is "mN" (that body's template), as the real evaluation would produce it; an unattributed message matches no body.
    private fun hit(vararg labels: Int?) = WorkedExample(fixture, true, labels.map { if (it == null) "unknown" else "m$it" }, labels.toList())
    private fun miss() = WorkedExample(fixture, false, emptyList(), emptyList())

    @Nested
    @DisplayName("The pure function")
    inner class Pure {

        @Test
        @DisplayName("the situations no matched outcome is attributed to, ascending; first, middle and last")
        fun uncovered() {
            assertThat(SituationCoverage.uncovered(rule(3), listOf(hit(1), hit(3)))).containsExactly(2)
            assertThat(SituationCoverage.uncovered(rule(3), listOf(hit(2), hit(3)))).containsExactly(1)
            assertThat(SituationCoverage.uncovered(rule(3), listOf(hit(1), hit(2)))).containsExactly(3)
            assertThat(SituationCoverage.uncovered(rule(3), listOf(hit(1)))).containsExactly(2, 3)
        }

        @Test
        @DisplayName("everything covered, or two bodies with both covered by ONE fixture that produces two messages: nothing to report")
        fun allCovered() {
            assertThat(SituationCoverage.uncovered(rule(2), listOf(hit(1), hit(2)))).isNull()
            assertThat(SituationCoverage.uncovered(rule(2), listOf(hit(2, 1)))).isNull()
        }

        @Test
        @DisplayName("unmatched outcomes cover nothing; the boundary is two bodies, one body is never reported")
        fun boundary() {
            assertThat(SituationCoverage.uncovered(rule(2), listOf(hit(1), miss(), miss()))).containsExactly(2)
            assertThat(SituationCoverage.uncovered(rule(1), listOf(hit(1)))).isNull()
            assertThat(SituationCoverage.uncovered(rule(0), listOf(hit(1)))).describedAs("a rule with no body").isNull()
        }

        @Test
        @DisplayName("nothing matched at all is the 'No matching example' finding's business, not this one")
        fun noMatch() {
            assertThat(SituationCoverage.uncovered(rule(3), listOf(miss(), miss()))).isNull()
            assertThat(SituationCoverage.uncovered(rule(3), emptyList())).isNull()
        }

        @Test
        @DisplayName("not claimable: an unattributed message (null label), a matched outcome with no message at all (a boolean rule), in ANY matched outcome")
        fun notClaimable() {
            assertThat(SituationCoverage.uncovered(rule(2), listOf(hit(1), hit(null)))).isNull()
            assertThat(SituationCoverage.uncovered(rule(2), listOf(hit(1), WorkedExample(fixture, true, emptyList(), emptyList())))).isNull()
        }

        @Test
        @DisplayName("CHALLENGE: templates where one body's is a generalisation of another's ('%v needs review' / 'payment %v needs review') are ambiguous: a message of the second is attributed to the first, so nothing is claimed")
        fun overlappingTemplates() {
            fun overlapping() = RuleGroup(
                "deny", RuleMetadata("T", "d", "C-1", listOf("F")), null,
                listOf(
                    RuleBody(emptyList(), null, "%v needs review", SourceRef("f.rego", 1), ""),
                    RuleBody(emptyList(), null, "payment %v needs review", SourceRef("f.rego", 5), ""),
                ),
            )
            // What the real attribution produces for a body-2 message: the FIRST body whose template matches, i.e. situation 1.
            val ambiguous = WorkedExample(fixture, true, listOf("payment P-1 needs review"), listOf(1))

            assertThat(SituationCoverage.uncovered(overlapping(), listOf(ambiguous))).isNull()
        }

        @Test
        @DisplayName("a message that matches no body template is not claimable either, and clean non-overlapping templates still are")
        fun unmatchedMessageAndCleanTemplates() {
            assertThat(SituationCoverage.uncovered(rule(2), listOf(WorkedExample(fixture, true, listOf("something else"), listOf(1))))).isNull()
            assertThat(SituationCoverage.uncovered(rule(2), listOf(hit(1)))).containsExactly(2)
        }

        @Test
        @DisplayName("not claimable: a function, a split default entry, an else chain")
        fun neverFor() {
            assertThat(SituationCoverage.uncovered(rule(2, parameters = listOf("x")), listOf(hit(1)))).isNull()
            assertThat(SituationCoverage.uncovered(rule(2, split = true), listOf(hit(1)))).isNull()
            assertThat(SituationCoverage.uncovered(rule(2, elseChain = true), listOf(hit(1)))).isNull()
        }
    }

    @Nested
    @DisplayName("In the findings")
    inner class InFindings {

        private fun set(vararg rules: RuleGroup) = PolicySet(listOf(PolicyPackage("p", rules.toList(), listOf("f.rego"))))
        private fun examples(vararg byRule: Pair<String, List<WorkedExample>>): Map<String, Map<String, List<WorkedExample>>> = mapOf("p" to byRule.toMap())
        private fun gaps(set: PolicySet, ex: Map<String, Map<String, List<WorkedExample>>>?, groups: ControlGroups? = null) =
            Quality.findingsOf(set, groups, ex).situationsWithoutExample.map { it.ref.label to it.situations }

        @Test
        @DisplayName("controls only, with their uncovered situations, in index order; an id-less rule is never listed")
        fun controlsOnly() {
            val s = set(rule(3, "b", id = "C-B"), rule(2, "a", id = "C-A"), rule(3, "helper", id = null))
            val ex = examples("a" to listOf(hit(1)), "b" to listOf(hit(1), hit(3)), "helper" to listOf(hit(1)))

            assertThat(gaps(s, ex)).containsExactly("C-A" to listOf(2), "C-B" to listOf(2))
        }

        @Test
        @DisplayName("no fixtures supplied (null or empty): nothing, and the findings stay empty")
        fun notSupplied() {
            val s = set(rule(2))
            for (ex in listOf<Map<String, Map<String, List<WorkedExample>>>?>(null, emptyMap())) {
                assertThat(gaps(s, ex)).isEmpty()
                assertThat(Quality.findingsOf(s, null, ex).isEmpty()).isTrue()
            }
        }

        @Test
        @DisplayName("it makes the findings non-empty, so the section is shown, and it is not a gap")
        fun nonEmptyNotAGap() {
            val s = set(rule(2))
            val findings = Quality.findingsOf(s, null, examples("deny" to listOf(hit(1))))

            assertThat(findings.isEmpty()).isFalse()
            assertThat(Quality.gapDescriptions(findings, null, RequiredSet.of(RequiredSet.ALL.filter { it != "fixture" && it != "verdict" }, examplesSupplied = false))).isEmpty()
        }

        @Test
        @DisplayName("a grouped control (one id over several rules) is skipped")
        fun grouped() {
            val s = set(rule(2, "a", id = "C-G"), rule(2, "b", id = "C-G"))
            val ex = examples("a" to listOf(hit(1)), "b" to listOf(hit(1)))

            assertThat(gaps(s, ex, ControlGroups.of(s))).isEmpty()
        }

        @Test
        @DisplayName("CHALLENGE: two same-named cards whose messages cannot be attributed get no outcomes (forGroup), so neither is reported")
        fun ambiguousSiblings() {
            val s = set(rule(2, "deny", id = "C-1"), rule(2, "deny", id = "C-2"))

            assertThat(gaps(s, examples("deny" to listOf(hit(1))))).isEmpty()
        }

        @Test
        @DisplayName("a control already under 'No matching example' is not listed here too")
        fun noDoubleNoise() {
            val s = set(rule(3))
            val findings = Quality.findingsOf(s, null, examples("deny" to listOf(miss())))

            assertThat(findings.noMatchingExample).hasSize(1)
            assertThat(findings.situationsWithoutExample).isEmpty()
        }
    }

    @Nested
    @DisplayName("Worded and exported")
    inner class Worded {

        private val one = Quality.SituationGap(Quality.ControlRef("SC-1", "sc1", "sc-1"), listOf(2))
        private val two = Quality.SituationGap(Quality.ControlRef("SC-7", "sc7", "sc-7"), listOf(2, 3))

        @Test
        @DisplayName("the summary line is singular for one control and plural for more, and sits right after the 'no matching example' line")
        fun lines() {
            val noMatch = Quality.ControlRef("SC-4", "sc4", "sc-4")

            assertThat(Quality.findingLines(Quality.Findings(emptyList(), emptyList(), emptyList(), situationsWithoutExample = listOf(one)))).containsExactly("1 control has a situation no example matches")
            assertThat(Quality.findingLines(Quality.Findings(emptyList(), emptyList(), emptyList(), noMatchingExample = listOf(noMatch), situationsWithoutExample = listOf(one, two))))
                .containsExactly("1 control has no matching example", "2 controls have a situation no example matches")
        }

        private fun twoControls() = PolicySet(
            listOf(
                PolicyPackage("sc1", listOf(rule(3, id = "SC-1")), listOf("sc1.rego")),
                PolicyPackage("sc7", listOf(rule(3, id = "SC-7")), listOf("sc7.rego")),
            ),
        )

        private fun outcomes() = mapOf(
            "sc1" to mapOf("deny" to listOf(hit(1), hit(3))),
            "sc7" to mapOf("deny" to listOf(hit(1))),
        )

        @Test
        @DisplayName("the bullet: singular 'situation' for one number, 'situations' with ', ' for several, controls joined by '; ', N counts controls, after 'No matching example'")
        fun bullet() {
            val index = MarkdownRenderer.renderIndex(twoControls(), outcomes()).lines()

            assertThat(index).contains(
                "- **Situation without a matching example (2):** [SC-1](sc1.md#sc-1) situation 2; [SC-7](sc7.md#sc-7) situations 2, 3 — add a fixture that makes each of them match",
            )
        }

        @Test
        @DisplayName("without outcomes the bullet is not there")
        fun noBullet() {
            assertThat(MarkdownRenderer.renderIndex(twoControls(), emptyMap())).doesNotContain("Situation without a matching example")
        }

        @Test
        @DisplayName("the JSON entries: control label and situation numbers; an empty array when there are none")
        fun json() {
            fun quality(examples: Map<String, Map<String, List<WorkedExample>>>) =
                Json.parseToJsonElement(JsonExport.render(twoControls(), "d", examples, emptyMap(), null)).jsonObject.getValue("quality").jsonObject

            val entries = quality(outcomes()).getValue("situationsWithoutExample").jsonArray.map { it.jsonObject }
            assertThat(entries.map { it.getValue("control").jsonPrimitive.content }).containsExactly("SC-1", "SC-7")
            assertThat(entries[1].getValue("situations").jsonArray.map { it.jsonPrimitive.content.toInt() }).containsExactly(2, 3)
            assertThat(quality(emptyMap()).getValue("situationsWithoutExample").jsonArray).isEmpty()
        }
    }
}
