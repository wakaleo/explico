/**
 * Spec §14.49: how an [io.explico.model.Condition.Every] is worded, nested, counted and walked. Hand-built domain objects -- no opa.
 */
package io.explico.render

import io.explico.model.Condition
import io.explico.model.Operand
import io.explico.model.Operator
import io.explico.model.PathSegment
import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RuleBody
import io.explico.model.RuleGroup
import io.explico.model.SourceRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class EveryRenderingTest {

    private fun field(vararg names: String) = Operand.Path(names.map { PathSegment.Field(it) })
    private val items = field("input", "items")
    private val itemOk = Condition.Comparison(
        Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("items"), PathSegment.VarIndex("i"), PathSegment.Field("ok"))),
        Operator.EQ,
        Operand.Literal("true"),
    )
    private val every = Condition.Every("i", items, listOf(itemOk))

    private fun card(vararg conditions: Condition): String {
        val rule = RuleGroup("r", null, null, listOf(RuleBody(conditions.toList(), null, null, SourceRef("f.rego", 1), "")))
        val pkg = PolicyPackage("p", listOf(rule), listOf("f.rego"))
        return MarkdownRenderer.renderCard(rule, pkg, PolicySet(listOf(pkg)))
    }

    private fun render(c: Condition) = ExpressionRenderer.render(c, { null }, { null }, { null })

    @Test
    fun theHeadPhraseNamesTheVariableTheCollectionAndTheEmptyCollectionCase() {
        assertThat(render(every)).isEqualTo("for every i in `items`, all of the following are true (vacuously true when there are none)")
    }

    @Test
    fun theKeyFormListsKeyThenValueLikeSome() {
        assertThat(render(Condition.Every("v", items, listOf(itemOk), key = "k"))).startsWith("for every k, v in `items`")
    }

    @Test
    fun theNestedConditionsIndentUnderTheHeadBullet() {
        val lines = card(every).lines()
        val at = lines.indexOfFirst { it.startsWith("- for every i in") }
        assertThat(lines[at + 1]).isEqualTo("  - `items ▸ [each i] ▸ ok` is `true`")
    }

    @Test
    @DisplayName("CHALLENGE: three levels nest by two spaces each, and a sibling after the block returns to the top level")
    fun deepNestingAndASiblingAfter() {
        val inner = Condition.Every("j", field("input", "more"), listOf(itemOk))
        val outer = Condition.Every("i", items, listOf(inner))
        val sibling = Condition.Truthy(field("input", "flag"), negated = false)
        val lines = card(outer, sibling).lines()
        val at = lines.indexOfFirst { it.startsWith("- for every i in") }
        assertThat(lines[at + 1]).startsWith("  - for every j in")
        assertThat(lines[at + 2]).startsWith("    - ")
        assertThat(lines[at + 3]).startsWith("- `flag`")
    }

    @Test
    fun coverageCountsTheHeadAndEveryNestedCondition() {
        val rule = RuleGroup("r", null, null, listOf(RuleBody(listOf(every, Condition.Truthy(field("input", "x"), false)), null, null, SourceRef("f.rego", 1), "")))
        assertThat(Coverage.of(rule)).isEqualTo(CoverageSummary(3, 3))
        assertThat(Coverage.of(PolicyPackage("p", listOf(rule), listOf("f.rego")))).isEqualTo(CoverageSummary(3, 3))
    }

    @Test
    fun anUnrenderableEveryStaysOneUnrenderedConditionAgainstCoverage() {
        val rule = RuleGroup("r", null, null, listOf(RuleBody(listOf(Condition.Unrendered("every ...", "every")), null, null, SourceRef("f.rego", 1), "")))
        assertThat(Coverage.of(rule)).isEqualTo(CoverageSummary(0, 1))
    }

    @Test
    fun flattenInlinesNestedConditionsAfterTheirHeadRecursively() {
        val inner = Condition.Every("j", field("input", "more"), listOf(itemOk))
        val outer = Condition.Every("i", items, listOf(inner, itemOk))
        assertThat(Coverage.flatten(listOf(outer))).containsExactly(outer, inner, itemOk, itemOk)
    }

    @Test
    fun theOperandsOfAnEveryAreItsCollectionOnly_theNestedOnesComeFromFlatten() {
        assertThat(Coverage.operandsOf(every)).containsExactly(items)
    }
}
