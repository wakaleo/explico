/**
 * Acceptance tests for the machine-readable JSON result (`render(json = true)` -> `explico.json`, schema `explico/render/1`;
 * `DiffReport.json`, schema `explico/diff/1`). Downstream tools (GRC dashboards, scripts, CI) consume it, so it must say the same
 * thing the Markdown says -- every condition's `text` is the exact phrase the card shows -- and carry the facts the Markdown only
 * summarises (all worked-example fixtures, the full resolved reference-data value). Opt-in, so with it off every output is
 * byte-identical to before. Authored from a request for a structured format, not from the frozen README contract; the frozen
 * Tier-1 assertions and every golden stay untouched.
 */
package io.explico.acceptance

import io.explico.Explico
import io.explico.opa.OpaRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
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

@DisplayName("Realistic policies: JSON output")
class RealisticJsonExportAcceptanceIT {

    companion object {
        private val pack = Path.of("src/test/resources/acceptance/policies")
        private val packExamples = Path.of("src/test/resources/acceptance/examples")
        private val packData = Path.of("src/test/resources/acceptance/data/release/data.json")
        private val realistic = Path.of("src/test/resources/realistic")
        private val diffs = Path.of("src/test/resources/diff")

        private lateinit var packOff: Map<String, String>
        private lateinit var packOn: Map<String, String>
        private lateinit var packOnAgain: Map<String, String>
        private lateinit var packJson: JsonObject
        private lateinit var effects: JsonObject
        private lateinit var ruleMeta: JsonObject
        private lateinit var packageMeta: JsonObject
        private lateinit var referenceData: JsonObject
        private lateinit var referenceNoData: JsonObject
        private lateinit var allCategories: io.explico.DiffReport
        private lateinit var changeSummary: io.explico.DiffReport

        /** An absent or blank document reads as `{}`, so a missing feature fails each test on an assertion, not in setup. */
        private fun parse(text: String?): JsonObject =
            if (text.isNullOrBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text).jsonObject

        private fun renderJson(dir: Path, data: Path? = null): Map<String, String> {
            val set = Explico.load(dir)
            return Explico.render(set, dir, null, data, false, true).files
        }

        @JvmStatic
        @BeforeAll
        fun renderAll() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            val set = Explico.load(pack)
            val examples = Explico.loadExamples(packExamples)
            packOff = Explico.render(set, pack, examples, packData).files
            packOn = Explico.render(set, pack, examples, packData, false, true).files
            packOnAgain = Explico.render(set, pack, examples, packData, false, true).files
            packJson = parse(packOn["explico.json"])
            effects = parse(renderJson(realistic.resolve("effect-of-match/policies"))["explico.json"])
            ruleMeta = parse(renderJson(realistic.resolve("metadata-passthrough/policies"))["explico.json"])
            packageMeta = parse(renderJson(realistic.resolve("metadata-passthrough-package/policies"))["explico.json"])
            val refDir = realistic.resolve("reference-data/policies")
            referenceData = parse(renderJson(refDir, realistic.resolve("reference-data/data"))["explico.json"])
            referenceNoData = parse(renderJson(refDir)["explico.json"])
            allCategories = Explico.diff(Explico.load(diffs.resolve("diff-all-categories/old")), Explico.load(diffs.resolve("diff-all-categories/new")))
            changeSummary = Explico.diff(Explico.load(diffs.resolve("change-summary/old")), Explico.load(diffs.resolve("change-summary/new")))
        }

