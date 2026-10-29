/**
 * Acceptance tests for FR-7 of the decision-object feature requests (specs/decision-object-rendering-spec.md): one control is often split over
 * several files and packages (`ctrl_28_signoff.rego`, `ctrl_28_signoff_validation.rego`, `ctrl_28_evidence.rego`), so today it is scattered over
 * several cards and package pages and appears as several rows in the index. With `groupByControl` (CLI: `--group-by-control`), opt-in:
 *
 * - every control id shared by TWO OR MORE rules gets ONE page, `controls/<slug>.md`, with each rule as a labelled sub-section, ordered by source
 *   path; a control id used by a single rule is untouched. Package pages keep one short pointer per grouped control instead of the cards.
 * - the index lists a grouped control ONCE, and the quality findings count it as ONE control (missing a description / a framework only if none of
 *   its rules has one).
 * - links between cards follow the cards: a link to a grouped rule goes to its anchor on the control page, relative to the page it is written on
 *   (`controls/...` from a package page, `../...` from a control page).
 * - explico.json gains a `controls` array, and each grouped rule says which page it lives on.
 * - presentation grouping only: no rule changes, no new evaluation. Off by default, and the output is then exactly what it is today.
 *
 * Operator decisions (see the FR-6/FR-7 design notes): an opt-in flag with one page per control. Decisions made while authoring, from the plan:
 * only shared ids get a page; a grouped control is "missing" a description/framework only when none of its rules has one; two ids that slug to
 * the same file name get distinct files (`x-1.md`, `x-1-2.md`, in id order). The diff side of FR-7 (`diff --group-by-control`) is its own
 * increment. Authored from the feature request and the implementation plan, NOT from the frozen acceptance-pack README. Fixtures:
 * `realistic/group-by-control/{policies,examples}`.
 */
package io.explico.acceptance

import io.explico.Explico
import io.explico.opa.OpaRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

@DisplayName("Realistic policies: group a multi-file control into one page (FR-7)")
class RealisticGroupByControlAcceptanceIT {

    companion object {
        private val policies = Path.of("src/test/resources/realistic/group-by-control/policies")
        private val examples = Path.of("src/test/resources/realistic/group-by-control/examples")

        private lateinit var plain: Map<String, String>
        private lateinit var grouped: Map<String, String>
        private lateinit var groupedAgain: Map<String, String>
        private lateinit var derivedGrouped: Map<String, String>

        private fun render(group: Boolean, derive: Boolean = false): Map<String, String> {
            val set = Explico.load(policies)
            val ex = Explico.loadExamples(examples)
            return if (derive) Explico.render(set, policies, ex, null, false, true, groupByControl = group, controlIdFrom = "ctrl_(?<id>\\d+)", controlIdTemplate = "CTRL-{id}").files
            else Explico.render(set, policies, ex, null, false, true, groupByControl = group).files
        }

        @JvmStatic
        @BeforeAll
        fun renderAll() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            plain = render(false)
            grouped = render(true)
            groupedAgain = render(true)
            derivedGrouped = render(true, derive = true)
        }

        private fun page(files: Map<String, String>, name: String): String {
            assertThat(files).describedAs("the rendered files").containsKey(name)
            return files.getValue(name)
        }

        private fun anchors(markdown: String) = Regex("<a id=\"([^\"]*)\"></a>").findAll(markdown).map { it.groupValues[1] }.toList()

        /** The `## ` headings of a page, in order (a rule/control heading; nested sections use `###`). */
        private fun headings(markdown: String) = markdown.lines().filter { it.startsWith("## ") }.map { it.removePrefix("## ") }

        /** The section of a control page that starts at the `## ` heading beginning with [heading], up to the next `## ` heading. */
        private fun section(markdown: String, heading: String): String {
            val lines = markdown.lines()
            val start = lines.indexOfFirst { it.startsWith("## $heading") }
            assertThat(start).describedAs("a '## $heading' section").isGreaterThanOrEqualTo(0)
            val end = lines.drop(start + 1).indexOfFirst { it.startsWith("## ") }.let { if (it < 0) lines.size else start + 1 + it }
            return lines.subList(start, end).joinToString("\n")
        }

