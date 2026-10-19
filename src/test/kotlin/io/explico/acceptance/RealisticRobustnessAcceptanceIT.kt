/**
 * Acceptance tests for the four real-world shapes found by benchmarking explico against public Rego
 * corpora (Gatekeeper, Trivy, Regal, conftest), each of which used to abort the render of the whole
 * file: an OBJECT-shaped `custom.frameworks`, a call-headed ref chain, a pattern function parameter,
 * and a `sprintf` whose verbs do not line up with its arguments. The contract pinned here: the
 * document renders, no card is lost, and nothing is invented -- an unmodelled METADATA shape is
 * ignored, an unmappable operand is its own verbatim source, and a message that cannot be aligned
 * has no Produces line rather than a misaligned one.
 *
 * NOT transcribed from the frozen acceptance-pack README: authored from the benchmark findings.
 * Fixture: `src/test/resources/realistic/robustness/robust.rego`.
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

@DisplayName("Realistic policies: shapes that used to abort the render")
class RealisticRobustnessAcceptanceIT {

    companion object {
        private lateinit var doc: String

        @JvmStatic
        @BeforeAll
        fun renderDocument() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            val dir = Path.of("src/test/resources/realistic/robustness")
            doc = Explico.render(Explico.load(dir), dir).files.getValue("robust.md")
        }

        private fun card(headingContains: String): String {
            val lines = doc.lines()
            val start = lines.indexOfFirst { it.startsWith("## ") && it.contains(headingContains) }
            assertThat(start).describedAs("a '## ' heading containing %s", headingContains).isGreaterThanOrEqualTo(0)
            val end = lines.drop(start + 1).indexOfFirst { it.startsWith("## ") }
            return lines.drop(start).let { if (end < 0) it else it.take(end + 1) }.joinToString("\n")
        }

        private fun bullets(markdown: String): List<String> = markdown.lines().filter { it.startsWith("- ") }
    }

    @Nested
    @DisplayName("A — the whole file renders and no card is lost")
    inner class WholeFileRenders {
        @Test
        fun everyRuleHasItsCard() {
            for (heading in listOf("RB-001", "robust.call_headed", "robust.lookup", "robust.pair_ok", "robust.pattern_param", "robust.misaligned", "robust.aligned")) {
                assertThat(card(heading)).startsWith("## ")
            }
        }
    }

    @Nested
    @DisplayName("B — an object-shaped custom.frameworks is ignored, not fatal and not invented")
    inner class ObjectShapedFrameworks {
        @Test
        fun controlIdStillReadsAndNothingIsInventedFromTheObject() {
            assertThat(card("RB-001")).contains("RB-001 — Object-shaped frameworks")
            assertThat(doc).doesNotContain("cis-aws").doesNotContain("1.20")
        }
    }

    @Nested
    @DisplayName("C — a call-headed chain is its own verbatim source and is counted as unrendered")
    inner class CallHeadedChain {
        @Test
        fun showsTheRawChainAndCountsIt() {
            val c = card("robust.call_headed")
            assertThat(bullets(c).single()).contains("`lookup(input.x).name`")
            assertThat(c).contains("1 unrendered value")
        }
    }

    @Nested
    @DisplayName("D — a pattern parameter is shown verbatim and the function stays callable")
    inner class PatternParameter {
        @Test
        fun parametersLineShowsThePatternAndCallsStillLink() {
            assertThat(card("robust.pair_ok")).contains("*Parameters: `[a, b]`*")
            assertThat(bullets(card("robust.pattern_param")).single()).contains("[`pair_ok`](#robust-pair-ok)")
        }
    }

    @Nested
    @DisplayName("E — a sprintf whose verbs do not line up has no Produces line; an aligned one keeps it")
    inner class SprintfAlignment {
        @Test
        fun misalignedMessageIsOmittedNotMisaligned() {
            val c = card("robust.misaligned")
            assertThat(c).doesNotContain("*Produces:*").doesNotContain("%d")
        }

        @Test
        fun alignedMessageStillRenders() {
            assertThat(card("robust.aligned")).contains("*Produces:* \"[total] of [name]\"")
        }
    }
}
