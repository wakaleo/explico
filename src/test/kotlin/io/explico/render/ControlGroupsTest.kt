/**
 * Spec §14.56: which control ids are grouped, what their pages and anchors are called, and in what order their rules appear. Hand-built domain
 * objects -- no opa; the real pages are pinned by RealisticGroupByControlAcceptanceIT.
 */
package io.explico.render

import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RuleBody
import io.explico.model.RuleGroup
import io.explico.model.RuleMetadata
import io.explico.model.SourceRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ControlGroupsTest {

    private fun body(file: String, row: Int = 1) = RuleBody(emptyList(), null, null, SourceRef(file, row), "")

    private fun rule(name: String, id: String?, file: String = "$name.rego", row: Int = 1, title: String? = null, split: Boolean = false) =
        RuleGroup(name, id?.let { RuleMetadata(title, null, it, emptyList()) }, null, if (split) emptyList() else listOf(body(file, row)), isSplitDefaultEntry = split)

    private fun set(vararg packages: Pair<String, List<RuleGroup>>) = PolicySet(packages.map { (path, rules) -> PolicyPackage(path, rules, listOf("f.rego")) })

    @Test
    @DisplayName("only an id used by two or more rules is grouped; a single-rule id and an id-less rule are not")
    fun onlySharedIdsAreGrouped() {
        val groups = ControlGroups.of(set("p" to listOf(rule("a", "C-1"), rule("b", "C-1"), rule("c", "C-2"), rule("d", null))))
        assertThat(groups.groups.map { it.controlId }).containsExactly("C-1")
        assertThat(groups.placementOf("p", 0)?.group?.controlId).isEqualTo("C-1")
        assertThat(groups.placementOf("p", 2)).isNull()
        assertThat(groups.placementOf("p", 3)).isNull()
    }

    @Test
    @DisplayName("no shared id: no groups at all")
    fun nothingShared() {
        assertThat(ControlGroups.of(set("p" to listOf(rule("a", "C-1"), rule("b", "C-2")))).groups).isEmpty()
        assertThat(ControlGroups.of(PolicySet(emptyList())).groups).isEmpty()
    }

    @Test
    @DisplayName("members are ordered by source path, then row, then package and name -- not by the order the packages were read")
    fun membersInSourceOrder() {
        val groups = ControlGroups.of(
            set(
                "z" to listOf(rule("late", "C-1", file = "a.rego", row = 9)),
                "a" to listOf(rule("second", "C-1", file = "b.rego"), rule("early", "C-1", file = "a.rego", row = 2)),
            ),
        )
        assertThat(groups.groups.single().members.map { it.rule.name }).containsExactly("early", "late", "second")
    }

    @Test
    @DisplayName("the page is named by the slug of the id; the title is the first TITLED rule's by source path")
    fun pageAndTitle() {
        val group = ControlGroups.of(set("p" to listOf(rule("b", "SEC-28", file = "b.rego", title = "From b"), rule("a", "SEC-28", file = "a.rego")))).groups.single()
        assertThat(group.page).isEqualTo("controls/sec-28.md")
        assertThat(group.anchor).isEqualTo("sec-28")
        assertThat(group.title).isEqualTo("From b")
    }

    @Test
    @DisplayName("no rule has a title: the control has none")
    fun noTitle() {
        assertThat(ControlGroups.of(set("p" to listOf(rule("a", "C-1"), rule("b", "C-1")))).groups.single().title).isNull()
    }

    @Test
    @DisplayName("ids that slug alike get distinct files in sorted-id order, whichever was read first; groups come out in id order")
    fun slugCollisionsAreDisambiguatedInIdOrder() {
        val groups = ControlGroups.of(set("p" to listOf(rule("c", "x_1"), rule("d", "x_1"), rule("a", "X-1"), rule("b", "X-1"))))
        assertThat(groups.groups.map { it.controlId to it.page }).containsExactly("X-1" to "controls/x-1.md", "x_1" to "controls/x-1-2.md")
    }

    @Test
    @DisplayName("a literal id that equals a disambiguated name does not steal it: every page name is distinct")
    fun disambiguationNeverCollides() {
        val groups = ControlGroups.of(set("p" to listOf(rule("a", "X-1"), rule("b", "X-1"), rule("c", "x_1"), rule("d", "x_1"), rule("e", "x-1-2"), rule("f", "x-1-2"))))
        assertThat(groups.groups.map { it.page }).doesNotHaveDuplicates().hasSize(3)
    }

    @Test
    @DisplayName("an id of only punctuation still gets a page (slug would be empty) and an anchor")
    fun punctuationOnlyId() {
        val group = ControlGroups.of(set("p" to listOf(rule("a", "§§"), rule("b", "§§")))).groups.single()
        assertThat(group.page).isEqualTo("controls/control.md")
        assertThat(group.anchor).isEqualTo("control")
    }

    @Test
    @DisplayName("an id with markdown specials, a pipe or a slash cannot escape the controls/ directory or the page name")
    fun hostileIdsStayInsideTheDirectory() {
        val group = ControlGroups.of(set("p" to listOf(rule("a", "../a|b/c"), rule("b", "../a|b/c")))).groups.single()
        assertThat(group.page).isEqualTo("controls/a-b-c.md")
        assertThat(group.page.removePrefix("controls/")).doesNotContain("/", "|", "..")
    }

    @Test
    @DisplayName("an id whose slug is a Windows device name (con, nul, com1, lpt9 ...) gets a page that is not one: con-control.md; the anchor keeps the slug; a later id cannot take the name")
    fun windowsDevices() {
        val groups = ControlGroups.of(set("p" to listOf(rule("a", "CON"), rule("b", "CON"), rule("c", "con-control"), rule("d", "con-control"), rule("e", "COM1"), rule("f", "COM1"), rule("g", "com10"), rule("h", "com10"))))
        assertThat(groups.groups.map { it.controlId to it.page }).containsExactly(
            "COM1" to "controls/com1-control.md", "CON" to "controls/con-control.md", "com10" to "controls/com10.md", "con-control" to "controls/con-control-2.md",
        )
        assertThat(groups.groups.first { it.controlId == "CON" }.anchor).isEqualTo("con")
    }

    @Test
    @DisplayName("each member's anchor is <id>-<package>-<rule>, slugged")
    fun memberAnchors() {
        val group = ControlGroups.of(set("grp_a.b" to listOf(rule("my_rule", "SEC-28")), "grp_c" to listOf(rule("other", "SEC-28")))).groups.single()
        assertThat(group.members.map { it.anchor }).containsExactly("sec-28-grp-a-b-my-rule", "sec-28-grp-c-other")
    }

    @Test
    @DisplayName("two rules of one name and one id in one package: the FIRST (policy order) claims the anchor, the other has none -- anchors stay unique")
    fun duplicateAnchorsAreClaimedOnce() {
        val group = ControlGroups.of(set("p" to listOf(rule("r", "C-1", file = "z.rego"), rule("r", "C-1", file = "a.rego")))).groups.single()
        // Page order is by source path (a.rego first), but the claim follows policy order: index 0 (z.rego) owns the anchor a link to `p.r` resolves to.
        assertThat(group.members.map { it.ruleIndex to it.anchor }).containsExactly(1 to null, 0 to "c-1-p-r")
    }

    @Test
    @DisplayName("a split-default entry is never grouped")
    fun splitDefaultIsNeverGrouped() {
        val groups = ControlGroups.of(set("p" to listOf(rule("a", "C-1"), rule("a", "C-1", split = true))))
        assertThat(groups.groups).isEmpty()
    }
}
