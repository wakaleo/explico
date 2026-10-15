/**
 * Acceptance tests for how a rule's `default` is shown and diffed when the rule name is split
 * across several cards (the "default repeated on every card" and "LOGIC_CHANGED with an empty
 * source diff" findings from the corpus re-render).
 *
 * NOT transcribed from the frozen acceptance-pack README: authored from the review at the
 * operator's request, with the operator's chosen design: a split rule's default becomes ONE entry
 * of its own, shown once; a rule name with a single card keeps its default on that card. In both
 * layouts a changed default must show up as a real before/after in the diff, never as a changed
 * control with an unchanged source diff. Fixtures: `src/test/resources/realistic/diff-default/`
 * (`old`, `changed` = defaults flipped false→true, `removed` = authz's default deleted) and the
 * existing `realistic/policies/authz.rego`. Heading/label wording is deliberately loose.
 */
package io.explico.acceptance

import io.explico.Explico
import io.explico.diff.DiffCategory
import io.explico.opa.OpaRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path

@DisplayName("Realistic policies: defaults on split rules (diff and render)")
class RealisticDefaultsAcceptanceIT {

    companion object {
        private const val BASE = "src/test/resources/realistic"
        private lateinit var corpus: Map<String, String>
        private lateinit var oldDocs: Map<String, String>

        private fun load(dir: String) = Explico.load(Path.of("$BASE/$dir"))

        private fun render(dir: String): Map<String, String> {
            val policyDir = Path.of("$BASE/$dir")
            return Explico.render(Explico.load(policyDir), policyDir).files
        }

        @JvmStatic
        @BeforeAll
        fun renderDocuments() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            corpus = render("policies")
            oldDocs = render("diff-default/old")
        }

        private fun headings(markdown: String) = markdown.lines().filter { it.startsWith("## ") }

        /** The `## ` section whose heading line contains [marker], up to the next `## ` heading. */
        private fun section(markdown: String, marker: String): String {
            val lines = markdown.lines()
            val start = lines.indexOfFirst { it.startsWith("## ") && it.contains(marker) }
            assertThat(start).describedAs("a '## ' heading containing %s", marker).isGreaterThanOrEqualTo(0)
            val end = lines.drop(start + 1).indexOfFirst { it.startsWith("## ") }
            return lines.drop(start).let { if (end < 0) it else it.take(end + 1) }.joinToString("\n")
        }

        /** The section whose heading line is exactly [heading] (so a longer heading sharing its prefix, e.g. a default entry, can't be picked instead). */
        private fun sectionWithExactHeading(markdown: String, heading: String): String {
            val lines = markdown.lines()
            val start = lines.indexOf(heading)
            assertThat(start).describedAs("a heading line exactly %s", heading).isGreaterThanOrEqualTo(0)
            val end = lines.drop(start + 1).indexOfFirst { it.startsWith("## ") }
            return lines.drop(start).let { if (end < 0) it else it.take(end + 1) }.joinToString("\n")
        }

        private fun count(markdown: String, needle: String) = markdown.lines().count { it.contains(needle) }

        /** The report split into one text block per entry of [category] (each starts at its `**CATEGORY**` line). */
        private fun entriesOf(report: String, category: String): List<String> =
            report.split(Regex("(?m)^\\*\\*$category\\*\\*$")).drop(1).map { it.substringBefore("\n---").let { s -> s } }

