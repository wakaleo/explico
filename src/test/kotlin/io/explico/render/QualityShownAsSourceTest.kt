/**
 * Spec §14.66: the "shown as source" finding -- condition-level fallbacks grouped by reason, over every card. Hand-built domain objects; no opa. The real index text
 * is pinned by RealisticShownAsSourceAcceptanceIT.
 */
package io.explico.render

import io.explico.model.Condition
import io.explico.model.ElseBranch
import io.explico.model.Operand
import io.explico.model.PathSegment
import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RuleBody
import io.explico.model.RuleGroup
import io.explico.model.RuleMetadata
import io.explico.model.SourceRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class QualityShownAsSourceTest {

    private fun fallback(reason: String) = Condition.Unrendered("src", reason)
    private val rendered = Condition.Truthy(Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("a"))), false)

    private fun body(vararg conditions: Condition, elseBranches: List<ElseBranch> = emptyList()) =
        RuleBody(conditions.toList(), null, null, SourceRef("f.rego", 1), "", elseBranches = elseBranches)

    private fun rule(name: String, id: String?, vararg bodies: RuleBody, split: Boolean = false) =
        RuleGroup(name, id?.let { RuleMetadata("T $name", "d", it, listOf("F")) }, null, if (split) emptyList() else bodies.toList(), isSplitDefaultEntry = split)

    private fun set(vararg rules: RuleGroup, path: String = "p") = PolicySet(listOf(PolicyPackage(path, rules.toList(), listOf("f.rego"))))

    private fun shown(set: PolicySet, groups: ControlGroups? = null) = Quality.findingsOf(set, groups).shownAsSource

    private fun summary(set: PolicySet, groups: ControlGroups? = null) = shown(set, groups).map { Triple(it.reason, it.count, it.cards.map { c -> c.label }) }

    @Test
    @DisplayName("the same reason twice in one card is K=2 with ONE card; reasons come out sorted by name; a fully rendered rule is never listed")
    fun countsAndOrder() {
        val s = set(
            rule("a", "C-1", body(fallback("with-override"), fallback("every"), fallback("every"))),
            rule("b", "C-2", body(rendered)),
            rule("c", "C-3", body(fallback("every"))),
        )
        assertThat(summary(s)).containsExactly(
            Triple("every", 3, listOf("C-1", "C-3")),
            Triple("with-override", 1, listOf("C-1")),
        )
    }

    @Test
    @DisplayName("nothing to show: empty, and the findings stay empty")
    fun none() {
        val s = set(rule("a", "C-1", body(rendered)))
        assertThat(shown(s)).isEmpty()
        assertThat(Quality.findingsOf(s).isEmpty()).isTrue()
    }

    @Test
    @DisplayName("any fallback makes the findings non-empty, so the section appears for it alone")
    fun makesTheSectionAppear() {
        assertThat(Quality.findingsOf(set(rule("a", "C-1", body(fallback("unclassified"))))).isEmpty()).isFalse()
    }

    @Test
    @DisplayName("a fallback in a later branch of an else chain counts, like it does on the card and in the coverage")
    fun elseBranches() {
        val chain = body(rendered, elseBranches = listOf(ElseBranch(listOf(fallback("function-call")), Operand.Literal("1"), SourceRef("f.rego", 3))))
        assertThat(summary(set(rule("a", "C-1", chain)))).containsExactly(Triple("function-call", 1, listOf("C-1")))
    }

    @Test
    @DisplayName("an id-less helper rule is a card too, labelled by its rule name, after the controls (index order); a split-default entry contributes nothing")
    fun helpersAndSplitDefaults() {
        val s = set(
            rule("zeta", null, body(fallback("function-call"))),
            rule("a", "C-9", body(fallback("function-call"))),
            rule("a", "C-9", split = true),
        )
        assertThat(summary(s)).containsExactly(Triple("function-call", 2, listOf("C-9", "zeta")))
    }

    @Test
    @DisplayName("a rule with no bodies (a constant or a default) has nothing to show")
    fun noBodies() {
        assertThat(shown(set(rule("a", "C-1")))).isEmpty()
    }

    @Test
    @DisplayName("CHALLENGE: two id-less rules of one name: both are counted, and the one that lost the anchor claim keeps its bare label (no link to a missing anchor)")
    fun lostAnchor() {
        val s = set(rule("dup", null, body(fallback("every"))), rule("dup", null, body(fallback("every"))))
        val cards = shown(s).single().cards
        assertThat(cards.map { it.label }).containsExactly("dup", "dup")
        assertThat(cards.map { it.anchorId }.count { it == null }).isEqualTo(1)
        assertThat(cards.count { it.link().startsWith("[") }).isEqualTo(1)
    }

    @Test
    @DisplayName("CHALLENGE: a grouped control is ONE card: K counts the fallbacks of every member rule, and the card links to the control's page")
    fun grouped() {
        val s = set(rule("a", "C-G", body(fallback("every"))), rule("b", "C-G", body(fallback("every"), fallback("with-override"))))
        val found = shown(s, ControlGroups.of(s))
        assertThat(found.map { Triple(it.reason, it.count, it.cards.map { c -> c.label }) }).containsExactly(
            Triple("every", 2, listOf("C-G")), Triple("with-override", 1, listOf("C-G")),
        )
        assertThat(found.first().cards.single().link()).isEqualTo("[C-G](controls/c-g.md#c-g)")
    }
}
