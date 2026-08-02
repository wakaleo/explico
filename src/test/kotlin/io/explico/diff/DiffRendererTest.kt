/**
 * Tests for DiffRenderer (spec §7.3): disclaimer wording, summary table, section ordering and
 * shapes per category, the coverage warning, driven by real `.rego` variants through `opa`.
 * Requires `opa` on PATH, same pattern as CanonicalizerTest/PolicyDiffTest.
 */
package io.explico.diff

import io.explico.Explico
import io.explico.model.PolicySet
import io.explico.opa.OpaRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Path

class DiffRendererTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
        }
    }

    private val canonicalizerFixtures = Path.of("src/test/resources/diff/canonicalizer")
    private val diffRendererFixtures = Path.of("src/test/resources/diff/diffrenderer")
    private val exemptionsDir = Path.of("src/test/resources/acceptance/policies/exemptions")

    private fun load(root: Path, variant: String): PolicySet = Explico.load(root.resolve(variant))

    private fun render(old: PolicySet, new: PolicySet): String =
        DiffRenderer.render(PolicyDiff.diff(old, new), old, new)

    @Test
    fun disclaimerAppearsVerbatimNearTheTop() {
        val markdown = render(load(canonicalizerFixtures, "base"), load(canonicalizerFixtures, "renamed-vars"))
        assertThat(markdown).contains(
            "This report shows structural changes only. It does not evaluate whether the " +
                "policy became stricter or more permissive.",
        )
        assertThat(markdown.indexOf("structural changes only")).isLessThan(markdown.indexOf("## Summary"))
    }

    @Test
    fun unchangedControlsProduceNoSectionAndCountOneInTheSummary() {
        val old = load(diffRendererFixtures, "unchanged-control")
        val new = load(diffRendererFixtures, "unchanged-control")
        val markdown = render(old, new)

        assertThat(markdown).contains("| UNCHANGED | 1 |")
        assertThat(markdown).doesNotContain("## Changes")
        assertThat(markdown).doesNotContain("Stable control")
    }

    @Test
    fun removedSectionShowsTheOldCardWithARemovalNotice() {
        val old = Explico.load(exemptionsDir)
        val new = PolicySet(emptyList())
        val markdown = render(old, new)

        assertThat(markdown).contains("| REMOVED | 1 |")
        assertThat(markdown).contains("**REMOVED**")
        assertThat(markdown).contains("This control has been removed")
        assertThat(markdown).contains("exempt_service")
        assertThat(markdown).contains("release.exemptions")
    }

    @Test
    fun addedSectionShowsTheNewCard() {
        val old = PolicySet(emptyList())
        val new = Explico.load(diffRendererFixtures.resolve("added-only"))
        val markdown = render(old, new)

        assertThat(markdown).contains("| ADDED | 1 |")
        assertThat(markdown).contains("**ADDED**")
        assertThat(markdown).contains("NEW-001")
        assertThat(markdown).contains("Brand new control")
        assertThat(markdown).doesNotContain("This control has been removed")
    }

    @Test
    fun logicChangedSectionShowsTheNewCardPlusAUnifiedSourceDiff() {
        val old = load(canonicalizerFixtures, "base")
        val new = load(canonicalizerFixtures, "operand-changed")
        val markdown = render(old, new)

        assertThat(markdown).contains("| LOGIC_CHANGED | 1 |")
        assertThat(markdown).contains("**LOGIC_CHANGED**")
        assertThat(markdown).contains("**Source diff**")
        assertThat(markdown).contains("```rego")
        assertThat(markdown).contains("- \tinput.deployment.environment == \"production\"")
        assertThat(markdown).contains("+ \tinput.deployment.environment == \"staging\"")
    }

    @Test
    fun docsChangedSectionShowsOldVsNewAsATwoColumnTable() {
        val old = Explico.load(diffRendererFixtures.resolve("docs-old"))
        val new = Explico.load(diffRendererFixtures.resolve("docs-new"))
        val markdown = render(old, new)

        assertThat(markdown).contains("| DOCS_CHANGED | 1 |")
        assertThat(markdown).contains("**DOCS_CHANGED**")
        assertThat(markdown).contains("| Title | Docs control | Docs control (updated) |")
        assertThat(markdown).contains("| Description | Original description. | Updated description text. |")
    }

    @Test
    fun sectionsAreOrderedRemovedAddedLogicChangedThenDocsChanged() {
        val old = PolicySet(
            Explico.load(exemptionsDir).packages +
                load(canonicalizerFixtures, "base").packages +
                Explico.load(diffRendererFixtures.resolve("docs-old")).packages,
        )
        val new = PolicySet(
            Explico.load(diffRendererFixtures.resolve("added-only")).packages +
                load(canonicalizerFixtures, "operand-changed").packages +
                Explico.load(diffRendererFixtures.resolve("docs-new")).packages,
        )
        val markdown = render(old, new)

        val removedIndex = markdown.indexOf("**REMOVED**")
        val addedIndex = markdown.indexOf("**ADDED**")
        val logicChangedIndex = markdown.indexOf("**LOGIC_CHANGED**")
        val docsChangedIndex = markdown.indexOf("**DOCS_CHANGED**")

        assertThat(listOf(removedIndex, addedIndex, logicChangedIndex, docsChangedIndex)).allMatch { it >= 0 }
        assertThat(removedIndex).isLessThan(addedIndex)
        assertThat(addedIndex).isLessThan(logicChangedIndex)
        assertThat(logicChangedIndex).isLessThan(docsChangedIndex)
    }

    @Test
    fun withinOneCategoryEntriesAreOrderedByControlIdAscending() {
        val old = PolicySet(emptyList())
        val new = PolicySet(
            Explico.load(diffRendererFixtures.resolve("added-only")).packages +
                Explico.load(diffRendererFixtures.resolve("added-only-2")).packages,
        )
        val markdown = render(old, new)

        // AAA-001 sorts before NEW-001.
        assertThat(markdown.indexOf("AAA-001")).isLessThan(markdown.indexOf("NEW-001"))
    }

    @Test
    fun coverageWarningAppearsForAChangedControlBelowFullCoverage() {
        val old = Explico.load(diffRendererFixtures.resolve("every-old"))
        val new = Explico.load(diffRendererFixtures.resolve("every-new"))
        val markdown = render(old, new)

        assertThat(markdown).contains("⚠ 1 changed controls contain conditions that could not be rendered; review source diffs directly.")
    }

    @Test
    fun noCoverageWarningWhenAllChangedControlsAreFullyRendered() {
        val old = load(canonicalizerFixtures, "base")
        val new = load(canonicalizerFixtures, "operand-changed")
        val markdown = render(old, new)

        assertThat(markdown).doesNotContain("could not be rendered")
    }

    @Test
    fun explicoDiffFacadeWiresPolicyDiffAndDiffRendererTogether() {
        val old = load(canonicalizerFixtures, "base")
        val new = load(canonicalizerFixtures, "operand-changed")
        val report = Explico.diff(old, new)

        assertThat(report.entries).hasSize(1)
        assertThat(report.entries.single().category).isEqualTo(DiffCategory.LOGIC_CHANGED)
        assertThat(report.markdown).isEqualTo(render(old, new))
    }

    @Test
    fun explicoDiffFacadePropagatesDuplicateControlIdsWithoutRenderingAnything() {
        val old = Explico.load(Path.of("src/test/resources/diff/policydiff/duplicate-control-ids"))
        val new = load(canonicalizerFixtures, "base")

        assertThatThrownBy { Explico.diff(old, new) }.isInstanceOf(DuplicateControlIdException::class.java)
    }
}
