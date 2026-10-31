/**
 * Unit tests for how a data change is worded and placed (spec §14.73): the sub-bullet, the JSON detail, the disclaimer line and the source-diff note.
 * Hand-built domain objects -- no opa; the real report is pinned by RealisticDataAwareDiffAcceptanceIT.
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.IdentityHashMap

class DataChangeReportTest {

    private val limits = Operand.Path(listOf(PathSegment.Field("data"), PathSegment.Field("limits"), PathSegment.Field("single_approver_minor")))
    private val countries = Operand.Path(listOf(PathSegment.Field("data"), PathSegment.Field("high_risk_countries")))

    private fun rule(source: String = "deny contains msg if { true }") = RuleGroup(
        "deny", RuleMetadata("T", "d", "C-1", emptyList()), null,
        listOf(RuleBody(listOf(Condition.Comparison(limits, Operator.EQ, Operand.Literal("1"))), null, null, SourceRef("f.rego", 1), source)),
    )

    private fun pkg(rule: RuleGroup) = PolicyPackage("p", listOf(rule), listOf("f.rego"))
    private fun entry(rule: RuleGroup = rule()) = DiffEntry(DiffCategory.LOGIC_CHANGED, "C-1", pkg(rule), rule, pkg(rule), rule)

    private fun indexOf(entry: DiffEntry, vararg changes: DataChange, raised: Boolean = true) =
        DataChangeIndex(IdentityHashMap<DiffEntry, List<DataChange>>().also { it[entry] = changes.toList() }, if (raised) setOf(entry) else emptySet())

    @Nested
    @DisplayName("The line")
    inner class TheLine {

        @Test
        @DisplayName("breadcrumb as the Reference data block words it, old and new as compact JSON in code spans")
        fun changed() {
            assertThat(ChangeSummary.dataChangeLine(DataChange(limits, JsonPrimitive(1), JsonPrimitive(2))))
                .isEqualTo("reference data changed: `data ▸ limits ▸ single approver minor`: `1` -> `2`")
        }

        @Test
        @DisplayName("an undefined side is the bare word absent; a real JSON null is shown as `null`")
        fun absentAndNull() {
            assertThat(ChangeSummary.dataChangeLine(DataChange(countries, JsonArray(listOf(JsonPrimitive("IR"))), null)))
                .isEqualTo("reference data changed: `data ▸ high risk countries`: `[\"IR\"]` -> absent")
            assertThat(ChangeSummary.dataChangeLine(DataChange(countries, null, JsonNull)))
                .isEqualTo("reference data changed: `data ▸ high risk countries`: absent -> `null`")
        }

        @Test
        @DisplayName("CHALLENGE: a long value is shown whole (the card's five-element cut would hide the very element that changed), and a backtick cannot end the code span")
        fun longAndBacktick() {
            val long = JsonArray((1..8).map { JsonPrimitive(it) })
            assertThat(ChangeSummary.dataChangeLine(DataChange(countries, long, null))).contains("`[1,2,3,4,5,6,7,8]`").doesNotContain("items").doesNotContain("…")
            assertThat(ChangeSummary.dataChangeLine(DataChange(countries, JsonPrimitive("a`b"), null))).contains("``\"a`b\"``")
        }
    }

    @Nested
    @DisplayName("In the sub-bullets and the JSON")
    inner class InTheReport {

        @Test
        @DisplayName("data lines come after the other sub-bullets, and stand in for the 'structure changed' fallback a data-only change would otherwise get")
        fun placement() {
            val e = entry()
            val change = DataChange(limits, JsonPrimitive(1), JsonPrimitive(2))

            assertThat(ChangeSummary.subBulletsFor(e, listOf(change))).containsExactly("reference data changed: `data ▸ limits ▸ single approver minor`: `1` -> `2`")
            assertThat(ChangeSummary.subBulletsFor(e)).containsExactly("the rule's structure changed; see the source diff below")
        }

        @Test
        @DisplayName("the JSON details are exactly the Markdown sub-bullets")
        fun json() {
            val e = entry()
            val index = indexOf(e, DataChange(limits, JsonPrimitive(1), JsonPrimitive(2)))
            val details = Json.parseToJsonElement(DiffJson.render(listOf(e), index)).jsonObject.getValue("changes").jsonArray.single().jsonObject.getValue("details").jsonArray

            assertThat(details.map { it.jsonPrimitive.content }).isEqualTo(ChangeSummary.subBulletsFor(e, index.of(e)))
        }
    }

    @Nested
    @DisplayName("In the Markdown report")
    inner class InTheMarkdown {

        private val set = PolicySet(listOf(pkg(rule())))

        @Test
        @DisplayName("the disclaimer says reference data is compared only when data was supplied, and always keeps the 'does not judge' sentence")
        fun disclaimer() {
            val e = entry()
            val without = DiffRenderer.render(listOf(e), set, set).lines().first { it.startsWith("*This report") }
            val with = DiffRenderer.render(listOf(e), set, set, DataChangeIndex.NONE, dataSupplied = true).lines().first { it.startsWith("*This report") }

            assertThat(without).isEqualTo("*This report shows structural changes only. It does not evaluate whether the policy became stricter or more permissive.*")
            assertThat(with).contains("reference data").contains("It does not evaluate whether the policy became stricter or more permissive.")
        }

        @Test
        @DisplayName("a data-only change says its source did not change, instead of printing a diff with no - or + line, and lists the data change under the card")
        fun identicalSource() {
            val e = entry()
            val markdown = DiffRenderer.render(listOf(e), set, set, indexOf(e, DataChange(limits, JsonPrimitive(1), JsonPrimitive(2))), dataSupplied = true)

            assertThat(markdown).contains("*The rule's source is unchanged: only the reference data it reads changed.*")
            assertThat(markdown).doesNotContain("```rego")
            assertThat(markdown.lines().count { it == "- reference data changed: `data ▸ limits ▸ single approver minor`: `1` -> `2`" }).isEqualTo(1)
        }

        @Test
        @DisplayName("a rule that was LOGIC_CHANGED on its own (equal source text, e.g. a called function moved package) never gets the 'source is unchanged' sentence, data change or not")
        fun logicChangedAnywayKeepsItsSourceDiff() {
            val e = entry()
            val markdown = DiffRenderer.render(listOf(e), set, set, indexOf(e, DataChange(limits, JsonPrimitive(1), JsonPrimitive(2)), raised = false), dataSupplied = true)

            assertThat(markdown).contains("```rego").doesNotContain("source is unchanged")
            assertThat(markdown).contains("- reference data changed: `data ▸ limits ▸ single approver minor`: `1` -> `2`")
        }

        @Test
        @DisplayName("with a real source change AND a data change, the source diff is printed as always")
        fun bothChanged() {
            val old = rule("deny contains msg if { a }")
            val new = rule("deny contains msg if { b }")
            val e = DiffEntry(DiffCategory.LOGIC_CHANGED, "C-1", pkg(old), old, pkg(new), new)
            val markdown = DiffRenderer.render(listOf(e), PolicySet(listOf(pkg(old))), PolicySet(listOf(pkg(new))), indexOf(e, DataChange(limits, null, JsonPrimitive(2))), dataSupplied = true)

            assertThat(markdown).contains("```rego").contains("- deny contains msg if { a }").contains("+ deny contains msg if { b }").doesNotContain("source is unchanged")
        }

        @Test
        @DisplayName("without any data change the report is exactly what it was: a LOGIC_CHANGED entry with identical source still prints its (empty) source diff")
        fun noDataUnchangedBehaviour() {
            val e = entry()
            val markdown = DiffRenderer.render(listOf(e), set, set)

            assertThat(markdown).contains("```rego").doesNotContain("source is unchanged").doesNotContain("reference data")
        }
    }
}
