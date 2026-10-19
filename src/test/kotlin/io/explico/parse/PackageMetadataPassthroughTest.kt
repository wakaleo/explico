/**
 * Spec §14.43 at package scope: a `scope: package` annotation's authors / related_resources / extra custom keys reach
 * [io.explico.model.PackageMetadata], mapped by the same rules as a rule's, and render with the same lines. No opa needed.
 */
package io.explico.parse

import io.explico.model.Author
import io.explico.model.PackageMetadata
import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RelatedResource
import io.explico.model.RuleGroup
import io.explico.model.RuleMetadata
import io.explico.opa.OpaModule
import io.explico.opa.opaJson
import io.explico.render.MarkdownRenderer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class PackageMetadataPassthroughTest {

    private fun metadataOf(annotation: String): PackageMetadata? {
        val module = opaJson.decodeFromString(
            OpaModule.serializer(),
            """{"package":{"path":[{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},"annotations":[$annotation],"rules":[]}""",
        )
        return AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages.single().metadata
    }

    @Test
    fun authorsRelatedResourcesAndCustomFieldsAreMapped() {
        val m = metadataOf(
            """{"scope":"package","title":"T",
               "authors":[{"name":"Ann","email":"a@x.io"},{"name":"Bob"}],
               "related_resources":[{"ref":"https://x.io/a","description":"Runbook"},{"ref":"https://x.io/b"}],
               "custom":{"tags":["a","b"],"owner":"p"}}""",
        )!!
        assertThat(m.authors).containsExactly(Author("Ann", "a@x.io"), Author("Bob", null))
        assertThat(m.relatedResources).containsExactly(RelatedResource("https://x.io/a", "Runbook"), RelatedResource("https://x.io/b", null))
        assertThat(m.customFields.entries.map { it.key to it.value }).containsExactly("owner" to "p", "tags" to "a, b")
    }

    @Test
    @DisplayName("CHALLENGE: an author with neither name nor email, a resource with no ref, structured values and control-id/frameworks are all dropped")
    fun degenerateEntriesAreDropped() {
        val m = metadataOf(
            """{"scope":"package","title":"T","authors":[{}],"related_resources":[{"description":"orphan"}],
               "custom":{"control-id":"X","frameworks":["F"],"obj":{"a":1},"ok":"yes"}}""",
        )!!
        assertThat(m.authors).isEmpty()
        assertThat(m.relatedResources).isEmpty()
        assertThat(m.customFields).containsOnlyKeys("ok")
    }

    @Test
    fun anAnnotationWithNoNewFieldsKeepsThemEmpty() {
        val m = metadataOf("""{"scope":"package","title":"T"}""")!!
        assertThat(m.authors).isEmpty()
        assertThat(m.relatedResources).isEmpty()
        assertThat(m.customFields).isEmpty()
    }

    private fun page(metadata: PackageMetadata, rules: List<RuleGroup> = emptyList()): String {
        val pkg = PolicyPackage("p", rules, listOf("f.rego"), metadata)
        return MarkdownRenderer.renderPackage(pkg, PolicySet(listOf(pkg)))
    }

    @Test
    fun thePackagePageShowsTheThreeLinesAfterTheDescriptionInCardOrder() {
        val markdown = page(
            PackageMetadata("Title", "Desc", listOf(Author("Ann", "a@x.io")), listOf(RelatedResource("https://x.io/a", null)), mapOf("owner" to "p")),
        )
        val lines = markdown.lines()
        val at = lines.indexOf("Desc")
        assertThat(lines.subList(at + 2, at + 5)).containsExactly(
            "*Authors: Ann <a@x.io>*",
            "*Related resources: [https://x.io/a](https://x.io/a)*",
            "*Custom: owner: p*",
        )
        assertThat(lines[at + 5]).isEmpty()
    }

    @Test
    fun anAuthorsOnlyPackageWithNoTitleOrDescriptionStillRenders() {
        val markdown = page(PackageMetadata(null, null, authors = listOf(Author("Ola", null))))
        assertThat(markdown).contains("*Authors: Ola*").doesNotContain("**")
    }

    @Test
    @DisplayName("the card and the package page share one formatting helper: identical data gives identical lines")
    fun cardAndPackagePageWordTheLinesIdentically() {
        val authors = listOf(Author("Ann", "a@x.io"), Author(null, "z@x.io"))
        val resources = listOf(RelatedResource("https://x.io/a", "Runbook"), RelatedResource("https://x.io/b", null))
        val custom = mapOf("owner" to "p", "tags" to "a, b")
        val pageLines = page(PackageMetadata("T", "D", authors, resources, custom)).lines().filter { it.startsWith("*Authors") || it.startsWith("*Related") || it.startsWith("*Custom") }

        val rule = RuleGroup("flag", RuleMetadata("T", "D", "C-1", emptyList(), authors, resources, custom), null, emptyList())
        val pkg = PolicyPackage("p", listOf(rule), listOf("f.rego"))
        val cardLines = MarkdownRenderer.renderCard(rule, pkg, PolicySet(listOf(pkg))).lines().filter { it.startsWith("*Authors") || it.startsWith("*Related") || it.startsWith("*Custom") }

        assertThat(pageLines).hasSize(3).isEqualTo(cardLines)
    }

    @Test
    fun emptyNewFieldsPrintNothing() {
        assertThat(page(PackageMetadata("T", "D"))).doesNotContain("Authors:").doesNotContain("Related resources:").doesNotContain("Custom:")
    }
}
