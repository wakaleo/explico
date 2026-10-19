/**
 * Acceptance tests for Rego v0 syntax (`deny[msg] { ... }`, no `if`/`contains`), selected explicitly with
 * `RegoVersion.V0` (CLI `--rego-version v0`). Authored from the real-world corpus benchmark: the
 * Gatekeeper library, the largest public Rego corpus, is v0 and was rejected outright by OPA 1.x's default
 * parsing. The default stays V1; there is NO auto-detection (a silent version guess could change what a
 * policy means). Facade only; fixture `src/test/resources/realistic/rego-v0/`.
 */
package io.explico.acceptance

import io.explico.Explico
import io.explico.model.RegoVersion
import io.explico.opa.OpaRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path

@DisplayName("Realistic policies: Rego v0 syntax")
class RealisticRegoV0AcceptanceIT {

    companion object {
        private val policies = Path.of("src/test/resources/realistic/rego-v0/policies")
        private val examplesDir = Path.of("src/test/resources/realistic/rego-v0/examples")
        private lateinit var doc: String

        @JvmStatic
        @BeforeAll
        fun renderDocument() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            val set = Explico.load(policies, RegoVersion.V0)
            doc = Explico.render(set, policies, Explico.loadExamples(examplesDir)).files.getValue("legacy-admission.md")
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
    @DisplayName("the default (v1) still rejects v0 syntax rather than guessing")
    fun defaultStaysV1() {
        assertThatThrownBy { Explico.load(policies) }.hasMessageContaining("rego")
    }

    @Test
    @DisplayName("a v0 `deny[msg] { ... }` rule renders like its v1 equivalent, with its message")
    fun denyRule() {
        val card = section("LEG-001")
        assertThat(card).contains("for some container in `containers`").contains("`containers ▸ [each container] ▸ privileged` is `true`")
        assertThat(card).contains("*Produces:* \"container [container name] is privileged\"").doesNotContain("⚠")
    }

    @Test
    @DisplayName("a v0 bracket-form `violation[{\"msg\": m}]` rule renders with its message")
    fun violationRule() {
        val card = section("LEG-002")
        assertThat(card).contains("does not start with").doesNotContain("⚠")
    }

    @Test
    @DisplayName("worked examples are evaluated under v0 too, with the right verdicts")
    fun workedExamples() {
        val card = section("LEG-001")
        assertThat(card).contains("**bad pod** — ❌ denied").contains("**good pod** — ✅ allowed")
    }
}
