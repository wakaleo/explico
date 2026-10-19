/**
 * Acceptance tests for package-level METADATA passthrough: a `# METADATA` block with `scope: package` can carry the same
 * `authors`, `related_resources` and extra `custom:` keys a rule can, and the package page shows them (previously only the
 * title and description appeared). Same three lines, same wording and order as a card. Authored from a review of the real
 * output, not from the frozen README contract; the acceptance pack declares no package-scope annotations.
 * Fixtures: `realistic/metadata-passthrough-package/policies` and `.../only-extras/policies`.
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

@DisplayName("Realistic policies: package-level METADATA passthrough")
class RealisticPackageMetadataAcceptanceIT {

    companion object {
        private val base = Path.of("src/test/resources/realistic/metadata-passthrough-package")
        private val full = base.resolve("policies")
        private val onlyExtras = base.resolve("only-extras/policies")

        private lateinit var fullFiles: Map<String, String>
        private lateinit var fullFilesAgain: Map<String, String>
        private lateinit var extrasFiles: Map<String, String>

        private fun render(dir: Path): Map<String, String> = Explico.render(Explico.load(dir), dir).files

        @JvmStatic
        @BeforeAll
        fun renderDocuments() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            fullFiles = render(full)
            fullFilesAgain = render(full)
            extrasFiles = render(onlyExtras)
        }
    }

    @Nested
    @DisplayName("Package with title, description, authors, related resources and custom keys")
    inner class FullPackage {

        private val page get() = fullFiles.getValue("pkgmeta.md")

        @Test
        @DisplayName("Authors line: name <email>")
        fun authors() {
            assertThat(page).contains("*Authors: Pat Package <pat@example.com>*")
        }

        @Test
        @DisplayName("Related resources line: a link labelled by its description")
        fun relatedResources() {
            assertThat(page).contains("*Related resources: [Package runbook](https://example.com/package-runbook)*")
        }

        @Test
        @DisplayName("Custom line: scalar and scalar-list keys, sorted, comma-joined")
        fun custom() {
            assertThat(page).contains("*Custom: owner: platform-team, tags: tier1, production*")
        }

        @Test
        @DisplayName("order: title, description, Authors, Related resources, Custom, then the first card")
        fun order() {
            val positions = listOf(
                "**Ownership package**",
                "Package-level METADATA with authors",
                "*Authors:",
                "*Related resources:",
                "*Custom:",
                "<a id=\"pm-001\"></a>",
            ).map { page.indexOf(it) }
            assertThat(positions).doesNotContain(-1).isSorted()
        }

        @Test
        @DisplayName("the nested-object custom key is skipped, never guessed at")
        fun nestedKeySkipped() {
            assertThat(page).doesNotContain("excluded").doesNotContain("not applicable")
        }

        @Test
        @DisplayName("package metadata does not leak into the rule's card")
        fun noLeakIntoCards() {
            val card = page.substringAfter("<a id=\"pm-001\"></a>")
            assertThat(card).doesNotContain("Authors:").doesNotContain("Related resources:").doesNotContain("Custom:")
        }

        @Test
        @DisplayName("the index is unchanged: no authors")
        fun indexUnchanged() {
            assertThat(fullFiles.getValue("index.md")).doesNotContain("Authors")
        }

        @Test
        @DisplayName("rendering twice is byte-identical")
        fun deterministic() {
            assertThat(fullFilesAgain).isEqualTo(fullFiles)
        }
    }

    @Nested
    @DisplayName("Package with ONLY authors (no title, no description)")
    inner class OnlyExtras {

        private val page get() = extrasFiles.getValue("onlyextras.md")

        @Test
        @DisplayName("the Authors line still renders, with no bold title line")
        fun authorsWithoutTitle() {
            assertThat(page).contains("*Authors: Ola Only*")
            // Only the package header (everything before the first card) -- the rule's own card has bold lines.
            assertThat(page.substringBefore("<a id=\"oe-001\"></a>")).doesNotContain("**")
        }

        @Test
        @DisplayName("the line comes before the first card")
        fun beforeFirstCard() {
            val authorsAt = page.indexOf("*Authors:")
            assertThat(authorsAt).describedAs("an Authors line on the page").isGreaterThanOrEqualTo(0)
            assertThat(authorsAt).isLessThan(page.indexOf("<a id=\"oe-001\"></a>"))
        }
    }
}
