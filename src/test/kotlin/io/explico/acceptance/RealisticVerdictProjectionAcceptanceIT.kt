/**
 * Acceptance tests for FR-2 of the decision-object feature requests (specs/decision-object-rendering-spec.md): a rule whose value is an
 * OBJECT can DECLARE which of its fields is the verdict, in `custom.verdict` METADATA (`outcome`: a boolean field, `reason`: an optional
 * string field). Its worked examples then read `✅ <outcome field>` / `❌ not <outcome field>` followed by the quoted reason, instead of the
 * meaningless "matched" and a JSON blob. explico renders DECLARED field names only -- it never guesses which field is the verdict, and the
 * word shown is the declared field's own name (nothing invented). Anything the evaluated value does not support (not an object, the outcome
 * missing or not a boolean, the reason missing or not a string) keeps that ONE example exactly as today, raw JSON.
 *
 * Authored from the feature request and the implementation plan (operator-accepted wording question Q3), NOT from the frozen acceptance-pack
 * README. Fixtures: `realistic/verdict-projection/{basic,shapes,malformed,diff}`. Scope: ONLY the verdict; actions, annotations and timing are
 * the next increment.
 *
 * The explico.json shape pinned here (additive, absent whenever nothing is declared): a rule gains `verdict: {outcome, reason}` (the declared
 * field names; `reason` null when not declared), and a worked example gains `verdict: {outcome, text, reason}` (the boolean, the words after
 * the glyph, the reason string or null) only when it was projected.
 */
package io.explico.acceptance

import io.explico.DiffReport
import io.explico.Explico
import io.explico.diff.DiffCategory
import io.explico.opa.OpaRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path

@DisplayName("Realistic policies: decision objects -- declared verdict projection (FR-2)")
class RealisticVerdictProjectionAcceptanceIT {

    private class Rendered(val files: Map<String, String>, val stderr: String) {
        val json: JsonObject get() = Json.parseToJsonElement(files.getValue("explico.json")).jsonObject
    }

    companion object {
        private val root = Path.of("src/test/resources/realistic/verdict-projection")

        private lateinit var basic: Rendered
        private lateinit var shapes: Rendered
        private lateinit var malformed: Rendered
        private lateinit var diff: DiffReport

        /** Renders one corpus through the public facade, capturing everything written to stderr while it loads and renders. */
        private fun render(name: String): Rendered {
            val policies = root.resolve(name).resolve("policies")
            val original = System.err
            val buffer = ByteArrayOutputStream()
            System.setErr(PrintStream(buffer, true, Charsets.UTF_8))
            try {
                val files = Explico.render(Explico.load(policies), policies, Explico.loadExamples(root.resolve(name).resolve("examples")), null, false, true).files
                return Rendered(files, buffer.toString(Charsets.UTF_8))
            } finally {
                System.setErr(original)
            }
        }

        @JvmStatic
        @BeforeAll
        fun renderAll() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            basic = render("basic")
            shapes = render("shapes")
            malformed = render("malformed")
            diff = Explico.diff(Explico.load(root.resolve("diff/old")), Explico.load(root.resolve("diff/new")))
        }

        /** The card whose anchor is [id]: from its `<a id>` to the next card's anchor (or the end of the document). */
        private fun card(r: Rendered, doc: String, id: String): String = r.files.getValue(doc).substringAfter("<a id=\"$id\"></a>").substringBefore("<a id=\"")

        /** The card's worked-examples section, from its heading to the provenance footer. */
        private fun examples(r: Rendered, doc: String, id: String): String =
            card(r, doc, id).substringAfter("**Worked examples**").substringBefore("*Examples are evaluated")

        /** The lines of the bullet for the fixture called [name]: its head line and everything indented under it. */
        private fun bullet(section: String, name: String): List<String> {
            val lines = section.lines()
            val start = lines.indexOfFirst { it.startsWith("- **$name** —") }
            assertThat(start).describedAs("a worked-example bullet for '$name'").isGreaterThanOrEqualTo(0)
            val end = lines.drop(start + 1).indexOfFirst { !it.startsWith("  ") }.let { if (it < 0) lines.size else start + 1 + it }
            return lines.subList(start, end)
        }

