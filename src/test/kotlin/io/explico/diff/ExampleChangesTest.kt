/**
 * Unit tests for [ExampleChanges] (spec §14.74): which author fixtures come out differently between two versions of a control. Hand-built outcomes -- no opa; the real
 * verdicts are pinned by RealisticFixtureVerdictDiffAcceptanceIT.
 */
package io.explico.diff

import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RuleBody
import io.explico.model.RuleGroup
import io.explico.model.RuleMetadata
import io.explico.model.SourceRef
import io.explico.render.Fixture
import io.explico.render.WorkedExample
import kotlinx.serialization.json.JsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ExampleChangesTest {

    private val body = RuleBody(emptyList(), null, null, SourceRef("f.rego", 1), "")

    private fun rule(name: String = "deny", id: String? = "C-1", parameters: List<String> = emptyList(), template: String? = null) =
        RuleGroup(
            name, id?.let { RuleMetadata("T $it", "d", it, emptyList()) }, null,
            listOf(RuleBody(emptyList(), null, template, SourceRef("f.rego", 1), "")), parameters = parameters,
        )

    private fun pkg(vararg rules: RuleGroup) = PolicyPackage("p", rules.toList(), listOf("f.rego"))
    private fun set(vararg rules: RuleGroup) = PolicySet(listOf(pkg(*rules)))

    private fun fixture(name: String) = Fixture(name, null, JsonObject(emptyMap()))
    private fun outcome(name: String, matched: Boolean, vararg messages: String) = WorkedExample(fixture(name), matched, messages.toList(), messages.map { null })
    private fun outcomes(ruleName: String, vararg list: WorkedExample) = mapOf("p" to mapOf(ruleName to list.toList()))

    private fun entry(category: DiffCategory, old: RuleGroup?, new: RuleGroup?) = DiffEntry(
        category, (new ?: old)!!.metadata?.controlId, old?.let { pkg(it) }, old, new?.let { pkg(it) }, new,
    )

    private fun compute(old: RuleGroup?, new: RuleGroup?, oldOutcomes: Map<String, Map<String, List<WorkedExample>>>, newOutcomes: Map<String, Map<String, List<WorkedExample>>>, category: DiffCategory = DiffCategory.LOGIC_CHANGED): List<ExampleChange> {
        val e = entry(category, old, new)
        return ExampleChanges.compute(listOf(e), oldOutcomes, newOutcomes).of(e)
    }

    private fun same(rule: RuleGroup, old: List<WorkedExample>, new: List<WorkedExample>) =
        compute(rule, rule, outcomes(rule.name, *old.toTypedArray()), outcomes(rule.name, *new.toTypedArray()))

    @Nested
    @DisplayName("What counts as a change")
    inner class WhatCounts {

        @Test
        @DisplayName("a flip in either direction is reported with the card's own words for each side")
        fun flips() {
            val r = rule()
            assertThat(same(r, listOf(outcome("a", false), outcome("b", true, "m")), listOf(outcome("a", true, "m"), outcome("b", false)))).containsExactly(
                ExampleChange("C-1", "a", "✅ allowed", "❌ denied"),
                ExampleChange("C-1", "b", "❌ denied", "✅ allowed"),
            )
        }

        @Test
        @DisplayName("the same outcome, and fixtures that did not change, are not reported")
        fun same() {
            val r = rule()
            assertThat(same(r, listOf(outcome("a", false), outcome("b", true, "m")), listOf(outcome("a", false), outcome("b", true, "m")))).isEmpty()
        }

        @Test
        @DisplayName("matched on both sides with a different message set is reported, with the same word on both sides")
        fun messageOnly() {
            val r = rule()
            assertThat(same(r, listOf(outcome("a", true, "old text")), listOf(outcome("a", true, "new text")))).containsExactly(ExampleChange("C-1", "a", "❌ denied", "❌ denied"))
        }

        @Test
        @DisplayName("messages are compared as a set: the same messages in another order are no change")
        fun messageOrder() {
            val r = rule()
            assertThat(same(r, listOf(outcome("a", true, "x", "y")), listOf(outcome("a", true, "y", "x")))).isEmpty()
        }

        @Test
        @DisplayName("a boolean rule (no messages) flips are detected, and nothing else is invented")
        fun booleanRule() {
            val r = rule("allow")
            assertThat(same(r, listOf(outcome("a", false), outcome("b", true)), listOf(outcome("a", true), outcome("b", true)))).containsExactly(ExampleChange("C-1", "a", "❌ denied", "✅ allowed"))
        }

        @Test
        @DisplayName("a rule with no naming convention says matched / not matched")
        fun otherName() {
            val r = rule("check")
            assertThat(same(r, listOf(outcome("a", false)), listOf(outcome("a", true)))).containsExactly(ExampleChange("C-1", "a", "not matched", "matched"))
        }

        @Test
        @DisplayName("results follow the order of the fixtures")
        fun fixtureOrder() {
            val r = rule()
            assertThat(same(r, listOf(outcome("z", false), outcome("a", false)), listOf(outcome("z", true, "m"), outcome("a", true, "m"))).map { it.fixture }).containsExactly("z", "a")
        }
    }

    @Nested
    @DisplayName("What is never compared")
    inner class NeverCompared {

        @Test
        @DisplayName("CHALLENGE: a fixture evaluated on one side only (the other skipped it with a warning) is not compared, so no bogus flip appears")
        fun oneSidedFixture() {
            val r = rule()
            assertThat(same(r, listOf(outcome("a", false), outcome("b", false)), listOf(outcome("b", false)))).isEmpty()
            assertThat(same(r, listOf(outcome("b", false)), listOf(outcome("a", true, "m"), outcome("b", false)))).isEmpty()
        }

        @Test
        @DisplayName("ADDED and REMOVED entries have one side and are skipped")
        fun addedAndRemoved() {
            val r = rule()
            assertThat(compute(null, r, outcomes("deny"), outcomes("deny", outcome("a", true, "m")), DiffCategory.ADDED)).isEmpty()
            assertThat(compute(r, null, outcomes("deny", outcome("a", true, "m")), outcomes("deny"), DiffCategory.REMOVED)).isEmpty()
        }

        @Test
        @DisplayName("a function is not evaluated against an input, so it is never compared")
        fun function() {
            val f = rule("is_ok", parameters = listOf("x"))
            assertThat(same(f, listOf(outcome("a", false)), listOf(outcome("a", true)))).isEmpty()
        }

        @Test
        @DisplayName("CHALLENGE: two same-named cards whose messages cannot be attributed get no examples (WorkedExamples.forGroup), so they are not compared")
        fun ambiguousSiblings() {
            val a = rule(id = "C-1")
            val b = rule(id = "C-2")
            val p = pkg(a, b)
            val e = DiffEntry(DiffCategory.LOGIC_CHANGED, "C-1", p, a, p, a)
            val result = ExampleChanges.compute(listOf(e), outcomes("deny", outcome("x", false)), outcomes("deny", outcome("x", true, "m")))

            assertThat(result.of(e)).isEmpty()
        }

        @Test
        @DisplayName("no outcomes for the rule on a side (no fixtures evaluated) means not compared")
        fun noOutcomes() {
            val r = rule()
            assertThat(compute(r, r, outcomes("deny"), outcomes("deny", outcome("a", true, "m")))).isEmpty()
            assertThat(compute(r, r, emptyMap(), emptyMap())).isEmpty()
        }
    }

    @Nested
    @DisplayName("Names, identity and order")
    inner class Identity {

        @Test
        @DisplayName("a control-id-preserving rename deny -> deny_stage reports the flip with each side's own word, and nothing when the verdict is the same")
        fun rename() {
            val old = rule("deny")
            val new = rule("deny_stage")
            val e = entry(DiffCategory.LOGIC_CHANGED, old, new)
            val flip = ExampleChanges.compute(listOf(e), outcomes("deny", outcome("a", false)), outcomes("deny_stage", outcome("a", true, "m"))).of(e)
            val same = ExampleChanges.compute(listOf(e), outcomes("deny", outcome("a", true, "m")), outcomes("deny_stage", outcome("a", true, "m"))).of(e)

            assertThat(flip).containsExactly(ExampleChange("C-1", "a", "✅ allowed", "matched"))
            assertThat(same).isEmpty()
        }

        @Test
        @DisplayName("a rule with no control id is named package.rule")
        fun idLess() {
            val r = rule(id = null)
            assertThat(same(r, listOf(outcome("a", false)), listOf(outcome("a", true, "m"))).map { it.control }).containsExactly("p.deny")
        }

        @Test
        @DisplayName("flat() lists the changes in the report's own control order (category, then control id), not the order of the entries")
        fun flatOrder() {
            val x = rule(id = "B-1")
            val y = rule(id = "A-1")
            val px = PolicyPackage("p", listOf(x), listOf("f.rego"))
            val py = PolicyPackage("q", listOf(y), listOf("g.rego"))
            val ex = DiffEntry(DiffCategory.LOGIC_CHANGED, "B-1", px, x, px, x)
            val ey = DiffEntry(DiffCategory.LOGIC_CHANGED, "A-1", py, y, py, y)
            val result = ExampleChanges.compute(
                listOf(ex, ey),
                mapOf("p" to mapOf("deny" to listOf(outcome("x", false))), "q" to mapOf("deny" to listOf(outcome("x", false)))),
                mapOf("p" to mapOf("deny" to listOf(outcome("x", true, "m"))), "q" to mapOf("deny" to listOf(outcome("x", true, "m")))),
            )

            assertThat(result.flat(listOf(ex, ey)).map { it.control }).containsExactly("A-1", "B-1")
        }
    }

    @Nested
    @DisplayName("What was compared (the ExampleComparison)")
    inner class Comparison {

        @Test
        @DisplayName("counts the controls present on both sides: compared, functions, unattributable; ADDED and REMOVED are not part of it")
        fun counts() {
            val ok = rule(id = "OK")
            val fn = rule("is_ok", id = "FN", parameters = listOf("x"))
            val none = rule("check", id = "NONE")
            val added = rule("added", id = "ADD")
            val p = pkg(ok, fn, none, added)
            val entries = listOf(
                DiffEntry(DiffCategory.UNCHANGED, "OK", p, ok, p, ok), DiffEntry(DiffCategory.UNCHANGED, "FN", p, fn, p, fn),
                DiffEntry(DiffCategory.UNCHANGED, "NONE", p, none, p, none), DiffEntry(DiffCategory.ADDED, "ADD", null, null, p, added),
            )
            val outcomes = mapOf("p" to mapOf("deny" to listOf(outcome("a", false)), "is_ok" to listOf(outcome("a", false))))
            val result = ExampleChanges.compute(entries, outcomes, outcomes)

            assertThat(result.comparison).isEqualTo(ExampleComparison(compared = 1, total = 3, functions = 1, unattributable = 1, fixturesOnOneVersion = 0))
        }

        @Test
        @DisplayName("CHALLENGE: a fixture only one version could evaluate is counted once by name, however many controls it touches")
        fun fixturesOnOneVersion() {
            val a = rule(id = "A")
            val b = rule("allow", id = "B")
            val p = pkg(a, b)
            val entries = listOf(DiffEntry(DiffCategory.UNCHANGED, "A", p, a, p, a), DiffEntry(DiffCategory.UNCHANGED, "B", p, b, p, b))
            val old = mapOf("p" to mapOf("deny" to listOf(outcome("x", false), outcome("y", false)), "allow" to listOf(outcome("x", false), outcome("y", false))))
            val new = mapOf("p" to mapOf("deny" to listOf(outcome("y", false)), "allow" to listOf(outcome("y", false), outcome("z", false))))

            assertThat(ExampleChanges.compute(entries, old, new).comparison).isEqualTo(ExampleComparison(2, 2, 0, 0, fixturesOnOneVersion = 2))
        }

        @Test
        @DisplayName("nothing to compare at all: zero of zero")
        fun empty() {
            assertThat(ExampleChanges.compute(emptyList(), emptyMap(), emptyMap()).comparison).isEqualTo(ExampleComparison(0, 0, 0, 0, 0))
        }
    }
}
