/**
 * Acceptance tests for worked examples on rules that share one name (`deny contains msg` repeated,
 * each with its own METADATA). Authored from the showcase finding, not the frozen README contract:
 * each card must show only the messages ITS bodies produce, and examples that cannot be attributed
 * to one card must be omitted, never copied onto every card.
 */
package io.explico.acceptance

import io.explico.Explico
import io.explico.opa.OpaRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path

@DisplayName("Realistic policies: worked examples for same-named rules")
class RealisticSameNameExamplesAcceptanceIT {

    companion object {
        private lateinit var doc: String

        @JvmStatic
        @BeforeAll
        fun renderDocument() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            val dir = Path.of("src/test/resources/realistic/same-name")
            val examples = Explico.loadExamples(dir.resolve("examples"))
            doc = Explico.render(Explico.load(dir.resolve("policies")), dir.resolve("policies"), examples).files.getValue("samename.md")
        }

        private fun section(marker: String): String {
            val lines = doc.lines()
            val start = lines.indexOfFirst { it.startsWith("## ") && it.contains(marker) }
            assertThat(start).describedAs("a '## ' heading containing %s", marker).isGreaterThanOrEqualTo(0)
            val end = lines.drop(start + 1).indexOfFirst { it.startsWith("## ") }
            return lines.drop(start).take(if (end < 0) lines.size else end + 1).joinToString("\n")
        }
    }

    @Test
    @DisplayName("a card shows only the messages its own bodies produce")
    fun cardShowsOnlyItsOwnMessages() {
        val encrypted = section("SN-001")
        assertThat(encrypted).contains("bucket logs is not encrypted").doesNotContain("production bucket is public")
        val private = section("SN-002")
        assertThat(private).contains("production bucket is public").doesNotContain("is not encrypted")
    }

    @Test
    @DisplayName("each card lists every fixture exactly once, with its own verdict")
    fun eachFixtureOncePerCard() {
        val encrypted = section("SN-001")
        assertThat(Regex("\\*\\*bad bucket\\*\\*").findAll(encrypted).count()).isEqualTo(1)
        assertThat(encrypted).contains("**bad bucket** — ❌ denied").contains("**good bucket** — ✅ allowed")
    }

    @Test
    @DisplayName("boolean same-named rules cannot be attributed, so their cards carry no worked examples")
    fun booleanSameNamedRulesOmitExamples() {
        assertThat(section("SN-003")).doesNotContain("Worked examples")
        assertThat(section("SN-004")).doesNotContain("Worked examples")
    }
}
