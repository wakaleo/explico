/**
 * Acceptance tests for the configurable required set: which documentation properties of a CONTROL are gaps -- the list `--fail-on-gaps` fails on (`RenderedDocs.gaps`).
 * `Explico.render(..., required = [...])` names them: `title`, `description`, `frameworks`, `fixture` (a matching worked example, spec §14.63) and `verdict` (a declared
 * `custom.verdict` on an object-valued rule, spec §14.63). Decisions (to be confirmed):
 *
 * - null (the default) is `description` + `frameworks`, byte-for-byte what every earlier version did; an empty list requires none; the order given and duplicates do not matter;
 * - duplicate-title gaps and requirement gaps are ALWAYS gaps, whatever is required; the rendered files never change (only `explico.json` echoes the effective set as
 *   `quality.required`, in canonical order);
 * - a gap line per control and property, a grouped control once, listed in the order description, frameworks, title, fixture, verdict, then duplicate titles;
 * - an unknown name, or `fixture` / `verdict` when no example fixtures were given (it would pass vacuously), is an `IllegalArgumentException` before any opa work.
 * Authored from the plan, NOT from the frozen acceptance-pack README. Facade only. Fixtures: `realistic/required-set/` (RS-1 complete, RS-2 no title, RS-3 no description,
 * RS-4 no frameworks, RS-5 never matched, RS-6 raw object, RS-7/RS-8 one shared title) and `realistic/quality-hints/grouped` (one control over two object-valued rules).
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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

@DisplayName("Realistic policies: the configurable required set")
class RealisticRequiredSetAcceptanceIT {

    companion object {
        private val base = Path.of("src/test/resources/realistic/required-set")
        private val policies = base.resolve("policies")
        private val examples = base.resolve("examples")

        private const val NO_DESCRIPTION = "control RS-3 has no description"
        private const val NO_FRAMEWORK = "control RS-4 names no framework"
        private const val NO_TITLE = "control RS-2 has no title"
        private const val NO_MATCH = "control RS-5 has no matching example"
        private const val NO_VERDICT = "control RS-6 returns an object but declares no custom.verdict"
        private const val DUPLICATE = "controls RS-7, RS-8 share the title \"Shared title\""

        private val canonical = listOf("title", "description", "frameworks", "fixture", "verdict")

        private lateinit var byDefault: io.explico.RenderedDocs
        private lateinit var defaultAgain: io.explico.RenderedDocs
        private lateinit var explicitDefault: io.explico.RenderedDocs

        private fun render(required: List<String>?, withExamples: Boolean = true, dir: Path = policies, exampleDir: Path = examples, group: Boolean = false): io.explico.RenderedDocs {
            val set = Explico.load(dir)
            val ex = if (withExamples) Explico.loadExamples(exampleDir) else null
            return Explico.render(set, dir, ex, json = true, groupByControl = group, required = required)
        }

        private fun gaps(required: List<String>?, withExamples: Boolean = true) = render(required, withExamples).gaps

        @JvmStatic
        @BeforeAll
        fun renderAll() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            byDefault = render(null)
            defaultAgain = render(null)
            explicitDefault = render(listOf("description", "frameworks"))
        }

        private fun requiredInJson(docs: io.explico.RenderedDocs): List<String> {
            val quality = Json.parseToJsonElement(docs.files.getValue("explico.json")).jsonObject.getValue("quality").jsonObject
            assertThat(quality).describedAs("the quality object").containsKey("required")
            return quality.getValue("required").jsonArray.map { it.jsonPrimitive.content }
        }

        /** explico.json without `quality.required`, as text. */
        private fun jsonWithoutRequired(docs: io.explico.RenderedDocs): String {
            val root = Json.parseToJsonElement(docs.files.getValue("explico.json")).jsonObject
            val quality = JsonObject(root.getValue("quality").jsonObject - "required")
            return JsonObject(root + ("quality" to quality)).toString()
        }
    }

    @Nested
    @DisplayName("The default is what it always was")
    inner class Default {

        @Test
        @DisplayName("no required set: a gap for a missing description, a missing framework and the shared title, in that order")
        fun defaultGaps() {
            assertThat(byDefault.gaps).containsExactly(NO_DESCRIPTION, NO_FRAMEWORK, DUPLICATE)
        }

        @Test
        @DisplayName("null is exactly [description, frameworks]: the same gaps and every file byte-for-byte")
        fun nullIsTheExplicitDefault() {
            assertThat(explicitDefault.gaps).isEqualTo(byDefault.gaps)
            assertThat(explicitDefault.files.toList()).isEqualTo(byDefault.files.toList())
        }

        @Test
        @DisplayName("two renders are byte-identical")
        fun deterministic() {
            assertThat(defaultAgain.gaps).isEqualTo(byDefault.gaps)
            assertThat(defaultAgain.files.toList()).isEqualTo(byDefault.files.toList())
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

    @Nested
    @DisplayName("Choosing the required properties")
    inner class Choosing {

        @Test
        @DisplayName("all five: one gap per control and property, ordered description, frameworks, title, fixture, verdict, then the shared title")
        fun allFive() {
            assertThat(gaps(canonical)).containsExactly(NO_DESCRIPTION, NO_FRAMEWORK, NO_TITLE, NO_MATCH, NO_VERDICT, DUPLICATE)
        }

        @Test
        @DisplayName("each name alone: exactly its own gap, plus the shared title that is always one")
        fun singleNames() {
            assertThat(gaps(listOf("title"))).containsExactly(NO_TITLE, DUPLICATE)
            assertThat(gaps(listOf("description"))).containsExactly(NO_DESCRIPTION, DUPLICATE)
            assertThat(gaps(listOf("frameworks"))).containsExactly(NO_FRAMEWORK, DUPLICATE)
            assertThat(gaps(listOf("fixture"))).containsExactly(NO_MATCH, DUPLICATE)
            assertThat(gaps(listOf("verdict"))).containsExactly(NO_VERDICT, DUPLICATE)
        }

        @Test
        @DisplayName("an empty list requires nothing: only the shared title is left")
        fun nothingRequired() {
            assertThat(gaps(emptyList())).containsExactly(DUPLICATE)
        }

        @Test
        @DisplayName("the order given and duplicates do not matter")
        fun orderAndDuplicates() {
            assertThat(gaps(listOf("verdict", "title", "verdict", "title"))).isEqualTo(gaps(listOf("title", "verdict")))
            assertThat(gaps(listOf("verdict", "title"))).containsExactly(NO_TITLE, NO_VERDICT, DUPLICATE)
        }

        @Test
        @DisplayName("title, description and frameworks need no example fixtures; without them the gaps are those and the shared title")
        fun withoutExamples() {
            assertThat(gaps(null, withExamples = false)).containsExactly(NO_DESCRIPTION, NO_FRAMEWORK, DUPLICATE)
            assertThat(gaps(listOf("title", "description", "frameworks"), withExamples = false)).containsExactly(NO_DESCRIPTION, NO_FRAMEWORK, NO_TITLE, DUPLICATE)
        }

        @Test
        @DisplayName("a grouped control (--group-by-control) over two object-valued rules is ONE verdict gap")
        fun groupedControlCountsOnce() {
            val docs = render(listOf("verdict"), dir = Path.of("src/test/resources/realistic/quality-hints/grouped/policies"),
                exampleDir = Path.of("src/test/resources/realistic/quality-hints/grouped/examples"), group = true)
            assertThat(docs.gaps).containsExactly("control QH-G returns an object but declares no custom.verdict")
        }
    }

    @Nested
    @DisplayName("Refused before any opa work")
    inner class Refused {

        @Test
        @DisplayName("an unknown name (case-sensitive) names itself and lists the valid names")
        fun unknownName() {
            for (bad in listOf("titel", "Title", "")) {
                assertThatThrownBy { render(listOf("description", bad)) }.describedAs("'$bad'").isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessageContaining("'$bad'").hasMessageContaining("title").hasMessageContaining("description").hasMessageContaining("frameworks")
                    .hasMessageContaining("fixture").hasMessageContaining("verdict")
            }
        }

        @Test
        @DisplayName("fixture or verdict without example fixtures would pass vacuously, so it is refused and says it needs example fixtures")
        fun needsExamples() {
            for (name in listOf("fixture", "verdict")) {
                assertThatThrownBy { render(listOf("title", name), withExamples = false) }.describedAs(name).isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessageContaining(name).hasMessageContaining("example")
            }
        }
    }

    @Nested
    @DisplayName("The files")
    inner class Files_ {

        @Test
        @DisplayName("explico.json echoes the effective set in canonical order: the default, all five, and a given set normalised")
        fun jsonEchoesTheSet() {
            assertThat(requiredInJson(byDefault)).containsExactly("description", "frameworks")
            assertThat(requiredInJson(render(canonical.reversed()))).containsExactly(*canonical.toTypedArray())
            assertThat(requiredInJson(render(listOf("verdict", "title", "title")))).containsExactly("title", "verdict")
            assertThat(requiredInJson(render(emptyList()))).isEmpty()
        }

        @Test
        @DisplayName("index.md, every package page and the rest of explico.json are identical whatever is required")
        fun filesDoNotDependOnTheSet() {
            val others = listOf(emptyList(), listOf("title"), canonical, listOf("fixture", "verdict")).map { render(it) }
            for (other in others) {
                assertThat(other.files.keys).isEqualTo(byDefault.files.keys)
                for ((name, text) in other.files) {
                    if (name == "explico.json") continue
                    assertThat(text).describedAs(name).isEqualTo(byDefault.files.getValue(name))
                }
                assertThat(jsonWithoutRequired(other)).isEqualTo(jsonWithoutRequired(byDefault))
            }
        }
    }
}
