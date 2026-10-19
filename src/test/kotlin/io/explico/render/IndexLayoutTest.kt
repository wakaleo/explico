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
