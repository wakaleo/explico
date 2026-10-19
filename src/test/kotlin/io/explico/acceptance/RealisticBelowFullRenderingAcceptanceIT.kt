/**
 * Acceptance tests for the index's below-100% note: a rule showing 0% (a helper whose only condition is an `every` block)
 * reads like a failure, so the index names every rule below 100%, links each to its card, and says the unrendered
 * constructs are shown as source there; the legend adds that a helper at 0% says nothing about the rules that call it.
 * Authored from a compliance-owner review of the real output, not from the frozen README contract.
 * Fixtures: the acceptance pack (one rule at 0%) and `realistic/effect-of-match/` (everything at 100%).
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

@DisplayName("Realistic policies: index note for rules below 100%")
class RealisticBelowFullRenderingAcceptanceIT {

    companion object {
        private const val NOTE_START = "*Below 100%: "
        private const val NOTE_END = " -- each shows the constructs it could not render as source on its own card.*"
        private const val LEGEND_SENTENCE =
            "A helper rule can show 0% because its only condition is a construct explico does not render (such as every); " +
                "that says nothing about the rules that call it."

        private val pack = Path.of("src/test/resources/acceptance/policies")
        private val packExamples = Path.of("src/test/resources/acceptance/examples")
        private val packData = Path.of("src/test/resources/acceptance/data/release/data.json")
        private val effects = Path.of("src/test/resources/realistic/effect-of-match/policies")

        private lateinit var packIndex: String
        private lateinit var packIndexWithoutExamples: String
        private lateinit var fullyRenderedIndex: String

        @JvmStatic
        @BeforeAll
        fun renderIndexes() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            packIndex = Explico.render(Explico.load(pack), pack, Explico.loadExamples(packExamples), packData).files.getValue("index.md")
            packIndexWithoutExamples = Explico.render(Explico.load(pack), pack).files.getValue("index.md")
            fullyRenderedIndex = Explico.render(Explico.load(effects), effects).files.getValue("index.md")
        }
    }

    @Nested
    @DisplayName("The acceptance pack: all_checks_passed is at 0%")
    inner class WithARuleBelow100 {

        @Test
        @DisplayName("the note names the one rule below 100% as a link to its card, and says where the source is")
        fun noteNamesTheRule() {
            assertThat(packIndex).contains(
                NOTE_START + "[Mandatory check completeness](release-governance.md#release-governance-all-checks-passed)" + NOTE_END,
            )
        }

        @Test
        @DisplayName("the note sits after the overall line and before the legend, with and without examples")
        fun placement() {
            for (index in listOf(packIndex, packIndexWithoutExamples)) {
                assertThat(index.indexOf(NOTE_START)).isGreaterThan(index.indexOf("*Overall rendering coverage:"))
                assertThat(index.indexOf(NOTE_START)).isLessThan(index.indexOf("## How to read this index"))
            }
        }

        @Test
        @DisplayName("only the rule below 100% is named: no 100% rule appears in the note")
        fun onlyRulesBelow100() {
            val note = packIndex.lines().single { it.startsWith(NOTE_START) }
            assertThat(note).doesNotContain("Production change approval").doesNotContain("Pipeline evidence").doesNotContain("exempt_service")
        }

        @Test
        @DisplayName("the link resolves: the card's anchor is in release-governance.md")
        fun linkTargetExists() {
            val governance = Explico.render(Explico.load(pack), pack).files.getValue("release-governance.md")
            assertThat(governance).contains("<a id=\"release-governance-all-checks-passed\"></a>")
        }
    }

    @Nested
    @DisplayName("A fully rendered set")
    inner class WithEverythingAt100 {

        @Test
        @DisplayName("there is no note at all")
        fun noNote() {
            assertThat(fullyRenderedIndex).doesNotContain("Below 100%")
        }
    }

    @Nested
    @DisplayName("The legend")
    inner class Legend {

        @Test
        @DisplayName("always carries the sentence that a helper at 0% says nothing about its callers")
        fun legendSentence() {
            for (index in listOf(packIndex, packIndexWithoutExamples, fullyRenderedIndex)) {
                assertThat(index.substringAfter("## How to read this index")).contains(LEGEND_SENTENCE)
            }
        }
    }
}