        private fun JsonElement.str(key: String): String? = jsonObject[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
        private fun JsonElement.arr(key: String): List<JsonElement> {
            assertThat(jsonObject.containsKey(key)).describedAs("a '%s' array in the JSON", key).isTrue()
            return jsonObject.getValue(key).jsonArray
        }
        private fun JsonElement.strings(key: String): List<String> = arr(key).map { it.jsonPrimitive.content }
        private fun JsonElement.coverage(): Triple<Int, Int, Int> {
            assertThat(jsonObject.containsKey("coverage")).describedAs("a 'coverage' object in the JSON").isTrue()
            return jsonObject.getValue("coverage").jsonObject.let {
                Triple(it.getValue("rendered").jsonPrimitive.int, it.getValue("total").jsonPrimitive.int, it.getValue("percent").jsonPrimitive.int)
            }
        }

        private fun packages(doc: JsonObject): List<JsonElement> = doc.arr("packages")
        private fun rule(doc: JsonObject, packagePath: String, name: String, controlId: String? = null): JsonObject {
            val pkg = packages(doc).firstOrNull { it.str("path") == packagePath }
            assertThat(pkg).describedAs("package %s in the JSON", packagePath).isNotNull()
            val found = pkg!!.arr("rules").firstOrNull { it.str("name") == name && (controlId == null || it.str("controlId") == controlId) }
            assertThat(found).describedAs("rule %s %s in the JSON", name, controlId).isNotNull()
            return found!!.jsonObject
        }
    }

    @Nested
    @DisplayName("Opt-in: off by default, and the Markdown is identical either way")
    inner class OffByDefault {

        @Test
        @DisplayName("no explico.json unless asked for")
        fun absentByDefault() {
            assertThat(packOff).doesNotContainKey("explico.json")
            assertThat(packOn).containsKey("explico.json")
        }

        @Test
        @DisplayName("the other files are exactly the same set, byte for byte, with or without json")
        fun markdownUnchanged() {
            assertThat(packOn.keys - "explico.json").isEqualTo(packOff.keys)
            for ((name, text) in packOff) assertThat(packOn.getValue(name)).describedAs(name).isEqualTo(text)
        }

        @Test
        @DisplayName("rendering twice gives byte-identical JSON, 2-space indented, ending in a newline")
        fun deterministicAndFormatted() {
            val text = packOn["explico.json"].orEmpty()
            assertThat(text).isNotBlank()
            assertThat(packOnAgain["explico.json"]).isEqualTo(text)
            assertThat(text).endsWith("}\n").contains("\n  \"packages\": [")
        }
    }

    @Nested
    @DisplayName("Top level")
    inner class TopLevel {

        @Test
        @DisplayName("schema, policy digest (the one the index shows) and overall coverage 22 of 23, 95%")
        fun header() {
            assertThat(packJson.str("schema")).isEqualTo("explico/render/1")
            val digestInIndex = Regex("sha256:([0-9a-f]{64})").find(packOn.getValue("index.md"))!!.groupValues[1]
            assertThat(packJson.str("policyDigest")).isEqualTo(digestInIndex)
            assertThat(packJson.coverage()).isEqualTo(Triple(22, 23, 95))
        }

        @Test
        @DisplayName("one entry per package document, sorted by path, each pointing at a file that exists")
        fun packagesMatchDocuments() {
            val paths = packages(packJson).map { it.str("path")!! }
            assertThat(paths).isEqualTo(paths.sorted()).hasSize(5)
            assertThat(paths).containsExactly("release.approvals", "release.evidence", "release.exemptions", "release.governance", "release.provenance")
            for (p in packages(packJson)) assertThat(packOn).containsKey(p.str("file"))
            assertThat(packOn.keys - "index.md" - "explico.json").hasSize(5)
        }
    }

