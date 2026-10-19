/**
 * Acceptance tests for the "Effect of a match" line: one line per card saying what it means when the rule matches, by the
 * project's own documented name-based convention (deny / violation => denied, allow => allowed -- the same mapping worked
 * examples already use). It names its basis in the line itself, so it is true by construction; any other rule name, a
 * function, a split default or a body-less rule gets NO line (an effect is never guessed). Authored from a compliance-owner
 * review of the real output, not from the frozen README contract (the frozen Tier-1 assertions keep holding untouched).
 * Fixtures: the acceptance pack, `realistic/effect-of-match/`, and `diff/diff-all-categories` for diff-report cards.
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

@DisplayName("Realistic policies: effect of a match")
class RealisticEffectOfMatchAcceptanceIT {

    companion object {
        private const val DENIED = "**Effect of a match:** ❌ denied *(by the `deny` naming convention)*"

        private val pack = Path.of("src/test/resources/acceptance/policies")
        private val packExamples = Path.of("src/test/resources/acceptance/examples")
        private val packData = Path.of("src/test/resources/acceptance/data/release/data.json")
        private val effects = Path.of("src/test/resources/realistic/effect-of-match/policies")
        private val allCategories = Path.of("src/test/resources/diff/diff-all-categories")

        private lateinit var packDocs: Map<String, String>
        private lateinit var effectDocs: Map<String, String>
        private lateinit var diffReport: String

        @JvmStatic
        @BeforeAll
        fun renderDocuments() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            packDocs = Explico.render(Explico.load(pack), pack, Explico.loadExamples(packExamples), packData).files
            effectDocs = Explico.render(Explico.load(effects), effects).files
            diffReport = Explico.diff(Explico.load(allCategories.resolve("old")), Explico.load(allCategories.resolve("new"))).markdown
        }

        private fun count(text: String, needle: String) = text.windowed(needle.length).count { it == needle }

        /** The card whose anchor is [id]: from its `<a id>` to the next card's anchor (or the end of the document). */
        private fun card(markdown: String, id: String): String =
            markdown.substringAfter("<a id=\"$id\"></a>").substringBefore("<a id=\"")
    }

    @Nested
    @DisplayName("The acceptance pack")
    inner class Pack {

        @Test
        @DisplayName("REL-001 (a rule named deny) says a match is denied, and states its basis")
        fun rel001() {
            assertThat(card(packDocs.getValue("release-approvals.md"), "rel-001")).contains(DENIED)
        }

        @Test
        @DisplayName("every deny control in the pack has the line: REL-001..REL-004, once each")
        fun everyDenyControl() {
            for ((file, id) in listOf("release-approvals.md" to "rel-001", "release-evidence.md" to "rel-002", "release-provenance.md" to "rel-003", "release-governance.md" to "rel-004")) {
                assertThat(count(card(packDocs.getValue(file), id), "**Effect of a match:**")).describedAs(file).isEqualTo(1)
                assertThat(card(packDocs.getValue(file), id)).describedAs(file).contains(DENIED)
            }
        }

        @Test
        @DisplayName("the line sits after the description and before the conditions, with a blank line either side")
        fun placement() {
            val lines = card(packDocs.getValue("release-approvals.md"), "rel-001").lines()
            val at = lines.indexOf(DENIED)
            assertThat(at).isGreaterThan(lines.indexOfFirst { it.startsWith("and the change author must not approve") })
            assertThat(lines[at - 1]).isEmpty()
            assertThat(lines[at + 1]).isEmpty()
            assertThat(lines[at + 2]).startsWith("**The rule matches when ANY")
        }

        @Test
        @DisplayName("helper rules named anything else (is_release_candidate, exempt_service, all_checks_passed) get no line")
        fun helpersHaveNoLine() {
            assertThat(card(packDocs.getValue("release-evidence.md"), "release-evidence-is-release-candidate")).doesNotContain("Effect of a match")
            assertThat(packDocs.getValue("release-exemptions.md")).doesNotContain("Effect of a match")
            assertThat(card(packDocs.getValue("release-governance.md"), "release-governance-all-checks-passed")).doesNotContain("Effect of a match")
        }

        @Test
        @DisplayName("the index and no other document gains the line")
        fun indexHasNoLine() {
            assertThat(packDocs.getValue("index.md")).doesNotContain("Effect of a match")
        }
    }

    @Nested
    @DisplayName("effect-of-match fixture: one rule per convention")
    inner class Conventions {

        private val doc get() = effectDocs.getValue("effects.md")

        @Test
        @DisplayName("EM-001 deny: denied")
        fun deny() {
            assertThat(card(doc, "em-001")).contains(DENIED)
        }

        @Test
        @DisplayName("EM-002 violation: denied, naming `violation` as its basis")
        fun violation() {
            assertThat(card(doc, "em-002")).contains("**Effect of a match:** ❌ denied *(by the `violation` naming convention)*")
        }

        @Test
        @DisplayName("EM-003 allow: allowed, naming `allow` as its basis")
        fun allow() {
            assertThat(card(doc, "em-003")).contains("**Effect of a match:** ✅ allowed *(by the `allow` naming convention)*")
        }

        @Test
        @DisplayName("EM-004 (flag) has no naming convention, so no line: an effect is never guessed")
        fun unconventionalName() {
            assertThat(card(doc, "em-004")).doesNotContain("Effect of a match")
        }

        @Test
        @DisplayName("EM-005 (a function) has no line")
        fun function() {
            assertThat(card(doc, "em-005")).doesNotContain("Effect of a match")
        }

        @Test
        @DisplayName("exactly three cards in the fixture carry the line")
        fun exactlyThree() {
            assertThat(count(doc, "**Effect of a match:**")).isEqualTo(3)
        }
    }

    @Nested
    @DisplayName("Diff report cards reuse the same card, so they carry the same line")
    inner class DiffReport {

        @Test
        @DisplayName("the REMOVED REL-001 card and the ADDED REL-005 card both say denied")
        fun removedAndAddedCards() {
            assertThat(card(diffReport, "rel-001")).contains(DENIED)
            assertThat(card(diffReport, "rel-005")).contains(DENIED)
        }

        @Test
        @DisplayName("every card in the report has it: removed, added and logic-changed")
        fun everyCardHasIt() {
            assertThat(count(diffReport, "**Effect of a match:**")).isEqualTo(3)
        }
    }
}
