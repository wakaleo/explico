/**
 * Acceptance tests for iterating over a PARTIAL rule's value (`container := workloads.containers[_]`,
 * `some c in workloads.containers`, a same-package `items[_]`). Authored from the real-world corpus
 * benchmark: 457 of ~1000 local-assignment fallbacks were exactly this (Trivy's `kubernetes.containers`),
 * each also cascading into every later `container.field` use. The collection renders as the rule's real
 * document path (`data ▸ <package> ▸ <rule>`), the same way a full `data.` path to a partial rule already does.
 * Facade only; fixture `src/test/resources/realistic/rule-iteration/`.
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

@DisplayName("Realistic policies: iteration over a partial rule's value")
class RealisticRuleIterationAcceptanceIT {

    companion object {
        private lateinit var doc: String

        @JvmStatic
        @BeforeAll
        fun renderDocument() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            val dir = Path.of("src/test/resources/realistic/rule-iteration")
            doc = Explico.render(Explico.load(dir), dir).files.getValue("riter.md")
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
    @DisplayName("an imported partial rule iterated with [_] reads 'for some container in <rule path>'")
    fun importedWildcard() {
        val card = section("RI-001")
        assertThat(card).contains("for some container in `data ▸ lib ▸ workloads ▸ containers`")
        assertThat(card).contains("`data ▸ lib ▸ workloads ▸ containers ▸ [each container] ▸ security context ▸ privileged` is `true`")
        assertThat(card).doesNotContain("⚠")
    }

    @Test
    @DisplayName("`some c in <imported partial rule>` renders the same way")
    fun importedSomeIn() {
        val card = section("RI-002")
        assertThat(card).contains("for some container in `data ▸ lib ▸ workloads ▸ containers`")
        assertThat(card).contains("`data ▸ lib ▸ workloads ▸ containers ▸ [each container] ▸ image` is `\"busybox\"`")
        assertThat(card).doesNotContain("⚠")
    }

    @Test
    @DisplayName("an `import ... as` alias resolves the same way")
    fun renamedImport() {
        val card = section("RI-003")
        assertThat(card).contains("for some container in `data ▸ lib ▸ workloads ▸ containers`").doesNotContain("⚠")
    }

    @Test
    @DisplayName("a same-package partial rule iterated with [_] resolves against the current package")
    fun samePackage() {
        val card = section("RI-004")
        assertThat(card).contains("for some item in `data ▸ riter ▸ local items`").doesNotContain("⚠")
    }

    @Test
    @DisplayName("a field read straight off <rule>[_] keeps its wildcard prefix")
    fun directField() {
        val card = section("RI-005")
        assertThat(card).contains("data ▸ lib ▸ workloads ▸ containers").contains("name").doesNotContain("⚠")
    }

    @Test
    @DisplayName("a document with these cards has no unrendered values")
    fun noUnrenderedValues() {
        assertThat(doc).doesNotContain("unrendered value")
    }
}