    @Nested
    @DisplayName("REL-001 — Production change approval")
    inner class Rel001 {

        private val rel001 get() = rule(packJson, "release.approvals", "deny", "REL-001")

        @Test
        @DisplayName("metadata, anchor, effect of a match, source file and coverage")
        fun metadata() {
            assertThat(rel001.str("title")).isEqualTo("Production change approval")
            assertThat(rel001.strings("frameworks")).containsExactly("SOC 2 CC8.1", "ISO 27001 A.8.32")
            assertThat(rel001.str("anchor")).isEqualTo("rel-001")
            assertThat(rel001.str("effectOfMatch")).isEqualTo("denied")
            assertThat(rel001.str("sourceFile")).isEqualTo("approvals/change_approval.rego")
            assertThat(rel001.coverage()).isEqualTo(Triple(4, 4, 100))
        }

        @Test
        @DisplayName("two bodies with their produced messages; the first has exactly the two card conditions")
        fun bodies() {
            val bodies = rel001.arr("bodies")
            assertThat(bodies.map { it.str("produces") }).containsExactly(
                "release [deployment id] has no approved change ticket",
                "change [change id] was approved by its author",
            )
            val conditions = bodies[0].arr("conditions")
            assertThat(conditions).hasSize(2)
            val cardLines = packOn.getValue("release-approvals.md").lines()
            for (c in conditions) {
                assertThat(c.str("kind")).isIn("comparison", "truthy")
                assertThat(c.jsonObject["rendered"]?.jsonPrimitive?.boolean == true).isTrue()
                assertThat(cardLines).contains("- " + c.str("text"))
            }
        }

        @Test
        @DisplayName("ALL six fixtures in fixture order with the verdict matrix: only 02 and 03 match, 02 is Situation 1")
        fun workedExamples() {
            val examples = rel001.arr("workedExamples")
            assertThat(examples.map { it.str("name") }).containsExactly(
                "approved standard release", "hotfix without change ticket", "self-approved change",
                "failed security scan", "exempt legacy service", "unsigned artifact in freeze window",
            )
            assertThat(examples.map { it.jsonObject.getValue("matched").jsonPrimitive.boolean })
                .containsExactly(false, true, true, false, false, false)
            val hotfix = examples[1].jsonObject
            assertThat(hotfix.str("outcome")).isEqualTo("❌ denied")
            assertThat(hotfix.strings("messages")).containsExactly("release rel-1002 has no approved change ticket")
            assertThat(hotfix.arr("situations").map { it.jsonPrimitive.int }).containsExactly(1)
            assertThat(examples[0].str("outcome")).isEqualTo("✅ allowed")
        }
    }

    @Nested
    @DisplayName("Helpers in the pack")
    inner class Helpers {

        @Test
        @DisplayName("all_checks_passed: its only condition is unrendered, with verbatim source and a reason, and 0%")
        fun allChecksPassed() {
            val rule = rule(packJson, "release.governance", "all_checks_passed")
            val condition = rule.arr("bodies").single().arr("conditions").single().jsonObject
            assertThat(condition.str("kind")).isEqualTo("unrendered")
            assertThat(condition["rendered"]?.jsonPrimitive?.boolean).isEqualTo(false)
            assertThat(condition["text"]).isEqualTo(JsonNull)
            assertThat(condition.str("source")).contains("every check in")
            assertThat(condition.str("reason")).isNotBlank()
            assertThat(rule.coverage()).isEqualTo(Triple(0, 1, 0))
            assertThat(rule.str("effectOfMatch")).isNull()
        }

        @Test
        @DisplayName("exempt_service: no effect of a match, and its reference data is the exempt-services list")
        fun exemptService() {
            val rule = rule(packJson, "release.exemptions", "exempt_service")
            assertThat(rule.str("effectOfMatch")).isNull()
            val ref = rule.arr("referenceData").single().jsonObject
            assertThat(ref.str("path")).isEqualTo("data.release.exempt_services")
            assertThat(ref.str("breadcrumb")).isEqualTo("data ▸ release ▸ exempt services")
            assertThat(ref.getValue("defined").jsonPrimitive.boolean).isTrue()
            assertThat(ref.getValue("value")).isEqualTo(Json.parseToJsonElement("[\"legacy-batch\"]"))
        }
    }

