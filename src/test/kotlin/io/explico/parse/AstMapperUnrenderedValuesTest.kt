/**
 * Spec §14.71: every operand the mapper shows as source knows where it starts. `Operand.Unrendered.row` is the 1-based row of the operand's own first line, from opa's own
 * location; null when unknown, never 0. Driven from the real `opa parse` capture `values-as-source.json` (the acceptance fixture's `vs.rego`); no `opa` needed.
 */
package io.explico.parse

import io.explico.model.Operand
import io.explico.model.RuleGroup
import io.explico.opa.OpaModule
import io.explico.opa.opaJson
import io.explico.render.Coverage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class AstMapperUnrenderedValuesTest {

    private val rules: List<RuleGroup> by lazy {
        val module = opaJson.decodeFromString(OpaModule.serializer(), Files.readString(Path.of("src/test/resources/ast/values-as-source.json")))
        AstMapper.mapPolicySet(listOf(ParsedFile("vs.rego", module))).packages.single().rules
    }

    private fun values(rule: String) = Coverage.unrenderedOperandsOf(rules.single { it.name == rule }.bodies.flatMap { Coverage.conditionsOf(it) }).map { it.sourceText to it.row }

    @Test
    @DisplayName("each unrendered value has its own row: the same text on two rows keeps both, one nested in count() has its own row")
    fun rows() {
        assertThat(values("vs_one")).containsExactly("json.marshal(input.a)" to 15, "json.marshal(input.a)" to 17, "json.marshal(input.b)" to 18)
        assertThat(values("vs_two")).containsExactly("json.marshal(input.a)" to 30, "json.marshal(input.c)" to 31)
        assertThat(values("vs_fn")).containsExactly("json.marshal(x)" to 35)
        assertThat(values("helper_v")).containsExactly("json.marshal(input.h)" to 39)
    }

    @Test
    @DisplayName("the row is never part of what the value says: a hand-built one has no place, and equal text with another row is the same text")
    fun rowIsMetadata() {
        assertThat(Operand.Unrendered("x").row).isNull()
        assertThat(Operand.Unrendered("x", 3).sourceText).isEqualTo(Operand.Unrendered("x", 9).sourceText)
    }

    @Test
    @DisplayName("the footer count is the number of instances: one traversal serves both")
    fun countEqualsInstances() {
        for (rule in rules) {
            val conditions = rule.bodies.flatMap { Coverage.conditionsOf(it) }
            assertThat(Coverage.unrenderedOperandCount(conditions)).describedAs(rule.name).isEqualTo(Coverage.unrenderedOperandsOf(conditions).size)
        }
    }
}
