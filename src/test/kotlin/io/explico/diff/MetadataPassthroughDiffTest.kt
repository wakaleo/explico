/**
 * Spec §14.43 and the diff: authors / related resources / custom fields are METADATA, so changing one must change the
 * metadata hash (DOCS_CHANGED), never the logic hash, and the change summary must name what differs. Hand-built domain
 * objects -- no opa needed.
 */
package io.explico.diff

import io.explico.model.Author
import io.explico.model.RelatedResource
import io.explico.model.RuleGroup
import io.explico.model.RuleMetadata
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MetadataPassthroughDiffTest {

    private val base = RuleMetadata("T", "d", "C-1", listOf("F"), listOf(Author("Ann", null)), listOf(RelatedResource("r", null)), mapOf("owner" to "a"))
    private fun rule(m: RuleMetadata) = RuleGroup("deny", m, null, emptyList())

    @Test
    fun changingAnyNewMetadataFieldChangesTheMetadataHashButNotTheLogicHash() {
        val variants = listOf(
            base.copy(authors = listOf(Author("Bob", null))),
            base.copy(authors = listOf(Author("Ann", "a@x.io"))),
            base.copy(relatedResources = listOf(RelatedResource("r2", null))),
            base.copy(relatedResources = listOf(RelatedResource("r", "desc"))),
            base.copy(customFields = mapOf("owner" to "b")),
            base.copy(customFields = mapOf("owner" to "a", "tier" to "1")),
        )
        for (v in variants) {
            assertThat(Canonicalizer.metadataHash(rule(v))).describedAs("%s", v).isNotEqualTo(Canonicalizer.metadataHash(rule(base)))
            assertThat(Canonicalizer.logicHash(rule(v))).describedAs("%s", v).isEqualTo(Canonicalizer.logicHash(rule(base)))
        }
    }

    @Test
    fun theSameMetadataHashesTheSame() {
        assertThat(Canonicalizer.metadataHash(rule(base))).isEqualTo(Canonicalizer.metadataHash(rule(base.copy())))
    }

    @Test
    fun theChangeSummaryNamesTheNewFieldsThatDiffer() {
        val old = rule(base)
        val new = rule(base.copy(authors = emptyList(), relatedResources = emptyList(), customFields = emptyMap()))
        val pkgOld = io.explico.model.PolicyPackage("p", listOf(old), listOf("f.rego"))
        val pkgNew = io.explico.model.PolicyPackage("p", listOf(new), listOf("f.rego"))
        val entry = DiffEntry(DiffCategory.DOCS_CHANGED, "C-1", pkgOld, old, pkgNew, new)

        assertThat(ChangeSummary.bulletFor(entry)).endsWith("documentation changed: authors, related resources, custom fields")
    }
}

class MetadataPassthroughDocsTableTest {

    private val base = RuleMetadata("T", "d", "C-1", listOf("F"))
    private fun rule(m: RuleMetadata) = RuleGroup("deny", m, null, emptyList())

    private fun report(old: RuleMetadata, new: RuleMetadata): String {
        val o = rule(old)
        val n = rule(new)
        val po = io.explico.model.PolicyPackage("p", listOf(o), listOf("f.rego"))
        val pn = io.explico.model.PolicyPackage("p", listOf(n), listOf("f.rego"))
        val entry = DiffEntry(DiffCategory.DOCS_CHANGED, "C-1", po, o, pn, n)
        return DiffRenderer.render(listOf(entry), io.explico.model.PolicySet(listOf(po)), io.explico.model.PolicySet(listOf(pn)))
    }

    @Test
    fun rowsForTheNewFieldsAppearOnlyWhenTheyDiffer() {
        val markdown = report(base, base.copy(authors = listOf(Author("Ann", "a@x.io")), customFields = mapOf("owner" to "p")))

        assertThat(markdown).contains("| Authors | — | Ann <a@x.io> |", "| Custom fields | — | owner: p |")
        assertThat(markdown).doesNotContain("Related resources")
    }

    @Test
    fun theThreeOriginalRowsAreUnchangedWhenOnlyTitleDiffers() {
        val markdown = report(base, base.copy(title = "T2"))
        assertThat(markdown).contains("| Title | T | T2 |", "| Description | d | d |", "| Frameworks | F | F |")
        assertThat(markdown).doesNotContain("Authors").doesNotContain("Custom fields").doesNotContain("Related resources")
    }

    @Test
    fun relatedResourcesShowTheirRefsWithDescriptions() {
        val markdown = report(base, base.copy(relatedResources = listOf(RelatedResource("https://x.io/a", "Runbook"), RelatedResource("https://x.io/b", null))))
        assertThat(markdown).contains("| Related resources | — | Runbook (https://x.io/a), https://x.io/b |")
    }
}
