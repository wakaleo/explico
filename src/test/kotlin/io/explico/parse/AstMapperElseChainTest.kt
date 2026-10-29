/**
 * Unit tests for FR-1: an `else` chain whose every branch maps cleanly becomes a [RuleBody] with [RuleBody.elseBranches]; otherwise the whole
 * chain stays ONE `else-chain` fallback. Driven from the real `opa parse` captures `else-chain.json` and `else-chain-edge.json` -- no `opa`
 * binary needed. (The sources are described by the rule names used below; the captures embed their own source text.)
 */
package io.explico.parse

import io.explico.model.Condition
import io.explico.model.Operand
import io.explico.model.Operator
import io.explico.model.PathSegment
import io.explico.model.RuleGroup
import io.explico.opa.OpaModule
import io.explico.opa.opaJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class AstMapperElseChainTest {

    private fun rules(capture: String): List<RuleGroup> {
        val module = opaJson.decodeFromString(OpaModule.serializer(), Files.readString(Path.of("src/test/resources/ast/$capture.json")))
        return AstMapper.mapPolicySet(listOf(ParsedFile("chain/$capture.rego", module))).packages.single().rules
    }

    private fun rule(capture: String, name: String) = rules(capture).single { it.name == name }

    private fun fieldsOf(operand: Operand) = (operand as Operand.Path).segments.map { (it as PathSegment.Field).name }

    private fun assertWholeChainFallback(group: RuleGroup) {
        val body = group.bodies.single()
        assertThat(body.elseBranches).isEmpty()
        val only = body.conditions.single() as Condition.Unrendered
        assertThat(only.reason).isEqualTo("else-chain")
        assertThat(only.sourceText).contains("else")
    }

    @Test
    @DisplayName("a three-branch chain: the head body, then each else in order, the bodiless last one with no conditions")
    fun threeBranchChain() {
        val body = rule("else-chain", "decision").bodies.single()

        assertThat(body.conditions).containsExactly(Condition.Truthy(Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("a"))), negated = true))
        assertThat(body.elseBranches).hasSize(2)
        assertThat(body.elseBranches[0].conditions).hasSize(2)
        assertThat(body.elseBranches[1].conditions).isEmpty()
        assertThat(body.elseBranches.map { it.sourceLocation.row }).containsExactly(7, 10)
        assertThat(body.sourceLocation.row).isEqualTo(5)
    }

    @Test
    @DisplayName("every branch carries the value it yields; a literal is a Literal, an unresolvable call is an Unrendered operand (never a guess)")
    fun branchValues() {
        val body = rule("else-chain", "decision").bodies.single()

        assertThat(body.elseBranches[1].yieldsValue).isEqualTo(Operand.Literal("\"last\""))
        // `non_compliant` is not defined in the capture's package set, so the head call cannot be resolved.
        assertThat(body.yieldsValue).isInstanceOf(Operand.Unrendered::class.java)
        assertThat(body.elseBranches[0].yieldsValue).isInstanceOf(Operand.Unrendered::class.java)
    }

    @Test
    @DisplayName("a boolean helper: the head's IMPLICIT true is stated as a Literal true (opa gives it no source location), the else's explicit false likewise")
    fun implicitTrueIsMadeExplicitInsideAChain() {
        val body = rule("else-chain", "flag").bodies.single()

        assertThat(body.yieldsValue).isEqualTo(Operand.Literal("true"))
        assertThat(body.elseBranches.single().conditions).isEmpty()
        assertThat(body.elseBranches.single().yieldsValue).isEqualTo(Operand.Literal("false"))
    }

    @Test
    @DisplayName("a function's chain resolves the parameter in every branch")
    fun functionParametersInEveryBranch() {
        val group = rule("else-chain-edge", "f")
        val body = group.bodies.single()

        assertThat(group.parameters).containsExactly("x")
        val comparison = body.conditions.single() as Condition.Comparison
        assertThat(comparison.left).isEqualTo(Operand.Parameter("x"))
        assertThat(comparison.op).isEqualTo(Operator.GT)
        assertThat(body.elseBranches.single().conditions).isEmpty()
    }

    @Test
    @DisplayName("a chain whose last branch has conditions (no bodiless else) keeps them: there is simply no Otherwise")
    fun lastBranchWithConditions() {
        val body = rule("else-chain-edge", "g").bodies.single()

        assertThat(body.conditions).hasSize(1)
        assertThat(body.elseBranches.single().conditions).hasSize(1)
    }

    @Test
    @DisplayName("each branch has its own scope: a local assignment in one branch is substituted there and nowhere else")
    fun assignmentsAreScopedPerBranch() {
        val body = rule("else-chain-edge", "per_branch_binding").bodies.single()

        assertThat(fieldsOf((body.conditions.single() as Condition.Comparison).left)).containsExactly("input", "a")
        assertThat(fieldsOf((body.elseBranches.single().conditions.single() as Condition.Comparison).left)).containsExactly("input", "b")
    }

    @Test
    @DisplayName("CHALLENGE: a variable assigned in the first branch is NOT in scope in the second -> unbound there -> the whole chain falls back")
    fun aVariableDoesNotLeakIntoALaterBranch() {
        assertWholeChainFallback(rule("else-chain-edge", "scope"))
    }

    @Test
    @DisplayName("one unrenderable condition (a `with`) in ANY branch keeps the whole chain as one fallback holding the whole source")
    fun oneUnrenderableBranchFallsBackWhole() {
        val group = rule("else-chain-edge", "tier")
        assertWholeChainFallback(group)
        val only = group.bodies.single().conditions.single() as Condition.Unrendered
        assertThat(only.sourceText).contains("input.score >= 90").contains("with input.score as 60").contains("\"bronze\"")
    }

    @Test
    @DisplayName("CHALLENGE: an always-true branch that is not the last would leave later branches unreachable -> 'Otherwise' followed by more would mislead -> whole fallback")
    fun alwaysTrueBranchInTheMiddleFallsBack() {
        assertWholeChainFallback(rule("else-chain-edge", "early"))
    }

    @Test
    @DisplayName("CHALLENGE: a head body with no conditions at all (`x = 1 if true else = 2`) has no 'Situation 1' to describe -> whole fallback")
    fun emptyFirstBranchFallsBack() {
        assertWholeChainFallback(rule("else-chain-edge", "first_empty"))
    }

    @Test
    @DisplayName("CHALLENGE: a chain among SIBLING definitions of one name (function overloads) falls back whole -- the sibling situations are OR-ed and could not show its later branches")
    fun aChainAmongSiblingDefinitionsFallsBack() {
        val group = rule("else-chain-multi", "pick")

        assertThat(group.bodies).hasSize(2)
        assertWholeChainFallback(group.copy(bodies = listOf(group.bodies[0])))
        assertThat(group.bodies[1].elseBranches).isEmpty()
        assertThat(group.bodies[1].conditions.single()).isNotInstanceOf(Condition.Unrendered::class.java)
    }

    @Test
    @DisplayName("the same chain as the ONLY definition of its name does render")
    fun theSameChainAloneRenders() {
        assertThat(rule("else-chain-multi", "solo").bodies.single().elseBranches).hasSize(1)
    }
}
