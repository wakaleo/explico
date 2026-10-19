/**
 * Spec §14.44: the card's one-line "Effect of a match", by the same name-based convention worked examples use. Hand-built
 * domain objects -- no opa needed; the real-output shapes are in RealisticEffectOfMatchAcceptanceIT.
 */
package io.explico.render

import io.explico.model.Condition
import io.explico.model.ConstantValue
import io.explico.model.DefaultValue
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

class EffectOfMatchTest {

    private val condition = Condition.Comparison(
        Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("x"))), Operator.EQ, Operand.Literal("1"),
    )
    private val body = RuleBody(listOf(condition), null, null, SourceRef("f.rego", 1), "")

    private fun card(rule: RuleGroup): String {
        val pkg = PolicyPackage("p", listOf(rule), listOf("f.rego"))
        return MarkdownRenderer.renderCard(rule, pkg, PolicySet(listOf(pkg)))
    }

    private fun rule(name: String, bodies: List<RuleBody> = listOf(body), parameters: List<String> = emptyList(), split: Boolean = false) =
        RuleGroup(name, null, null, bodies, parameters, isSplitDefaultEntry = split)

    @Test
    fun denyAndViolationSayDeniedNamingTheirOwnName() {
        assertThat(card(rule("deny"))).contains("**Effect of a match:** ❌ denied *(by the `deny` naming convention)*")
        assertThat(card(rule("violation"))).contains("**Effect of a match:** ❌ denied *(by the `violation` naming convention)*")
    }

    @Test
    fun allowSaysAllowed() {
        assertThat(card(rule("allow"))).contains("**Effect of a match:** ✅ allowed *(by the `allow` naming convention)*")
    }

    @Test
    fun theLineFollowsTheDescriptionWithBlankLinesAndPrecedesTheConditions() {
        val lines = card(rule("deny")).lines()
        val at = lines.indexOfFirst { it.startsWith("**Effect of a match:**") }
        assertThat(lines[at - 2]).isEqualTo("*No description provided in policy metadata.*")
        assertThat(lines[at - 1]).isEmpty()
        assertThat(lines[at + 1]).isEmpty()
        assertThat(lines[at + 2]).isEqualTo("**All of the following are true:**")
    }

    @Test
    @DisplayName("CHALLENGE: any other name, a function, a split default and a body-less rule get no line")
    fun everyOtherShapeHasNoLine() {
        assertThat(card(rule("flag"))).doesNotContain("Effect of a match")
        assertThat(card(rule("deny_stage"))).doesNotContain("Effect of a match") // a name that merely starts with a convention
        assertThat(card(rule("Deny"))).doesNotContain("Effect of a match") // the convention is exact, case-sensitive
        assertThat(card(rule("allow", parameters = listOf("x")))).doesNotContain("Effect of a match")
        assertThat(card(rule("allow", bodies = emptyList(), split = true).copy(default = DefaultValue("false", SourceRef("f.rego", 1))))).doesNotContain("Effect of a match")
        assertThat(card(rule("deny", bodies = emptyList()))).doesNotContain("Effect of a match")
        assertThat(card(rule("deny", bodies = emptyList()).copy(constantValue = ConstantValue("1", SourceRef("f.rego", 1))))).doesNotContain("Effect of a match")
    }

    @Test
    fun aDefaultOutcomeLineStillFollowsTheEffectLine() {
        val lines = card(rule("allow").copy(default = DefaultValue("false", SourceRef("f.rego", 1)))).lines()
        assertThat(lines.indexOfFirst { it.startsWith("**Effect of a match:**") }).isLessThan(lines.indexOfFirst { it.startsWith("**Default outcome:**") })
    }
}
