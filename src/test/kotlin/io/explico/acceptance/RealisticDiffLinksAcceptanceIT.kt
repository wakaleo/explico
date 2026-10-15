/**
 * Acceptance tests for links inside a diff report (follow-up to review item 18). The diff reuses
 * the normal card renderer for REMOVED/ADDED/LOGIC_CHANGED controls, so a reference to a rule that
 * has NO card in the diff (UNCHANGED or DOCS_CHANGED target) used to become a dead `](#anchor)`
 * link. Decided: such a reference renders without a link (same wording, plain backticked name);
 * a reference whose target card IS in the diff keeps its working link.
 *
 * NOT transcribed from the frozen acceptance-pack README: authored at the operator's request.
 * Fixtures: `src/test/resources/realistic/diff-links/{old,new}` plus the existing diff fixtures.
 */
package io.explico.acceptance

import io.explico.Explico
import io.explico.opa.OpaRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.nio.file.Path

@DisplayName("Realistic policies: links inside a diff report resolve")
class RealisticDiffLinksAcceptanceIT {

    companion object {
        private const val RESOURCES = "src/test/resources"

        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
        }

        private fun diff(old: String, new: String): String =
            Explico.diff(Explico.load(Path.of(RESOURCES, old)), Explico.load(Path.of(RESOURCES, new))).markdown

        private val linksDiff by lazy { diff("realistic/diff-links/old", "realistic/diff-links/new") }

        private fun unfenced(markdown: String): List<String> {
            var inFence = false
            return markdown.lines().filter { line ->
                if (line.trim().startsWith("```")) { inFence = !inFence; false } else !inFence
            }
        }

        /** The section for [controlId]: from its `## ` heading up to the next `---` separator. */
        private fun section(markdown: String, controlId: String): String {
            val lines = markdown.lines()
            val start = lines.indexOfFirst { it.startsWith("## $controlId") }
            assertThat(start).describedAs("a '## %s' heading", controlId).isGreaterThanOrEqualTo(0)
            val end = lines.drop(start + 1).indexOfFirst { it == "---" }
            return lines.drop(start).let { if (end < 0) it else it.take(end + 1) }.joinToString("\n")
        }

        private val LINK = Regex("""\]\(#([^)\s]+)\)""")

        private fun deadLinks(markdown: String): List<String> {
            val lines = unfenced(markdown)
            val anchors = lines.flatMap { Regex("""<a id="([^"]+)"></a>""").findAll(it).map { m -> m.groupValues[1] }.toList() }.toSet()
            return lines.flatMap { LINK.findAll(it).map { m -> m.groupValues[1] }.toList() }.filter { it !in anchors }
        }
    }

    @Nested
    @DisplayName("A — a reference to a rule with no card in the diff is not linked")
    inner class UnlinkedReference {

        private val card get() = section(linksDiff, "DL-001")

        @Test
        @DisplayName("no link to is_prod, but the reference is not lost")
        fun referenceKeptWithoutLink() {
            assertThat(unfenced(card).joinToString("\n")).doesNotContain("](#difflinks-is-prod)")
            val bullet = unfenced(card).filter { it.startsWith("- ") }.single { it.contains("`is_prod`") }
            assertThat(bullet).containsIgnoringCase("rule")
        }
    }

    @Nested
    @DisplayName("B — a reference to a rule that has its own card in the diff keeps its link")
    inner class LinkedReference {

        @Test
        @DisplayName("DL-002 links to is_eu, which carries exactly one anchor in the document")
        fun linkKept() {
            assertThat(unfenced(section(linksDiff, "DL-002")).joinToString("\n")).contains("[`is_eu`](#difflinks-is-eu)")
            assertThat(Regex("""<a id="difflinks-is-eu"></a>""").findAll(linksDiff).count()).isEqualTo(1)
        }
    }

    @Nested
    @DisplayName("C — every link in a diff report resolves to an anchor in the same document")
    inner class NoDeadLinks {

        @TestFactory
        fun everyLinkResolves(): List<DynamicTest> = listOf(
            "realistic/diff-links/old" to "realistic/diff-links/new",
            "realistic/diff-default/old" to "realistic/diff-default/changed",
            "realistic/diff-default/old" to "realistic/diff-default/removed",
            "diff/diff-all-categories/old" to "diff/diff-all-categories/new",
        ).map { (old, new) ->
            dynamicTest("$old -> $new") {
                assertThat(deadLinks(diff(old, new))).describedAs("links with no matching anchor").isEmpty()
            }
        }
    }

    @Nested
    @DisplayName("D — guard: REMOVED and ADDED cards still carry anchors")
    inner class AnchorsKept {

        @Test
        @DisplayName("diff-all-categories has an anchor for the removed REL-001 and the added REL-005")
        fun removedAndAddedAnchors() {
            val markdown = diff("diff/diff-all-categories/old", "diff/diff-all-categories/new")
            assertThat(markdown).contains("<a id=\"rel-001\"></a>").contains("<a id=\"rel-005\"></a>")
        }
    }
}
