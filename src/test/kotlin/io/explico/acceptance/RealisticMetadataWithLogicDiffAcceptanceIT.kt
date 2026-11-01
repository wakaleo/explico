/**
 * Acceptance tests for documentation changes that travel WITH a logic change. Today a control whose logic changed shows only its new card and the logic sub-bullets: a
 * reviewer cannot see that its criticality rose, that a framework was added, or that its description dropped a sentence. Authored from the same control-owner journey
 * review as the data-aware and per-fixture diffs, NOT from the frozen acceptance-pack README. Decisions (to be confirmed):
 *
 * - "Differs" is exactly what DOCS_CHANGED already means (title, description, frameworks, authors, related resources, custom fields, the declared projections): one
 *   definition, the field names it already prints.
 * - For a LOGIC_CHANGED entry whose metadata also differs: (1) `## What changed` gets a last sub-bullet `documentation changed: <fields>`; (2) its section in
 *   `## Changes` gets a `**Documentation changes**` block, after the card (and after a `**Reference data**` block) and before `**Source diff**`, with the SAME old/new table
 *   a DOCS_CHANGED prints but ONLY the rows that differ; (3) its JSON change gains `fields` (as a DOCS_CHANGED has), only then.
 * - A LOGIC_CHANGED entry whose metadata is identical looks exactly as before; DOCS_CHANGED looks as before (all three base rows); ADDED/REMOVED are unaffected;
 *   categories and hashes do not change. A DOCS_CHANGED control raised to LOGIC_CHANGED by the data (data-aware diff) now carries its documentation changes too.
 * Fixtures: `realistic/metadata-with-logic/{old,new,data}` -- PAY-REL-001 (logic + description + frameworks + criticality), PAY-SAN-001 (logic only), PAY-DOC-001 (title
 * only), PAY-LIM-001 (description only in the Rego, plus a changed limit in the data). Facade only.
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
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

@DisplayName("Realistic policies: documentation changes beside a logic change")
class RealisticMetadataWithLogicDiffAcceptanceIT {

    companion object {
        private val base = Path.of("src/test/resources/realistic/metadata-with-logic")
        private val oldPolicies = base.resolve("old")
        private val newPolicies = base.resolve("new")
        private fun data(name: String) = base.resolve("data/$name.json")

        private const val REL = "PAY-REL-001"
        private const val SAN = "PAY-SAN-001"
        private const val DOC = "PAY-DOC-001"
        private const val LIM = "PAY-LIM-001"
        private const val BLOCK = "**Documentation changes**"

        private const val REL_OLD_DESCRIPTION = "A payment above the limit needs a second approver. Payments pre-approved by Treasury are exempt."
        private const val REL_NEW_DESCRIPTION = "A payment above the limit needs a second approver."

        private lateinit var report: DiffReport
        private lateinit var reportAgain: DiffReport
        private lateinit var withData: DiffReport

        @JvmStatic
        @BeforeAll
        fun renderAll() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            report = Explico.diff(Explico.load(oldPolicies), Explico.load(newPolicies))
            reportAgain = Explico.diff(Explico.load(oldPolicies), Explico.load(newPolicies))
            withData = Explico.diff(Explico.load(oldPolicies), Explico.load(newPolicies), DiffOptions(data = DataVersions(data("old"), data("new"))))
        }

        /** The bullet in `## What changed` whose line contains [id], with its `  - ` sub-bullets. */
        private fun bullet(report: DiffReport, id: String): List<String> {
            val lines = report.markdown.lines()
            val start = lines.indexOf("## What changed")
            assertThat(start).describedAs("a '## What changed' section").isGreaterThanOrEqualTo(0)
            val section = lines.drop(start + 1).takeWhile { !it.startsWith("## ") }
            val at = section.indexOfFirst { it.startsWith("- ") && it.contains(id) }
            assertThat(at).describedAs("a bullet for %s", id).isGreaterThanOrEqualTo(0)
            return listOf(section[at]) + section.drop(at + 1).takeWhile { it.startsWith("  - ") }
        }

        /** The lines of the control's card section in `## Changes`: from its anchor to the closing `---`. */
        private fun card(report: DiffReport, anchorId: String): List<String> {
            val lines = report.markdown.lines()
            val start = lines.indexOf("<a id=\"$anchorId\"></a>")
            assertThat(start).describedAs("a card anchored %s", anchorId).isGreaterThanOrEqualTo(0)
            return lines.drop(start).takeWhile { it != "---" }
        }

        /** The table lines of the `**Documentation changes**` block of [card] (empty when the block is absent). */
        private fun block(card: List<String>): List<String> {
            val at = card.indexOf(BLOCK)
            if (at < 0) return emptyList()
            return card.drop(at + 1).dropWhile { it.isBlank() }.takeWhile { it.startsWith("|") }
        }

        /** The strings of [key] in [change] -- an assertion failure, not an exception, when the key is missing. */
        private fun strings(change: JsonObject, key: String): List<String> {
            assertThat(change).describedAs("the JSON change").containsKey(key)
            return change.getValue(key).jsonArray.map { it.jsonPrimitive.content }
        }

        private fun jsonChange(report: DiffReport, id: String): JsonObject {
            val changes = Json.parseToJsonElement(report.json).jsonObject.getValue("changes").jsonArray.map { it.jsonObject }
            val change = changes.singleOrNull { it["controlId"]?.jsonPrimitive?.content == id }
            assertThat(change).describedAs("a JSON change for %s", id).isNotNull()
            return change!!
        }
    }

    @Nested
    @DisplayName("PAY-REL-001: logic, description, frameworks and criticality all changed")
    inner class LogicAndDocumentation {

        @Test
        @DisplayName("What changed ends the control's sub-bullets with the documentation fields, after the logic ones")
        fun subBullet() {
            val lines = bullet(report, REL)

            assertThat(lines.first()).endsWith("— logic changed")
            assertThat(lines).contains("  - condition removed: `payment ▸ treasury preapproved` is absent or false")
            assertThat(lines.last()).isEqualTo("  - documentation changed: description, frameworks, custom fields")
        }

        @Test
        @DisplayName("the section has a Documentation changes table with exactly the rows that differ, old and new cells as the DOCS_CHANGED table words them")
        fun table() {
            assertThat(block(card(report, "pay-rel-001"))).containsExactly(
                "| | Old | New |",
                "|---|---|---|",
                "| Description | $REL_OLD_DESCRIPTION | $REL_NEW_DESCRIPTION |",
                "| Frameworks | SOX ITGC-AC-2 | SOX ITGC-AC-2, PSD2 Art. 97 |",
                "| Custom fields | criticality: high, owner: Payments Risk | criticality: critical, owner: Payments Risk |",
            )
        }

        @Test
        @DisplayName("rows that did not change (Title) are not in the block, and neither is a row for a field that did not differ")
        fun unchangedRowsAbsent() {
            val rows = block(card(report, "pay-rel-001")).joinToString("\n")

            assertThat(rows).doesNotContain("| Title |").doesNotContain("| Authors |").doesNotContain("| Verdict |")
        }

        @Test
        @DisplayName("the block sits after the card and before the Source diff")
        fun placement() {
            val card = card(report, "pay-rel-001")

            assertThat(card.indexOf(BLOCK)).isGreaterThan(card.indexOfFirst { it.startsWith("**Effect of a match:**") }).isLessThan(card.indexOf("**Source diff**"))
            assertThat(card.count { it == BLOCK }).isEqualTo(1)
        }

        @Test
        @DisplayName("the JSON change carries fields, the same names, next to its details")
        fun json() {
            val change = jsonChange(report, REL)

            assertThat(strings(change, "fields")).containsExactly("description", "frameworks", "custom fields")
            assertThat(change).containsKey("details")
            assertThat(change.getValue("category").jsonPrimitive.content).isEqualTo("LOGIC_CHANGED")
        }
    }

    @Nested
    @DisplayName("PAY-SAN-001: logic changed, documentation identical")
    inner class LogicOnly {

        @Test
        @DisplayName("looks exactly as before: no documentation sub-bullet, no block, no JSON fields")
        fun nothingAdded() {
            assertThat(bullet(report, SAN)).containsExactly(
                "- [$SAN — Payments must be screened](#pay-san-001) — logic changed",
                "  - condition added: `payment ▸ beneficiary country` is not `\"GB\"`",
            )
            assertThat(card(report, "pay-san-001")).doesNotContain(BLOCK)
            assertThat(jsonChange(report, SAN)).doesNotContainKey("fields")
        }
    }

    @Nested
    @DisplayName("PAY-DOC-001: documentation only")
    inner class DocumentationOnly {

        @Test
        @DisplayName("DOCS_CHANGED is exactly as before: its bullet, all three base rows in its table, no new block, and fields in the JSON")
        fun unchanged() {
            assertThat(report.markdown).contains("- PAY-DOC-001 — Blocked currencies (updated) — documentation changed: title")
            assertThat(report.markdown).contains(
                "| Title | Blocked currencies | Blocked currencies (updated) |",
                "| Description | Payments in a blocked currency are denied. | Payments in a blocked currency are denied. |",
                "| Frameworks | ISO 27001 A.5.3 | ISO 27001 A.5.3 |",
            )
            assertThat(strings(jsonChange(report, DOC), "fields")).containsExactly("title")
            assertThat(jsonChange(report, DOC)).doesNotContainKey("details")
        }
    }

    @Nested
    @DisplayName("PAY-LIM-001: documentation changed in the Rego, limit changed in the data")
    inner class RaisedByData {

        @Test
        @DisplayName("without data it is DOCS_CHANGED as always; with data it is LOGIC_CHANGED AND keeps its documentation change")
        fun categories() {
            assertThat(report.markdown).contains("- PAY-LIM-001 — Payments above the currency limit are denied — documentation changed: description")
            assertThat(withData.entries.single { it.controlId == LIM }.category.name).isEqualTo("LOGIC_CHANGED")
        }

        @Test
        @DisplayName("the raised entry ends its sub-bullets with the data line then the documentation line")
        fun subBullets() {
            val lines = bullet(withData, LIM)

            assertThat(lines).contains("  - reference data changed: `data ▸ limits ▸ max`: `{\"EUR\":100000}` -> `{\"EUR\":50000}`")
            assertThat(lines.last()).isEqualTo("  - documentation changed: description")
        }

        @Test
        @DisplayName("its section lists the reference data, then the Documentation changes block with the one differing row, then the Source diff")
        fun block() {
            val card = card(withData, "pay-lim-001")

            assertThat(block(card)).containsExactly(
                "| | Old | New |",
                "|---|---|---|",
                "| Description | A payment above the limit for its currency is denied. | A payment above the limit for its currency is denied (limits are set by Treasury). |",
            )
            assertThat(card.indexOf("**Reference data**")).isLessThan(card.indexOf(BLOCK))
            assertThat(card.indexOf(BLOCK)).isLessThan(card.indexOf("**Source diff**"))
        }

        @Test
        @DisplayName("its JSON change carries both details and fields")
        fun json() {
            val change = jsonChange(withData, LIM)

            assertThat(strings(change, "fields")).containsExactly("description")
            assertThat(strings(change, "details").last()).isEqualTo("documentation changed: description")
        }
    }

    @Nested
    @DisplayName("What stays the same")
    inner class Unchanged {

        @Test
        @DisplayName("a report with no logic-and-documentation entry is byte-identical to the committed diff-all-categories golden")
        fun golden() {
            val dir = Path.of("src/test/resources/diff/diff-all-categories")
            val golden = Explico.diff(Explico.load(dir.resolve("old")), Explico.load(dir.resolve("new")))

            assertThat(golden.markdown).isEqualTo(Files.readString(dir.resolve("expected/diff-report.md")))
            assertThat(golden.markdown).doesNotContain(BLOCK)
        }

        @Test
        @DisplayName("the Summary counts are untouched by the new lines: 2 LOGIC_CHANGED and 2 DOCS_CHANGED without data, 3 and 1 with")
        fun categoriesUntouched() {
            assertThat(report.markdown).contains("| LOGIC_CHANGED | 2 |").contains("| DOCS_CHANGED | 2 |")
            assertThat(withData.markdown).contains("| LOGIC_CHANGED | 3 |").contains("| DOCS_CHANGED | 1 |")
        }

        @Test
        @DisplayName("two runs are byte-identical, Markdown and JSON")
        fun deterministic() {
            assertThat(reportAgain.markdown).isEqualTo(report.markdown)
            assertThat(reportAgain.json).isEqualTo(report.json)
        }
    }
}
