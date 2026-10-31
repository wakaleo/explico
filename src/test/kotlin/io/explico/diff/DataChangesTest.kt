/**
 * Unit tests for [DataChanges] (spec §14.73): which controls read reference data that differs between two versions of the supplied data. Hand-built domain
 * objects and hand-built resolved values -- no opa; the real data files are covered by RealisticDataAwareDiffAcceptanceIT.
 */
package io.explico.diff

import io.explico.model.Condition
import io.explico.model.Operand
import io.explico.model.Operator
import io.explico.model.PathSegment
import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RuleBody
import io.explico.model.RuleGroup
import io.explico.model.RuleMetadata
import io.explico.model.SourceRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DataChangesTest {

    private fun data(vararg names: String) = Operand.Path(listOf(PathSegment.Field("data")) + names.map { PathSegment.Field(it) })
    private val limits = data("limits")
    private val countries = data("countries")

    private fun reading(vararg paths: Operand.Path) = RuleBody(
        paths.map { Condition.Comparison(it, Operator.EQ, Operand.Literal("1")) }, null, null, SourceRef("f.rego", 1), "",
    )

    private fun rule(id: String, vararg paths: Operand.Path, name: String = "deny") =
        RuleGroup(name, RuleMetadata("T $id", "d", id, emptyList()), null, if (paths.isEmpty()) emptyList() else listOf(reading(*paths)))

    private fun set(vararg rules: RuleGroup) = PolicySet(listOf(PolicyPackage("p", rules.toList(), listOf("f.rego"))))

    private fun entry(category: DiffCategory, old: RuleGroup?, new: RuleGroup?) = DiffEntry(
        category, (new ?: old)!!.metadata?.controlId,
        old?.let { PolicyPackage("p", listOf(it), listOf("f.rego")) }, old, new?.let { PolicyPackage("p", listOf(it), listOf("f.rego")) }, new,
    )

    private fun num(n: Int): JsonElement = JsonPrimitive(n)
    private fun obj(vararg pairs: Pair<String, JsonElement>): JsonElement = JsonObject(mapOf(*pairs))

    private fun apply(entries: List<DiffEntry>, oldSet: PolicySet, newSet: PolicySet, oldValues: Map<Operand.Path, JsonElement?>, newValues: Map<Operand.Path, JsonElement?>) =
        DataChanges.apply(entries, oldSet, newSet, oldValues, newValues)

    @Nested
    @DisplayName("What counts as a change")
    inner class WhatCounts {

        private fun changes(old: JsonElement?, new: JsonElement?): List<DataChange> {
            val r = rule("C-1", limits)
            val s = set(r)
            val result = apply(listOf(entry(DiffCategory.UNCHANGED, r, r)), s, s, mapOf(limits to old), mapOf(limits to new))
            return result.changes.of(result.entries.single())
        }

        @Test
        @DisplayName("a different value is a change carrying both sides")
        fun changed() {
            assertThat(changes(num(1), num(2))).containsExactly(DataChange(limits, num(1), num(2)))
        }

        @Test
        @DisplayName("defined on one side only is a change: added (old undefined) and removed (new undefined)")
        fun addedAndRemoved() {
            assertThat(changes(null, num(2))).containsExactly(DataChange(limits, null, num(2)))
            assertThat(changes(num(1), null)).containsExactly(DataChange(limits, num(1), null))
        }

        @Test
        @DisplayName("undefined on both sides, and the same value, are no change")
        fun same() {
            assertThat(changes(null, null)).isEmpty()
            assertThat(changes(num(1), num(1))).isEmpty()
        }

        @Test
        @DisplayName("CHALLENGE: a real JSON null is a value, distinct from undefined, in both directions")
        fun jsonNullIsNotUndefined() {
            assertThat(changes(JsonNull, null)).containsExactly(DataChange(limits, JsonNull, null))
            assertThat(changes(null, JsonNull)).containsExactly(DataChange(limits, null, JsonNull))
            assertThat(changes(JsonNull, JsonNull)).isEmpty()
        }

        @Test
        @DisplayName("an object compares by its members, not by their order; an array by its order")
        fun objectsAndArrays() {
            assertThat(changes(obj("a" to num(1), "b" to num(2)), obj("b" to num(2), "a" to num(1)))).isEmpty()
            assertThat(changes(JsonArray(listOf(num(1), num(2))), JsonArray(listOf(num(2), num(1))))).hasSize(1)
        }
    }

    @Nested
    @DisplayName("Which entries are affected")
    inner class WhichEntries {

        @Test
        @DisplayName("an UNCHANGED or DOCS_CHANGED control whose data changed becomes LOGIC_CHANGED; a LOGIC_CHANGED one stays and gets the change attached")
        fun upgrades() {
            val a = rule("A", limits)
            val b = rule("B", limits)
            val c = rule("C", limits)
            val s = set(a, b, c)
            val entries = listOf(entry(DiffCategory.UNCHANGED, a, a), entry(DiffCategory.DOCS_CHANGED, b, b), entry(DiffCategory.LOGIC_CHANGED, c, c))
            val result = apply(entries, s, s, mapOf(limits to num(1)), mapOf(limits to num(2)))

            assertThat(result.entries.map { it.category }).containsExactly(DiffCategory.LOGIC_CHANGED, DiffCategory.LOGIC_CHANGED, DiffCategory.LOGIC_CHANGED)
            assertThat(result.entries.map { result.changes.of(it).size }).containsExactly(1, 1, 1)
        }

        @Test
        @DisplayName("only an entry the data RAISED is marked so; one that was LOGIC_CHANGED already is not")
        fun raisedFlag() {
            val a = rule("A", limits)
            val b = rule("B", limits)
            val s = set(a, b)
            val result = apply(listOf(entry(DiffCategory.UNCHANGED, a, a), entry(DiffCategory.LOGIC_CHANGED, b, b)), s, s, mapOf(limits to num(1)), mapOf(limits to num(2)))

            assertThat(result.entries.map { result.changes.wasRaised(it) }).containsExactly(true, false)
        }

        @Test
        @DisplayName("nothing changed: entries returned as they came, category included")
        fun untouched() {
            val a = rule("A", limits)
            val s = set(a)
            val entries = listOf(entry(DiffCategory.DOCS_CHANGED, a, a))
            val result = apply(entries, s, s, mapOf(limits to num(1)), mapOf(limits to num(1)))

            assertThat(result.entries).isEqualTo(entries)
            assertThat(result.changes.of(result.entries.single())).isEmpty()
        }

        @Test
        @DisplayName("ADDED and REMOVED entries never get a data line, whatever the data did")
        fun addedAndRemovedAreLeftAlone() {
            val a = rule("A", limits)
            val s = set(a)
            val added = entry(DiffCategory.ADDED, null, a)
            val removed = entry(DiffCategory.REMOVED, a, null)
            val result = apply(listOf(added, removed), s, s, mapOf(limits to num(1)), mapOf(limits to num(2)))

            assertThat(result.entries).containsExactly(added, removed)
            assertThat(result.entries.map { result.changes.of(it) }).containsOnly(emptyList())
        }

        @Test
        @DisplayName("a control that reads no data, or only data that did not change, is untouched while its neighbour changes")
        fun onlyTheReaderChanges() {
            val reader = rule("R", limits)
            val other = rule("O", countries)
            val none = rule("N")
            val s = set(reader, other, none)
            val entries = listOf(entry(DiffCategory.UNCHANGED, reader, reader), entry(DiffCategory.UNCHANGED, other, other), entry(DiffCategory.UNCHANGED, none, none))
            val result = apply(entries, s, s, mapOf(limits to num(1), countries to num(5)), mapOf(limits to num(2), countries to num(5)))

            assertThat(result.entries.map { it.category }).containsExactly(DiffCategory.LOGIC_CHANGED, DiffCategory.UNCHANGED, DiffCategory.UNCHANGED)
        }

        @Test
        @DisplayName("CHALLENGE: a path read only by the old rule, or only by the new rule, is compared too")
        fun readBySingleSide() {
            val old = rule("A", limits)
            val new = rule("A", countries)
            val result = apply(
                listOf(entry(DiffCategory.LOGIC_CHANGED, old, new)), set(old), set(new),
                mapOf(limits to num(1), countries to null), mapOf(limits to null, countries to num(7)),
            )

            assertThat(result.changes.of(result.entries.single())).containsExactly(DataChange(limits, num(1), null), DataChange(countries, null, num(7)))
        }

        @Test
        @DisplayName("CHALLENGE: the same path read by two controls is reported by each")
        fun sharedPath() {
            val a = rule("A", limits)
            val b = rule("B", limits)
            val s = set(a, b)
            val result = apply(listOf(entry(DiffCategory.UNCHANGED, a, a), entry(DiffCategory.UNCHANGED, b, b)), s, s, mapOf(limits to num(1)), mapOf(limits to num(2)))

            assertThat(result.entries.map { result.changes.of(it) }).containsExactly(listOf(DataChange(limits, num(1), num(2))), listOf(DataChange(limits, num(1), num(2))))
        }

        @Test
        @DisplayName("changes follow the order the rules read the paths: old rule's first, then the new rule's extra ones")
        fun order() {
            val old = rule("A", countries, limits)
            val s = set(old)
            val result = apply(listOf(entry(DiffCategory.UNCHANGED, old, old)), s, s, mapOf(limits to num(1), countries to num(1)), mapOf(limits to num(2), countries to num(2)))

            assertThat(result.changes.of(result.entries.single()).map { it.path }).containsExactly(countries, limits)
        }

        @Test
        @DisplayName("CHALLENGE: a data path that names a loaded rule is not data: never read, so never compared even if the values differ")
        fun loadedRulePathIsSkipped() {
            val target = rule("T", name = "limits_rule")
            val reader = RuleGroup("deny", RuleMetadata("T", "d", "R", emptyList()), null, listOf(reading(data("p", "limits_rule"))))
            val s = set(target, reader)
            val result = apply(listOf(entry(DiffCategory.UNCHANGED, reader, reader)), s, s, mapOf(data("p", "limits_rule") to num(1)), mapOf(data("p", "limits_rule") to num(2)))

            assertThat(result.entries.single().category).isEqualTo(DiffCategory.UNCHANGED)
        }
    }

    @Nested
    @DisplayName("The paths to resolve")
    inner class PathsToResolve {

        @Test
        @DisplayName("every distinct path either side's rule of a matched entry reads, once, in first-appearance order; ADDED/REMOVED entries read none")
        fun distinct() {
            val a = rule("A", limits, countries)
            val b = rule("B", limits)
            val extra = rule("X", data("other"))
            val s = set(a, b, extra)
            val entries = listOf(entry(DiffCategory.UNCHANGED, a, a), entry(DiffCategory.UNCHANGED, b, b), entry(DiffCategory.ADDED, null, extra))

            assertThat(DataChanges.pathsToResolve(entries, s, s)).containsExactly(limits, countries)
        }
    }
}
