/**
 * Unit tests for the "Showing M of N examples" line of a card (spec §14.79): a card lists at most [WorkedExamples.MAX_MATCHING] matching then
 * [WorkedExamples.MAX_NON_MATCHING] non-matching examples; when it has more outcomes than it lists it says so, once, before the provenance footer. Hand-built outcomes -- no opa;
 * the real pages (and the grouped control page) are pinned by RealisticReportClarityAcceptanceIT.
 */
package io.explico.render

import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RuleBody
import io.explico.model.RuleGroup
import io.explico.model.RuleMetadata
import io.explico.model.SourceRef
import kotlinx.serialization.json.JsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ExamplesShownNoteTest {

    private fun outcomes(matching: Int, nonMatching: Int): List<WorkedExample> =
        List(matching) { WorkedExample(Fixture("m$it", null, JsonObject(emptyMap())), true, listOf("m"), listOf(1)) } +
            List(nonMatching) { WorkedExample(Fixture("n$it", null, JsonObject(emptyMap())), false, emptyList(), emptyList()) }

    private fun line(shown: Int, total: Int) = "*Showing $shown of $total examples: at most 3 matching, then at most 2 non-matching, in file-name order.*"

    @Nested
    @DisplayName("The note")
    inner class Note {

        @Test
        @DisplayName("the selection rule is named once: 3 matching then 2 non-matching")
        fun constants() {
            assertThat(WorkedExamples.MAX_MATCHING).isEqualTo(3)
            assertThat(WorkedExamples.MAX_NON_MATCHING).isEqualTo(2)
        }

        @Test
        @DisplayName("nothing omitted (everything listed, or nothing to list): no note")
        fun noneOmitted() {
            for ((m, n) in listOf(0 to 0, 3 to 0, 0 to 2, 3 to 2, 1 to 1, 2 to 1, 1 to 2)) assertThat(WorkedExamples.shownNote(outcomes(m, n))).describedAs("$m matching, $n not").isNull()
        }

        @Test
        @DisplayName("boundaries: one past either cap is the first time the note appears")
        fun boundaries() {
            assertThat(WorkedExamples.shownNote(outcomes(4, 0))).isEqualTo(line(3, 4))
            assertThat(WorkedExamples.shownNote(outcomes(0, 3))).isEqualTo(line(2, 3))
            assertThat(WorkedExamples.shownNote(outcomes(4, 1))).describedAs("5 outcomes, 4 match: 3 + 1 listed").isEqualTo(line(4, 5))
            assertThat(WorkedExamples.shownNote(outcomes(2, 4))).isEqualTo(line(4, 6))
            assertThat(WorkedExamples.shownNote(outcomes(4, 3))).isEqualTo(line(5, 7))
            assertThat(WorkedExamples.shownNote(outcomes(6, 0))).isEqualTo(line(3, 6))
        }

        @Test
        @DisplayName("M is exactly the number WorkedExamples.select lists, so the note cannot disagree with the list")
        fun sameAsSelect() {
            for (m in 0..8) for (n in 0..8) {
                val all = outcomes(m, n)
                val note = WorkedExamples.shownNote(all)
                val shown = WorkedExamples.select(all).size
                if (shown == all.size) assertThat(note).isNull() else assertThat(note).isEqualTo(line(shown, all.size))
            }
        }
    }

    @Nested
    @DisplayName("On the card")
    inner class OnTheCard {

        private val rule = RuleGroup("deny", RuleMetadata("T", "d", "C-1", listOf("F")), null, listOf(RuleBody(emptyList(), "m", "m", SourceRef("f.rego", 1), "")))
        private val pkg = PolicyPackage("p", listOf(rule), listOf("f.rego"))
        private val set = PolicySet(listOf(pkg))
        private val footer = "*Examples are evaluated against this policy version by OPA at generation time.*"

        @Test
        @DisplayName("the line is the last thing before the footer; with everything listed there is no line")
        fun position() {
            val omitted = MarkdownRenderer.renderPackage(pkg, set, mapOf("deny" to outcomes(4, 3))).lines().filter { it.isNotBlank() }
            val at = omitted.indexOf(line(5, 7))

            assertThat(at).isGreaterThan(0)
            assertThat(omitted[at + 1]).isEqualTo(footer)
            assertThat(omitted[at - 1]).describedAs("the line after the last example entry").startsWith("- **n1**")
            assertThat(MarkdownRenderer.renderPackage(pkg, set, mapOf("deny" to outcomes(3, 2)))).doesNotContain("Showing")
        }

        @Test
        @DisplayName("a card with no outcomes has no examples section and no note")
        fun noOutcomes() {
            assertThat(MarkdownRenderer.renderPackage(pkg, set, emptyMap())).doesNotContain("Showing").doesNotContain("Worked examples")
        }
    }
}
