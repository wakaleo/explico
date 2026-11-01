/**
 * Acceptance tests for the per-fixture verdict diff: `diff` says what moved in the policy, but a control owner asks "which of MY known cases now come out
 * differently?". Authored from the same control-owner journey review as the data-aware diff, NOT from the frozen acceptance-pack README. Decisions (to be confirmed):
 *
 * - `Explico.diff(..., examples, oldPolicyDir, newPolicyDir)`: the SAME fixtures are evaluated by OPA against the old and the new policies (through the worked-example
 *   path: OPA's verdicts and messages, never ours). The policy directories are needed because opa re-reads the `.rego` files, exactly as `render` needs `policyDir`;
 *   `examples` without both is an `IllegalArgumentException` naming them. When data is given (`dataOld`/`dataNew`) each side is evaluated with its own data.
 * - Compared per CONTROL present on both sides, with the scoping/ambiguity of `WorkedExamples.forGroup` (no examples for a name = not compared, never a guess).
 *   ADDED and REMOVED controls have no other side and are not compared.
 * - New section `## Behaviour on your examples`, only when examples were supplied, after `## What changed` (before `## Changes`): "No example changed outcome." when
 *   nothing differs, else one bullet per affected control labelled as in "What changed" but without its suffix -- `- [<id> — <title>](#<anchor>)` -- with sub-bullets
 *   `<fixture>: <old word> -> <new word>` in the card's own words (`✅ allowed`, `❌ denied`, `matched`, `not matched`), or `<fixture>: still <word>, message changed` when the
 *   verdict word is the same but the produced message set differs. A control whose logic changed but whose outcomes did not is not listed.
 * - JSON: every change gains `examples: [{fixture, old, new}]` ONLY when examples were supplied (an empty array when none differs); the schema stays `explico/diff/1`.
 * - Library: `DiffReport.exampleChanges` (control, fixture, old, new). Gate: `--fail-on-example-changes` (CLI process test: CliFixtureVerdictDiffProcessTest).
 * Fixtures: `realistic/fixture-verdict-diff/` -- BANK-EXM-001 loses its Treasury exemption (the standing-order fixture flips allowed -> denied), BANK-TGT-001 tightens a
 * threshold (the mid-value fixture flips), BANK-SIT-001 gains a situation no fixture triggers, BANK-MSG-001 changes only its message, BANK-LIM-001 has identical Rego but a
 * limit in the data (two fixtures flip when only the data changes), BANK-REM-001 exists only in `old`, BANK-NEW-001 only in `new`. Facade only.
 */
package io.explico.acceptance

import io.explico.DiffReport
import io.explico.Explico
import io.explico.DataVersions
import io.explico.DiffOptions
import io.explico.opa.OpaRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

@DisplayName("Realistic policies: per-fixture verdict diff")
class RealisticFixtureVerdictDiffAcceptanceIT {

    companion object {
        private val base = Path.of("src/test/resources/realistic/fixture-verdict-diff")
        private val oldPolicies = base.resolve("policies/old")
        private val newPolicies = base.resolve("policies/new")
        private val examplesDir = base.resolve("examples")
        private fun data(name: String) = base.resolve("data/$name.json")

        private const val HEADING = "## Behaviour on your examples"
        private const val EXM = "BANK-EXM-001"
        private const val TGT = "BANK-TGT-001"
        private const val SIT = "BANK-SIT-001"
        private const val MSG = "BANK-MSG-001"
        private const val LIM = "BANK-LIM-001"

        private const val STANDING = "standing order pre-approved by Treasury"
        private const val MID = "mid-value payment without a second approver"
        private const val KP = "payment to a sanctioned country"

        private const val ALLOWED = "✅ allowed"
        private const val DENIED = "❌ denied"

        private fun diff(old: Path, new: Path, dataOld: Path? = null, dataNew: Path? = null, withExamples: Boolean = true): DiffReport =
            Explico.diff(Explico.load(old), Explico.load(new), DiffOptions(data = if (dataOld != null && dataNew != null) DataVersions(dataOld, dataNew) else null, examples = if (withExamples) Explico.loadExamples(examplesDir) else null, digests = (if (withExamples) old else null) != null))

        private lateinit var changed: DiffReport
        private lateinit var changedAgain: DiffReport
        private lateinit var identical: DiffReport
        private lateinit var dataDriven: DiffReport
        private lateinit var withoutExamples: DiffReport

        @JvmStatic
        @BeforeAll
        fun renderAll() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            changed = diff(oldPolicies, newPolicies)
            changedAgain = diff(oldPolicies, newPolicies)
            identical = diff(oldPolicies, oldPolicies)
            dataDriven = diff(oldPolicies, oldPolicies, data("old"), data("new"))
            withoutExamples = diff(oldPolicies, newPolicies, withExamples = false)
        }

