/**
 * Spec §14.49 and the diff: an `every` is hashed by STRUCTURE, never by its source text -- reformatting keeps the hash (and the diff says
 * UNCHANGED), changing a condition inside it changes the logic hash, and renaming its variables does not. Hand-built domain objects -- no opa.
 */
package io.explico.diff

import io.explico.model.Condition
import io.explico.model.Operand
import io.explico.model.Operator
import io.explico.model.PathSegment
import io.explico.model.RuleBody
import io.explico.model.RuleGroup
import io.explico.model.SourceRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CanonicalizerEveryTest {

    private fun field(vararg names: String) = Operand.Path(names.map { PathSegment.Field(it) })
    private fun element(variable: String, literal: String) = Condition.Comparison(
        Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("items"), PathSegment.VarIndex(variable), PathSegment.Field("status"))),
        Operator.EQ,
        Operand.Literal(literal),
    )

    private fun rule(sourceText: String, vararg conditions: Condition) =
        RuleGroup("r", null, null, listOf(RuleBody(conditions.toList(), null, null, SourceRef("f.rego", 1), sourceText)))

    private fun hash(vararg conditions: Condition, sourceText: String = "") = Canonicalizer.logicHash(rule(sourceText, *conditions))

    private val items = field("input", "items")

    @Test
    fun sourceTextAndFormattingNeverChangeTheHash() {
        val every = Condition.Every("i", items, listOf(element("i", "\"ok\"")))
        assertThat(hash(every, sourceText = "every i in input.items {\n i.status == \"ok\"\n}")).isEqualTo(hash(every, sourceText = "every  i  in  input.items { i.status ==\"ok\" } # c"))
    }

    @Test
    fun renamingTheVariableKeepsTheHash() {
        assertThat(hash(Condition.Every("i", items, listOf(element("i", "\"ok\"")))))
            .isEqualTo(hash(Condition.Every("item", items, listOf(element("item", "\"ok\"")))))
    }

    @Test
    fun changingANestedConditionChangesTheHash() {
        assertThat(hash(Condition.Every("i", items, listOf(element("i", "\"ok\"")))))
            .isNotEqualTo(hash(Condition.Every("i", items, listOf(element("i", "\"failed\"")))))
    }

    @Test
    fun changingTheCollectionTheKeyOrTheBodyLengthChangesTheHash() {
        val base = hash(Condition.Every("i", items, listOf(element("i", "\"ok\""))))
        assertThat(hash(Condition.Every("i", field("input", "other"), listOf(element("i", "\"ok\""))))).isNotEqualTo(base)
        assertThat(hash(Condition.Every("i", items, listOf(element("i", "\"ok\"")), key = "k"))).isNotEqualTo(base)
        assertThat(hash(Condition.Every("i", items, listOf(element("i", "\"ok\""), element("i", "\"ok\""))))).isNotEqualTo(base)
    }

    @Test
    fun anEveryDiffersFromTheSameConditionsWrittenWithoutIt() {
        val plain = element("i", "\"ok\"")
        assertThat(hash(Condition.Every("i", items, listOf(plain)))).isNotEqualTo(hash(plain))
    }
}
