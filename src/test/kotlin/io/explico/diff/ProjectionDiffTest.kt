/**
 * Spec §14.54 in `diff`: the declared actions, annotations and timing change how a rule's EXAMPLES read, not what the rule decides, so editing
 * one is a documentation change (DOCS_CHANGED) -- in the metadata hash, never the logic hash -- named in the summary in a fixed order, with a
 * docs-table row only for what differs. Hand-built domain objects; no opa needed.
 */
package io.explico.diff

import io.explico.model.ActionsProjection
import io.explico.model.AnnotationsProjection
import io.explico.model.DeclaredProjection
import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RuleBody
import io.explico.model.RuleGroup
import io.explico.model.RuleMetadata
import io.explico.model.SourceRef
import io.explico.model.TimingProjection
import io.explico.model.VerdictProjection
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ProjectionDiffTest {

    private fun rule(projection: DeclaredProjection?) = RuleGroup(
        "decision",
        RuleMetadata("T", "d", "C-1", listOf("F"), projection = projection),
        null,
        listOf(RuleBody(emptyList(), null, null, SourceRef("f.rego", 1), "decision := 1")),
    )

    private fun set(rule: RuleGroup) = PolicySet(listOf(PolicyPackage("p", listOf(rule), listOf("f.rego"))))
    private val actions = ActionsProjection("actions", "action.predicate")
    private val annotations = AnnotationsProjection("annotations")
    private val timing = TimingProjection("dueBy", "startBefore")

    private fun entry(old: DeclaredProjection?, new: DeclaredProjection?) = PolicyDiff.diff(set(rule(old)), set(rule(new))).single()
    private fun report(old: DeclaredProjection?, new: DeclaredProjection?): String = DiffRenderer.render(listOf(entry(old, new)), set(rule(old)), set(rule(new)))

    @Test
    @DisplayName("CHALLENGE: a rule with only a verdict hashes exactly as it did before the other projections existed (pinned value)")
    fun verdictOnlyHashIsUnchanged() {
        assertThat(Canonicalizer.metadataHash(rule(DeclaredProjection(VerdictProjection("ok", "why"))))).isEqualTo("4364bdd1fea48cfdeb3595aa7add313ad3ce15b573bfb6a46acfbb431ca5ad4b")
    }

    @Test
    @DisplayName("declaring, changing or removing each of the three changes the metadata hash, and never the logic hash")
    fun eachIsDocumentation() {
        val none = rule(null)
        val variants = listOf(
            DeclaredProjection(actions = actions) to DeclaredProjection(actions = ActionsProjection("actions", "other")),
            DeclaredProjection(annotations = annotations) to DeclaredProjection(annotations = AnnotationsProjection("tags")),
            DeclaredProjection(timing = timing) to DeclaredProjection(timing = TimingProjection("dueBy", null)),
        )
        for ((a, b) in variants) {
            assertThat(Canonicalizer.metadataHash(rule(a))).isNotEqualTo(Canonicalizer.metadataHash(none))
            assertThat(Canonicalizer.metadataHash(rule(a))).isNotEqualTo(Canonicalizer.metadataHash(rule(b)))
            assertThat(Canonicalizer.metadataHash(rule(a))).isEqualTo(Canonicalizer.metadataHash(rule(a.copy())))
            assertThat(Canonicalizer.logicHash(rule(a))).isEqualTo(Canonicalizer.logicHash(none))
        }
    }

    @Test
    @DisplayName("CHALLENGE: the three are distinguished from each other (the same field name under another projection is a different declaration)")
    fun projectionsAreNotInterchangeable() {
        val hashes = listOf(
            DeclaredProjection(actions = ActionsProjection("x", "x")),
            DeclaredProjection(annotations = AnnotationsProjection("x")),
            DeclaredProjection(timing = TimingProjection("x", null)),
            DeclaredProjection(timing = TimingProjection(null, "x")),
            DeclaredProjection(verdict = VerdictProjection("x")),
        ).map { Canonicalizer.metadataHash(rule(it)) }
        assertThat(hashes).doesNotHaveDuplicates()
    }

    @Test
    @DisplayName("editing only one of them is DOCS_CHANGED")
    fun docsChanged() {
        assertThat(entry(DeclaredProjection(actions = actions), DeclaredProjection(actions = ActionsProjection("a", "b"))).category).isEqualTo(DiffCategory.DOCS_CHANGED)
    }

    @Test
    @DisplayName("the summary names what changed, in the fixed order: verdict, actions, annotations, timing")
    fun summaryOrder() {
        val old = DeclaredProjection(VerdictProjection("a"), actions, annotations, timing)
        val new = DeclaredProjection(VerdictProjection("b"), ActionsProjection("x", "y"), AnnotationsProjection("z"), TimingProjection("t", null))
        assertThat(ChangeSummary.changedMetadataFields(entry(old, new))).containsExactly("verdict", "actions", "annotations", "timing")
        assertThat(ChangeSummary.changedMetadataFields(entry(old, old.copy(timing = null)))).containsExactly("timing")
        assertThat(ChangeSummary.changedMetadataFields(entry(null, DeclaredProjection(annotations = annotations)))).containsExactly("annotations")
    }

    @Test
    @DisplayName("the docs table shows a row per changed declaration (old and new, as declared) and none for one that did not change")
    fun docsTableRows() {
        val old = DeclaredProjection(VerdictProjection("ok"), actions, annotations, timing)
        val new = old.copy(actions = ActionsProjection("actions", "action.name"), timing = TimingProjection("due", null))
        val markdown = report(old, new)

        assertThat(markdown.lines().filter { it.startsWith("| Actions |") }.single()).contains("`action.predicate`").contains("`action.name`")
        assertThat(markdown.lines().filter { it.startsWith("| Timing |") }.single()).contains("dueBy `dueBy`").contains("startBefore `startBefore`").contains("dueBy `due`")
        assertThat(markdown).doesNotContain("| Annotations |").doesNotContain("| Verdict |")
    }

    @Test
    @DisplayName("a declaration added or removed shows '—' on the other side")
    fun addedAndRemoved() {
        val added = report(null, DeclaredProjection(annotations = annotations))
        assertThat(added.lines().single { it.startsWith("| Annotations |") }).contains("| — |").contains("at `annotations`")
        val removed = report(DeclaredProjection(annotations = annotations), null)
        assertThat(removed.lines().single { it.startsWith("| Annotations |") }).endsWith("| — |")
    }
}
