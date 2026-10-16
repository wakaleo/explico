/**
 * Acceptance tests for promoting `json.is_valid(x)` to a rendered condition-position builtin
 * (coverage-audit backlog: unknown builtins). Verified with real `opa eval`: it takes a string and
 * returns `false` (not undefined) for invalid JSON AND for a non-string value, so a unary-predicate
 * wording -- "is valid JSON" / "is not valid JSON" -- is faithful in both polarities. It joins the
 * unary builtin family (`is_string` and friends). `json.marshal` is deliberately NOT promoted: it
 * must still fall back as a helper assignment.
 *
 * NOT transcribed from the frozen acceptance-pack README: authored at the operator's request.
 * Fixture: `src/test/resources/realistic/json-builtins/json.rego`.
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

@DisplayName("Realistic policies: json.is_valid as a rendered builtin")
class RealisticJsonBuiltinsAcceptanceIT {

    companion object {
        private lateinit var doc: String

        @JvmStatic
        @BeforeAll
        fun renderDocument() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            val dir = Path.of("src/test/resources/realistic/json-builtins")
            doc = Explico.render(Explico.load(dir), dir).files.getValue("jsonbuiltins.md")
        }

        private fun card(name: String): String {
            val lines = doc.lines()
            val start = lines.indexOf("## jsonbuiltins.$name")
            assertThat(start).describedAs("heading ## jsonbuiltins.%s", name).isGreaterThanOrEqualTo(0)
            val end = lines.drop(start + 1).indexOfFirst { it.startsWith("## ") }
            return lines.drop(start).let { if (end < 0) it else it.take(end + 1) }.joinToString("\n")
        }

        private fun bullets(markdown: String): List<String> {
            var inFence = false
            return markdown.lines().filter { line ->
                if (line.trim().startsWith("```")) { inFence = !inFence; false } else !inFence
            }.filter { it.startsWith("- ") }
        }

        private fun footer(card: String): String = card.lines().single { it.startsWith("*Rendering coverage:") }
    }

    @Nested
    @DisplayName("A — json.is_valid on a path")
    inner class ValidOnAPath {
        @Test
        fun rendersAsIsValidJson() {
            val c = card("valid_payload")
            assertThat(bullets(c).single()).contains("payload").contains("is valid JSON").doesNotContain("not valid")
            assertThat(footer(c)).contains("1 of 1 conditions").doesNotContain("unrendered value")
            assertThat(c).doesNotContain("⚠")
        }
    }

    @Nested
    @DisplayName("B — not json.is_valid")
    inner class NegatedValid {
        @Test
        fun rendersAsIsNotValidJson() {
            val c = card("invalid_payload")
            assertThat(bullets(c).single()).contains("is not valid JSON")
            assertThat(footer(c)).contains("1 of 1 conditions")
            assertThat(c).doesNotContain("⚠")
        }
    }

    @Nested
    @DisplayName("C — json.is_valid over a wildcard element")
    inner class WildcardElement {
        @Test
        fun carriesTheAnyOfPrefix() {
            val c = card("any_item")
            assertThat(bullets(c).single()).startsWith("- any of:").contains("items").contains("is valid JSON")
            assertThat(c).doesNotContain("⚠")
        }
    }

    @Nested
    @DisplayName("D — json.is_valid on a bare function parameter")
    inner class BareParameter {
        @Test
        fun rendersTheParameterPhrase() {
            val c = card("via_param")
            assertThat(bullets(c).single()).contains("parameter `p`").contains("is valid JSON")
            assertThat(c).doesNotContain("⚠")
        }
    }

    @Nested
    @DisplayName("E — json.marshal stays unpromoted (guard)")
    inner class MarshalStaysFallback {
        @Test
        fun stillAHelperAssignmentFallbackBlock() {
            val c = card("marshal_rule")
            assertThat(c).contains("⚠").contains("json.marshal(input.p)")
        }
    }
}
