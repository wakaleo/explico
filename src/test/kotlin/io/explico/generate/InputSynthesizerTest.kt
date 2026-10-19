/**
 * Unit tests for the pure input solver (no opa): a rule body's conditions in, a JSON `input` that satisfies them out,
 * or NotSolvable. Whether a solved input really makes the rule match is checked by the real `opa eval` at generation
 * time and in the acceptance tests; here only the mapping from conditions to values is pinned.
 */
package io.explico.generate

import io.explico.model.Condition
import io.explico.model.LiteralCollection
import io.explico.model.Operand
import io.explico.model.Operator
import io.explico.model.PathSegment
import io.explico.model.RuleBody
import io.explico.model.SourceRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class InputSynthesizerTest {

    // ---- builders ----------------------------------------------------------------------------------------------
    private fun body(vararg conditions: Condition, args: List<Operand.Path> = emptyList()) =
        RuleBody(conditions.toList(), null, null, SourceRef("f.rego", 1), "", messageArguments = args)

    private fun path(vararg segments: Any): Operand.Path =
        Operand.Path(listOf<PathSegment>(PathSegment.Field("input")) + segments.map { if (it is PathSegment) it else PathSegment.Field(it as String) })

    private val any = PathSegment.AnyIndex
    private fun each(variable: String) = PathSegment.VarIndex(variable)
    private fun lit(rendered: String) = Operand.Literal(rendered)
    private fun set(vararg elements: String) = Operand.Literal(elements.joinToString(", "), LiteralCollection.SET)
    private fun eq(left: Operand, right: Operand, negated: Boolean = false) = Condition.Comparison(left, Operator.EQ, right, negated)
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun solved(b: RuleBody, violate: Int? = null): JsonObject =
        (InputSynthesizer.synthesize(b, violate) as? Synthesis.Solved)?.input
            ?: error("expected Solved but got ${InputSynthesizer.synthesize(b, violate)}")

    private fun reason(b: RuleBody, violate: Int? = null): String =
        (InputSynthesizer.synthesize(b, violate) as? Synthesis.NotSolvable)?.reason ?: error("expected NotSolvable")

    companion object {
        @JvmStatic
        fun scalarCases(): Stream<Arguments> = Stream.of(
            // op, negated, literal, expected value of input.x (as JSON)
            Arguments.of(Operator.EQ, false, "10", "10"),
            Arguments.of(Operator.NEQ, false, "10", "11"),
            Arguments.of(Operator.GT, false, "10", "11"),
            Arguments.of(Operator.GTE, false, "10", "10"),
            Arguments.of(Operator.LT, false, "10", "9"),
            Arguments.of(Operator.LTE, false, "10", "10"),
            // negation inverts the operator: not (x > 10) is x <= 10, not (x == 10) is x != 10 ...
            Arguments.of(Operator.GT, true, "10", "10"),
            Arguments.of(Operator.GTE, true, "10", "9"),
            Arguments.of(Operator.LT, true, "10", "10"),
            Arguments.of(Operator.LTE, true, "10", "11"),
            Arguments.of(Operator.EQ, true, "10", "11"),
            Arguments.of(Operator.NEQ, true, "10", "10"),
            Arguments.of(Operator.EQ, false, "\"prod\"", "\"prod\""),
            Arguments.of(Operator.NEQ, false, "\"prod\"", "\"prod-other\""),
            Arguments.of(Operator.EQ, false, "true", "true"),
            Arguments.of(Operator.NEQ, false, "true", "false"),
            Arguments.of(Operator.EQ, false, "null", "null"),
            Arguments.of(Operator.EQ, false, "2.5", "2.5"),
            Arguments.of(Operator.GT, false, "2.5", "3.5"),
        )
    }


    // ---- comparisons ---------------------------------------------------------------------------------------------
    @Nested
    inner class Comparisons {

        @ParameterizedTest(name = "{0} negated={1} against {2} gives x = {3}")
        @MethodSource("io.explico.generate.InputSynthesizerTest#scalarCases")
        fun pathAgainstALiteral(op: Operator, negated: Boolean, literal: String, expected: String) {
            val input = solved(body(Condition.Comparison(path("x"), op, lit(literal), negated)))
            assertThat(input).isEqualTo(json("""{"x": $expected}"""))
        }

        @Test
        fun aLiteralOnTheLeftIsTheMirroredComparison() {
            // 10 < input.x  means  input.x > 10
            assertThat(solved(body(Condition.Comparison(lit("10"), Operator.LT, path("x"))))).isEqualTo(json("""{"x": 11}"""))
        }

        @Test
        fun anOrderingComparisonAgainstANonNumberIsNotSolvable() {
            assertThat(reason(body(Condition.Comparison(path("x"), Operator.GT, lit("\"a\""))))).contains("non-number")
        }

        @Test
        fun nestedFieldsBuildNestedObjectsInInsertionOrder() {
            val input = solved(body(eq(path("a", "b", "c"), lit("1")), eq(path("a", "d"), lit("2")), eq(path("z"), lit("3"))))
            assertThat(input).isEqualTo(json("""{"a": {"b": {"c": 1}, "d": 2}, "z": 3}"""))
            assertThat(input.keys).containsExactly("a", "z") // deterministic: order of first use
        }

        @Test
        fun aKeyLiteralSegmentIsAnObjectKey() {
            val labels = path("labels", PathSegment.KeyLiteral("signed-off-by"))
            assertThat(solved(body(eq(labels, lit("\"bob\""))))).isEqualTo(json("""{"labels": {"signed-off-by": "bob"}}"""))
        }

        @Test
        fun twoPathsEqualShareOneValue() {
            val input = solved(body(eq(path("a"), path("b"))))
            assertThat(input["a"]).isEqualTo(input["b"])
            assertThat(input["a"]).isNotNull()
        }

        @Test
        fun twoPathsNotEqualGetDifferentValues() {
            val input = solved(body(Condition.Comparison(path("a"), Operator.NEQ, path("b"))))
            assertThat(input["a"]).isNotEqualTo(input["b"])
            assertThat(input["a"]).isNotNull()
            assertThat(input["b"]).isNotNull()
        }

        @Test
        fun notEqualBetweenTwoPathsAlreadyPinnedToTheSameValueIsAContradiction() {
            // a == "x", b == "x", a != b: the old code kept both at "x", i.e. a candidate that VIOLATES the condition.
            val b = body(eq(path("a"), lit("\"x\"")), eq(path("b"), lit("\"x\"")), Condition.Comparison(path("a"), Operator.NEQ, path("b")))
            assertThat(reason(b)).contains("conflict")
        }

        @Test
        fun notEqualBetweenTwoPathsAlreadyPinnedToDifferentValuesIsAlreadySatisfied() {
            val b = body(eq(path("a"), lit("\"x\"")), eq(path("b"), lit("\"y\"")), Condition.Comparison(path("a"), Operator.NEQ, path("b")))
            assertThat(solved(b)).isEqualTo(json("""{"a": "x", "b": "y"}"""))
        }

        @Test
        fun anEqualityAlreadyPinnedByALiteralIsCopiedToTheOtherPath() {
            val input = solved(body(eq(path("a"), lit("\"x\"")), eq(path("a"), path("b"))))
            assertThat(input).isEqualTo(json("""{"a": "x", "b": "x"}"""))
        }

        @Test
        fun orderingBetweenTwoPathsIsSatisfiedWithNumbers() {
            val gt = solved(body(Condition.Comparison(path("a"), Operator.GT, path("b"))))
            assertThat(gt["a"].toString().toInt()).isGreaterThan(gt["b"].toString().toInt())
            val lt = solved(body(Condition.Comparison(path("a"), Operator.LT, path("b"))))
            assertThat(lt["a"].toString().toInt()).isLessThan(lt["b"].toString().toInt())
        }

        @Test
        fun twoConditionsOnTheSamePathThatAgreeAreFine() {
            assertThat(solved(body(eq(path("x"), lit("1")), Condition.Comparison(path("x"), Operator.GTE, lit("1"))))).isEqualTo(json("""{"x": 1}"""))
        }

        @Test
        fun contradictoryConstraintsAreNotSolvable() {
            assertThat(reason(body(eq(path("x"), lit("1")), eq(path("x"), lit("2"))))).contains("conflict")
        }

        @Test
        fun twoLiteralsAreNotAComparisonOfAnyPath() {
            assertThat(reason(body(eq(lit("1"), lit("1"))))).contains("path")
        }
    }

    // ---- truthiness, membership, builtins -------------------------------------------------------------------------
    @Nested
    inner class OtherConditions {

        @Test
        fun truthyIsTrueAndNegatedTruthyIsFalse() {
            assertThat(solved(body(Condition.Truthy(path("flag"), negated = false)))).isEqualTo(json("""{"flag": true}"""))
            assertThat(solved(body(Condition.Truthy(path("flag"), negated = true)))).isEqualTo(json("""{"flag": false}"""))
        }

        @Test
        fun membershipOfAPathInALiteralCollectionPicksAMember() {
            assertThat(solved(body(Condition.Membership(false, path("env"), set("\"prod\"", "\"stage\""))))).isEqualTo(json("""{"env": "prod"}"""))
        }

        @Test
        fun notAMemberPicksAValueOutsideTheCollection() {
            val value = solved(body(Condition.Membership(true, path("env"), set("\"prod\"", "\"stage\""))))["env"].toString()
            assertThat(value).isNotIn("\"prod\"", "\"stage\"")
        }

        @Test
        fun aLiteralInAPathPutsItInTheArray() {
            assertThat(solved(body(Condition.Membership(false, lit("\"admin\""), path("user", "roles"))))).isEqualTo(json("""{"user": {"roles": ["admin"]}}"""))
        }

        @Test
        fun aLiteralNotInAPathLeavesItOutOfTheArray() {
            val roles = solved(body(Condition.Membership(true, lit("\"admin\""), path("user", "roles"))))["user"]!!.jsonObject["roles"].toString()
            assertThat(roles).doesNotContain("\"admin\"")
        }

        @Test
        fun aPathInAPathSharesTheValue() {
            val input = solved(body(Condition.Membership(false, path("x"), path("xs"))))
            assertThat(input["xs"].toString()).isEqualTo("[" + input["x"].toString() + "]")
        }

        @Test
        fun startswithEndswithContainsBuildMatchingStrings() {
            fun builtin(name: String, negated: Boolean = false) = Condition.BuiltinCall(name, listOf(path("s"), lit("\"ab\"")), negated)
            assertThat(solved(body(builtin("startswith")))["s"].toString().trim('"')).startsWith("ab")
            assertThat(solved(body(builtin("endswith")))["s"].toString().trim('"')).endsWith("ab")
            assertThat(solved(body(builtin("contains")))["s"].toString().trim('"')).contains("ab")
        }

        @Test
        fun negatedStringBuiltinsBuildNonMatchingStrings() {
            fun builtin(name: String) = Condition.BuiltinCall(name, listOf(path("s"), lit("\"z\"")), negated = true)
            // a naive "zzz-no" would still START with "z": the generator must check, not assume
            assertThat(solved(body(builtin("startswith")))["s"].toString().trim('"')).doesNotStartWith("z")
            assertThat(solved(body(builtin("endswith")))["s"].toString().trim('"')).doesNotEndWith("z")
            assertThat(solved(body(builtin("contains")))["s"].toString().trim('"')).doesNotContain("z")
        }

        @Test
        fun typeChecksBuildValuesOfThatType() {
            fun check(name: String, negated: Boolean = false) = body(Condition.BuiltinCall(name, listOf(path("v")), negated))
            assertThat(solved(check("is_string"))).isEqualTo(json("""{"v": "s"}"""))
            assertThat(solved(check("is_number"))).isEqualTo(json("""{"v": 1}"""))
            assertThat(solved(check("is_boolean"))).isEqualTo(json("""{"v": true}"""))
            assertThat(solved(check("is_string", negated = true))).isEqualTo(json("""{"v": 1}"""))
            assertThat(solved(check("is_number", negated = true))).isEqualTo(json("""{"v": "s"}"""))
        }
    }

    // ---- iteration -------------------------------------------------------------------------------------------------
    @Nested
    inner class Iteration {

        @Test
        fun someInCreatesAOneElementArrayAndLaterConditionsFillTheElement() {
            val b = body(
                Condition.SomeIn("c", path("containers")),
                eq(path("containers", each("c"), "privileged"), lit("true")),
            )
            assertThat(solved(b)).isEqualTo(json("""{"containers": [{"privileged": true}]}"""))
        }

        @Test
        fun aWildcardPathUsesTheOneElementToo() {
            val b = body(eq(path("containers", any, "image"), lit("\"busybox\"")))
            assertThat(solved(b)).isEqualTo(json("""{"containers": [{"image": "busybox"}]}"""))
        }

        @Test
        fun aWildcardAtTheEndMeansAnArrayOfScalars() {
            assertThat(solved(body(eq(path("tags", any), lit("\"a\""))))).isEqualTo(json("""{"tags": ["a"]}"""))
        }

        @Test
        fun nestedIterationNestsTheElements() {
            val b = body(
                Condition.SomeIn("s", path("stages")),
                Condition.SomeIn("t", path("stages", each("s"), "tasks")),
                eq(path("stages", each("s"), "tasks", each("t"), "done"), lit("false")),
            )
            assertThat(solved(b)).isEqualTo(json("""{"stages": [{"tasks": [{"done": false}]}]}"""))
        }

        @Test
        fun anElementFieldAndANormalFieldCoexist() {
            val b = body(eq(path("env"), lit("\"production\"")), Condition.SomeIn("c", path("containers")), eq(path("containers", each("c"), "name"), lit("\"app\"")))
            assertThat(solved(b)).isEqualTo(json("""{"env": "production", "containers": [{"name": "app"}]}"""))
        }

        @Test
        fun aVariableWithNoGeneratedElementIsNotSolvable() {
            assertThat(reason(body(eq(path("containers", each("ghost"), "x"), lit("1"))))).contains("ghost")
        }
    }

    // ---- message arguments -------------------------------------------------------------------------------------------
    @Nested
    inner class MessageArguments {

        @Test
        fun anArgumentNoConditionDefinesGetsAPlaceholder() {
            val input = solved(body(eq(path("encrypted"), lit("false")), args = listOf(path("name"))))
            assertThat(input).isEqualTo(json("""{"encrypted": false, "name": "sample"}"""))
        }

        @Test
        fun anArgumentAConditionAlreadyDefinedIsLeftAlone() {
            val input = solved(body(eq(path("name"), lit("\"logs\"")), args = listOf(path("name"))))
            assertThat(input).isEqualTo(json("""{"name": "logs"}"""))
        }

        @Test
        fun anIteratedElementsFieldIsFilledToo() {
            val b = body(Condition.SomeIn("c", path("containers")), args = listOf(path("containers", each("c"), "name")))
            assertThat(solved(b)).isEqualTo(json("""{"containers": [{"name": "sample"}]}"""))
        }

        @Test
        fun anArgumentOnAnUnsolvableVariableMakesTheBodyNotSolvable() {
            assertThat(reason(body(args = listOf(path("containers", each("ghost"), "name"))))).contains("ghost")
        }

        @Test
        fun aBodyWithNoConditionsAndNoArgumentsNeedsAnEmptyInput() {
            assertThat(solved(body())).isEqualTo(json("{}"))
        }
    }

    // ---- near-misses -----------------------------------------------------------------------------------------------
    @Nested
    inner class NearMisses {

        @Test
        fun violatingAComparisonBuildsTheOppositeValueAndLeavesTheRestAlone() {
            val b = body(eq(path("env"), lit("\"production\"")), Condition.Comparison(path("size"), Operator.GT, lit("10")))
            assertThat(solved(b, violate = 0)).isEqualTo(json("""{"env": "production-other", "size": 11}"""))
            assertThat(solved(b, violate = 1)).isEqualTo(json("""{"env": "production", "size": 10}"""))
        }

        @Test
        fun violatingANegatedConditionHoldsIt() {
            assertThat(solved(body(Condition.Comparison(path("x"), Operator.EQ, lit("1"), negated = true)), violate = 0)).isEqualTo(json("""{"x": 1}"""))
        }

        @Test
        fun violatingTruthyMembershipAndBuiltinsFlipsEach() {
            assertThat(solved(body(Condition.Truthy(path("f"), false)), violate = 0)).isEqualTo(json("""{"f": false}"""))
            assertThat(solved(body(Condition.Membership(false, path("e"), set("\"a\"", "\"b\""))), violate = 0)["e"].toString()).isNotIn("\"a\"", "\"b\"")
            assertThat(solved(body(Condition.BuiltinCall("is_string", listOf(path("v")), false)), violate = 0)).isEqualTo(json("""{"v": 1}"""))
        }

        @Test
        fun aViolationThatContradictsAnotherConditionIsNotSolvable() {
            // Two identical equalities: breaking the first one sets x to something else, the second then demands x == 1.
            val b = body(eq(path("x"), lit("1")), eq(path("x"), lit("1")))
            assertThat(reason(b, violate = 0)).contains("conflict")
        }

        @Test
        fun anIterationCannotBeViolated() {
            assertThat(reason(body(Condition.SomeIn("c", path("xs"))), violate = 0)).contains("iteration")
        }
    }

    // ---- what is not modelled ----------------------------------------------------------------------------------------
    @Nested
    inner class NotSolvableConstructs {

        @Test
        fun everyUnmodelledConditionKindIsReportedNotGuessed() {
            val cases = mapOf(
                "RuleReference" to Condition.RuleReference("p", "r", false),
                "FunctionCall" to Condition.FunctionCall("p", "f", listOf(path("x")), false),
                "Unrendered" to Condition.Unrendered("every x in y { true }", "every"),
                "key variable" to Condition.SomeIn("v", path("m"), key = "k"),
                "unsupported builtin" to Condition.BuiltinCall("net.cidr_contains", listOf(path("a"), lit("\"10.0.0.0/8\"")), false),
            )
            for ((label, condition) in cases) {
                assertThat(InputSynthesizer.synthesize(body(condition))).describedAs(label).isInstanceOf(Synthesis.NotSolvable::class.java)
            }
        }

        @Test
        fun aDataRootedPathNeedsADataDocumentSoItIsNotSolvable() {
            val data = Operand.Path(listOf(PathSegment.Field("data"), PathSegment.Field("limits"), PathSegment.Field("max")))
            assertThat(reason(body(eq(data, lit("1"))))).contains("data")
        }

        @Test
        fun aCountComparisonIsNotModelled() {
            val count = Operand.BuiltinCall("count", listOf(path("items")))
            assertThat(reason(body(Condition.Comparison(count, Operator.GT, lit("2"))))).contains("BuiltinCall")
        }

        @Test
        fun aNotSolvableConditionAnywhereInTheBodyMakesTheWholeBodyNotSolvable() {
            val b = body(eq(path("a"), lit("1")), Condition.RuleReference("p", "r", false))
            assertThat(InputSynthesizer.synthesize(b)).isInstanceOf(Synthesis.NotSolvable::class.java)
        }
    }

    // ---- determinism -------------------------------------------------------------------------------------------------
    @Test
    fun theSameBodyAlwaysGivesTheSameBytes() {
        val b = body(
            eq(path("env"), lit("\"production\"")),
            Condition.SomeIn("c", path("containers")),
            eq(path("containers", each("c"), "privileged"), lit("true")),
            args = listOf(path("containers", each("c"), "name"), path("node")),
        )
        assertThat(solved(b).toString()).isEqualTo(solved(b).toString())
        assertThat(solved(b).toString()).isEqualTo("""{"env":"production","containers":[{"privileged":true,"name":"sample"}],"node":"sample"}""")
    }
}
