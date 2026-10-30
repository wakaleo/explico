/**
 * Spec §14.60: the solver's `count(<path>) <op> <int>` and `time.parse_rfc3339_ns(<path>) <op> <path>` support, on hand-built conditions (no opa). Whether a solved
 * input really makes the rule match is checked by the real `opa eval` at generation time and in RealisticGeneratorExtensionsAcceptanceIT.
 */
package io.explico.generate

import io.explico.model.Condition
import io.explico.model.Operand
import io.explico.model.Operator
import io.explico.model.PathSegment
import io.explico.model.RuleBody
import io.explico.model.SourceRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant
import java.util.stream.Stream

class InputSynthesizerCountTimeTest {

    private fun body(vararg conditions: Condition) = RuleBody(conditions.toList(), null, null, SourceRef("f.rego", 1), "")
    private fun path(vararg names: String) = Operand.Path((listOf("input") + names).map { PathSegment.Field(it) })
    private fun count(path: Operand.Path) = Operand.BuiltinCall("count", listOf(path))
    private fun time(path: Operand.Path) = Operand.BuiltinCall("time.parse_rfc3339_ns", listOf(path))
    private fun cmp(left: Operand, op: Operator, right: Operand, negated: Boolean = false) = Condition.Comparison(left, op, right, negated)
    private fun n(value: Long) = Operand.Literal(value.toString())

    private fun solved(b: RuleBody, violate: Int? = null): JsonObject =
        (InputSynthesizer.synthesize(b, violate) as? Synthesis.Solved)?.input ?: error("expected Solved but got ${InputSynthesizer.synthesize(b, violate)}")

    private fun reason(b: RuleBody, violate: Int? = null): String =
        (InputSynthesizer.synthesize(b, violate) as? Synthesis.NotSolvable)?.reason ?: error("expected NotSolvable")

    private fun sizeOf(input: JsonObject, field: String) = (input.getValue(field) as JsonArray).size

    companion object {
        @JvmStatic
        fun timeOps(): Stream<Arguments> = Stream.of(
            Arguments.of(Operator.LT, { ts: Long, now: Long -> ts < now }),
            Arguments.of(Operator.LTE, { ts: Long, now: Long -> ts <= now }),
            Arguments.of(Operator.EQ, { ts: Long, now: Long -> ts == now }),
            Arguments.of(Operator.NEQ, { ts: Long, now: Long -> ts != now }),
            Arguments.of(Operator.GT, { ts: Long, now: Long -> ts > now }),
            Arguments.of(Operator.GTE, { ts: Long, now: Long -> ts >= now }),
        )

        @JvmStatic
        fun countCases(): Stream<Arguments> = Stream.of(
            // op, literal, expected list length
            Arguments.of(Operator.EQ, 3L, 3), Arguments.of(Operator.EQ, 0L, 0), Arguments.of(Operator.NEQ, 3L, 4), Arguments.of(Operator.GT, 3L, 4),
            Arguments.of(Operator.GTE, 3L, 3), Arguments.of(Operator.LT, 3L, 2), Arguments.of(Operator.LTE, 3L, 3), Arguments.of(Operator.GT, 0L, 1),
        )
    }

    @ParameterizedTest(name = "count(x) {0} {1} -> a list of {2}")
    @MethodSource("countCases")
    @DisplayName("the operator table: the shortest list that makes the comparison true")
    fun table(op: Operator, literal: Long, expected: Int) {
        assertThat(sizeOf(solved(body(cmp(count(path("x")), op, n(literal)))), "x")).isEqualTo(expected)
    }

    @Test
    @DisplayName("operands the other way round: 2 < count(x) is count(x) > 2")
    fun mirrored() {
        assertThat(sizeOf(solved(body(cmp(n(2), Operator.LT, count(path("x"))))), "x")).isEqualTo(3)
    }

    @Test
    @DisplayName("negation and a near-miss invert the operator: not count(x) > 2 is a list of 2; violating count(x) >= 3 is a list of 2; violating count(x) == 0 is a list of 1")
    fun invertedAndViolated() {
        assertThat(sizeOf(solved(body(cmp(count(path("x")), Operator.GT, n(2), negated = true))), "x")).isEqualTo(2)
        assertThat(sizeOf(solved(body(cmp(count(path("x")), Operator.GTE, n(3))), violate = 0), "x")).isEqualTo(2)
        assertThat(sizeOf(solved(body(cmp(count(path("x")), Operator.EQ, n(0))), violate = 0), "x")).isEqualTo(1)
    }

