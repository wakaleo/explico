/**
 * Acceptance tests for comprehensions (`{a | ...}`, `[x | ...]`, `{k: v | ...}`) in operand position.
 * Until now every comprehension was verbatim source (spec §1.2 non-goal); the operator approved
 * rendering them, amending REL-004's Tier-1 assertions (see the acceptance README). A comprehension is
 * rendered only when its WHOLE body is rendered: any unrenderable condition or operand inside keeps the
 * entire comprehension as the marked verbatim fallback, with no partial credit.
 * Facade only; fixture `src/test/resources/realistic/comprehensions/`.
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

@DisplayName("Realistic policies: comprehensions")
class RealisticComprehensionsAcceptanceIT {

    companion object {
        private lateinit var doc: String

        @JvmStatic
        @BeforeAll
        fun renderDocument() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            val dir = Path.of("src/test/resources/realistic/comprehensions")
            doc = Explico.render(Explico.load(dir), dir).files.getValue("comp.md")
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
    @DisplayName("count of a set comprehension compared with 0 reads '(the set of ... such that ...) is empty'")
    fun setComprehensionIsEmpty() {
        val card = section("CP-001")
        assertThat(card).contains("(the set of `approvals ▸ [each a]` such that for some a in `approvals`; `approvals ▸ [each a] ▸ role` is `\"manager\"`) is empty")
        assertThat(card).doesNotContain("⚠").doesNotContain("unrendered value")
    }

    @Test
    @DisplayName("an array comprehension reads 'the list of ...' and keeps the ordinary 'the number of' wording")
    fun arrayComprehensionCount() {
        val card = section("CP-002")
        assertThat(card).contains("the number of (the list of `items ▸ [each x]` such that for some x in `items`; `items ▸ [each x] ▸ enabled` is `true`) is greater than `2`")
        assertThat(card).doesNotContain("⚠")
    }

    @Test
    @DisplayName("a comprehension assigned to a variable is substituted at its later use")
    fun assignedComprehensionIsSubstituted() {
        val card = section("CP-003")
        assertThat(card).contains("`\"root\"` is one of (the set of `items ▸ [each item] ▸ name` such that for some item in `items`)")
        assertThat(card).doesNotContain("⚠")
    }

    @Test
    @DisplayName("an object comprehension reads 'the object mapping K to V such that ...'")
    fun objectComprehension() {
        val card = section("CP-004")
        assertThat(card).contains("(the object mapping `k` to `labels ▸ [each v]` such that for some k, v in `labels`; `labels ▸ [each v]` is `\"x\"`)")
        assertThat(card).doesNotContain("⚠")
    }

    @Test
    @DisplayName("a comprehension may use a variable bound outside it")
    fun outerVariable() {
        val card = section("CP-005")
        assertThat(card).contains("`members ▸ [each m] ▸ team` is `teams ▸ [each team] ▸ name`")
        assertThat(card).doesNotContain("⚠")
    }

    @Test
    @DisplayName("a comprehension with an unrenderable condition inside stays whole verbatim source, with no partial credit")
    fun unrenderableBodyKeepsTheWholeFallback() {
        val card = section("CP-006")
        assertThat(card).contains("⚠ **not rendered — shown as source:**").contains("count({a | some a in input.xs; net.cidr_is_valid(a)}) > 0")
        assertThat(card).doesNotContain("the set of")
    }

    @Test
    @DisplayName("a comprehension as a user-function argument renders inside the linked call")
    fun functionArgument() {
        val card = section("CP-007")
        assertThat(card).contains("function [`big`](#comp-big) is satisfied for (the set of `xs ▸ [each x]` such that for some x in `xs`; `xs ▸ [each x] ▸ on` is `true`)")
        assertThat(card).doesNotContain("⚠")
    }

    @Test
    @DisplayName("the KEY of `some k, v in c` is the plain variable k, never an element path")
    fun keyVariableIsNotAnElement() {
        val card = section("CP-008")
        assertThat(card).contains("for some k, v in `labels`").contains("`k` starts with `\"team-\"`").contains("`labels ▸ [each v]` is `\"x\"`")
        assertThat(card).doesNotContain("[each k]")
    }
}
