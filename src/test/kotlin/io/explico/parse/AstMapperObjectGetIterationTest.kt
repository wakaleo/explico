/**
 * Unit tests for spec §14.62: `some x in object.get(<plain path>, "<key>", [])` -- the defensive way to read a list that may be absent -- becomes a real
 * [Condition.SomeIn] over the path extended by the key, carrying the default it states. Driven from the real `opa parse` capture `object-get-iteration.json`
 * (no `opa` binary needed); each rule name below describes the shape its source has.
 */
package io.explico.parse

import io.explico.model.Condition
import io.explico.model.Operand
import io.explico.model.PathSegment
import io.explico.model.RuleGroup
import io.explico.opa.OpaModule
import io.explico.opa.opaJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class AstMapperObjectGetIterationTest {

    private val rules: List<RuleGroup> by lazy {
        val module = opaJson.decodeFromString(OpaModule.serializer(), Files.readString(Path.of("src/test/resources/ast/object-get-iteration.json")))
        AstMapper.mapPolicySet(listOf(ParsedFile("og/capture.rego", module))).packages.single().rules
    }

    private fun conditions(name: String) = rules.single { it.name == name }.bodies.single().conditions

    private fun input(vararg more: PathSegment) = Operand.Path(listOf<PathSegment>(PathSegment.Field("input")) + more)

    @Test
    @DisplayName("object.get(input, \"items\", []) iterates the path `input.items` and states its default; the loop variable then resolves like any iteration")
    fun topLevelKey() {
        val conditions = conditions("top")
        assertThat(conditions[0]).isEqualTo(Condition.SomeIn("g", input(PathSegment.Field("items")), defaultValue = "[]"))
        val comparison = conditions[1] as Condition.Comparison
        assertThat(comparison.left).isEqualTo(input(PathSegment.Field("items"), PathSegment.VarIndex("g"), PathSegment.Field("kind")))
    }

    @Test
    @DisplayName("a longer plain first argument extends: object.get(input.request, \"reviewers\", [])")
    fun nestedFirstArgument() {
        assertThat(conditions("nested")[0]).isEqualTo(Condition.SomeIn("r", input(PathSegment.Field("request"), PathSegment.Field("reviewers")), defaultValue = "[]"))
    }

    @Test
    @DisplayName("a camelCase key is an ordinary field (the humaniser splits it later), exactly like input.narGroups")
    fun camelCaseKey() {
        assertThat((conditions("camel")[0] as Condition.SomeIn).collection).isEqualTo(input(PathSegment.Field("narGroups")))
    }

    @Test
    @DisplayName("a key that is not a plain identifier stays a quoted bracket key, as input[\"my-items\"] would: dash, and dot")
    fun keysThatAreNotIdentifiers() {
        assertThat((conditions("dashed")[0] as Condition.SomeIn).collection).isEqualTo(input(PathSegment.KeyLiteral("my-items")))
        assertThat((conditions("dotted_key")[0] as Condition.SomeIn).collection).isEqualTo(input(PathSegment.KeyLiteral("a.b")))
    }

    @Test
    @DisplayName("the two-variable form keeps both variables and the default")
    fun keyAndValueForm() {
        assertThat(conditions("pair")[0]).isEqualTo(Condition.SomeIn("v", input(PathSegment.Field("items")), "k", "[]"))
    }

    @Test
    @DisplayName("a first argument that is itself an iteration variable keeps its element index: object.get(o, \"inner\", [])")
    fun variableRootedFirstArgument() {
        val someIn = conditions("var_first_arg")[1] as Condition.SomeIn
        assertThat(someIn.collection).isEqualTo(input(PathSegment.Field("outer"), PathSegment.VarIndex("o"), PathSegment.Field("inner")))
        assertThat(someIn.defaultValue).isEqualTo("[]")
    }

    @Test
    @DisplayName("a key that is not a string literal is not modelled: the iteration is a fallback with the verbatim source")
    fun nonLiteralKeyFallsBack() {
        val first = conditions("non_literal_key")[0] as Condition.Unrendered
        assertThat(first.sourceText).contains("object.get(input, input.listName, [])")
    }

    @Test
    @DisplayName("only an empty-array default is modelled: [\"a\"] and {} stay fallbacks")
    fun otherDefaultsFallBack() {
        assertThat(conditions("other_default")[0]).isInstanceOf(Condition.Unrendered::class.java)
        assertThat(conditions("object_default")[0]).isInstanceOf(Condition.Unrendered::class.java)
    }

    @Test
    @DisplayName("the default exists only on the iteration: a plain `some x in input.items` has none")
    fun plainIterationHasNoDefault() {
        assertThat(Condition.SomeIn("g", input(PathSegment.Field("items"))).defaultValue).isNull()
    }

    @Test
    @DisplayName("inside a comprehension the same iteration is recognised")
    fun insideAComprehension() {
        val comparison = conditions("comprehended").single() as Condition.Comparison
        val comprehension = (comparison.left as Operand.BuiltinCall).args.single() as Operand.Comprehension
        assertThat((comprehension.body[0] as Condition.SomeIn).defaultValue).isEqualTo("[]")
    }

    @Test
    @DisplayName("`every` over the same expression stays one fallback: an absent list makes `every` undefined, not vacuously true, so dropping the default would misstate it")
    fun everyStaysAFallback() {
        val only = conditions("every_over_it").single() as Condition.Unrendered
        assertThat(only.reason).isEqualTo("every")
    }
}
