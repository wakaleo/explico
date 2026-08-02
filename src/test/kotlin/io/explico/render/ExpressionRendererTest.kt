/**
 * Parameterised from spec §6.3's phrasing table and builtin table, plus the
 * two-position rule and §6.4's Literal/AnyIndex propagation. Pure unit tests.
 */
package io.explico.render

import io.explico.model.Condition
import io.explico.model.Operand
import io.explico.model.Operator
import io.explico.model.PathSegment
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class ExpressionRendererTest {

    private val deploymentEnv = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("deployment"), PathSegment.Field("environment")))
    private val production = Operand.Literal("\"production\"")
    private val noAnchor: (Condition.RuleReference) -> String = { "#stub" }

    data class Case(val description: String, val condition: Condition, val rendered: String)

    companion object {
        @JvmStatic
        fun phrasingTable(): Stream<Arguments> {
            val env = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("deployment"), PathSegment.Field("environment")))
            val prod = Operand.Literal("\"production\"")
            val approved = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("change"), PathSegment.Field("ticket"), PathSegment.Field("approved")))
            val zero = Operand.Literal("0")

            return listOf(
                Case("Comparison EQ", Condition.Comparison(env, Operator.EQ, prod), "`deployment ▸ environment` is `\"production\"`"),
                Case("Comparison NEQ", Condition.Comparison(env, Operator.NEQ, prod), "`deployment ▸ environment` is not `\"production\"`"),
                Case("Comparison GT", Condition.Comparison(approved, Operator.GT, zero), "`change ▸ ticket ▸ approved` is greater than `0`"),
                Case("Comparison GTE", Condition.Comparison(approved, Operator.GTE, zero), "`change ▸ ticket ▸ approved` is at least `0`"),
                Case("Comparison LT", Condition.Comparison(approved, Operator.LT, zero), "`change ▸ ticket ▸ approved` is less than `0`"),
                Case("Comparison LTE", Condition.Comparison(approved, Operator.LTE, zero), "`change ▸ ticket ▸ approved` is at most `0`"),
                Case("Membership positive", Condition.Membership(false, env, Operand.Literal("\"production\", \"staging\"")), "`deployment ▸ environment` is one of `\"production\", \"staging\"`"),
                Case("Membership negated", Condition.Membership(true, env, Operand.Literal("\"production\", \"staging\"")), "`deployment ▸ environment` is not one of `\"production\", \"staging\"`"),
                Case("Truthy positive (bare reference)", Condition.Truthy(approved, false), "`change ▸ ticket ▸ approved` is true"),
                Case("Truthy negated (absent or false)", Condition.Truthy(approved, true), "`change ▸ ticket ▸ approved` is absent or false"),
                Case(
                    "SomeIn",
                    Condition.SomeIn("stage", Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("pipeline"), PathSegment.Field("stages")))),
                    "for some stage in `pipeline ▸ stages`",
                ),
            ).map { Arguments.of(it) }.stream()
        }

        @JvmStatic
        fun builtinTable(): Stream<Arguments> {
            val a = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("artifact"), PathSegment.Field("source_branch")))
            val b = Operand.Literal("\"release/\"")
            return listOf(
                Case("startswith positive", Condition.BuiltinCall("startswith", listOf(a, b), false), "`artifact ▸ source branch` starts with `\"release/\"`"),
                Case("startswith negated (evidenced by acceptance README)", Condition.BuiltinCall("startswith", listOf(a, b), true), "`artifact ▸ source branch` does not start with `\"release/\"`"),
                Case("endswith positive", Condition.BuiltinCall("endswith", listOf(a, b), false), "`artifact ▸ source branch` ends with `\"release/\"`"),
                Case("endswith negated", Condition.BuiltinCall("endswith", listOf(a, b), true), "`artifact ▸ source branch` does not end with `\"release/\"`"),
                Case("contains positive", Condition.BuiltinCall("contains", listOf(a, b), false), "`artifact ▸ source branch` contains `\"release/\"`"),
                Case("contains negated", Condition.BuiltinCall("contains", listOf(a, b), true), "`artifact ▸ source branch` does not contain `\"release/\"`"),
                Case("regex.match positive", Condition.BuiltinCall("regex.match", listOf(b, a), false), "`artifact ▸ source branch` matches pattern `\"release/\"`"),
                Case("regex.match negated", Condition.BuiltinCall("regex.match", listOf(b, a), true), "`artifact ▸ source branch` does not match pattern `\"release/\"`"),
                Case("glob.match positive", Condition.BuiltinCall("glob.match", listOf(b, a), false), "`artifact ▸ source branch` matches glob `\"release/\"`"),
                Case("glob.match negated", Condition.BuiltinCall("glob.match", listOf(b, a), true), "`artifact ▸ source branch` does not match glob `\"release/\"`"),
            ).map { Arguments.of(it) }.stream()
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("phrasingTable")
    @DisplayName("spec §6.3 phrasing table")
    fun rendersPhrasingTable(case: Case) {
        assertThat(ExpressionRenderer.render(case.condition, noAnchor)).isEqualTo(case.rendered)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("builtinTable")
    @DisplayName("spec §6.3 builtin table (positive and negated)")
    fun rendersBuiltinTable(case: Case) {
        assertThat(ExpressionRenderer.render(case.condition, noAnchor)).isEqualTo(case.rendered)
    }

    @Test
    @DisplayName("RuleReference: positive uses 'see rule', negated uses 'does not match', anchor is delegated")
    fun rendersRuleReferenceUsingInjectedAnchor() {
        val anchorFor: (Condition.RuleReference) -> String = { ref -> "release-exemptions.md#${ref.ruleName}" }

        val positive = Condition.RuleReference("release.evidence", "is_release_candidate", negated = false)
        assertThat(ExpressionRenderer.render(positive, anchorFor))
            .isEqualTo("see rule [`is_release_candidate`](release-exemptions.md#is_release_candidate)")

        val negated = Condition.RuleReference("release.exemptions", "exempt_service", negated = true)
        assertThat(ExpressionRenderer.render(negated, anchorFor))
            .isEqualTo("rule [`exempt_service`](release-exemptions.md#exempt_service) does not match")
    }

    @Test
    @DisplayName("Condition.Unrendered is not part of the phrasing table -- rejected, not silently mis-rendered")
    fun rejectsConditionUnrendered() {
        val unrendered = Condition.Unrendered("count({a | ...})", "comprehension")
        assertThatThrownBy { ExpressionRenderer.render(unrendered, noAnchor) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    @DisplayName("A BuiltinCall name outside the recognised condition-position set is rejected, not silently mis-rendered")
    fun rejectsUnrecognisedBuiltinName() {
        // AstMapper never constructs this (it only builds BuiltinCall from the CONDITION_BUILTINS
        // whitelist) -- this constructs one directly to prove the defensive check actually fires.
        val bogus = Condition.BuiltinCall("sprintf", listOf(Operand.Literal("\"x\"")), negated = false)
        assertThatThrownBy { ExpressionRenderer.render(bogus, noAnchor) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    @DisplayName("AnyIndex anywhere in a condition prefixes the whole phrase with 'any of:' exactly once")
    fun prefixesAnyOfWhenAnyIndexPresent() {
        val pathWithAnyIndex = Operand.Path(
            listOf(
                PathSegment.Field("input"), PathSegment.Field("pipeline"), PathSegment.Field("stages"),
                PathSegment.AnyIndex, PathSegment.Field("checks"), PathSegment.AnyIndex, PathSegment.Field("status"),
            )
        )
        val condition = Condition.Comparison(pathWithAnyIndex, Operator.NEQ, Operand.Literal("\"passed\""))
        assertThat(ExpressionRenderer.render(condition, noAnchor))
            .isEqualTo("any of: `pipeline ▸ stages ▸ checks ▸ status` is not `\"passed\"`")
    }

    @Test
    @DisplayName("Operand.Literal content is already fully formatted by AstMapper (spec §6.4 closing rules); the renderer only adds the backtick wrapping")
    fun literalOperandContentIsAlreadyFormatted() {
        val condition = Condition.Comparison(deploymentEnv, Operator.EQ, Operand.Literal("\"production\", \"staging\""))
        assertThat(ExpressionRenderer.render(condition, noAnchor))
            .isEqualTo("`deployment ▸ environment` is `\"production\", \"staging\"`")
    }

    @Test
    @DisplayName("Operand.Variable renders as its bare name in backticks")
    fun variableOperandRendersAsBareName() {
        val condition = Condition.Comparison(Operand.Variable("x"), Operator.EQ, Operand.Literal("5"))
        assertThat(ExpressionRenderer.render(condition, noAnchor)).isEqualTo("`x` is `5`")
    }

    @Test
    @DisplayName("Operand.Unrendered renders inline as its verbatim source in backticks, never guessed prose")
    fun unrenderedOperandRendersAsVerbatimSourceInline() {
        val condition = Condition.Comparison(Operand.Unrendered("count(input.x)"), Operator.EQ, Operand.Literal("0"))
        assertThat(ExpressionRenderer.render(condition, noAnchor)).isEqualTo("`count(input.x)` is `0`")
    }
}
