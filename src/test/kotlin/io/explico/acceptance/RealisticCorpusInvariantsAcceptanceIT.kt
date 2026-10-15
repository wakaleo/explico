/**
 * Cross-cutting invariants swept over EVERY realistic fixture directory (review item 18) -- not
 * per-construct assertions like the sibling Realistic*ITs. One dynamic test per directory, so a
 * failure names the directory.
 *
 * NOT transcribed from the frozen acceptance-pack README: authored from the review at the
 * operator's request.
 *
 * (A) no crash / non-blank output; (B) determinism (CLAUDE.md hard rule 3); (C) every rendered
 * link resolves -- to an existing document AND to an anchor in it, either an explicit HTML anchor
 * or the GitHub slug of a `## ` heading; (D) coverage-footer arithmetic; (E) heading uniqueness
 * within a document; (F) no internal model names or opa gensyms leaking into prose (the bare word
 * `null` is allowed only inside the legitimate `is null` / `is not null` type-check phrasing).
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

@DisplayName("Realistic corpus invariants (review item 18)")
class RealisticCorpusInvariantsAcceptanceIT {

    companion object {
        private const val ROOT = "src/test/resources/realistic"

        /** Directory (relative to [ROOT]) -> optional examples directory (relative to [ROOT]). */
        private val CORPUS: Map<String, String?> = linkedMapOf(
            "policies" to null,
            "functions" to null,
            "builtins" to null,
            "rulevalues" to null,
            "messages/policies" to "messages/examples",
            "diff-default/old" to null,
            "diff-default/changed" to null,
            "diff-default/removed" to null,
        )

        private val DIFF_PAIRS = listOf("diff-default/old" to "diff-default/changed", "diff-default/old" to "diff-default/removed")

        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
        }

        private fun render(dir: String): Map<String, String> {
            val policyDir = Path.of(ROOT, dir)
            val examples = CORPUS[dir]?.let { Explico.loadExamples(Path.of(ROOT, it)) }
            return Explico.render(Explico.load(policyDir), policyDir, examples).files
        }

        private val rendered = HashMap<String, Map<String, String>>()
        private fun rendered(dir: String): Map<String, String> = rendered.getOrPut(dir) { render(dir) }

        private fun perDirectory(check: (String, Map<String, String>) -> Unit): List<DynamicTest> =
            CORPUS.keys.map { dir -> dynamicTest(dir) { check(dir, rendered(dir)) } }

        /** Lines outside fenced code blocks. */
        private fun unfenced(markdown: String): List<String> {
            var inFence = false
            return markdown.lines().filter { line ->
                if (line.trim().startsWith("```")) { inFence = !inFence; false } else !inFence
            }
        }

        private fun githubSlug(heading: String): String =
            heading.lowercase().filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }.replace(' ', '-')

        private val LINK = Regex("""\]\((?:([^)#\s]+\.md))?#([^)\s]+)\)""")

        private data class Link(val from: String, val file: String, val anchor: String)

        private fun linksIn(docs: Map<String, String>): List<Link> = docs.flatMap { (name, md) ->
            unfenced(md).flatMap { line -> LINK.findAll(line).map { Link(name, it.groupValues[1].ifEmpty { name }, it.groupValues[2]) }.toList() }
        }

        private fun anchorsIn(markdown: String): Set<String> {
            val lines = unfenced(markdown)
            val explicit = lines.flatMap { Regex("""<a\s+(?:id|name)="([^"]+)"""").findAll(it).map { m -> m.groupValues[1] }.toList() }
            val headings = lines.filter { it.startsWith("## ") }.map { githubSlug(it.removePrefix("## ").trim()) }
            return (explicit + headings).toSet()
        }

        private fun headings(markdown: String): List<String> = unfenced(markdown).filter { it.startsWith("## ") }.map { it.trim() }
    }

    @Nested
    @DisplayName("A — every directory loads and renders to non-blank documents")
    inner class NoCrash {
        @TestFactory
        fun rendersWithoutCrash(): List<DynamicTest> = perDirectory { _, docs ->
            assertThat(docs).isNotEmpty()
            docs.forEach { (name, md) -> assertThat(md).describedAs(name).isNotBlank() }
        }
    }

    @Nested
    @DisplayName("B — determinism: identical input renders byte-identically")
    inner class Determinism {
        @TestFactory
        fun renderTwiceIsByteIdentical(): List<DynamicTest> = perDirectory { dir, first ->
            val second = render(dir)
            assertThat(second.keys.toList()).isEqualTo(first.keys.toList())
            first.forEach { (name, md) -> assertThat(second.getValue(name)).describedAs("%s/%s", dir, name).isEqualTo(md) }
        }

        @TestFactory
        fun diffTwiceIsByteIdentical(): List<DynamicTest> = DIFF_PAIRS.map { (old, new) ->
            dynamicTest("$old vs $new") {
                fun once() = Explico.diff(Explico.load(Path.of(ROOT, old)), Explico.load(Path.of(ROOT, new))).markdown
                assertThat(once()).isNotBlank().isEqualTo(once())
            }
        }
    }

    @Nested
    @DisplayName("C — every rendered link resolves to a document and an anchor")
    inner class LinkIntegrity {
        @Test
        fun theCorpusActuallyContainsLinks() {
            assertThat(CORPUS.keys.sumOf { linksIn(rendered(it)).size }).isGreaterThan(0)
        }

        @TestFactory
        fun targetDocumentExists(): List<DynamicTest> = perDirectory { _, docs ->
            val missing = linksIn(docs).filter { it.file !in docs }
            assertThat(missing).describedAs("links to documents that were not rendered").isEmpty()
        }

        @TestFactory
        fun anchorResolvesInTargetDocument(): List<DynamicTest> = perDirectory { _, docs ->
            val anchors = docs.mapValues { anchorsIn(it.value) }
            val broken = linksIn(docs).filter { it.file in docs && it.anchor !in anchors.getValue(it.file) }
            assertThat(broken).describedAs("links whose anchor matches neither an explicit <a id> nor a heading slug").isEmpty()
        }
    }

    @Nested
    @DisplayName("D — coverage footer arithmetic")
    inner class CoverageArithmetic {
        private val cardFooter = Regex("""^\*Rendering coverage: (\d+) of (\d+) conditions""")
        private val packageFooter = Regex("""^\*Package rendering coverage: (\d+) of (\d+) conditions \((\d+)%\)\*""")

        @TestFactory
        fun footersAreConsistent(): List<DynamicTest> = perDirectory { _, docs ->
            docs.filterKeys { it != "index.md" }.forEach { (name, md) ->
                val lines = unfenced(md)
                val cards = lines.mapNotNull { cardFooter.find(it) }.map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
                cards.forEach { (x, y) -> assertThat(x).describedAs("%s card %s of %s", name, x, y).isBetween(0, y) }
                val pkg = lines.mapNotNull { packageFooter.find(it) }.single()
                assertThat(pkg.groupValues[1].toInt()).describedAs("%s package rendered", name).isEqualTo(cards.sumOf { it.first })
                assertThat(pkg.groupValues[2].toInt()).describedAs("%s package total", name).isEqualTo(cards.sumOf { it.second })
                val percent = pkg.groupValues[3].toInt()
                assertThat(percent).isBetween(0, 100)
                if (pkg.groupValues[2].toInt() == 0) assertThat(percent).isEqualTo(100)
            }
        }
    }

    @Nested
    @DisplayName("E — no two headings in one document are identical")
    inner class HeadingUniqueness {
        @TestFactory
        fun headingsAreUnique(): List<DynamicTest> = perDirectory { _, docs ->
            docs.forEach { (name, md) ->
                val duplicates = headings(md).groupingBy { it }.eachCount().filterValues { it > 1 }.keys
                assertThat(duplicates).describedAs("duplicate '## ' headings in %s", name).isEmpty()
            }
        }
    }

    @Nested
    @DisplayName("F — internal model names and opa gensyms never leak into prose")
    inner class NoInternalLeaks {
        private val forbidden = listOf(
            Regex("""(?<!is )(?<!is not )\bnull\b"""), Regex("""\bundefined\b"""), Regex("""Operand\."""), Regex("""Condition\."""),
            Regex("""RuleReference\("""), Regex("""\$\d"""),
        )

        @TestFactory
        fun proseHasNoLeaks(): List<DynamicTest> = perDirectory { _, docs ->
            docs.forEach { (name, md) ->
                val prose = unfenced(md).joinToString("\n").replace(Regex("`[^`]*`"), "")
                forbidden.forEach { pattern ->
                    assertThat(pattern.findAll(prose).map { it.value }.toList()).describedAs("%s: %s", name, pattern.pattern).isEmpty()
                }
            }
        }
    }
}
