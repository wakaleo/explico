/**
 * Spec §14.46 layout: the optional index sections each sit between blank lines and never leave a double blank line, whichever of
 * them are present. Acceptance tests deliberately do not pin whitespace; this does. No opa.
 */
package io.explico.render

import io.explico.model.Condition
import io.explico.model.Operand
import io.explico.model.Operator
import io.explico.model.PathSegment
import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RuleBody
import io.explico.model.RuleGroup
import io.explico.model.RuleMetadata
import io.explico.model.SourceRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IndexLayoutTest {

    private val rendered = Condition.Comparison(Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("x"))), Operator.EQ, Operand.Literal("1"))
    private val unrendered = Condition.Unrendered("every x in y {}", "unclassified")

    private fun control(id: String, vararg conditions: Condition, description: String? = null, frameworks: List<String> = emptyList()) =
        RuleGroup("r_$id", RuleMetadata("T", description, id, frameworks), null, listOf(RuleBody(conditions.toList(), null, null, SourceRef("f.rego", 1), "")))

    private fun index(requirements: List<String>, vararg rules: RuleGroup) =
        MarkdownRenderer.renderIndex(PolicySet(listOf(PolicyPackage("p", rules.toList(), listOf("f.rego")))), requirements = requirements)

    private fun assertWellSpaced(markdown: String) {
        assertThat(markdown).doesNotContain("\n\n\n")
        val lines = markdown.lines()
        for ((i, line) in lines.withIndex()) if (line.startsWith("## ")) assertThat(lines[i - 1]).describedAs("line before '%s'", line).isEmpty()
        for ((i, line) in lines.withIndex()) if (line.startsWith("## ") && i + 1 < lines.size) assertThat(lines[i + 1]).describedAs("line after '%s'", line).isEmpty()
    }

    @Test
    fun qualityFindingsAloneAreSpacedLikeEveryOtherSection() {
        assertWellSpaced(index(emptyList(), control("C-1", rendered)))
    }

    @Test
    @org.junit.jupiter.api.DisplayName("spec §14.63: with every kind of finding present the bullets run missing description, no framework, no matching example, raw decision object, duplicate title; spacing holds")
    fun allQualityFindingsAreInTheDocumentedOrder() {
        val fixture = Fixture("f", null, kotlinx.serialization.json.JsonObject(emptyMap()))
        val decision = kotlinx.serialization.json.buildJsonObject { put("compliant", kotlinx.serialization.json.JsonPrimitive(false)) }
        val examples = mapOf(
            "p" to mapOf(
                "r_C-1" to listOf(WorkedExample(fixture, true, emptyList(), emptyList(), decision)),
                "r_C-2" to listOf(WorkedExample(fixture, false, emptyList(), emptyList(), null)),
            ),
        )
        val markdown = MarkdownRenderer.renderIndex(
            PolicySet(listOf(PolicyPackage("p", listOf(control("C-1", rendered), control("C-2", rendered)), listOf("f.rego")))), examplesByPackage = examples,
        )
        val order = listOf("- **Missing description", "- **No framework", "- **No matching example", "- **Raw decision object", "- **Duplicate title").map { markdown.indexOf(it) }
        assertThat(order).describedAs("every bullet is present").doesNotContain(-1)
        assertThat(order).isSorted()
        assertWellSpaced(markdown)
    }

    @Test
    @org.junit.jupiter.api.DisplayName("spec §14.65: the comments bullet sits after the example-based bullets and before duplicate titles; it alone makes the section")
    fun commentsBulletPosition() {
        val blocks = listOf(io.explico.model.CommentBlock("a.rego", 1, 3), io.explico.model.CommentBlock("b/c.rego", 7, 2))
        val rules = listOf(control("C-1", rendered, description = "d", frameworks = listOf("F")), control("C-2", rendered, description = "d", frameworks = listOf("F")))
        val markdown = MarkdownRenderer.renderIndex(PolicySet(listOf(PolicyPackage("p", rules, listOf("f.rego"), unpublishedComments = blocks))))
        assertThat(markdown).contains(
            "- **Comments not published (2):** `a.rego:1` (3 lines), `b/c.rego:7` (2 lines) — move what readers need into a rule's description in its METADATA",
        )
        assertThat(markdown).contains("- **Duplicate title")
        assertThat(markdown.indexOf("- **Comments not published")).isLessThan(markdown.indexOf("- **Duplicate title"))
        assertWellSpaced(markdown)
        val alone = MarkdownRenderer.renderIndex(PolicySet(listOf(PolicyPackage("p", emptyList(), listOf("f.rego"), unpublishedComments = blocks))))
        assertThat(alone).contains("## Documentation quality").contains("- **Comments not published (2):**")
        assertWellSpaced(alone)
    }

    @Test
    fun qualityFindingsAfterTheBelow100NoteAreSpaced() {
        val markdown = index(emptyList(), control("C-1", unrendered))
        assertThat(markdown.indexOf("*Below 100%:")).isLessThan(markdown.indexOf("## Documentation quality"))
        assertWellSpaced(markdown)
    }

    @Test
    fun requirementsAndQualityTogetherAreSpaced() {
        assertWellSpaced(index(listOf("X", "Y"), control("C-1", rendered, frameworks = listOf("X", "Z"))))
    }

    @Test
    fun requirementsWithoutAnyFrameworkMatrixSitBeforeTheOverallLine() {
        val markdown = index(listOf("X"), control("C-1", rendered, description = "d"))
        assertThat(markdown).doesNotContain("Controls by framework")
        assertThat(markdown.indexOf("## Requirements coverage")).isLessThan(markdown.indexOf("*Overall rendering coverage:"))
        assertWellSpaced(markdown)
    }

    @Test
    fun aCleanIndexWithNoRequirementsHasNeitherSection() {
        val markdown = index(emptyList(), control("C-1", rendered, description = "d", frameworks = listOf("F")))
        assertThat(markdown).doesNotContain("Documentation quality").doesNotContain("Requirements coverage")
        assertWellSpaced(markdown)
    }
}
