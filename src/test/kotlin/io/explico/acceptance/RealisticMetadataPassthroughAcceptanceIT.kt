/**
 * Acceptance tests for METADATA passthrough: standard `authors`/`related_resources` (opa itself already parses
 * them; explico's own decoding just didn't model them) and arbitrary `custom:` keys beyond `control-id`/
 * `frameworks` (already preserved by opa -- `custom` is unstructured to it -- but silently dropped by explico's
 * decoding) now show up on the card, so ownership and policy links are visible. Authored from a review of the
 * real output, not from the frozen README contract; the acceptance pack itself declares none of these fields.
 * Fixture: `src/test/resources/realistic/metadata-passthrough/policies/meta.rego`.
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

@DisplayName("Realistic policies: METADATA passthrough")
class RealisticMetadataPassthroughAcceptanceIT {

    companion object {
        private val policies = Path.of("src/test/resources/realistic/metadata-passthrough/policies")

        private lateinit var files: Map<String, String>
        private lateinit var filesAgain: Map<String, String>

        private fun render(): Map<String, String> = Explico.render(Explico.load(policies), policies).files

        @JvmStatic
        @BeforeAll
        fun renderDocuments() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            files = render()
            filesAgain = render()
        }

        private val doc get() = files.getValue("meta-ownership.md")

        private fun card(markdown: String, id: String): String =
            markdown.substringAfter("<a id=\"$id\"></a>").substringBefore("<a id=\"")
    }

    @Nested
    @DisplayName("META-001: full METADATA (authors, related_resources, extra custom keys)")
    inner class FullMetadata {

        private val card get() = card(doc, "meta-001")

        @Test
        @DisplayName("Authors line: name <email> for the parsed string author, bare name for the object author")
        fun authorsLine() {
            assertThat(card).contains("*Authors: Ann Owner <ann@example.com>, Bob Reviewer*")
        }

        @Test
        @DisplayName("Authors, Related resources, Custom follow Frameworks in that order, then the blank line and description")
        fun placement() {
            val lines = card.lines()
            val frameworksAt = lines.indexOfFirst { it.startsWith("*Frameworks:") }
            assertThat(lines[frameworksAt + 1]).startsWith("*Authors:")
            assertThat(lines[frameworksAt + 2]).startsWith("*Related resources:")
            assertThat(lines[frameworksAt + 3]).startsWith("*Custom:")
            assertThat(lines[frameworksAt + 4]).isEmpty()
        }

        @Test
        @DisplayName("Related resources line: a description-labelled link, then a bare-URL link labelled by itself")
        fun relatedResourcesLine() {
            assertThat(card).contains(
                "*Related resources: [Runbook](https://example.com/runbook), " +
                    "[https://example.com/ticket/123](https://example.com/ticket/123)*",
            )
        }

        @Test
        @DisplayName("Custom line: the two extra scalar/list keys, sorted alphabetically, comma-joined")
        fun customLine() {
            assertThat(card).contains("*Custom: owner: platform-team, tags: tier1, production*")
        }

        @Test
        @DisplayName("the nested-object custom key is silently skipped, never guessed at")
        fun nestedCustomKeyIsSkipped() {
            assertThat(card).doesNotContain("excluded").doesNotContain("not applicable")
        }
    }

    @Nested
    @DisplayName("META-002: a plain rule declares none of the three, and gets none of the three lines")
    inner class PlainMetadata {

        private val card get() = card(doc, "meta-002")

        @Test
        fun noAuthorsRelatedResourcesOrCustomLines() {
            assertThat(card).doesNotContain("Authors:").doesNotContain("Related resources:").doesNotContain("Custom:")
        }
    }

    @Nested
    @DisplayName("Determinism")
    inner class Determinism {

        @Test
        fun renderingTwiceIsByteIdentical() {
            assertThat(filesAgain).isEqualTo(files)
        }
    }
}
