/**
 * Acceptance tests for the diff side of FR-7 of the decision-object feature requests: `Explico.diff(old, new, DiffOptions(groupByControl = true))` (CLI: `diff --group-by-control`).
 * A control split over several files shares ONE control id, which `diff` rejects today (`DuplicateControlIdException`, exit 4). With the option, opt-in:
 *
 * - the duplicate check is relaxed, and a rule's identity is (control id, package, rule) for any control id shared by two or more rules on EITHER side,
 *   decided per id over BOTH versions; an id used by a single rule on both sides keeps the plain control-id identity (so a package/rule rename of such a
 *   control stays one entry, as today); a rule with no control id keeps its `package.rule` identity.
 * - entries are per RULE: only the edited rule of a shared control is LOGIC_CHANGED, and an unchanged rule (also one moved to another file) is UNCHANGED
 *   and absent from the report; the report names the rule (`package.rule`) wherever it names a shared control, so two entries with one id are
 *   distinguishable, and every anchor in it is unique.
 * - without the option shared ids still throw, exactly as before; with it but no shared ids the report is exactly the old one.
 *
 * Operator decisions (see the FR-6/FR-7 design notes): the flag relaxes the check and changes the identity only with the flag. Authored from the feature
 * request and the plan, NOT from the frozen acceptance-pack README. Fixtures: `realistic/group-by-control-diff/{old,new}` (13 rules: see the entry table
 * in [Entries]). Facade only; failures are assertion failures (an exception from the call under test is turned into one).
 */
package io.explico.acceptance

import io.explico.DiffReport
import io.explico.Explico
import io.explico.DiffOptions
import io.explico.diff.DiffCategory
import io.explico.diff.DiffEntry
import io.explico.diff.DuplicateControlIdException
import io.explico.opa.OpaRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path

@DisplayName("Realistic policies: diff a multi-file control with --group-by-control (FR-7)")
class RealisticGroupByControlDiffAcceptanceIT {

    companion object {
        private val old = Path.of("src/test/resources/realistic/group-by-control-diff/old")
        private val new = Path.of("src/test/resources/realistic/group-by-control-diff/new")

        private lateinit var oldSet: io.explico.model.PolicySet
        private lateinit var newSet: io.explico.model.PolicySet
        private lateinit var outcome: Outcome
        private lateinit var again: Outcome

        /** A diff call's result or the exception it threw (kept so the test can fail with an ASSERTION instead of an error). */
        class Outcome(val report: DiffReport?, val error: Throwable?)

        private fun attempt(old: io.explico.model.PolicySet, new: io.explico.model.PolicySet): Outcome =
            try { Outcome(Explico.diff(old, new, DiffOptions(groupByControl = true)), null) } catch (e: Exception) { Outcome(null, e) }

        @JvmStatic
        @BeforeAll
        fun diffAll() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            oldSet = Explico.load(old)
            newSet = Explico.load(new)
            outcome = attempt(oldSet, newSet)
            again = attempt(oldSet, newSet)
        }

        /** The grouped diff; an ASSERTION failure (not an exception) when the call threw. */
        private fun report(result: Outcome = outcome): DiffReport {
            assertThat(result.error).describedAs("Explico.diff(old, new, DiffOptions(groupByControl = true)) must not throw").isNull()
            return result.report!!
        }

        /** The one entry whose rule is called [rule] on its displayed side (new, else old), or on its old side (a renamed rule); an assertion failure unless exactly one. */
        private fun entry(rule: String): DiffEntry {
            val matching = report().entries.filter { rule == it.newRule?.name || rule == it.oldRule?.name }
            assertThat(matching).describedAs("exactly one entry for rule '$rule'").hasSize(1)
            return matching.single()
        }

        private fun markdown() = report().markdown

        private fun json(): JsonObject = Json.parseToJsonElement(report().json).jsonObject

        private fun changes(): List<JsonObject> = json().getValue("changes").jsonArray.map { it.jsonObject }

        /** The top-level bullets of the "What changed" section (sub-bullets are indented). */
        private fun whatChanged(): List<String> {
            val lines = markdown().lines()
            val start = lines.indexOfFirst { it == "## What changed" }
            assertThat(start).describedAs("a '## What changed' section").isGreaterThanOrEqualTo(0)
            val end = lines.drop(start + 1).indexOfFirst { it.startsWith("## ") }.let { if (it < 0) lines.size else start + 1 + it }
            return lines.subList(start + 1, end).filter { it.startsWith("- ") }
        }

