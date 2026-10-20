/**
 * Acceptance tests for the diff report's `## What changed` section: a short, plain, mechanically derived list placed before the
 * full cards, for a change approver who should not have to read a source diff to learn what moved. Authored from a
 * compliance-owner review of the real report, not from the frozen README contract.
 *
 * What these pin (facade only: `Explico.load` x2 + `Explico.diff(old, new).markdown`):
 *  - Placement: after `## Summary`, before `## Changes`; absent when nothing changed; the Summary table itself is untouched
 *    (the CLI prints exactly that section).
 *  - One bullet per non-UNCHANGED control, in section order; label links to the card (REMOVED/ADDED/LOGIC_CHANGED) or is plain
 *    text (DOCS_CHANGED has no card).
 *  - LOGIC_CHANGED sub-bullets are derived, never guessed: conditions removed/added (as the card words them, unlinked), messages
 *    removed/added, body count, default outcome, code shown as source; and an honest fallback sentence when none applies.
 * Fixtures: `diff/diff-all-categories/{old,new}` and `diff/change-summary/{old,new}`.
 */
package io.explico.acceptance

import io.explico.Explico
import io.explico.opa.OpaRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path

@DisplayName("Realistic policies: diff change summary")
class RealisticDiffChangeSummaryAcceptanceIT {

    companion object {
        private const val HEADING = "## What changed"

        private val allCategories = Path.of("src/test/resources/diff/diff-all-categories")
        private val shapes = Path.of("src/test/resources/diff/change-summary")

        private lateinit var pack: String
        private lateinit var packAgain: String
        private lateinit var packIdentical: String
        private lateinit var shapesReport: String

        private fun diff(old: Path, new: Path): String = Explico.diff(Explico.load(old), Explico.load(new)).markdown

        @JvmStatic
        @BeforeAll
        fun renderReports() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            pack = diff(allCategories.resolve("old"), allCategories.resolve("new"))
            packAgain = diff(allCategories.resolve("old"), allCategories.resolve("new"))
            packIdentical = diff(allCategories.resolve("old"), allCategories.resolve("old"))
            shapesReport = diff(shapes.resolve("old"), shapes.resolve("new"))
        }

        /** The lines of the `## ` section whose heading is [heading], up to the next `## ` heading. */
        private fun section(markdown: String, heading: String): List<String> {
            val lines = markdown.lines()
            val start = lines.indexOf(heading)
            assertThat(start).describedAs("a '%s' heading", heading).isGreaterThanOrEqualTo(0)
            val end = lines.drop(start + 1).indexOfFirst { it.startsWith("## ") }
            return if (end < 0) lines.drop(start + 1) else lines.subList(start + 1, start + 1 + end)
        }

