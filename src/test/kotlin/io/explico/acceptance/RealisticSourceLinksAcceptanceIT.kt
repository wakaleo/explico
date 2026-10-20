/**
 * Acceptance tests for `sourceUrl`: with a URL template (`{file}`, `{line}`) every card's "defined in", every package page's source
 * files and every index row link to the exact Rego line in the repository, so an auditor is one click from the code under review.
 * Opt-in: without it every Markdown file is byte-identical to before. Authored from a compliance-owner request, not from the frozen
 * README contract. Fixture: the acceptance pack (rule rows verified against the files: REL-001/003/004 at 16, REL-002 at 17,
 * is_release_candidate 28, all_checks_passed 38, exempt_service 5).
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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path

@DisplayName("Realistic policies: source links")
class RealisticSourceLinksAcceptanceIT {

    companion object {
        private const val BASE = "https://git.example.com/org/repo/blob/abc123/policies/"
        private const val TEMPLATE = BASE + "{file}#L{line}"

        private val pack = Path.of("src/test/resources/acceptance/policies")

        private lateinit var linked: Map<String, String>
        private lateinit var linkedJson: JsonObject
        private lateinit var plain: Map<String, String>
        private lateinit var plainExplicitNull: Map<String, String>
        private lateinit var plainJson: JsonObject
        private lateinit var noLine: Map<String, String>
        private lateinit var fileTwice: Map<String, String>

        private fun render(sourceUrl: String?, json: Boolean = false, explicitNull: Boolean = true): Map<String, String> {
            val set = Explico.load(pack)
            return if (explicitNull) Explico.render(set, pack, null, null, false, json, emptyList(), sourceUrl).files
            else Explico.render(set, pack, null, null, false, json).files
        }

        private fun parse(text: String?): JsonObject = if (text.isNullOrBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text).jsonObject

        @JvmStatic
        @BeforeAll
        fun renderAll() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            linked = render(TEMPLATE, json = true)
            linkedJson = parse(linked["explico.json"])
            plain = render(null, explicitNull = false)
            plainExplicitNull = render(null)
            plainJson = parse(render(null, json = true)["explico.json"])
            noLine = render("https://git.example.com/r/{file}")
            fileTwice = render("https://x.example/{file}?f={file}&l={line}")
        }

        private fun rule(doc: JsonObject, packagePath: String, name: String, controlId: String? = null): JsonElement {
            val pkg = doc["packages"]?.jsonArray?.firstOrNull { it.jsonObject["path"]?.jsonPrimitive?.content == packagePath }
            assertThat(pkg).describedAs("package %s in the JSON", packagePath).isNotNull()
            val found = pkg!!.jsonObject.getValue("rules").jsonArray.firstOrNull {
                it.jsonObject["name"]?.jsonPrimitive?.content == name && (controlId == null || it.jsonObject["controlId"]?.jsonPrimitive?.content == controlId)
            }
            assertThat(found).describedAs("rule %s in the JSON", name).isNotNull()
            return found!!
        }
    }

    @Nested
    @DisplayName("Cards")
    inner class Cards {

        @Test
        @DisplayName("REL-001: 'defined in' links basename:row to the repository file at that line")
        fun rel001() {
            assertThat(linked.getValue("release-approvals.md")).contains(
                "*Rule `deny` in package `release.approvals` — defined in " +
                    "[`change_approval.rego:16`](${BASE}approvals/change_approval.rego#L16)*",
            )
        }

        @Test
        @DisplayName("REL-004 (row 16), the helper all_checks_passed (row 38) and exempt_service (row 5) each link their own row")
        fun otherRules() {
            val governance = linked.getValue("release-governance.md")
            assertThat(governance).contains("defined in [`release_governance.rego:16`](${BASE}governance/release_governance.rego#L16)*")
            assertThat(governance).contains("defined in [`release_governance.rego:38`](${BASE}governance/release_governance.rego#L38)*")
            assertThat(linked.getValue("release-exemptions.md")).contains("defined in [`exemptions.rego:5`](${BASE}exemptions/exemptions.rego#L5)*")
        }

        @Test
        @DisplayName("every card's 'defined in' is linked, none left as plain text")
        fun noPlainLeft() {
            for ((name, doc) in linked.filterKeys { it.endsWith(".md") && it != "index.md" }) {
                assertThat(doc.lines().filter { it.contains("defined in") }).describedAs(name).isNotEmpty().allMatch { it.contains("defined in [`") }
            }
        }
    }

    @Nested
    @DisplayName("Package page and index")
    inner class PageAndIndex {

        @Test
        @DisplayName("the package page links each source file (line 1) and keeps the relative path as the label")
        fun packagePage() {
            assertThat(linked.getValue("release-approvals.md"))
                .contains("*Source files: [`approvals/change_approval.rego`](${BASE}approvals/change_approval.rego#L1)*")
        }

        @Test
        @DisplayName("the index's Source file cell links the relative path with the rule's row")
        fun indexRow() {
            val row = linked.getValue("index.md").lines().single { it.startsWith("| REL-001 |") }
            assertThat(row).endsWith("| [`approvals/change_approval.rego:16`](${BASE}approvals/change_approval.rego#L16) |")
        }
    }

    @Nested
    @DisplayName("JSON")
    inner class JsonFields {

        @Test
        @DisplayName("each rule carries sourceLine and the exact sourceUrl")
        fun withTemplate() {
            val rel001 = rule(linkedJson, "release.approvals", "deny", "REL-001").jsonObject
            assertThat(rel001.getValue("sourceLine").jsonPrimitive.int).isEqualTo(16)
            assertThat(rel001.getValue("sourceUrl").jsonPrimitive.content).isEqualTo("${BASE}approvals/change_approval.rego#L16")
            assertThat(rule(linkedJson, "release.governance", "all_checks_passed").jsonObject.getValue("sourceLine").jsonPrimitive.int).isEqualTo(38)
        }

        @Test
        @DisplayName("without a template sourceLine is still there and sourceUrl is null")
        fun withoutTemplate() {
            val rel001 = rule(plainJson, "release.approvals", "deny", "REL-001").jsonObject
            assertThat(rel001.getValue("sourceLine").jsonPrimitive.int).isEqualTo(16)
            assertThat(rel001.getValue("sourceUrl")).isEqualTo(JsonNull)
        }
    }

    @Nested
    @DisplayName("Template forms")
    inner class TemplateForms {

        @Test
        @DisplayName("a template without {line} yields links with no fragment; the label still shows the row")
        fun noLinePlaceholder() {
            assertThat(noLine.getValue("release-approvals.md"))
                .contains("defined in [`change_approval.rego:16`](https://git.example.com/r/approvals/change_approval.rego)*")
        }

        @Test
        @DisplayName("every occurrence of {file} and {line} is replaced")
        fun placeholdersTwice() {
            assertThat(fileTwice.getValue("release-approvals.md"))
                .contains("](https://x.example/approvals/change_approval.rego?f=approvals/change_approval.rego&l=16)")
        }

        @Test
        @DisplayName("a template without {file} is rejected naming the placeholder")
        fun missingFile() {
            val set = Explico.load(pack)
            assertThatThrownBy { Explico.render(set, pack, null, null, false, false, emptyList(), "https://git.example.com/no-placeholder") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("{file}")
        }
    }

    @Nested
    @DisplayName("Off by default")
    inner class OffByDefault {

        @Test
        @DisplayName("no template: every file equals what omitting the argument gives, and no link appears")
        fun byteIdentical() {
            assertThat(plainExplicitNull).isEqualTo(plain)
            assertThat(plain.values.joinToString("\n")).doesNotContain("git.example.com").contains("defined in `change_approval.rego`")
        }

        @Test
        @DisplayName("with a template the file set is the same and only the source-location lines differ")
        fun sameFileSet() {
            assertThat(linked.keys - "explico.json").isEqualTo(plain.keys)
        }
    }
}
