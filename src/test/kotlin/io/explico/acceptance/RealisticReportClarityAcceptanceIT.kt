/**
 * Acceptance tests for small report-clarity items on the way to 1.0, from the bank-journey review (findings 4 and 10) and the real-world corpus benchmark. Authored from those,
 * NOT from the frozen acceptance-pack README. Decisions (to be confirmed):
 *
 * - A. A REMOVED entry without a control id is a RULE (a helper such as `is_exempt`), not a control: its notice reads `**⚠ This rule has been removed.**`. An entry WITH a control id
 *   keeps `**⚠ This control has been removed.**` exactly. Nothing else changes.
 * - C. A CALL whose function cannot be found because the package it names (through an `import data...`) is NOT among the loaded policies has its own reason, `package-not-loaded`
 *   (instead of `function-call`), with a sentence saying to render that package together. Decided by the mapper from its own import resolution: a user-function call, also negated.
 *   A rule reference or `some x in alias.rule` keeps `unclassified` (amended with the operator's agreement): an import of reference data looks exactly like an import of a package
 *   and neither is rendered, so "package" would not be a fact. When the package IS loaded nothing changes (an unknown function in a loaded package stays `function-call`, an
 *   unmodelled builtin never gets it).
 * - B. A card that shows fewer examples than it has outcomes says why: `*Showing M of N examples: at most 3 matching, then at most 2 non-matching, in file-name order.*`, directly after
 *   the example list and before the provenance footer; no line when everything is shown. (An explico.json `workedExamplesTotal` was pinned first and removed in review: it always equalled the length of `workedExamples`.) Approved by the
 *   operator knowing it changes the acceptance pack's five Tier-2 goldens (7 cards), `docs/sample-output` and the README excerpt: those are regenerated deliberately in /tdd.
 * Fixtures: `realistic/report-clarity/{diff,lib,lib-missing,examples}` (see its README). Facade only.
 */
package io.explico.acceptance

import io.explico.Explico
import io.explico.RenderedDocs
import io.explico.opa.OpaRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

@DisplayName("Realistic policies: report clarity")
class RealisticReportClarityAcceptanceIT {

    companion object {
        private val base = Path.of("src/test/resources/realistic/report-clarity")

        private const val RULE_NOTICE = "**⚠ This rule has been removed.**"
        private const val CONTROL_NOTICE = "**⚠ This control has been removed.**"
        private const val SENTENCE = "needs a package that is imported but not among the rendered policies; render it together with these policies to resolve it"

        private const val FOOTER = "*Examples are evaluated against this policy version by OPA at generation time.*"
        private fun showing(shown: Int, total: Int) = "*Showing $shown of $total examples: at most 3 matching, then at most 2 non-matching, in file-name order.*"

        private fun render(dir: Path): RenderedDocs = Explico.render(Explico.load(dir), dir, null, null, json = true)

        private fun renderWith(dir: Path, examples: Path?, group: Boolean = false, data: Path? = null): RenderedDocs =
            Explico.render(Explico.load(dir), dir, examples?.let { Explico.loadExamples(it) }, data, json = true, groupByControl = group)

        private lateinit var diffReport: String
        private lateinit var diffReportAgain: String
        private lateinit var appOnly: RenderedDocs
        private lateinit var appOnlyAgain: RenderedDocs
        private lateinit var both: RenderedDocs
        private lateinit var missing: RenderedDocs
        private lateinit var seven: RenderedDocs
        private lateinit var sevenAgain: RenderedDocs
        private lateinit var five: RenderedDocs
        private lateinit var two: RenderedDocs
        private lateinit var grouped: RenderedDocs
        private lateinit var withoutExamples: RenderedDocs
        private lateinit var samplePack: RenderedDocs

        @JvmStatic
        @BeforeAll
        fun renderAll() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            diffReport = Explico.diff(Explico.load(base.resolve("diff/old")), Explico.load(base.resolve("diff/new"))).markdown
            diffReportAgain = Explico.diff(Explico.load(base.resolve("diff/old")), Explico.load(base.resolve("diff/new"))).markdown
            appOnly = render(base.resolve("lib/policies"))
            appOnlyAgain = render(base.resolve("lib/policies"))
            both = render(base.resolve("lib"))
            missing = render(base.resolve("lib-missing"))
            val ex = base.resolve("examples")
            seven = renderWith(ex.resolve("policies"), ex.resolve("examples-7"))
            sevenAgain = renderWith(ex.resolve("policies"), ex.resolve("examples-7"))
            five = renderWith(ex.resolve("policies"), ex.resolve("examples-5"))
            two = renderWith(ex.resolve("policies"), ex.resolve("examples-2"))
            grouped = renderWith(ex.resolve("grouped/policies"), ex.resolve("examples-7"), group = true)
            withoutExamples = renderWith(ex.resolve("policies"), null)
            samplePack = renderWith(Path.of("samples/policies"), Path.of("samples/examples"), data = Path.of("samples/data/release/data.json"))
        }

