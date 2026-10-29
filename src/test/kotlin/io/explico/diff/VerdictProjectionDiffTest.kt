/**
 * Spec §14.53 in `diff`: the declared verdict changes how a rule's EXAMPLES read, not what the rule decides, so editing it is a
 * documentation change (DOCS_CHANGED) -- in the metadata hash, never the logic hash. Hand-built domain objects; no opa needed.
 */
package io.explico.diff

import io.explico.model.DeclaredProjection
import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RuleBody
import io.explico.model.RuleGroup
import io.explico.model.RuleMetadata
import io.explico.model.SourceRef
import io.explico.model.VerdictProjection
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class VerdictProjectionDiffTest {

    private fun rule(projection: DeclaredProjection?) = RuleGroup(
        "decision",
        RuleMetadata("T", "d", "C-1", listOf("F"), projection = projection),
        null,
        listOf(RuleBody(emptyList(), null, null, SourceRef("f.rego", 1), "decision := 1")),
    )

    private fun set(rule: RuleGroup) = PolicySet(listOf(PolicyPackage("p", listOf(rule), listOf("f.rego"))))
    private fun verdict(outcome: String, reason: String? = null) = DeclaredProjection(VerdictProjection(outcome, reason))

    @Test
    @DisplayName("CHALLENGE: a rule with no declaration hashes exactly as it did before projections existed (pinned value)")
    fun existingMetadataHashesAreUnchanged() {
        assertThat(Canonicalizer.metadataHash(rule(null))).isEqualTo("ced09f265c5e2cfda4be6d99dd515cde0d6f7a8336c8aa71e996cbb8ae496486")
    }

    @Test
    @DisplayName("declaring, changing or removing a verdict changes the metadata hash")
    fun metadataHashSeesTheDeclaration() {
        val none = Canonicalizer.metadataHash(rule(null))
        val a = Canonicalizer.metadataHash(rule(verdict("ok", "why")))
        assertThat(a).isNotEqualTo(none)
        assertThat(Canonicalizer.metadataHash(rule(verdict("ok", "other")))).isNotEqualTo(a)
        assertThat(Canonicalizer.metadataHash(rule(verdict("ok")))).isNotEqualTo(a)
        assertThat(Canonicalizer.metadataHash(rule(verdict("ok", "why")))).isEqualTo(a)
    }

    @Test
    @DisplayName("...but never the LOGIC hash")
    fun logicHashIgnoresTheDeclaration() {
        assertThat(Canonicalizer.logicHash(rule(verdict("ok", "why")))).isEqualTo(Canonicalizer.logicHash(rule(null)))
    }

    @Test
    @DisplayName("editing only the declaration is DOCS_CHANGED")
    fun docsChanged() {
        val entry = PolicyDiff.diff(set(rule(verdict("ok", "why"))), set(rule(verdict("ok", "other")))).single()
        assertThat(entry.category).isEqualTo(DiffCategory.DOCS_CHANGED)
    }

    @Test
    @DisplayName("the change summary names it, so the report says what changed")
    fun summaryNamesTheField() {
        val entry = PolicyDiff.diff(set(rule(null)), set(rule(verdict("ok")))).single()
        assertThat(ChangeSummary.changedMetadataFields(entry)).containsExactly("verdict")
    }

    @Test
    @DisplayName("the docs table shows old and new declaration -- and only when they differ")
    fun docsTableRow() {
        val changed = PolicyDiff.diff(set(rule(verdict("ok", "why"))), set(rule(verdict("allowed")))).single()
        val withRow = DiffRenderer.render(listOf(changed), set(rule(verdict("ok", "why"))), set(rule(verdict("allowed"))))
        assertThat(withRow).contains("| Verdict | outcome `ok`, reason `why` | outcome `allowed` |")

        val oldSet = set(rule(verdict("ok")))
        val newSet = set(rule(verdict("ok")).let { it.copy(metadata = it.metadata!!.copy(title = "New title")) })
        val titleOnly = PolicyDiff.diff(oldSet, newSet).single()
        assertThat(DiffRenderer.render(listOf(titleOnly), oldSet, newSet)).doesNotContain("| Verdict |")
    }
}