        private fun fencedAfterSourceDiff(entry: String): String =
            entry.substringAfter("**Source diff**", "").substringAfter("```rego", "").substringBefore("```")
    }

    @Nested
    @DisplayName("Render: a split rule's default is shown once, in its own entry")
    inner class SplitRender {

        @Test
        @DisplayName("realistic authz.md: exactly one 'Default outcome' line, and none inside AZ-001, AZ-002 or the unannotated allow card")
        fun shownOnce() {
            val md = corpus["authz.md"] ?: ""
            assertThat(count(md, "Default outcome")).isEqualTo(1)
            for (marker in listOf("AZ-001", "AZ-002")) assertThat(section(md, marker)).doesNotContain("Default outcome")
            assertThat(sectionWithExactHeading(md, "## authz.allow")).doesNotContain("Default outcome")
        }

        @Test
        @DisplayName("that one entry has its own heading mentioning default, and states the value false")
        fun ownHeading() {
            val md = corpus["authz.md"] ?: ""
            val defaultHeadings = headings(md).filter { it.contains("default", ignoreCase = true) }
            assertThat(defaultHeadings).hasSize(1)
            assertThat(defaultHeadings.single()).contains("authz.allow")
            val body = section(md, defaultHeadings.single().removePrefix("## "))
            assertThat(body).contains("Default outcome").contains("false")
        }

        @Test
        @DisplayName("fixture authz (two annotated cards, no unannotated one) also shows its default once")
        fun fixtureSplit() {
            val md = oldDocs["authz.md"] ?: ""
            assertThat(count(md, "Default outcome")).isEqualTo(1)
            assertThat(headings(md).count { it.contains("default", ignoreCase = true) }).isEqualTo(1)
        }
    }

    @Nested
    @DisplayName("Render: a rule name with a single card keeps its default on that card")
    inner class SingleCardRender {

        @Test
        @DisplayName("single.md: the allow card carries 'Default outcome' and there is no separate default heading")
        fun staysOnTheCard() {
            val md = oldDocs["single.md"] ?: ""
            assertThat(section(md, "single.allow")).contains("Default outcome").contains("false")
            assertThat(headings(md).filter { it.contains("default", ignoreCase = true) }).isEmpty()
            assertThat(count(md, "Default outcome")).isEqualTo(1)
        }
    }

    @Nested
    @DisplayName("Diff: a changed default is reported once, with a real before/after")
    inner class ChangedDefault {

        private val report by lazy { Explico.diff(load("diff-default/old"), load("diff-default/changed")) }

        @Test
        @DisplayName("exactly two entries change (the split rule's default entry, and single.allow); AZ-001 and AZ-002 are UNCHANGED")
        fun entries() {
            val changed = report.entries.filter { it.category != DiffCategory.UNCHANGED }
            assertThat(changed).hasSize(2)
            assertThat(changed.map { it.category }.toSet()).containsExactly(DiffCategory.LOGIC_CHANGED)
            assertThat(report.entries.filter { it.controlId != null }.map { it.category }.toSet()).containsExactly(DiffCategory.UNCHANGED)
            assertThat(report.markdown).contains("| LOGIC_CHANGED | 2 |").contains("| UNCHANGED | 2 |")
        }

        @Test
        @DisplayName("neither AZ-001 nor AZ-002 appears in the report body")
        fun splitCardsAbsent() {
            assertThat(report.markdown.substringAfter("## Changes")).doesNotContain("AZ-001").doesNotContain("AZ-002")
        }

        @Test
        @DisplayName("each changed section's source diff shows a removed line with false and an added line with true")
        fun realBeforeAfter() {
            val sections = entriesOf(report.markdown, "LOGIC_CHANGED")
            assertThat(sections).hasSize(2)
            for (section in sections) {
                val diff = fencedAfterSourceDiff(section).lines()
                assertThat(diff.filter { it.startsWith("- ") && it.contains("false") }).describedAs("removed line").hasSize(1)
                assertThat(diff.filter { it.startsWith("+ ") && it.contains("true") }).describedAs("added line").hasSize(1)
            }
        }

        @Test
        @DisplayName("the report shows 'Default outcome' exactly twice, once per changed entry")
        fun noRepeatedDefault() {
            assertThat(count(report.markdown, "Default outcome")).isEqualTo(2)
        }
    }

    @Nested
    @DisplayName("Diff: removing and re-adding a split rule's default")
    inner class RemovedAndAddedDefault {

        @Test
        @DisplayName("removing it is one REMOVED entry; AZ-001, AZ-002 and single.allow stay UNCHANGED")
        fun removed() {
            val report = Explico.diff(load("diff-default/old"), load("diff-default/removed"))
            assertThat(report.entries.count { it.category == DiffCategory.REMOVED }).isEqualTo(1)
            assertThat(report.entries.count { it.category == DiffCategory.UNCHANGED }).isEqualTo(3)
            assertThat(report.markdown).contains("| REMOVED | 1 |").contains("| LOGIC_CHANGED | 0 |")
            assertThat(report.markdown.substringAfter("## Changes")).doesNotContain("AZ-001").doesNotContain("AZ-002")
        }

        @Test
        @DisplayName("adding it back is one ADDED entry and nothing else changes")
        fun added() {
            val report = Explico.diff(load("diff-default/removed"), load("diff-default/old"))
            assertThat(report.entries.count { it.category == DiffCategory.ADDED }).isEqualTo(1)
            assertThat(report.entries.count { it.category == DiffCategory.UNCHANGED }).isEqualTo(3)
            assertThat(report.markdown).contains("| ADDED | 1 |").contains("| LOGIC_CHANGED | 0 |")
        }
    }

    @Nested
    @DisplayName("Index: the default entry is listed with the rules that have no control id")
    inner class IndexRow {

        @Test
        @DisplayName("fixture index lists the split default entry (an `allow` row after the id-less heading) and single.allow")
        fun listed() {
            val lines = (oldDocs["index.md"] ?: "").lines()
            val heading = lines.indexOfFirst { it.startsWith("## ") }
            assertThat(heading).isGreaterThanOrEqualTo(0)
            val rows = lines.drop(heading + 1).filter { it.startsWith("|") && it.contains("`") }
            assertThat(rows.filter { it.contains("`authz`") && it.contains("`allow`") }).hasSize(1)
            assertThat(rows.filter { it.contains("`single`") && it.contains("`allow`") }).hasSize(1)
        }
    }
}
