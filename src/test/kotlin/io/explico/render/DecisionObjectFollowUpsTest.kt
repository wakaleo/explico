/**
 * Spec §14.81: what the TDIE-18035 feature requests asked for, driven through the REAL pipeline (real `opa parse`/`eval`) over
 * `src/test/resources/followups/`:
 *  1. a call to an object-literal function in a rule's value is shown as the object it returns (a verdict when `custom.verdict` is declared and reads
 *     statically, else its fields), and stays an ordinary function call whenever that is not exactly true;
 *  2. a `sprintf` message in a value is shown with bracketed placeholders (only `%v`/`%s`/`%%`, verbs matching arguments);
 *  3. `emitSituations` writes companion rules that, evaluated by `opa` beside the originals, name the situation that applied;
 *  4. three small presentation bugs: parameter names of a pattern function, an empty situation heading, examples on a constant rule.
 */
package io.explico.render

import io.explico.Explico
import io.explico.RenderOptions
import io.explico.generate.SituationCompanion
import io.explico.model.RegoVersion
import io.explico.opa.OpaRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@DisplayName("Spec §14.81: decision-object follow-ups")
class DecisionObjectFollowUpsTest {

    companion object {
        private val policies = Path.of("src/test/resources/followups/policies")
        private val examples = Path.of("src/test/resources/followups/examples")

        private lateinit var files: Map<String, String>
        private lateinit var json: JsonObject

        @JvmStatic
        @BeforeAll
        fun renderAll() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            files = Explico.render(
                Explico.load(policies),
                RenderOptions(examples = Explico.loadExamples(examples), json = true, emitSituations = true),
            ).files
            json = Json.parseToJsonElement(files.getValue("explico.json")).jsonObject
        }

        private fun card(doc: String, id: String): String =
            files.getValue(doc).substringAfter("<a id=\"$id\"></a>").substringBefore("<a id=\"")

