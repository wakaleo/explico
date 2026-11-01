/**
 * Acceptance tests for FR-11 of the decision-object feature requests: fixture inputs carry subject ids and e-mail addresses that must not appear in the
 * documentation. A rule declares `custom.mask: [paths]` in its METADATA and its worked examples show `***` instead. Operator decisions (asked):
 *
 * - REACH: the real input is still evaluated (verdicts stay true), then every masked value is replaced by `***` everywhere that example shows it -- the path-value
 *   bullets, the produced messages, the verdict reason, the remediation labels -- in the Markdown and in `explico.json`, not only in the displayed input.
 * - PATHS: dotted paths inside the fixture input, no `input.` prefix (a dot separates keys, as in the other projections), `[]` for every element of an array
 *   (`people[].email`); a string or number found there is masked, an object or array there whole; a path that is absent is ignored.
 * - FAIL CLOSED: a malformed declaration would otherwise leak the very values it was written to hide, so it is NOT ignored with a warning like the other
 *   projections: `Explico.render` throws `IllegalArgumentException` naming `custom.mask` and the rule, before any opa work.
 * - `mask` is a reserved `custom` key (no longer shown in the card's `*Custom:*` line), is echoed in `explico.json` as the rule's `mask` list, and editing it is a
 *   documentation change in `diff`. Masking is per rule: a rule that declares none shows its values.
 * Markup (decided here, to be confirmed): the marker is `***`; in a code span (path value, remediation label) and in the JSON it is written as is, inside an italic
 * `*"..."*` message or reason line it is escaped (`\*\*\*`) so it displays as `***` instead of opening emphasis.
 * Authored from the feature request and the answers above, NOT from the frozen acceptance-pack README. Facade only. Fixtures: `realistic/input-masking/`
 * (MK-1 message + ticket path, MK-2 nothing masked, MK-3 wildcard + number, MK-4 declared verdict/actions, `malformed-*`, `diff-old`/`diff-new`).
 */
package io.explico.acceptance

import io.explico.Explico
import io.explico.RenderOptions
import io.explico.diff.DiffCategory
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
import java.nio.file.Path

@DisplayName("Realistic policies: input masking (FR-11)")
class RealisticInputMaskingAcceptanceIT {

    companion object {
        private val base = Path.of("src/test/resources/realistic/input-masking")
        private val policies = base.resolve("policies")

        private lateinit var files: Map<String, String>
        private lateinit var again: Map<String, String>

        private fun render(dir: Path = policies, examples: Boolean = true): Map<String, String> {
            val set = Explico.load(dir)
            val ex = if (examples) Explico.loadExamples(base.resolve("examples")) else null
            return Explico.render(set, RenderOptions(examples = ex, json = true)).files
        }

        @JvmStatic
        @BeforeAll
        fun renderAll() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            files = render()
            again = render()
        }

        private fun card(id: String): String {
            val lines = files.getValue("mask.md").lines()
            val start = lines.indexOfFirst { it.startsWith("## $id") }
            assertThat(start).describedAs("a '## $id' heading").isGreaterThanOrEqualTo(0)
            val end = lines.drop(start + 1).indexOfFirst { it.startsWith("## ") }
            return lines.drop(start).take(if (end < 0) lines.size else end + 1).joinToString("\n")
        }

        private fun ruleJson(id: String): JsonObject =
            Json.parseToJsonElement(files.getValue("explico.json")).jsonObject.getValue("packages").jsonArray.map { it.jsonObject }
                .flatMap { it.getValue("rules").jsonArray.map { r -> r.jsonObject } }
                .first { it.getValue("controlId").jsonPrimitive.content == id }

