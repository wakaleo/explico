/**
 * Acceptance tests for conditional complete rules showing the value they yield (follow-up to
 * review item 10). Before this, `tier := "high" if input.risk > 80` rendered only its condition,
 * so a card read like a boolean rule; `limit := n if { n := input.limit }` rendered an EMPTY
 * conditions list. A plain boolean rule (`flag if input.enabled`, implicit `true`) must gain no
 * value line.
 *
 * NOT transcribed from the frozen acceptance-pack README: authored at the operator's request.
 * Only a case-insensitive `value` label is pinned, not its exact wording.
 * Fixture: `src/test/resources/realistic/values/values.rego`.
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

@DisplayName("Realistic policies: conditional complete rules show their value")
class RealisticConditionalValuesAcceptanceIT {

    companion object {
        private lateinit var doc: String

        @JvmStatic
        @BeforeAll
        fun renderDocument() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            val dir = Path.of("src/test/resources/realistic/values")
            doc = Explico.render(Explico.load(dir), dir).files.getValue("values.md")
        }

        /** The section whose heading line is exactly [heading], up to the next `## ` heading. */
        private fun section(markdown: String, heading: String): String {
            val lines = markdown.lines()
            val start = lines.indexOf(heading)
            assertThat(start).describedAs("a heading line exactly %s", heading).isGreaterThanOrEqualTo(0)
            val end = lines.drop(start + 1).indexOfFirst { it.startsWith("## ") }
            return lines.drop(start).let { if (end < 0) it else it.take(end + 1) }.joinToString("\n")
        }

        private fun unfenced(markdown: String): List<String> {
            var inFence = false
            return markdown.lines().filter { line ->
                if (line.trim().startsWith("```")) { inFence = !inFence; false } else !inFence
            }
        }

        private fun bullets(markdown: String): List<String> = unfenced(markdown).filter { it.startsWith("- ") }

        private fun valueLines(text: String): List<String> =
            unfenced(text).filter { it.contains("value", ignoreCase = true) && !it.startsWith("*Rule `") && !it.startsWith("## ") && !it.startsWith("<a ") }

        private fun footer(card: String): String =
            card.lines().single { it.startsWith("*Rendering coverage:") }

        private fun card(name: String) = section(doc, "## values.$name")
    }

    @Nested
    @DisplayName("A — a multi-body rule shows each Situation's own value")
    inner class MultiBody {

        private val situations get() = card("tier").split("### Situation").drop(1)

        @Test
        @DisplayName("Situation 1 yields \"high\", Situation 2 yields \"low\", each inside its own section")
        fun eachSituationHasItsOwnValue() {
            assertThat(situations).hasSize(2)
            assertThat(valueLines(situations[0]).joinToString("\n")).contains("\"high\"").doesNotContain("\"low\"")
            assertThat(valueLines(situations[1]).joinToString("\n")).contains("\"low\"").doesNotContain("\"high\"")
        }

        @Test
        @DisplayName("the condition bullets are unchanged")
        fun conditionsUnchanged() {
            assertThat(bullets(situations[0]).single()).contains("`risk`").contains("greater than").contains("80")
            assertThat(bullets(situations[1]).single()).contains("`risk`").contains("80")
        }

        @Test
        @DisplayName("footer: 2 of 2 conditions, no unrendered value")
        fun footerCounts() {
            assertThat(footer(card("tier"))).contains("2 of 2 conditions").doesNotContain("unrendered value")
        }
    }

    @Nested
    @DisplayName("B/C/E — single-body rules show their value")
    inner class SingleBody {

        @Test
        @DisplayName("single := \"only\" shows \"only\" and keeps its condition")
        fun singleLiteral() {
            val c = card("single")
            assertThat(valueLines(c).joinToString("\n")).contains("\"only\"")
            assertThat(bullets(c)).hasSize(1)
            assertThat(footer(c)).contains("1 of 1 conditions").doesNotContain("unrendered value")
        }

        @Test
        @DisplayName("explicit := true shows true (explicit, unlike an implicit boolean)")
        fun explicitTrue() {
            val c = card("explicit")
            assertThat(valueLines(c).joinToString("\n")).contains("true")
            assertThat(footer(c)).contains("1 of 1 conditions").doesNotContain("unrendered value")
        }

        @Test
        @DisplayName("label := sprintf(...) has a non-empty value line (verbatim or humanized)")
        fun sprintfValue() {
            val c = card("label")
            val line = valueLines(c).singleOrNull { it.contains("value", ignoreCase = true) }
            assertThat(line).describedAs("one Value line").isNotNull()
            val afterLabel = Regex("value", RegexOption.IGNORE_CASE).split(line!!, 2)[1]
            assertThat(afterLabel.trim(' ', ':', '*', '_')).describedAs("text after the Value label").isNotBlank()
            assertThat(footer(c)).contains("1 of 1 conditions").doesNotContain("unrendered value")
        }
    }

    @Nested
    @DisplayName("D — guard: a plain boolean rule gains no value line")
    inner class PlainBoolean {

        @Test
        @DisplayName("flag if input.enabled has no Value line and keeps its single condition")
        fun noValueLine() {
            val c = card("flag")
            assertThat(valueLines(c)).isEmpty()
            assertThat(bullets(c)).hasSize(1)
            assertThat(footer(c)).contains("1 of 1 conditions")
        }
    }

    @Nested
    @DisplayName("F — a value-only body (`n := input.limit`) neither shows an empty list nor hides its value")
    inner class AssignedValue {

        @Test
        @DisplayName("limit has a Value line")
        fun hasValueLine() {
            assertThat(valueLines(card("limit"))).isNotEmpty()
        }

        @Test
        @DisplayName("no empty 'All of the following are true' list")
        fun noEmptyConditionList() {
            val lines = card("limit").lines()
            val header = lines.indexOfFirst { it.contains("All of the following are true") }
            if (header >= 0) {
                val footerAt = lines.indexOfFirst { it.startsWith("*Rendering coverage:") }
                assertThat(lines.subList(header + 1, footerAt).filter { it.isNotBlank() })
                    .describedAs("content between the header and the footer").isNotEmpty()
            }
        }
    }
}
