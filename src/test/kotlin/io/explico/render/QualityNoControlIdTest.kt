/**
 * Spec §14.68: id-less ENTRY POINTS -- a rule with no control id, at least one body, not a function, that no other rule uses. Hand-built domain objects; no opa. The
 * reference decision itself is pinned by AstMapperRuleReferencesTest; the real index is pinned by RealisticNoControlIdAcceptanceIT.
 */
package io.explico.render

import io.explico.model.ConstantValue
import io.explico.model.DefaultValue
import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RuleBody
import io.explico.model.RuleGroup
import io.explico.model.RuleMetadata
import io.explico.model.SourceRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class QualityNoControlIdTest {

    private val body = RuleBody(emptyList(), null, null, SourceRef("f.rego", 1), "")

    private fun rule(
        name: String, id: String? = null, referenced: Boolean = false, parameters: List<String> = emptyList(), bodies: List<RuleBody> = listOf(body), split: Boolean = false,
        constant: ConstantValue? = null,
    ) = RuleGroup(
        name, id?.let { RuleMetadata("T", "d", it, listOf("F")) }, null, bodies, parameters, isSplitDefaultEntry = split, constantValue = constant, isReferenced = referenced,
    )

    private fun set(vararg packages: Pair<String, List<RuleGroup>>) = PolicySet(packages.map { (path, rules) -> PolicyPackage(path, rules, listOf("f.rego")) })

    private fun labels(set: PolicySet, groups: ControlGroups? = null) = Quality.findingsOf(set, groups).noControlId.map { it.label }

    @Test
    @DisplayName("an id-less rule nothing uses is listed as <package>.<rule>; a used one, and one with an id, are not")
    fun listed() {
        val s = set("p" to listOf(rule("entry"), rule("helper", referenced = true), rule("control", id = "C-1")))
        assertThat(labels(s)).containsExactly("p.entry")
    }

    @Test
    @DisplayName("a function, a constant and a default-only rule (no body) are never entry points, and a split-default entry is not a card of its own")
    fun notEntryPoints() {
        val s = set(
            "p" to listOf(
                rule("fn", parameters = listOf("x")),
                rule("const", bodies = emptyList(), constant = ConstantValue("5", SourceRef("f.rego", 1))),
                rule("only_default", bodies = emptyList()),
                rule("entry", split = true, bodies = emptyList()),
            ),
        )
        assertThat(labels(s)).isEmpty()
    }

    @Test
    @DisplayName("the default 'referenced' (a hand-built rule) never produces a finding")
    fun defaultNeverFinds() {
        val handBuilt = RuleGroup("r", null, null, listOf(body))
        assertThat(labels(set("p" to listOf(handBuilt)))).isEmpty()
    }

    @Test
    @DisplayName("an OPA unit test (test_* or todo_test_*) is never an entry point, even unreferenced")
    fun testRulesAreNotEntryPoints() {
        assertThat(labels(set("p_test" to listOf(rule("test_x"), rule("todo_test_y"), rule("tests_are_not_prefixed"))))).containsExactly("p_test.tests_are_not_prefixed")
    }

    @Test
    @DisplayName("same-named rules in two packages are both listed, told apart by their package, in index order (package, then rule name)")
    fun orderAndLabels() {
        val s = set("nc2" to listOf(rule("deny")), "nc" to listOf(rule("deny"), rule("allow_other")))
        assertThat(labels(s)).containsExactly("nc.allow_other", "nc.deny", "nc2.deny")
    }

    @Test
    @DisplayName("it makes the findings non-empty, so the section appears for it alone, and it is not a gap")
    fun sectionAndGaps() {
        val findings = Quality.findingsOf(set("p" to listOf(rule("entry"))))
        assertThat(findings.isEmpty()).isFalse()
        assertThat(Quality.gapDescriptions(findings, null, RequiredSet.of(RequiredSet.ALL.filter { it != "fixture" && it != "verdict" }, examplesSupplied = false))).isEmpty()
    }

    @Test
    @DisplayName("the card keeps its anchor: a link when it claimed one, the bare label when a same-named card took it")
    fun anchors() {
        val s = set("p" to listOf(rule("dup"), rule("dup")))
        val refs = Quality.findingsOf(s).noControlId
        assertThat(refs.map { it.label }).containsExactly("p.dup", "p.dup")
        assertThat(refs.count { it.link().startsWith("[") }).isEqualTo(1)
    }

    @Test
    @DisplayName("the finding line follows the framework line: one rule is singular, more are plural")
    fun findingLine() {
        fun refs(n: Int) = (1..n).map { Quality.ControlRef("p.r$it", "p", "p-r$it") }
        val one = Quality.Findings(emptyList(), refs(1), emptyList(), noControlId = refs(1))
        assertThat(Quality.findingLines(one)).containsExactly("1 control names no framework", "1 rule has no control id and is not used by another rule")
        val many = Quality.Findings(emptyList(), refs(2), emptyList(), noControlId = refs(3), noMatchingExample = refs(1))
        assertThat(Quality.findingLines(many)).containsExactly(
            "2 controls name no framework", "3 rules have no control id and are not used by another rule", "1 control has no matching example",
        )
    }
}
