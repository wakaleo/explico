/**
 * Spec §14.68: which rules are used by ANOTHER rule, decided from opa's own AST (`RuleReferences`), so a use that the model only keeps as verbatim source -- inside a
 * comprehension, an `every`, a `with` -- still counts. Driven from real `opa parse` captures (`no-control-id-nc.json` / `-nc2.json` are the acceptance fixture,
 * `rule-references.json` / `-2.json` hold the edge cases); no `opa` needed. The default is "referenced": an unknown state must never produce a finding.
 */
package io.explico.parse

import io.explico.model.PolicySet
import io.explico.opa.OpaModule
import io.explico.opa.opaJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class AstMapperRuleReferencesTest {

    private fun module(capture: String) = opaJson.decodeFromString(OpaModule.serializer(), Files.readString(Path.of("src/test/resources/ast/$capture.json")))

    private fun mapped(vararg captures: Pair<String, String>): PolicySet = AstMapper.mapPolicySet(captures.map { (name, file) -> ParsedFile(file, module(name)) })

    private fun referenced(set: PolicySet, pkg: String): Map<String, Boolean> =
        set.packages.single { it.path == pkg }.rules.filter { !it.isSplitDefaultEntry }.associate { it.name to it.isReferenced }

    private val acceptance by lazy { mapped("no-control-id-nc" to "nc.rego", "no-control-id-nc2" to "nc2.rego") }
    private val edge by lazy { mapped("rule-references" to "re.rego", "rule-references-2" to "re2.rego") }

    @Test
    @DisplayName("every shape of use in the acceptance fixture counts: a plain reference, a constant operand, an else branch, an every body, a comprehension, a with, an import")
    fun everyShapeOfUse() {
        val nc = referenced(acceptance, "nc")
        for (used in listOf("helper_plain", "max_items", "else_helper", "every_helper", "comp_helper", "helper_with")) assertThat(nc.getValue(used)).describedAs(used).isTrue()
        assertThat(referenced(acceptance, "nc2").getValue("shared_rule")).describedAs("used through an import from nc").isTrue()
    }

    @Test
    @DisplayName("what nothing uses is not referenced: the entry points, the unused function, constant and default-only rule, in both packages")
    fun unusedRules() {
        val nc = referenced(acceptance, "nc")
        for (unused in listOf("deny", "allow_other", "entry_point", "unused_fn", "unused_const", "flag_only", "nc_one", "nc_two", "nc_three")) assertThat(nc.getValue(unused)).describedAs(unused).isFalse()
        assertThat(referenced(acceptance, "nc2").getValue("deny")).isFalse()
    }

    @Test
    @DisplayName("a rule in one package is not used just because a same-named rule is used in another: nc2.deny and nc.deny are told apart")
    fun sameNameInTwoPackages() {
        val set = mapped("rule-references" to "re.rego", "rule-references-2" to "re2.rego")
        assertThat(referenced(set, "re2").getValue("unused_two")).isFalse()
        assertThat(referenced(set, "re2").getValue("target_a")).isTrue()
    }

    @Test
    @DisplayName("a reference through a data.<package>.<rule> path and through a package alias (import data.re2 as other) counts")
    fun fullPathAndAlias() {
        val re2 = referenced(edge, "re2")
        assertThat(re2.getValue("target_a")).describedAs("data.re2.target_a").isTrue()
        assertThat(re2.getValue("target_b")).describedAs("other.target_b").isTrue()
    }

    @Test
    @DisplayName("a reference from a function body, from a partial-rule operand and from a with's value counts (opa does not allow a variable in a default, so no default can use a rule)")
    fun otherPlacesOfUse() {
        val re = referenced(edge, "re")
        assertThat(re.getValue("used_in_function")).isTrue()
        assertThat(re.getValue("partial_used")).isTrue()
        assertThat(re.getValue("with_target")).describedAs("only inside `with data.x as with_target`").isTrue()
    }

    @Test
    @DisplayName("a rule used only by itself (an else branch naming its own rule) is NOT used by another rule")
    fun selfReferenceIsIgnored() {
        assertThat(referenced(edge, "re").getValue("selfish")).isFalse()
    }

    @Test
    @DisplayName("CHALLENGE: a local variable named like a rule counts as a use of it -- the safe direction: it can only hide a finding, never invent one")
    fun shadowingErrsTowardUsed() {
        assertThat(referenced(edge, "re").getValue("shadowed")).isTrue()
    }

    @Test
    @DisplayName("unreferenced ordinary rules stay unreferenced: a rule and a function nothing calls")
    fun unreferencedStay() {
        val re = referenced(edge, "re")
        assertThat(re.getValue("unused_one")).isFalse()
        assertThat(re.getValue("fn_user")).isFalse()
    }

    @Test
    @DisplayName("the model default is 'referenced' so an unknown state never produces a finding")
    fun defaultIsReferenced() {
        assertThat(io.explico.model.RuleGroup("r", null, null, emptyList()).isReferenced).isTrue()
    }

    @Test
    @DisplayName("OPA unit tests (rules named test_* or todo_test_*) are not users: a rule only a test refers to is NOT referenced, so the real entry point is not hidden by its tests")
    fun testRulesAreNotUsers() {
        val tp = referenced(mapped("test-rules-p" to "p.rego", "test-rules-p-test" to "p_test.rego"), "tp")
        assertThat(tp.getValue("deny")).describedAs("only a test_ rule refers to it").isFalse()
        assertThat(tp.getValue("used_only_by_a_test")).describedAs("only a todo_test_ rule refers to it").isFalse()
    }
}
