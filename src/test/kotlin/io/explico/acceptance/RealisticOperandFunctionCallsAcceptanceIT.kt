/**
 * Acceptance tests for user-defined function calls in OPERAND position (coverage-audit rank 4),
 * e.g. `severity_of(input.finding.level) >= 2`. Review item 8 handled a same-package call only as a
 * whole condition; inside a comparison it rendered as an untraced raw call and counted as an
 * unrendered operand. Decided: a same-package call whose arguments all map cleanly renders as a
 * link to the function's own card plus its humanized arguments (the word `function`, the exact link
 * and the argument breadcrumb are pinned; the rest of the wording is loose). A call with an
 * unmappable argument, a cross-package call, or a comprehension argument keeps today's behaviour.
 *
 * NOT transcribed from the frozen acceptance-pack README: authored at the operator's request.
 * Out of scope: a Parameter-rooted path such as `finding.severity` inside a function body.
 * Fixture: `src/test/resources/realistic/function-values/funcs.rego`.
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

@DisplayName("Realistic policies: user-defined function calls in operand position")
class RealisticOperandFunctionCallsAcceptanceIT {

    companion object {
        private lateinit var doc: String

        private const val SEVERITY_LINK = "[`severity_of`](#funcvalues-severity-of)"
        private const val LIMIT_LINK = "[`limit_for`](#funcvalues-limit-for)"

        @JvmStatic
        @BeforeAll
        fun renderDocument() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            val dir = Path.of("src/test/resources/realistic/function-values")
            doc = Explico.render(Explico.load(dir), dir).files.getValue("funcvalues.md")
        }

        private fun card(name: String): String {
            val lines = doc.lines()
            val start = lines.indexOf("## funcvalues.$name")
            assertThat(start).describedAs("heading ## funcvalues.%s", name).isGreaterThanOrEqualTo(0)
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
    }

    @Nested
    @DisplayName("A — a call on the left of a comparison")
    inner class LeftOfComparison {
        @Test
        fun rendersAsALinkedFunctionResultWithItsArgument() {
            val c = card("deny")
            val bullet = bullets(c).single()
            assertThat(bullet).contains(SEVERITY_LINK).containsIgnoringCase("function").contains("`finding ▸ level`").contains("at least").contains("`2`")
            assertThat(footer(c)).contains("1 of 1 conditions").doesNotContain("unrendered value")
        }
    }

    @Nested
    @DisplayName("B — a call on the right of a comparison")
    inner class RightOfComparison {
        @Test
        fun rendersAsALinkedFunctionResultWithItsArgument() {
            val c = card("cap")
            val bullet = bullets(c).single()
            assertThat(bullet).contains(LIMIT_LINK).containsIgnoringCase("function").contains("`count`").contains("`env`").contains("greater than")
            assertThat(footer(c)).contains("1 of 1 conditions").doesNotContain("unrendered value")
        }
    }

    @Nested
    @DisplayName("C — a call as another call's argument")
    inner class NestedCalls {
        @Test
        fun bothCallsAreLinked() {
            val c = card("nested")
            val bullet = bullets(c).single()
            assertThat(bullet).contains(SEVERITY_LINK).contains(LIMIT_LINK).contains("`items`")
            assertThat(footer(c)).doesNotContain("unrendered value")
        }
    }

    @Nested
    @DisplayName("D — a call assigned to a local variable is substituted at its use")
    inner class ViaVariable {
        @Test
        fun noHelperFallback() {
            val c = card("via_var")
            assertThat(c).doesNotContain("⚠").doesNotContain("helper definition")
            assertThat(bullets(c).single()).contains(SEVERITY_LINK).contains("greater than").contains("`1`")
        }
    }

    @Nested
    @DisplayName("E — guard: a comprehension argument still makes the whole condition a fallback")
    inner class ComprehensionArgument {
        @Test
        fun wholeConditionStaysFenced() {
            val c = card("bad_arg")
            assertThat(c.lines().count { it.contains("⚠") }).isEqualTo(1)
            assertThat(fenced(c)).contains("limit_for({e | some e in input.envs; net.cidr_is_valid(e)})")
            assertThat(bullets(c).filter { !it.startsWith("- ⚠") }.joinToString("\n")).doesNotContain(LIMIT_LINK)
        }
    }

    @Nested
    @DisplayName("F — a CROSS-PACKAGE call resolves through its import and links into the other package's document")
    inner class CrossPackageCall {
        @Test
        fun rendersAsALinkedResult() {
            val c = card("unknown")
            assertThat(bullets(c).single())
                .contains("result of function [`helper`](funcvalues-other.md#funcvalues-other-helper)")
                .doesNotContain("other.helper(input.x)")
            assertThat(footer(c)).doesNotContain("unrendered value")
        }
    }

    @Nested
    @DisplayName("G/H — guards: the links resolve, and the function's own card is unchanged")
    inner class FunctionCards {
        @Test
        fun eachFunctionAnchorAppearsExactlyOnce() {
            assertThat(Regex("""<a id="funcvalues-severity-of"></a>""").findAll(doc).count()).isEqualTo(1)
            assertThat(Regex("""<a id="funcvalues-limit-for"></a>""").findAll(doc).count()).isEqualTo(1)
        }

        @Test
        fun severityCardStillShowsThreeSituations() {
            assertThat(Regex("### Situation").findAll(card("severity_of")).count()).isEqualTo(3)
        }
    }
}
