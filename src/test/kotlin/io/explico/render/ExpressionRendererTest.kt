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
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
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
                Case(
                    "Comparison EQ, negated (spec §14 amendment: not x == y)",
                    Condition.Comparison(env, Operator.EQ, prod, negated = true),
                    "`deployment ▸ environment` is not `\"production\"`",
                ),
                Case(
                    "Comparison NEQ, negated",
                    Condition.Comparison(env, Operator.NEQ, prod, negated = true),
                    "`deployment ▸ environment` is `\"production\"`",
                ),
                Case(
                    "Comparison GT, negated",
                    Condition.Comparison(approved, Operator.GT, zero, negated = true),
                    "`change ▸ ticket ▸ approved` is not greater than `0`",
                ),
                Case(
                    "Comparison GTE, negated",
                    Condition.Comparison(approved, Operator.GTE, zero, negated = true),
                    "`change ▸ ticket ▸ approved` is not at least `0`",
                ),
                Case(
                    "Comparison LT, negated",
                    Condition.Comparison(approved, Operator.LT, zero, negated = true),
                    "`change ▸ ticket ▸ approved` is not less than `0`",
                ),
                Case(
                    "Comparison LTE, negated",
                    Condition.Comparison(approved, Operator.LTE, zero, negated = true),
                    "`change ▸ ticket ▸ approved` is not at most `0`",
                ),
                Case("Membership positive", Condition.Membership(false, env, Operand.Literal("\"production\", \"staging\"")), "`deployment ▸ environment` is one of `\"production\", \"staging\"`"),
                Case("Membership negated", Condition.Membership(true, env, Operand.Literal("\"production\", \"staging\"")), "`deployment ▸ environment` is not one of `\"production\", \"staging\"`"),
                Case("Truthy positive (bare reference)", Condition.Truthy(approved, false), "`change ▸ ticket ▸ approved` is present and not false"),
                Case("Truthy negated (absent or false)", Condition.Truthy(approved, true), "`change ▸ ticket ▸ approved` is absent or false"),
                Case(
                    "SomeIn",
                    Condition.SomeIn("stage", Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("pipeline"), PathSegment.Field("stages")))),
                    "for some stage in `pipeline ▸ stages`",
                ),
                Case(
                    "SomeIn, two-variable form (spec §14 promotion)",
                    Condition.SomeIn(
                        "v",
                        Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("change"), PathSegment.Field("metadata"))),
                        key = "k",
                    ),
                    "for some k, v in `change ▸ metadata`",
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
                // review item 11: net.cidr_contains(cidr, ip) -- args[0] is the cidr, args[1] the ip
                // (confirmed via a real `opa parse` run), rendered subject-first (ip, then cidr).
                Case("net.cidr_contains positive", Condition.BuiltinCall("net.cidr_contains", listOf(b, a), false), "`artifact ▸ source branch` is within `\"release/\"`"),
                Case("net.cidr_contains negated", Condition.BuiltinCall("net.cidr_contains", listOf(b, a), true), "`artifact ▸ source branch` is not within `\"release/\"`"),
                // review item 11: the seven is_* type checks, each a single-argument condition builtin.
                Case("is_string positive", Condition.BuiltinCall("is_string", listOf(a), false), "`artifact ▸ source branch` is a string"),
                Case("is_string negated", Condition.BuiltinCall("is_string", listOf(a), true), "`artifact ▸ source branch` is not a string"),
                Case("is_number positive", Condition.BuiltinCall("is_number", listOf(a), false), "`artifact ▸ source branch` is a number"),
                Case("is_number negated", Condition.BuiltinCall("is_number", listOf(a), true), "`artifact ▸ source branch` is not a number"),
                Case("is_boolean positive", Condition.BuiltinCall("is_boolean", listOf(a), false), "`artifact ▸ source branch` is a boolean"),
                Case("is_boolean negated", Condition.BuiltinCall("is_boolean", listOf(a), true), "`artifact ▸ source branch` is not a boolean"),
                Case("is_array positive", Condition.BuiltinCall("is_array", listOf(a), false), "`artifact ▸ source branch` is an array"),
                Case("is_array negated", Condition.BuiltinCall("is_array", listOf(a), true), "`artifact ▸ source branch` is not an array"),
                Case("is_object positive", Condition.BuiltinCall("is_object", listOf(a), false), "`artifact ▸ source branch` is an object"),
                Case("is_object negated", Condition.BuiltinCall("is_object", listOf(a), true), "`artifact ▸ source branch` is not an object"),
                Case("is_null positive", Condition.BuiltinCall("is_null", listOf(a), false), "`artifact ▸ source branch` is null"),
                Case("is_null negated", Condition.BuiltinCall("is_null", listOf(a), true), "`artifact ▸ source branch` is not null"),
                Case("is_set positive", Condition.BuiltinCall("is_set", listOf(a), false), "`artifact ▸ source branch` is a set"),
                Case("is_set negated", Condition.BuiltinCall("is_set", listOf(a), true), "`artifact ▸ source branch` is not a set"),
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
    @DisplayName("CHALLENGE edge case (follow-up to review item 18): a null anchor -- the target has no card in THIS document (e.g. an unchanged control in a diff report) -- renders a plain backticked name, never a dead link")
    fun rendersRuleReferenceUnlinkedWhenAnchorForReturnsNull() {
        val noAnchorAvailable: (Condition.RuleReference) -> String? = { null }

        val positive = Condition.RuleReference("release.evidence", "is_release_candidate", negated = false)
        assertThat(ExpressionRenderer.render(positive, noAnchorAvailable)).isEqualTo("see rule `is_release_candidate`")

        val negated = Condition.RuleReference("release.exemptions", "exempt_service", negated = true)
        assertThat(ExpressionRenderer.render(negated, noAnchorAvailable)).isEqualTo("rule `exempt_service` does not match")
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

    @Test
    @DisplayName("Operand.BuiltinCall(count) renders as spec §6.3/§14's \"the number of X\" template")
    fun operandBuiltinCallCountRendersFaithfully() {
        val approvals = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("change"), PathSegment.Field("approvals")))
        val condition = Condition.Comparison(Operand.BuiltinCall("count", listOf(approvals)), Operator.EQ, Operand.Literal("2"))
        assertThat(ExpressionRenderer.render(condition, noAnchor)).isEqualTo("the number of `change ▸ approvals` is `2`")
    }

    @Test
    @DisplayName("Operand.BuiltinCall(lower/upper) render as spec §6.3/§14's \"X lowercased\"/\"X uppercased\" templates")
    fun operandBuiltinCallLowerAndUpperRenderFaithfully() {
        val author = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("change"), PathSegment.Field("author")))
        val lower = Condition.Comparison(Operand.BuiltinCall("lower", listOf(author)), Operator.EQ, Operand.Literal("\"asmith\""))
        val upper = Condition.Comparison(Operand.BuiltinCall("upper", listOf(author)), Operator.EQ, Operand.Literal("\"ASMITH\""))
        assertThat(ExpressionRenderer.render(lower, noAnchor)).isEqualTo("`change ▸ author` lowercased is `\"asmith\"`")
        assertThat(ExpressionRenderer.render(upper, noAnchor)).isEqualTo("`change ▸ author` uppercased is `\"ASMITH\"`")
    }

    @Test
    @DisplayName("Operand.BuiltinCall(sum/max/min) render as spec §6.3/§14's \"the sum/largest/smallest of X\" templates")
    fun operandBuiltinCallSumMaxMinRenderFaithfully() {
        val scores = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("scores")))
        val sum = Condition.Comparison(Operand.BuiltinCall("sum", listOf(scores)), Operator.GT, Operand.Literal("0"))
        val max = Condition.Comparison(Operand.BuiltinCall("max", listOf(scores)), Operator.GTE, Operand.Literal("90"))
        val min = Condition.Comparison(Operand.BuiltinCall("min", listOf(scores)), Operator.LT, Operand.Literal("10"))
        assertThat(ExpressionRenderer.render(sum, noAnchor)).isEqualTo("the sum of `scores` is greater than `0`")
        assertThat(ExpressionRenderer.render(max, noAnchor)).isEqualTo("the largest of `scores` is at least `90`")
        assertThat(ExpressionRenderer.render(min, noAnchor)).isEqualTo("the smallest of `scores` is less than `10`")
    }

    @Test
    @DisplayName("Operand.BuiltinCall(to_number/trim_space) render as spec §6.3/§14's \"X as a number\"/\"X trimmed\" templates")
    fun operandBuiltinCallToNumberAndTrimSpaceRenderFaithfully() {
        val countText = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("count_text")))
        val name = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("name")))
        val toNumber = Condition.Comparison(Operand.BuiltinCall("to_number", listOf(countText)), Operator.EQ, Operand.Literal("5"))
        val trimSpace = Condition.Comparison(Operand.BuiltinCall("trim_space", listOf(name)), Operator.EQ, Operand.Literal("\"root\""))
        assertThat(ExpressionRenderer.render(toNumber, noAnchor)).isEqualTo("`count text` as a number is `5`")
        assertThat(ExpressionRenderer.render(trimSpace, noAnchor)).isEqualTo("`name` trimmed is `\"root\"`")
    }

    @Test
    @DisplayName("Operand.BuiltinCall(object.keys) renders as spec §6.3/§14's \"the keys of X\" template, including nested inside count")
    fun operandBuiltinCallObjectKeysRendersFaithfullyEvenNestedInsideCount() {
        val labels = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("labels")))
        val keys = Operand.BuiltinCall("object.keys", listOf(labels))
        val bare = Condition.Comparison(keys, Operator.GT, Operand.Literal("0"))
        assertThat(ExpressionRenderer.render(bare, noAnchor)).isEqualTo("the keys of `labels` is greater than `0`")
        val nested = Condition.Comparison(Operand.BuiltinCall("count", listOf(keys)), Operator.GT, Operand.Literal("3"))
        assertThat(ExpressionRenderer.render(nested, noAnchor)).isEqualTo("the number of the keys of `labels` is greater than `3`")
    }

    @Test
    @DisplayName("Operand.BuiltinCall(time.parse_rfc3339_ns) renders as spec §6.3/§14's \"the time X\" template")
    fun operandBuiltinCallTimeParseRfc3339RendersFaithfully() {
        val createdAt = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("created_at")))
        val condition = Condition.Comparison(Operand.BuiltinCall("time.parse_rfc3339_ns", listOf(createdAt)), Operator.LT, Operand.BuiltinCall("time.now_ns", emptyList()))
        assertThat(ExpressionRenderer.render(condition, noAnchor)).isEqualTo("the time `created at` is less than the current time")
    }

    @Test
    @DisplayName("CHALLENGE edge case: AnyIndex inside a unary type check's argument still prefixes the whole phrase with 'any of:'")
    fun anyOfPrefixPropagatesThroughUnaryTypeCheck() {
        val pathWithAnyIndex = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("items"), PathSegment.AnyIndex, PathSegment.Field("id")))
        val condition = Condition.BuiltinCall("is_string", listOf(pathWithAnyIndex), negated = false)
        assertThat(ExpressionRenderer.render(condition, noAnchor)).isEqualTo("any of: `items ▸ id` is a string")
    }

    @Test
    @DisplayName("Operand.BuiltinCall(object.get) extends the object's own breadcrumb with the key as a KeyLiteral, per spec §6.3/§14")
    fun operandBuiltinCallObjectGetRendersFaithfully() {
        val change = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("change")))
        val objectGet = Operand.BuiltinCall("object.get", listOf(change, Operand.Literal("\"ticket\""), Operand.Literal("\"none\"")))
        val condition = Condition.Comparison(objectGet, Operator.EQ, Operand.Literal("\"none\""))
        assertThat(ExpressionRenderer.render(condition, noAnchor))
            .isEqualTo("`change ▸ \"ticket\"` (default `\"none\"`) is `\"none\"`")
    }

    @Test
    @DisplayName("Operand.BuiltinCall(time.now_ns) renders as the fixed phrase 'the current time', per spec §6.3/§14")
    fun operandBuiltinCallTimeNowNsRendersFaithfully() {
        val timestamp = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("deployment"), PathSegment.Field("timestamp")))
        val condition = Condition.Comparison(Operand.BuiltinCall("time.now_ns", emptyList()), Operator.GT, timestamp)
        assertThat(ExpressionRenderer.render(condition, noAnchor))
            .isEqualTo("the current time is greater than `deployment ▸ timestamp`")
    }

    @Test
    @DisplayName("Operand.BuiltinCall(plus/minus/mul/div/rem) render via an infix-prose template, per spec §6.3/§14")
    fun operandBuiltinCallArithmeticRendersFaithfully() {
        val approvals = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("change"), PathSegment.Field("approvals_count")))
        val one = Operand.Literal("1")
        val plus = Condition.Comparison(Operand.BuiltinCall("plus", listOf(approvals, one)), Operator.GT, Operand.Literal("0"))
        val minus = Condition.Comparison(Operand.BuiltinCall("minus", listOf(approvals, one)), Operator.GT, Operand.Literal("0"))
        val mul = Condition.Comparison(Operand.BuiltinCall("mul", listOf(approvals, one)), Operator.GT, Operand.Literal("0"))
        val div = Condition.Comparison(Operand.BuiltinCall("div", listOf(approvals, one)), Operator.GT, Operand.Literal("0"))
        val rem = Condition.Comparison(Operand.BuiltinCall("rem", listOf(approvals, one)), Operator.GT, Operand.Literal("0"))
        assertThat(ExpressionRenderer.render(plus, noAnchor)).isEqualTo("`change ▸ approvals count` plus `1` is greater than `0`")
        assertThat(ExpressionRenderer.render(minus, noAnchor)).isEqualTo("`change ▸ approvals count` minus `1` is greater than `0`")
        assertThat(ExpressionRenderer.render(mul, noAnchor)).isEqualTo("`change ▸ approvals count` times `1` is greater than `0`")
        assertThat(ExpressionRenderer.render(div, noAnchor)).isEqualTo("`change ▸ approvals count` divided by `1` is greater than `0`")
        assertThat(ExpressionRenderer.render(rem, noAnchor)).isEqualTo("`change ▸ approvals count` modulo `1` is greater than `0`")
    }

    @Test
    @DisplayName("Subtraction preserves left-to-right operand order (non-commutative, unlike plus)")
    fun arithmeticMinusPreservesOperandOrder() {
        val a = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("a")))
        val b = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("b")))
        val condition = Condition.Comparison(Operand.BuiltinCall("minus", listOf(a, b)), Operator.EQ, Operand.Literal("0"))
        assertThat(ExpressionRenderer.render(condition, noAnchor)).isEqualTo("`a` minus `b` is `0`")
    }

    @Test
    @DisplayName("Nested arithmetic (opa's own left-associative parse tree, plus(plus(a,b),c)) renders correctly with no extra logic")
    fun chainedArithmeticRendersLeftAssociatively() {
        // `input.a + input.b + input.c` desugars to plus(plus(a, b), c) -- confirmed via a real
        // `opa parse` run. Each level renders via the exact same recursive mechanism.
        val a = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("a")))
        val b = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("b")))
        val c = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("c")))
        val innerPlus = Operand.BuiltinCall("plus", listOf(a, b))
        val outerPlus = Operand.BuiltinCall("plus", listOf(innerPlus, c))
        val condition = Condition.Comparison(outerPlus, Operator.GT, Operand.Literal("0"))
        assertThat(ExpressionRenderer.render(condition, noAnchor)).isEqualTo("`a` plus `b` plus `c` is greater than `0`")
    }

    @Test
    @DisplayName("Arithmetic nested inside another promoted operand builtin (count(x) + 1) composes as prose, never malformed nested backticks")
    fun arithmeticNestedWithAnotherOperandBuiltinComposesAsProse() {
        val approvals = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("change"), PathSegment.Field("approvals")))
        val count = Operand.BuiltinCall("count", listOf(approvals))
        val plusOne = Operand.BuiltinCall("plus", listOf(count, Operand.Literal("1")))
        val condition = Condition.Comparison(plusOne, Operator.GT, Operand.Literal("0"))
        assertThat(ExpressionRenderer.render(condition, noAnchor))
            .isEqualTo("the number of `change ▸ approvals` plus `1` is greater than `0`")
    }

    @Test
    @DisplayName("An unbound operand nested inside arithmetic still counts against the coverage footer, per Coverage's existing BuiltinCall.args recursion")
    fun anUnrenderedOperandNestedInsideArithmeticStillCountsAgainstCoverage() {
        val unbound = Operand.Unrendered("y")
        val arithmetic = Operand.BuiltinCall("plus", listOf(unbound, Operand.Literal("1")))
        val condition = Condition.Comparison(arithmetic, Operator.GT, Operand.Literal("0"))
        assertThat(ExpressionRenderer.render(condition, noAnchor)).isEqualTo("`y` plus `1` is greater than `0`")
        assertThat(Coverage.unrenderedOperandCount(listOf(condition))).isEqualTo(1)
    }

    @Test
    @DisplayName("Operand.BuiltinCall(concat) comma-joins exploded array elements, per spec §6.3/§14")
    fun operandBuiltinCallConcatWithExplodedArrayRendersFaithfully() {
        // args[0] is always the separator; args.drop(1) is one operand per array element
        // (AstMapper's own convention -- see mapConcatOperand's KDoc).
        val separator = Operand.Literal("\"/\"")
        val namespace = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("change"), PathSegment.Field("namespace")))
        val name = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("change"), PathSegment.Field("name")))
        val concat = Operand.BuiltinCall("concat", listOf(separator, namespace, name))
        val condition = Condition.Comparison(concat, Operator.EQ, Operand.Literal("\"a/b\""))
        assertThat(ExpressionRenderer.render(condition, noAnchor))
            .isEqualTo("`change ▸ namespace`, `change ▸ name` joined with `\"/\"` is `\"a/b\"`")
    }

    @Test
    @DisplayName("Operand.BuiltinCall(concat) with a single whole-collection operand needs no comma")
    fun operandBuiltinCallConcatWithWholeCollectionReferenceRendersFaithfully() {
        // A single args[1] element -- a path/var referencing an existing collection, not an
        // exploded literal -- joinToString naturally produces no comma for a one-element list.
        val separator = Operand.Literal("\"/\"")
        val parts = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("change"), PathSegment.Field("path_parts")))
        val concat = Operand.BuiltinCall("concat", listOf(separator, parts))
        val condition = Condition.Comparison(concat, Operator.EQ, Operand.Literal("\"a/b\""))
        assertThat(ExpressionRenderer.render(condition, noAnchor))
            .isEqualTo("`change ▸ path parts` joined with `\"/\"` is `\"a/b\"`")
    }

    @Test
    @DisplayName("An unbound element nested inside concat still counts against the coverage footer")
    fun anUnrenderedOperandNestedInsideConcatStillCountsAgainstCoverage() {
        val separator = Operand.Literal("\"/\"")
        val unbound = Operand.Unrendered("y")
        val concat = Operand.BuiltinCall("concat", listOf(separator, unbound))
        val condition = Condition.Comparison(concat, Operator.EQ, Operand.Literal("\"a/b\""))
        assertThat(ExpressionRenderer.render(condition, noAnchor)).isEqualTo("`y` joined with `\"/\"` is `\"a/b\"`")
        assertThat(Coverage.unrenderedOperandCount(listOf(condition))).isEqualTo(1)
    }

    @Test
    @DisplayName("An Operand.BuiltinCall name outside the recognised operand-position set is rejected, not silently mis-rendered")
    fun rejectsUnrecognisedOperandBuiltinName() {
        // AstMapper never constructs this (it only builds Operand.BuiltinCall from
        // count/lower/upper/object.get/time.now_ns/plus/minus/mul/div/rem/concat) -- this constructs
        // one directly to prove the defensive check actually fires. "sprintf" is a real builtin
        // that's explicitly documented (spec §6.3's own table) as head-value-only, never
        // operand-position -- genuinely never promoted here.
        val bogus = Condition.Comparison(Operand.BuiltinCall("sprintf", listOf(Operand.Literal("\"%v\""))), Operator.GT, Operand.Literal("0"))
        assertThatThrownBy { ExpressionRenderer.render(bogus, noAnchor) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    @DisplayName("AnyIndex inside an Operand.BuiltinCall's argument still prefixes the whole phrase with 'any of:'")
    fun anyOfPrefixPropagatesThroughOperandBuiltinCall() {
        val pathWithAnyIndex = Operand.Path(
            listOf(PathSegment.Field("input"), PathSegment.Field("pipeline"), PathSegment.Field("checks"), PathSegment.AnyIndex, PathSegment.Field("name")),
        )
        val condition = Condition.Comparison(Operand.BuiltinCall("count", listOf(pathWithAnyIndex)), Operator.GT, Operand.Literal("3"))
        assertThat(ExpressionRenderer.render(condition, noAnchor))
            .isEqualTo("any of: the number of `pipeline ▸ checks ▸ name` is greater than `3`")
    }

    @Nested
    @DisplayName("Condition.FunctionCall / Operand.Parameter (review item 8)")
    inner class FunctionCallRendering {

        private val noFunctionAnchor: (Condition.FunctionCall) -> String = { "#stub" }
        private val namespacePath = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("request"), PathSegment.Field("namespace")))

        @Test
        @DisplayName("a positive call links and names its argument")
        fun positiveCallLinksAndNamesItsArgument() {
            val condition = Condition.FunctionCall("k8s.admission", "is_exempt", listOf(namespacePath), negated = false)
            assertThat(ExpressionRenderer.render(condition, noAnchor, noFunctionAnchor))
                .isEqualTo("function [`is_exempt`](#stub) is satisfied for `request ▸ namespace`")
        }

        @Test
        @DisplayName("a negated call renders 'is not satisfied for'")
        fun negatedCallRendersIsNotSatisfied() {
            val condition = Condition.FunctionCall("k8s.admission", "is_exempt", listOf(namespacePath), negated = true)
            assertThat(ExpressionRenderer.render(condition, noAnchor, noFunctionAnchor))
                .isEqualTo("function [`is_exempt`](#stub) is not satisfied for `request ▸ namespace`")
        }

        @Test
        @DisplayName("CHALLENGE edge case: a zero-argument call drops the dangling 'for' clause entirely")
        fun zeroArgumentCallDropsTheForClause() {
            val condition = Condition.FunctionCall("scratch", "is_weekend", emptyList(), negated = false)
            assertThat(ExpressionRenderer.render(condition, noAnchor, noFunctionAnchor))
                .isEqualTo("function [`is_weekend`](#stub) is satisfied")
        }

        @Test
        @DisplayName("a call whose argument itself can't be resolved renders it inline, verbatim, never dropped or guessed")
        fun unresolvableArgumentRendersInlineVerbatim() {
            val condition = Condition.FunctionCall("scratch", "is_exempt", listOf(Operand.Unrendered("helper(input.x)")), negated = false)
            assertThat(ExpressionRenderer.render(condition, noAnchor, noFunctionAnchor))
                .isEqualTo("function [`is_exempt`](#stub) is satisfied for `helper(input.x)`")
        }

        @Test
        @DisplayName("AnyIndex inside a FunctionCall argument still prefixes the whole phrase with 'any of:'")
        fun anyOfPrefixPropagatesThroughFunctionCallArgs() {
            val pathWithAnyIndex = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("items"), PathSegment.AnyIndex, PathSegment.Field("id")))
            val condition = Condition.FunctionCall("scratch", "is_valid", listOf(pathWithAnyIndex), negated = false)
            assertThat(ExpressionRenderer.render(condition, noAnchor, noFunctionAnchor))
                .isEqualTo("any of: function [`is_valid`](#stub) is satisfied for `items ▸ id`")
        }

        @Test
        @DisplayName("CHALLENGE edge case (follow-up to review item 18): a null functionAnchorFor result -- the function has no card in THIS document -- renders unlinked, not a dead link")
        fun rendersUnlinkedWhenFunctionAnchorForReturnsNull() {
            val noAnchorAvailable: (Condition.FunctionCall) -> String? = { null }
            val condition = Condition.FunctionCall("k8s.admission", "is_exempt", listOf(namespacePath), negated = false)
            assertThat(ExpressionRenderer.render(condition, noAnchor, noAnchorAvailable))
                .isEqualTo("function `is_exempt` is satisfied for `request ▸ namespace`")
        }

        @Test
        @DisplayName("rendering a FunctionCall without supplying functionAnchorFor fails loudly rather than silently breaking the link")
        fun missingFunctionAnchorForFailsLoudly() {
            val condition = Condition.FunctionCall("scratch", "is_exempt", listOf(namespacePath), negated = false)
            assertThatThrownBy { ExpressionRenderer.render(condition, noAnchor) }.isInstanceOf(IllegalStateException::class.java)
        }

        @Test
        @DisplayName("Operand.Parameter renders as a named, known parameter -- distinct from a bare Operand.Variable")
        fun parameterRendersDistinctlyFromVariable() {
            val condition = Condition.Membership(negated = false, member = Operand.Parameter("ns"), collection = Operand.Literal("\"kube-system\", \"kube-public\""))
            assertThat(ExpressionRenderer.render(condition, noAnchor))
                .isEqualTo("parameter `ns` is one of `\"kube-system\", \"kube-public\"`")
        }
    }

    @Nested
    @DisplayName("count(x) emptiness idioms (review item 15)")
    inner class EmptinessIdioms {

        private val items = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("items")))
        private fun count(op: Operator, literal: String, negated: Boolean = false, arg: Operand = items) =
            Condition.Comparison(Operand.BuiltinCall("count", listOf(arg)), op, Operand.Literal(literal), negated)
        private fun render(c: Condition) = ExpressionRenderer.render(c, noAnchor)

        @ParameterizedTest
        @CsvSource("EQ,0,`items` is empty", "NEQ,0,`items` is not empty", "GT,0,`items` is not empty")
        @DisplayName("== 0 is empty; != 0 and > 0 are not empty")
        fun exactIdioms(op: Operator, literal: String, expected: String) {
            assertThat(render(count(op, literal))).isEqualTo(expected)
        }

        @ParameterizedTest
        @CsvSource(
            "GTE,0", "GT,1", "GTE,1", "LT,1", "LTE,0", "LT,0", "EQ,1", "EQ,5", "NEQ,1",
        )
        @DisplayName("CHALLENGE: every other operator/literal keeps the number-of wording -- the idiom is never widened")
        fun notWidened(op: Operator, literal: String) {
            assertThat(render(count(op, literal))).startsWith("the number of ").doesNotContain("empty")
        }

        @Test
        @DisplayName("CHALLENGE: a negated comparison keeps the number-of wording (not asserting the opposite emptiness)")
        fun negatedIsNotRewritten() {
            assertThat(render(count(Operator.EQ, "0", negated = true))).isEqualTo("the number of `items` is not `0`")
        }

        @Test
        @DisplayName("CHALLENGE: `0.0`/`\"0\"` are not the literal 0 and keep the number-of wording")
        fun otherZeroSpellingsAreNotMatched() {
            assertThat(render(count(Operator.EQ, "0.0"))).startsWith("the number of ")
            assertThat(render(count(Operator.EQ, "\"0\""))).startsWith("the number of ")
        }

        @Test
        @DisplayName("AnyIndex in the argument keeps the any-of prefix; a non-path argument (Variable) works too")
        fun anyOfPrefixAndVariableArgument() {
            val pods = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("pods"), PathSegment.AnyIndex, PathSegment.Field("containers")))
            assertThat(render(count(Operator.EQ, "0", arg = pods))).isEqualTo("any of: `pods ▸ containers` is empty")
            assertThat(render(count(Operator.GT, "0", arg = Operand.Variable("missing")))).isEqualTo("`missing` is not empty")
        }

        @Test
        @DisplayName("only `count` qualifies: another promoted builtin compared with 0 keeps its own wording")
        fun otherBuiltinsUnaffected() {
            val sum = Condition.Comparison(Operand.BuiltinCall("sum", listOf(items)), Operator.GT, Operand.Literal("0"))
            assertThat(render(sum)).isEqualTo("the sum of `items` is greater than `0`")
        }
    }

    @Nested
    @DisplayName("Operand.RuleValueReference (review item 10) / ExpressionRenderer.renderValue (follow-up to review item 10)")
    inner class RuleValueReferenceRendering {

        private val reference = Operand.RuleValueReference("rulevalues.config", "max_items")

        @Test
        fun rendersAsALinkToTheReferencedRulesOwnCard() {
            val anchorFor: (Operand.RuleValueReference) -> String = { "rulevalues-config.md#${it.ruleName}" }
            val condition = Condition.Comparison(Operand.Literal("5"), Operator.LT, reference)
            assertThat(ExpressionRenderer.render(condition, noAnchor, ruleValueAnchorFor = anchorFor))
                .isEqualTo("`5` is less than rule [`max_items`](rulevalues-config.md#max_items)")
        }

        @Test
        @DisplayName("CHALLENGE edge case (follow-up to review item 18): a null ruleValueAnchorFor result -- no card in THIS document -- renders unlinked, not a dead link")
        fun rendersUnlinkedWhenRuleValueAnchorForReturnsNull() {
            val noAnchorAvailable: (Operand.RuleValueReference) -> String? = { null }
            val condition = Condition.Comparison(Operand.Literal("5"), Operator.LT, reference)
            assertThat(ExpressionRenderer.render(condition, noAnchor, ruleValueAnchorFor = noAnchorAvailable))
                .isEqualTo("`5` is less than rule `max_items`")
        }

        @Test
        @DisplayName("renderValue (used for RuleBody.yieldsValue, follow-up to review item 10) renders a bare operand standalone")
        fun renderValueRendersABareOperandStandalone() {
            val anchorFor: (Operand.RuleValueReference) -> String = { "#${it.ruleName}" }
            assertThat(ExpressionRenderer.renderValue(reference, anchorFor)).isEqualTo("rule [`max_items`](#max_items)")
            assertThat(ExpressionRenderer.renderValue(Operand.Literal("\"high\""))).isEqualTo("`\"high\"`")
        }

        @Test
        @DisplayName("rendering a RuleValueReference without supplying ruleValueAnchorFor fails loudly rather than silently breaking the link")
        fun missingRuleValueAnchorForFailsLoudly() {
            val condition = Condition.Comparison(Operand.Literal("5"), Operator.LT, reference)
            assertThatThrownBy { ExpressionRenderer.render(condition, noAnchor) }.isInstanceOf(IllegalStateException::class.java)
        }
    }
}
