/**
 * Spec §14.56 on hand-built domain objects (no opa): the pages a grouped control produces, the page-relative links between cards, and how the index, quality
 * findings and requirements coverage count a grouped control. The real pages are pinned by RealisticGroupByControlAcceptanceIT.
 */
package io.explico.render

import io.explico.model.Condition
import io.explico.model.Operand
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

class GroupedControlsRenderingTest {

    private fun body(file: String, vararg conditions: Condition) = RuleBody(conditions.toList(), null, null, SourceRef(file, 3), "")

    private fun rule(
        name: String, id: String?, file: String, title: String? = null, description: String? = null, frameworks: List<String> = emptyList(),
        conditions: List<Condition> = emptyList(),
    ) = RuleGroup(name, id?.let { RuleMetadata(title, description, it, frameworks) }, null, listOf(body(file, *conditions.toTypedArray())))

    private fun set(vararg packages: Pair<String, List<RuleGroup>>) = PolicySet(packages.map { (path, rules) -> PolicyPackage(path, rules, listOf("f.rego")) })

    private fun ref(pkg: String, rule: String) = Condition.RuleReference(pkg, rule, negated = false)

    private val policy = set(
        "pa" to listOf(rule("a1", "C-1", "a.rego", title = "Control one", description = "d", frameworks = listOf("F1"), conditions = listOf(ref("pc", "plain")))),
        "pb" to listOf(rule("b1", "C-1", "b.rego", conditions = listOf(ref("pa", "a1")))),
        "pc" to listOf(rule("plain", "C-2", "c.rego", title = "Two", conditions = listOf(ref("pb", "b1")))),
    )
    private val groups = ControlGroups.of(policy)

    @Nested
    @DisplayName("Pages and links")
    inner class Pages {

        @Test
        @DisplayName("a control page links a sibling in another package by bare anchor, and a card that stayed on a package page one directory up")
        fun controlPageLinks() {
            val page = MarkdownRenderer.renderControlPage(groups.groups.single(), policy, groups)
            assertThat(page).contains("# Control C-1 — Control one")
            assertThat(page).contains("[`plain`](../pc.md#c-2)")
            assertThat(page).contains("see rule [`a1`](#c-1-pa-a1)")
        }

        @Test
        @DisplayName("a package page links a grouped rule into controls/, and keeps ONE pointer however many rules it holds there")
        fun packagePageLinks() {
            val page = MarkdownRenderer.renderPackage(policy.packages.first { it.path == "pc" }, policy, groups = groups)
            assertThat(page).contains("[`b1`](controls/c-1.md#c-1-pb-b1)")
            assertThat(page).contains("## C-2 — Two")
        }

        @Test
        @DisplayName("a package holding only grouped rules shows the pointer and no card")
        fun pointerOnly() {
            val page = MarkdownRenderer.renderPackage(policy.packages.first { it.path == "pb" }, policy, groups = groups)
            assertThat(page.lines().filter { it.startsWith("## ") }).containsExactly("## C-1 — Control one")
            assertThat(page).contains("](controls/c-1.md)").contains("[`b1`](controls/c-1.md#c-1-pb-b1)").doesNotContain("*Rule `b1`")
            assertThat(anchorIds(page)).isEmpty()
        }

        @Test
        @DisplayName("two rules of one name and id: the one a link resolves to (first in policy order) owns the anchor; the other has none")
        fun noLinkToAnUnclaimedAnchor() {
            val twin = set("p" to listOf(rule("r", "C-1", "z.rego"), rule("r", "C-1", "a.rego"), rule("caller", null, "m.rego", conditions = listOf(ref("p", "r")))))
            val g = ControlGroups.of(twin)
            val page = MarkdownRenderer.renderPackage(twin.packages.single(), twin, groups = g)
            // `p.r` resolves to the FIRST rule named r (policy order), which owns the claim: linked. The second one has no anchor, so it is not linkable by name.
            assertThat(page).contains("see rule [`r`](controls/c-1.md#c-1-p-r)")
            assertThat(g.groups.single().members.count { it.anchor == null }).isEqualTo(1)
        }

        @Test
        @DisplayName("a line break in an id or title cannot end the control page's or the pointer's heading early")
        fun headingsStayOnOneLine() {
            val odd = set("pa" to listOf(rule("a", "C\n1", "a.rego", title = "Two\nlines")), "pb" to listOf(rule("b", "C\n1", "b.rego")))
            val g = ControlGroups.of(odd)
            val page = MarkdownRenderer.renderControlPage(g.groups.single(), odd, g)
            assertThat(page.lines()).contains("# Control C 1 — Two lines")
            assertThat(MarkdownRenderer.renderPackage(odd.packages.first(), odd, groups = g).lines()).contains("## C 1 — Two lines")
        }

        @Test
        @DisplayName("without groups, nothing about links or headings changes")
        fun withoutGroupsNothingChanges() {
            val page = MarkdownRenderer.renderPackage(policy.packages.first { it.path == "pc" }, policy)
            assertThat(page).contains("[`b1`](pb.md#c-1)").contains("## C-2 — Two")
        }

        private fun anchorIds(markdown: String) = Regex("<a id=\"([^\"]*)\"></a>").findAll(markdown).map { it.groupValues[1] }.toList()
    }

