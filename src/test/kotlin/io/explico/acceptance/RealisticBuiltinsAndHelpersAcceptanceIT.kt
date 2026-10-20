/**
 * Acceptance tests for more builtins and for grouped helper assignments on the realistic policies
 * (`src/test/resources/realistic/{policies,builtins}/`, review items 11 and 12).
 *
 * NOT transcribed from the frozen acceptance-pack README: authored from the "improve rendered
 * output" review at the operator's request. They pin only what those items require:
 * - 11: `net.cidr_contains` and the `is_*` type checks (condition position), and `sum`/`max`/`min`/
 *   `to_number`/`trim_space`/`object.keys`/`time.parse_rfc3339_ns` (operand position) render as
 *   humanized bullets that count as rendered; an unsupported builtin and a type check whose
 *   argument can't be mapped stay verbatim fenced source.
 * - 12 (cheap improvement only): consecutive helper-assignment fallbacks render as ONE labelled
 *   block, while coverage stays exactly as honest as before -- merging is presentation only, and
 *   only assignments merge. The deferred set-difference ("a without b") operand and
 *   comprehension-argument context are deliberately NOT tested here.
 * Wording is deliberately loose (type words, the direction words for max/min, the "helper" label).
 */
package io.explico.acceptance

import io.explico.Explico
import io.explico.opa.OpaRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path

@DisplayName("Realistic policies: more builtins and grouped helper assignments (review items 11-12)")
class RealisticBuiltinsAndHelpersAcceptanceIT {

    companion object {
        private lateinit var files: Map<String, String>
        private lateinit var builtinFiles: Map<String, String>

        private fun render(dir: String): Map<String, String> {
            val policyDir = Path.of(dir)
            return Explico.render(Explico.load(policyDir), policyDir).files
        }

        @JvmStatic
        @BeforeAll
        fun renderDocuments() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            files = render("src/test/resources/realistic/policies")
            builtinFiles = render("src/test/resources/realistic/builtins")
        }

        /** The `## ` section whose heading line contains [marker], up to the next `## ` heading. */
        private fun section(markdown: String, marker: String): String {
            val lines = markdown.lines()
            val start = lines.indexOfFirst { it.startsWith("## ") && it.contains(marker) }
            assertThat(start).describedAs("a '## ' heading containing %s", marker).isGreaterThanOrEqualTo(0)
            val end = lines.drop(start + 1).indexOfFirst { it.startsWith("## ") }
            return lines.drop(start).let { if (end < 0) it else it.take(end + 1) }.joinToString("\n")
        }

        private fun unfenced(markdown: String): List<String> {
            var inFence = false
            return markdown.lines().filter { line ->
                if (line.trim().startsWith("```")) { inFence = !inFence; false } else !inFence
            }
        }

        private fun bullets(markdown: String): List<String> = unfenced(markdown).filter { it.startsWith("- ") }

        /** Condition bullets that are rendered (not a ⚠ fallback bullet). */
        private fun renderedBullets(markdown: String): List<String> = bullets(markdown).filterNot { it.startsWith("- ⚠") }

        private fun warnBullets(markdown: String): List<String> = bullets(markdown).filter { it.startsWith("- ⚠") }

        private fun fencedSource(markdown: String): String = fencedBlocks(markdown).joinToString("\n")

        /** Each fenced block's content, in document order. */
        private fun fencedBlocks(markdown: String): List<String> {
            val blocks = mutableListOf<String>()
            var current: MutableList<String>? = null
            for (line in markdown.lines()) {
                if (line.trim().startsWith("```")) {
                    if (current == null) current = mutableListOf() else { blocks += current.joinToString("\n"); current = null }
                } else current?.add(line)
            }
            return blocks
        }

