/**
 * Real-world benchmark promotions, from real `opa parse` captures (no opa needed):
 * 1. `import data.lib.pkg.is_ok` imports a FUNCTION or RULE by name, so a bare `is_ok(x)` / `flag` / `limit` resolves to it (Gatekeeper's
 *    `import data.lib.exempt_container.is_exempt` was ~100 unresolved calls);
 * 2. a body that is only the literal `true` has no condition (it tests nothing), while `false` stays a visible fallback.
 */
package io.explico.parse

import io.explico.model.Condition
import io.explico.model.Operand
import io.explico.opa.OpaModule
import io.explico.opa.opaJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class AstMapperImportedMemberTest {

    private fun module(name: String) = opaJson.decodeFromString(OpaModule.serializer(), Files.readString(Path.of("src/test/resources/ast/$name.json")))

    private val policySet by lazy {
        AstMapper.mapPolicySet(listOf(ParsedFile("lib.rego", module("imported-lib")), ParsedFile("user.rego", module("imported-user"))))
    }

    private fun conditions(rule: String): List<Condition> =
        policySet.packages.single { it.path == "imp" }.rules.single { it.name == rule }.bodies.single().conditions

    @Test
    @DisplayName("a function imported by name resolves, negated")
    fun importedFunctionResolvesNegated() {
        val call = conditions("not_ok").single() as Condition.FunctionCall
        assertThat(call.packagePath).isEqualTo("lib.helpers")
        assertThat(call.functionName).isEqualTo("is_ok")
        assertThat(call.negated).isTrue()
    }

    @Test
    @DisplayName("an imported function called through its `as` alias resolves to the real function, not the alias")
    fun aliasedImportedFunctionResolves() {
        val call = conditions("aliased").single() as Condition.FunctionCall
        assertThat(call.functionName).isEqualTo("is_ok")
        assertThat(call.negated).isFalse()
    }

    @Test
    @DisplayName("a rule imported by name resolves as a rule reference")
    fun importedRuleResolvesAsReference() {
        val ref = conditions("flagged").single() as Condition.RuleReference
        assertThat(ref.packagePath).isEqualTo("lib.helpers")
        assertThat(ref.ruleName).isEqualTo("flag")
        assertThat(ref.negated).isTrue()
    }

    @Test
    @DisplayName("a complete rule imported by name resolves as a rule value in operand position")
    fun importedRuleValueResolvesInOperandPosition() {
        val comparison = conditions("over_limit").single() as Condition.Comparison
        val left = comparison.left as Operand.BuiltinCall
        assertThat(left.name).isEqualTo("count")
        val right = comparison.right as Operand.RuleValueReference
        assertThat(right.packagePath).isEqualTo("lib.helpers")
        assertThat(right.ruleName).isEqualTo("limit")
    }

    @Test
    @DisplayName("a body that is only the literal `true` has no conditions")
    fun literalTrueBodyHasNoConditions() {
        assertThat(conditions("constant_fn")).isEmpty()
    }

    @Test
    @DisplayName("CHALLENGE: a literal `false` is NOT dropped -- it makes the body unsatisfiable, so it stays a visible fallback")
    fun literalFalseIsNotDropped() {
        assertThat(conditions("never")).hasSize(1)
        assertThat(conditions("never").single()).isInstanceOf(Condition.Unrendered::class.java)
    }
}
