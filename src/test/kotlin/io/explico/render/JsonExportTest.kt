/**
 * Edge cases for [JsonExport] (spec §14.45) that the real-fixture acceptance test cannot reach cheaply: text that needs JSON escaping,
 * an empty policy set, rules with no body, a conditional value, and the opt-out of reference data. Hand-built domain objects -- no opa.
 */
package io.explico.render

import io.explico.model.Condition
import io.explico.model.ConstantValue
import io.explico.model.DefaultValue
import io.explico.model.Operand
import io.explico.model.Operator
import io.explico.model.PathSegment
import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RuleBody
import io.explico.model.RuleGroup
import io.explico.model.RuleMetadata
import io.explico.model.SourceRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class JsonExportTest {

    private val digest = "a".repeat(64)
    private val condition = Condition.Comparison(Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("x"))), Operator.EQ, Operand.Literal("1"))
    private fun body(vararg c: Condition, yields: Operand? = null) = RuleBody(c.toList(), null, null, SourceRef("f.rego", 3), "", yields)

    private fun export(set: PolicySet, data: Map<Operand.Path, kotlinx.serialization.json.JsonElement?>? = null): JsonObject =
        Json.parseToJsonElement(JsonExport.render(set, digest, emptyMap(), emptyMap(), data)).jsonObject

    private fun firstRule(doc: JsonObject) = doc.getValue("packages").jsonArray.single().jsonObject.getValue("rules").jsonArray.first().jsonObject

    @Test
    fun anEmptyPolicySetIsAValidDocumentWithNoPackagesAndFullCoverage() {
        val doc = export(PolicySet(emptyList()))
        assertThat(doc.getValue("packages").jsonArray).isEmpty()
        assertThat(doc.getValue("coverage").jsonObject.getValue("percent").jsonPrimitive.int).isEqualTo(100)
        assertThat(doc.getValue("schema").jsonPrimitive.content).isEqualTo("explico/render/1")
    }

    @Test
    @DisplayName("CHALLENGE: quotes, backslashes, newlines, tabs and non-ASCII in METADATA text round-trip through the JSON exactly")
    fun specialCharactersRoundTrip() {
        val tricky = "He said \"stop\" \\ path\\to\n\tline two — ✓ café"
        val rule = RuleGroup("deny", RuleMetadata("A \"title\"", tricky, "C-1", listOf("F \"1\"")), null, listOf(body(condition)))
        val doc = export(PolicySet(listOf(PolicyPackage("p", listOf(rule), listOf("f.rego")))))
        val r = firstRule(doc)
        assertThat(r.getValue("title").jsonPrimitive.content).isEqualTo("A \"title\"")
        assertThat(r.getValue("description").jsonPrimitive.content).isEqualTo(tricky)
        assertThat(r.getValue("frameworks").jsonArray.single().jsonPrimitive.content).isEqualTo("F \"1\"")
    }

    @Test
    @DisplayName("CHALLENGE: a rule with only a default and a constant rule have no bodies, and carry their value")
    fun bodylessRules() {
        val onlyDefault = RuleGroup("allow", null, DefaultValue("false", SourceRef("f.rego", 1)), emptyList())
        val constant = RuleGroup("max", null, null, emptyList(), constantValue = ConstantValue("10", SourceRef("f.rego", 2)))
        val pkg = PolicyPackage("p", listOf(onlyDefault, constant), listOf("f.rego"))
        val rules = export(PolicySet(listOf(pkg))).getValue("packages").jsonArray.single().jsonObject.getValue("rules").jsonArray.map { it.jsonObject }

        assertThat(rules[0].getValue("bodies").jsonArray).isEmpty()
        assertThat(rules[0].getValue("default").jsonPrimitive.content).isEqualTo("false")
        assertThat(rules[0].getValue("effectOfMatch")).isEqualTo(JsonNull) // named allow, but no body: no effect, as on the card
        assertThat(rules[1].getValue("constantValue").jsonPrimitive.content).isEqualTo("10")
        assertThat(rules[1].getValue("sourceFile").jsonPrimitive.content).isEqualTo("f.rego")
    }

    @Test
    @DisplayName("CHALLENGE: a rule with no source location at all has a null sourceFile, not a crash")
    fun noSourceFile() {
        val rule = RuleGroup("ghost", null, null, emptyList())
        assertThat(firstRule(export(PolicySet(listOf(PolicyPackage("p", listOf(rule), emptyList()))))).getValue("sourceFile")).isEqualTo(JsonNull)
    }

    @Test
    fun aConditionalValueIsYieldedAsTheCardWordsIt() {
        val rule = RuleGroup("limit", null, null, listOf(body(condition, yields = Operand.Literal("\"high\""))))
        val yields = firstRule(export(PolicySet(listOf(PolicyPackage("p", listOf(rule), listOf("f.rego")))))).getValue("bodies").jsonArray.single().jsonObject.getValue("yields")
        assertThat(yields.jsonPrimitive.content).isEqualTo("`\"high\"`")
    }

    @Test
    @DisplayName("an unrendered condition keeps its source and reason and has no text; it is not rendered")
    fun unrenderedCondition() {
        val rule = RuleGroup("deny", null, null, listOf(body(Condition.Unrendered("every x in y {}", "every-block"))))
        val c = firstRule(export(PolicySet(listOf(PolicyPackage("p", listOf(rule), listOf("f.rego")))))).getValue("bodies").jsonArray.single().jsonObject.getValue("conditions").jsonArray.single().jsonObject
        assertThat(c.getValue("kind").jsonPrimitive.content).isEqualTo("unrendered")
        assertThat(c.getValue("text")).isEqualTo(JsonNull)
        assertThat(c.getValue("source").jsonPrimitive.content).isEqualTo("every x in y {}")
        assertThat(c.getValue("reason").jsonPrimitive.content).isEqualTo("every-block")
    }

    @Test
    fun referenceDataIsEmptyWithoutADataDirectoryAndFilledWithOne() {
        val dataPath = Operand.Path(listOf(PathSegment.Field("data"), PathSegment.Field("limits"), PathSegment.Field("max")))
        val rule = RuleGroup("deny", null, null, listOf(body(Condition.Truthy(dataPath, false))))
        val set = PolicySet(listOf(PolicyPackage("p", listOf(rule), listOf("f.rego"))))

        assertThat(firstRule(export(set, data = null)).getValue("referenceData").jsonArray).isEmpty()
        val withData = firstRule(export(set, data = mapOf(dataPath to JsonPrimitive(5)))).getValue("referenceData").jsonArray.single().jsonObject
        assertThat(withData.getValue("path").jsonPrimitive.content).isEqualTo("data.limits.max")
        assertThat(withData.getValue("value").jsonPrimitive.int).isEqualTo(5)
        // A JSON null that IS defined is different from undefined -- the same distinction the card makes.
        val nullDefined = firstRule(export(set, data = mapOf(dataPath to JsonNull))).getValue("referenceData").jsonArray.single().jsonObject
        assertThat(nullDefined.getValue("defined").jsonPrimitive.content).isEqualTo("true")
        val undefined = firstRule(export(set, data = mapOf(dataPath to null))).getValue("referenceData").jsonArray.single().jsonObject
        assertThat(undefined.getValue("defined").jsonPrimitive.content).isEqualTo("false")
    }

    @Test
    fun theDocumentIsPrettyPrintedWithTwoSpacesAndOneTrailingNewline() {
        val text = JsonExport.render(PolicySet(emptyList()), digest, emptyMap(), emptyMap(), null)
        assertThat(text).startsWith("{\n  \"schema\"").endsWith("}\n").doesNotEndWith("\n\n")
    }
}