    @Test
    @DisplayName("the elements are distinct placeholder strings")
    fun elements() {
        assertThat(solved(body(cmp(count(path("x")), Operator.EQ, n(2))))["x"].toString()).isEqualTo("[\"item-1\",\"item-2\"]")
    }

    @Test
    @DisplayName("a count that would need a negative length is not solvable: count(x) < 0, and the near-miss of count(x) >= 0")
    fun negative() {
        assertThat(reason(body(cmp(count(path("x")), Operator.LT, n(0))))).contains("negative")
        assertThat(reason(body(cmp(count(path("x")), Operator.GTE, n(0))), violate = 0)).contains("negative")
    }

    @Test
    @DisplayName("a huge count is not solvable and never allocates a list: 1,000,000 and 51 are refused, 50 is listed")
    fun tooLarge() {
        assertThat(reason(body(cmp(count(path("x")), Operator.GTE, n(1_000_000))))).contains("too large")
        assertThat(reason(body(cmp(count(path("x")), Operator.GTE, n(51))))).contains("too large")
        assertThat(sizeOf(solved(body(cmp(count(path("x")), Operator.GTE, n(50)))), "x")).isEqualTo(50)
    }

    @Test
    @DisplayName("the same path counted twice: equal targets are one list, different ones conflict (GE-007's contradiction)")
    fun samePathTwice() {
        assertThat(sizeOf(solved(body(cmp(count(path("x")), Operator.GTE, n(2)), cmp(count(path("x")), Operator.LTE, n(2)))), "x")).isEqualTo(2)
        assertThat(reason(body(cmp(count(path("x")), Operator.GT, n(3)), cmp(count(path("x")), Operator.LT, n(2))))).contains("conflicting")
        // count(x) == 0 then count(x) > 2: an EMPTY list is still a pinned length
        assertThat(reason(body(cmp(count(path("x")), Operator.EQ, n(0)), cmp(count(path("x")), Operator.GT, n(2))))).contains("conflicting")
    }

    @Test
    @DisplayName("a count on a list that iteration already filled with element objects, or whose members membership already chose, conflicts unless the sizes agree")
    fun withOtherConstraints() {
        val iterated = Condition.SomeIn("i", path("x"), null)
        assertThat(reason(body(iterated, cmp(count(path("x")), Operator.GTE, n(2))))).contains("conflicting")
        val member = Condition.Membership(negated = false, member = Operand.Literal("\"a\""), collection = path("x"))
        assertThat(sizeOf(solved(body(member, cmp(count(path("x")), Operator.EQ, n(1)))), "x")).isEqualTo(1)
        assertThat(reason(body(member, cmp(count(path("x")), Operator.EQ, n(3))))).contains("conflicting")
        // membership AFTER a count that pinned the length would add a member and break it
        assertThat(reason(body(cmp(count(path("x")), Operator.EQ, n(1)), member))).contains("conflicting")
    }

    @Test
    @DisplayName("a count compared with a non-integer, a string or another path is not solvable")
    fun notAnInteger() {
        assertThat(reason(body(cmp(count(path("x")), Operator.GT, Operand.Literal("1.5"))))).contains("non-integer")
        assertThat(reason(body(cmp(count(path("x")), Operator.GT, Operand.Literal("\"a\""))))).contains("number")
        assertThat(InputSynthesizer.synthesize(body(cmp(count(path("x")), Operator.GT, path("y"))))).isInstanceOf(Synthesis.NotSolvable::class.java)
    }

    @Test
    @DisplayName("a count over a path that is not a list slot or not rooted at input stays unsolvable")
    fun unsolvablePaths() {
        val data = Operand.Path(listOf(PathSegment.Field("data"), PathSegment.Field("limits")))
        assertThat(reason(body(cmp(count(data), Operator.GT, n(1))))).contains("data")
        assertThat(reason(body(cmp(count(path("s")), Operator.GT, n(1)), cmp(path("s"), Operator.EQ, Operand.Literal("\"text\""))))).contains("conflicting")
    }