        private fun rule(r: Rendered, controlId: String): JsonObject =
            r.json.getValue("packages").jsonArray.flatMap { it.jsonObject.getValue("rules").jsonArray }
                .first { it.jsonObject["controlId"]?.jsonPrimitive?.content == controlId }.jsonObject

        private fun exampleJson(r: Rendered, controlId: String, name: String): JsonObject =
            rule(r, controlId).getValue("workedExamples").jsonArray.first { it.jsonObject.getValue("name").jsonPrimitive.content == name }.jsonObject

        /** The `verdict` object of a rule or example; an ASSERTION failure (not an exception) when the key is absent. */
        private fun verdictOf(owner: JsonObject, what: String): JsonObject {
            assertThat(owner).describedAs("$what carries a `verdict` object").containsKey("verdict")
            return owner.getValue("verdict").jsonObject
        }

        private fun warningsAbout(stderr: String, text: String) = stderr.lines().filter { it.contains("custom.verdict") && it.contains(text) }
    }

    @Nested
    @DisplayName("CTRL-V-DECL: a declared verdict {outcome: compliant, reason: commentary}")
    inner class Declared {
        private val section get() = examples(basic, "verdict_declared.md", "ctrl-v-decl")

        @Test
        @DisplayName("an example whose outcome field is true reads '✅ compliant' with the declared reason quoted on the next line")
        fun positive() {
            val lines = bullet(section, "subject with an owner")
            assertThat(lines[0]).isEqualTo("- **subject with an owner** — ✅ compliant")
            assertThat(lines[1].trim()).isEqualTo("*\"Owner recorded\"*")
        }

        @Test
        @DisplayName("an example whose outcome field is false reads '❌ not compliant' -- 'not' plus the declared field name, nothing invented")
        fun negative() {
            val lines = bullet(section, "subject without an owner")
            assertThat(lines[0]).isEqualTo("- **subject without an owner** — ❌ not compliant")
            assertThat(lines[1].trim()).isEqualTo("*\"No owner recorded\"*")
        }

        @Test
        @DisplayName("the raw JSON blob and the meaningless word 'matched' are gone from this rule's examples")
        fun noRawJson() {
            assertThat(section).doesNotContain("{\"").doesNotContain("\"compliant\":").doesNotContain("— matched")
        }

        @Test
        @DisplayName("the values of the referenced paths are still listed under each example")
        fun pathValuesSurvive() {
            assertThat(bullet(section, "subject with an owner")).anyMatch { it.trim() == "- `owner`: `\"alice\"`" }
            assertThat(bullet(section, "subject without an owner")).anyMatch { it.trim() == "- `owner`: absent" }
        }
    }

    @Nested
    @DisplayName("CTRL-V-OUT: a declared outcome with no reason")
    inner class OutcomeOnly {
        private val section get() = examples(basic, "verdict_outcome_only.md", "ctrl-v-out")

        @Test
        @DisplayName("the verdict line is shown and no reason line is invented")
        fun noReasonLine() {
            val lines = bullet(section, "subject with an owner")
            assertThat(lines[0]).isEqualTo("- **subject with an owner** — ✅ compliant")
            assertThat(lines.drop(1).none { it.trim().startsWith("*\"") }).describedAs("no quoted reason line").isTrue()
            assertThat(bullet(section, "subject without an owner")[0]).isEqualTo("- **subject without an owner** — ❌ not compliant")
        }
    }

    @Nested
    @DisplayName("CTRL-V-RAW: no declaration -- regression guard")
    inner class Undeclared {
        private val section get() = examples(basic, "verdict_undeclared.md", "ctrl-v-raw")

        @Test
        @DisplayName("the object value is shown exactly as before: 'matched' and the raw JSON, no verdict glyph")
        fun unchanged() {
            val lines = bullet(section, "subject with an owner")
            assertThat(lines[0]).isEqualTo("- **subject with an owner** — matched")
            assertThat(lines[1].trim()).isEqualTo("*\"{\"commentary\":\"Owner recorded\",\"compliant\":true}\"*")
            assertThat(section).doesNotContain("✅").doesNotContain("❌")
        }

        @Test
        @DisplayName("nothing about a verdict is warned about")
        fun noWarnings() {
            assertThat(basic.stderr).doesNotContain("custom.verdict")
        }
    }

    @Nested
    @DisplayName("Per-example fallback: what the evaluated value does not support keeps that one example raw")
    inner class Shapes {
        private val a get() = examples(shapes, "verdict_shapes.md", "ctrl-v-mixa")
        private val b get() = examples(shapes, "verdict_shapes.md", "ctrl-v-mixb")

        @Test
        @DisplayName("control: the well-formed object of the same rule IS projected")
        fun wellFormed() {
            val lines = bullet(a, "well-formed object")
            assertThat(lines[0]).isEqualTo("- **well-formed object** — ✅ ok")
            assertThat(lines[1].trim()).isEqualTo("*\"fine\"*")
        }

        @Test
        @DisplayName("the outcome field is missing -> raw JSON")
        fun missingOutcome() = assertRaw(bullet(a, "object without the outcome field"), "{\"why\":\"no outcome field\"}")

        @Test
        @DisplayName("the outcome field is not a boolean (\"yes\") -> raw JSON, never read as true")
        fun outcomeNotBoolean() = assertRaw(bullet(a, "outcome that is not a boolean"), "{\"ok\":\"yes\",\"why\":\"outcome not boolean\"}")

        @Test
        @DisplayName("the declared reason field is missing -> raw JSON (a half-projection would be a guess)")
        fun missingReason() = assertRaw(bullet(b, "object without the reason field"), "{\"ok\":false}")

        @Test
        @DisplayName("the declared reason is not a string (42) -> raw JSON")
        fun reasonNotString() = assertRaw(bullet(b, "reason that is not a string"), "{\"ok\":false,\"why\":42}")

        @Test
        @DisplayName("the value is not an object at all -> raw, as today")
        fun scalarValue() = assertRaw(bullet(b, "scalar value"), "\"just a string\"")

        private fun assertRaw(lines: List<String>, rawJson: String) {
            assertThat(lines[0]).endsWith("— matched")
            assertThat(lines.joinToString("\n")).contains("*\"$rawJson\"*").doesNotContain("✅").doesNotContain("❌")
        }

        @Test
        @DisplayName("none of these is a warning: a well-formed declaration meeting an unexpected value is not a mistake in the METADATA")
        fun noWarnings() {
            assertThat(shapes.stderr).doesNotContain("custom.verdict")
        }
    }

    @Nested
    @DisplayName("Wrongly-shaped declarations are ignored with a warning, and the rule renders as today")
    inner class Malformed {

        @Test
        @DisplayName("`verdict: compliant` (a scalar) -> exactly one stderr warning naming custom.verdict and the rule")
        fun scalarWarned() {
            assertThat(warningsAbout(malformed.stderr, "bad_scalar")).hasSize(1)
        }

        @Test
        @DisplayName("`verdict` without an `outcome` -> exactly one stderr warning naming custom.verdict and the rule")
        fun missingOutcomeWarned() {
            assertThat(warningsAbout(malformed.stderr, "bad_no_outcome")).hasSize(1)
        }

        @Test
        @DisplayName("both rules still render, their examples exactly as today (raw JSON, 'matched')")
        fun renderedAsToday() {
            for ((id, doc) in listOf("ctrl-v-bad1" to "verdict_malformed.md", "ctrl-v-bad2" to "verdict_malformed.md")) {
                val section = examples(malformed, doc, id)
                val lines = bullet(section, "subject without an owner")
                assertThat(lines[0]).describedAs(id).isEqualTo("- **subject without an owner** — matched")
                assertThat(lines[1].trim()).describedAs(id).isEqualTo("*\"{\"commentary\":\"No owner recorded\",\"compliant\":false}\"*")
                assertThat(section).describedAs(id).doesNotContain("✅").doesNotContain("❌")
            }
        }

        @Test
        @DisplayName("explico.json carries no verdict for them")
        fun noJsonVerdict() {
            assertThat(rule(malformed, "CTRL-V-BAD1")).doesNotContainKey("verdict")
            assertThat(rule(malformed, "CTRL-V-BAD2")).doesNotContainKey("verdict")
        }
    }

    @Nested
    @DisplayName("explico.json: the same projected facts, additive and absent when nothing is declared")
    inner class ExplicoJson {

        @Test
        @DisplayName("a rule carries its declared field names; a missing reason is null")
        fun declaredFieldNames() {
            val decl = verdictOf(rule(basic, "CTRL-V-DECL"), "rule CTRL-V-DECL")
            assertThat(decl.getValue("outcome").jsonPrimitive.content).isEqualTo("compliant")
            assertThat(decl.getValue("reason").jsonPrimitive.content).isEqualTo("commentary")
            val outcomeOnly = verdictOf(rule(basic, "CTRL-V-OUT"), "rule CTRL-V-OUT")
            assertThat(outcomeOnly.getValue("outcome").jsonPrimitive.content).isEqualTo("compliant")
            assertThat(outcomeOnly.getValue("reason")).isEqualTo(JsonNull)
        }

        @Test
        @DisplayName("a projected example carries the boolean, the words after the glyph and the reason")
        fun projectedExamples() {
            val yes = verdictOf(exampleJson(basic, "CTRL-V-DECL", "subject with an owner"), "the owner example")
            assertThat(yes.getValue("outcome").jsonPrimitive.booleanOrNull).isTrue()
            assertThat(yes.getValue("text").jsonPrimitive.content).isEqualTo("compliant")
            assertThat(yes.getValue("reason").jsonPrimitive.content).isEqualTo("Owner recorded")
            val no = verdictOf(exampleJson(basic, "CTRL-V-DECL", "subject without an owner"), "the no-owner example")
            assertThat(no.getValue("outcome").jsonPrimitive.booleanOrNull).isFalse()
            assertThat(no.getValue("text").jsonPrimitive.content).isEqualTo("not compliant")
        }

        @Test
        @DisplayName("every projected example agrees with its Markdown bullet (glyph, words, reason)")
        fun agreesWithMarkdown() {
            val section = examples(basic, "verdict_declared.md", "ctrl-v-decl")
            val projected = rule(basic, "CTRL-V-DECL").getValue("workedExamples").jsonArray.map { it.jsonObject }.filter { "verdict" in it }
            assertThat(projected).hasSize(2)
            for (example in projected) {
                val verdict = example.getValue("verdict").jsonObject
                val glyph = if (verdict.getValue("outcome").jsonPrimitive.booleanOrNull == true) "✅" else "❌"
                val lines = bullet(section, example.getValue("name").jsonPrimitive.content)
                assertThat(lines[0]).endsWith("— $glyph ${verdict.getValue("text").jsonPrimitive.content}")
                assertThat(lines[1].trim()).isEqualTo("*\"${verdict.getValue("reason").jsonPrimitive.content}\"*")
            }
        }

        @Test
        @DisplayName("no declaration -> no `verdict` key on the rule or on any of its examples")
        fun absentWhenUndeclared() {
            val raw = rule(basic, "CTRL-V-RAW")
            assertThat(raw).doesNotContainKey("verdict")
            assertThat(raw.getValue("workedExamples").jsonArray).isNotEmpty
            assertThat(raw.getValue("workedExamples").jsonArray.map { it.jsonObject }).noneMatch { "verdict" in it }
        }

        @Test
        @DisplayName("an example that fell back to raw JSON has no `verdict` key either, though its rule declares one")
        fun absentWhenNotProjected() {
            assertThat(rule(shapes, "CTRL-V-MIXB")).containsKey("verdict")
            val raw = exampleJson(shapes, "CTRL-V-MIXB", "reason that is not a string")
            assertThat(raw).doesNotContainKey("verdict")
            assertThat(exampleJson(shapes, "CTRL-V-MIXA", "well-formed object")).containsKey("verdict")
        }
    }

    @Nested
    @DisplayName("diff: editing only the verdict declaration")
    inner class DiffOfTheDeclaration {

        @Test
        @DisplayName("is DOCS_CHANGED -- it changes how examples read, not what the rule decides")
        fun docsChanged() {
            val changed = diff.entries.filter { it.category != DiffCategory.UNCHANGED }
            assertThat(changed.map { it.controlId to it.category }).containsExactly("CTRL-V-DIFF" to DiffCategory.DOCS_CHANGED)
        }
    }
}
