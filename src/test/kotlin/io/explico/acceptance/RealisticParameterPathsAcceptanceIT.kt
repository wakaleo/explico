/**
 * Acceptance tests for PARAMETER-ROOTED paths inside user-defined function bodies (the remaining
 * half of coverage-audit probe 28, disclosed in spec §14.25), e.g. `finding.severity` inside
 * `risk_score(finding) := 10 if {...}`. Today such a chain renders as a raw backticked operand and
 * counts as an unrendered value. Decided: a chain rooted at a function parameter whose remaining
 * segments map cleanly renders as "parameter `finding` ▸ severity" (the parameter phrase and the
 * humanized tail are pinned; the exact joining wording is loose) and counts as rendered. A tail the
 * mapper cannot resolve (an unbound dynamic index) keeps the raw fallback.
 *
 * NOT transcribed from the frozen acceptance-pack README: authored at the operator's request.
 * Fixture: `src/test/resources/realistic/parameter-paths/funcs.rego`.
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

@DisplayName("Realistic policies: parameter-rooted paths in function bodies")
class RealisticParameterPathsAcceptanceIT {

    companion object {
        private lateinit var doc: String

        @JvmStatic
        @BeforeAll
        fun renderDocument() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            val dir = Path.of("src/test/resources/realistic/parameter-paths")
            doc = Explico.render(Explico.load(dir), dir).files.getValue("parampaths.md")
        }

        private fun card(name: String): String {
            val lines = doc.lines()
            val start = lines.indexOf("## parampaths.$name")
            assertThat(start).describedAs("heading ## parampaths.%s", name).isGreaterThanOrEqualTo(0)
            val end = lines.drop(start + 1).indexOfFirst { it.startsWith("## ") }
            return lines.drop(start).let { if (end < 0) it else it.take(end + 1) }.joinToString("\n")
        }

        private fun bullets(markdown: String): List<String> {
            var inFence = false
            return markdown.lines().filter { line ->
                if (line.trim().startsWith("```")) { inFence = !inFence; false } else !inFence
            }.filter { it.startsWith("- ") }
        }

        private fun footer(card: String): String = card.lines().single { it.startsWith("*Rendering coverage:") }
    }

    @Nested
    @DisplayName("A — a plain field tail on a parameter")
    inner class PlainFieldTail {
        @Test
        fun rendersAsAParameterBreadcrumbAndCountsAsRendered() {
            val c = card("risk_score")
            val joined = bullets(c).joinToString("\n")
            assertThat(joined).contains("parameter `finding`").contains("severity").doesNotContain("`finding.severity`")
            assertThat(footer(c)).doesNotContain("unrendered value")
        }
    }

    @Nested
    @DisplayName("B — a nested field tail on a parameter")
    inner class NestedFieldTail {
        @Test
        fun rendersBothTailSegments() {
            val bullet = bullets(card("risk_score")).single { it.contains("platform") }
            assertThat(bullet).contains("parameter `finding`").contains("owner").contains("team").doesNotContain("`finding.owner.team`")
        }
    }

    @Nested
    @DisplayName("C — a key-literal tail on a parameter")
    inner class KeyLiteralTail {
        @Test
        fun rendersTheLiteralKeyedTail() {
            val c = card("has_label")
            val bullet = bullets(c).single()
            assertThat(bullet).contains("parameter `res`").contains("labels").contains("env").doesNotContain("`res.labels[\"env\"]`")
            assertThat(footer(c)).doesNotContain("unrendered value")
        }
    }

    @Nested
    @DisplayName("D — the caller of the function is unchanged (guard)")
    inner class CallerUnchanged {
        @Test
        fun denyStillLinksToTheFunctionWithNoUnrenderedValue() {
            val c = card("deny")
            assertThat(bullets(c).single()).contains("[`risk_score`](#parampaths-risk-score)")
            assertThat(footer(c)).doesNotContain("unrendered value")
        }
    }

    @Nested
    @DisplayName("E — an unresolvable parameter tail keeps the raw fallback (guard)")
    inner class UnresolvableTailFallsBack {
        @Test
        fun badKeepsItsVerbatimSourceAndStillCountsAsUnrendered() {
            val c = card("bad")
            // Repointed (spec §14.51): `idx` used ONCE is now the wildcard `_`, so the rule uses it twice (a shared index under a parameter has no
            // plain path to bind against and stays raw).
            assertThat(bullets(c).single()).contains("`res.items[idx].ok`").contains("`res.flags[idx]`")
            assertThat(footer(c)).contains("2 unrendered values")
        }
    }

    @Nested
    @DisplayName("F — a bare parameter use is unchanged (guard)")
    inner class BareParameterUnchanged {
        @Test
        fun stillRendersAsParameterX() {
            val c = card("in_set")
            assertThat(bullets(c).single()).contains("parameter `x`")
            assertThat(footer(c)).doesNotContain("unrendered value")
        }
    }
}
