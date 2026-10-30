/**
 * Acceptance tests for the 0.3.1 fixes found by rendering real decision-object policies (shapes only: the fixture is invented, no real policy is stored):
 *
 * 1. `some g in object.get(input, "items", [])` -- the defensive way to read a list that may be absent -- fell back to verbatim source and left every later
 *    `g.kind` as raw text. It now renders as an iteration over the plain path with the default shown (same `(default ...)` convention as `object.get` in operand
 *    position), and later uses are the ordinary `items ▸ [each g] ▸ kind` breadcrumb. A key that is not a string literal, or a first argument that is not a plain
 *    path, still falls back.
 * 2. A function card listed "Worked examples" with "not matched" entries -- a function is not matched by an input, so that was misleading. A function has no worked
 *    examples (Markdown or `explico.json`); the rules that CALL it keep theirs.
 * 3. A string literal written with `\t`, `\n`, `\r` was printed with the raw control characters, breaking the line it sat in. Control characters are now shown
 *    escaped (`\t`, `\n`, `\r`, any other as `\u00XX`); a literal without them is unchanged.
 *
 * Authored from the findings, NOT from the frozen acceptance-pack README. Facade only. Fixture: `realistic/real-world-fixes/policies/rw.rego` (RW-1 object.get list,
 * RW-2 object.get on a nested path, RW-3 non-literal key, RW-4 calls function `normalized`, RW-5 control characters; indented with spaces on purpose, so a tab in the
 * output can only come from a rendered literal). The 0.3.0 outputs are guarded by `docs/sample-output` staying byte-identical.
 */
package io.explico.acceptance

import io.explico.Explico
import io.explico.opa.OpaRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

@DisplayName("Realistic policies: fixes found on real decision-object policies (0.3.1)")
class RealisticRealWorldFixesAcceptanceIT {

    companion object {
        private val base = Path.of("src/test/resources/realistic/real-world-fixes")
        private val policies = base.resolve("policies")

        private lateinit var files: Map<String, String>
        private lateinit var again: Map<String, String>

        private fun render(): Map<String, String> {
            val set = Explico.load(policies)
            return Explico.render(set, policies, Explico.loadExamples(base.resolve("examples")), null, false, true).files
        }

        @JvmStatic
        @BeforeAll
        fun renderAll() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            files = render()
            again = render()
        }

        /** The card whose heading starts with [heading] (a control id, or `rw.<name>` for an id-less rule). */
        private fun card(heading: String): String {
            val lines = files.getValue("rw.md").lines()
            val start = lines.indexOfFirst { it.startsWith("## $heading") }
            assertThat(start).describedAs("a '## $heading' heading").isGreaterThanOrEqualTo(0)
            val end = lines.drop(start + 1).indexOfFirst { it.startsWith("## ") }
            return lines.drop(start).take(if (end < 0) lines.size else end + 1).joinToString("\n")
        }

        private fun ruleJson(match: (JsonObject) -> Boolean): JsonObject =
            Json.parseToJsonElement(files.getValue("explico.json")).jsonObject.getValue("packages").jsonArray.map { it.jsonObject }
                .flatMap { it.getValue("rules").jsonArray.map { r -> r.jsonObject } }
                .first(match)

