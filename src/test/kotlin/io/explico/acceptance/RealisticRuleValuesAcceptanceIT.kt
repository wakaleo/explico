/**
 * Acceptance tests for references to another rule's VALUE (review item 10), plus the link-target
 * fix it forces: constant-value rules.
 *
 * Three reference shapes to a complete rule's value must render as a link to that rule's card, not
 * as an untraced name, raw dotted text, or -- worst -- a DATA breadcrumb: a bare same-package name
 * (`threshold`), an import-alias reference (`config.max_items`), and a full data path to a rule
 * (`data.rulevalues.config.region_prefix`). A genuine data path with no rule behind it stays a data
 * breadcrumb. A constant rule (`max_items := 10`, whose opa AST is a synthetic `true` body) must show
 * its value instead of a "⚠ not rendered" card, since that card is where the links point.
 *
 * Scope decision, pinned by a guard: references to PARTIAL (set/object) rules (`count(flagged)`) are
 * deliberately NOT linked in this item. Also out of scope, no tests: a conditional complete rule such
 * as `x := "high" if {...}` still never shows the value it yields (a follow-up).
 *
 * NOT transcribed from the frozen acceptance-pack README: authored from the review at the operator's
 * request. Fixtures: `src/test/resources/realistic/rulevalues/` and the existing `realistic/policies`.
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

@DisplayName("Realistic policies: references to another rule's value (review item 10)")
class RealisticRuleValuesAcceptanceIT {

    companion object {
        private lateinit var values: Map<String, String>
        private lateinit var corpus: Map<String, String>

        private fun render(dir: String): Map<String, String> {
            val policyDir = Path.of(dir)
            return Explico.render(Explico.load(policyDir), policyDir).files
        }

        @JvmStatic
        @BeforeAll
        fun renderDocuments() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            values = render("src/test/resources/realistic/rulevalues")
            corpus = render("src/test/resources/realistic/policies")
        }

        /** The section whose heading line is exactly [heading], up to the next `## ` heading (exact, so `## authz.allow` never matches a longer heading sharing its prefix). */
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

        private fun bulletWith(card: String, vararg needles: String): String {
            val matching = bullets(card).filter { b -> needles.all { b.contains(it) } }
            assertThat(matching).describedAs("exactly one bullet containing %s", needles.toList()).hasSize(1)
            return matching.single()
        }

        private fun footer(card: String): String {
            val lines = card.lines().filter { it.startsWith("*Rendering coverage:") }
            assertThat(lines).describedAs("one card coverage footer line").hasSize(1)
            return lines.single()
        }
    }

    @Nested
    @DisplayName("A — references to a complete rule's value become links")
    inner class ValueReferences {

        private val card get() = section(values["rulevalues-checks.md"] ?: "", "## rulevalues.checks.deny")

        @Test
        @DisplayName("bare same-package `threshold` links to the rule's card")
        fun barePackageLocalReference() {
            val bullet = bulletWith(card, "[`threshold`](#rulevalues-checks-threshold)")
            assertThat(bullet).contains("the number of `items`").containsIgnoringCase("rule")
        }

        @Test
        @DisplayName("import-alias `config.max_items` links across packages and never shows raw dotted text")
        fun aliasReference() {
            val bullet = bulletWith(card, "[`max_items`](rulevalues-config.md#rulevalues-config-max-items)")
            assertThat(bullet).doesNotContain("config.max_items")
        }

        @Test
        @DisplayName("full data path `data.rulevalues.config.region_prefix` is a link, not a data breadcrumb")
        fun dataPathToARule() {
            val bullet = bulletWith(card, "[`region_prefix`](rulevalues-config.md#rulevalues-config-region-prefix)")
            assertThat(bullet).doesNotContain("data ▸ rulevalues")
        }

        @Test
        @DisplayName("guard: a data path with no rule behind it stays a data breadcrumb with no link")
        fun genuineDataPathStaysABreadcrumb() {
            val bullet = bulletWith(card, "data ▸ external ▸ limit")
            assertThat(bullet).doesNotContain("](")
        }

        @Test
        @DisplayName("all four conditions are rendered and nothing is left as an unrendered value")
        fun coverageFooter() {
            assertThat(footer(card)).contains("4 of 4 conditions").doesNotContain("unrendered value")
        }
    }

    @Nested
    @DisplayName("B — guard: references to PARTIAL rules are deliberately not linked in this item")
    inner class PartialRuleReference {

        private val card get() = section(values["rulevalues-checks.md"] ?: "", "## rulevalues.checks.warn")

        @Test
        @DisplayName("`count(flagged)` is still shown inline, unlinked, and still counts as an unrendered value")
        fun partialReferenceUnchanged() {
            val bullet = bulletWith(card, "flagged")
            assertThat(bullet).contains("the number of").doesNotContain("](")
            assertThat(footer(card)).contains("1 unrendered value")
        }
    }

    @Nested
    @DisplayName("C — constant-value rules show their value, not a ⚠ card")
    inner class ConstantRules {

        private fun assertValueCard(card: String, literal: String) {
            assertThat(bullets(card).filter { it.startsWith("- ⚠") }).describedAs("⚠ bullets").isEmpty()
            assertThat(card).doesNotContain("All of the following are true")
            val valueLines = card.lines().filter { it.contains("Value", ignoreCase = true) && it.contains(literal) }
            assertThat(valueLines).describedAs("a value line mentioning %s", literal).isNotEmpty()
            assertThat(footer(card)).contains("0 of 0 conditions")
        }

        @Test
        @DisplayName("max_items := 10")
        fun maxItems() = assertValueCard(section(values["rulevalues-config.md"] ?: "", "## rulevalues.config.max_items"), "10")

        @Test
        @DisplayName("region_prefix := \"eu-\"")
        fun regionPrefix() = assertValueCard(section(values["rulevalues-config.md"] ?: "", "## rulevalues.config.region_prefix"), "\"eu-\"")

        @Test
        @DisplayName("threshold := 5 (in the checks package)")
        fun threshold() = assertValueCard(section(values["rulevalues-checks.md"] ?: "", "## rulevalues.checks.threshold"), "5")

        @Test
        @DisplayName("the index row for max_items shows 100% coverage")
        fun indexRow() {
            val rows = (values["index.md"] ?: "").lines().filter { it.startsWith("|") && it.contains("`max_items`") }
            assertThat(rows).hasSize(1)
            assertThat(rows.single()).contains("100%")
        }
    }

    @Nested
    @DisplayName("D — the realistic authz policy's `risk_level == \"low\"`")
    inner class RealisticAuthz {

        @Test
        @DisplayName("authz.allow links to the risk_level card and no longer reports an unrendered value")
        fun riskLevelReference() {
            val card = section(corpus["authz.md"] ?: "", "## authz.allow")
            assertThat(bulletWith(card, "[`risk_level`](#authz-risk-level)", "\"low\"")).isNotEmpty()
            assertThat(footer(card)).doesNotContain("unrendered value")
        }

        @Test
        @DisplayName("guard: the authz.risk_level else-chain card is unchanged -- still one ⚠ bullet")
        fun elseChainCardUnchanged() {
            val card = section(corpus["authz.md"] ?: "", "## authz.risk_level")
            assertThat(bullets(card).filter { it.startsWith("- ⚠") }).hasSize(1)
        }
    }
}
