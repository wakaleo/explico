/**
 * Acceptance tests for the data-aware diff: a threshold or a list that lives in the DATA file is part of what a control does, so `diff` must not call a control
 * UNCHANGED when only the data it reads changed. Authored from a control-owner journey review (a bank halves a payment limit in `limits.json`; the report said
 * "UNCHANGED 4" and `--fail-on` exited 0), NOT from the frozen acceptance-pack README. Decisions (to be confirmed):
 *
 * - `Explico.diff(old, new, groupByControl, dataOld, dataNew)`: two optional data paths (a file or a directory, as `render`'s data is). Both or neither; one alone is an
 *   `IllegalArgumentException` naming both parameters. Neither = exactly the report of today.
 * - The values compared are the plain `data.*` prefixes each control reads (spec 14.41's `referencedDataPaths`: truncated before the first dynamic segment, a loaded
 *   rule or package excluded), evaluated by OPA as `data.<path>` on each side. Rego logic is never evaluated; a data path no control reads is never reported.
 * - A control whose read value differs is LOGIC_CHANGED, with one sub-bullet per path, in the "What changed" section and as a `details` entry of the JSON report:
 *   ``reference data changed: `<breadcrumb>`: `<old>` -> `<new>` `` with compact JSON values (OPA's own key order), the word `absent` for an undefined side.
 * - Values are compared as JSON values, not as text: reordered keys and different layout are no change.
 * - When data was supplied, the report's first italic (disclaimer) line says reference data is included; without data it is untouched.
 * Fixtures: `realistic/data-aware-diff/` (BANK-LIM-001 reads a limit map with a dynamic key, BANK-HRC-001 a list with `in`, BANK-SAN-001 no data; one data file per
 * change, see its README). Facade only; the CLI exit code is pinned in CliDataAwareDiffProcessTest.
 */
package io.explico.acceptance

import io.explico.Explico
import io.explico.diff.DiffCategory
import io.explico.opa.OpaRunner
import kotlinx.serialization.json.Json
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

@DisplayName("Realistic policies: data-aware diff")
class RealisticDataAwareDiffAcceptanceIT {

    companion object {
        private val base = Path.of("src/test/resources/realistic/data-aware-diff")
        private val oldPolicies = base.resolve("policies/old")
        private val newLogicPolicies = base.resolve("policies/new-logic")
        private fun data(name: String) = base.resolve("data/$name.json")

        private const val LIM = "BANK-LIM-001"
        private const val HRC = "BANK-HRC-001"
        private const val SAN = "BANK-SAN-001"
        private const val LIMIT_PATH = "`data ▸ limits ▸ single approver minor`"
        private const val LIST_PATH = "`data ▸ high risk countries`"

        private fun diff(old: Path, new: Path, dataOld: Path?, dataNew: Path?) =
            Explico.diff(Explico.load(old), Explico.load(new), dataOld = dataOld, dataNew = dataNew)

        /** Same policies on both sides; only the data differs. */
        private fun dataOnly(newData: String) = diff(oldPolicies, oldPolicies, data("old"), data(newData))

        private lateinit var limitChanged: io.explico.DiffReport
        private lateinit var limitChangedAgain: io.explico.DiffReport
        private lateinit var keyAdded: io.explico.DiffReport
        private lateinit var listAdded: io.explico.DiffReport
        private lateinit var listRemoved: io.explico.DiffReport
        private lateinit var unreadChanged: io.explico.DiffReport
        private lateinit var reformatted: io.explico.DiffReport
        private lateinit var identical: io.explico.DiffReport
        private lateinit var logicAndData: io.explico.DiffReport
        private lateinit var noData: io.explico.DiffReport

        @JvmStatic
        @BeforeAll
        fun renderAll() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            limitChanged = dataOnly("limit-changed")
            limitChangedAgain = dataOnly("limit-changed")
            keyAdded = dataOnly("key-added")
            listAdded = dataOnly("list-added")
            listRemoved = dataOnly("list-removed-entirely")
            unreadChanged = dataOnly("unread-changed")
            reformatted = dataOnly("same-reformatted")
            identical = dataOnly("old")
            logicAndData = diff(oldPolicies, newLogicPolicies, data("old"), data("limit-changed"))
            noData = Explico.diff(Explico.load(oldPolicies), Explico.load(oldPolicies))
        }

        private fun categories(report: io.explico.DiffReport): Map<String, DiffCategory> = report.entries.associate { it.controlId!! to it.category }

