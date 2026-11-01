/**
 * Process-level test for the typed facts in the diff JSON (see RealisticDiffFactsAcceptanceIT for the contract): `explico diff --json` writes, for every LOGIC_CHANGED change,
 * a `facts` array parallel to `details` with `facts[i].sentence == details[i]`. Runs the real packaged CLI like [CliGroupByControlDiffProcessTest]. Requires `opa`.
 */
package io.explico.cli

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

class CliDiffFactsProcessTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(io.explico.opa.OpaRunner.isAvailable(), "opa binary not on PATH")
        }

        private val base = Path.of("src/test/resources/realistic/metadata-with-logic")
        private val old = base.resolve("old").toAbsolutePath().toString()
        private val new = base.resolve("new").toAbsolutePath().toString()
    }

    @Test
    @DisplayName("--json carries facts parallel to details for every LOGIC_CHANGED change, and none on a documentation-only one")
    fun factsInTheJsonFile(@TempDir tempDir: Path) {
        val json = tempDir.resolve("report.json")
        val result = runCli(listOf("diff", old, new, "--out", tempDir.resolve("report.md").toString(), "--json", json.toString()), tempDir)

        assertThat(result.exitCode).isEqualTo(0)
        val changes = Json.parseToJsonElement(Files.readString(json)).jsonObject.getValue("changes").jsonArray.map { it.jsonObject }
        val logic = changes.filter { it.getValue("category").jsonPrimitive.content == "LOGIC_CHANGED" }
        assertThat(logic).isNotEmpty()
        for (change in logic) {
            assertThat(change).describedAs("a LOGIC_CHANGED change").containsKey("facts")
            val sentences = change.getValue("facts").jsonArray.map { it.jsonObject.getValue("sentence").jsonPrimitive.content }
            assertThat(sentences).isEqualTo(change.getValue("details").jsonArray.map { it.jsonPrimitive.content })
        }
        assertThat(changes.filter { it.getValue("category").jsonPrimitive.content == "DOCS_CHANGED" }).allSatisfy { assertThat(it).doesNotContainKey("facts") }
    }
}
