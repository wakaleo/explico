/**
 * Acceptance tests for FR-8 of the decision-object feature requests: `--generate-examples` (spec §14.39) fails on the builtins the decision policies lean on. The
 * incremental plan -- `count(<path>) <op> <int>`, `time.parse_rfc3339_ns(<path>)` against a plain path such as `input.now`, and membership over a plain `<path>`
 * collection -- extends what the generator can MODEL; everything else about it is unchanged and these tests also pin that:
 *
 * - a rule using such a builtin gets one satisfying example and a near-miss for each condition, each the verdict OPA gave (never a prediction): a
 *   "satisfies every condition" entry is matched, a "breaks only: <the condition as the card words it>" entry is not;
 * - the generated INPUT does what the condition says: `count(input.approvers) < 2` gets a list of fewer than two for the satisfying case and two or more for the
 *   near-miss; a signature time is before `input.now` in one and not before it in the other (operands in either order);
 * - what still cannot be solved from the input -- a count over reference `data.*`, contradictory counts -- gets NO generated section (never a guess);
 * - membership over a plain path collection keeps working (a guard: it already did);
 * - off by default and deterministic.
 * Authored from the feature request and the plan, NOT from the frozen acceptance-pack README. Facade only. Fixture: `realistic/generated-extensions/policies/genext.rego`
 * (GE-001 count < 2, GE-002 kind + count >= 3, GE-003 time < now, GE-004 now < time, GE-005 membership, GE-006 count over data, GE-007 contradictory counts).
 */
package io.explico.acceptance

import io.explico.Explico
import io.explico.RenderOptions
import io.explico.opa.OpaRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.nio.file.Path
import java.time.Instant

@DisplayName("Realistic policies: --generate-examples for count, time and membership (FR-8)")
class RealisticGeneratorExtensionsAcceptanceIT {

    companion object {
        private val policies = Path.of("src/test/resources/realistic/generated-extensions/policies")
        private const val SECTION = "**Generated examples**"

        private lateinit var generated: String
        private lateinit var generatedAgain: String
        private lateinit var off: String
        private lateinit var offExplicit: String

        private fun render(generate: Boolean?): String {
            val set = Explico.load(policies)
            val docs = if (generate == null) Explico.render(set) else Explico.render(set, RenderOptions(generateExamples = generate))
            return docs.files.getValue("genext.md")
        }

        @JvmStatic
        @BeforeAll
        fun renderAll() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            generated = render(true)
            generatedAgain = render(true)
            off = render(null)
            offExplicit = render(false)
        }

        /** One generated example: the name, OPA's verdict word, and each shown input path's JSON text. */
        class Entry(val name: String, val verdict: String, val values: Map<String, String>) {
            fun json(path: String): JsonElement {
                assertThat(values).describedAs("the values shown for '$name'").containsKey(path)
                return Json.parseToJsonElement(values.getValue(path))
            }
        }

        private fun card(doc: String, id: String): String {
            val lines = doc.lines()
            val start = lines.indexOfFirst { it.startsWith("## $id") }
            assertThat(start).describedAs("a '## $id' heading").isGreaterThanOrEqualTo(0)
            val end = lines.drop(start + 1).indexOfFirst { it.startsWith("## ") }
            return lines.drop(start).take(if (end < 0) lines.size else end + 1).joinToString("\n")
        }

        /** The generated entries of control [id]; an ASSERTION failure when it has no generated section. */
        private fun entries(id: String): List<Entry> {
            val text = card(generated, id)
            assertThat(text).describedAs("$id has a generated section").contains(SECTION)
            val lines = text.substringAfter(SECTION).lines().takeWhile { !it.startsWith("*Generated examples are derived") }
            val result = mutableListOf<Entry>()
            var name: String? = null
            var verdict = ""
            var values = linkedMapOf<String, String>()
            fun flush() { name?.let { result += Entry(it, verdict, values) } }
            for (line in lines) {
                val header = Regex("""^- \*\*(.+)\*\* — (matched|not matched)""").find(line)
                if (header != null) {
                    flush()
                    name = header.groupValues[1]; verdict = header.groupValues[2]; values = linkedMapOf()
                    continue
                }
                Regex("""^ {2}- `([^`]+)`: `(.*)`$""").find(line)?.let { values[it.groupValues[1]] = it.groupValues[2] }
            }
            flush()
            return result
        }

        private fun size(element: JsonElement): Int = (element as? JsonArray)?.size ?: -1

        private fun epochNanos(element: JsonElement): BigInteger {
            val text = (element as JsonPrimitive).content
            val instant = Instant.parse(text)
            return BigInteger.valueOf(instant.epochSecond).multiply(BigInteger.valueOf(1_000_000_000L)).add(BigInteger.valueOf(instant.nano.toLong()))
        }

