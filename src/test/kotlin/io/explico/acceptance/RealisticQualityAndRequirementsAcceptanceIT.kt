/**
 * Acceptance tests for the documentation-quality report and the requirement gap analysis. A compliance owner asks "is the
 * documentation complete?" and "which requirements have NO control?". Quality findings (a control with no description, no
 * framework, or a title shared with another) are always computed and shown in the index only when there is something to report;
 * `requirements` (opt-in) adds a requirements-coverage table in the file's order, `**No control**` for a gap, and the framework
 * strings no listed requirement matches (a typo catcher). Authored from a compliance-owner review, not from the frozen README
 * contract; with no requirements and no findings every output is byte-identical to before.
 * Fixtures: the acceptance pack, `realistic/quality/policies`, `realistic/requirements/`.
 */
package io.explico.acceptance

import io.explico.Explico
import io.explico.opa.OpaRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
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

@DisplayName("Realistic policies: documentation quality and requirement gap analysis")
class RealisticQualityAndRequirementsAcceptanceIT {

    companion object {
        private val pack = Path.of("src/test/resources/acceptance/policies")
        private val packExamples = Path.of("src/test/resources/acceptance/examples")
        private val packData = Path.of("src/test/resources/acceptance/data/release/data.json")
        private val quality = Path.of("src/test/resources/realistic/quality/policies")

        private val requirements = listOf("SOC 2 CC8.1", "ISO 27001 A.8.32", "DORA Art. 9", "SLSA v1.0 L2", "NIST 800-53 CM-3")
        private val typoRequirements = listOf("SOC 2 CC8.1", "ISO 27001 A.8.32", "DORA Art 9", "SLSA v1.0 L2")

        private lateinit var packIndex: String
        private lateinit var packIndexEmptyList: String
        private lateinit var packFiles: Map<String, String>
        private lateinit var packFilesEmptyList: Map<String, String>
        private lateinit var packJson: JsonObject
        private lateinit var withRequirements: Map<String, String>
        private lateinit var withRequirementsJson: JsonObject
        private lateinit var withTypos: Map<String, String>
        private lateinit var withTyposJson: JsonObject
        private lateinit var qualityIndex: String
        private lateinit var qualityJson: JsonObject
        private lateinit var qualityAgain: String

        private fun renderPack(requirements: List<String>? = null, json: Boolean = false): Map<String, String> {
            val set = Explico.load(pack)
            val examples = Explico.loadExamples(packExamples)
            return if (requirements == null) Explico.render(set, pack, examples, packData, false, json).files
            else Explico.render(set, pack, examples, packData, false, json, requirements).files
        }

        private fun parse(text: String?): JsonObject =
            if (text.isNullOrBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text).jsonObject

        @JvmStatic
        @BeforeAll
        fun renderAll() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            packFiles = renderPack()
            packFilesEmptyList = renderPack(emptyList())
            packIndex = packFiles.getValue("index.md")
            packIndexEmptyList = packFilesEmptyList.getValue("index.md")
            packJson = parse(renderPack(emptyList(), json = true)["explico.json"])
            withRequirements = renderPack(requirements, json = true)
            withRequirementsJson = parse(withRequirements["explico.json"])
            withTypos = renderPack(typoRequirements, json = true)
            withTyposJson = parse(withTypos["explico.json"])
            val q = Explico.render(Explico.load(quality), quality, null, null, false, true).files
            qualityIndex = q.getValue("index.md")
            qualityJson = parse(q["explico.json"])
            qualityAgain = Explico.render(Explico.load(quality), quality, null, null, false, true).files.getValue("index.md")
        }

        private fun JsonElement.arr(key: String): List<JsonElement> {
            assertThat(jsonObject.containsKey(key)).describedAs("a '%s' key in the JSON", key).isTrue()
            return jsonObject.getValue(key).jsonArray
        }