    @Nested
    @DisplayName("Index")
    inner class Index {

        @Test
        @DisplayName("a grouped control is one row linking to its page; the others keep theirs; a legend bullet explains the pages")
        fun oneRow() {
            val index = MarkdownRenderer.renderIndex(policy, groups = groups)
            val rows = index.lines().filter { it.startsWith("| C-") }
            assertThat(rows).hasSize(2)
            assertThat(rows.first()).startsWith("| C-1 | [Control one](controls/c-1.md#c-1) | `pa`, `pb` | `a1`, `b1` |")
            assertThat(rows.last()).contains("[Two](pc.md#c-2)")
            assertThat(index).contains("**Control pages**")
        }

        @Test
        @DisplayName("no groups: three plain rows and no legend bullet")
        fun noGroups() {
            val index = MarkdownRenderer.renderIndex(policy)
            assertThat(index.lines().count { it.startsWith("| C-") }).isEqualTo(3)
            assertThat(index).doesNotContain("Control pages")
        }

        @Test
        @DisplayName("the framework matrix lists the control once, with the union of its rules' frameworks")
        fun frameworkMatrix() {
            val index = MarkdownRenderer.renderIndex(policy, groups = groups)
            assertThat(index.lines().single { it.startsWith("| F1 |") }).isEqualTo("| F1 | [C-1](controls/c-1.md#c-1) |")
        }
    }

    @Nested
    @DisplayName("Quality and requirements")
    inner class QualityCounting {

        @Test
        @DisplayName("a grouped control with a description and a framework on SOME rule is complete; without grouping its other rule is reported")
        fun oneRuleIsEnough() {
            val grouped = Quality.findingsOf(policy, groups)
            assertThat(grouped.missingDescription.map { it.label }).containsExactly("C-2")
            assertThat(grouped.noFramework.map { it.label }).containsExactly("C-2")
            assertThat(Quality.findingsOf(policy).missingDescription.map { it.label }).containsExactly("C-1", "C-2")
        }

        @Test
        @DisplayName("a grouped control that is incomplete on EVERY rule is reported once, linked to its page")
        fun reportedOnce() {
            val bare = set("pa" to listOf(rule("a", "C-1", "a.rego")), "pb" to listOf(rule("b", "C-1", "b.rego")))
            val findings = Quality.findingsOf(bare, ControlGroups.of(bare))
            assertThat(findings.missingDescription.map { it.link() }).containsExactly("[C-1](controls/c-1.md#c-1)")
            assertThat(findings.noFramework).hasSize(1)
        }

        @Test
        @DisplayName("two rules of one control with the same title are not a duplicate title")
        fun sameTitleWithinAControl() {
            val twin = set("pa" to listOf(rule("a", "C-1", "a.rego", title = "Same")), "pb" to listOf(rule("b", "C-1", "b.rego", title = "Same")))
            assertThat(Quality.findingsOf(twin).duplicateTitles).hasSize(1)
            assertThat(Quality.findingsOf(twin, ControlGroups.of(twin)).duplicateTitles).isEmpty()
        }

        @Test
        @DisplayName("a requirement is covered by the control if any of its rules names it, and the control is listed once")
        fun requirementsCoverage() {
            val coverage = Quality.coverageOf(policy, listOf("F1"), groups)
            assertThat(coverage.items.single().controls.map { it.link() }).containsExactly("[C-1](controls/c-1.md#c-1)")
        }
    }
}
