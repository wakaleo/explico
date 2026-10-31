/**
 * Unit tests for [PlainLabel] (spec §14.76): the ONE definition of a label that is plain text and cannot break the Markdown line, bullet, table or link it is written into.
 * The diff header's `--old-label`/`--new-label` use the base rules; `--companion-label` adds `[`, `]`, `|`, `*`.
 */
package io.explico.render

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class PlainLabelTest {

    private fun rejected(value: String, extra: String = "") =
        assertThatThrownBy { PlainLabel.validate(value, "oldLabel", extra) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("oldLabel")

    @Test
    @DisplayName("ordinary text is accepted: dots, inner spaces, parentheses, digits, unicode, and exactly 80 characters")
    fun accepted() {
        for (ok in listOf("v1.2", "release 2026-10 (rc1)", "Версия é 版本", "x".repeat(80))) PlainLabel.validate(ok, "oldLabel")
    }

    @Test
    @DisplayName("not empty and not blank")
    fun blank() {
        rejected("")
        rejected("   ")
    }

    @Test
    @DisplayName("no control character: a line break, a carriage return or a tab, at the start, inside or at the end")
    fun controlCharacters() {
        for (bad in listOf("two\nlines", "a\rb", "a\tb", "\tlead", "trail\t")) rejected(bad)
    }

    @Test
    @DisplayName("no backtick (it would end a code span)")
    fun backtick() {
        rejected("back`tick")
    }

    @Test
    @DisplayName("no leading or trailing space")
    fun spaces() {
        rejected(" leading")
        rejected("trailing ")
    }

    @Test
    @DisplayName("at most 80 characters, counted as characters not UTF-16 units: 81 is refused, 80 accepted, 80 astral characters accepted")
    fun length() {
        rejected("x".repeat(81))
        PlainLabel.validate("x".repeat(80), "oldLabel")
        PlainLabel.validate("😀".repeat(80), "oldLabel")
        rejected("😀".repeat(81))
    }

    @Test
    @DisplayName("extra forbidden characters apply only when asked for (the companion label)")
    fun extra() {
        PlainLabel.validate("a]b", "oldLabel")
        rejected("a]b", "[]|*")
        rejected("a|b", "[]|*")
    }

    @Test
    @DisplayName("the message names the parameter and shows the offending value with control characters escaped, never raw")
    fun message() {
        val message = assertThatThrownBy { PlainLabel.validate("a\nb\tc", "newLabel") }.hasMessageContaining("newLabel").actual().message!!

        assertThat(message).contains("a\\nb\\tc").doesNotContain("\n").doesNotContain("\t")
    }
}
