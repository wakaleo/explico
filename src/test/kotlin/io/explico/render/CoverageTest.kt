package io.explico.render

import io.explico.model.Condition
import io.explico.model.Operand
import io.explico.model.Operator
import io.explico.model.PolicyPackage
import io.explico.model.RuleBody
import io.explico.model.RuleGroup
import io.explico.model.SourceRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CoverageTest {

    private val path = Operand.Path(emptyList())
    private val literal = Operand.Literal("\"x\"")
    private val unrenderedOperand = Operand.Unrendered("count(input.x)")

    @Test
    fun countsRenderedVersusTotalExcludingUnrenderedFromTheNumerator() {
        val conditions = listOf(
            Condition.Comparison(path, Operator.EQ, literal),
            Condition.Unrendered("count({a | ...})", "comprehension"),
        )
        val coverage = Coverage.of(conditions)
        assertThat(coverage.rendered).isEqualTo(1)
        assertThat(coverage.total).isEqualTo(2)
        assertThat(coverage.percent).isEqualTo(50)
    }

    @Test
    fun emptyConditionListIsFullCoverageByConvention() {
        assertThat(Coverage.of(emptyList()).percent).isEqualTo(100)
    }

    @Test
    fun operandLevelUnrenderedDoesNotCountAgainstConditionCoverageButIsCountedSeparately() {
        val conditions = listOf(Condition.Comparison(unrenderedOperand, Operator.EQ, literal))
        val coverage = Coverage.of(conditions)
        assertThat(coverage.rendered).isEqualTo(1) // the Comparison itself IS rendered
        assertThat(coverage.total).isEqualTo(1)
        assertThat(Coverage.unrenderedOperandCount(conditions)).isEqualTo(1)
    }

    @Test
    fun unrenderedOperandCountWalksEveryConditionShapeThatCarriesOperands() {
        val conditions = listOf(
            Condition.Membership(false, unrenderedOperand, literal),
            Condition.Truthy(unrenderedOperand, false),
            Condition.BuiltinCall("startswith", listOf(unrenderedOperand, literal), false),
            Condition.SomeIn("x", unrenderedOperand),
            Condition.RuleReference("pkg", "rule", false), // no operands at all
        )
        assertThat(Coverage.unrenderedOperandCount(conditions)).isEqualTo(4)
    }

    @Test
    fun ofRuleGroupSumsAcrossAllItsBodies() {
        val rule = RuleGroup(
            "deny", metadata = null, default = null,
            bodies = listOf(
                RuleBody(listOf(Condition.Comparison(path, Operator.EQ, literal)), null, null, SourceRef("f.rego", 1)),
                RuleBody(listOf(Condition.Unrendered("x", "unclassified")), null, null, SourceRef("f.rego", 5)),
            ),
        )
        val coverage = Coverage.of(rule)
        assertThat(coverage.rendered).isEqualTo(1)
        assertThat(coverage.total).isEqualTo(2)
    }

    @Test
    fun ofPolicyPackageSumsAcrossAllItsRules() {
        val ruleA = RuleGroup("a", null, null, listOf(RuleBody(listOf(Condition.Comparison(path, Operator.EQ, literal)), null, null, SourceRef("f.rego", 1))))
        val ruleB = RuleGroup("b", null, null, listOf(RuleBody(listOf(Condition.Unrendered("x", "unclassified")), null, null, SourceRef("f.rego", 5))))
        val pkg = PolicyPackage("pkg", listOf(ruleA, ruleB), listOf("f.rego"))

        val coverage = Coverage.of(pkg)
        assertThat(coverage.rendered).isEqualTo(1)
        assertThat(coverage.total).isEqualTo(2)
    }
}
