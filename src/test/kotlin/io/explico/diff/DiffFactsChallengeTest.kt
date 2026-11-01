/**
 * CHALLENGE cases for the typed diff facts (spec §14.78) that need the real `opa` binary: `--group-by-control` entries and a real string literal with a backslash and
 * a quote going through the JSON. The main contract is RealisticDiffFactsAcceptanceIT.
 */
package io.explico.diff

import io.explico.Explico
import io.explico.opa.OpaRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DiffFactsChallengeTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
        }

        private fun policy(pkg: String, id: String, condition: String) = "package $pkg\n\nimport rego.v1\n\n" +
            "# METADATA\n# scope: document\n# title: Title $id\n# description: D\n# custom:\n#   control-id: $id\n#   frameworks:\n#     - F 1\n" +
            "deny contains msg if {\n\t$condition\n\tmsg := \"denied\"\n}\n"

        private fun write(dir: Path, name: String, text: String) = dir.resolve(name).also { Files.createDirectories(it.parent); Files.writeString(it, text) }

        private fun changes(json: String) = Json.parseToJsonElement(json).jsonObject.getValue("changes").jsonArray.map { it.jsonObject }
    }

    @Test
    @DisplayName("--group-by-control: each rule of a shared control id has its own facts, parallel to its own details")
    fun groupedControl(@TempDir dir: Path) {
        write(dir, "old/a.rego", policy("a", "SHARED", "input.amount > 100"))
        write(dir, "old/b.rego", policy("b", "SHARED", "input.amount > 100"))
        write(dir, "new/a.rego", policy("a", "SHARED", "input.amount > 50"))
        write(dir, "new/b.rego", policy("b", "SHARED", "input.amount > 100\n\tinput.region == \"eu\""))

        val report = Explico.diff(Explico.load(dir.resolve("old")), Explico.load(dir.resolve("new")), groupByControl = true)
        val logic = changes(report.json).filter { it.getValue("category").jsonPrimitive.content == "LOGIC_CHANGED" }

        assertThat(logic).hasSize(2)
        for (change in logic) {
            val facts = change.getValue("facts").jsonArray.map { it.jsonObject }
            assertThat(facts.map { it.getValue("sentence").jsonPrimitive.content }).isEqualTo(change.getValue("details").jsonArray.map { it.jsonPrimitive.content })
        }
        assertThat(logic.map { change -> change.getValue("facts").jsonArray.map { it.jsonObject.getValue("kind").jsonPrimitive.content } })
            .containsExactlyInAnyOrder(listOf("condition-removed", "condition-added"), listOf("condition-added"))
    }

    @Test
    @DisplayName("a string literal with a backslash and an escaped quote reaches the JSON phrase unchanged, equal to the sentence's tail")
    fun escapedLiteral(@TempDir dir: Path) {
        write(dir, "old/a.rego", policy("a", "C-1", """input.path == "C:\\dir\\\"x\"""""))
        write(dir, "new/a.rego", policy("a", "C-1", "input.path == \"other\""))

        val report = Explico.diff(Explico.load(dir.resolve("old")), Explico.load(dir.resolve("new")))
        val removed = changes(report.json).single().getValue("facts").jsonArray.map { it.jsonObject }.first { it.getValue("kind").jsonPrimitive.content == "condition-removed" }
        val phrase = removed.getValue("condition").jsonPrimitive.content

        assertThat(phrase).contains("C:").contains("dir")
        assertThat(removed.getValue("sentence").jsonPrimitive.content).isEqualTo("condition removed: $phrase")
        assertThat(report.json).describedAs("the raw JSON is valid and escapes the backslash").contains("\\\\")
    }
}
