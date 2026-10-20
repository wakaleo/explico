/**
 * Acceptance tests for rendering Rego's `every`: `every x in coll { body }` is now a nested "for every ... all of the following are
 * true" list, ALL-OR-NOTHING like comprehensions -- if the domain is not a plain path, or anything in the body cannot be rendered,
 * the whole block stays verbatim source. It states the one thing a reader could miss: it is vacuously true when the collection is
 * empty. Authored with the operator's approval to amend the frozen contract (the pack's `all_checks_passed` is an `every`); these
 * tests use `realistic/every`, the pack amendment is made in /tdd. Fixtures: `realistic/every/{policies,examples}`,
 * `realistic/every-diff/{old,new}`.
 */
package io.explico.acceptance

import io.explico.Explico
import io.explico.opa.OpaRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
import java.nio.file.Path

@DisplayName("Realistic policies: every")
class RealisticEveryAcceptanceIT {

    companion object {
        private const val TAIL = ", all of the following are true (vacuously true when there are none)"

        private val policies = Path.of("src/test/resources/realistic/every/policies")
        private val examples = Path.of("src/test/resources/realistic/every/examples")
        private val diffRoot = Path.of("src/test/resources/realistic/every-diff")

        private lateinit var files: Map<String, String>
        private lateinit var json: JsonObject
        private lateinit var diff: io.explico.DiffReport

        @JvmStatic
        @BeforeAll
        fun renderAll() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            val set = Explico.load(policies)
            files = Explico.render(set, policies, Explico.loadExamples(examples), null, false, true).files
            json = Json.parseToJsonElement(files.getValue("explico.json")).jsonObject
            diff = Explico.diff(Explico.load(diffRoot.resolve("old")), Explico.load(diffRoot.resolve("new")))
        }

        private val doc get() = files.getValue("every-demo.md")

        /** The card whose anchor is [id]: from its `<a id>` to the next card's anchor (or the end of the document). */
        private fun card(id: String): String = doc.substringAfter("<a id=\"$id\"></a>").substringBefore("<a id=\"")

        private fun lines(id: String): List<String> = card(id).lines()

        private fun rule(controlId: String): JsonObject =
            json.getValue("packages").jsonArray.flatMap { it.jsonObject.getValue("rules").jsonArray }
                .first { it.jsonObject["controlId"]?.jsonPrimitive?.content == controlId }.jsonObject

