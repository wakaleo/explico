/**
 * Acceptance tests for free index variables (`pods[i]` with `i` not bound by `some x in`), spec §14.51. Operator-approved wording (option M2):
 * - an index used EXACTLY ONCE in a rule is the wildcard `_` (that is what `_` is), so it renders like `pods[_]` ("any of: ...");
 * - an index used more than once is a named binding: "for some index or key `i` of `<collection>`", and every later use reads `[each i]` (or plain
 *   `i` as a value);
 * - never when the first use is under a `not`, inside a comprehension or `every`, or under a function parameter -- those keep the raw operand.
 * Fixture: `realistic/free-index/policies`.
 */
package io.explico.acceptance

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
import java.nio.file.Path

@DisplayName("Realistic policies: free index variables")
class RealisticFreeIndexAcceptanceIT {

    companion object {
        private const val HEAD = "- for some index or key"
        private val policies = Path.of("src/test/resources/realistic/free-index/policies")

        private lateinit var doc: String
        private lateinit var json: kotlinx.serialization.json.JsonObject

        @JvmStatic
        @BeforeAll
        fun renderAll() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            val files = Explico.render(Explico.load(policies), policies, null, null, false, true).files
            doc = files.getValue("free-index.md")
            json = Json.parseToJsonElement(files.getValue("explico.json")).jsonObject
        }

