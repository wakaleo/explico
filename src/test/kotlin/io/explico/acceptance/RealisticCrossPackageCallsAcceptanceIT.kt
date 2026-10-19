/**
 * Acceptance tests for calls to user-defined functions in ANOTHER package, reached through an import
 * (`kube.is_privileged(c)`, `import data.lib.kube as k8s`, or the full `data.lib.kube.fn(...)` path).
 * Authored from the real-world corpus benchmark, not the frozen README contract: alias-qualified calls
 * were the largest resolvable fallback class (about a fifth of all fallbacks on Trivy/Gatekeeper/conftest).
 * Facade only; the fixture is `src/test/resources/realistic/cross-package-calls/`.
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

@DisplayName("Realistic policies: cross-package function calls")
class RealisticCrossPackageCallsAcceptanceIT {

    companion object {
        private lateinit var files: Map<String, String>
        private lateinit var doc: String

        @JvmStatic
        @BeforeAll
        fun renderDocuments() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            val dir = Path.of("src/test/resources/realistic/cross-package-calls")
            files = Explico.render(Explico.load(dir), dir).files
            doc = files.getValue("xpkg.md")
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
    @DisplayName("an import-aliased call renders as a satisfied function, linked into the library's document")
    fun aliasedCall() {
        assertThat(section("XP-001"))
            .contains("function [`is_privileged`](lib-kube.md#lib-kube-is-privileged) is satisfied for")
            .doesNotContain("⚠")
    }

    @Test
    @DisplayName("an `import ... as` alias resolves, and negation reads 'is not satisfied'")
    fun renamedImportNegated() {
        assertThat(section("XP-002"))
            .contains("function [`has_flag`](lib-kube.md#lib-kube-has-flag) is not satisfied for")
            .doesNotContain("⚠")
    }

    @Test
    @DisplayName("a full data.<package>.<function> path resolves the same way")
    fun fullDataPath() {
        assertThat(section("XP-003"))
            .contains("function [`is_privileged`](lib-kube.md#lib-kube-is-privileged) is satisfied for")
            .doesNotContain("⚠")
    }

    @Test
    @DisplayName("a cross-package call in operand position renders as a linked result, with no unrendered value")
    fun operandPosition() {
        assertThat(section("XP-004"))
            .contains("[`image_name`](lib-kube.md#lib-kube-image-name)")
            .doesNotContain("⚠")
            .doesNotContain("unrendered value")
    }

    @Test
    @DisplayName("the library package's own document still has a card for each function being linked to")
    fun targetsExist() {
        val lib = files.getValue("lib-kube.md")
        assertThat(lib).contains("<a id=\"lib-kube-is-privileged\"></a>").contains("<a id=\"lib-kube-has-flag\"></a>")
    }
}
