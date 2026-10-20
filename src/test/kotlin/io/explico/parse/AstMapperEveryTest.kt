/**
 * `every` mapping (spec §14.49) from a real `opa parse` capture (`src/test/resources/ast/every.json`) -- no opa needed. All-or-nothing:
 * a [Condition.Every] only when the domain is a plain path and EVERY condition in the body is fully rendered; otherwise the whole block is
 * one [Condition.Unrendered] with the reason `every`.
 */
package io.explico.parse

import io.explico.model.Condition
import io.explico.model.Operand
import io.explico.model.PathSegment
import io.explico.opa.OpaModule
import io.explico.opa.opaJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class AstMapperEveryTest {

    private val policySet by lazy {
        val module = opaJson.decodeFromString(OpaModule.serializer(), Files.readString(Path.of("src/test/resources/ast/every.json")))
        AstMapper.mapPolicySet(listOf(ParsedFile("every.rego", module)))
    }

    private fun conditions(rule: String): List<Condition> =
        policySet.packages.single().rules.single { it.name == rule }.bodies.single().conditions

    private fun path(vararg names: String) = Operand.Path(names.map { PathSegment.Field(it) })

    @Test
    fun aSimpleEveryBecomesOneEveryWithItsBodyMappedAndTheVariableBoundAsAnIterationElement() {
        val every = conditions("simple").single() as Condition.Every
        assertThat(every.variable).isEqualTo("check")
        assertThat(every.key).isNull()
        assertThat(every.collection).isEqualTo(path("input", "pipeline", "checks"))
        val nested = every.body.single() as Condition.Comparison
        // check.status is read through the iteration binding: the collection, the [each check] marker, then the field.
        assertThat(nested.left).isEqualTo(Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("pipeline"), PathSegment.Field("checks"), PathSegment.VarIndex("check"), PathSegment.Field("status"))))
    }

    @Test
    fun theTwoVariableFormBindsTheKeyAsAnIterationKeyNotAnElement() {
        val every = conditions("keyed").single() as Condition.Every
        assertThat(every.key).isEqualTo("k")
        assertThat(every.variable).isEqualTo("v")
        assertThat(every.body).hasSize(2)
        assertThat((every.body[0] as Condition.Comparison).left).isEqualTo(Operand.Variable("k"))
    }

    @Test
    fun anEveryInsideAnEveryNestsAndTheInnerDomainIsThePathThroughTheOuterElement() {
        val outer = conditions("nested").single() as Condition.Every
        val inner = outer.body.single() as Condition.Every
        assertThat(inner.collection).isEqualTo(Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("groups"), PathSegment.VarIndex("g"), PathSegment.Field("members"))))
        assertThat(inner.body.single()).isInstanceOf(Condition.Comparison::class.java)
    }

    @Test
    @DisplayName("a body-local assignment is substituted inline, not left as a fallback, so the block still renders")
    fun aLocalAssignmentInsideTheBodyIsSubstituted() {
        val every = conditions("with_helper").single() as Condition.Every
        assertThat(every.body.single()).isInstanceOf(Condition.Comparison::class.java)
    }

    @Test
    @DisplayName("CHALLENGE: a literal collection is not a plain path, so the whole block is one fallback with the reason 'every'")
    fun aLiteralDomainFallsBack() {
        val only = conditions("literal_domain").single() as Condition.Unrendered
        assertThat(only.reason).isEqualTo("every")
        assertThat(only.sourceText).contains("every x in [1, 2]")
    }

    @Test
    @DisplayName("CHALLENGE: one unrenderable condition inside the body keeps the WHOLE block as verbatim source")
    fun anUnrenderableBodyFallsBackWhole() {
        val only = conditions("unrenderable_body").single() as Condition.Unrendered
        assertThat(only.reason).isEqualTo("every")
        assertThat(only.sourceText).contains("every c in input.containers").contains("json.marshal(c)")
    }

    @Test
    fun theIterationVariableDoesNotLeakOutOfTheBlock() {
        // `check` is only bound inside; a sibling rule's `check` would be unbound. Structurally: nothing outside the Every references VarIndex("check").
        val rules = policySet.packages.single().rules
        for (rule in rules.filter { it.name != "simple" }) {
            val text = rule.bodies.single().conditions.joinToString { it.toString() }
            assertThat(text).doesNotContain("VarIndex(name=check)")
        }
    }

    @Test
    @DisplayName("CHALLENGE (review finding): an every inside a comprehension keeps the WHOLE enclosing condition as source -- its nested conditions can never be dropped")
    fun anEveryInsideAComprehensionFallsBackWhole() {
        val only = conditions("in_comprehension").single() as Condition.Unrendered
        assertThat(only.reason).isEqualTo("comprehension")
        assertThat(only.sourceText).contains("every m in g.members").contains("m.active == true")
    }

    @Test
    @DisplayName("an every over a partial rule's value renders against the rule's document path, like some does")
    fun anEveryOverAPartialRuleRendersAgainstItsDocumentPath() {
        val every = conditions("over_partial_rule").single() as Condition.Every
        assertThat(every.collection).isEqualTo(path("data", "ev", "images"))
        assertThat(every.body.single()).isInstanceOf(Condition.Comparison::class.java)
    }

    @Test
    @DisplayName("CHALLENGE: an every over a function PARAMETER's field is not a plain path, so the whole block stays source rather than guessing a breadcrumb")
    fun anEveryOverAParameterRootedCollectionFallsBackWhole() {
        val rule = policySet.packages.single().rules.single { it.name == "all_ok" }
        val only = rule.bodies.single().conditions.single() as Condition.Unrendered
        assertThat(only.reason).isEqualTo("every")
        assertThat(only.sourceText).contains("every c in finding.checks")
    }
}
