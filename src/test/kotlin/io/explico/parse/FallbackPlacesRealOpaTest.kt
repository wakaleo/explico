/**
 * Spec §14.69 against real `opa parse` output, for the shapes a hand-built list cannot vouch for: Windows line endings, a byte-order mark, a one-line body, a fallback
 * inside a function. Needs `opa` (guarded like the other real-parser tests).
 */
package io.explico.parse

import io.explico.Explico
import io.explico.model.Condition
import io.explico.opa.OpaRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FallbackPlacesRealOpaTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
        }
    }

    private val body = "package p\n\nimport rego.v1\n\nr if {\n\tinput.a == 1\n\tunknown_check(input.x)\n}\n"

    private fun rowsOf(dir: Path, text: String, rule: String = "r"): List<Int?> {
        Files.writeString(dir.resolve("p.rego"), text)
        return Explico.load(dir).packages.single().rules.single { it.name == rule }.bodies.flatMap { it.conditions }.filterIsInstance<Condition.Unrendered>().map { it.row }
    }

    @Test
    @DisplayName("Windows line endings give the same row as Unix ones: 7")
    fun crlf(@TempDir dir: Path) {
        assertThat(rowsOf(dir, body)).containsExactly(7)
        assertThat(rowsOf(dir, body.replace("\n", "\r\n"))).containsExactly(7)
    }

    @Test
    @DisplayName("a UTF-8 byte-order mark does not shift the rows")
    fun byteOrderMark(@TempDir dir: Path) {
        Files.write(dir.resolve("p.rego"), byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body.toByteArray())
        val rule = Explico.load(dir).packages.single().rules.single { it.name == "r" }
        assertThat(rule.bodies.flatMap { it.conditions }.filterIsInstance<Condition.Unrendered>().map { it.row }).containsExactly(7)
    }

    @Test
    @DisplayName("two fallbacks written on one line are two conditions on the same row")
    fun oneLineBody(@TempDir dir: Path) {
        val text = "package p\n\nimport rego.v1\n\nr if { unknown_a(1); unknown_b(2) }\n"
        assertThat(rowsOf(dir, text)).containsExactly(5, 5)
    }

    @Test
    @DisplayName("a fallback inside a function body has its place too (a function is a card like any other)")
    fun insideAFunction(@TempDir dir: Path) {
        val text = "package p\n\nimport rego.v1\n\nf(x) if {\n\tx == 1\n\tunknown_check(x)\n}\n"
        assertThat(rowsOf(dir, text, "f")).containsExactly(7)
    }
}
