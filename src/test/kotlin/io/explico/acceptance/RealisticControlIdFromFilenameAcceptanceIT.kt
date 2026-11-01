/**
 * Acceptance tests for FR-6 of the decision-object feature requests (specs/decision-object-rendering-spec.md): a corpus that names its controls
 * by FILE NAME (`ctrl_1123.rego`, `ctrl_19_signoff.rego`) rather than in METADATA can have explico derive each rule's control id from that name,
 * opt-in:
 *
 * - `controlIdFrom`: a regex with a named group `id`, matched (`find`) against the rule's source file BASE NAME -- a directory called `ctrl_99/`
 *   does not count. `controlIdTemplate` writes it: `CTRL-{id}` turns `ctrl_1123.rego` into `CTRL-1123` (default `{id}`).
 * - METADATA's `control-id` always wins. Only the id is derived, never a title: a rule keeps its METADATA title, else its card is headed with
 *   the rule name. Nothing is derived when the regex does not match, the `id` group did not take part, or it captured an empty string.
 * - A derived id behaves like a METADATA one everywhere: heading, anchor, links from other cards, the index's controls table, explico.json.
 * - Off by default: with no pattern, or one matching nothing, the output is exactly what it is today.
 *
 * Operator decisions (see the FR-6/FR-7 design notes): a regex plus a template (two options), `render` only -- `diff` is not affected.
 * Authored from the feature request and the implementation plan, NOT from the frozen acceptance-pack README. Fixtures:
 * `realistic/control-id-from-filename/policies` (one rule per file, plus one file holding two rules, a file in a sub-directory, a directory
 * named like a control, and a differently-cased name).
 */
package io.explico.acceptance

import io.explico.Explico
import io.explico.ControlIdOptions
import io.explico.RenderOptions
import io.explico.opa.OpaRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
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

@DisplayName("Realistic policies: control id derived from the file name (FR-6)")
class RealisticControlIdFromFilenameAcceptanceIT {

    companion object {
        private val policies = Path.of("src/test/resources/realistic/control-id-from-filename/policies")
        private const val PATTERN = "ctrl_(?<id>\\d+)"

        private lateinit var plain: Map<String, String>
        private lateinit var derived: Map<String, String>

        private fun render(from: String?, template: String? = null): Map<String, String> {
            val set = Explico.load(policies)
            return if (template == null) Explico.render(set, RenderOptions(json = true, controlId = from?.let { ControlIdOptions(it) })).files
            else Explico.render(set, RenderOptions(json = true, controlId = from?.let { ControlIdOptions(it, template) })).files
        }

        @JvmStatic
        @BeforeAll
        fun renderAll() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            plain = render(null)
            derived = render(PATTERN, "CTRL-{id}")
        }

        private fun doc(files: Map<String, String>, name: String) = files.getValue(name)

        private fun anchors(markdown: String) = Regex("<a id=\"([^\"]*)\"></a>").findAll(markdown).map { it.groupValues[1] }.toList()

        /** The index's rows for the controls table (before the id-less heading) and for the "Rules without a control id" table. */
        private fun indexRows(files: Map<String, String>): Pair<List<String>, List<String>> {
            val index = files.getValue("index.md")
            val marker = "## Rules without a control id"
            assertThat(index).describedAs("the index lists rules without a control id").contains(marker)
            val rows = { text: String -> text.lines().filter { it.startsWith("| ") && !it.startsWith("| Control ID") && !it.startsWith("|---") } }
            return rows(index.substringBefore(marker)) to rows(index.substringAfter(marker).substringBefore("\n## "))
        }

        /** The one row starting with [prefix]; an ASSERTION failure (not an exception) when there is none or several. */
        private fun rowStarting(rows: List<String>, prefix: String): String {
            val matching = rows.filter { it.startsWith(prefix) }
            assertThat(matching).describedAs("exactly one index row starting with '$prefix'").hasSize(1)
            return matching.single()
        }

