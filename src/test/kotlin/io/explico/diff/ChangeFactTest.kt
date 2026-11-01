/**
 * Unit tests for the typed facts of a LOGIC_CHANGED change (spec §14.78): [FactKind] is a closed set of 15, [ChangeSummary.factsFor] builds the facts FIRST and the
 * sentences (`subBulletsFor`, the Markdown, the JSON `details`) are rendered FROM them, and [DiffJson] emits each fact's typed fields. Hand-built domain objects -- no opa;
 * the real fixtures are pinned by RealisticDiffFactsAcceptanceIT.
 */
package io.explico.diff

import io.explico.model.Condition
import io.explico.model.ConstantValue
import io.explico.model.DeclaredProjection
import io.explico.model.DefaultValue
import io.explico.model.Operand
import io.explico.model.Operator
import io.explico.model.PathSegment
import io.explico.model.PolicyPackage
import io.explico.model.RuleBody
import io.explico.model.RuleGroup
import io.explico.model.RuleMetadata
import io.explico.model.SourceRef
import io.explico.model.VerdictProjection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.IdentityHashMap

class ChangeFactTest {

    private fun path(name: String) = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field(name)))
    private fun dataPath(vararg names: String) = Operand.Path(listOf(PathSegment.Field("data")) + names.map { PathSegment.Field(it) })
    private fun cond(field: String, value: String) = Condition.Comparison(path(field), Operator.EQ, Operand.Literal(value))
    private fun body(vararg conditions: Condition, template: String? = null) = RuleBody(conditions.toList(), template, template, SourceRef("f.rego", 1), "")
    private fun meta(description: String = "D", projection: DeclaredProjection? = null) = RuleMetadata("T", description, "C-1", listOf("F"), projection = projection)
    private fun rule(
        vararg bodies: RuleBody, metadata: RuleMetadata? = meta(), default: DefaultValue? = null, constant: ConstantValue? = null,
    ) = RuleGroup("deny", metadata, default, bodies.toList(), constantValue = constant)

    private fun entry(old: RuleGroup, new: RuleGroup) = DiffEntry(
        DiffCategory.LOGIC_CHANGED, "C-1", PolicyPackage("p", listOf(old), listOf("f.rego")), old, PolicyPackage("p", listOf(new), listOf("f.rego")), new,
    )

    private fun kinds(e: DiffEntry, data: List<DataChange> = emptyList()) = ChangeSummary.factsFor(e, data).map { it.kind }

    @Nested
    @DisplayName("FactKind")
    inner class Kinds {

        @Test
        @DisplayName("the closed set is exactly these 15 kebab-case names, each unique")
        fun closedSet() {
            val expected = listOf(
                "condition-removed", "condition-added", "message-removed", "message-added", "bodies-changed", "default-outcome-changed", "value-changed",
                "source-fallback-changed", "situations-reordered", "situation-value-changed", "situation-removed", "situation-added", "structure-changed",
                "reference-data-changed", "documentation-changed",
            )

            assertThat(FactKind.entries.map { it.wire }).containsExactlyInAnyOrderElementsOf(expected)
            assertThat(FactKind.entries.map { it.wire }.toSet()).hasSameSizeAs(FactKind.entries)
            assertThat(FactKind.entries.map { it.wire }).allMatch { Regex("[a-z]+(-[a-z]+)+").matches(it) }
        }
    }

    @Nested
    @DisplayName("factsFor")
    inner class FactsFor {

        @Test
        @DisplayName("the sentences ARE the facts' sentences, in order: subBulletsFor is factsFor mapped")
        fun oneSource() {
            val e = entry(rule(body(cond("a", "1"))), rule(body(cond("b", "2"), template = "new"), body(cond("c", "3")), metadata = meta("D2")))
            val data = listOf(DataChange(dataPath("limits"), JsonPrimitive(1), JsonPrimitive(2)))

            assertThat(ChangeSummary.factsFor(e, data).map { it.sentence }).isEqualTo(ChangeSummary.subBulletsFor(e, data))
        }

        @Test
        @DisplayName("conditions and messages carry the phrase alone; the sentence is built from it")
        fun phrases() {
            val e = entry(rule(body(cond("a", "1"), template = "old %v")), rule(body(cond("b", "2"), template = "new %v")))
            val facts = ChangeSummary.factsFor(e)

            assertThat(facts.map { it.kind }).containsExactly(FactKind.CONDITION_REMOVED, FactKind.CONDITION_ADDED, FactKind.MESSAGE_REMOVED, FactKind.MESSAGE_ADDED)
            assertThat(facts[0].text).isEqualTo("`a` is `1`")
            assertThat(facts[0].sentence).isEqualTo("condition removed: `a` is `1`")
        }

        @Test
        @DisplayName("bodies carry the old and new counts as numbers; default outcome and a constant value are sentence-only")
        fun countsAndDefaults() {
            val e = entry(rule(body(cond("a", "1"))), rule(body(cond("a", "1")), body(cond("a", "1"), cond("b", "2"))))
            val bodies = ChangeSummary.factsFor(e).single { it.kind == FactKind.BODIES_CHANGED }

            assertThat(bodies.oldCount).isEqualTo(1)
            assertThat(bodies.newCount).isEqualTo(2)
            assertThat(bodies.sentence).isEqualTo("bodies: 1 -> 2")
            val d = entry(rule(body(cond("a", "1")), default = DefaultValue("false", SourceRef("f", 1))), rule(body(cond("a", "1")), default = DefaultValue("true", SourceRef("f", 1))))
            assertThat(kinds(d)).containsExactly(FactKind.DEFAULT_OUTCOME_CHANGED)
            val c = entry(rule(constant = ConstantValue("10", SourceRef("f", 1))), rule(constant = ConstantValue("20", SourceRef("f", 1))))
            assertThat(kinds(c)).containsExactly(FactKind.VALUE_CHANGED)
        }

        @Test
        @DisplayName("nothing mechanical to name: the structure fallback; with documentation it stays first and documentation is last")
        fun fallbackAndOrdering() {
            val same = body(cond("a", "1"))
            assertThat(kinds(entry(rule(same), rule(same, metadata = meta())))).containsExactly(FactKind.STRUCTURE_CHANGED)
            assertThat(kinds(entry(rule(same), rule(same, metadata = meta("D2"))))).containsExactly(FactKind.STRUCTURE_CHANGED, FactKind.DOCUMENTATION_CHANGED)
        }

        @Test
        @DisplayName("an entry raised by data only: the data fact then the documentation fact, never a structure fallback")
        fun raisedByData() {
            val same = body(cond("a", "1"))
            val data = listOf(DataChange(dataPath("limits", "max"), JsonPrimitive(1), JsonPrimitive(2)))

            assertThat(kinds(entry(rule(same), rule(same, metadata = meta("D2"))), data)).containsExactly(FactKind.REFERENCE_DATA_CHANGED, FactKind.DOCUMENTATION_CHANGED)
        }

        @Test
        @DisplayName("documentation-changed carries the same field names as the sentence")
        fun documentationFields() {
            val same = body(cond("a", "1"))
            val fact = ChangeSummary.factsFor(entry(rule(same), rule(same, metadata = meta("D2", DeclaredProjection(verdict = VerdictProjection("ok")))))).last()

            assertThat(fact.kind).isEqualTo(FactKind.DOCUMENTATION_CHANGED)
            assertThat(fact.fields).containsExactly("description", "verdict")
            assertThat(fact.sentence).isEqualTo("documentation changed: description, verdict")
        }
    }

    @Nested
    @DisplayName("In the JSON")
    inner class InJson {

        private fun json(e: DiffEntry, data: List<DataChange> = emptyList()): JsonObject {
            val index = DataChangeIndex(IdentityHashMap<DiffEntry, List<DataChange>>().also { it[e] = data }, emptySet())
            return Json.parseToJsonElement(DiffJson.render(listOf(e), index)).jsonObject.getValue("changes").jsonArray.single().jsonObject
        }

        @Test
        @DisplayName("facts follow details, parallel to it, each with kind (wire name) and sentence")
        fun parallel() {
            val e = entry(rule(body(cond("a", "1"))), rule(body(cond("b", "2")), metadata = meta("D2")))
            val change = json(e)

            assertThat(change.keys.toList().indexOf("facts")).isEqualTo(change.keys.toList().indexOf("details") + 1)
            assertThat(change.getValue("facts").jsonArray.map { it.jsonObject.getValue("sentence").jsonPrimitive.content })
                .isEqualTo(change.getValue("details").jsonArray.map { it.jsonPrimitive.content })
            assertThat(change.getValue("facts").jsonArray.map { it.jsonObject.getValue("kind").jsonPrimitive.content })
                .containsExactly("condition-removed", "condition-added", "documentation-changed")
        }

        @Test
        @DisplayName("condition and message facts carry the phrase under their own key; bodies carry JSON numbers")
        fun fields() {
            val e = entry(rule(body(cond("a", "1"), template = "old")), rule(body(cond("a", "1"), template = "new"), body(cond("a", "1"))))
            val facts = json(e).getValue("facts").jsonArray.map { it.jsonObject }

            assertThat(facts.single { it.getValue("kind").jsonPrimitive.content == "message-added" }.getValue("message").jsonPrimitive.content).isEqualTo("new")
            val bodies = facts.single { it.getValue("kind").jsonPrimitive.content == "bodies-changed" }
            assertThat(bodies.getValue("old").jsonPrimitive.isString).isFalse()
            assertThat(bodies.getValue("old").jsonPrimitive.content).isEqualTo("1")
            assertThat(bodies.getValue("new").jsonPrimitive.content).isEqualTo("2")
        }

        @Test
        @DisplayName("CHALLENGE: a phrase with quotes, a backslash and unicode survives JSON escaping unchanged")
        fun escaping() {
            val tricky = """the "q" \ back é 版本"""
            val e = entry(rule(body(Condition.Comparison(path("a"), Operator.EQ, Operand.Literal("\"$tricky\"")))), rule(body(cond("b", "1"))))
            val removed = json(e).getValue("facts").jsonArray.map { it.jsonObject }.first { it.getValue("kind").jsonPrimitive.content == "condition-removed" }

            assertThat(removed.getValue("condition").jsonPrimitive.content).contains(tricky).isEqualTo(removed.getValue("sentence").jsonPrimitive.content.removePrefix("condition removed: "))
        }

        @Test
        @DisplayName("reference data: path without backticks, old and new as real JSON; an undefined side has NO key, a real JSON null is null")
        fun referenceData() {
            val same = body(cond("a", "1"))
            val e = entry(rule(same), rule(same, metadata = meta("D2")))
            fun fact(old: kotlinx.serialization.json.JsonElement?, new: kotlinx.serialization.json.JsonElement?) =
                json(e, listOf(DataChange(dataPath("limits", "max"), old, new))).getValue("facts").jsonArray.map { it.jsonObject }.first { it.getValue("kind").jsonPrimitive.content == "reference-data-changed" }

            val both = fact(JsonObject(mapOf("EUR" to JsonPrimitive(1))), JsonArray(listOf(JsonPrimitive("x"))))
            assertThat(both.getValue("path").jsonPrimitive.content).isEqualTo("data ▸ limits ▸ max")
            assertThat(both.getValue("old")).isInstanceOf(JsonObject::class.java)
            assertThat(both.getValue("new")).isInstanceOf(JsonArray::class.java)
            val undefinedNew = fact(JsonPrimitive(1), null)
            assertThat(undefinedNew).doesNotContainKey("new")
            assertThat(undefinedNew.getValue("old").jsonPrimitive.content).isEqualTo("1")
            val nullNew = fact(JsonPrimitive(1), JsonNull)
            assertThat(nullNew).containsKey("new")
            assertThat(nullNew.getValue("new")).isEqualTo(JsonNull)
            assertThat(fact(null, JsonPrimitive(2))).doesNotContainKey("old")
        }

        @Test
        @DisplayName("documentation-changed carries fields; facts exist only on LOGIC_CHANGED")
        fun otherCategories() {
            val same = body(cond("a", "1"))
            assertThat(json(entry(rule(same), rule(same, metadata = meta("D2")))).getValue("facts").jsonArray.last().jsonObject.getValue("fields").jsonArray.map { it.jsonPrimitive.content })
                .containsExactly("description")
            val r = rule(same)
            val added = DiffEntry(DiffCategory.ADDED, "C-1", null, null, PolicyPackage("p", listOf(r), listOf("f.rego")), r)
            val docs = DiffEntry(DiffCategory.DOCS_CHANGED, "C-1", PolicyPackage("p", listOf(r), listOf("f.rego")), r, PolicyPackage("p", listOf(r), listOf("f.rego")), r)
            for (e in listOf(added, docs)) {
                val change = Json.parseToJsonElement(DiffJson.render(listOf(e))).jsonObject.getValue("changes").jsonArray.single().jsonObject
                assertThat(change).doesNotContainKey("facts")
            }
        }
    }
}
