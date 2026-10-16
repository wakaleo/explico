/**
 * Acceptance tests for collection-literal DELIMITERS in non-membership operand positions.
 * A small scalar array/set literal renders as a bare comma list (`"a", "b"`), which is right
 * inside a membership phrase ("is one of `"prod", "staging"`", frozen by Tier-1/goldens) but
 * ambiguous anywhere else: an array and a set literal rendered identically, and
 * `"owner", "team"` minus `provided` reads as two operands. Decided: outside the collection side
 * of a membership, a collection literal keeps its Rego delimiters inside the same backtick span --
 * `{"a", "b"}` for a set, `["a", "b"]` for an array.
 *
 * NOT transcribed from the frozen acceptance-pack README: authored at the operator's request.
 * Fixture: `src/test/resources/realistic/literal-delimiters/lits.rego`.
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

@DisplayName("Realistic policies: collection-literal delimiters")
class RealisticLiteralDelimitersAcceptanceIT {

    companion object {
        private lateinit var doc: String

        @JvmStatic
        @BeforeAll
        fun renderDocument() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            val dir = Path.of("src/test/resources/realistic/literal-delimiters")
            doc = Explico.render(Explico.load(dir), dir).files.getValue("litdelims.md")
        }

        private fun card(name: String): String {
            val lines = doc.lines()
            val start = lines.indexOf("## litdelims.$name")
            assertThat(start).describedAs("heading ## litdelims.%s", name).isGreaterThanOrEqualTo(0)
            val end = lines.drop(start + 1).indexOfFirst { it.startsWith("## ") }
            return lines.drop(start).let { if (end < 0) it else it.take(end + 1) }.joinToString("\n")
        }

        private fun bullet(card: String): String = card.lines().filter { it.startsWith("- ") }.single()

        private fun footer(card: String): String = card.lines().single { it.startsWith("*Rendering coverage:") }
    }

    @Nested
    @DisplayName("A — a set literal as an arithmetic operand")
    inner class SetInArithmetic {
        @Test
        fun keepsItsBracesInsideOneBacktickSpan() {
            val c = card("minus_set")
            assertThat(bullet(c)).contains("`{\"owner\", \"team\"}` minus `provided`")
            assertThat(footer(c)).doesNotContain("unrendered value")
        }
    }

    @Nested
    @DisplayName("B — an array literal as a comparison operand")
    inner class ArrayInComparison {
        @Test
        fun keepsItsBrackets() {
            val c = card("cmp_array")
            assertThat(bullet(c)).contains("`[\"a\", \"b\"]`")
            assertThat(footer(c)).doesNotContain("unrendered value")
        }
    }

    @Nested
    @DisplayName("C — a set literal as a comparison operand differs from the array")
    inner class SetInComparison {
        @Test
        fun keepsItsBraces() {
            val c = card("cmp_set")
            assertThat(bullet(c)).contains("`{\"a\", \"b\"}`").doesNotContain("[\"a\"")
            assertThat(footer(c)).doesNotContain("unrendered value")
        }
    }

    @Nested
    @DisplayName("D — an array literal as a builtin argument")
    inner class ArrayInBuiltinArgument {
        @Test
        fun keepsItsBrackets() {
            val c = card("ident_count")
            assertThat(bullet(c)).contains("the number of `[\"a\", \"b\"]`")
            assertThat(footer(c)).doesNotContain("unrendered value")
        }
    }

    @Nested
    @DisplayName("E — the collection side of a membership stays a bare list (guard)")
    inner class MembershipUnchanged {
        @Test
        fun isOneOfWithTheBareList() {
            val b = bullet(card("member"))
            assertThat(b).contains("is one of").contains("`\"prod\", \"staging\"`").doesNotContain("{\"prod\"").doesNotContain("[\"prod\"")
        }
    }

    @Nested
    @DisplayName("F — concat's per-element rendering is unaffected (guard)")
    inner class ConcatUnchanged {
        @Test
        fun stillRendersElementsIndividually() {
            val b = bullet(card("joined"))
            assertThat(b).contains("joined with").contains("`\"a\"`, `b`").doesNotContain("[\"a\"").doesNotContain("{\"a\"")
        }
    }
}
