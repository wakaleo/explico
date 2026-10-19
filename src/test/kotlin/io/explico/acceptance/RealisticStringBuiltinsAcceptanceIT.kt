/**
 * Acceptance tests for the string/number/collection builtins promoted after the real-world corpus
 * benchmark (`split`, `trim_prefix`, `trim_suffix`, `trim`, `replace`, `substring`, `abs`, `ceil`,
 * `floor`, `round`, `array.concat`, `sort`, `indexof`, `json.unmarshal`). Each phrase was checked against
 * a real `opa eval` of the builtin. Facade only; fixture `src/test/resources/realistic/string-builtins/`.
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

@DisplayName("Realistic policies: string, number and collection builtins")
class RealisticStringBuiltinsAcceptanceIT {

    companion object {
        private lateinit var doc: String

        @JvmStatic
        @BeforeAll
        fun renderDocument() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            val dir = Path.of("src/test/resources/realistic/string-builtins")
            doc = Explico.render(Explico.load(dir), dir).files.getValue("strs.md")
        }

        private fun section(marker: String): String {
            val lines = doc.lines()
            val start = lines.indexOfFirst { it.startsWith("## ") && it.contains(marker) }
            assertThat(start).describedAs("a '## ' heading containing %s", marker).isGreaterThanOrEqualTo(0)
            val end = lines.drop(start + 1).indexOfFirst { it.startsWith("## ") }
            return lines.drop(start).take(if (end < 0) lines.size else end + 1).joinToString("\n")
        }

        private fun assertFullyRendered(card: String) {
            assertThat(card).doesNotContain("⚠").doesNotContain("unrendered value")
        }
    }

    @Test
    @DisplayName("split: 'the parts of x split on d'")
    fun split() {
        val card = section("SB-001")
        assertThat(card).contains("the number of the parts of `image` split on `\":\"` is greater than `1`")
        assertFullyRendered(card)
    }

    @Test
    @DisplayName("trim_suffix / trim_prefix / trim")
    fun trims() {
        val card = section("SB-002")
        assertThat(card)
            .contains("`host` without its trailing `\".internal\"` is `\"db\"`")
            .contains("`name` without its leading `\"svc-\"` is `\"api\"`")
            .contains("`code` trimmed of `\"-\"` at both ends is `\"x\"`")
        assertFullyRendered(card)
    }

    @Test
    @DisplayName("replace: every occurrence")
    fun replace() {
        val card = section("SB-003")
        assertThat(card).contains("`path` with every `\"/\"` replaced by `\".\"` is `\"a.b\"`")
        assertFullyRendered(card)
    }

    @Test
    @DisplayName("substring: offset and length")
    fun substring() {
        val card = section("SB-004")
        assertThat(card).contains("the substring of `sha` from position `0`, length `7` is `\"abc1234\"`")
        assertFullyRendered(card)
    }

    @Test
    @DisplayName("abs, ceil, floor, round")
    fun numeric() {
        val card = section("SB-005")
        assertThat(card)
            .contains("the absolute value of `delta` is greater than `5`")
            .contains("`ratio` rounded up is `2`")
            .contains("`ratio` rounded down is `1`")
            .contains("`ratio` rounded to the nearest whole number is `2`")
        assertFullyRendered(card)
    }

    @Test
    @DisplayName("array.concat, sort, indexof, json.unmarshal")
    fun collections() {
        val card = section("SB-006")
        assertThat(card)
            .contains("the number of `a` followed by `b` is greater than `3`")
            .contains("`tags` sorted is `[\"a\", \"b\"]`")
            .contains("the position of `\"x\"` in `s` (-1 if absent) is greater than `-1`")
            .contains("`raw` parsed as JSON is `expected`")
        assertFullyRendered(card)
    }
}