        private fun card(id: String): String = doc.substringAfter("<a id=\"$id\"></a>").substringBefore("<a id=\"")
        private fun lines(id: String): List<String> = card(id).lines()
        private fun coverage(id: String) = lines(id).single { it.startsWith("*Rendering coverage:") }
    }

    @Test
    @DisplayName("FI-001 an undeclared index used once renders exactly like pods[_]: any of")
    fun undeclaredSingleUse() {
        assertThat(lines("fi-001")).contains("- any of: `pods ▸ privileged` is `true`")
        assertThat(card("fi-001")).doesNotContain("input.pods[i]").doesNotContain("unrendered value")
        assertThat(coverage("fi-001")).isEqualTo("*Rendering coverage: 1 of 1 conditions*")
    }

    @Test
    @DisplayName("FI-002 a declared `some i` used once renders the same, and the declaration itself is not a bullet")
    fun declaredSingleUse() {
        assertThat(lines("fi-002")).contains("- any of: `pods ▸ privileged` is `true`")
        assertThat(card("fi-002")).doesNotContain("some i")
        assertThat(coverage("fi-002")).isEqualTo("*Rendering coverage: 1 of 1 conditions*")
    }

    @Test
    @DisplayName("FI-003 an index shared by two collections gets a head bullet, then [each i] on both")
    fun sharedIndex() {
        val lines = lines("fi-003")
        val at = lines.indexOf("$HEAD `i` of `pods`")
        assertThat(at).isGreaterThanOrEqualTo(0)
        assertThat(lines[at + 1]).isEqualTo("- `pods ▸ [each i] ▸ name` is `names ▸ [each i]`")
        assertThat(card("fi-003")).doesNotContain("unrendered value")
        assertThat(coverage("fi-003")).isEqualTo("*Rendering coverage: 2 of 2 conditions*")
    }

    @Test
    @DisplayName("FI-004 an index also used as a value reads as plain `i`")
    fun indexAsValue() {
        val lines = lines("fi-004")
        val at = lines.indexOf("$HEAD `i` of `pods`")
        assertThat(at).isGreaterThanOrEqualTo(0)
        assertThat(lines.subList(at + 1, at + 3)).containsExactly(
            "- `pods ▸ [each i] ▸ privileged` is `true`",
            "- `i` is greater than `0`",
        )
        assertThat(coverage("fi-004")).isEqualTo("*Rendering coverage: 3 of 3 conditions*")
    }

    @Test
    @DisplayName("FI-005 spec[field][_]: a key bullet, then an element bullet over that key's collection; no helper block")
    fun mapKeyThenWildcard() {
        val lines = lines("fi-005")
        val at = lines.indexOf("$HEAD `field` of `review ▸ object ▸ spec`")
        assertThat(at).isGreaterThanOrEqualTo(0)
        assertThat(lines.subList(at + 1, at + 4)).containsExactly(
            "- for some container in `review ▸ object ▸ spec ▸ [each field]`",
            "- `review ▸ object ▸ spec ▸ [each field] ▸ [each container] ▸ privileged` is `true`",
            "- `field` is not `\"volumes\"`",
        )
        assertThat(card("fi-005")).doesNotContain("⚠")
        assertThat(coverage("fi-005")).isEqualTo("*Rendering coverage: 4 of 4 conditions*")
    }

    @Test
    @DisplayName("FI-006 a first use under `not` is never turned into a binding: it stays raw, and the later positive use binds")
    fun negatedFirstUse() {
        val card = card("fi-006")
        assertThat(card).contains("`input.pods[i].ok`").doesNotContain("of `pods`")
        assertThat(card.lines()).contains("$HEAD `i` of `allowed`")
    }

    @Test
    @DisplayName("FI-007 a shared index inside a comprehension keeps the whole condition as source")
    fun comprehensionKeepsSource() {
        val card = card("fi-007")
        assertThat(card).contains("⚠ **not rendered — shown as source:**").contains("count({x | x := input.pods[i]; input.names[i] == x.name}) > 0")
        assertThat(card).doesNotContain("for some index or key")
    }

    @Test
    @DisplayName("FI-008 an index that is also the rule's produced key is a named binding")
    fun headUse() {
        val lines = lines("fi-008")
        val at = lines.indexOf("$HEAD `i` of `pods`")
        assertThat(at).isGreaterThanOrEqualTo(0)
        assertThat(lines[at + 1]).isEqualTo("- `pods ▸ [each i] ▸ privileged` is `true`")
    }

    @Test
    @DisplayName("FI-009 under a function parameter nothing is bound: the raw operands stay")
    fun parameterRooted() {
        val card = card("fi-009")
        assertThat(card).contains("`p.items[i].bad`").doesNotContain("for some index or key")
    }

    @Test
    @DisplayName("FI-010 a wildcard straight under the root names nothing to show, so it stays the raw operand (never an empty breadcrumb)")
    fun rootWildcardStaysRaw() {
        val card = card("fi-010")
        assertThat(card).contains("`input[_]`").doesNotContain("``  ").doesNotContain("any of: ``")
    }

    @Test
    @DisplayName("FI-011 a bound index can be an every domain: the head names the index, the every reads [each i], its condition nests")
    fun boundIndexAsEveryDomain() {
        val lines = lines("fi-011")
        val at = lines.indexOf("$HEAD `i` of `groups`")
        assertThat(at).isGreaterThanOrEqualTo(0)
        assertThat(lines.subList(at + 1, at + 4)).containsExactly(
            "- `groups ▸ [each i] ▸ enabled` is `true`",
            "- for every m in `members ▸ [each i]`, all of the following are true (vacuously true when there are none)",
            "  - `members ▸ [each i] ▸ [each m] ▸ ok` is `true`",
        )
        assertThat(card("fi-011")).doesNotContain("⚠")
    }

    @Test
    @DisplayName("diff: changing the collection a shared index is compared with is a logic change, named as a removed and an added condition")
    fun diffNamesTheChangedSharedIndexCondition() {
        val root = Path.of("src/test/resources/realistic/free-index-diff")
        val report = Explico.diff(Explico.load(root.resolve("old")), Explico.load(root.resolve("new")))
        val change = Json.parseToJsonElement(report.json).jsonObject.getValue("changes").jsonArray.single().jsonObject
        assertThat(change.getValue("details").jsonArray.map { it.jsonPrimitive.content }).containsExactly(
            "condition removed: `pods ▸ [each i] ▸ name` is `names ▸ [each i]`",
            "condition added: `pods ▸ [each i] ▸ name` is `labels ▸ [each i]`",
        )
    }

    @Test
    @DisplayName("JSON: the binding is kind someKey and its text is the card's bullet")
    fun json() {
        val rule = json.getValue("packages").jsonArray.flatMap { it.jsonObject.getValue("rules").jsonArray }
            .first { it.jsonObject["controlId"]?.jsonPrimitive?.content == "FI-003" }.jsonObject
        val condition = rule.getValue("bodies").jsonArray.single().jsonObject.getValue("conditions").jsonArray.first().jsonObject
        assertThat(condition.getValue("kind").jsonPrimitive.content).isEqualTo("someKey")
        assertThat(condition.getValue("text").jsonPrimitive.content).isEqualTo("for some index or key `i` of `pods`")
        assertThat(doc.lines()).contains("- " + condition.getValue("text").jsonPrimitive.content)
    }
}