        private fun coverageLine(id: String) = lines(id).single { it.startsWith("*Rendering coverage:") }
    }

    @Nested
    @DisplayName("EV-001: one condition")
    inner class OneCondition {

        @Test
        @DisplayName("a 'for every' bullet with the condition nested under it, and a coverage footer that counts both")
        fun rendered() {
            val lines = lines("ev-001")
            val at = lines.indexOf("- for every check in `pipeline ▸ checks`$TAIL")
            assertThat(at).describedAs("the head bullet").isGreaterThanOrEqualTo(0)
            assertThat(lines[at + 1]).isEqualTo("  - `pipeline ▸ checks ▸ [each check] ▸ status` is `\"passed\"`")
            assertThat(card("ev-001")).doesNotContain("⚠")
            assertThat(coverageLine("ev-001")).isEqualTo("*Rendering coverage: 2 of 2 conditions*")
        }
    }

    @Nested
    @DisplayName("EV-002: key and value")
    inner class Keyed {

        @Test
        @DisplayName("names both variables and nests both conditions")
        fun keyed() {
            val lines = lines("ev-002")
            val at = lines.indexOf("- for every k, v in `labels`$TAIL")
            assertThat(at).isGreaterThanOrEqualTo(0)
            assertThat(lines.drop(at + 1).takeWhile { it.startsWith("  - ") }).hasSize(2)
            assertThat(coverageLine("ev-002")).isEqualTo("*Rendering coverage: 3 of 3 conditions*")
        }
    }

    @Nested
    @DisplayName("EV-003: several conditions in the body")
    inner class SeveralConditions {

        @Test
        @DisplayName("every body condition is nested, in source order")
        fun nested() {
            val lines = lines("ev-003")
            val at = lines.indexOf("- for every c in `containers`$TAIL")
            assertThat(at).isGreaterThanOrEqualTo(0)
            assertThat(lines.subList(at + 1, at + 3)).containsExactly(
                "  - `containers ▸ [each c] ▸ image` is not `\"\"`",
                "  - `containers ▸ [each c] ▸ privileged` is `false`",
            )
            assertThat(coverageLine("ev-003")).isEqualTo("*Rendering coverage: 3 of 3 conditions*")
        }
    }

    @Nested
    @DisplayName("EV-004: an every inside an every")
    inner class EveryInEvery {

        @Test
        @DisplayName("the inner block nests one level deeper, with its own condition two levels down")
        fun nestedEvery() {
            val lines = lines("ev-004")
            val outer = lines.indexOf("- for every g in `groups`$TAIL")
            assertThat(outer).isGreaterThanOrEqualTo(0)
            assertThat(lines[outer + 1]).isEqualTo("  - for every m in `groups ▸ [each g] ▸ members`$TAIL")
            assertThat(lines[outer + 2]).isEqualTo("    - `groups ▸ [each g] ▸ members ▸ [each m] ▸ active` is `true`")
            assertThat(coverageLine("ev-004")).isEqualTo("*Rendering coverage: 3 of 3 conditions*")
        }
    }

    @Nested
    @DisplayName("EV-005 and EV-006: all or nothing")
    inner class AllOrNothing {

        @Test
        @DisplayName("an unrenderable body keeps the WHOLE block as verbatim source, with nothing half-rendered")
        fun unrenderableBody() {
            val card = card("ev-005")
            assertThat(card).contains("⚠ **not rendered — shown as source:**").contains("every c in input.containers").contains("json.marshal(c)")
            assertThat(card).doesNotContain("for every")
            assertThat(coverageLine("ev-005")).isEqualTo("*Rendering coverage: 0 of 1 conditions*")
        }

        @Test
        @DisplayName("a literal collection (not a path) keeps the block as source")
        fun literalDomain() {
            val card = card("ev-006")
            assertThat(card).contains("⚠ **not rendered — shown as source:**").contains("every x in [1, 2]")
            assertThat(card).doesNotContain("for every")
            assertThat(coverageLine("ev-006")).isEqualTo("*Rendering coverage: 0 of 1 conditions*")
        }
    }

    @Nested
    @DisplayName("EV-007 and EV-008: in context")
    inner class InContext {

        @Test
        @DisplayName("a sibling condition before the block keeps its place")
        fun siblingFirst() {
            val lines = lines("ev-007")
            val sibling = lines.indexOfFirst { it.startsWith("- `env` is `\"production\"`") }
            val every = lines.indexOf("- for every check in `pipeline ▸ checks`$TAIL")
            assertThat(sibling).isGreaterThanOrEqualTo(0)
            assertThat(every).isEqualTo(sibling + 1)
            assertThat(coverageLine("ev-007")).isEqualTo("*Rendering coverage: 3 of 3 conditions*")
        }

        @Test
        @DisplayName("a rule reference inside the body stays a link, nested")
        fun ruleReferenceInside() {
            val lines = lines("ev-008")
            val at = lines.indexOf("- for every c in `containers`$TAIL")
            assertThat(at).isGreaterThanOrEqualTo(0)
            assertThat(lines[at + 1]).startsWith("  - ").contains("[`exempt_name`](#every-demo-exempt-name)")
            assertThat(lines[at + 2]).isEqualTo("  - `containers ▸ [each c] ▸ image` is not `\"\"`")
        }
    }

    @Nested
    @DisplayName("Worked examples and the rest of the document")
    inner class Rest {

        @Test
        @DisplayName("a path read inside the body shows its values across the elements in the worked examples")
        fun workedExampleValues() {
            val c = card("ev-001")
            assertThat(c).contains("`pipeline ▸ checks ▸ [each check] ▸ status`: `\"passed\", \"passed\"`")
            assertThat(c).contains("`pipeline ▸ checks ▸ [each check] ▸ status`: `\"passed\", \"failed\"`")
        }

        @Test
        @DisplayName("package coverage: 18 of 20 conditions (the two blocks left as source count against it)")
        fun packageCoverage() {
            assertThat(doc).contains("*Package rendering coverage: 18 of 20 conditions (90%)*")
        }

        @Test
        @DisplayName("deterministic")
        fun deterministic() {
            val set = Explico.load(policies)
            assertThat(Explico.render(set, policies, Explico.loadExamples(examples), null, false, true).files).isEqualTo(files)
        }
    }

    @Nested
    @DisplayName("JSON")
    inner class JsonOutput {

        @Test
        @DisplayName("an every condition has kind 'every', the head phrase as its text (a card bullet) and its conditions nested")
        fun everyCondition() {
            val condition = rule("EV-001").getValue("bodies").jsonArray.single().jsonObject.getValue("conditions").jsonArray.single().jsonObject
            assertThat(condition.getValue("kind").jsonPrimitive.content).isEqualTo("every")
            assertThat(condition.getValue("rendered").jsonPrimitive.content).isEqualTo("true")
            assertThat(condition.getValue("text").jsonPrimitive.content).isEqualTo("for every check in `pipeline ▸ checks`$TAIL")
            val nested = condition.getValue("conditions").jsonArray.single().jsonObject
            assertThat(nested.getValue("text").jsonPrimitive.content).isEqualTo("`pipeline ▸ checks ▸ [each check] ▸ status` is `\"passed\"`")
            assertThat(doc.lines()).contains("- " + condition.getValue("text").jsonPrimitive.content)
        }

        @Test
        @DisplayName("an every left as source is unrendered with its source and the reason 'every'")
        fun unrenderedEvery() {
            val condition = rule("EV-005").getValue("bodies").jsonArray.single().jsonObject.getValue("conditions").jsonArray.single().jsonObject
            assertThat(condition.getValue("kind").jsonPrimitive.content).isEqualTo("unrendered")
            assertThat(condition.getValue("rendered").jsonPrimitive.content).isEqualTo("false")
            assertThat(condition.getValue("text")).isEqualTo(kotlinx.serialization.json.JsonNull)
            assertThat(condition.getValue("reason").jsonPrimitive.content).isEqualTo("every")
            assertThat(condition.getValue("source").jsonPrimitive.content).contains("every c in input.containers")
        }
    }

    @Nested
    @DisplayName("Diff")
    inner class DiffBehaviour {

        private val changes: List<JsonElement> get() = Json.parseToJsonElement(diff.json).jsonObject.getValue("changes").jsonArray

        @Test
        @DisplayName("changing a condition INSIDE an every is named as a removed and an added condition")
        fun nestedChange() {
            val change = changes.single { it.jsonObject["controlId"]?.jsonPrimitive?.content == "ED-001" }
            assertThat(change.jsonObject.getValue("details").jsonArray.map { it.jsonPrimitive.content }).containsExactly(
                "condition removed: `pipeline ▸ checks ▸ [each check] ▸ status` is `\"passed\"`",
                "condition added: `pipeline ▸ checks ▸ [each check] ▸ status` is `\"ok\"`",
            )
        }

        @Test
        @DisplayName("only reformatting an every (whitespace, a comment) is UNCHANGED: it is hashed by structure, not by its source text")
        fun reformattingIsUnchanged() {
            assertThat(changes.map { it.jsonObject["controlId"]?.jsonPrimitive?.content }).containsExactly("ED-001")
            assertThat(diff.markdown).doesNotContain("ED-002")
        }
    }
}