        /** The bullet whose line contains [label], followed by its indented `  - ` sub-bullets. */
        private fun entry(section: List<String>, label: String): List<String> {
            val at = section.indexOfFirst { it.startsWith("- ") && it.contains(label) }
            assertThat(at).describedAs("a bullet for %s", label).isGreaterThanOrEqualTo(0)
            return listOf(section[at]) + section.drop(at + 1).takeWhile { it.startsWith("  - ") }
        }
    }

    @Nested
    @DisplayName("diff-all-categories: REMOVED REL-001, ADDED REL-005, LOGIC_CHANGED REL-003, DOCS_CHANGED REL-004")
    inner class AllCategories {

        private val summary get() = section(pack, HEADING)

        @Test
        @DisplayName("the section sits after ## Summary and before ## Changes")
        fun placement() {
            val lines = pack.lines()
            assertThat(lines.indexOf(HEADING)).isGreaterThan(lines.indexOf("## Summary")).isLessThan(lines.indexOf("## Changes"))
            assertThat(lines.count { it == HEADING }).isEqualTo(1)
        }

        @Test
        @DisplayName("REMOVED and ADDED controls: a linked label and one word")
        fun removedAndAdded() {
            assertThat(summary).contains(
                "- [REL-001 — Production change approval](#rel-001) — removed",
                "- [REL-005 — New deployment window check](#rel-005) — added",
            )
        }

        @Test
        @DisplayName("DOCS_CHANGED has no card, so its label is plain text, and names exactly the fields that differ")
        fun docsChanged() {
            // Labelled by the NEW side's title (same precedent REMOVED/ADDED/LOGIC_CHANGED follow): the fixture's
            // real new title is "... (updated)" -- confirmed against the fixture itself, not assumed.
            assertThat(summary).contains("- REL-004 — Release governance evidence (updated) — documentation changed: title, description")
        }

        @Test
        @DisplayName("LOGIC_CHANGED REL-003: the one condition that changed, removed then added, as the card words it")
        fun logicChanged() {
            val rel003 = entry(summary, "REL-003")
            assertThat(rel003).containsExactly(
                "- [REL-003 — Artifact provenance](#rel-003) — logic changed",
                "  - condition removed: `artifact ▸ source branch` does not start with `\"release/\"`",
                "  - condition added: `artifact ▸ source branch` does not start with `\"releases/\"`",
            )
        }

        @Test
        @DisplayName("the renamed-but-unchanged REL-002 and the unchanged exempt_service are not listed")
        fun unchangedControlsAreNotListed() {
            assertThat(summary.joinToString("\n")).doesNotContain("REL-002").doesNotContain("exempt_service")
        }

        @Test
        @DisplayName("bullets follow the section order: REMOVED, ADDED, LOGIC_CHANGED, DOCS_CHANGED")
        fun order() {
            val text = summary.joinToString("\n")
            val positions = listOf("REL-001", "REL-005", "REL-003", "REL-004").map { text.indexOf(it) }
            assertThat(positions).doesNotContain(-1).isSorted()
        }

        @Test
        @DisplayName("the only links are the three card labels; no phrase carries a link")
        fun onlyTheLabelsAreLinks() {
            val links = Regex("\\]\\(([^)]*)\\)").findAll(summary.joinToString("\n")).map { it.groupValues[1] }.toList()
            assertThat(links).containsExactly("#rel-001", "#rel-005", "#rel-003")
        }

        @Test
        @DisplayName("every linked label resolves to a card anchor in the same report")
        fun linksResolve() {
            for (id in listOf("rel-001", "rel-005", "rel-003")) assertThat(pack).contains("<a id=\"$id\"></a>")
        }

        @Test
        @DisplayName("the Summary section is still only its table, and the cards and source diff are still there")
        fun restOfTheReportIsUnchanged() {
            assertThat(section(pack, "## Summary").filter { it.isNotBlank() }).allMatch { it.startsWith("|") }
            assertThat(pack).contains("**Source diff**").contains("## Changes")
        }

        @Test
        @DisplayName("two diffs of the same directories are byte-identical")
        fun deterministic() {
            assertThat(packAgain).isEqualTo(pack)
        }

        @Test
        @DisplayName("old compared with itself has no What changed section")
        fun noChangesNoSection() {
            assertThat(packIdentical).doesNotContain(HEADING).doesNotContain("## Changes")
        }
    }

    @Nested
    @DisplayName("change-summary: one rule per LOGIC_CHANGED shape, plus a frameworks-only DOCS_CHANGED")
    inner class Shapes {

        private val summary get() = section(shapesReport, HEADING)

        @Test
        @DisplayName("CS-001 gains a body: the new condition, the new message and the body count")
        fun gainedBody() {
            assertThat(entry(summary, "CS-001")).containsExactly(
                "- [CS-001 — Gains a body](#cs-001) — logic changed",
                "  - condition added: `b` is `2`",
                "  - message added: \"b is two\"",
                "  - bodies: 1 -> 2",
            )
        }

        @Test
        @DisplayName("CS-002 changes only its message: the old and the new wording, no condition lines")
        fun messageOnly() {
            assertThat(entry(summary, "CS-002")).containsExactly(
                "- [CS-002 — Message only](#cs-002) — logic changed",
                "  - message removed: \"old wording\"",
                "  - message added: \"new wording\"",
            )
        }

        @Test
        @DisplayName("CS-003 changes only its default outcome")
        fun defaultOutcome() {
            assertThat(entry(summary, "CS-003")).containsExactly(
                "- [CS-003 — Default outcome](#cs-003) — logic changed",
                "  - default outcome changed",
            )
        }

        @Test
        @DisplayName("CS-004 changes only a condition inside an every block, which is named like any other condition (rendered since spec §14.49)")
        fun everyBlockOnly() {
            assertThat(entry(summary, "CS-004")).containsExactly(
                "- [CS-004 — Every block only](#cs-004) — logic changed",
                "  - condition removed: `items ▸ [each item] ▸ ok` is `true`",
                "  - condition added: `items ▸ [each item] ▸ ok` is `false`",
            )
        }

        @Test
        @DisplayName("CS-005 regroups the same conditions across bodies: the honest structure-only sentence, never a guess")
        fun structureOnly() {
            assertThat(entry(summary, "CS-005")).containsExactly(
                "- [CS-005 — Structure only](#cs-005) — logic changed",
                "  - the rule's structure changed; see the source diff below",
            )
        }

        @Test
        @DisplayName("CS-006 changes only its frameworks")
        fun frameworksOnly() {
            assertThat(summary).contains("- CS-006 — Frameworks only — documentation changed: frameworks")
        }

        @Test
        @DisplayName("bullets are in section order, then control id")
        fun order() {
            val text = summary.joinToString("\n")
            assertThat(listOf("CS-001", "CS-002", "CS-003", "CS-004", "CS-005", "CS-006").map { text.indexOf(it) })
                .doesNotContain(-1).isSorted()
        }
    }
}
