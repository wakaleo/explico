/**
 * Acceptance tests for the review's polish items 13-17 on the realistic policies
 * (`src/test/resources/realistic/{policies,builtins,messages}/`):
 * 13 else-chain header, 14 helper-assignment coverage breakdown, 15 `count(x)` emptiness idioms,
 * 16 worked examples for object-valued messages, 17 index helper section.
 *
 * NOT transcribed from the frozen acceptance-pack README: authored from the review at the
 * operator's request. Wording is deliberately loose except where an assertion states it. The
 * comprehension-argument-context idea is DEFERRED (it would conflict with REL-004's frozen Tier-1
 * "⚠ not rendered" block and <100% coverage assertions) and is deliberately not tested.
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

@DisplayName("Realistic policies: presentation polish (review items 13-17)")
class RealisticPresentationAcceptanceIT {

    companion object {
        private lateinit var files: Map<String, String>
        private lateinit var builtinFiles: Map<String, String>
        private var messageFiles: Map<String, String> = emptyMap()
        private var messagesError: Throwable? = null

        private fun render(dir: String): Map<String, String> {
            val policyDir = Path.of(dir)
            return Explico.render(Explico.load(policyDir), policyDir).files
        }

        @JvmStatic
        @BeforeAll
        fun renderDocuments() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            files = render("src/test/resources/realistic/policies")
            builtinFiles = render("src/test/resources/realistic/builtins")
            // Item 16: rendering object-valued sets with worked examples crashes today -- capture it so
            // the failure is an assertion, not an exception.
            try {
                val policyDir = Path.of("src/test/resources/realistic/messages/policies")
                val examples = Explico.loadExamples(Path.of("src/test/resources/realistic/messages/examples"))
                messageFiles = Explico.render(Explico.load(policyDir), policyDir, examples).files
            } catch (e: Exception) {
                messagesError = e
            }
        }

        /** The `## ` section whose heading line contains [marker], up to the next `## ` heading. */
        private fun section(markdown: String, marker: String): String {
            val lines = markdown.lines()
            val start = lines.indexOfFirst { it.startsWith("## ") && it.contains(marker) }
            assertThat(start).describedAs("a '## ' heading containing %s", marker).isGreaterThanOrEqualTo(0)
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
        private fun renderedBullets(markdown: String): List<String> = bullets(markdown).filterNot { it.startsWith("- ⚠") }
        private fun warnBullets(markdown: String): List<String> = bullets(markdown).filter { it.startsWith("- ⚠") }

        private fun fencedBlocks(markdown: String): List<String> {
            val blocks = mutableListOf<String>()
            var current: MutableList<String>? = null
            for (line in markdown.lines()) {
                if (line.trim().startsWith("```")) {
                    if (current == null) current = mutableListOf() else { blocks += current.joinToString("\n"); current = null }
                } else current?.add(line)
            }
            return blocks
        }

        private fun bulletWith(card: String, vararg needles: String): String {
            val matching = renderedBullets(card).filter { b -> needles.all { b.contains(it) } }
            assertThat(matching).describedAs("exactly one rendered bullet containing %s", needles.toList()).hasSize(1)
            assertThat(matching.single()).doesNotContain("input.").doesNotContain("(")
            return matching.single()
        }

        private fun coverageFooter(card: String): String {
            val lines = card.lines().filter { it.startsWith("*Rendering coverage:") }
            assertThat(lines).describedAs("one card coverage footer line").hasSize(1)
            return lines.single()
        }
    }

    @Nested
    @DisplayName("Item 13 — else-chain header")
    inner class ElseChain {

        private val card get() = section(files["authz.md"] ?: "", "authz.risk_level")

        @Test
        @DisplayName("the ⚠ bullet's header says it is an else chain whose branches are tried in order")
        fun headerExplainsTheConstruct() {
            val warn = warnBullets(card)
            assertThat(warn).hasSize(1)
            assertThat(warn.single()).containsIgnoringCase("else")
            assertThat(warn.single().lowercase()).containsAnyOf("first", "priority", "in order")
        }

        @Test
        @DisplayName("the fenced source still holds the whole chain verbatim, and coverage stays 0 of 1")
        fun sourceAndCoverageUnchanged() {
            val fence = fencedBlocks(card).joinToString("\n")
            assertThat(fence).contains("risk_level := \"high\" if {").contains("} else := \"medium\" if {").contains("} else := \"low\"")
            assertThat(card).contains("0 of 1 conditions")
        }

        @Test
        @DisplayName("guard: the `every` block keeps the generic header and never mentions else")
        fun everyKeepsTheGenericHeader() {
            val warn = warnBullets(section(files["authz.md"] ?: "", "authz.allow"))
            assertThat(warn).hasSize(1)
            assertThat(warn.single()).doesNotContainIgnoringCase("else")
        }
    }

    @Nested
    @DisplayName("Item 14 — coverage breakdown for helper assignments")
    inner class CoverageBreakdown {

        @Test
        @DisplayName("K8S-003 keeps 1 of 2 conditions and its footer counts the 1 remaining helper assignment")
        fun k8s003() {
            val footer = coverageFooter(section(files["k8s-admission.md"] ?: "", "K8S-003"))
            assertThat(footer).contains("1 of 2 conditions").containsIgnoringCase("1 helper assignment")
        }

        @Test
        @DisplayName("helpers.deny keeps 3 of 5 conditions and counts only the 2 assignments (json.is_valid is now rendered, spec §14 promotion)")
        fun helpersDeny() {
            val footer = coverageFooter(section(builtinFiles["helpers.md"] ?: "", "helpers.deny"))
            assertThat(footer).contains("3 of 5 conditions").containsIgnoringCase("2 helper assignment")
        }

        @Test
        @DisplayName("guards: cards without helper assignments and the package footer never mention helper")
        fun noHelperMentionElsewhere() {
            assertThat(coverageFooter(section(builtinFiles["builtins.md"] ?: "", "builtins.deny")))
                .contains("16 of 17 conditions").doesNotContainIgnoringCase("helper")
            assertThat(coverageFooter(section(files["terraform-s3.md"] ?: "", "TF-001"))).doesNotContainIgnoringCase("helper")
            val packageFooters = (files["k8s-admission.md"] ?: "").lines().filter { it.startsWith("*Package rendering coverage:") }
            assertThat(packageFooters).hasSize(1)
            assertThat(packageFooters.single()).doesNotContainIgnoringCase("helper")
        }
    }

    @Nested
    @DisplayName("Item 15 — count(x) emptiness idioms")
    inner class CountIdioms {

        private val card get() = section(builtinFiles["idioms.md"] ?: "", "idioms.deny")

        @Test
        @DisplayName("count(x) == 0 reads `x` is empty")
        fun equalsZero() {
            val bullet = bulletWith(card, "items")
            assertThat(bullet).containsIgnoringCase("empty").doesNotContain("not empty").doesNotContain("the number of")
        }

        @Test
        @DisplayName("count(x) > 0 and count(x) != 0 read `x` is not empty")
        fun greaterThanAndNotEqualsZero() {
            for (word in listOf("roles", "tags")) {
                val bullet = bulletWith(card, word)
                assertThat(bullet).contains("not empty").doesNotContain("the number of")
            }
        }

        @Test
        @DisplayName("comparisons that are not exactly ==/!=/> against 0 keep the number-of phrasing and never say empty")
        fun otherComparisonsAreNotWidened() {
            for ((word, literal) in listOf("labels" to "`1`", "rows" to "`1`", "cols" to "`5`")) {
                val bullet = bulletWith(card, word, literal)
                assertThat(bullet).contains("the number of").doesNotContainIgnoringCase("empty")
            }
        }

        @Test
        @DisplayName("count(input.pods[_].containers) == 0 says empty and keeps the `any of:` prefix")
        fun anyIndexPrefixSurvives() {
            val bullet = bulletWith(card, "pods")
            assertThat(bullet).startsWith("- any of:").contains("empty").doesNotContain("not empty")
        }

        @Test
        @DisplayName("all seven are rendered bullets with no raw `input.` or `count(`")
        fun sevenHumanizedBullets() {
            val rendered = renderedBullets(card)
            assertThat(rendered).hasSize(7)
            for (bullet in rendered) assertThat(bullet).doesNotContain("input.").doesNotContain("count(")
            assertThat(card).contains("7 of 7 conditions")
        }

        @Test
        @DisplayName("authz.md: count(input.user.roles) > 0 reads `user ▸ roles` is not empty")
        fun authz() {
            assertThat(bulletWith(files["authz.md"] ?: "", "user ▸ roles", "not empty")).doesNotContain("the number of")
        }

        @Test
        @DisplayName("K8S-003: the later bullet reads `... minus `provided`` is not empty, not a `the number of` phrasing")
        fun k8s003() {
            val card = section(files["k8s-admission.md"] ?: "", "K8S-003")
            assertThat(bulletWith(card, "minus", "not empty")).doesNotContain("the number of")
        }
    }

    @Nested
    @DisplayName("Item 16 — worked examples for object-valued messages")
    inner class ObjectValuedMessages {

        private val md get() = messageFiles["messages.md"] ?: ""

        @Test
        @DisplayName("rendering a set rule whose elements are objects (and a partial-object rule) does not throw")
        fun doesNotThrow() {
            assertThat(messagesError).describedAs("render crash").isNull()
        }

        @Test
        @DisplayName("messages.deny: fixture 1 is denied with `public acl` (Situation 1) and the unencrypted message (Situation 2)")
        fun denyMessagesAreAttributed() {
            val card = section(md, "messages.deny")
            assertThat(card).contains("- **public unencrypted bucket** — ❌ denied")
            val lines = card.lines()
            assertThat(lines.filter { it.contains("\"public acl\"") && it.contains("(Situation 1)") }).hasSize(1)
            assertThat(lines.filter { it.contains("\"bucket logs is unencrypted\"") && it.contains("(Situation 2)") }).hasSize(1)
        }

        @Test
        @DisplayName("messages.deny never shows a raw JSON object, and fixture 2 is allowed")
        fun noRawJsonAndSecondFixtureAllowed() {
            val card = section(md, "messages.deny")
            assertThat(card).doesNotContain("{\"msg\"")
            assertThat(card).contains("✅ allowed")
        }

        @Test
        @DisplayName("messages.violation: fixture 1 is denied with `not prod`; fixture 2 (empty partial object) is allowed")
        fun violation() {
            val card = section(md, "messages.violation")
            assertThat(card).contains("- **public unencrypted bucket** — ❌ denied")
            // the worked-example message line (indented *"..."*), not the card's own `Produces:` line
            assertThat(card.lines().filter { it.trimStart().startsWith("*\"not prod\"") }).hasSize(1)
            assertThat(card).contains("- **private encrypted production bucket** — ✅ allowed")
            assertThat(card).doesNotContain("{\"msg\"")
        }
    }

    @Nested
    @DisplayName("Item 17 — index: controls first, then a separate table for rules without a control id")
    inner class IndexHelperSection {

        private val lines get() = (files["index.md"] ?: "").lines()
        private val headingAt get() = lines.indexOfFirst { it.startsWith("## ") }
        private fun dataRows(range: List<String>) = range.filter { it.startsWith("|") && it.contains("`") }

        @Test
        @DisplayName("exactly one `## ` heading, mentioning without/helper, and exactly two table separators")
        fun structure() {
            assertThat(lines.filter { it.startsWith("## ") }).hasSize(1)
            assertThat(lines[headingAt].lowercase()).containsAnyOf("without", "helper")
            assertThat(lines.count { it.startsWith("|---") }).isEqualTo(2)
        }

        @Test
        @DisplayName("before the heading: only the six controls, none with `—` as its control id")
        fun controlsTable() {
            val rows = dataRows(lines.take(headingAt.coerceAtLeast(0)))
            val firstCells = rows.map { it.split("|")[1].trim() }
            assertThat(firstCells).containsExactlyInAnyOrder("AZ-001", "AZ-002", "K8S-001", "K8S-002", "K8S-003", "TF-001")
        }

        @Test
        @DisplayName("after the heading: the eight id-less entries (the split `allow` default is its own entry)")
        fun helperTable() {
            val rows = dataRows(lines.drop(headingAt + 1).filterNot { it.startsWith("*") })
            assertThat(rows).hasSize(8)
            for (rule in listOf("risk_level", "is_exempt", "tagged", "violation")) {
                assertThat(rows.filter { it.contains("`$rule`") }).describedAs("row for %s", rule).hasSize(1)
            }
            assertThat(rows.count { it.contains("`allow`") }).isEqualTo(2)
            assertThat(rows.count { it.contains("`deny`") }).isEqualTo(2)
        }

        @Test
        @DisplayName("exactly one overall coverage line, after the second table")
        fun overallCoverageLineIsLast() {
            val at = lines.indices.filter { lines[it].startsWith("*Overall rendering coverage:") }
            assertThat(at).hasSize(1)
            val lastRow = lines.indexOfLast { it.startsWith("|") }
            assertThat(at.single()).isGreaterThan(lastRow)
            assertThat(lastRow).isGreaterThan(headingAt)
        }
    }
}