        private fun ruleJson(files: Map<String, String>, sourceFile: String, name: String): JsonObject =
            Json.parseToJsonElement(files.getValue("explico.json")).jsonObject.getValue("packages").jsonArray
                .flatMap { it.jsonObject.getValue("rules").jsonArray }
                .first { it.jsonObject.getValue("sourceFile").jsonPrimitive.content == sourceFile && it.jsonObject.getValue("name").jsonPrimitive.content == name }.jsonObject
    }

    @Nested
    @DisplayName("With `ctrl_(?<id>\\d+)` and `CTRL-{id}`")
    inner class Derived {

        @Test
        @DisplayName("ctrl_1123.rego -> 'CTRL-1123': the card is headed with the id and the rule name, and its anchor is the id")
        fun headingAndAnchor() {
            val markdown = doc(derived, "fname_one.md")
            assertThat(markdown).contains("## CTRL-1123 — deny").contains("<a id=\"ctrl-1123\"></a>")
            assertThat(markdown).doesNotContain("## fname_one.deny")
        }

        @Test
        @DisplayName("a file in a sub-directory counts, and the id is the first match in the name: team_a/ctrl_19_signoff.rego -> 'CTRL-19'")
        fun subDirectoryAndPartialMatch() {
            assertThat(doc(derived, "fname_nineteen.md")).contains("## CTRL-19 — signed_off").contains("<a id=\"ctrl-19\"></a>")
        }

        @Test
        @DisplayName("only the file's BASE name is matched: a directory called ctrl_99/ derives nothing")
        fun directoryNameIsIgnored() {
            assertThat(doc(derived, "fname_dirname.md")).contains("## fname_dirname.note").doesNotContain("CTRL-99")
        }

        @Test
        @DisplayName("METADATA's control-id wins: ctrl_77.rego declares SEC-77, so 'CTRL-77' appears nowhere")
        fun metadataWins() {
            assertThat(doc(derived, "fname_metadata_wins.md")).contains("## SEC-77 — Explicit id wins")
            assertThat(derived.values).noneMatch { it.contains("CTRL-77") }
        }

        @Test
        @DisplayName("METADATA with a title but no id keeps its title and gains the derived id: '## CTRL-55 — Title but no id'")
        fun titleKeptIdDerived() {
            assertThat(doc(derived, "fname_title_only.md")).contains("## CTRL-55 — Title but no id")
        }

        @Test
        @DisplayName("two rules in one file both get the file's id and keep their own headings; the page keeps its anchors unique the usual way (the first card claims `ctrl-8`, the later one has none)")
        fun twoRulesInOneFile() {
            val markdown = doc(derived, "fname_two_rules.md")
            assertThat(markdown).contains("## CTRL-8 — first").contains("## CTRL-8 — second")
            // Anchors are unique per page (existing convention, review item 18): a duplicate id is claimed by the FIRST card only.
            assertThat(anchors(markdown)).containsExactly("ctrl-8")
            val rows = indexRows(derived).first.filter { it.startsWith("| CTRL-8 |") }
            assertThat(rows).hasSize(2)
            assertThat(rows.single { it.contains("`ctrl_8.rego`") && it.contains("[first]") }).contains("fname_two_rules.md#ctrl-8")
            assertThat(rows.single { it.contains("second") }).describedAs("the later card is not a link target").doesNotContain("](")
        }

        @Test
        @DisplayName("a file whose name does not match stays without an id: helpers.rego, and the differently-cased Ctrl_9.rego (the match is case-sensitive)")
        fun nonMatchingNamesStayIdLess() {
            assertThat(doc(derived, "fname_helpers.md")).contains("## fname_helpers.helper")
            assertThat(doc(derived, "fname_casing.md")).contains("## fname_casing.cased")
            assertThat(doc(derived, "fname_empty.md")).contains("## fname_empty.e")
        }

        @Test
        @DisplayName("a link from another card follows the derived id: fname_one's reference to signed_off points at fname_nineteen.md#ctrl-19")
        fun linksFollowTheDerivedId() {
            assertThat(doc(derived, "fname_one.md")).contains("rule [`signed_off`](fname_nineteen.md#ctrl-19) does not match")
        }

        @Test
        @DisplayName("the index lists derived ids in the controls table, with the link and the source file, and keeps the rest under 'Rules without a control id'")
        fun index() {
            val (controls, idLess) = indexRows(derived)
            val first = rowStarting(controls, "| CTRL-1123 |")
            assertThat(first).contains("fname_one.md#ctrl-1123").contains("`ctrl_1123.rego`")
            assertThat(rowStarting(controls, "| CTRL-19 |")).contains("fname_nineteen.md#ctrl-19").contains("`team_a/ctrl_19_signoff.rego`")
            assertThat(rowStarting(controls, "| SEC-77 |")).contains("fname_metadata_wins.md#sec-77")
            assertThat(rowStarting(controls, "| CTRL-55 |")).contains("Title but no id")
            assertThat(controls.filter { it.startsWith("| CTRL-8 |") }).hasSize(2)
            assertThat(idLess.map { it.split("|")[1].trim() }).containsOnly("—")
            assertThat(idLess).anyMatch { it.contains("`helpers.rego`") }.anyMatch { it.contains("`ctrl_99/notes.rego`") }.anyMatch { it.contains("`Ctrl_9.rego`") }
            assertThat(idLess).noneMatch { it.contains("`ctrl_1123.rego`") || it.contains("`ctrl_77.rego`") }
        }

        @Test
        @DisplayName("explico.json carries the derived id and its anchor; a rule with no id has controlId null")
        fun json() {
            val derivedRule = ruleJson(derived, "ctrl_1123.rego", "deny")
            assertThat(derivedRule.getValue("controlId").jsonPrimitive.content).isEqualTo("CTRL-1123")
            assertThat(derivedRule.getValue("anchor").jsonPrimitive.content).isEqualTo("ctrl-1123")
            assertThat(ruleJson(derived, "helpers.rego", "helper").getValue("controlId")).isEqualTo(JsonNull)
            assertThat(ruleJson(derived, "ctrl_77.rego", "allow").getValue("controlId").jsonPrimitive.content).isEqualTo("SEC-77")
        }
    }

    @Nested
    @DisplayName("Provenance: a derived id says it is derived")
    inner class Provenance {
        private val marker = "derived from the file name"

        @Test
        @DisplayName("each derived card says which file its id came from (the base name), including both cards of a two-rule file and a title-only METADATA card")
        fun cardsSayWhereTheIdCameFrom() {
            assertThat(doc(derived, "fname_one.md")).contains("*Control id derived from the file name `ctrl_1123.rego`*")
            assertThat(doc(derived, "fname_nineteen.md")).contains("*Control id derived from the file name `ctrl_19_signoff.rego`*")
            assertThat(doc(derived, "fname_title_only.md")).contains("*Control id derived from the file name `ctrl_55.rego`*")
            assertThat(doc(derived, "fname_two_rules.md").windowed(marker.length).count { it == marker }).isEqualTo(2)
        }

        @Test
        @DisplayName("a METADATA-declared id and a rule with no id are not marked")
        fun declaredAndIdLessAreNotMarked() {
            assertThat(doc(derived, "fname_metadata_wins.md")).doesNotContain(marker)
            assertThat(doc(derived, "fname_helpers.md")).doesNotContain(marker)
        }

        @Test
        @DisplayName("explico.json flags a derived id with `controlIdDerived: true`; there is no such key for a declared id, an id-less rule, or without the option")
        fun jsonFlag() {
            assertThat(ruleJson(derived, "ctrl_1123.rego", "deny").getValue("controlIdDerived").jsonPrimitive.content).isEqualTo("true")
            assertThat(ruleJson(derived, "ctrl_77.rego", "allow")).doesNotContainKey("controlIdDerived")
            assertThat(ruleJson(derived, "helpers.rego", "helper")).doesNotContainKey("controlIdDerived")
            assertThat(ruleJson(plain, "ctrl_1123.rego", "deny")).doesNotContainKey("controlIdDerived")
        }

        @Test
        @DisplayName("the index legend explains derived ids when there are some, and says nothing without the option")
        fun indexLegend() {
            assertThat(derived.getValue("index.md")).contains("**Derived control ids**")
            assertThat(plain.getValue("index.md")).doesNotContain("Derived control ids")
            assertThat(plain.values).noneMatch { it.contains(marker) }
        }
    }

    @Nested
    @DisplayName("The template")
    inner class Template {

        @Test
        @DisplayName("with no template the id is the captured text itself: '## 1123 — deny'")
        fun defaultTemplate() {
            assertThat(doc(render(PATTERN), "fname_one.md")).contains("## 1123 — deny")
        }

        @Test
        @DisplayName("any template containing {id} is used as written: 'SEC-{id}' -> '## SEC-1123 — deny'")
        fun customTemplate() {
            val files = render(PATTERN, "SEC-{id}")
            assertThat(doc(files, "fname_one.md")).contains("## SEC-1123 — deny")
            assertThat(doc(files, "fname_metadata_wins.md")).contains("## SEC-77 — Explicit id wins")
        }
    }

    @Nested
    @DisplayName("What counts as a match")
    inner class Matching {

        @Test
        @DisplayName("an inline (?i) flag makes the match case-insensitive: Ctrl_9.rego -> 'CTRL-9'")
        fun caseInsensitive() {
            assertThat(doc(render("(?i)ctrl_(?<id>\\d+)", "CTRL-{id}"), "fname_casing.md")).contains("## CTRL-9 — cased")
        }

        @Test
        @DisplayName("a group that captured nothing derives nothing, not 'CTRL-': ctrl_.rego stays without an id")
        fun emptyCaptureDerivesNothing() {
            val files = render("ctrl_(?<id>\\d*)", "CTRL-{id}")
            assertThat(doc(files, "fname_empty.md")).contains("## fname_empty.e")
            assertThat(files.values).noneMatch { it.contains("## CTRL- ") || it.contains("\"CTRL-\"") }
            assertThat(doc(files, "fname_one.md")).contains("## CTRL-1123 — deny")
        }

        @Test
        @DisplayName("a match in which the `id` group took no part derives nothing: with an alternation, helpers.rego matches but has no id")
        fun nonParticipatingGroupDerivesNothing() {
            val files = render("ctrl_(?<id>\\d+)|helpers", "CTRL-{id}")
            assertThat(doc(files, "fname_helpers.md")).contains("## fname_helpers.helper")
            assertThat(doc(files, "fname_one.md")).contains("## CTRL-1123 — deny")
        }
    }

    @Nested
    @DisplayName("Off by default")
    inner class OffByDefault {

        @Test
        @DisplayName("with no pattern nothing is derived: every rule here is without an id and no CTRL- id appears")
        fun noPattern() {
            assertThat(plain.getValue("index.md")).contains("## Rules without a control id")
            assertThat(indexRows(plain).first.none { it.startsWith("| CTRL-") }).isTrue()
            assertThat(doc(plain, "fname_one.md")).contains("## fname_one.deny")
            assertThat(plain.values).noneMatch { it.contains("CTRL-1123") }
        }

        @Test
        @DisplayName("a pattern that matches no file leaves every document exactly as without it")
        fun patternMatchingNothing() {
            assertThat(render("zzz_(?<id>\\d+)", "CTRL-{id}")).isEqualTo(plain)
        }
    }

    @Nested
    @DisplayName("A mistake in the options is the caller's, and is reported (IllegalArgumentException)")
    inner class Validation {

        @Test
        @DisplayName("first, good values are accepted")
        fun goodValuesAreAccepted() {
            assertThat(render(PATTERN, "CTRL-{id}")).isNotEmpty()
        }

        @Test
        @DisplayName("a pattern that is not a regular expression")
        fun invalidSyntax() {
            assertThatThrownBy { render("ctrl_(?<id>\\d+", "CTRL-{id}") }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        @DisplayName("a pattern without the named group `id` says how to write it")
        fun missingGroup() {
            assertThatThrownBy { render("ctrl_(\\d+)", "CTRL-{id}") }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("(?<id>")
        }

        @Test
        @DisplayName("a template without {id} says so")
        fun templateWithoutId() {
            assertThatThrownBy { render(PATTERN, "CTRL-") }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("{id}")
        }
    }
}