        /** The "What changed" lines of [report]: the bullet whose line contains [label] and its `  - ` sub-bullets. */
        private fun bullet(report: io.explico.DiffReport, label: String): List<String> {
            val lines = report.markdown.lines()
            val start = lines.indexOf("## What changed")
            assertThat(start).describedAs("a '## What changed' section").isGreaterThanOrEqualTo(0)
            val section = lines.drop(start + 1).takeWhile { !it.startsWith("## ") }
            val at = section.indexOfFirst { it.startsWith("- ") && it.contains(label) }
            assertThat(at).describedAs("a bullet for %s", label).isGreaterThanOrEqualTo(0)
            return listOf(section[at]) + section.drop(at + 1).takeWhile { it.startsWith("  - ") }
        }

        private fun jsonDetails(report: io.explico.DiffReport, controlId: String): List<String> {
            val changes = Json.parseToJsonElement(report.json).jsonObject.getValue("changes").jsonArray
            val change = changes.map { it.jsonObject }.singleOrNull { it["controlId"]?.jsonPrimitive?.content == controlId }
            assertThat(change).describedAs("a JSON change for %s", controlId).isNotNull()
            return change!!["details"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        }

        private fun disclaimer(report: io.explico.DiffReport): String = report.markdown.lines().first { it.startsWith("*This report") }
    }

    @Nested
    @DisplayName("A limit in a map read with a dynamic key")
    inner class LimitChanged {

        @Test
        @DisplayName("only the control that reads it is LOGIC_CHANGED; the others stay UNCHANGED")
        fun categories() {
            assertThat(categories(limitChanged)).containsExactlyInAnyOrderEntriesOf(
                mapOf(LIM to DiffCategory.LOGIC_CHANGED, HRC to DiffCategory.UNCHANGED, SAN to DiffCategory.UNCHANGED),
            )
        }

        @Test
        @DisplayName("the change line names the map (the plain prefix before the dynamic key) with the old and the new value")
        fun changeLine() {
            assertThat(bullet(limitChanged, LIM)).containsExactly(
                "- [$LIM — Payments above the single-approver limit need a second approver](#bank-lim-001) — logic changed",
                "  - reference data changed: $LIMIT_PATH: `{\"EUR\":100000,\"GBP\":90000}` -> `{\"EUR\":1000,\"GBP\":90000}`",
            )
        }

        @Test
        @DisplayName("the Summary table counts it: 1 LOGIC_CHANGED, 2 UNCHANGED")
        fun summary() {
            assertThat(limitChanged.markdown).contains("| LOGIC_CHANGED | 1 |").contains("| UNCHANGED | 2 |")
        }

        @Test
        @DisplayName("the JSON report carries the same line in the control's details")
        fun json() {
            assertThat(jsonDetails(limitChanged, LIM)).containsExactly(
                "reference data changed: $LIMIT_PATH: `{\"EUR\":100000,\"GBP\":90000}` -> `{\"EUR\":1000,\"GBP\":90000}`",
            )
        }
    }

    @Nested
    @DisplayName("Other shapes of data change")
    inner class OtherShapes {

        @Test
        @DisplayName("a key added to the map read dynamically shows the whole map before and after (the key itself is not a path the policy names)")
        fun keyAdded() {
            assertThat(categories(keyAdded)[LIM]).isEqualTo(DiffCategory.LOGIC_CHANGED)
            assertThat(bullet(keyAdded, LIM)).contains(
                "  - reference data changed: $LIMIT_PATH: `{\"EUR\":100000,\"GBP\":90000}` -> `{\"CHF\":95000,\"EUR\":100000,\"GBP\":90000}`",
            )
        }

        @Test
        @DisplayName("an element added to a list read with `in` changes the control that reads the list, and only that one")
        fun listElementAdded() {
            assertThat(categories(listAdded)).containsExactlyInAnyOrderEntriesOf(
                mapOf(LIM to DiffCategory.UNCHANGED, HRC to DiffCategory.LOGIC_CHANGED, SAN to DiffCategory.UNCHANGED),
            )
            assertThat(bullet(listAdded, HRC)).contains("  - reference data changed: $LIST_PATH: `[\"IR\",\"KP\"]` -> `[\"IR\",\"KP\",\"SY\"]`")
        }

        @Test
        @DisplayName("a document that disappears from the data says `absent` for the new side")
        fun documentRemoved() {
            assertThat(categories(listRemoved)[HRC]).isEqualTo(DiffCategory.LOGIC_CHANGED)
            assertThat(bullet(listRemoved, HRC)).contains("  - reference data changed: $LIST_PATH: `[\"IR\",\"KP\"]` -> absent")
        }
    }