        private fun byControl(id: String) = ruleJson { it["controlId"]?.jsonPrimitive?.content == id }
        private fun byName(name: String) = ruleJson { it.getValue("name").jsonPrimitive.content == name }
    }

    @Nested
    @DisplayName("object.get(<path>, \"key\", []) as the collection of 'some x in'")
    inner class ObjectGetIteration {

        @Test
        @DisplayName("RW-1: the iteration names the path and its default, and later uses of g are the ordinary breadcrumb")
        fun topLevel() {
            val card = card("RW-1")
            assertThat(card).contains("for some g in `items` (default `[]`)")
            assertThat(card).contains("`items ▸ [each g] ▸ kind` is `\"gold\"`").contains("`items ▸ [each g] ▸ tier` is `\"a\"`")
            assertThat(card).doesNotContain("`g.kind`").doesNotContain("`g.tier`")
        }

        @Test
        @DisplayName("RW-1: no fallback block, and every condition counts as rendered")
        fun fullyRendered() {
            val card = card("RW-1")
            assertThat(card).doesNotContain("⚠").doesNotContain("not rendered")
            assertThat(card).contains("Rendering coverage: 3 of 3 conditions")
        }

        @Test
        @DisplayName("RW-1: the worked examples show the path values OPA evaluated, with OPA's verdicts")
        fun examples() {
            val card = card("RW-1")
            assertThat(card).contains("- **everything holds** — matched").contains("- **nothing holds** — not matched").contains("- **no list at all** — not matched")
            assertThat(card).contains("`items ▸ [each g] ▸ kind`: `\"gold\"`").contains("`items ▸ [each g] ▸ tier`: `\"a\"`")
        }

        @Test
        @DisplayName("RW-2: the first argument may be a longer plain path: request ▸ reviewers")
        fun nestedPath() {
            val card = card("RW-2")
            assertThat(card).contains("for some r in `request ▸ reviewers` (default `[]`)")
            assertThat(card).contains("`request ▸ reviewers ▸ [each r] ▸ role` is `\"lead\"`")
            assertThat(card).doesNotContain("⚠").contains("Rendering coverage: 2 of 2 conditions")
        }

        @Test
        @DisplayName("RW-3: a key that is not a string literal is not modelled: the whole iteration stays a marked fallback, raw h.kind and all")
        fun nonLiteralKeyStillFallsBack() {
            val card = card("RW-3")
            assertThat(card).contains("⚠ **not rendered").contains("some h in object.get(input, input.listName, [])").contains("`h.kind` is `\"extra\"`")
            assertThat(card).doesNotContain("(default `[]`)").doesNotContain("Rendering coverage: 2 of 2 conditions")
        }

        @Test
        @DisplayName("the verdicts of the fallback rule are still OPA's (it holds when the named list has an extra)")
        fun fallbackRuleStillEvaluated() {
            assertThat(card("RW-3")).contains("- **everything holds** — matched").contains("- **nothing holds** — not matched")
        }
    }

    @Nested
    @DisplayName("A function is not matched by an input: it has no worked examples")
    inner class FunctionCards {

        @Test
        @DisplayName("the function card has no 'Worked examples' section, no matched / not matched entry and no provenance footer")
        fun noExamplesOnTheCard() {
            val card = card("rw.normalized")
            assertThat(card).contains("*Parameters: `value`*")
            assertThat(card).doesNotContain("Worked examples").doesNotContain("not matched").doesNotContain("— matched").doesNotContain("Examples are evaluated")
        }

        @Test
        @DisplayName("explico.json: a function has no workedExamples key, an ordinary rule still has its entries")
        fun json() {
            assertThat(byName("normalized")).doesNotContainKey("workedExamples")
            val names = byControl("RW-1").getValue("workedExamples").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
            assertThat(names).containsExactlyInAnyOrder("everything holds", "nothing holds", "no list at all")
        }

        @Test
        @DisplayName("RW-4 calls the function: its examples are evaluated through it (' OK\\n' normalises to ok; 'nope' and a non-string do not)")
        fun callerKeepsItsExamples() {
            val card = card("RW-4")
            assertThat(card).contains("Worked examples")
            assertThat(card).contains("- **everything holds** — matched").contains("- **nothing holds** — not matched").contains("- **no list at all** — not matched")
        }
    }

    @Nested
    @DisplayName("Control characters in a literal are shown escaped")
    inner class ControlCharacters {

        @Test
        @DisplayName("a function's Value line shows trim(value, \" \\t\\n\\r\") with the four characters as written, on ONE line")
        fun trimArgument() {
            assertThat(card("rw.normalized")).contains("parameter `value` trimmed of `\" \\t\\n\\r\"`")
        }

        @Test
        @DisplayName("RW-5: a condition literal with tab, newline and another control character reads as escapes; a non-ASCII letter is unchanged")
        fun conditionLiteral() {
            val card = card("RW-5")
            assertThat(card).contains("`text` is `\"a\\tb\\nc\\u0001\"`")
            assertThat(card).contains("`name` is `\"café\"`")
        }

        @Test
        @DisplayName("no rendered file contains a raw tab, carriage return or other control character (only line feeds)")
        fun noRawControlCharacters() {
            for ((name, text) in files) {
                val bad = text.filter { it.code < 0x20 && it != '\n' }
                assertThat(bad).describedAs("control characters in $name").isEmpty()
            }
        }
    }

    @Nested
    @DisplayName("Nothing else moves")
    inner class Contract {

        @Test
        @DisplayName("two renders are byte-identical")
        fun deterministic() {
            assertThat(again.toList()).isEqualTo(files.toList())
        }

        @Test
        @DisplayName("the five-package sample (docs/sample-output) renders byte-for-byte as committed")
        fun sampleOutputUntouched() {
            val dir = Path.of("samples/policies")
            val docs = Explico.render(Explico.load(dir), dir, Explico.loadExamples(Path.of("samples/examples")), Path.of("samples/data/release/data.json")).files
            assertThat(docs).isNotEmpty()
            for ((name, text) in docs) {
                assertThat(text).describedAs("docs/sample-output/$name").isEqualTo(Files.readString(Path.of("docs/sample-output").resolve(name)))
            }
        }
    }
}