    @Test
    @DisplayName("another builtin in operand position, or a count with two arguments, is not modelled")
    fun otherBuiltins() {
        assertThat(reason(body(cmp(Operand.BuiltinCall("lower", listOf(path("x"))), Operator.EQ, Operand.Literal("\"a\""))))).contains("lower")
        assertThat(InputSynthesizer.synthesize(body(cmp(Operand.BuiltinCall("count", listOf(path("x"), path("y"))), Operator.EQ, n(1))))).isInstanceOf(Synthesis.NotSolvable::class.java)
    }

    // ---- time ----------------------------------------------------------------------------------------------------

    private fun nowOf(input: JsonObject) = (input.getValue("now") as JsonPrimitive).content.toLong()
    private fun nanosOf(input: JsonObject, field: String) = Instant.parse((input.getValue(field) as JsonPrimitive).content).let { it.epochSecond * 1_000_000_000L + it.nano }

    @ParameterizedTest(name = "time(ts) {0} now")
    @MethodSource("timeOps")
    @DisplayName("each operator puts the timestamp on the right side of the pinned instant")
    fun timeOperators(op: Operator, holds: (Long, Long) -> Boolean) {
        val input = solved(body(cmp(time(path("ts")), op, path("now"))))
        assertThat(holds(nanosOf(input, "ts"), nowOf(input))).describedAs("$op").isTrue()
    }

    @Test
    @DisplayName("operands the other way round: now < time(ts) puts ts after now")
    fun timeMirrored() {
        val input = solved(body(cmp(path("now"), Operator.LT, time(path("ts")))))
        assertThat(nanosOf(input, "ts")).isGreaterThan(nowOf(input))
    }

    @Test
    @DisplayName("negation and near-miss: not (time < now) puts ts at or after now; violating time > now puts ts before it")
    fun timeInverted() {
        val a = solved(body(cmp(time(path("ts")), Operator.LT, path("now"), negated = true)))
        assertThat(nanosOf(a, "ts")).isGreaterThanOrEqualTo(nowOf(a))
        val b = solved(body(cmp(time(path("ts")), Operator.GT, path("now"))), violate = 0)
        assertThat(nanosOf(b, "ts")).isLessThanOrEqualTo(nowOf(b))
    }

    @Test
    @DisplayName("the pinned instant is exactly 1700000000000000000 -- a whole JSON integer, no precision lost")
    fun nowIsExact() {
        assertThat(solved(body(cmp(time(path("ts")), Operator.LT, path("now"))))["now"].toString()).isEqualTo("1700000000000000000")
    }

    @Test
    @DisplayName("two time conditions share one now; a now already pinned to something else conflicts; a timestamp path already set conflicts")
    fun timeConflicts() {
        val input = solved(body(cmp(time(path("a")), Operator.LT, path("now")), cmp(time(path("b")), Operator.GT, path("now"))))
        assertThat(nanosOf(input, "a")).isLessThan(nowOf(input))
        assertThat(nanosOf(input, "b")).isGreaterThan(nowOf(input))
        assertThat(reason(body(cmp(path("now"), Operator.EQ, n(5)), cmp(time(path("ts")), Operator.LT, path("now"))))).contains("conflicting")
        assertThat(reason(body(cmp(path("ts"), Operator.EQ, Operand.Literal("\"yesterday\"")), cmp(time(path("ts")), Operator.LT, path("now"))))).contains("conflicting")
        assertThat(reason(body(cmp(time(path("ts")), Operator.LT, path("now")), cmp(time(path("ts")), Operator.GT, path("now"))))).contains("conflicting")
    }

    @Test
    @DisplayName("a time compared with a number, or with another call, is not solvable")
    fun timeAgainstNonPaths() {
        assertThat(reason(body(cmp(time(path("ts")), Operator.LT, n(5))))).contains("plain path")
        assertThat(InputSynthesizer.synthesize(body(cmp(time(path("ts")), Operator.LT, time(path("other)")))))).isInstanceOf(Synthesis.NotSolvable::class.java)
    }

    @Test
    @DisplayName("deterministic: the same body gives the same input")
    fun deterministic() {
        val b = body(cmp(count(path("x")), Operator.GTE, n(3)), cmp(time(path("ts")), Operator.LT, path("now")))
        assertThat(solved(b).toString()).isEqualTo(solved(b).toString())
    }
}