    @Nested
    @DisplayName("No change a reader should hear about")
    inner class NoChange {

        @Test
        @DisplayName("a data path no control reads can change without a word: everything UNCHANGED, no data line anywhere")
        fun unreadPath() {
            assertThat(categories(unreadChanged).values).containsOnly(DiffCategory.UNCHANGED)
            assertThat(unreadChanged.markdown).doesNotContain("reference data changed").doesNotContain("retention")
            assertThat(unreadChanged.markdown).contains("| LOGIC_CHANGED | 0 |").contains("| UNCHANGED | 3 |")
        }

        @Test
        @DisplayName("identical data: everything UNCHANGED")
        fun identicalData() {
            assertThat(categories(identical).values).containsOnly(DiffCategory.UNCHANGED)
            assertThat(identical.markdown).doesNotContain("reference data changed")
        }

        @Test
        @DisplayName("the same values in a different key order and layout are no change (values are compared, not text), and the report equals the identical-data one")
        fun reformattedData() {
            assertThat(categories(reformatted).values).containsOnly(DiffCategory.UNCHANGED)
            assertThat(reformatted.markdown).isEqualTo(identical.markdown)
        }
    }

    @Nested
    @DisplayName("Logic and data together")
    inner class LogicAndData {

        @Test
        @DisplayName("a control whose comparison AND limit both changed is one LOGIC_CHANGED entry listing the condition change and the data change")
        fun both() {
            val lines = bullet(logicAndData, LIM)
            assertThat(lines.first()).endsWith("— logic changed")
            assertThat(lines.drop(1).filter { it.startsWith("  - condition removed:") }).hasSize(1)
            assertThat(lines.drop(1).filter { it.startsWith("  - condition added:") }).hasSize(1)
            assertThat(lines).contains("  - reference data changed: $LIMIT_PATH: `{\"EUR\":100000,\"GBP\":90000}` -> `{\"EUR\":1000,\"GBP\":90000}`")
            assertThat(logicAndData.entries.count { it.controlId == LIM }).isEqualTo(1)
        }
    }

    @Nested
    @DisplayName("When data is supplied, and when it is not")
    inner class Supplied {

        @Test
        @DisplayName("without data the report is exactly today's: the committed diff-all-categories golden, byte for byte")
        fun noDataIsToday() {
            val dir = Path.of("src/test/resources/diff/diff-all-categories")
            val report = Explico.diff(Explico.load(dir.resolve("old")), Explico.load(dir.resolve("new")))
            assertThat(report.markdown).isEqualTo(Files.readString(dir.resolve("expected/diff-report.md")))
        }

        @Test
        @DisplayName("without data the report never mentions reference data")
        fun noDataNoMention() {
            assertThat(noData.markdown).doesNotContain("reference data")
            assertThat(disclaimer(noData)).doesNotContain("reference data")
        }

        @Test
        @DisplayName("with data the disclaimer line says reference data is compared, and still says the report does not judge stricter or more permissive")
        fun disclaimerWithData() {
            for (report in listOf(limitChanged, identical)) {
                assertThat(disclaimer(report)).contains("reference data").contains("stricter or more permissive")
            }
        }

        @Test
        @DisplayName("one side only is refused, naming both parameters; both together are accepted")
        fun oneSideOnly() {
            val old = Explico.load(oldPolicies)
            assertThat(Explico.diff(old, old, dataOld = data("old"), dataNew = data("old")).entries).isNotEmpty()
            assertThatThrownBy { Explico.diff(old, old, dataOld = data("old")) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("dataOld").hasMessageContaining("dataNew")
            assertThatThrownBy { Explico.diff(old, old, dataNew = data("old")) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("dataOld").hasMessageContaining("dataNew")
        }
    }

    @Nested
    @DisplayName("Determinism")
    inner class Determinism {

        @Test
        @DisplayName("two runs are byte-identical, Markdown and JSON")
        fun deterministic() {
            assertThat(limitChangedAgain.markdown).isEqualTo(limitChanged.markdown)
            assertThat(limitChangedAgain.json).isEqualTo(limitChanged.json)
        }
    }
}