        private fun bulletFor(vararg parts: String): String {
            val matching = whatChanged().filter { bullet -> parts.all { it in bullet } }
            assertThat(matching).describedAs("exactly one 'What changed' bullet containing %s", parts.toList()).hasSize(1)
            return matching.single()
        }

        /** The card headings of the Changes section (a rule's card is headed `## `; the report's own sections are not cards). */
        private fun cardHeadings(): List<String> {
            val own = setOf("## Summary", "## What changed", "## Changes")
            return markdown().lines().filter { it.startsWith("## ") && it !in own }
        }

        private fun headingFor(vararg parts: String): String {
            val matching = cardHeadings().filter { heading -> parts.all { it in heading } }
            assertThat(matching).describedAs("exactly one card heading containing %s", parts.toList()).hasSize(1)
            return matching.single()
        }

        /** The card that starts at the heading containing [parts], up to the next `---` separator. */
        private fun card(vararg parts: String): String {
            val heading = headingFor(*parts)
            val lines = markdown().lines()
            val start = lines.indexOf(heading)
            val end = lines.drop(start + 1).indexOfFirst { it == "---" }
            return lines.drop(start).let { if (end < 0) it else it.take(end + 1) }.joinToString("\n")
        }

        private fun anchors(markdown: String) = Regex("<a id=\"([^\"]*)\"></a>").findAll(markdown).map { it.groupValues[1] }.toList()

