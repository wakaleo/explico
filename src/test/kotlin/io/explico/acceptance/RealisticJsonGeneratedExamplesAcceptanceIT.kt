/**
 * The JSON export carries generated worked examples exactly when generation was requested, named mechanically like the card
 * ("satisfies every condition", "breaks only: ..."), flagged `generated`, with OPA's verdicts. Authored alongside the JSON export
 * (spec §14.45), not from the frozen README contract. Fixture: `realistic/generated-examples/policies`.
 */
package io.explico.acceptance

import io.explico.Explico
import io.explico.opa.OpaRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path

@DisplayName("Realistic policies: JSON export of generated examples")
class RealisticJsonGeneratedExamplesAcceptanceIT {

    companion object {
        private val policies = Path.of("src/test/resources/realistic/generated-examples/policies")
        private lateinit var withGeneration: List<JsonElement>
        private lateinit var withoutGeneration: List<JsonElement>
        private lateinit var markdown: String

        private fun rules(generate: Boolean): Pair<List<JsonElement>, Map<String, String>> {
            val files = Explico.render(Explico.load(policies), policies, null, null, generate, true).files
            val doc = Json.parseToJsonElement(files.getValue("explico.json")).jsonObject
            return doc.getValue("packages").jsonArray.flatMap { it.jsonObject.getValue("rules").jsonArray } to files
        }

        @JvmStatic
        @BeforeAll
        fun render() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            val (on, files) = rules(true)
            withGeneration = on
            markdown = files.filterKeys { it != "index.md" && it != "explico.json" }.values.joinToString("\n")
            withoutGeneration = rules(false).first
        }
    }

    @Test
    @DisplayName("off by default: every generatedExamples array is empty")
    fun emptyWithoutGeneration() {
        assertThat(withoutGeneration).isNotEmpty()
        for (r in withoutGeneration) assertThat(r.jsonObject.getValue("generatedExamples").jsonArray).isEmpty()
    }

    @Test
    @DisplayName("with generation: some rules carry examples, each flagged generated, named as on the card, with a verdict")
    fun presentWithGeneration() {
        val entries = withGeneration.flatMap { it.jsonObject.getValue("generatedExamples").jsonArray }.map { it.jsonObject }
        assertThat(entries).isNotEmpty()
        for (e in entries) {
            assertThat(e.getValue("generated").jsonPrimitive.boolean).isTrue()
            assertThat(e.getValue("name").jsonPrimitive.content).matches("satisfies every condition.*|breaks only: .*")
            // A "satisfies" example matched, a "breaks only" near-miss did not -- OPA's verdict, as on the card.
            assertThat(e.getValue("matched").jsonPrimitive.boolean).isEqualTo(e.getValue("name").jsonPrimitive.content.startsWith("satisfies"))
        }
    }

    @Test
    @DisplayName("every generated example's name appears on a card as an example entry")
    fun namesAgreeWithTheCards() {
        val names = withGeneration.flatMap { it.jsonObject.getValue("generatedExamples").jsonArray }.map { it.jsonObject.getValue("name").jsonPrimitive.content }
        for (name in names.map { it.replace(Regex("\\s+"), " ") }) {
            // the card words a broken condition with links; the JSON without -- compare with link markup stripped
            val stripped = markdown.replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            assertThat(stripped).describedAs(name).contains("**$name**")
        }
    }
}
