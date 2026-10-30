/**
 * Spec §14.71: the "Values shown as source" finding -- the unrendered operands the card footer counts, grouped by their exact source text. Hand-built domain objects; no opa.
 * The real index text is pinned by RealisticValuesShownAsSourceAcceptanceIT.
 */
package io.explico.render

import io.explico.model.Condition
import io.explico.model.ElseBranch
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
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class QualityValuesShownAsSourceTest {

    private val path = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("a")))

    /** A rendered condition holding the value [source] on [row] (null = no known place). */
    private fun holding(source: String, row: Int?) = Condition.Comparison(Operand.Unrendered(source, row), Operator.EQ, Operand.Literal("1"))

    private fun body(file: String, vararg conditions: Condition, elseBranches: List<ElseBranch> = emptyList()) =
        RuleBody(conditions.toList(), null, null, SourceRef(file, 1), "", elseBranches = elseBranches)

    private fun rule(name: String, id: String?, vararg bodies: RuleBody, split: Boolean = false, parameters: List<String> = emptyList()) =
        RuleGroup(name, id?.let { RuleMetadata("T $name", "d", it, listOf("F")) }, null, if (split) emptyList() else bodies.toList(), parameters, isSplitDefaultEntry = split)

    private fun set(vararg rules: RuleGroup) = PolicySet(listOf(PolicyPackage("p", rules.toList(), listOf("f.rego"))))

    private fun found(set: PolicySet, groups: ControlGroups? = null) = Quality.findingsOf(set, groups).valuesShownAsSource

    private fun summary(set: PolicySet, groups: ControlGroups? = null) = found(set, groups).map { Triple(it.source, it.count, it.cards.map { c -> c.label }) }

    @Test
    @DisplayName("values are grouped by their exact text; the order is count descending, then text ascending; cards are in index order, each once")
    fun groupingAndOrder() {
        val s = set(
            rule("a", "C-2", body("a.rego", holding("b", 1), holding("a", 2), holding("a", 3))),
            rule("b", "C-1", body("b.rego", holding("a", 7), holding("c", 8))),
            rule("helper", null, body("h.rego", holding("c", 1))),
        )
        assertThat(summary(s)).containsExactly(
            Triple("a", 3, listOf("C-1", "C-2")),
            Triple("c", 2, listOf("C-1", "helper")),
            Triple("b", 1, listOf("C-2")),
        )
    }

    @Test
    @DisplayName("grouping is by EXACT text: differences in whitespace make distinct values")
    fun exactText() {
        val s = set(rule("a", "C-1", body("a.rego", holding("f(x)", 1), holding("f( x )", 2), holding("f(x)\n", 3))))
        assertThat(found(s).map { it.source }).containsExactlyInAnyOrder("f(x)", "f( x )", "f(x)\n")
    }

    @Test
    @DisplayName("nothing to show: empty, and the findings stay empty; a value makes them non-empty so the section appears for it alone")
    fun none() {
        assertThat(Quality.findingsOf(set(rule("a", "C-1", body("a.rego", Condition.Truthy(path, false)))))).matches { it.isEmpty() }
        assertThat(Quality.findingsOf(set(rule("a", "C-1", body("a.rego", holding("x", 1))))).isEmpty()).isFalse()
    }

    @Test
    @DisplayName("places: the holding body's file and the value's row, ascending within a card; a value with no row counts and names its card but has no place")
    fun places() {
        val s = set(rule("a", "C-1", body("a.rego", holding("v", 9), holding("v", 3), holding("v", null))))
        val item = found(s).single()
        assertThat(item.count).isEqualTo(3)
        assertThat(item.cards.map { it.label }).containsExactly("C-1")
        assertThat(item.places.map { it.file to it.row }).containsExactly("a.rego" to 3, "a.rego" to 9)
    }

    @Test
    @DisplayName("two values on ONE row are two occurrences and one place")
    fun sameRowTwice() {
        val s = set(rule("a", "C-1", body("a.rego", holding("v", 5), holding("v", 5))))
        val item = found(s).single()
        assertThat(item.count).isEqualTo(2)
        assertThat(item.places.map { it.row }).containsExactly(5)
    }

    @Test
    @DisplayName("a value nested in a modelled builtin or a function call counts, exactly as the card footer counts it")
    fun nested() {
        val nested = Condition.Comparison(Operand.BuiltinCall("count", listOf(Operand.Unrendered("deep", 4))), Operator.GT, Operand.Literal("0"))
        val viaCall = Condition.Comparison(Operand.FunctionCall("p", "f", listOf(Operand.Unrendered("arg", 5))), Operator.EQ, Operand.Literal("1"))
        val conditions = listOf<Condition>(nested, viaCall)
        val s = set(rule("a", "C-1", body("a.rego", *conditions.toTypedArray())))
        assertThat(found(s).map { it.source }).containsExactlyInAnyOrder("deep", "arg")
        assertThat(found(s).sumOf { it.count }).isEqualTo(Coverage.unrenderedOperandCount(conditions))
    }

    @Test
    @DisplayName("a value in an else branch is a place in the BRANCH's own file; a split-default entry contributes nothing; a function is a card like any other")
    fun elseBranchesAndOthers() {
        val branch = ElseBranch(listOf(holding("e", 8)), Operand.Literal("1"), SourceRef("z.rego", 7))
        val s = set(
            rule("a", "C-1", body("m.rego", holding("e", 2), elseBranches = listOf(branch))),
            rule("d", "C-9", split = true),
            rule("fn", null, body("f.rego", holding("e", 1)), parameters = listOf("x")),
        )
        val item = found(s).single()
        assertThat(item.count).isEqualTo(3)
        assertThat(item.places.filter { it.card.label == "C-1" }.map { it.file to it.row }).containsExactly("m.rego" to 2, "z.rego" to 8)
    }

    @Test
    @DisplayName("a grouped control is ONE card gathering the values of all its rules")
    fun grouped() {
        val s = set(rule("a", "C-G", body("a.rego", holding("v", 1))), rule("b", "C-G", body("b.rego", holding("v", 2))))
        val item = found(s, ControlGroups.of(s)).single()
        assertThat(item.count).isEqualTo(2)
        assertThat(item.cards.map { it.label }).containsExactly("C-G")
        assertThat(item.places.map { it.file to it.row }).containsExactly("a.rego" to 1, "b.rego" to 2)
    }

    @Test
    @DisplayName("it is not a gap, whatever is required")
    fun notAGap() {
        val findings = Quality.findingsOf(set(rule("a", "C-1", body("a.rego", holding("v", 1)))))
        assertThat(Quality.gapDescriptions(findings, null, RequiredSet.of(RequiredSet.ALL.filter { it != "fixture" && it != "verdict" }, examplesSupplied = false))).isEmpty()
    }

    @Nested
    @DisplayName("Rendering and the finding line")
    inner class Rendering {

        private fun manyValues(n: Int) = set(rule("a", "C-1", body("a.rego", *(1..n).map { holding("v%02d".format(it), it) as Condition }.toTypedArray())))

        private fun lines(set: PolicySet, links: SourceLinks? = null): List<String> {
            val index = MarkdownRenderer.renderIndex(set, sourceLinks = links).lines()
            val start = index.indexOfFirst { it.startsWith("- **Values shown as source") }
            return if (start < 0) emptyList() else listOf(index[start]) + index.drop(start + 1).takeWhile { it.startsWith("  - ") }
        }

        @Test
        @DisplayName("the cap: 25, 26 and 27 distinct values list at most 25 and say how many more; the head counts all of them")
        fun cap() {
            assertThat(lines(manyValues(25))).hasSize(1 + 25)
            val twentySix = lines(manyValues(26))
            assertThat(twentySix).hasSize(1 + 25 + 1)
            assertThat(twentySix.last()).isEqualTo("  - … and 1 more distinct value (all are in explico.json)")
            assertThat(lines(manyValues(27)).first()).startsWith("- **Values shown as source (27):**")
            assertThat(lines(manyValues(27)).last()).isEqualTo("  - … and 2 more distinct values (all are in explico.json)")
        }

        @Test
        @DisplayName("a source text with backticks, a pipe or a line break stays inside one code span on one line; a blank source says so instead of an empty span")
        fun awkwardSources() {
            val s = set(rule("a", "C-1", body("a.rego", holding("f(`x`) | g", 1), holding("two\nlines", 2), holding("", 3))))
            val shown = lines(s).joinToString("\n")
            assertThat(shown).contains("``f(`x`) | g`` ×1")
            assertThat(shown).contains("`two lines` ×1")
            assertThat(shown).contains("(no source text) ×1")
            assertThat(lines(s).drop(1)).allSatisfy { assertThat(it).startsWith("  - ").doesNotContain("\n") }
        }

        @Test
        @DisplayName("with a source template each place is a link; a card with no known place has no parentheses")
        fun links() {
            val s = set(rule("a", "C-1", body("a.rego", holding("v", 3))), rule("b", "C-2", body("b.rego", holding("v", null))))
            assertThat(lines(s, SourceLinks.of("https://h/{file}#L{line}"))).contains(
                "  - `v` ×2 in [C-1](p.md#c-1) ([`a.rego:3`](https://h/a.rego#L3)), [C-2](p.md#c-2)",
            )
        }

        @Test
        @DisplayName("the finding line: 1 is singular, 2 and 11 plural; the number is the total occurrences, not the distinct values")
        fun findingLine() {
            fun line(n: Int) = Quality.findingLines(Quality.Findings(emptyList(), emptyList(), emptyList(), valuesShownAsSource = listOf(Quality.ValueShownAsSource("v", n, emptyList(), emptyList()))))
            assertThat(line(1)).containsExactly("1 value is shown as source inside a rendered condition")
            assertThat(line(2)).containsExactly("2 values are shown as source inside rendered conditions")
            assertThat(line(11)).containsExactly("11 values are shown as source inside rendered conditions")
        }

        @Test
        @DisplayName("the finding line comes right after the shown-as-source line and before the shared titles")
        fun findingLineOrder() {
            val f = Quality.Findings(
                emptyList(), emptyList(), listOf("T" to listOf(Quality.ControlRef("C-1", "p", "c-1"))),
                shownAsSource = listOf(Quality.ShownAsSource("every", 2, emptyList())),
                valuesShownAsSource = listOf(Quality.ValueShownAsSource("v", 1, emptyList(), emptyList())),
            )
            assertThat(Quality.findingLines(f)).containsExactly(
                "2 conditions are shown as source", "1 value is shown as source inside a rendered condition", "1 title is shared by several controls",
            )
        }
    }
}
