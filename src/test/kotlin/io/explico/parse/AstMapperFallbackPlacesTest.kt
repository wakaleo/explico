/**
 * Spec §14.69: every condition-level fallback knows its place. `Condition.Unrendered.row` is the 1-based row of the fallback statement's first line (a whole construct --
 * an `every` block, an else chain -- its first row), from opa's own location; null when unknown. Driven from the real `opa parse` capture `fallback-places-top.json`
 * (the acceptance fixture's `top.rego`); no `opa` needed.
 */
package io.explico.parse

import io.explico.model.Condition
import io.explico.model.RuleGroup
import io.explico.opa.OpaModule
import io.explico.opa.opaJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class AstMapperFallbackPlacesTest {

    private val rules: List<RuleGroup> by lazy {
        val module = opaJson.decodeFromString(OpaModule.serializer(), Files.readString(Path.of("src/test/resources/ast/fallback-places-top.json")))
        AstMapper.mapPolicySet(listOf(ParsedFile("top.rego", module))).packages.single().rules
    }

    private fun fallbacks(rule: String) = rules.single { it.name == rule }.bodies.flatMap { it.conditions }.filterIsInstance<Condition.Unrendered>()

    @Test
    @DisplayName("each fallback statement has its own row: two calls of one rule are rows 15 and 17")
    fun statementRows() {
        assertThat(fallbacks("fp_one").map { it.row }).containsExactly(15, 17)
        assertThat(fallbacks("helper_fallback").map { it.row }).containsExactly(68)
        assertThat(fallbacks("fp_three").map { it.row to it.reason }).containsExactly(45 to "with-override")
    }

    @Test
    @DisplayName("a construct spanning several rows reports its FIRST row: the every block starts at 30, the whole else chain at 56")
    fun wholeConstructs() {
        assertThat(fallbacks("fp_two").map { it.row to it.reason }).containsExactly(30 to "every")
        assertThat(fallbacks("fp_four").map { it.row to it.reason }).containsExactly(56 to "else-chain")
    }

    @Test
    @DisplayName("the row never changes what the fallback says: its source text and reason are as before")
    fun textAndReasonUnchanged() {
        val call = fallbacks("fp_one").first()
        assertThat(call.sourceText).isEqualTo("unknown_check(input.x)")
        assertThat(call.reason).isEqualTo("function-call")
    }

    @Test
    @DisplayName("a hand-built condition has no place: row is null")
    fun defaultIsUnknown() {
        assertThat(Condition.Unrendered("x", "unclassified").row).isNull()
    }
}
