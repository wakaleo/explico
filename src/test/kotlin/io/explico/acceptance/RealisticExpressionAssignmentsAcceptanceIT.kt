/**
 * Acceptance tests for local-variable assignments whose right-hand side is an operand EXPRESSION,
 * not just a plain path (`diff := input.a - input.b`, `total := count(input.items) + 1`,
 * `missing := required - provided`). Decided: when the right-hand side maps cleanly (no
 * Operand.Unrendered anywhere inside it) the variable is substituted inline wherever it is used and
 * the assignment disappears, exactly as a plain-path assignment already does. A right-hand side the
 * tool can't map (comprehension, unknown builtin) keeps the helper-assignment fallback and later
 * uses stay an untraced name. Subsumes the earlier-deferred set-difference operand (`a - b`).
 *
 * NOT transcribed from the frozen acceptance-pack README: authored at the operator's request.
 * Fixture: `src/test/resources/realistic/expressions/exprs.rego`.
 */
package io.explico.acceptance

import io.explico.Explico
import io.explico.opa.OpaRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path

@DisplayName("Realistic policies: expression assignments are substituted inline")
class RealisticExpressionAssignmentsAcceptanceIT {

    companion object {
        private lateinit var doc: String

        @JvmStatic
        @BeforeAll
        fun renderDocument() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            val dir = Path.of("src/test/resources/realistic/expressions")
            doc = Explico.render(Explico.load(dir), dir).files.getValue("exprs.md")
        }

        private fun card(name: String): String {
            val lines = doc.lines()
            val start = lines.indexOf("## exprs.$name")
            assertThat(start).describedAs("heading ## exprs.%s", name).isGreaterThanOrEqualTo(0)
            val end = lines.drop(start + 1).indexOfFirst { it.startsWith("## ") }
            return lines.drop(start).let { if (end < 0) it else it.take(end + 1) }.joinToString("\n")
        }

        private fun unfenced(markdown: String): List<String> {
            var inFence = false
            return markdown.lines().filter { line ->
                if (line.trim().startsWith("```")) { inFence = !inFence; false } else !inFence
            }
        }

        private fun fenced(markdown: String): String {
            var inFence = false
            return markdown.lines().filter { line ->
                if (line.trim().startsWith("```")) { inFence = !inFence; false } else inFence
            }.joinToString("\n")
        }

        private fun bullets(markdown: String): List<String> = unfenced(markdown).filter { it.startsWith("- ") }

        private fun footer(card: String): String = card.lines().single { it.startsWith("*Rendering coverage:") }

        private fun assertNoFallback(card: String) {
            assertThat(card).doesNotContain("⚠").doesNotContain("helper definition")
        }
    }

    @Nested
    @DisplayName("A — diff := input.a - input.b")
    inner class Subtraction {
        @Test
        fun substitutedInline() {
            val c = card("over")
            assertNoFallback(c)
            val bullet = bullets(c).single()
            assertThat(bullet).contains("`a`").contains("`b`").contains("minus").contains("greater than").contains("`5`")
            assertThat(footer(c)).contains("1 of 1 conditions").doesNotContain("helper assignment")
        }
    }

    @Nested
    @DisplayName("B — total := count(input.items) + 1")
    inner class Arithmetic {
        @Test
        fun substitutedInline() {
            val c = card("warn")
            assertNoFallback(c)
            assertThat(bullets(c).single()).contains("the number of `items`").contains("plus").contains("greater than").contains("`10`")
            assertThat(footer(c)).contains("1 of 1 conditions")
        }
    }

    @Nested
    @DisplayName("C — missing := required - provided, with `provided` itself a comprehension (now rendered, so both assignments substitute)")
    inner class SetDifference {
        @Test
        fun neitherAssignmentIsAFallbackAnyMore() {
            // Was: `provided := {x | ...}` stayed a visible helper-assignment fallback. Comprehensions are
            // rendered, so the assignment substitutes like any other fully rendered expression.
            val c = card("deny")
            assertThat(fenced(c)).isEmpty()
            assertNoFallback(c)
        }

        @Test
        fun theCheckShowsTheSubstitutedExpressionIncludingTheComprehension() {
            val c = card("deny")
            val bullet = bullets(c).single { it.contains("is not empty") }
            assertThat(bullet).contains("[`required`](#exprs-required)").contains("minus").contains("(the set of `provided ▸ [each x]` such that for some x in `provided`)")
        }

        @Test
        fun footerCountsOneConditionAndNoHelperAssignment() {
            assertThat(footer(card("deny"))).contains("1 of 1 conditions").doesNotContain("helper assignment")
        }
    }

    @Nested
    @DisplayName("D — a substituted variable used twice renders at both uses")
    inner class UsedTwice {
        @Test
        fun bothUsesSubstituted() {
            val c = card("twice")
            assertNoFallback(c)
            assertThat(bullets(c)).hasSize(2).allSatisfy { assertThat(it).contains("the number of") }
            assertThat(footer(c)).contains("2 of 2 conditions")
        }
    }

    @Nested
    @DisplayName("E — guard: an unmappable right-hand side stays a helper-assignment fallback")
    inner class UnmappableStaysFallback {
        @Test
        fun unknownBuiltinKeepsTheFallback() {
            val c = card("guard")
            assertThat(c.lines().count { it.contains("⚠") }).isEqualTo(1)
            assertThat(fenced(c)).contains("x := json.marshal")
            assertThat(bullets(c).single { !it.startsWith("- ⚠") }).contains("`x`")
        }
    }

    @Nested
    @DisplayName("F — chained: a := input.p; b := a + 1")
    inner class Chained {
        @Test
        fun secondAssignmentSubstitutesThroughTheFirst() {
            val c = card("chained")
            assertNoFallback(c)
            assertThat(bullets(c).single()).contains("`p`").contains("plus").contains("`1`").contains("greater than").contains("`2`")
            assertThat(footer(c)).contains("1 of 1 conditions")
        }
    }

    @Nested
    @DisplayName("G — substitution never introduces an unrendered value")
    inner class NoUnrenderedValues {
        @Test
        fun noFooterReportsAnUnrenderedValue() {
            listOf("over", "warn", "deny", "twice", "chained").forEach { assertThat(footer(card(it))).describedAs(it).doesNotContain("unrendered value") }
        }
    }
}