        private fun yieldsStructures(): List<JsonObject> {
            val found = mutableListOf<JsonObject>()
            fun walk(e: JsonElement) {
                when (e) {
                    is JsonObject -> {
                        (e["yieldsStructure"] as? JsonObject)?.let { found += it }
                        e.values.forEach { walk(it) }
                    }
                    is JsonArray -> e.forEach { walk(it) }
                    else -> {}
                }
            }
            walk(json)
            return found
        }
    }

    @Nested
    @DisplayName("1. an object-literal function in a value")
    inner class ObjectFunctions {
        private val decision get() = card("attestation_check.md", "attestation-check-decision")

        @Test
        fun `a declared verdict reads from the literal, with the reason and the remediation through the arguments`() {
            assertThat(decision).contains("*Then:* ❌ not compliant")
            assertThat(decision).contains("- *Reason:* result of function [`reason_for`](checks.md#checks-reason-for) for `report ▸ standards`, rule [`code`](#attestation-check-code), `\"Reported as failing\"`")
            assertThat(decision).contains("- *Remediation offered:* result of function [`fix_standard`](checks.md#checks-fix-standard) for `subject`, rule [`code`](#attestation-check-code)")
            assertThat(decision).contains("*Then:* ✅ compliant")
            assertThat(decision).contains("- *No remediation offered.*")
            assertThat(decision).doesNotContain("result of function [`non_compliant`]")
            assertThat(decision).doesNotContain("result of function [`compliant`]")
        }

        @Test
        fun `the card keeps its situations and its worked examples`() {
            assertThat(decision).contains("### Situation 1 — all of the following are true", "### Otherwise", "**Worked examples**")
        }

        @Test
        fun `without a declared verdict the object is listed as its fields, never read as a verdict`() {
            val plain = card("plain_flow.md", "plain-flow-decision")
            assertThat(plain).contains("an object, as returned by function [`non_compliant`](shared.md#shared-non-compliant), with:")
            assertThat(plain).contains("- `compliant`: `false`", "- `commentary`: `\"Flow is broken\"`", "- `actions`: an empty list", "- `notifications`: `[]`")
            assertThat(plain).doesNotContain("✅ compliant").doesNotContain("❌ not compliant")
        }

        @Test
        fun `a function with a condition is not inlined`() {
            val guarded = card("edge_cases.md", "edge-cases-decision")
            assertThat(guarded).contains("result of function [`guarded`]").doesNotContain("an object, as returned by")
        }

        @Test
        fun `a function with two definitions is not inlined`() {
            assertThat(card("edge_cases.md", "edge-cases-second")).contains("result of function [`twice`]").doesNotContain("an object, as returned by")
        }

        @Test
        fun `an outcome that is not a literal is not a verdict, so the fields are listed`() {
            val third = card("edge_cases.md", "edge-cases-third")
            assertThat(third).contains("an object, as returned by function [`verdict_of`]", "- `ok`: `passed`", "- `why`: `\"because\"`")
            assertThat(third).doesNotContain("✅").doesNotContain("❌")
        }

        @Test
        fun `a nested object literal is not modelled, so the call stays a call`() {
            assertThat(card("edge_cases.md", "edge-cases-fourth")).contains("result of function [`nested`]").doesNotContain("an object, as returned by")
        }

        @Test
        fun `the JSON gains the same structure beside the rendered text`() {
            val structures = yieldsStructures()
            val failing = structures.first { it["verdict"]?.jsonObject?.get("outcome")?.jsonPrimitive?.content == "false" }
            assertThat(failing["function"]!!.jsonPrimitive.content).isEqualTo("non_compliant")
            assertThat(failing["verdict"]!!.jsonObject["text"]!!.jsonPrimitive.content).isEqualTo("not compliant")
            assertThat(failing["reason"]!!.jsonPrimitive.content).contains("result of function `reason_for`")
            assertThat(failing["remediation"]!!.jsonArray).hasSize(1)
            val clean = structures.first { it["verdict"]?.jsonObject?.get("outcome")?.jsonPrimitive?.content == "true" }
            assertThat(clean["remediation"]!!.jsonArray).isEmpty()
            val fields = structures.first { it["fields"] != null && it["function"]!!.jsonPrimitive.content == "non_compliant" }
            assertThat(fields["fields"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }).contains("compliant", "commentary")
        }
    }

    @Nested
    @DisplayName("2. sprintf messages in a value")
    inner class Messages {
        private val decision get() = card("message_flows.md", "message-flows-decision")

        @Test
        fun `each branch shows the template with its argument humanised`() {
            assertThat(decision).contains("`\"Too many [parameter label] data flows are non-authoritative\"`")
            assertThat(decision).contains("`\"Too high a proportion of [parameter label] data flows are non-authoritative\"`")
            assertThat(decision).contains("`\"Some [parameter label] data flows were not authoritative\"`")
            assertThat(decision).contains("`\"All [parameter label] data flows are from authoritative sources\"`")
            assertThat(decision).doesNotContain("shown as source")
        }

        @Test
        fun `a plain input field is humanised like the Produces line`() {
            assertThat(card("message_flows.md", "message-flows-from-input")).contains("`\"service [service name] is unhealthy\"`")
        }

        @Test
        fun `a verb other than v or s keeps the author's own source, never a misaligned message`() {
            val counted = card("message_flows.md", "message-flows-counted")
            assertThat(counted).contains("sprintf(\"%d flows\", [n])").doesNotContain("[parameter n]")
        }

        @Test
        fun `verbs that do not match the arguments keep the source`() {
            assertThat(card("message_flows.md", "message-flows-excess")).contains("sprintf(\"flows\", [n])")
        }

        @Test
        fun `an indexed argument keeps the source`() {
            assertThat(card("message_flows.md", "message-flows-indexed")).contains("sprintf(\"first is %v\", [input.items[0]])")
        }

        @Test
        fun `a literal argument keeps the source`() {
            assertThat(card("message_flows.md", "message-flows-literal-arg")).contains("sprintf(\"limit %v\", [3])")
        }
    }

    @Nested
    @DisplayName("4. small presentation bugs")
    inner class SmallBugs {
        @Test
        fun `a pattern function's parameters are named from the first definition that names them`() {
            val card = card("message_flows.md", "message-flows-shown-percent")
            assertThat(card).contains("*Parameters: `violations`, `total`*").doesNotContain("`\$0`")
        }

        @Test
        fun `a situation reached only by a literal argument says so instead of an empty heading`() {
            val card = card("message_flows.md", "message-flows-shown-percent")
            assertThat(card).contains("### Situation 1 — when called with arguments matching `(_, 0)`")
            assertThat(card).doesNotContain("### Situation 1 — all of the following are true")
            assertThat(card).contains("### Situation 2 — all of the following are true")
        }

        @Test
        fun `a body with no conditions and no pattern says it has no conditions`() {
            assertThat(card("message_flows.md", "message-flows-tiers")).contains("### Situation 1 — no conditions")
        }

        @Test
        fun `a constant that reads the input keeps its examples, and a string value is not quoted twice`() {
            val board = card("constants.md", "constants-board")
            assertThat(board).contains("**Worked examples**")
            assertThat(card("constants.md", "constants-owner")).contains("*\"alice\"*").doesNotContain("\"\"alice\"\"")
        }

        @Test
        fun `a literal constant rule has no worked examples and its value is shown once`() {
            val constant = card("attestation_check.md", "attestation-check-code")
            assertThat(constant).contains("**Value:** \"STANDARD5\"").doesNotContain("Worked examples").doesNotContain("\"\"STANDARD5\"\"")
            assertThat(card("message_flows.md", "message-flows-max-count")).doesNotContain("Worked examples")
            val names = json["packages"]!!.jsonArray.flatMap { it.jsonObject["rules"]!!.jsonArray }
                .filter { it.jsonObject["name"]!!.jsonPrimitive.content == "code" }
            assertThat(names).isNotEmpty
            names.forEach { assertThat(it.jsonObject).doesNotContainKey("workedExamples") }
        }
    }

    @Nested
    @DisplayName("3. companion situation rules")
    inner class Companions {
        @TempDir
        lateinit var tmp: Path

        private fun situationOf(generatedDir: Path, fixture: String, query: String): JsonElement {
            val input = Json.parseToJsonElement(Files.readString(examples.resolve("$fixture.json"))).jsonObject["input"]!!
            val inputFile = Files.writeString(tmp.resolve("$fixture-input.json"), input.toString())
            return OpaRunner.eval(inputFile, listOf(policies, generatedDir), query)
        }

        private fun writeGenerated(): Path {
            val dir = tmp.resolve("generated")
            files.filterKeys { it.startsWith("situations/") }.forEach { (name, text) ->
                val target = dir.resolve(name.removePrefix("situations/"))
                Files.createDirectories(target.parent)
                Files.writeString(target, text)
            }
            return dir
        }

        @Test
        fun `a chain becomes the same chain with constant values, compiled beside the original`() {
            val text = files.getValue("situations/attestation/att.rego")
            assertThat(text).startsWith(SituationCompanion.MARKER)
            assertThat(text).contains("package attestation_check", "import data.checks.is_failing")
            assertThat(text).contains("decision__situation := 1 if {\n    is_failing(input.report.standards, code)\n} else := 2")
            val dir = writeGenerated()
            assertThat(situationOf(dir, "failing-report", "data.attestation_check.decision__situation")).isEqualTo(JsonPrimitive(1))
            assertThat(situationOf(dir, "clean-report", "data.attestation_check.decision__situation")).isEqualTo(JsonPrimitive(2))
        }

        @Test
        fun `on the acceptance pack every deny rule's companion agrees with the worked examples for every fixture`() {
            val pack = Path.of("src/test/resources/acceptance")
            val docs = Explico.render(
                Explico.load(pack.resolve("policies")),
                RenderOptions(examples = Explico.loadExamples(pack.resolve("examples")), dataDir = pack.resolve("data"), json = true, emitSituations = true),
            ).files
            val generated = tmp.resolve("pack-generated")
            docs.filterKeys { it.startsWith("situations/") }.forEach { (name, text) ->
                val target = generated.resolve(name.removePrefix("situations/"))
                Files.createDirectories(target.parent)
                Files.writeString(target, text)
            }
            val packJson = Json.parseToJsonElement(docs.getValue("explico.json")).jsonObject
            var compared = 0
            for (pkg in packJson["packages"]!!.jsonArray.map { it.jsonObject }) {
                val rule = pkg["rules"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it["name"]!!.jsonPrimitive.content == "deny" } ?: continue
                val packagePath = pkg["path"]!!.jsonPrimitive.content
                if (docs.filterKeys { it.startsWith("situations/") }.values.none { it.contains("package $packagePath\n") && it.contains("deny__situations") }) continue
                for (example in rule["workedExamples"]!!.jsonArray.map { it.jsonObject }) {
                    val fixtureFile = Files.list(Path.of("src/test/resources/acceptance/examples")).use { l ->
                        l.filter { f -> Json.parseToJsonElement(Files.readString(f)).jsonObject["name"]!!.jsonPrimitive.content == example["name"]!!.jsonPrimitive.content }.findFirst().get()
                    }
                    val input = Json.parseToJsonElement(Files.readString(fixtureFile)).jsonObject["input"]!!
                    val inputFile = Files.writeString(tmp.resolve("pack-input.json"), input.toString())
                    val actual = OpaRunner.eval(inputFile, listOf(pack.resolve("policies"), pack.resolve("data"), generated), "data.$packagePath.deny__situations")
                    val actualNumbers = (actual as? JsonArray).orEmpty().map { it.jsonPrimitive.content.toInt() }.sorted()
                    val expected = example["situations"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() }.sorted()
                    assertThat(actualNumbers).describedAs("$packagePath / ${example["name"]}").isEqualTo(expected)
                    compared++
                }
            }
            assertThat(compared).isGreaterThan(5)
        }

        @Test
        fun `a function with one else chain gets a companion taking the same arguments, which opa evaluates`() {
            val text = files.getValue("situations/messages/messages.rego")
            assertThat(text).contains("decision__situation(label, violations, total, fixes) := 1 if {", "} else := 2 if {", "} else := 4")
            val dir = writeGenerated()
            val input = Files.writeString(tmp.resolve("empty.json"), "{}")
            fun situation(args: String) = OpaRunner.eval(input, listOf(policies, dir), "data.message_flows.decision__situation($args)")
            assertThat(situation("1, 5, 10, []")).isEqualTo(JsonPrimitive(1))
            assertThat(situation("1, 2, 10, []")).isEqualTo(JsonPrimitive(2))
            assertThat(situation("1, 2, 100, []")).isEqualTo(JsonPrimitive(3))
            assertThat(situation("1, 0, 100, []")).isEqualTo(JsonPrimitive(4))
        }

        @Test
        fun `a function with several definitions is skipped, and the file says so`() {
            val text = files.getValue("situations/messages/messages.rego")
            assertThat(text).contains("# No companion rule for `shown_percent`: it is a function with several definitions")
            assertThat(text).doesNotContain("shown_percent__situation")
        }

        @Test
        fun `generated files never become policy input`() {
            val copy = tmp.resolve("copy")
            policies.toFile().copyRecursively(copy.toFile())
            val dir = writeGenerated()
            dir.toFile().copyRecursively(copy.resolve("gen").toFile())
            assertThat(Explico.load(copy).packages.map { it.path to it.rules.map { r -> r.name } })
                .isEqualTo(Explico.load(policies).packages.map { it.path to it.rules.map { r -> r.name } })
            assertThat(PolicyDigest.of(copy)).isEqualTo(PolicyDigest.of(policies))
        }

        @Test
        fun `Rego v0 policies are refused before any work`() {
            val v0 = Explico.load(policies).copy(regoVersion = RegoVersion.V0)
            assertThatThrownBy { Explico.render(v0, RenderOptions(emitSituations = true)) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("Rego v0")
        }

        @Test
        fun `without the option nothing is generated`() {
            val plain = Explico.render(Explico.load(policies)).files
            assertThat(plain.keys).noneMatch { it.startsWith("situations/") }
        }
    }
}