        /** The single rendered bullet in [card] containing every one of [needles]; fails with the candidates listed if not exactly one. */
        private fun bulletWith(card: String, vararg needles: String): String {
            val matching = renderedBullets(card).filter { b -> needles.all { b.contains(it) } }
            assertThat(matching).describedAs("exactly one rendered bullet containing %s", needles.toList()).hasSize(1)
            // A bullet that merely echoes its own raw source inline (an Operand.Unrendered) can contain
            // all the words above by accident -- it is never a humanized rendering.
            assertThat(matching.single()).doesNotContain("input.").doesNotContain("(")
            return matching.single()
        }
    }

    @Nested
    @DisplayName("Item 11 — builtins.deny: condition-position builtins")
    inner class ConditionBuiltins {

        private val card get() = section(builtinFiles["builtins.md"] ?: "", "builtins.deny")

        @Test
        @DisplayName("net.cidr_contains(\"10.0.0.0/8\", input.client.ip) names the network and `client ▸ ip`")
        fun cidrContains() {
            assertThat(bulletWith(card, "10.0.0.0/8", "client ▸ ip")).doesNotContain("input.")
        }

        @Test
        @DisplayName("is_string(input.user.id) says `user ▸ id` is a string")
        fun isString() {
            assertThat(bulletWith(card, "user ▸ id", "string")).doesNotContain("input.")
        }

        @Test
        @DisplayName("not is_number(input.user.age) says `user ▸ age` is not a number")
        fun notIsNumber() {
            assertThat(bulletWith(card, "user ▸ age", "number")).containsIgnoringCase("not")
        }

        @Test
        @DisplayName("is_boolean / is_array / is_object / is_set each name their humanized path and type word")
        fun otherPositiveTypeChecks() {
            bulletWith(card, "flags ▸ beta", "boolean")
            bulletWith(card, "items", "array")
            bulletWith(card, "meta", "object")
            bulletWith(card, "tags", "set")
        }

        @Test
        @DisplayName("not is_null(input.user.deleted_at) says `user ▸ deleted at` is not null")
        fun notIsNull() {
            assertThat(bulletWith(card, "deleted at", "null")).containsIgnoringCase("not")
        }
    }

    @Nested
    @DisplayName("Item 11 — builtins.deny: operand-position builtins")
    inner class OperandBuiltins {

        private val card get() = section(builtinFiles["builtins.md"] ?: "", "builtins.deny")

        @Test
        @DisplayName("sum(input.amounts) > 1000 mentions the sum of `amounts`")
        fun sum() {
            assertThat(bulletWith(card, "amounts", "sum", "1000")).doesNotContain("input.")
        }

        @Test
        @DisplayName("max(input.scores) >= 90 mentions the largest of `scores`")
        fun max() {
            val bullet = bulletWith(card, "scores", "`90`")
            assertThat(bullet).containsAnyOf("largest", "maximum", "highest")
        }

        @Test
        @DisplayName("min(input.scores) < 10 mentions the smallest of `scores`")
        fun min() {
            val bullet = bulletWith(card, "scores", "`10`")
            assertThat(bullet).containsAnyOf("smallest", "minimum", "lowest")
        }

        @Test
        @DisplayName("to_number(input.count_text) == 5 names `count text` and 5")
        fun toNumber() {
            bulletWith(card, "count text", "`5`")
        }

        @Test
        @DisplayName("trim_space(input.name) == \"root\" names `name` and \"root\"")
        fun trimSpace() {
            bulletWith(card, "name", "\"root\"")
        }

        @Test
        @DisplayName("count(object.keys(input.labels)) > 3 mentions the keys of `labels`")
        fun objectKeysNestedInCount() {
            bulletWith(card, "labels", "keys", "`3`")
        }

        @Test
        @DisplayName("time.parse_rfc3339_ns(input.created_at) < time.now_ns() names `created at` and the current time")
        fun timeParse() {
            bulletWith(card, "created at", "current time")
        }
    }

    @Nested
    @DisplayName("Item 11 — builtins.deny: coverage and what stays fenced")
    inner class CoverageAndFallback {

        private val card get() = section(builtinFiles["builtins.md"] ?: "", "builtins.deny")

        @Test
        @DisplayName("none of the promoted builtins appear in fenced source")
        fun promotedBuiltinsAreNotFenced() {
            val fence = fencedSource(card)
            for (name in listOf(
                "net.cidr_contains", "is_number", "is_boolean", "is_null", "is_array", "is_object", "is_set",
                "sum(", "max(", "min(", "to_number", "trim_space", "object.keys", "time.parse_rfc3339_ns",
            )) {
                assertThat(fence).describedAs("fenced source mentioning %s", name).doesNotContain(name)
            }
            // `is_string` appears in fenced source exactly once: the unmappable-comprehension call in body 3.
            assertThat(Regex("is_string\\(").findAll(fence).count()).isEqualTo(1)
        }

        @Test
        @DisplayName("bodies 1, 2 and json.is_valid in body 3 are sixteen rendered bullets; no bullet shows raw `input.` or `[_]`")
        fun sixteenRenderedBulletsAllHumanized() {
            // Was 15 (bodies 1-2 only) before json.is_valid joined the unary builtin family
            // (spec §14 promotion) -- body 3's `json.is_valid(input.payload)` now renders too,
            // leaving only its comprehension-argument sibling fenced.
            val rendered = renderedBullets(card)
            assertThat(rendered).hasSize(16)
            for (bullet in rendered) assertThat(bullet).doesNotContain("input.").doesNotContain("[_]")
        }

        @Test
        @DisplayName("a type check with an unmappable comprehension argument stays verbatim fenced source; json.is_valid no longer does")
        fun unmappableStaysFencedJsonIsValidNowRenders() {
            val fence = fencedSource(card)
            assertThat(fence).contains("is_string({x | some x in input.xs; net.cidr_is_valid(x)})").doesNotContain("json.is_valid")
            assertThat(renderedBullets(card).any { it.contains("is valid JSON") }).isTrue()
            assertThat(renderedBullets(card).none { it.contains("some x in") }).isTrue()
            assertThat(warnBullets(card)).hasSize(1)
        }

        @Test
        @DisplayName("coverage footer: 16 of 17 conditions (promoted builtins count as rendered), no unrendered value")
        fun coverageFooter() {
            assertThat(card).contains("16 of 17 conditions").doesNotContain("unrendered value")
        }
    }

    @Nested
    @DisplayName("Item 11 — authz.md now renders its cidr and type-check conditions")
    inner class AuthzBuiltins {

        private val md get() = files["authz.md"] ?: ""

        @Test
        @DisplayName("net.cidr_contains(\"10.0.0.0/8\", input.client.ip) is a bullet naming the network and `client ▸ ip`")
        fun cidrIsABullet() {
            val matching = renderedBullets(md).filter { it.contains("10.0.0.0/8") }
            assertThat(matching).hasSize(1)
            assertThat(matching.single()).contains("client ▸ ip")
        }

        @Test
        @DisplayName("is_string(input.user.id) is a bullet naming `user ▸ id` and string")
        fun isStringIsABullet() {
            val matching = renderedBullets(md).filter { it.contains("user ▸ id") && it.contains("string") }
            assertThat(matching).hasSize(1)
        }

        @Test
        @DisplayName("neither appears in fenced source any more, and neither does the `every` block (rendered, spec §14.49)")
        fun noLongerFencedButEveryIs() {
            val fence = fencedSource(md)
            assertThat(fence).doesNotContain("net.cidr_contains(").doesNotContain("is_string(")
            assertThat(fence).doesNotContain("every role in input.user.roles")
        }
    }

    @Nested
    @DisplayName("Item 12 — consecutive helper assignments render as one labelled block")
    inner class GroupedHelperAssignments {

        private val card get() = section(builtinFiles["helpers.md"] ?: "", "helpers.grouped")

        @Test
        @DisplayName("helpers.grouped has exactly one ⚠ bullet, labelled as helper definitions")
        fun oneLabelledBullet() {
            val warn = warnBullets(card)
            assertThat(warn).hasSize(1)
            assertThat(warn.single()).containsIgnoringCase("helper")
        }

        @Test
        @DisplayName("one fenced block holds `x :=`, `y :=` and `z :=` verbatim, in source order")
        fun oneBlockInSourceOrder() {
            val blocks = fencedBlocks(card)
            assertThat(blocks).hasSize(1)
            val block = blocks.single()
            assertThat(block).contains("x := json.marshal(input.p)")
            assertThat(block).contains("y := json.marshal(input.q)")
            assertThat(block).contains("z := json.marshal(input.r)")
            assertThat(block.indexOf("x :=")).isLessThan(block.indexOf("y :="))
            assertThat(block.indexOf("y :=")).isLessThan(block.indexOf("z :="))
        }

        @Test
        @DisplayName("the later `x` is not `y` bullet still follows the block")
        fun laterConditionStillFollows() {
            val bullet = bulletWith(card, "`x`", "`y`")
            assertThat(card.indexOf(bullet)).isGreaterThan(card.indexOf("z := json.marshal(input.r)"))
        }

        @Test
        @DisplayName("coverage is unchanged: merging is presentation only (1 of 4 conditions)")
        fun coverageIsNotInflated() {
            assertThat(card).contains("Rendering coverage: 1 of 4 conditions")
        }
    }

    @Nested
    @DisplayName("Item 12 — only adjacent assignment fallbacks merge (guards: pass today)")
    inner class NothingElseMerges {

        private val card get() = section(builtinFiles["helpers.md"] ?: "", "helpers.deny")

        @Test
        @DisplayName("`a :=` and `b :=` are two separate ⚠ bullets and two separate blocks; json.is_valid(...) now renders (spec §14 promotion)")
        fun twoSeparateBlocksJsonIsValidNowRenders() {
            // Was three separate fenced blocks (this fixture's whole point: a differently-reasoned
            // Unrendered right next to a helper-assignment is never absorbed into its block). Now
            // that json.is_valid itself is promoted, it is not Unrendered at all -- only the two
            // genuinely adjacent-but-separated-by-a-rendered-bullet helper assignments stay fenced,
            // which still demonstrates the same underlying grouping rule (consecutive-same-reason
            // merges; a rendered bullet in between keeps them in separate blocks).
            assertThat(warnBullets(card)).hasSize(2)
            val blocks = fencedBlocks(card)
            assertThat(blocks).hasSize(2)
            assertThat(blocks[0]).contains("a := json.marshal(input.xs)").doesNotContain("b :=")
            assertThat(blocks[1]).contains("b := json.marshal(input.ys)")
            assertThat(card).doesNotContain("json.is_valid")
            assertThat(renderedBullets(card).any { it.contains("is valid JSON") }).isTrue()
        }

        @Test
        @DisplayName("the rendered conditions between them remain bullets, order preserved")
        fun orderPreserved() {
            val env = bulletWith(card, "env", "\"prod\"")
            val valid = bulletWith(card, "`z`", "is valid JSON")
            val comparison = bulletWith(card, "`a`", "`b`")
            val aAt = card.indexOf("a := json.marshal(input.xs)")
            val envAt = card.indexOf(env)
            val bAt = card.indexOf("b := json.marshal(input.ys)")
            val validAt = card.indexOf(valid)
            val cmpAt = card.indexOf(comparison)
            assertThat(listOf(aAt, envAt, bAt, validAt, cmpAt)).isSorted().doesNotContain(-1)
        }

        @Test
        @DisplayName("coverage footer: 3 of 5 conditions")
        fun coverageFooter() {
            assertThat(card).contains("3 of 5 conditions")
        }
    }
}