        private fun indexRows(files: Map<String, String>) = files.getValue("index.md").lines().filter { it.startsWith("| ") && !it.startsWith("| Control ID") && !it.startsWith("|---") }

        private fun json(files: Map<String, String>) = Json.parseToJsonElement(files.getValue("explico.json")).jsonObject

        /** The one row starting with [prefix]; an ASSERTION failure (not an exception) unless there is exactly one. */
        private fun onlyRow(rows: List<String>, prefix: String): String {
            val matching = rows.filter { it.startsWith(prefix) }
            assertThat(matching).describedAs("exactly one index row starting with '$prefix'").hasSize(1)
            return matching.single()
        }

        /** [owner]'s [key]; an ASSERTION failure (not an exception) when it is absent. */
        private fun arrayOrNull(owner: JsonObject, key: String, what: String): kotlinx.serialization.json.JsonElement {
            assertThat(owner).describedAs("$what carries `$key`").containsKey(key)
            return owner.getValue(key)
        }

        private fun ruleJson(files: Map<String, String>, pkg: String, rule: String): JsonObject =
            json(files).getValue("packages").jsonArray.map { it.jsonObject }.first { it.getValue("path").jsonPrimitive.content == pkg }
                .getValue("rules").jsonArray.map { it.jsonObject }.first { it.getValue("name").jsonPrimitive.content == rule }
    }

    @Nested
    @DisplayName("Which pages exist")
    inner class Pages {

        @Test
        @DisplayName("one page per control id shared by two or more rules, named by the slug of the id; the single-rule SEC-30 gets none")
        fun onePagePerSharedControl() {
            assertThat(grouped.keys.filter { it.startsWith("controls/") }.sorted())
                .containsExactly("controls/sec-28.md", "controls/x-1-2.md", "controls/x-1.md")
        }

        @Test
        @DisplayName("two ids that slug to the same name get distinct files, in id order: X-1 -> x-1.md, x_1 -> x-1-2.md, each with its own rules")
        fun slugCollisions() {
            val first = page(grouped, "controls/x-1.md")
            val second = page(grouped, "controls/x-1-2.md")
            assertThat(first).contains("grp_col_a.ra").contains("grp_col_b.rb").doesNotContain("grp_col_c.rc")
            assertThat(second).contains("grp_col_c.rc").contains("grp_col_d.rd").doesNotContain("grp_col_a.ra")
        }

        @Test
        @DisplayName("every package page and the index are still written")
        fun packagePagesRemain() {
            for (name in listOf("index.md", "explico.json", "grp_signoff.md", "grp_evidence.md", "grp_other.md", "grp_col_a.md", "grp_nine_a.md")) assertThat(grouped).containsKey(name)
        }

        @Test
        @DisplayName("off by default: no controls/ files, and an explicit false renders exactly as not mentioning the option")
        fun offByDefault() {
            assertThat(plain.keys.none { it.startsWith("controls/") }).isTrue()
            assertThat(page(plain, "grp_signoff.md")).contains("## SEC-28 — Sign-off").contains("## SEC-28 — valid_signature")
            val set = Explico.load(policies)
            assertThat(Explico.render(set, policies, Explico.loadExamples(examples), null, false, true).files).isEqualTo(plain)
        }

        @Test
        @DisplayName("deterministic: two renders give the same files in the same order")
        fun deterministic() {
            assertThat(groupedAgain.toList()).isEqualTo(grouped.toList())
        }
    }

    @Nested
    @DisplayName("SEC-28: one control over three files and two packages")
    inner class TheControlPage {
        private val control get() = page(grouped, "controls/sec-28.md")

        @Test
        @DisplayName("the page is titled with the control id and the title of its first titled rule (by source path), and opens with the control's anchor")
        fun title() {
            assertThat(control).contains("# Control SEC-28 — Evidence is attached").contains("<a id=\"sec-28\"></a>")
        }

        @Test
        @DisplayName("each rule is a sub-section labelled package.rule, in source-path order: evidence, sign-off, validation")
        fun subSectionsInSourcePathOrder() {
            assertThat(headings(control).map { it.substringBefore(" — ") })
                .containsExactly("grp_evidence.evidence_present", "grp_signoff.signed_off", "grp_signoff.valid_signature")
        }

        @Test
        @DisplayName("each sub-section is the rule's whole card: defined-in line, conditions, description and frameworks where it has them")
        fun eachSubSectionIsTheRulesCard() {
            val evidence = section(control, "grp_evidence.evidence_present")
            assertThat(evidence).contains("defined in `ctrl_28_evidence.rego`").contains("`evidence` is not empty")
            val signoff = section(control, "grp_signoff.signed_off")
            assertThat(signoff).contains("A change is signed off by an approver.").contains("*Frameworks: SOC 2 CC8.1*").contains("`signoff ▸ approved` is present and not false")
            assertThat(section(control, "grp_signoff.valid_signature")).contains("defined in `ctrl_28_signoff_validation.rego`")
        }

        @Test
        @DisplayName("each sub-section has its own anchor `<id>-<package>-<rule>`, and every anchor on the page is distinct")
        fun anchors() {
            assertThat(anchors(control)).containsExactly(
                "sec-28", "sec-28-grp-evidence-evidence-present", "sec-28-grp-signoff-signed-off", "sec-28-grp-signoff-valid-signature",
            )
        }

        @Test
        @DisplayName("worked examples travel with the rule's section")
        fun workedExamples() {
            val signoff = section(control, "grp_signoff.signed_off")
            assertThat(signoff).contains("**Worked examples**").contains("signed-off change")
            assertThat(page(grouped, "grp_signoff.md")).doesNotContain("**Worked examples**").doesNotContain("signed-off change")
        }

        @Test
        @DisplayName("the page closes with the coverage of the whole control: 6 of 6 conditions")
        fun coverage() {
            assertThat(control.lines().single { it.startsWith("*Control rendering coverage:") }).isEqualTo("*Control rendering coverage: 6 of 6 conditions (100%)*")
        }
    }

    @Nested
    @DisplayName("Package pages keep a pointer, not the cards")
    inner class PackagePages {

        @Test
        @DisplayName("a package holding rules of a grouped control shows ONE entry for it, pointing at the control page and naming this package's rules")
        fun pointer() {
            val markdown = page(grouped, "grp_signoff.md")
            assertThat(headings(markdown).filter { it.startsWith("SEC-28") }).hasSize(1)
            val pointer = markdown.lines().single { it.contains("](controls/sec-28.md") }
            assertThat(pointer).contains("`signed_off`").contains("`valid_signature`")
            assertThat(markdown).doesNotContain("`signoff ▸ approved` is present and not false").doesNotContain("defined in `ctrl_28_signoff")
            assertThat(anchors(markdown).toSet()).describedAs("anchors on the page are unique").hasSize(anchors(markdown).size)
        }

        @Test
        @DisplayName("a package with only single-rule controls and id-less rules is unchanged: SEC-30 keeps its card")
        fun untouchedPackage() {
            val markdown = page(grouped, "grp_other.md")
            assertThat(markdown).contains("## SEC-30 — Retention").contains("`retention ▸ days` is at least `90`").contains("## grp_other.uses_signoff")
        }
    }

    @Nested
    @DisplayName("Links follow the cards, relative to the page they are written on")
    inner class Links {

        @Test
        @DisplayName("within a control page: to a sibling in the same package, and to a sibling in another package, by anchor")
        fun withinTheControlPage() {
            val control = page(grouped, "controls/sec-28.md")
            assertThat(section(control, "grp_signoff.valid_signature")).contains("see rule [`signed_off`](#sec-28-grp-signoff-signed-off)")
            assertThat(section(control, "grp_signoff.signed_off")).contains("see rule [`evidence_present`](#sec-28-grp-evidence-evidence-present)")
        }

        @Test
        @DisplayName("from a control page to a card that stays on a package page: one directory up")
        fun controlPageToPackagePage() {
            assertThat(section(page(grouped, "controls/sec-28.md"), "grp_evidence.evidence_present")).contains("see rule [`retention_ok`](../grp_other.md#sec-30)")
        }

        @Test
        @DisplayName("from a package page to a grouped rule: into controls/, at the rule's anchor")
        fun packagePageToControlPage() {
            assertThat(page(grouped, "grp_other.md")).contains("see rule [`signed_off`](controls/sec-28.md#sec-28-grp-signoff-signed-off)")
        }

        @Test
        @DisplayName("without the option the same links are the old ones")
        fun unchangedWithoutTheOption() {
            assertThat(page(plain, "grp_other.md")).contains("(grp_signoff.md#sec-28)")
        }

        @Test
        @DisplayName("every link in every page of the grouped render resolves to an anchor that exists")
        fun noDeadLinks() {
            val anchorsByPage = grouped.filterKeys { it.endsWith(".md") }.mapValues { anchors(it.value).toSet() }
            for ((name, markdown) in grouped.filterKeys { it.endsWith(".md") }) {
                val dir = name.substringBeforeLast('/', "")
                for (match in Regex("\\]\\(([^)#]*)#([^)]*)\\)").findAll(markdown)) {
                    val (target, anchor) = match.destructured
                    if (target.startsWith("http")) continue
                    val resolved = if (target.isEmpty()) name else Path.of(dir).resolve(target).normalize().toString().replace('\\', '/').removePrefix("/")
                    assertThat(anchorsByPage).describedAs("$name links to $target#$anchor").containsKey(resolved)
                    assertThat(anchorsByPage.getValue(resolved)).describedAs("$name -> $resolved#$anchor").contains(anchor)
                }
            }
        }
    }

    @Nested
    @DisplayName("The index")
    inner class Index {

        @Test
        @DisplayName("a grouped control is ONE row, linking to its page, listing its packages, rules and source files")
        fun oneRow() {
            val row = onlyRow(indexRows(grouped), "| SEC-28 |")
            assertThat(row).contains("controls/sec-28.md#sec-28").contains("Evidence is attached")
            assertThat(row).contains("`grp_evidence`").contains("`grp_signoff`")
            assertThat(row).contains("`evidence_present`").contains("`signed_off`").contains("`valid_signature`")
            assertThat(row).contains("`ctrl_28_evidence.rego`").contains("`ctrl_28_signoff.rego`").contains("`ctrl_28_signoff_validation.rego`")
        }

        @Test
        @DisplayName("the rows of other controls are the same as without the option; without it SEC-28 is three rows")
        fun otherRowsUnchanged() {
            assertThat(onlyRow(indexRows(grouped), "| SEC-30 |")).isEqualTo(onlyRow(indexRows(plain), "| SEC-30 |"))
            assertThat(indexRows(plain).count { it.startsWith("| SEC-28 |") }).isEqualTo(3)
            assertThat(indexRows(grouped).count { it.startsWith("| X-1 |") }).isEqualTo(1)
        }

        @Test
        @DisplayName("quality counts a grouped control once: SEC-28 has a description and a framework on some rule so it is not listed; X-1 and x_1 are listed once each")
        fun qualityCountsControls() {
            val section = page(grouped, "index.md").substringAfter("## Documentation quality").substringBefore("## How to read")
            val missing = section.lines().single { it.startsWith("- **Missing description") }
            val noFramework = section.lines().single { it.startsWith("- **No framework") }
            for (line in listOf(missing, noFramework)) {
                assertThat(line).doesNotContain("SEC-28")
                assertThat(Regex("\\bX-1\\b").findAll(line).count()).isEqualTo(1)
                assertThat(Regex("\\bx_1\\b").findAll(line).count()).isEqualTo(1)
            }
        }

        @Test
        @DisplayName("without the option the findings list each rule, as before")
        fun qualityUnchangedWithoutTheOption() {
            val line = page(plain, "index.md").lines().single { it.startsWith("- **Missing description") }
            assertThat(Regex("\\bSEC-28\\b").findAll(line).count()).isEqualTo(2)
        }
    }

    @Nested
    @DisplayName("explico.json")
    inner class ExplicoJson {

        @Test
        @DisplayName("a `controls` array, one entry per grouped control in id order, with its page, anchor, title and rules in page order")
        fun controlsArray() {
            val controls = arrayOrNull(json(grouped), "controls", "explico.json").jsonArray.map { it.jsonObject }
            assertThat(controls.map { it.getValue("controlId").jsonPrimitive.content }).containsExactly("SEC-28", "X-1", "x_1")
            val sec28 = controls.first()
            assertThat(sec28.getValue("page").jsonPrimitive.content).isEqualTo("controls/sec-28.md")
            assertThat(sec28.getValue("anchor").jsonPrimitive.content).isEqualTo("sec-28")
            assertThat(sec28.getValue("title").jsonPrimitive.content).isEqualTo("Evidence is attached")
            assertThat(sec28.getValue("rules").jsonArray.map { it.jsonObject.getValue("rule").jsonPrimitive.content }).containsExactly("evidence_present", "signed_off", "valid_signature")
            assertThat(sec28.getValue("rules").jsonArray.first().jsonObject.getValue("package").jsonPrimitive.content).isEqualTo("grp_evidence")
            assertThat(sec28.getValue("rules").jsonArray.first().jsonObject.getValue("sourceFile").jsonPrimitive.content).isEqualTo("ctrl_28_evidence.rego")
        }

        @Test
        @DisplayName("no such key without the option")
        fun absentWithoutTheOption() {
            assertThat(json(plain)).doesNotContainKey("controls")
        }

        @Test
        @DisplayName("a grouped rule names the page it lives on and carries its anchor there; an ungrouped rule is as before")
        fun ruleSaysWhereItLives() {
            val rule = ruleJson(grouped, "grp_signoff", "signed_off")
            assertThat(arrayOrNull(rule, "controlPage", "the grouped rule").jsonPrimitive.content).isEqualTo("controls/sec-28.md")
            assertThat(rule.getValue("anchor").jsonPrimitive.content).isEqualTo("sec-28-grp-signoff-signed-off")
            assertThat(anchors(page(grouped, "controls/sec-28.md"))).contains(rule.getValue("anchor").jsonPrimitive.content)
            val retention = ruleJson(grouped, "grp_other", "retention_ok")
            assertThat(retention).doesNotContainKey("controlPage")
            assertThat(retention.getValue("anchor").jsonPrimitive.content).isEqualTo("sec-30")
        }

        @Test
        @DisplayName("the quality block counts controls too: X-1 once, SEC-28 not at all")
        fun qualityBlock() {
            val missing = json(grouped).getValue("quality").jsonObject.getValue("missingDescription").jsonArray.map { it.jsonPrimitive.content }
            assertThat(missing).doesNotContain("SEC-28")
            assertThat(missing.count { it == "X-1" }).isEqualTo(1)
        }
    }

    @Nested
    @DisplayName("Together with ids derived from file names (FR-6)")
    inner class WithDerivedIds {

        @Test
        @DisplayName("rules that share a DERIVED id are grouped too: CTRL-9 (two packages) and CTRL-30 (two rules of one file); a METADATA id still wins, so SEC-28's page is unchanged")
        fun derivedIdsGroup() {
            assertThat(derivedGrouped.keys.filter { it.startsWith("controls/") }.sorted())
                .containsExactly("controls/ctrl-30.md", "controls/ctrl-9.md", "controls/sec-28.md", "controls/x-1-2.md", "controls/x-1.md")
            assertThat(page(derivedGrouped, "controls/sec-28.md")).isEqualTo(page(grouped, "controls/sec-28.md"))
        }

        @Test
        @DisplayName("a derived-id control page keeps the 'derived from the file name' marker on each of its rules, and the index legend still explains it")
        fun provenanceSurvives() {
            val nine = page(derivedGrouped, "controls/ctrl-9.md")
            assertThat(headings(nine).map { it.substringBefore(" — ") }).containsExactly("grp_nine_a.a1", "grp_nine_b.b1")
            assertThat(Regex("derived from the file name").findAll(nine).count()).isEqualTo(2)
            assertThat(page(derivedGrouped, "index.md")).contains("**Derived control ids**")
        }
    }
}