        private const val STARS = "\\*\\*\\*"
    }

    @Nested
    @DisplayName("Messages, reasons, labels and path values show *** for a masked value")
    inner class InTheMarkdown {

        @Test
        @DisplayName("MK-1: the message echoes a masked e-mail and subject id -> both are ***; the verdict word is unchanged")
        fun message() {
            val card = card("MK-1")
            assertThat(card).contains("- **everything missing** — matched *(Situation 1)*")
            assertThat(card).contains("*\"" + STARS + " (subject " + STARS + ") has no ticket\"*")
            assertThat(card).doesNotContain("alice@example.com").doesNotContain("S-12345")
        }

        @Test
        @DisplayName("MK-1: a masked path that the card shows as a 'path: value' bullet reads `***` (the absent one still reads absent)")
        fun pathValueBullet() {
            val card = card("MK-1")
            assertThat(card).contains("  - `ticket`: `***`").contains("  - `ticket`: absent")
            assertThat(card).doesNotContain("T-1")
        }

        @Test
        @DisplayName("MK-3: a number (accountNumber) and a wildcard path (people[].email) are masked in the message")
        fun numberAndWildcard() {
            val card = card("MK-3")
            assertThat(card).contains("*\"account $STARS: $STARS is inactive\"*")
            assertThat(card).doesNotContain("987654321").doesNotContain("bob@example.com")
        }

        @Test
        @DisplayName("MK-4: the declared verdict's reason and the remediation label are masked too")
        fun reasonAndRemediation() {
            val card = card("MK-4")
            assertThat(card).contains("*\"No attestation for $STARS\"*").contains("    - `complete the form for ***`")
            assertThat(card).doesNotContain("S-12345")
        }

        @Test
        @DisplayName("masking is per rule: MK-2 declares nothing and still shows the e-mail its message echoes")
        fun perRule() {
            assertThat(card("MK-2")).contains("*\"alice@example.com has no approval\"*")
        }

        @Test
        @DisplayName("the declaration is not shown as a 'Custom' field; an unmasked rule's other values are untouched")
        fun reservedKey() {
            assertThat(files.getValue("mask.md")).doesNotContain("mask: requester.email").doesNotContain("*Custom:")
            assertThat(card("MK-2")).contains("  - `approval`: `true`")
        }
    }

    @Nested
    @DisplayName("explico.json")
    inner class InTheJson {

        @Test
        @DisplayName("the produced messages, the verdict text/reason and the remediation labels carry *** (plain, no escaping)")
        fun examples() {
            val mk1 = ruleJson("MK-1").getValue("workedExamples").jsonArray.map { it.jsonObject }.first { it.getValue("name").jsonPrimitive.content == "everything missing" }
            assertThat(mk1.getValue("messages").jsonArray.map { it.jsonPrimitive.content }).containsExactly("*** (subject ***) has no ticket")
            val mk4 = ruleJson("MK-4").getValue("workedExamples").jsonArray.map { it.jsonObject }.first { it.getValue("name").jsonPrimitive.content == "everything missing" }
            assertThat(mk4.getValue("verdict").jsonObject.getValue("reason").jsonPrimitive.content).isEqualTo("No attestation for ***")
            assertThat(mk4.getValue("remediation").jsonArray.map { it.jsonPrimitive.content }).containsExactly("complete the form for ***")
        }

        @Test
        @DisplayName("no masked value appears anywhere in the JSON; the unmasked rule's e-mail still does (control)")
        fun noLeak() {
            val json = files.getValue("explico.json")
            assertThat(json).doesNotContain("S-12345").doesNotContain("987654321").doesNotContain("bob@example.com").doesNotContain("T-1")
            assertThat(json).contains("alice@example.com")
        }

        @Test
        @DisplayName("a rule's declared mask is echoed as `mask`, in declared order; a rule without one has no such key")
        fun declaredMask() {
            assertThat(ruleJson("MK-1")).containsKey("mask")
            assertThat(ruleJson("MK-1").getValue("mask").jsonArray.map { it.jsonPrimitive.content }).containsExactly("requester.email", "subjectId", "ticket")
            assertThat(ruleJson("MK-3")).containsKey("mask")
            assertThat(ruleJson("MK-3").getValue("mask").jsonArray.map { it.jsonPrimitive.content }).containsExactly("people[].email", "accountNumber")
            assertThat(ruleJson("MK-2")).doesNotContainKey("mask")
        }
    }

    @Nested
    @DisplayName("A malformed declaration fails closed")
    inner class Malformed {

        @Test
        @DisplayName("a scalar instead of a list is an IllegalArgumentException naming custom.mask and the rule, before any opa work")
        fun scalar() {
            val dir = base.resolve("malformed-scalar")
            val set = Explico.load(dir)
            assertThatThrownBy { Explico.render(set) }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("custom.mask").hasMessageContaining("badmask.leaky")
        }

        @Test
        @DisplayName("an empty path or a non-string entry in the list is the same")
        fun badEntry() {
            val dir = base.resolve("malformed-entry")
            val set = Explico.load(dir)
            assertThatThrownBy { Explico.render(set) }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("custom.mask").hasMessageContaining("badentry.leaky")
        }
    }

    @Nested
    @DisplayName("diff and determinism")
    inner class Elsewhere {

        @Test
        @DisplayName("adding a path to the mask is a documentation change named 'mask', never a logic change")
        fun diffMask() {
            val report = Explico.diff(Explico.load(base.resolve("diff-old")), Explico.load(base.resolve("diff-new")))
            assertThat(report.entries.single().category).isEqualTo(DiffCategory.DOCS_CHANGED)
            assertThat(report.markdown).contains("documentation changed: mask")
        }

        @Test
        @DisplayName("two renders are byte-identical")
        fun deterministic() {
            assertThat(again.toList()).isEqualTo(files.toList())
        }
    }
}