        /** The lines of the `## Behaviour on your examples` section (a failure, not an exception, when it is missing). */
        private fun section(report: DiffReport): List<String> {
            val lines = report.markdown.lines()
            val start = lines.indexOf(HEADING)
            assertThat(start).describedAs("a '%s' section", HEADING).isGreaterThanOrEqualTo(0)
            return lines.drop(start + 1).takeWhile { !it.startsWith("## ") }
        }

        /** The bullet for [controlId] in the section followed by its `  - ` sub-bullets; empty when the control is not listed. */
        private fun bullet(report: DiffReport, controlId: String): List<String> {
            val lines = section(report)
            val at = lines.indexOfFirst { it.startsWith("- [$controlId — ") }
            if (at < 0) return emptyList()
            return listOf(lines[at]) + lines.drop(at + 1).takeWhile { it.startsWith("  - ") }
        }

        /** The `examples` entries of [controlId]'s JSON change (an assertion failure, not an exception, when the key is missing). */
        private fun examplesOf(report: DiffReport, controlId: String): List<JsonObject> {
            val change = jsonChange(report, controlId)
            assertThat(change).describedAs("the JSON change of %s", controlId).containsKey("examples")
            return change.getValue("examples").jsonArray.map { it.jsonObject }
        }

        private fun jsonChange(report: DiffReport, controlId: String): JsonObject {
            val changes = Json.parseToJsonElement(report.json).jsonObject.getValue("changes").jsonArray.map { it.jsonObject }
            val change = changes.singleOrNull { it["controlId"]?.jsonPrimitive?.content == controlId }
            assertThat(change).describedAs("a JSON change for %s", controlId).isNotNull()
            return change!!
        }
    }

    @Nested
    @DisplayName("Old against new policies")
    inner class PolicyChange {

        @Test
        @DisplayName("the section sits after ## What changed and before ## Changes, once")
        fun placement() {
            val lines = changed.markdown.lines()
            assertThat(lines.count { it == HEADING }).isEqualTo(1)
            assertThat(lines.indexOf(HEADING)).isGreaterThan(lines.indexOf("## What changed")).isLessThan(lines.indexOf("## Changes"))
        }

        @Test
        @DisplayName("a removed exemption: the standing-order fixture goes from allowed to denied, in the card's own words")
        fun removedExemption() {
            assertThat(bullet(changed, EXM)).containsExactly(
                "- [$EXM — High-value payments need a second approver](#bank-exm-001)",
                "  - $STANDING: $ALLOWED -> $DENIED",
            )
        }

        @Test
        @DisplayName("a tightened threshold: only the fixture between the two thresholds flips")
        fun tightenedThreshold() {
            assertThat(bullet(changed, TGT)).containsExactly(
                "- [$TGT — Very high payments need CFO approval](#bank-tgt-001)",
                "  - $MID: $ALLOWED -> $DENIED",
            )
        }

        @Test
        @DisplayName("a message-only change: the verdict word is the same, and the line says the message changed")
        fun messageOnly() {
            assertThat(bullet(changed, MSG)).containsExactly(
                "- [$MSG — Payments to sanctioned countries are blocked](#bank-msg-001)",
                "  - $KP: still $DENIED, message changed",
            )
        }

        @Test
        @DisplayName("fixtures whose outcome did not change are never listed, and neither is a control with nothing to report")
        fun unflippedNeverListed() {
            val text = section(changed).joinToString("\n")
            for (name in listOf("small payment", "screening still pending", "large payment approved by a second person")) {
                assertThat(text).describedAs(name).doesNotContain(name)
            }
            assertThat(section(changed).count { it.startsWith("  - ") }).isEqualTo(3)
            assertThat(section(changed).count { it.startsWith("- [") }).isEqualTo(3)
        }

        @Test
        @DisplayName("a control whose logic changed but whose outcomes did not (BANK-SIT-001, a new situation no fixture triggers) is changed in the report and absent from the section")
        fun logicChangedWithoutOutcomeChange() {
            assertThat(changed.markdown).contains("[$SIT — Payments must be screened](#bank-sit-001) — logic changed")
            assertThat(bullet(changed, SIT)).isEmpty()
            assertThat(section(changed).joinToString("\n")).doesNotContain(SIT)
        }

        @Test
        @DisplayName("an ADDED and a REMOVED control have no other side and are not compared, though both have outcomes of their own")
        fun addedAndRemoved() {
            assertThat(changed.markdown).contains("— removed").contains("— added")
            val text = section(changed).joinToString("\n")
            assertThat(text).doesNotContain("BANK-REM-001").doesNotContain("BANK-NEW-001")
        }

        @Test
        @DisplayName("the library lists the same changes: control, fixture, old and new word")
        fun libraryChanges() {
            assertThat(changed.exampleChanges.map { listOf(it.control, it.fixture, it.old, it.new) }).containsExactlyInAnyOrder(
                listOf(EXM, STANDING, ALLOWED, DENIED),
                listOf(TGT, MID, ALLOWED, DENIED),
                listOf(MSG, KP, DENIED, DENIED),
            )
        }
    }

    @Nested
    @DisplayName("What was compared")
    inner class WhatWasCompared {

        @Test
        @DisplayName("every control present on both sides had examples on both: the section ends with the exact count, in Markdown, JSON and the library")
        fun everythingCompared() {
            assertThat(section(changed)).contains("*Compared 5 of 5 controls present in both versions.*")
            // Old against old has six controls in common (BANK-REM-001 exists on both sides there); old against new has five.
            assertThat(section(identical)).contains("No example changed outcome.", "*Compared 6 of 6 controls present in both versions.*")
            val comparison = Json.parseToJsonElement(changed.json).jsonObject.getValue("exampleComparison").jsonObject
            assertThat(comparison.mapValues { it.value.jsonPrimitive.content }).isEqualTo(
                mapOf("compared" to "5", "total" to "5", "functions" to "0", "unattributable" to "0", "fixturesOnOneVersion" to "0"),
            )
            assertThat(changed.exampleComparison).isEqualTo(io.explico.diff.ExampleComparison(5, 5, 0, 0, 0))
        }

        @Test
        @DisplayName("without examples there is no comparison at all: no JSON object, no library value")
        fun withoutExamples() {
            assertThat(withoutExamples.json).doesNotContain("exampleComparison")
            assertThat(withoutExamples.exampleComparison).isNull()
        }
    }

    @Nested
    @DisplayName("Nothing differs")
    inner class NothingDiffers {

        @Test
        @DisplayName("the same policies on both sides: the section says no example changed outcome, lists no control, and the library has no changes")
        fun sentence() {
            assertThat(section(identical)).contains("No example changed outcome.")
            assertThat(section(identical).count { it.startsWith("- [") }).isZero()
            assertThat(identical.exampleChanges).isEmpty()
        }
    }

    @Nested
    @DisplayName("The data alone changes the outcome")
    inner class DataDriven {

        @Test
        @DisplayName("identical Rego, a lower limit in the data: BANK-LIM-001 lists the two fixtures that now exceed it, and nothing else is listed")
        fun dataFlip() {
            assertThat(bullet(dataDriven, LIM)).containsExactlyInAnyOrder(
                "- [$LIM — Payments above the currency limit are denied](#bank-lim-001)",
                "  - $MID: $ALLOWED -> $DENIED",
                "  - $STANDING: $ALLOWED -> $DENIED",
            )
            assertThat(section(dataDriven).count { it.startsWith("- [") }).isEqualTo(1)
            assertThat(dataDriven.exampleChanges.map { it.control }.distinct()).containsExactly(LIM)
        }
    }

    @Nested
    @DisplayName("JSON")
    inner class Json_ {

        @Test
        @DisplayName("with examples every change carries examples [{fixture, old, new}]; an empty array when none differs; the schema is unchanged")
        fun withExamples() {
            val exm = examplesOf(changed, EXM)
            assertThat(exm).hasSize(1)
            assertThat(exm.single().getValue("fixture").jsonPrimitive.content).isEqualTo(STANDING)
            assertThat(exm.single().getValue("old").jsonPrimitive.content).isEqualTo(ALLOWED)
            assertThat(exm.single().getValue("new").jsonPrimitive.content).isEqualTo(DENIED)
            assertThat(examplesOf(changed, MSG).map { it.getValue("fixture").jsonPrimitive.content }).containsExactly(KP)
            assertThat(examplesOf(changed, SIT)).isEmpty()
            assertThat(Json.parseToJsonElement(changed.json).jsonObject.getValue("schema").jsonPrimitive.content).isEqualTo("explico/diff/1")
        }

        @Test
        @DisplayName("without examples no change has an examples key at all")
        fun withoutExamples() {
            assertThat(withoutExamples.json).doesNotContain("\"examples\"")
            assertThat(Json.parseToJsonElement(withoutExamples.json).jsonObject.getValue("changes").jsonArray).isNotEmpty()
        }
    }

    @Nested
    @DisplayName("Without example fixtures, and the rules of the call")
    inner class Without {

        @Test
        @DisplayName("without examples there is no section, no library changes, and the report is the committed diff-all-categories golden byte for byte")
        fun noExamplesIsToday() {
            assertThat(withoutExamples.markdown).doesNotContain(HEADING)
            assertThat(withoutExamples.exampleChanges).isEmpty()
            val dir = Path.of("src/test/resources/diff/diff-all-categories")
            val golden = Explico.diff(Explico.load(dir.resolve("old")), Explico.load(dir.resolve("new")))
            assertThat(golden.markdown).isEqualTo(Files.readString(dir.resolve("expected/diff-report.md")))
        }

        @Test
        @DisplayName("examples need the sets' directories, and the error names sourceDir; sets loaded by Explico.load have them (migrated, spec 14.80 step 2)")
        fun needsPolicyDirs() {
            val old = Explico.load(oldPolicies)
            val examples = Explico.loadExamples(examplesDir)
            assertThat(Explico.diff(old, old, DiffOptions(examples = examples, digests = true)).markdown).contains(HEADING)
            val hand = io.explico.model.PolicySet(emptyList())
            assertThatThrownBy { Explico.diff(hand, hand, DiffOptions(examples = examples)) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("sourceDir").hasMessageContaining("Explico.load")
        }

        @Test
        @DisplayName("data for both versions together with examples is accepted (migrated, spec 14.80 step 2: data for one version only can no longer be written)")
        fun oneSidedDataStillRefused() {
            val old = Explico.load(oldPolicies)
            val examples = Explico.loadExamples(examplesDir)
            assertThat(Explico.diff(old, old, DiffOptions(data = DataVersions(data("old"), data("new")), examples = examples, digests = true)).markdown).contains(HEADING)
        }
    }

    @Nested
    @DisplayName("Determinism")
    inner class Determinism {

        @Test
        @DisplayName("two runs are byte-identical, Markdown and JSON, and list the same changes")
        fun deterministic() {
            assertThat(changedAgain.markdown).isEqualTo(changed.markdown)
            assertThat(changedAgain.json).isEqualTo(changed.json)
            assertThat(changedAgain.exampleChanges).isEqualTo(changed.exampleChanges)
        }
    }
}
