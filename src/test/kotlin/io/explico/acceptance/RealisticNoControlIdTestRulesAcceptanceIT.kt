/**
 * Acceptance test for the "No control id" finding next to OPA unit tests (spec §14.68): a policy directory usually holds `*_test.rego` files. A rule named `test_*`
 * (or `todo_test_*`) is OPA's own test convention; it is neither an entry point of the policy nor a use of the rule it exercises, so the finding lists the REAL
 * entry points (`tp.deny`) and never the tests. Found in review: before this, the tests were listed and the entry points they referenced were hidden.
 * Facade only. Fixture: `realistic/no-control-id-tests/policies` (`p.rego`: `deny` and `used_only_by_a_test`; `p_test.rego`: `test_denies`, `todo_test_later`).
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

@DisplayName("Realistic policies: no control id, next to OPA unit tests")
class RealisticNoControlIdTestRulesAcceptanceIT {

    companion object {
        private val dir = Path.of("src/test/resources/realistic/no-control-id-tests/policies")
        private lateinit var docs: io.explico.RenderedDocs

        @JvmStatic
        @BeforeAll
        fun renderAll() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            docs = Explico.render(Explico.load(dir), dir)
        }

        private fun bullet(): String {
            val lines = docs.files.getValue("index.md").lines()
            val line = lines.firstOrNull { it.startsWith("- **No control id") }
            assertThat(line).describedAs("a 'No control id' bullet").isNotNull()
            return line!!
        }
    }

    @Test
    @DisplayName("the real entry points are listed -- the policy's deny and the rule only a test exercises -- and the tests are not")
    fun entryPointsNotTests() {
        val line = bullet()
        assertThat(line).startsWith("- **No control id (2):** [tp.deny](tp.md#tp-deny), [tp.used_only_by_a_test](tp.md#tp-used-only-by-a-test)")
        assertThat(line).doesNotContain("test_denies").doesNotContain("todo_test_later")
    }

    @Test
    @DisplayName("the finding line counts the two entry points, and nothing is a gap")
    fun findingLine() {
        assertThat(docs.findings).contains("2 rules have no control id and are not used by another rule")
        assertThat(docs.gaps).isEmpty()
    }
}
