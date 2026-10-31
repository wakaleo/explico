/**
 * Unit tests for how an example change is worded and placed in the report (spec §14.74): the sub-bullet, the section, the JSON. Hand-built domain objects -- no opa;
 * the real report is pinned by RealisticFixtureVerdictDiffAcceptanceIT.
 */
package io.explico.diff

import io.explico.model.Condition
import io.explico.model.Operand
import io.explico.model.Operator
import io.explico.model.PathSegment
import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RuleBody
import io.explico.model.RuleGroup
import io.explico.model.RuleMetadata
import io.explico.model.SourceRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.IdentityHashMap

class ExampleReportTest {

    private fun rule(source: String = "deny contains msg if { true }", id: String = "C-1") = RuleGroup(
        "deny", RuleMetadata("Title $id", "d", id, emptyList()), null,
        listOf(RuleBody(listOf(Condition.Comparison(Operand.Path(listOf(PathSegment.Field("input"))), Operator.EQ, Operand.Literal("1"))), null, null, SourceRef("f.rego", 1), source)),
    )

    private fun pkg(path: String, rule: RuleGroup) = PolicyPackage(path, listOf(rule), listOf("f.rego"))
    private fun entry(category: DiffCategory, rule: RuleGroup, packagePath: String = "p") = DiffEntry(category, rule.metadata?.controlId, pkg(packagePath, rule), rule, pkg(packagePath, rule), rule)

    private fun index(vararg pairs: Pair<DiffEntry, List<ExampleChange>>) = ExampleChangeIndex(IdentityHashMap<DiffEntry, List<ExampleChange>>().also { m -> pairs.forEach { (e, c) -> m[e] = c } })
    private fun set(vararg entries: DiffEntry) = PolicySet(entries.map { pkg(it.newPackage!!.path, it.newRule!!) })

    @Nested
    @DisplayName("The sub-bullet")
    inner class Line {

        @Test
        @DisplayName("a changed verdict reads old -> new in the cards' words")
        fun flip() {
            assertThat(ChangeSummary.exampleLine(ExampleChange("C-1", "standing order", "✅ allowed", "❌ denied"))).isEqualTo("standing order: ✅ allowed -> ❌ denied")
        }

        @Test
        @DisplayName("the same word on both sides means only the message changed")
        fun messageOnly() {
            assertThat(ChangeSummary.exampleLine(ExampleChange("C-1", "to KP", "❌ denied", "❌ denied"))).isEqualTo("to KP: still ❌ denied, message changed")
        }
    }

    @Nested
    @DisplayName("The section")
    inner class Section {

        @Test
        @DisplayName("supplied but nothing moved: the heading and one sentence, no bullet; not supplied (null): no heading at all")
        fun suppliedAndNot() {
            val e = entry(DiffCategory.LOGIC_CHANGED, rule())
            val none = DiffRenderer.render(listOf(e), set(e), set(e), exampleChanges = index())
            val absent = DiffRenderer.render(listOf(e), set(e), set(e))

            assertThat(none).contains("## Behaviour on your examples\n\nNo example changed outcome.\n")
            assertThat(absent).doesNotContain("Behaviour on your examples")
        }

        @Test
        @DisplayName("the section appears even when no control changed (nothing else follows the Summary)")
        fun noChangedControls() {
            val e = entry(DiffCategory.UNCHANGED, rule())
            val markdown = DiffRenderer.render(listOf(e), set(e), set(e), exampleChanges = index())

            assertThat(markdown).contains("## Behaviour on your examples").contains("No example changed outcome.").doesNotContain("## What changed")
        }

        @Test
        @DisplayName("an affected control: its linked label (as What changed, without the suffix) and one line per fixture; an unaffected changed control is not listed")
        fun affected() {
            val a = entry(DiffCategory.LOGIC_CHANGED, rule(id = "A-1"), "a")
            val b = entry(DiffCategory.LOGIC_CHANGED, rule(id = "B-1"), "b")
            val markdown = DiffRenderer.render(
                listOf(a, b), set(a, b), set(a, b),
                exampleChanges = index(a to listOf(ExampleChange("A-1", "f1", "✅ allowed", "❌ denied"), ExampleChange("A-1", "f2", "❌ denied", "❌ denied"))),
            )

            assertThat(markdown).contains(
                "- [A-1 — Title A-1](#a-1)\n  - f1: ✅ allowed -> ❌ denied\n  - f2: still ❌ denied, message changed\n",
            )
            assertThat(markdown.substringAfter("## Behaviour on your examples").substringBefore("## Changes")).doesNotContain("B-1").doesNotContain("No example changed outcome.")
        }

        @Test
        @DisplayName("the section follows What changed and precedes Changes")
        fun placement() {
            val e = entry(DiffCategory.LOGIC_CHANGED, rule())
            val lines = DiffRenderer.render(listOf(e), set(e), set(e), exampleChanges = index()).lines()

            assertThat(lines.indexOf("## Behaviour on your examples")).isGreaterThan(lines.indexOf("## What changed")).isLessThan(lines.indexOf("## Changes"))
        }
    }

