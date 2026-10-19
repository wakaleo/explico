/**
 * Acceptance tests for `=` used as a BINDING: a fresh variable unified with a plain path, a wildcard element
 * or a fully rendered expression is the same as `:=` / `some x in c` (confirmed with `opa eval`; Rego v0
 * code writes `msg = sprintf(...)`, `c = containers[_]` everywhere). Authored from the real-world corpus
 * benchmark, where v0-style unification was ~6% of all fallbacks and kept most comprehensions from rendering.
 * Conservative: exactly one side a bare FRESH variable, never negated, the other side fully rendered; an
 * already-bound variable stays an ordinary comparison. Facade only; fixture `realistic/unification-bindings/`.
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

@DisplayName("Realistic policies: unification as a binding")
class RealisticUnificationBindingsAcceptanceIT {

    companion object {
        private lateinit var doc: String

        @JvmStatic
        @BeforeAll
        fun renderDocument() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            val dir = Path.of("src/test/resources/realistic/unification-bindings")
            doc = Explico.render(Explico.load(dir), dir).files.getValue("unif.md")
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
    @DisplayName("`env = input.x` substitutes like `:=`, with no fallback bullet")
    fun substituted() {
        val card = section("UB-001")
        assertThat(card).contains("`deployment ▸ environment` is `\"production\"`").doesNotContain("⚠").doesNotContain("env =")
    }

    @Test
    @DisplayName("`c = input.containers[_]` reads 'for some c in ...' and later uses extend the path")
    fun wildcard() {
        val card = section("UB-002")
        assertThat(card).contains("for some c in `containers`").contains("`containers ▸ [each c] ▸ privileged` is `true`").doesNotContain("⚠")
    }

    @Test
    @DisplayName("the reversed orientation `input.containers[_] = c` is the same")
    fun reversed() {
        val card = section("UB-003")
        assertThat(card).contains("for some c in `containers`").contains("`containers ▸ [each c] ▸ image` is `\"busybox\"`").doesNotContain("⚠")
    }

    @Test
    @DisplayName("`msg = sprintf(...)` produces the same *Produces* line as `msg := sprintf(...)`")
    fun messageByUnification() {
        val card = section("UB-004")
        assertThat(card).contains("*Produces:* \"bucket [name] is not encrypted\"").doesNotContain("⚠")
    }

    @Test
    @DisplayName("guard: an already-bound variable unified with a wildcard stays an ordinary comparison")
    fun alreadyBound() {
        val card = section("UB-005")
        assertThat(card).doesNotContain("for some y in").contains("any of:").contains("`wanted`").doesNotContain("⚠")
    }

    @Test
    @DisplayName("guard: an unrenderable right-hand side keeps the whole unification as marked source")
    fun unrenderableRhs() {
        val card = section("UB-006")
        assertThat(card).contains("⚠ **not rendered — shown as source:**").contains("x = json.marshal(input.payload)")
    }

    @Test
    @DisplayName("a v0-style binding inside a comprehension no longer blocks the comprehension from rendering")
    fun insideComprehension() {
        val card = section("UB-007")
        assertThat(card).contains("(the set of `perms ▸ [each p]` such that for some p in `perms`; `perms ▸ [each p]` is greater than `600`) is not empty")
        assertThat(card).doesNotContain("⚠")
    }
}