        private fun strings(e: JsonElement, key: String) = e.arr(key).map { it.jsonPrimitive.content }
        private fun section(index: String, heading: String): List<String> {
            val lines = index.lines()
            val start = lines.indexOf(heading)
            assertThat(start).describedAs("a '%s' heading", heading).isGreaterThanOrEqualTo(0)
            val end = lines.drop(start + 1).indexOfFirst { it.startsWith("## ") || it.startsWith("*Overall rendering coverage") }
            return if (end < 0) lines.drop(start + 1) else lines.subList(start + 1, start + 1 + end)
        }
    }

    @Nested
    @DisplayName("Documentation quality findings")
    inner class QualityFindings {

        @Test
        @DisplayName("the quality fixture reports exactly the missing description, the missing framework and the shared title")
        fun exactBullets() {
            assertThat(section(qualityIndex, "## Documentation quality").filter { it.isNotBlank() }).containsExactly(
                "- **Missing description (1):** [Q-003](quality-demo.md#q-003)",
                "- **No framework (1):** [Q-002](quality-demo.md#q-002)",
                "- **Duplicate title (2):** [Q-001](quality-demo.md#q-001), [Q-002](quality-demo.md#q-002) -- \"Shared\"",
            )
        }

        @Test
        @DisplayName("the section sits after the overall line and before the legend; an id-less rule is never reported")
        fun placementAndScope() {
            assertThat(qualityIndex.indexOf("## Documentation quality")).isGreaterThan(qualityIndex.indexOf("*Overall rendering coverage:"))
            assertThat(qualityIndex.indexOf("## Documentation quality")).isLessThan(qualityIndex.indexOf("## How to read this index"))
            assertThat(section(qualityIndex, "## Documentation quality").joinToString("\n")).doesNotContain("helper_without_metadata").doesNotContain("Q-004")
        }

        @Test
        @DisplayName("the JSON carries the same findings, by control id")
        fun jsonFindings() {
            val q = qualityJson.getValue("quality").jsonObject
            assertThat(strings(q, "missingDescription")).containsExactly("Q-003")
            assertThat(strings(q, "noFramework")).containsExactly("Q-002")
            val duplicate = q.arr("duplicateTitles").single().jsonObject
            assertThat(duplicate.getValue("title").jsonPrimitive.content).isEqualTo("Shared")
            assertThat(strings(duplicate, "controls")).containsExactly("Q-001", "Q-002")
        }

        @Test
        @DisplayName("a fully documented set (the acceptance pack) has no section and empty JSON findings")
        fun cleanPack() {
            assertThat(packIndex).doesNotContain("Documentation quality")
            val q = packJson.getValue("quality").jsonObject
            assertThat(q.arr("missingDescription")).isEmpty()
            assertThat(q.arr("noFramework")).isEmpty()
            assertThat(q.arr("duplicateTitles")).isEmpty()
        }

        @Test
        @DisplayName("deterministic")
        fun deterministic() {
            assertThat(qualityAgain).isEqualTo(qualityIndex)
        }
    }

    @Nested
    @DisplayName("Requirements coverage")
    inner class RequirementsCoverage {

        private val lines get() = section(withRequirements.getValue("index.md"), "## Requirements coverage").filter { it.isNotBlank() }

        @Test
        @DisplayName("4 of 5 requirements have a control; the table follows the file's order")
        fun summaryAndOrder() {
            assertThat(lines.first()).isEqualTo("*4 of 5 requirements have at least one control.*")
            assertThat(lines.drop(1).filter { it.startsWith("| ") && !it.startsWith("| Requirement") }.map { it.split("|")[1].trim() })
                .containsExactly("SOC 2 CC8.1", "ISO 27001 A.8.32", "DORA Art. 9", "SLSA v1.0 L2", "NIST 800-53 CM-3")
        }

        @Test
        @DisplayName("each row links its controls sorted by control id; a requirement nothing claims says **No control**")
        fun rows() {
            assertThat(lines).contains(
                "| SOC 2 CC8.1 | [REL-001](release-approvals.md#rel-001), [REL-002](release-evidence.md#rel-002), [REL-004](release-governance.md#rel-004) |",
                "| ISO 27001 A.8.32 | [REL-001](release-approvals.md#rel-001), [REL-003](release-provenance.md#rel-003) |",
                "| DORA Art. 9 | [REL-004](release-governance.md#rel-004) |",
                "| SLSA v1.0 L2 | [REL-003](release-provenance.md#rel-003) |",
                "| NIST 800-53 CM-3 | **No control** |",
            )
        }

        @Test
        @DisplayName("every framework string the controls name is on the list, so there is no 'not in the requirements list' line")
        fun noUnlisted() {
            assertThat(withRequirements.getValue("index.md")).doesNotContain("not in the requirements list")
        }

        @Test
        @DisplayName("placed right after the framework matrix and before the overall line")
        fun placement() {
            val index = withRequirements.getValue("index.md")
            assertThat(index.indexOf("## Requirements coverage")).isGreaterThan(index.indexOf("## Controls by framework"))
            assertThat(index.indexOf("## Requirements coverage")).isLessThan(index.indexOf("*Overall rendering coverage:"))
        }

        @Test
        @DisplayName("JSON: total, covered, items in list order with control ids, and no unlisted strings")
        fun json() {
            val r = withRequirementsJson.getValue("requirements").jsonObject
            assertThat(r.getValue("total").jsonPrimitive.int).isEqualTo(5)
            assertThat(r.getValue("covered").jsonPrimitive.int).isEqualTo(4)
            assertThat(r.arr("items").map { it.jsonObject.getValue("requirement").jsonPrimitive.content })
                .containsExactly("SOC 2 CC8.1", "ISO 27001 A.8.32", "DORA Art. 9", "SLSA v1.0 L2", "NIST 800-53 CM-3")
            assertThat(strings(r.arr("items")[0], "controls")).containsExactly("REL-001", "REL-002", "REL-004")
            assertThat(strings(r.arr("items")[4], "controls")).isEmpty()
            assertThat(r.arr("unlisted")).isEmpty()
        }
    }

    @Nested
    @DisplayName("A typo in a control's framework string")
    inner class Typos {

        @Test
        @DisplayName("the misspelt requirement has no control, and the real string is reported as not in the list")
        fun typoCaught() {
            val lines = section(withTypos.getValue("index.md"), "## Requirements coverage").filter { it.isNotBlank() }
            assertThat(lines.first()).isEqualTo("*3 of 4 requirements have at least one control.*")
            assertThat(lines).contains("| DORA Art 9 | **No control** |")
            assertThat(lines.last()).isEqualTo("*Framework strings not in the requirements list: DORA Art. 9.*")
        }

        @Test
        @DisplayName("JSON lists the unlisted string")
        fun jsonUnlisted() {
            assertThat(strings(withTyposJson.getValue("requirements"), "unlisted")).containsExactly("DORA Art. 9")
            assertThat(withTyposJson.getValue("requirements").jsonObject.getValue("covered").jsonPrimitive.int).isEqualTo(3)
        }
    }

    @Nested
    @DisplayName("Opt-in: nothing changes without a requirements list")
    inner class OffByDefault {

        @Test
        @DisplayName("no 'Requirements coverage' text, and an empty list renders exactly what omitting it does")
        fun emptyListIsIdentical() {
            assertThat(packIndex).doesNotContain("Requirements coverage")
            assertThat(packFilesEmptyList).isEqualTo(packFiles)
        }

        @Test
        @DisplayName("JSON requirements is null when none were given")
        fun jsonNull() {
            assertThat(packJson["requirements"]).isEqualTo(JsonNull)
        }

        @Test
        @DisplayName("the package documents are identical with or without requirements")
        fun packageDocsUnchanged() {
            for ((name, text) in packFiles) if (name != "index.md") assertThat(withRequirements.getValue(name)).describedAs(name).isEqualTo(text)
        }
    }
}