    @Nested
    @DisplayName("The JSON")
    inner class Json_ {

        @Test
        @DisplayName("examples on every change when supplied (empty when none), absent when not; the same values as the Markdown")
        fun json() {
            val a = entry(DiffCategory.LOGIC_CHANGED, rule(id = "A-1"), "a")
            val b = entry(DiffCategory.LOGIC_CHANGED, rule(id = "B-1"), "b")
            val withIndex = Json.parseToJsonElement(DiffJson.render(listOf(a, b), exampleChanges = index(a to listOf(ExampleChange("A-1", "f1", "✅ allowed", "❌ denied"))))).jsonObject.getValue("changes").jsonArray.map { it.jsonObject }
            val without = Json.parseToJsonElement(DiffJson.render(listOf(a, b))).jsonObject.getValue("changes").jsonArray.map { it.jsonObject }

            assertThat(withIndex[0].getValue("examples").jsonArray.single().jsonObject.mapValues { it.value.jsonPrimitive.content })
                .isEqualTo(mapOf("fixture" to "f1", "old" to "✅ allowed", "new" to "❌ denied"))
            assertThat(withIndex[1].getValue("examples").jsonArray).isEmpty()
            assertThat(without).allSatisfy { assertThat(it).doesNotContainKey("examples") }
        }
    }

    @Nested
    @DisplayName("What was compared (spec §14.74)")
    inner class Comparison {

        private fun render(c: ExampleComparison): String {
            val e = entry(DiffCategory.LOGIC_CHANGED, rule())
            return DiffRenderer.render(listOf(e), set(e), set(e), exampleChanges = ExampleChangeIndex(IdentityHashMap(), c))
        }

        @Test
        @DisplayName("everything compared: the plain sentence and an exact count line")
        fun allCompared() {
            val markdown = render(ExampleComparison(compared = 5, total = 5, functions = 0, unattributable = 0, fixturesOnOneVersion = 0))

            assertThat(markdown).contains("No example changed outcome.\n\n*Compared 5 of 5 controls present in both versions.*\n")
        }

        @Test
        @DisplayName("some controls not compared: the sentence only claims the compared ones, and the line says how many and why")
        fun someSkipped() {
            val markdown = render(ExampleComparison(compared = 3, total = 6, functions = 2, unattributable = 1, fixturesOnOneVersion = 0))

            assertThat(markdown).contains("No compared example changed outcome.")
            assertThat(markdown).contains("*Compared 3 of 6 controls present in both versions; not compared: 2 function(s), 1 with no examples attributable to the rule.*")
        }

        @Test
        @DisplayName("a fixture one version could not evaluate also withdraws the plain sentence, and is counted")
        fun fixtureOnOneVersion() {
            val markdown = render(ExampleComparison(compared = 2, total = 2, functions = 0, unattributable = 0, fixturesOnOneVersion = 1))

            assertThat(markdown).contains("No compared example changed outcome.")
            assertThat(markdown).contains("*Compared 2 of 2 controls present in both versions; 1 fixture(s) evaluated on one version only (see the warnings on stderr).*")
        }

        @Test
        @DisplayName("nothing could be compared: it says so instead of 'no example changed'")
        fun nothingCompared() {
            val markdown = render(ExampleComparison(compared = 0, total = 2, functions = 2, unattributable = 0, fixturesOnOneVersion = 0))

            assertThat(markdown).contains("No example could be compared.").doesNotContain("No example changed outcome.").doesNotContain("No compared example")
        }

        @Test
        @DisplayName("no control on both sides: no count line at all (there is nothing to count)")
        fun noControls() {
            assertThat(render(ExampleComparison(0, 0, 0, 0, 0))).contains("No example changed outcome.").doesNotContain("*Compared")
        }

        @Test
        @DisplayName("the JSON carries the same counts as an object next to the changes, only when examples were supplied")
        fun json() {
            val e = entry(DiffCategory.LOGIC_CHANGED, rule())
            val with = Json.parseToJsonElement(DiffJson.render(listOf(e), exampleChanges = ExampleChangeIndex(IdentityHashMap(), ExampleComparison(3, 6, 2, 1, 4)))).jsonObject
            val without = Json.parseToJsonElement(DiffJson.render(listOf(e))).jsonObject

            assertThat(with.getValue("exampleComparison").jsonObject.mapValues { it.value.jsonPrimitive.content })
                .isEqualTo(mapOf("compared" to "3", "total" to "6", "functions" to "2", "unattributable" to "1", "fixturesOnOneVersion" to "4"))
            assertThat(without).doesNotContainKey("exampleComparison")
        }
    }
}