        private fun nanos(element: JsonElement): BigInteger = BigInteger((element as JsonPrimitive).content)
    }

    @Nested
    @DisplayName("GE-001 — count(<path>) < N")
    inner class CountLess {

        @Test
        @DisplayName("one satisfying example and one near-miss, with OPA's verdicts and the card's own wording of the broken condition")
        fun entries() {
            val e = entries("GE-001")
            assertThat(e.map { it.name to it.verdict }).containsExactly(
                "satisfies every condition" to "matched",
                "breaks only: the number of `approvers` is less than `2`" to "not matched",
            )
        }

        @Test
        @DisplayName("the satisfying input has fewer than two approvers, the near-miss two or more")
        fun sizes() {
            val e = entries("GE-001")
            assertThat(size(e[0].json("approvers"))).isBetween(0, 1)
            assertThat(size(e[1].json("approvers"))).isGreaterThanOrEqualTo(2)
        }
    }

    @Nested
    @DisplayName("GE-002 — a count with another condition: >= N")
    inner class CountAtLeast {

        @Test
        @DisplayName("one satisfying example and one near-miss for EACH condition (the kind, and the count), worded as the card words them")
        fun entries() {
            assertThat(entries("GE-002").map { it.name to it.verdict }).containsExactlyInAnyOrder(
                "satisfies every condition" to "matched",
                "breaks only: `kind` is `\"batch\"`" to "not matched",
                "breaks only: the number of `items` is at least `3`" to "not matched",
            )
        }

        @Test
        @DisplayName("satisfying: three or more items; breaking the count: fewer than three; breaking the kind: the count still holds")
        fun sizes() {
            val e = entries("GE-002").associateBy { it.name }
            assertThat(size(e.getValue("satisfies every condition").json("items"))).isGreaterThanOrEqualTo(3)
            assertThat(size(e.getValue("breaks only: the number of `items` is at least `3`").json("items"))).isBetween(0, 2)
            assertThat(size(e.getValue("breaks only: `kind` is `\"batch\"`").json("items"))).isGreaterThanOrEqualTo(3)
            assertThat(e.getValue("satisfies every condition").json("kind")).isEqualTo(JsonPrimitive("batch"))
        }
    }

    @Nested
    @DisplayName("GE-003 / GE-004 — time.parse_rfc3339_ns(<path>) against input.now, in either order")
    inner class TimeAgainstNow {

        @Test
        @DisplayName("GE-003 (time < now): the satisfying signature time is before now, the near-miss's is not")
        fun timeBeforeNow() {
            val e = entries("GE-003")
            assertThat(e.map { it.name to it.verdict }).containsExactly(
                "satisfies every condition" to "matched",
                "breaks only: the time `signed at` is less than `now`" to "not matched",
            )
            assertThat(epochNanos(e[0].json("signed at"))).isLessThan(nanos(e[0].json("now")))
            assertThat(epochNanos(e[1].json("signed at"))).isGreaterThanOrEqualTo(nanos(e[1].json("now")))
        }

        @Test
        @DisplayName("GE-004 (now < time, operands the other way round): the satisfying expiry is after now, the near-miss's is not")
        fun nowBeforeTime() {
            val e = entries("GE-004")
            assertThat(e.map { it.name to it.verdict }).containsExactly(
                "satisfies every condition" to "matched",
                "breaks only: `now` is less than the time `expires at`" to "not matched",
            )
            assertThat(nanos(e[0].json("now"))).isLessThan(epochNanos(e[0].json("expires at")))
            assertThat(nanos(e[1].json("now"))).isGreaterThanOrEqualTo(epochNanos(e[1].json("expires at")))
        }
    }

    @Nested
    @DisplayName("GE-005 — membership over a plain path collection (a guard: it already worked)")
    inner class Membership {

        @Test
        @DisplayName("one satisfying example (the requester is in the list) and one near-miss (it is not)")
        fun entries() {
            val e = entries("GE-005")
            assertThat(e.map { it.name to it.verdict }).containsExactly(
                "satisfies every condition" to "matched",
                "breaks only: `requester` is one of `approvers`" to "not matched",
            )
            assertThat((e[0].json("approvers") as JsonArray)).contains(e[0].json("requester"))
            assertThat((e[1].json("approvers") as JsonArray)).doesNotContain(e[1].json("requester"))
        }
    }

    @Nested
    @DisplayName("What is still not solved from the input gets no generated section, never a guess")
    inner class StillUnsolved {

        @Test
        @DisplayName("GE-006 counts reference data (data.*): no generated section")
        fun countOverData() {
            assertThat(card(generated, "GE-006")).doesNotContain(SECTION).doesNotContain("satisfies every condition")
        }

        @Test
        @DisplayName("GE-007's counts contradict each other: no generated section")
        fun contradiction() {
            assertThat(card(generated, "GE-007")).doesNotContain(SECTION).doesNotContain("satisfies every condition")
        }
    }

    @Nested
    @DisplayName("Everything generated is OPA's verdict; off by default; deterministic")
    inner class Contract {

        @Test
        @DisplayName("in the whole document a 'satisfies every condition' entry is matched and a 'breaks only' entry is not matched")
        fun verdictsAgreeWithNames() {
            val all = listOf("GE-001", "GE-002", "GE-003", "GE-004", "GE-005").flatMap { entries(it) }
            assertThat(all).hasSize(2 + 3 + 2 + 2 + 2)
            for (entry in all) {
                val expected = if (entry.name.startsWith("satisfies")) "matched" else "not matched"
                assertThat(entry.verdict).describedAs(entry.name).isEqualTo(expected)
            }
        }

        @Test
        @DisplayName("without the option nothing generated appears, and an explicit false is the same")
        fun offByDefault() {
            assertThat(off).doesNotContain("Generated examples").doesNotContain("satisfies every condition")
            assertThat(offExplicit).isEqualTo(off)
        }

        @Test
        @DisplayName("two renders are byte-identical")
        fun deterministic() {
            assertThat(generatedAgain).isEqualTo(generated)
        }
    }
}