        private fun unfenced(markdown: String): List<String> {
            var inFence = false
            return markdown.lines().filter { line -> if (line.trim().startsWith("```")) { inFence = !inFence; false } else !inFence }
        }
    }

    @Nested
    @DisplayName("Without the option nothing changes")
    inner class WithoutTheOption {

        @Test
        @DisplayName("shared control ids still throw DuplicateControlIdException, naming the ids shared in the OLD version")
        fun stillThrows() {
            val thrown = catchThrowable { Explico.diff(oldSet, newSet) }
            assertThat(thrown).isInstanceOf(DuplicateControlIdException::class.java)
            assertThat((thrown as DuplicateControlIdException).controlIds).containsExactly("SEC-28", "SEC-40")
        }

        @Test
        @DisplayName("an explicit false is the same as not mentioning the option")
        fun explicitFalse() {
            val thrown = catchThrowable { Explico.diff(oldSet, newSet) }
            assertThat(thrown).isInstanceOf(DuplicateControlIdException::class.java)
        }

        @Test
        @DisplayName("with the option but NO shared id, the report is exactly the one without it")
        fun noSharedIdsNoDifference() {
            val before = Explico.load(Path.of("src/test/resources/diff/diff-all-categories/old"))
            val after = Explico.load(Path.of("src/test/resources/diff/diff-all-categories/new"))
            val plain = Explico.diff(before, after)
            val grouped = Explico.diff(before, after, DiffOptions(groupByControl = true))
            assertThat(grouped.markdown).isEqualTo(plain.markdown)
            assertThat(grouped.json).isEqualTo(plain.json)
            assertThat(grouped.entries).isEqualTo(plain.entries)
        }
    }

    @Nested
    @DisplayName("Entries: one per rule of a shared control")
    inner class Entries {

        @Test
        @DisplayName("13 entries: 2 ADDED, 1 REMOVED, 3 LOGIC_CHANGED, 1 DOCS_CHANGED, 6 UNCHANGED")
        fun counts() {
            val byCategory = report().entries.groupingBy { it.category }.eachCount()
            assertThat(report().entries).hasSize(13)
            assertThat(byCategory).containsEntry(DiffCategory.ADDED, 2).containsEntry(DiffCategory.REMOVED, 1).containsEntry(DiffCategory.LOGIC_CHANGED, 3)
                .containsEntry(DiffCategory.DOCS_CHANGED, 1).containsEntry(DiffCategory.UNCHANGED, 6)
        }

        @Test
        @DisplayName("SEC-28 (shared on both sides): one entry per rule -- only the edited rule is LOGIC_CHANGED, only the re-documented one DOCS_CHANGED")
        fun sharedOnBothSides() {
            assertThat(report().entries.filter { it.controlId == "SEC-28" }.map { (it.newRule ?: it.oldRule)!!.name to it.category })
                .containsExactlyInAnyOrder(
                    "evidence_present" to DiffCategory.UNCHANGED,
                    "audit_logged" to DiffCategory.UNCHANGED,
                    "signed_off" to DiffCategory.LOGIC_CHANGED,
                    "valid_signature" to DiffCategory.DOCS_CHANGED,
                )
            val signedOff = entry("signed_off")
            assertThat(signedOff.oldPackage?.path).isEqualTo("grp_signoff")
            assertThat(signedOff.newPackage?.path).isEqualTo("grp_signoff")
            assertThat(entry("valid_signature").newPackage?.path).isEqualTo("grp_signoff")
        }

        @Test
        @DisplayName("a rule moved to another FILE (same id, package and rule) is UNCHANGED")
        fun movedBetweenFiles() {
            val moved = entry("audit_logged")
            assertThat(moved.category).isEqualTo(DiffCategory.UNCHANGED)
            assertThat(moved.oldRule).isNotNull()
            assertThat(moved.newRule).isNotNull()
        }

        @Test
        @DisplayName("SEC-40: the rule only in the old version is REMOVED, the one only in the new is ADDED, the shared one is UNCHANGED")
        fun addedAndRemoved() {
            val dropped = entry("drop")
            assertThat(dropped.category).isEqualTo(DiffCategory.REMOVED)
            assertThat(dropped.controlId).isEqualTo("SEC-40")
            assertThat(dropped.oldPackage?.path).isEqualTo("grp_life")
            assertThat(dropped.newRule).isNull()
            val fresh = entry("fresh")
            assertThat(fresh.category).isEqualTo(DiffCategory.ADDED)
            assertThat(fresh.controlId).isEqualTo("SEC-40")
            assertThat(fresh.newPackage?.path).isEqualTo("grp_life")
            assertThat(fresh.oldRule).isNull()
            assertThat(entry("keep").category).isEqualTo(DiffCategory.UNCHANGED)
        }

        @Test
        @DisplayName("SEC-50 (one rule in the old version, two in the new): identity is decided over BOTH versions, so the old rule is matched and only the new one is ADDED")
        fun singleThenShared() {
            val gate = entry("gate")
            assertThat(gate.category).isEqualTo(DiffCategory.UNCHANGED)
            assertThat(gate.oldRule).isNotNull()
            assertThat(gate.newRule).isNotNull()
            assertThat(entry("gate_extra").category).isEqualTo(DiffCategory.ADDED)
            assertThat(report().entries.filter { it.controlId == "SEC-50" }).hasSize(2)
        }

        @Test
        @DisplayName("SEC-60 (one rule on both sides, package and rule renamed): still ONE entry by the plain control id -- UNCHANGED, as today")
        fun renameOfASingleRuleControl() {
            val entries = report().entries.filter { it.controlId == "SEC-60" }
            assertThat(entries).hasSize(1)
            val renamed = entries.single()
            assertThat(renamed.category).isEqualTo(DiffCategory.UNCHANGED)
            assertThat(renamed.oldRule?.name).isEqualTo("old_name")
            assertThat(renamed.newRule?.name).isEqualTo("new_name")
            assertThat(renamed.oldPackage?.path).isEqualTo("grp_ren")
            assertThat(renamed.newPackage?.path).isEqualTo("grp_ren2")
        }

        @Test
        @DisplayName("a single-rule control (SEC-70) and rules with no control id keep their identities: LOGIC_CHANGED, controlId null for the id-less rule")
        fun otherIdentitiesUnchanged() {
            assertThat(entry("only").category).isEqualTo(DiffCategory.LOGIC_CHANGED)
            assertThat(entry("only").controlId).isEqualTo("SEC-70")
            val plain = entry("plain_changed")
            assertThat(plain.category).isEqualTo(DiffCategory.LOGIC_CHANGED)
            assertThat(plain.controlId).isNull()
            assertThat(plain.newPackage?.path).isEqualTo("grp_other")
            assertThat(entry("calm").category).isEqualTo(DiffCategory.UNCHANGED)
        }
    }

    @Nested
    @DisplayName("The report")
    inner class TheReport {

        @Test
        @DisplayName("the summary table carries the same counts")
        fun summary() {
            val md = markdown()
            for ((category, count) in listOf("ADDED" to 2, "REMOVED" to 1, "LOGIC_CHANGED" to 3, "DOCS_CHANGED" to 1, "UNCHANGED" to 6)) {
                assertThat(md).describedAs("summary row for $category").contains("| $category | $count |")
            }
        }

        @Test
        @DisplayName("'What changed' has one bullet per changed RULE (7), and none for an unchanged rule, including the one that only moved file")
        fun whatChangedBullets() {
            assertThat(whatChanged()).hasSize(7)
            assertThat(markdown()).doesNotContain("audit_logged")
        }

        @Test
        @DisplayName("a shared control's bullets name the rule as well as the id: SEC-28 signed_off 'logic changed', SEC-28 valid_signature 'documentation changed: title, description'")
        fun sharedBulletsNameTheRule() {
            assertThat(bulletFor("SEC-28", "grp_signoff.signed_off")).contains("logic changed")
            assertThat(bulletFor("SEC-28", "grp_signoff.valid_signature")).contains("documentation changed: title, description")
            assertThat(bulletFor("SEC-40", "grp_life.drop")).contains("removed")
            assertThat(bulletFor("SEC-40", "grp_life.fresh")).contains("added")
            assertThat(bulletFor("SEC-50", "grp_grow.gate_extra")).contains("added")
        }

        @Test
        @DisplayName("a single-rule control and an id-less rule are labelled as before: no package path added to SEC-70, 'grp_other.plain_changed' for the id-less one")
        fun otherBulletsAsBefore() {
            val single = bulletFor("SEC-70")
            assertThat(single).contains("logic changed").doesNotContain("grp_single")
            assertThat(bulletFor("grp_other.plain_changed")).contains("logic changed")
        }

        @Test
        @DisplayName("each card of a shared control is headed with its id AND its package.rule: signed_off (changed), drop (removed), fresh (added), gate_extra (added)")
        fun cardHeadingsNameTheRule() {
            headingFor("SEC-28", "grp_signoff.signed_off")
            headingFor("SEC-40", "grp_life.drop")
            headingFor("SEC-40", "grp_life.fresh")
            headingFor("SEC-50", "grp_grow.gate_extra")
        }

        @Test
        @DisplayName("a single-rule control's card heading is as before: 'SEC-70 — Single rule'")
        fun singleRuleHeadingAsBefore() {
            assertThat(headingFor("SEC-70")).isEqualTo("## SEC-70 — Single rule")
        }

        @Test
        @DisplayName("only the edited rule has a source diff: the signed_off card shows the added approver condition, and no other SEC-28 rule is in the report body")
        fun onlyTheEditedRuleHasADiff() {
            val signedOff = card("SEC-28", "grp_signoff.signed_off")
            assertThat(signedOff).contains("**Source diff**")
            assertThat(signedOff.substringAfter("**Source diff**").lines().filter { it.startsWith("+") }).anyMatch { "input.signoff.approver" in it }
            assertThat(markdown()).doesNotContain("count(input.evidence) > 0").doesNotContain("input.audit.logged")
        }

        @Test
        @DisplayName("the DOCS_CHANGED rule has a docs table with its old and new title and no card")
        fun docsChanged() {
            val md = markdown()
            assertThat(md).contains("| Title | — | Signature is valid |").contains("| Description | — | The signature on the change is present. |")
            assertThat(cardHeadings().none { "valid_signature" in it }).isTrue()
        }
    }

    @Nested
    @DisplayName("Anchors and links")
    inner class AnchorsAndLinks {

        @Test
        @DisplayName("every anchor in the report is unique, and each of the six carded entries has its own")
        fun anchorsAreUnique() {
            val all = anchors(markdown())
            assertThat(all).describedAs("anchors of the six carded entries").hasSize(6)
            assertThat(all).doesNotHaveDuplicates()
        }

        @Test
        @DisplayName("each linked 'What changed' bullet links to an anchor that exists, and no two bullets link to the same one")
        fun bulletsLinkToTheirCards() {
            val existing = anchors(markdown()).toSet()
            val targets = whatChanged().flatMap { bullet -> Regex("""\]\(#([^)\s]+)\)""").findAll(bullet).map { it.groupValues[1] }.toList() }
            assertThat(targets).hasSize(6)
            assertThat(existing).containsAll(targets)
            assertThat(targets).doesNotHaveDuplicates()
        }

        @Test
        @DisplayName("a card links to a rule that has its own card (fresh -> signed_off) and leaves a rule without one (signed_off -> evidence_present) unlinked")
        fun crossReferences() {
            assertThat(card("SEC-40", "grp_life.fresh")).contains("[`signed_off`](#")
            val reference = unfenced(card("SEC-28", "grp_signoff.signed_off")).single { it.contains("`evidence_present`") }
            assertThat(reference).doesNotContain("](#")
        }

        @Test
        @DisplayName("no link anywhere in the report points at a missing anchor")
        fun noDeadLinks() {
            val lines = unfenced(markdown())
            val existing = anchors(markdown()).toSet()
            val links = lines.flatMap { Regex("""\]\(#([^)\s]+)\)""").findAll(it).map { m -> m.groupValues[1] }.toList() }
            assertThat(links).isNotEmpty()
            assertThat(links.filter { it !in existing }).isEmpty()
        }
    }

    @Nested
    @DisplayName("explico diff JSON")
    inner class DiffJson {

        @Test
        @DisplayName("summary counts and one change per changed rule (7), each naming its package and rule")
        fun changesNameTheRule() {
            val summary = json().getValue("summary").jsonObject
            assertThat(summary.getValue("ADDED").jsonPrimitive.content).isEqualTo("2")
            assertThat(summary.getValue("UNCHANGED").jsonPrimitive.content).isEqualTo("6")
            val triples = changes().map { Triple(it.getValue("category").jsonPrimitive.content, it.getValue("package").jsonPrimitive.content, it.getValue("rule").jsonPrimitive.content) }
            assertThat(triples).containsExactlyInAnyOrder(
                Triple("REMOVED", "grp_life", "drop"),
                Triple("ADDED", "grp_life", "fresh"),
                Triple("ADDED", "grp_grow", "gate_extra"),
                Triple("LOGIC_CHANGED", "grp_signoff", "signed_off"),
                Triple("LOGIC_CHANGED", "grp_single", "only"),
                Triple("LOGIC_CHANGED", "grp_other", "plain_changed"),
                Triple("DOCS_CHANGED", "grp_signoff", "valid_signature"),
            )
        }

        @Test
        @DisplayName("a shared control's label names the rule; the control id is kept; a single-rule control's label is as before")
        fun labels() {
            val signedOff = changes().single { it.getValue("rule").jsonPrimitive.content == "signed_off" }
            assertThat(signedOff.getValue("controlId").jsonPrimitive.content).isEqualTo("SEC-28")
            assertThat(signedOff.getValue("label").jsonPrimitive.content).contains("SEC-28").contains("grp_signoff.signed_off")
            val only = changes().single { it.getValue("rule").jsonPrimitive.content == "only" }
            assertThat(only.getValue("label").jsonPrimitive.content).doesNotContain("grp_single")
        }

        @Test
        @DisplayName("each carded change's anchor is distinct, exists in the Markdown, and is the target of its bullet; the docs-only change has none")
        fun anchorsMatchTheMarkdown() {
            val md = markdown()
            val anchorsOf = changes().filter { it.getValue("category").jsonPrimitive.content != "DOCS_CHANGED" }
                .map { it.getValue("anchor").jsonPrimitive.content }
            assertThat(anchorsOf).hasSize(6).doesNotHaveDuplicates()
            for (anchor in anchorsOf) {
                assertThat(md).contains("<a id=\"$anchor\"></a>")
                assertThat(whatChanged().count { "](#$anchor)" in it }).describedAs("bullets linking to $anchor").isEqualTo(1)
            }
            assertThat(changes().single { it.getValue("category").jsonPrimitive.content == "DOCS_CHANGED" }.getValue("anchor")).isEqualTo(kotlinx.serialization.json.JsonNull)
        }
    }

    @Nested
    @DisplayName("Determinism")
    inner class Determinism {

        @Test
        @DisplayName("two runs give the same entries, Markdown and JSON")
        fun deterministic() {
            assertThat(report(again).markdown).isEqualTo(report().markdown)
            assertThat(report(again).json).isEqualTo(report().json)
            assertThat(report(again).entries.map { it.category to (it.newRule ?: it.oldRule)!!.name })
                .isEqualTo(report().entries.map { it.category to (it.newRule ?: it.oldRule)!!.name })
        }

        @Test
        @DisplayName("diffing a version against itself reports nothing changed: 12 UNCHANGED, no 'What changed' section")
        fun selfDiff() {
            val same = report(attempt(newSet, newSet))
            assertThat(same.entries).hasSize(12).allMatch { it.category == DiffCategory.UNCHANGED }
            assertThat(same.markdown).doesNotContain("## What changed").doesNotContain("## Changes")
        }
    }
}