    @Nested
    @DisplayName("The JSON never says more than the Markdown")
    inner class AgreesWithMarkdown {

        @Test
        @DisplayName("every rendered condition's text is a bullet in its package document (a link in the card is stripped in the JSON)")
        fun everyTextIsACardBullet() {
            var checked = 0
            for (p in packages(packJson)) {
                val bullets = packOn.getValue(p.str("file")!!).lines().filter { it.startsWith("- ") }
                    .map { it.replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1") }
                for (r in p.arr("rules")) for (b in r.arr("bodies")) for (c in b.arr("conditions")) {
                    if (c.jsonObject["rendered"]?.jsonPrimitive?.boolean == true) {
                        assertThat(bullets).describedAs("%s: %s", p.str("path"), c.str("text")).contains("- " + c.str("text"))
                        checked++
                    }
                }
            }
            assertThat(checked).isGreaterThan(15)
        }

        @Test
        @DisplayName("rule references carry the target as a link object")
        fun ruleReferenceLinks() {
            val rel002 = rule(packJson, "release.evidence", "deny", "REL-002")
            val references = rel002.arr("bodies").flatMap { it.arr("conditions") }.filter { it.str("kind") == "ruleReference" }
            assertThat(references).isNotEmpty()
            assertThat(references.map { it.jsonObject.getValue("link").jsonObject.str("rule") }).contains("is_release_candidate", "exempt_service")
        }
    }

    @Nested
    @DisplayName("Fixtures with other features")
    inner class OtherFeatures {

        @Test
        @DisplayName("effect of a match: deny and violation denied, allow allowed, flag and a function none; parameters listed")
        fun effects() {
            fun effect(id: String) = packages(effects).single().arr("rules").single { it.str("controlId") == id }
            assertThat(effect("EM-001").str("effectOfMatch")).isEqualTo("denied")
            assertThat(effect("EM-002").str("effectOfMatch")).isEqualTo("denied")
            assertThat(effect("EM-003").str("effectOfMatch")).isEqualTo("allowed")
            assertThat(effect("EM-004").str("effectOfMatch")).isNull()
            assertThat(effect("EM-005").str("effectOfMatch")).isNull()
            assertThat(effect("EM-005").strings("parameters")).containsExactly("x")
            assertThat(effects.coverage()).isEqualTo(Triple(5, 5, 100))
        }

        @Test
        @DisplayName("rule-level authors, related resources and custom keys; the nested-object key is absent")
        fun ruleMetadata() {
            val rule = rule(ruleMeta, "meta.ownership", "deny", "META-001")
            assertThat(rule.arr("authors").map { it.str("name") to it.str("email") }).containsExactly("Ann Owner" to "ann@example.com", "Bob Reviewer" to null)
            assertThat(rule.arr("relatedResources").map { it.str("ref") to it.str("description") })
                .containsExactly("https://example.com/runbook" to "Runbook", "https://example.com/ticket/123" to null)
            assertThat(rule.jsonObject.getValue("custom").jsonObject.mapValues { it.value.jsonPrimitive.content })
                .containsExactly(org.assertj.core.api.Assertions.entry("owner", "platform-team"), org.assertj.core.api.Assertions.entry("tags", "tier1, production"))
        }

        @Test
        @DisplayName("package-level authors, related resources and custom keys")
        fun packageMetadata() {
            val pkg = packages(packageMeta).single()
            assertThat(pkg.str("title")).isEqualTo("Ownership package")
            assertThat(pkg.arr("authors").map { it.str("email") }).containsExactly("pat@example.com")
            assertThat(pkg.arr("relatedResources").map { it.str("ref") }).containsExactly("https://example.com/package-runbook")
            assertThat(pkg.jsonObject.getValue("custom").jsonObject.keys).containsExactly("owner", "tags")
        }

        @Test
        @DisplayName("reference data carries the FULL resolved value (not the card's 5-element cut) and defined=false for an undefined path")
        fun referenceDataValues() {
            val regions = rule(referenceData, "refdata.limits", "deny", "RD-001").arr("referenceData").single().jsonObject
            assertThat(regions.str("path")).isEqualTo("data.limits.regions")
            assertThat(regions.getValue("value").jsonArray).hasSize(7)
            val missing = rule(referenceData, "refdata.limits", "deny", "RD-002").arr("referenceData").single().jsonObject
            assertThat(missing.getValue("defined").jsonPrimitive.boolean).isFalse()
            assertThat(missing.getValue("value")).isEqualTo(JsonNull)
        }

        @Test
        @DisplayName("without a data directory every referenceData array is empty")
        fun noDataDirectory() {
            for (p in packages(referenceNoData)) for (r in p.arr("rules")) assertThat(r.arr("referenceData")).isEmpty()
        }
    }

    @Nested
    @DisplayName("Diff JSON (schema explico/diff/1)")
    inner class DiffJson {

        private val all get() = parse(allCategories.json)

        @Test
        @DisplayName("DiffReport.json is the document, with the schema and the summary counts")
        fun summary() {
            assertThat(all.str("schema")).isEqualTo("explico/diff/1")
            val counts = all.jsonObject.getValue("summary").jsonObject.mapValues { it.value.jsonPrimitive.int }
            assertThat(counts).containsOnly(
                org.assertj.core.api.Assertions.entry("REMOVED", 1), org.assertj.core.api.Assertions.entry("ADDED", 1),
                org.assertj.core.api.Assertions.entry("LOGIC_CHANGED", 1), org.assertj.core.api.Assertions.entry("DOCS_CHANGED", 1),
                org.assertj.core.api.Assertions.entry("UNCHANGED", 4),
            )
        }

        @Test
        @DisplayName("changes in section order; REL-002 and unchanged controls are not listed")
        fun changes() {
            val changes = all.arr("changes")
            assertThat(changes.map { it.str("category") to it.str("controlId") }).containsExactly(
                "REMOVED" to "REL-001", "ADDED" to "REL-005", "LOGIC_CHANGED" to "REL-003", "DOCS_CHANGED" to "REL-004",
            )
            assertThat(allCategories.json).doesNotContain("REL-002")
        }

        @Test
        @DisplayName("REL-003's details are exactly the two condition sub-bullets; REL-004 names the fields that differ")
        fun details() {
            val changes = all.arr("changes")
            assertThat(changes[2].strings("details")).containsExactly(
                "condition removed: `artifact ▸ source branch` does not start with `\"release/\"`",
                "condition added: `artifact ▸ source branch` does not start with `\"releases/\"`",
            )
            assertThat(changes[2].str("anchor")).isEqualTo("rel-003")
            assertThat(changes[3].strings("fields")).containsExactly("title", "description")
            assertThat(changes[3].str("anchor")).isNull()
        }

        @Test
        @DisplayName("each LOGIC_CHANGED shape yields the exact detail strings the Markdown change summary shows")
        fun changeSummaryShapes() {
            val changes = parse(changeSummary.json).arr("changes").associateBy { it.str("controlId") }
            assertThat(changes.getValue("CS-001").strings("details")).containsExactly("condition added: `b` is `2`", "message added: \"b is two\"", "bodies: 1 -> 2")
            assertThat(changes.getValue("CS-003").strings("details")).containsExactly("default outcome changed")
            assertThat(changes.getValue("CS-005").strings("details")).containsExactly("the rule's structure changed; see the source diff below")
            assertThat(changes.getValue("CS-006").strings("fields")).containsExactly("frameworks")
        }

        @Test
        @DisplayName("two diffs of the same directories give byte-identical JSON")
        fun deterministic() {
            val again = Explico.diff(Explico.load(diffs.resolve("diff-all-categories/old")), Explico.load(diffs.resolve("diff-all-categories/new")))
            assertThat(again.json).isEqualTo(allCategories.json)
        }
    }
}
