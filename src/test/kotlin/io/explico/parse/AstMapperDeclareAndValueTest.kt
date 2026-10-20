/**
 * Real-world benchmark promotions (corpus: Gatekeeper, Trivy, Regal, conftest), from a real `opa parse` capture -- no opa needed:
 * 1. a declare-only `some i, j` asserts nothing and is not a condition;
 * 2. a function / complete rule whose value variable is assigned a message (`f(c) = msg { ...; msg := sprintf(...) }`) shows that text as
 *    its produced value, exactly like a rule's own message, and only when it is the LAST statement and fully understood.
 */
package io.explico.parse

import io.explico.model.Condition
import io.explico.opa.OpaModule
import io.explico.opa.opaJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class AstMapperDeclareAndValueTest {

    private val policySet by lazy {
        val module = opaJson.decodeFromString(OpaModule.serializer(), Files.readString(Path.of("src/test/resources/ast/declare-and-value.json")))
        AstMapper.mapPolicySet(listOf(ParsedFile("sm.rego", module)))
    }

    private fun body(rule: String) = policySet.packages.single().rules.single { it.name == rule }.bodies.single()

    @Test
    @DisplayName("a declare-only `some i` is not a condition: the one real comparison is all that remains")
    fun declareOnlySomeIsNotACondition() {
        val conditions = body("a").conditions
        assertThat(conditions).hasSize(1)
        assertThat(conditions.single()).isInstanceOf(Condition.Comparison::class.java)
    }

    @Test
    @DisplayName("the multi-variable form `some i, j` is not a condition either")
    fun declareOnlyMultiVariableSomeIsNotACondition() {
        assertThat(body("b").conditions).hasSize(1)
    }

    @Test
    @DisplayName("a function's message assignment becomes its produced value, not a helper-assignment fallback")
    fun functionMessageAssignmentBecomesProducedValue() {
        val b = body("message_of")
        assertThat(b.conditions).hasSize(1)
        assertThat(b.conditions.none { it is Condition.Unrendered }).isTrue()
        assertThat(b.producesValue).isEqualTo("container [c name] is bad")
        assertThat(b.messageTemplate).isEqualTo("container %v is bad")
        assertThat(b.yieldsValue).describedAs("the value is the text itself, not a bare `msg`").isNull()
    }

    @Test
    @DisplayName("CHALLENGE: a message assignment that is NOT the last statement is left alone (later statements may use the variable)")
    fun messageAssignmentNotLastIsLeftAlone() {
        val b = body("message_not_last")
        assertThat(b.producesValue).isNull()
        assertThat(b.conditions.filterIsInstance<Condition.Unrendered>()).hasSize(1)
    }

    @Test
    @DisplayName("CHALLENGE: a value variable assigned something that is not a message (a computed number) is not consumed")
    fun computedValueIsNotConsumed() {
        val b = body("computed")
        assertThat(b.producesValue).isNull()
        assertThat(b.messageTemplate).isNull()
    }
}