        private fun count(text: String, needle: String) = Regex(Regex.escape(needle)).findAll(text).count()

        /** The text of each `**REMOVED**` section of a diff report (up to its closing rule). */
        private fun removedSections(report: String): List<String> = report.split("**REMOVED**").drop(1).map { it.substringBefore("\n---") }

        private fun shownAsSourceBullets(docs: RenderedDocs): List<String> {
            val lines = docs.files.getValue("index.md").lines()
            val start = lines.indexOfFirst { it.startsWith("- **Shown as source (") }
            if (start < 0) return emptyList()
            return lines.drop(start + 1).takeWhile { it.startsWith("  - ") }
        }
    }

    @Nested
    @DisplayName("A. A removed rule without a control id is called a rule")
    inner class RemovedRule {

        @Test
        @DisplayName("the helper that lost its control id says 'This rule has been removed', not 'control'")
        fun helperIsARule() {
            val helper = removedSections(diffReport).single { it.contains("## rc1.is_exempt") }

            assertThat(helper).contains(RULE_NOTICE).doesNotContain("This control has been removed")
        }

        @Test
        @DisplayName("a removed control (RC-2, with an id) keeps the exact control wording")
        fun controlKeepsItsWording() {
            val control = removedSections(diffReport).single { it.contains("## RC-2 — ") }

            assertThat(control).contains(CONTROL_NOTICE).doesNotContain("This rule has been removed")
        }

        @Test
        @DisplayName("each wording appears exactly once in the report, and the What changed bullets still say only 'removed'")
        fun onceEach() {
            assertThat(Regex(Regex.escape("This rule has been removed")).findAll(diffReport).count()).isEqualTo(1)
            assertThat(Regex(Regex.escape("This control has been removed")).findAll(diffReport).count()).isEqualTo(1)
            assertThat(diffReport).contains("- [rc1.is_exempt](#rc1-is-exempt) — removed").contains("- [RC-2 — A control that is removed](#rc-2) — removed")
        }

        @Test
        @DisplayName("a report whose removed entries all have an id is exactly as before: the committed diff-all-categories golden, byte for byte")
        fun goldenUnchanged() {
            val dir = Path.of("src/test/resources/diff/diff-all-categories")

            assertThat(Explico.diff(Explico.load(dir.resolve("old")), Explico.load(dir.resolve("new"))).markdown).isEqualTo(Files.readString(dir.resolve("expected/diff-report.md")))
        }

        @Test
        @DisplayName("two runs are byte-identical")
        fun deterministic() {
            assertThat(diffReportAgain).isEqualTo(diffReport)
        }
    }

    @Nested
    @DisplayName("C. A statement that needs a package that is not loaded says so")
    inner class PackageNotLoaded {

        @Test
        @DisplayName("rendering the app alone: the two calls have the reason package-not-loaded, with the new sentence and their places; the iteration over the imported name stays unclassified")
        fun reasonAndSentence() {
            val bullets = shownAsSourceBullets(appOnly)

            assertThat(bullets).hasSize(2)
            assertThat(bullets[0]).startsWith("  - `package-not-loaded` ×2 in [APP-1](app.md#app-1) (`app.rego:17`, `app.rego:18`) — ").contains(SENTENCE)
            assertThat(bullets[1]).startsWith("  - `unclassified` ×1 in [APP-1](app.md#app-1) (`app.rego:19`) — ")
        }

        @Test
        @DisplayName("the calls no longer say function-call, and the ordinary condition still renders: 1 of 4 conditions")
        fun coverageCountsThemAsUnrendered() {
            val index = appOnly.files.getValue("index.md")

            assertThat(index).doesNotContain("`function-call`")
            assertThat(index).contains("*Overall rendering coverage: 1 of 4 conditions (25%)*")
            assertThat(appOnly.files.getValue("app.md")).contains("`kind` is `\"x\"`")
        }

        @Test
        @DisplayName("the card still shows each statement as verbatim source")
        fun cardShowsTheSource() {
            val card = appOnly.files.getValue("app.md")

            assertThat(card).contains("helpers.is_big(input.n)").contains("not helpers.is_big(input.m)").contains("some n in helpers.names")
        }

        @Test
        @DisplayName("explico.json: shownAsSource has package-not-loaded with count 2 and unclassified with count 1, both on APP-1")
        fun json() {
            val entries = Json.parseToJsonElement(appOnly.files.getValue("explico.json")).jsonObject.getValue("quality").jsonObject.getValue("shownAsSource").jsonArray.map { it.jsonObject }

            assertThat(entries.map { it.getValue("reason").jsonPrimitive.content to it.getValue("count").jsonPrimitive.content })
                .containsExactlyInAnyOrder("package-not-loaded" to "2", "unclassified" to "1")
            for (entry in entries) assertThat(entry.getValue("rules").jsonArray.map { it.jsonPrimitive.content }).containsExactly("APP-1")
        }

        @Test
        @DisplayName("rendering the app together with the helper package: nothing has that reason, and the same statements render as calls and a rule path, 6 of 6")
        fun loadedPackageRenders() {
            for ((name, text) in both.files) assertThat(text).describedAs(name).doesNotContain("package-not-loaded")
            assertThat(both.files.getValue("index.md")).contains("*Overall rendering coverage: 6 of 6 conditions (100%)*")
            assertThat(both.files.getValue("app.md")).contains("function [`is_big`](lib-helpers.md#lib-helpers-is-big) is satisfied for `n`")
        }

        @Test
        @DisplayName("a function missing from a LOADED package keeps function-call, and an unmodelled builtin keeps helper-assignment: neither is package-not-loaded")
        fun otherReasonsStay() {
            val bullets = shownAsSourceBullets(missing)

            assertThat(bullets).hasSize(2)
            assertThat(bullets[0]).startsWith("  - `function-call` ×1 in [APP-2](app2.md#app-2)")
            assertThat(bullets[1]).startsWith("  - `helper-assignment` ×1 in [APP-2](app2.md#app-2)")
            assertThat(missing.files.getValue("index.md")).doesNotContain("package-not-loaded")
        }

        @Test
        @DisplayName("two runs are byte-identical")
        fun deterministic() {
            assertThat(appOnlyAgain.files.toList()).isEqualTo(appOnly.files.toList())
        }
    }

    @Nested
    @DisplayName("B. A card says why only some examples are shown")
    inner class ExamplesShown {

        @Test
        @DisplayName("7 fixtures, 5 listed: the line is on both cards, once each, after the last example and immediately before the provenance footer")
        fun lineAndPosition() {
            for (page in listOf("ex1.md", "ex2.md")) {
                val text = seven.files.getValue(page)
                assertThat(count(text, showing(5, 7))).describedAs(page).isEqualTo(1)
                val lines = text.lines().filter { it.isNotBlank() }
                val at = lines.indexOf(showing(5, 7))
                assertThat(lines[at + 1]).describedAs("$page: the footer follows the line").isEqualTo(FOOTER)
                assertThat(lines[at - 1]).describedAs("$page: the line follows the last example").matches("(- |  ).*")
            }
        }

        @Test
        @DisplayName("5 fixtures: a card that lists all 5 has no line; the other lists 4 of 5 and says so")
        fun allShownHasNoLine() {
            assertThat(five.files.getValue("ex1.md")).doesNotContain("Showing")
            assertThat(count(five.files.getValue("ex2.md"), showing(4, 5))).isEqualTo(1)
        }

        @Test
        @DisplayName("2 fixtures: everything is listed, so no page has the line")
        fun fewFixtures() {
            for ((name, text) in two.files) assertThat(text).describedAs(name).doesNotContain("Showing")
        }

        @Test
        @DisplayName("a grouped control page has one line per member card")
        fun groupedPage() {
            assertThat(count(grouped.files.getValue("controls/g-1.md"), showing(5, 7))).isEqualTo(2)
        }

        @Test
        @DisplayName("without example fixtures no page has the line")
        fun noFixturesNoLine() {
            for ((name, text) in withoutExamples.files) assertThat(text).describedAs(name).doesNotContain("Showing")
        }

        @Test
        @DisplayName("the sample pack: its 7 cards that omit examples say so, on the right pages, and the index has none")
        fun samplePackCards() {
            val files = samplePack.files
            assertThat(count(files.getValue("release-approvals.md"), "Showing 4 of 6 examples")).isEqualTo(1)
            assertThat(count(files.getValue("release-evidence.md"), "Showing 3 of 6 examples")).isEqualTo(2)
            assertThat(count(files.getValue("release-exemptions.md"), "Showing 3 of 6 examples")).isEqualTo(1)
            assertThat(count(files.getValue("release-governance.md"), "Showing 3 of 6 examples")).isEqualTo(2)
            assertThat(count(files.getValue("release-provenance.md"), "Showing 4 of 6 examples")).isEqualTo(1)
            assertThat(files.getValue("index.md")).doesNotContain("Showing")
        }

        @Test
        @DisplayName("two renders are byte-identical")
        fun deterministic() {
            assertThat(sevenAgain.files.toList()).isEqualTo(seven.files.toList())
        }
    }
}
