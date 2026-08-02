/**
 * Smoke tests: `opa parse`/`opa inspect` succeed and decode into the DTOs for
 * every file in the acceptance pack, without asserting rendered content
 * (that's the Tier-1 acceptance tests' job). Requires the `opa` binary.
 */
package io.explico.opa

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.toList

class OpaRunnerSmokeTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
        }
    }

    private val policiesDir = Path.of("src/test/resources/acceptance/policies")
    private val dataDir = Path.of("src/test/resources/acceptance/data/release/data.json")
    private val examplesDir = Path.of("src/test/resources/acceptance/examples")

    @Test
    fun parsesEveryPolicyFileInTheAcceptancePack() {
        val regoFiles = Files.walk(policiesDir).use { paths ->
            paths.filter { it.toString().endsWith(".rego") }.toList()
        }
        assertThat(regoFiles).isNotEmpty()

        for (file in regoFiles) {
            val module = OpaRunner.parse(file)
            assertThat(module.pkg.path).describedAs("package path for %s", file).isNotEmpty()
            assertThat(module.rules).describedAs("rules for %s", file).isNotEmpty()
        }
    }

    @Test
    fun inspectsTheAcceptancePackAndReadsRel001Metadata() {
        val result = OpaRunner.inspect(policiesDir)

        assertThat(result.annotations).isNotEmpty()
        val rel001 = result.annotations.firstOrNull { it.annotations.custom?.controlId == "REL-001" }
        assertThat(rel001).isNotNull()
        assertThat(rel001!!.annotations.title).isEqualTo("Production change approval")
        assertThat(rel001.annotations.custom?.frameworks).containsExactly("SOC 2 CC8.1", "ISO 27001 A.8.32")
    }

    @Test
    fun evalsFixture02AgainstApprovalsAndMatchesTheVerdictMatrix() {
        // Fixture 02 "hotfix without change ticket" -> REL-001 S1, verbatim message per the
        // acceptance README's fixture verdict matrix (the oracle for §6.7).
        val inputFile = Files.createTempFile("explico-smoke-eval", ".json")
        try {
            val fixtureJson = Files.readString(examplesDir.resolve("02-hotfix-without-change-ticket.json"))
            val input = opaJson.parseToJsonElement(fixtureJson).jsonObject.getValue("input")
            Files.writeString(inputFile, input.toString())

            val value = OpaRunner.eval(inputFile, listOf(policiesDir, dataDir), "data.release.approvals")
            val messages = value.jsonObject.getValue("deny").jsonArray.map { it.jsonPrimitive.content }
            assertThat(messages).containsExactly("release rel-1002 has no approved change ticket")
        } finally {
            Files.deleteIfExists(inputFile)
        }
    }

    @Test
    fun evalFailsWithStderrOnAnInvalidQuery() {
        val inputFile = Files.createTempFile("explico-smoke-eval", ".json")
        Files.writeString(inputFile, "{}")
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy { OpaRunner.eval(inputFile, listOf(policiesDir), "data.release.approvals ===") }
                .isInstanceOf(OpaInvocationException::class.java)
        } finally {
            Files.deleteIfExists(inputFile)
        }
    }

    @Test
    fun parseFailsWithStderrOnInvalidRego() {
        val invalid = Files.createTempFile("explico-smoke", ".rego")
        Files.writeString(invalid, "package broken\n\nthis is not valid rego {{{")
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy { OpaRunner.parse(invalid) }
                .isInstanceOf(OpaInvocationException::class.java)
        } finally {
            Files.deleteIfExists(invalid)
        }
    }
}
