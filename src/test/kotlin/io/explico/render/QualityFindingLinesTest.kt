/**
 * Spec §14.67: the summary sentences for the findings, worded once in `Quality.findingLines` (like `gapDescriptions`). Hand-built findings; no opa. The real
 * index, library and CLI output are pinned by RealisticFindingsSummaryAcceptanceIT and CliFindingsSummaryProcessTest.
 */
package io.explico.render

import io.explico.model.CommentBlock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class QualityFindingLinesTest {

    private fun refs(n: Int) = (1..n).map { Quality.ControlRef("C-$it", "p", "c-$it") }
    private fun blocks(n: Int) = (1..n).map { CommentBlock("f.rego", it * 10, 2) }
    private fun shown(vararg counts: Int) = counts.mapIndexed { i, c -> Quality.ShownAsSource("reason-$i", c, refs(1)) }
    private fun titles(n: Int) = (1..n).map { "Title $it" to refs(2) }

    private fun lines(f: Quality.Findings) = Quality.findingLines(f)

    @Test
    @DisplayName("nothing to report: no lines")
    fun empty() {
        assertThat(lines(Quality.Findings(emptyList(), emptyList(), emptyList()))).isEmpty()
    }

    @Test
    @DisplayName("one of each kind, in the section's order, as singular sentences")
    fun singular() {
        val f = Quality.Findings(
            missingDescription = refs(1), noFramework = refs(1), duplicateTitles = titles(1), noMatchingExample = refs(1), rawDecisionObject = refs(1),
            unpublishedComments = blocks(1), shownAsSource = shown(1),
        )
        assertThat(lines(f)).containsExactly(
            "1 control has no description",
            "1 control names no framework",
            "1 control has no matching example",
            "1 control returns an object but declares no custom.verdict",
            "1 comment block is not published",
            "1 condition is shown as source",
            "1 title is shared by several controls",
        )
    }

    @Test
    @DisplayName("two and eleven of each kind: plural sentences with the right number; conditions are summed over the reasons; titles are counted, not their controls")
    fun plural() {
        val f = Quality.Findings(
            missingDescription = refs(2), noFramework = refs(11), duplicateTitles = titles(2), noMatchingExample = refs(2), rawDecisionObject = refs(11),
            unpublishedComments = blocks(2), shownAsSource = shown(5, 6),
        )
        assertThat(lines(f)).containsExactly(
            "2 controls have no description",
            "11 controls name no framework",
            "2 controls have no matching example",
            "11 controls return an object but declare no custom.verdict",
            "2 comment blocks are not published",
            "11 conditions are shown as source",
            "2 titles are shared by several controls",
        )
    }

    @Test
    @DisplayName("a kind with nothing to say is left out, the others keep their order")
    fun onlyWhatIsThere() {
        val f = Quality.Findings(emptyList(), refs(3), emptyList(), unpublishedComments = blocks(1), shownAsSource = shown(1, 1))
        assertThat(lines(f)).containsExactly("3 controls name no framework", "1 comment block is not published", "2 conditions are shown as source")
    }

    @Test
    @DisplayName("a missing title is not a displayed finding, so it is not a line")
    fun missingTitleIsNotAFinding() {
        assertThat(lines(Quality.Findings(emptyList(), emptyList(), emptyList(), missingTitle = refs(4)))).isEmpty()
    }

    @Test
    @DisplayName("CHALLENGE: a very large count is printed whole")
    fun largeCounts() {
        assertThat(lines(Quality.Findings(refs(12345), emptyList(), emptyList()))).containsExactly("12345 controls have no description")
    }
}
