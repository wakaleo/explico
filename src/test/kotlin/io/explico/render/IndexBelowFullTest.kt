/**
 * Spec §14.44: the index's note naming every rule below 100% rendered, and the legend sentence about a helper at 0%.
 * Hand-built domain objects -- no opa needed; the real pack is in RealisticBelowFullRenderingAcceptanceIT.
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
import io.explico.model.RuleMetadata
import io.explico.model.SourceRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class IndexBelowFullTest {

    private val rendered = Condition.Comparison(Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("x"))), Operator.EQ, Operand.Literal("1"))
    private val unrendered = Condition.Unrendered("every x in y {}", "unclassified")

    private fun rule(name: String, id: String?, vararg conditions: Condition) = RuleGroup(
        name, id?.let { RuleMetadata("Title $it", "d", it, emptyList()) }, null,
        listOf(RuleBody(conditions.toList(), null, null, SourceRef("f.rego", 1), "")),
    )

    private fun index(vararg rules: RuleGroup) = MarkdownRenderer.renderIndex(PolicySet(listOf(PolicyPackage("p", rules.toList(), listOf("f.rego")))))

    private fun note(markdown: String) = markdown.lines().singleOrNull { it.startsWith("*Below 100%: ") }

    @Test
    fun aRuleBelow100IsNamedAsALinkToItsCard() {
        val markdown = index(rule("deny", "C-1", rendered, unrendered))
        assertThat(note(markdown)).isEqualTo(
            "*Below 100%: [Title C-1](p.md#c-1) -- each shows the constructs it could not render as source on its own card.*",
        )
    }

    @Test
    fun anIdLessRuleIsLinkedByItsNameAndRulesAt100AreLeftOut() {
        val markdown = index(rule("deny", "C-1", rendered), rule("helper", null, unrendered))
        assertThat(note(markdown)).contains("[helper](p.md#p-helper)").doesNotContain("Title C-1")
    }

    @Test
    fun severalRulesAreCommaJoinedInTheTablesOrder() {
        val markdown = index(rule("deny", "C-2", unrendered), rule("allow", "C-1", unrendered))
        assertThat(note(markdown)).contains("[Title C-1](p.md#c-1), [Title C-2](p.md#c-2)")
    }

    @Test
    fun noNoteWhenEverythingIsRendered() {
        assertThat(index(rule("deny", "C-1", rendered)).lines().none { it.startsWith("*Below 100%:") }).isTrue()
    }

    @Test
    @DisplayName("CHALLENGE: a rule with zero conditions (100% of 0) is not below 100% and is not named")
    fun aRuleWithNoConditionsIsNotNamed() {
        assertThat(note(index(rule("deny", "C-1")))).isNull()
    }

    @Test
    fun theNoteFollowsTheOverallLineAndPrecedesTheLegend() {
        val markdown = index(rule("deny", "C-1", unrendered))
        assertThat(markdown.indexOf("*Below 100%:")).isGreaterThan(markdown.indexOf("*Overall rendering coverage:"))
        assertThat(markdown.indexOf("*Below 100%:")).isLessThan(markdown.indexOf("## How to read this index"))
    }

    @Test
    fun theLegendAlwaysExplainsAHelperAtZeroPercent() {
        assertThat(index(rule("deny", "C-1", rendered)).substringAfter("## How to read this index")).contains(
            "A helper rule can show 0% because its only condition is a construct explico does not render (such as a `with` override); that says nothing about the rules that call it.",
        )
    }
}
