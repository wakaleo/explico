/**
 * Acceptance tests for the index's below-100% note: a rule showing 0% (a helper whose only condition is a construct explico leaves
 * as source, here an `every` whose body it cannot render) reads like a failure, so the index names every rule below 100%, links each to its card, and says the unrendered
 * constructs are shown as source there; the legend adds that a helper at 0% says nothing about the rules that call it.
 * Authored from a compliance-owner review of the real output, not from the frozen README contract.
 * Fixtures: `realistic/every/` (two rules at 0%, one at 67%; the acceptance pack was the fixture until `every` rendered, spec §14.49)
 * and `realistic/effect-of-match/` (everything at 100%).
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
            "A helper rule can show 0% because its only condition is a construct explico does not render (such as a `with` override); " +
                "that says nothing about the rules that call it."

                private val everyPolicies = Path.of("src/test/resources/realistic/every/policies")
        private val everyExamples = Path.of("src/test/resources/realistic/every/examples")
        private val effects = Path.of("src/test/resources/realistic/effect-of-match/policies")

        private lateinit var packIndex: String
        private lateinit var packIndexWithoutExamples: String
        private lateinit var fullyRenderedIndex: String

        @JvmStatic
        @BeforeAll
        fun renderIndexes() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            packIndex = Explico.render(Explico.load(everyPolicies), everyPolicies, Explico.loadExamples(everyExamples)).files.getValue("index.md")
            packIndexWithoutExamples = Explico.render(Explico.load(everyPolicies), everyPolicies).files.getValue("index.md")
            fullyRenderedIndex = Explico.render(Explico.load(effects), effects).files.getValue("index.md")
        }
    }

    @Nested
    @DisplayName("realistic/every: EV-005 and EV-006 are at 0%")
    inner class WithARuleBelow100 {

        @Test
        @DisplayName("the note names the rules below 100% as links to their cards, and says where the source is")
        fun noteNamesTheRule() {
            assertThat(packIndex).contains(
                NOTE_START + "[Unrenderable body](every-demo.md#ev-005), [Literal domain](every-demo.md#ev-006)" + NOTE_END,
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
            assertThat(note).doesNotContain("All checks passed").doesNotContain("Containers hardened").doesNotContain("exempt_name")
        }

        @Test
        @DisplayName("the link resolves: the card's anchor is in release-governance.md")
        fun linkTargetExists() {
            val demo = Explico.render(Explico.load(everyPolicies), everyPolicies).files.getValue("every-demo.md")
            assertThat(demo).contains("<a id=\"ev-005\"></a>").contains("<a id=\"ev-006\"></a>")
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
