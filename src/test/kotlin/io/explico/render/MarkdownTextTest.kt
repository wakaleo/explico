/**
 * Spec §14.54: declared labels, statuses and instants are free text from the policy's evaluated value. They are shown on ONE line inside a list
 * item, so a line break or a backtick must never break the structure -- and the text must still read as written.
 */
package io.explico.render

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class MarkdownTextTest {

    @ParameterizedTest(name = "code span of {0}")
    @CsvSource(
        delimiter = '|',
        value = [
            "waiver.signoff.senior | `waiver.signoff.senior`",
            "überprüfen ✓ | `überprüfen ✓`",
            "a`b | ``a`b``",
            "a``b`c | ```a``b`c```",
            "`a | `` `a ``",
            "a` | `` a` ``",
            "`both` | `` `both` ``",
        ],
    )
    @DisplayName("a code span whose fence is longer than any backtick run inside, padded where the text starts or ends with one (CommonMark)")
    fun codeSpan(text: String, expected: String) {
        assertThat(MarkdownText.codeSpan(text)).isEqualTo(expected)
    }

    @Test
    @DisplayName("a line break inside a code span becomes a space -- exactly what CommonMark itself renders -- so the list item stays on one line")
    fun codeSpanLineBreaks() {
        assertThat(MarkdownText.codeSpan("one\ntwo")).isEqualTo("`one two`")
        assertThat(MarkdownText.codeSpan("one\r\ntwo\rthree")).isEqualTo("`one two three`")
    }

    @ParameterizedTest(name = "emphasis text of {0}")
    @CsvSource(
        delimiter = '|',
        value = [
            "not_started | not_started",
            "expired | expired",
            "a*b | a\\*b",
            "back\\slash | back\\\\slash",
            "a`b | a\\`b",
            "[x](http://e) | \\[x\\](http://e)",
            "<b>hi | \\<b>hi",
            "_lead | \\_lead",
            "trail_ | trail\\_",
            "snake_case_word | snake_case_word",
        ],
    )
    @DisplayName("characters that would change how the text renders are escaped; an underscore inside a word is left alone")
    fun emphasisText(text: String, expected: String) {
        assertThat(MarkdownText.emphasisText(text)).isEqualTo(expected)
    }

    @Test
    @DisplayName("a line break becomes a space")
    fun emphasisLineBreaks() {
        assertThat(MarkdownText.emphasisText("line\nbreak")).isEqualTo("line break")
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "plain | plain",
            "back\\slash \"quote\" | back\\slash \"quote\"",
            "caf\u00e9 \u00dcber \u65e5\u672c | caf\u00e9 \u00dcber \u65e5\u672c",
        ],
    )
    @DisplayName("spec §14.62: text without a control character is returned as it was -- a backslash, a quote and non-ASCII letters are not touched")
    fun controlCharactersLeavesOtherTextAlone(text: String, expected: String) {
        assertThat(MarkdownText.controlCharacters(text)).isEqualTo(expected)
    }

    @Test
    @DisplayName("spec §14.62: tab, line feed and carriage return are written \\t \\n \\r; any other control character is \\u00XX with lowercase hex")
    fun controlCharactersAreEscaped() {
        assertThat(MarkdownText.controlCharacters(" \t\n\r")).isEqualTo(" \\t\\n\\r")
        assertThat(MarkdownText.controlCharacters("a\u0001b\u001fc\u007fd\u0085e")).isEqualTo("a\\u0001b\\u001fc\\u007fd\\u0085e")
    }

    @Test
    @DisplayName("spec §14.62: the result never contains a control character, whatever went in")
    fun controlCharactersLeavesNoneBehind() {
        val everything = (0..0x9f).map { it.toChar() }.joinToString("")
        assertThat(MarkdownText.controlCharacters(everything).any { it.isISOControl() }).isFalse()
    }
}
