/**
 * Acceptance tests for FR-3, FR-4 and FR-5 of the decision-object feature requests (specs/decision-object-rendering-spec.md): three more
 * DECLARED projections of an object-valued rule, in `custom:` METADATA, built exactly like the verdict (FR-2) -- explico reads declared field
 * paths and the values `opa eval` produced, never meaning:
 *
 * - `actions {at, label}`: the array of remediation actions and the path to each one's label. Shown under the example as
 *   `*Remediation offered:*` with one `` - `<label>` `` line per action, or `*No remediation offered.*` for an empty array. No invented words:
 *   only the declared label (a `kind` key is deliberately not supported).
 * - `annotations {at}`: an array of strings, appended to the summary line as ` · *status: a, b*`.
 * - `timing {dueBy, startBefore}`: two field paths whose string values are printed VERBATIM (`*Timing:* due by ...; start before ...`).
 *
 * Strict and independent: an example whose value does not fit a projection simply lacks that projection (never a partial list, never a
 * coerced value), without affecting the others or the verdict. A wrongly-shaped declaration is ignored with ONE stderr warning naming the key
 * and the rule. Opt-in: nothing changes without a declaration.
 *
 * Authored from the feature request and the implementation plan, NOT from the frozen acceptance-pack README. Fixtures:
 * `realistic/declared-projections/{basic,shapes,malformed,diff,diff-single}`. The explico.json shape pinned here (all absent unless declared /
 * applied): a rule gains `actions: {at, label}`, `annotations: {at}`, `timing: {dueBy, startBefore}`; an example gains `remediation: [labels]`,
 * `annotations: [strings]`, `timing: {dueBy, startBefore}`.
 */
package io.explico.acceptance

import io.explico.DiffReport
import io.explico.Explico
import io.explico.diff.DiffCategory
import io.explico.opa.OpaRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path

@DisplayName("Realistic policies: decision objects -- declared actions, annotations and timing (FR-3, FR-4, FR-5)")
class RealisticDeclaredProjectionsAcceptanceIT {

    private class Rendered(val files: Map<String, String>, val stderr: String) {
        val json: JsonObject get() = Json.parseToJsonElement(files.getValue("explico.json")).jsonObject
    }

    companion object {
        private val root = Path.of("src/test/resources/realistic/declared-projections")

        private const val REMEDIATION = "  *Remediation offered:*"
        private const val NONE_OFFERED = "  *No remediation offered.*"

        private lateinit var basic: Rendered
        private lateinit var shapes: Rendered
        private lateinit var malformed: Rendered
        private lateinit var diffAll: DiffReport
        private lateinit var diffOne: DiffReport

        /** Renders one corpus through the public facade, capturing everything written to stderr while it loads and renders. */
        private fun render(name: String): Rendered {
            val policies = root.resolve(name).resolve("policies")
            val original = System.err
            val buffer = ByteArrayOutputStream()
            System.setErr(PrintStream(buffer, true, Charsets.UTF_8))
            try {
                val files = Explico.render(Explico.load(policies), policies, Explico.loadExamples(root.resolve(name).resolve("examples")), null, false, true).files
                return Rendered(files, buffer.toString(Charsets.UTF_8))
            } finally {
                System.setErr(original)
            }
        }

        @JvmStatic
        @BeforeAll
        fun renderAll() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            basic = render("basic")
            shapes = render("shapes")
            malformed = render("malformed")
            diffAll = Explico.diff(Explico.load(root.resolve("diff/old")), Explico.load(root.resolve("diff/new")))
            diffOne = Explico.diff(Explico.load(root.resolve("diff-single/old")), Explico.load(root.resolve("diff-single/new")))
        }

        private fun card(r: Rendered, doc: String, id: String): String = r.files.getValue(doc).substringAfter("<a id=\"$id\"></a>").substringBefore("<a id=\"")

        private fun examples(r: Rendered, doc: String, id: String): String = card(r, doc, id).substringAfter("**Worked examples**").substringBefore("*Examples are evaluated")

        /** The lines of the bullet for the fixture called [name]: its head line and everything indented under it. */
        private fun bullet(section: String, name: String): List<String> {
            val lines = section.lines()
            val start = lines.indexOfFirst { it.startsWith("- **$name** —") }
            assertThat(start).describedAs("a worked-example bullet for '$name'").isGreaterThanOrEqualTo(0)
            val end = lines.drop(start + 1).indexOfFirst { !it.startsWith("  ") }.let { if (it < 0) lines.size else start + 1 + it }
            return lines.subList(start, end)
        }

        private fun rule(r: Rendered, controlId: String): JsonObject =
            r.json.getValue("packages").jsonArray.flatMap { it.jsonObject.getValue("rules").jsonArray }
                .first { it.jsonObject["controlId"]?.jsonPrimitive?.content == controlId }.jsonObject

        private fun exampleJson(r: Rendered, controlId: String, name: String): JsonObject =
            rule(r, controlId).getValue("workedExamples").jsonArray.first { it.jsonObject.getValue("name").jsonPrimitive.content == name }.jsonObject

        /** A key's object; an ASSERTION failure (not an exception) when it is absent. */
        private fun objectAt(owner: JsonObject, key: String, what: String): JsonObject {
            assertThat(owner).describedAs("$what carries `$key`").containsKey(key)
            return owner.getValue(key).jsonObject
        }

        private fun arrayAt(owner: JsonObject, key: String, what: String): JsonArray {
            assertThat(owner).describedAs("$what carries `$key`").containsKey(key)
            return owner.getValue(key).jsonArray
        }

        private fun strings(array: JsonArray) = array.map { it.jsonPrimitive.content }

        /**
         * The labels listed under a `*Remediation offered:*` line, in order. They are nested ONE LEVEL DEEPER than the example's own sub-bullets
         * (four spaces, not two), so the input-path values that follow stay a separate list and never read as more remediation items.
         */
        private fun remediationLabels(lines: List<String>): List<String> {
            val at = lines.indexOf(REMEDIATION)
            assertThat(at).describedAs("a '$REMEDIATION' line").isGreaterThanOrEqualTo(0)
            return lines.drop(at + 1).takeWhile { it.startsWith("    - `") && it.endsWith("`") }.map { it.removePrefix("    - `").removeSuffix("`") }
        }

        private fun warningsAbout(stderr: String, key: String, rule: String) = stderr.lines().filter { it.contains(key) && it.contains(rule) }
    }

    @Nested
    @DisplayName("CTRL-P-ALL: a verdict, actions, annotations and timing declared together")
    inner class Everything {
        private val section get() = examples(basic, "pd_all.md", "ctrl-p-all")

        @Test
        @DisplayName("the status goes on the summary line, after the verdict words and before nothing else")
        fun statusOnTheSummaryLine() {
            assertThat(bullet(section, "no attestation yet")[0]).isEqualTo("- **no attestation yet** — ❌ not compliant · *status: not_started*")
            assertThat(bullet(section, "expired attestation")[0]).isEqualTo("- **expired attestation** — ❌ not compliant · *status: expired, overdue*")
        }

        @Test
        @DisplayName("an empty annotations array adds nothing to the summary line")
        fun emptyAnnotationsAddNothing() {
            assertThat(bullet(section, "current attestation")[0]).isEqualTo("- **current attestation** — ✅ compliant")
        }

        @Test
        @DisplayName("each action's declared label is listed, in array order, under '*Remediation offered:*'")
        fun remediationLabels() {
            assertThat(remediationLabels(bullet(section, "no attestation yet"))).containsExactly("attest.submit", "attest.confirm")
            assertThat(remediationLabels(bullet(section, "expired attestation"))).containsExactly("attest.renew")
        }

        @Test
        @DisplayName("the remediation labels are nested deeper than the input-path values that follow, so the two never run together as one list")
        fun remediationIsNotMergedWithPathValues() {
            val lines = bullet(section, "no attestation yet")
            val firstPathValue = lines.indexOfFirst { it.startsWith("  - `form") }
            assertThat(firstPathValue).describedAs("a path-value bullet").isGreaterThan(lines.indexOf(REMEDIATION))
            assertThat(lines.subList(lines.indexOf(REMEDIATION) + 1, firstPathValue)).isNotEmpty().allMatch { it.startsWith("    - `") }
            assertThat(lines.filter { it.startsWith("  - `") }).describedAs("only path values sit at the example's own sub-bullet level")
                .allMatch { it.contains("`: ") }
        }

        @Test
        @DisplayName("an empty actions array says '*No remediation offered.*' and lists nothing")
        fun noRemediationOffered() {
            val lines = bullet(section, "current attestation")
            assertThat(lines).contains(NONE_OFFERED).doesNotContain(REMEDIATION)
        }

        @Test
        @DisplayName("timing values are printed verbatim; a null or empty value drops its clause, and none shown means no Timing line")
        fun timing() {
            assertThat(bullet(section, "no attestation yet").none { it.startsWith("  *Timing:*") }).describedAs("null/null -> no Timing line").isTrue()
            assertThat(bullet(section, "expired attestation")).contains("  *Timing:* due by `2025-01-01T00:00:00Z`")
            assertThat(bullet(section, "current attestation")).contains("  *Timing:* due by `2025-07-09T00:00:00Z`; start before `2025-06-09T00:00:00Z`")
        }

        @Test
        @DisplayName("one example carries all four: verdict line, reason, status, timing and remediation")
        fun allFourOnOneExample() {
            val lines = bullet(section, "expired attestation")
            assertThat(lines[0]).startsWith("- **expired attestation** — ❌ not compliant").endsWith("*status: expired, overdue*")
            assertThat(lines[1].trim()).isEqualTo("*\"Attestation has expired\"*")
            assertThat(lines).contains("  *Timing:* due by `2025-01-01T00:00:00Z`").contains(REMEDIATION)
        }

        @Test
        @DisplayName("the values of the referenced paths are still listed, and the raw JSON stays gone")
        fun pathValuesSurvive() {
            assertThat(bullet(section, "expired attestation")).anyMatch { it.trim() == "- `form ▸ expired`: `true`" }
            // The raw JSON MESSAGE line is gone. (A path-value line may legitimately hold an object -- `form` is one -- so the whole section is not searched.)
            assertThat(section.lines().filter { it.trim().startsWith("*\"{") }).isEmpty()
            assertThat(section).doesNotContain("— matched")
        }
    }

    @Nested
    @DisplayName("Independence: each projection works without the others, and without a verdict")
    inner class Independence {

        @Test
        @DisplayName("CTRL-P-ACT (actions only): '— matched' and the raw JSON stay, the remediation lines are added, nothing else is")
        fun actionsOnly() {
            val section = examples(basic, "pd_actions_only.md", "ctrl-p-act")
            val first = bullet(section, "no attestation yet")
            assertThat(first[0]).isEqualTo("- **no attestation yet** — matched")
            assertThat(first[1].trim()).startsWith("*\"{\"").contains("No attestation provided")
            assertThat(remediationLabels(first)).containsExactly("attest.submit", "attest.confirm")
            assertThat(first.joinToString("\n")).doesNotContain("✅").doesNotContain("❌").doesNotContain("status:").doesNotContain("*Timing:*")
            assertThat(bullet(section, "current attestation")).contains(NONE_OFFERED)
        }

        @Test
        @DisplayName("CTRL-P-ANN (annotations only): the status follows '— matched'; an empty array adds nothing")
        fun annotationsOnly() {
            val section = examples(basic, "pd_annotations_only.md", "ctrl-p-ann")
            assertThat(bullet(section, "no attestation yet")[0]).isEqualTo("- **no attestation yet** — matched · *status: not_started*")
            assertThat(bullet(section, "expired attestation")[0]).isEqualTo("- **expired attestation** — matched · *status: expired, overdue*")
            assertThat(bullet(section, "current attestation")[0]).isEqualTo("- **current attestation** — matched")
            assertThat(section).doesNotContain(REMEDIATION).doesNotContain("*Timing:*")
        }

        @Test
        @DisplayName("CTRL-P-TIM (only dueBy declared): only the declared clause is ever shown, though the value has a startBefore too")
        fun timingOnly() {
            val section = examples(basic, "pd_timing_only.md", "ctrl-p-tim")
            assertThat(bullet(section, "expired attestation")).contains("  *Timing:* due by `2025-01-01T00:00:00Z`")
            assertThat(bullet(section, "current attestation")).contains("  *Timing:* due by `2025-07-09T00:00:00Z`")
            assertThat(bullet(section, "no attestation yet").none { it.startsWith("  *Timing:*") }).isTrue()
            assertThat(section).doesNotContain("start before").doesNotContain(REMEDIATION).doesNotContain("status:")
        }

        @Test
        @DisplayName("no warning about anything declared correctly")
        fun noWarnings() {
            assertThat(basic.stderr).doesNotContain("custom.actions").doesNotContain("custom.annotations").doesNotContain("custom.timing")
        }
    }

    @Nested
    @DisplayName("Per-example strictness: what the value does not support is simply not shown")
    inner class Shapes {
        private val actionsA get() = examples(shapes, "pd_shapes.md", "ctrl-p-sa")
        private val actionsB get() = examples(shapes, "pd_shapes.md", "ctrl-p-sb")
        private val annotations get() = examples(shapes, "pd_shapes.md", "ctrl-p-sc")
        private val timing get() = examples(shapes, "pd_shapes.md", "ctrl-p-sd")

        private fun assertNoRemediation(section: String, name: String) {
            val lines = bullet(section, name)
            assertThat(lines[0]).describedAs(name).isEqualTo("- **$name** — matched")
            assertThat(lines[1].trim()).describedAs("$name keeps its raw JSON").startsWith("*\"")
            assertThat(lines.joinToString("\n")).describedAs(name).doesNotContain("Remediation offered").doesNotContain("No remediation offered")
        }

        @Test
        @DisplayName("`at` is not an array -> nothing")
        fun notAnArray() = assertNoRemediation(actionsA, "actions-not-array")

        @Test
        @DisplayName("an action without the declared label -> nothing (not a list with a hole)")
        fun missingLabel() = assertNoRemediation(actionsA, "actions-missing-label")

        @Test
        @DisplayName("a label that is not a string (7) -> nothing")
        fun numericLabel() = assertNoRemediation(actionsA, "actions-numeric-label")

        @Test
        @DisplayName("one good action and one without a label -> NO partial list: the good label is not shown either")
        fun mixed() {
            assertNoRemediation(actionsB, "actions-mixed")
            // (the raw JSON line legitimately contains the text "good.one"; what must be absent is its remediation BULLET)
            assertThat(bullet(actionsB, "actions-mixed")).doesNotContain("    - `good.one`")
        }

        @Test
        @DisplayName("no `actions` field at all -> nothing: absent is not 'no remediation offered'")
        fun fieldAbsent() = assertNoRemediation(actionsB, "actions-field-absent")

        @Test
        @DisplayName("the value is not an object -> nothing")
        fun scalarValue() = assertNoRemediation(actionsB, "actions-scalar-value")

        @Test
        @DisplayName("annotations that are not all strings, or not an array -> nothing on the summary line")
        fun annotationShapes() {
            for (name in listOf("ann-mixed", "ann-number", "ann-not-array")) {
                assertThat(bullet(annotations, name)[0]).describedAs(name).isEqualTo("- **$name** — matched")
            }
        }

        @Test
        @DisplayName("a timing value that is a number, null or an empty string is omitted; none shown means no Timing line")
        fun unusableTimingValues() {
            for (name in listOf("time-number", "time-empty")) {
                assertThat(bullet(timing, name).none { it.startsWith("  *Timing:*") }).describedAs(name).isTrue()
            }
        }

        @Test
        @DisplayName("only one clause usable -> only that clause")
        fun oneClause() {
            assertThat(bullet(timing, "time-start-only")).contains("  *Timing:* start before `2025-06-09T00:00:00Z`")
        }

        @Test
        @DisplayName("none of this is a warning: a well-formed declaration meeting an unexpected value is not a mistake in the METADATA")
        fun noWarnings() {
            assertThat(shapes.stderr).doesNotContain("custom.")
        }
    }

    @Nested
    @DisplayName("Wrongly-shaped declarations are ignored with one warning each, and the rule renders as without them")
    inner class Malformed {

        private val actionsRules = listOf("bad_actions_scalar", "bad_actions_no_at", "bad_actions_no_label", "bad_actions_nonstring_at", "bad_actions_empty_segment")
        private val annotationRules = listOf("bad_annotations_no_at", "bad_annotations_nonstring_at")
        private val timingRules = listOf("bad_timing_empty", "bad_timing_nonstring")

        @Test
        @DisplayName("`custom.actions`: a scalar, no `at`, no `label`, a non-string `at`, an empty path segment -> exactly one warning each")
        fun actionsWarned() {
            for (name in actionsRules) assertThat(warningsAbout(malformed.stderr, "custom.actions", name)).describedAs(name).hasSize(1)
        }

        @Test
        @DisplayName("`custom.annotations`: no `at`, a non-string `at` -> exactly one warning each")
        fun annotationsWarned() {
            for (name in annotationRules) assertThat(warningsAbout(malformed.stderr, "custom.annotations", name)).describedAs(name).hasSize(1)
        }

        @Test
        @DisplayName("`custom.timing`: neither field, a non-string field name -> exactly one warning each")
        fun timingWarned() {
            for (name in timingRules) assertThat(warningsAbout(malformed.stderr, "custom.timing", name)).describedAs(name).hasSize(1)
        }

        @Test
        @DisplayName("no other warning: exactly nine in total, one per bad rule")
        fun nineInTotal() {
            assertThat(malformed.stderr.lines().filter { it.contains("warning") }).hasSize(9)
        }

        @Test
        @DisplayName("every bad rule renders as without the declaration: '— matched', raw JSON, no remediation, status or timing, no leaked 'Custom:' line")
        fun renderedAsWithoutIt() {
            for (n in 1..9) {
                val id = "ctrl-p-bad$n"
                val card = card(malformed, "pd_malformed.md", id)
                val lines = bullet(examples(malformed, "pd_malformed.md", id), "no attestation yet")
                assertThat(lines[0]).describedAs(id).isEqualTo("- **no attestation yet** — matched")
                assertThat(lines[1].trim()).describedAs(id).startsWith("*\"{\"")
                assertThat(lines.joinToString("\n")).describedAs(id).doesNotContain("Remediation").doesNotContain("status:").doesNotContain("*Timing:*")
                assertThat(card).describedAs("$id: a reserved key is never a passthrough 'Custom:' field").doesNotContain("*Custom:")
            }
        }

        @Test
        @DisplayName("explico.json carries none of the three keys for them")
        fun noJsonKeys() {
            for (n in 1..9) {
                val r = rule(malformed, "CTRL-P-BAD$n")
                assertThat(r).describedAs("CTRL-P-BAD$n").doesNotContainKey("actions").doesNotContainKey("annotations").doesNotContainKey("timing")
            }
        }
    }

    @Nested
    @DisplayName("explico.json: the same projected facts, absent unless declared or applied")
    inner class ExplicoJson {

        @Test
        @DisplayName("a rule carries the declarations it made, with the field names as declared")
        fun declarations() {
            val all = rule(basic, "CTRL-P-ALL")
            assertThat(objectAt(all, "actions", "CTRL-P-ALL").mapValues { it.value.jsonPrimitive.content }).containsExactlyInAnyOrderEntriesOf(mapOf("at" to "actions", "label" to "action.predicate"))
            assertThat(objectAt(all, "annotations", "CTRL-P-ALL").getValue("at").jsonPrimitive.content).isEqualTo("annotations")
            val timing = objectAt(all, "timing", "CTRL-P-ALL")
            assertThat(timing.getValue("dueBy").jsonPrimitive.content).isEqualTo("dueBy")
            assertThat(timing.getValue("startBefore").jsonPrimitive.content).isEqualTo("startBefore")
        }

        @Test
        @DisplayName("an undeclared timing field is null; a rule that declared only one projection has no key for the others")
        fun onlyWhatWasDeclared() {
            assertThat(objectAt(rule(basic, "CTRL-P-TIM"), "timing", "CTRL-P-TIM").getValue("startBefore")).isEqualTo(JsonNull)
            assertThat(rule(basic, "CTRL-P-TIM")).doesNotContainKey("actions").doesNotContainKey("annotations")
            assertThat(rule(basic, "CTRL-P-ACT")).containsKey("actions").doesNotContainKey("annotations").doesNotContainKey("timing")
            assertThat(rule(basic, "CTRL-P-ANN")).containsKey("annotations").doesNotContainKey("actions").doesNotContainKey("timing")
        }

        @Test
        @DisplayName("an example carries its remediation labels, status strings and timing values (nulls where nothing was shown)")
        fun projectedExamples() {
            val first = exampleJson(basic, "CTRL-P-ALL", "no attestation yet")
            assertThat(strings(arrayAt(first, "remediation", "the first example"))).containsExactly("attest.submit", "attest.confirm")
            assertThat(strings(arrayAt(first, "annotations", "the first example"))).containsExactly("not_started")
            val none = objectAt(first, "timing", "the first example")
            assertThat(none.getValue("dueBy")).isEqualTo(JsonNull)
            assertThat(none.getValue("startBefore")).isEqualTo(JsonNull)

            val current = exampleJson(basic, "CTRL-P-ALL", "current attestation")
            assertThat(arrayAt(current, "remediation", "the current example")).isEmpty()
            assertThat(arrayAt(current, "annotations", "the current example")).isEmpty()
            val both = objectAt(current, "timing", "the current example")
            assertThat(both.getValue("dueBy").jsonPrimitive.content).isEqualTo("2025-07-09T00:00:00Z")
            assertThat(both.getValue("startBefore").jsonPrimitive.content).isEqualTo("2025-06-09T00:00:00Z")
        }

        @Test
        @DisplayName("every non-null JSON value is on the example's Markdown bullet")
        fun agreesWithMarkdown() {
            val section = examples(basic, "pd_all.md", "ctrl-p-all")
            val all = rule(basic, "CTRL-P-ALL").getValue("workedExamples").jsonArray.map { it.jsonObject }
            assertThat(all).hasSize(3)
            for (example in all) {
                val lines = bullet(section, example.getValue("name").jsonPrimitive.content)
                val remediation = strings(arrayAt(example, "remediation", "an example"))
                if (remediation.isEmpty()) assertThat(lines).contains(NONE_OFFERED) else assertThat(remediationLabels(lines)).isEqualTo(remediation)
                val status = strings(arrayAt(example, "annotations", "an example"))
                if (status.isNotEmpty()) assertThat(lines[0]).endsWith("· *status: ${status.joinToString(", ")}*") else assertThat(lines[0]).doesNotContain("status:")
                val timing = objectAt(example, "timing", "an example")
                val shown = listOf("dueBy" to "due by", "startBefore" to "start before").filter { timing.getValue(it.first) != JsonNull }
                    .joinToString("; ") { (key, words) -> "$words `${timing.getValue(key).jsonPrimitive.content}`" }
                if (shown.isNotEmpty()) assertThat(lines).contains("  *Timing:* $shown") else assertThat(lines.none { it.startsWith("  *Timing:*") }).isTrue()
            }
        }

        @Test
        @DisplayName("a projection that was not declared adds no key to the example")
        fun absentWhenNotDeclared() {
            for (example in rule(basic, "CTRL-P-ACT").getValue("workedExamples").jsonArray.map { it.jsonObject }) {
                assertThat(example).containsKey("remediation").doesNotContainKey("annotations").doesNotContainKey("timing").doesNotContainKey("verdict")
            }
        }

        @Test
        @DisplayName("an example that did not fit has no key for that projection (no empty list standing in for 'unusable')")
        fun absentWhenNotApplied() {
            assertThat(exampleJson(shapes, "CTRL-P-SA", "actions-numeric-label")).doesNotContainKey("remediation")
            assertThat(exampleJson(shapes, "CTRL-P-SB", "actions-mixed")).doesNotContainKey("remediation")
            assertThat(exampleJson(shapes, "CTRL-P-SB", "actions-field-absent")).doesNotContainKey("remediation")
            assertThat(exampleJson(shapes, "CTRL-P-SC", "ann-mixed")).doesNotContainKey("annotations")
        }

        @Test
        @DisplayName("timing keeps both keys for an object value: null where a value was unusable, the string where it was shown")
        fun timingShapes() {
            val number = objectAt(exampleJson(shapes, "CTRL-P-SD", "time-number"), "timing", "time-number")
            assertThat(number.getValue("dueBy")).isEqualTo(JsonNull)
            assertThat(number.getValue("startBefore")).isEqualTo(JsonNull)
            val startOnly = objectAt(exampleJson(shapes, "CTRL-P-SD", "time-start-only"), "timing", "time-start-only")
            assertThat(startOnly.getValue("dueBy")).isEqualTo(JsonNull)
            assertThat(startOnly.getValue("startBefore").jsonPrimitive.content).isEqualTo("2025-06-09T00:00:00Z")
        }
    }

    @Nested
    @DisplayName("diff: editing a declaration is a documentation change")
    inner class DiffOfTheDeclarations {

        @Test
        @DisplayName("editing the actions, annotations and timing declarations (verdict untouched) is DOCS_CHANGED, never LOGIC_CHANGED")
        fun docsChanged() {
            val changed = diffAll.entries.filter { it.category != DiffCategory.UNCHANGED }
            assertThat(changed.map { it.controlId to it.category }).containsExactly("CTRL-P-DIFF" to DiffCategory.DOCS_CHANGED)
        }

        @Test
        @DisplayName("the report names the three fields in the fixed order: actions, annotations, timing")
        fun summaryNamesTheFields() {
            assertThat(diffAll.markdown).contains("documentation changed: actions, annotations, timing")
        }

        @Test
        @DisplayName("the docs table has a row per changed declaration showing the old and new field names, and none for the unchanged verdict")
        fun docsTableRows() {
            val rows = diffAll.markdown.lines()
            assertThat(rows.filter { it.startsWith("| Actions |") && it.contains("`action.predicate`") && it.contains("`action.name`") }).hasSize(1)
            assertThat(rows.filter { it.startsWith("| Annotations |") && it.contains("`annotations`") && it.contains("`tags`") }).hasSize(1)
            assertThat(rows.filter { it.startsWith("| Timing |") && it.contains("`dueBy`") && it.contains("`due`") }).hasSize(1)
            assertThat(diffAll.markdown).doesNotContain("| Verdict |")
        }

        @Test
        @DisplayName("editing ONLY the timing declaration names only timing and shows only its row")
        fun onlyTiming() {
            assertThat(diffOne.entries.filter { it.category != DiffCategory.UNCHANGED }.map { it.category }).containsExactly(DiffCategory.DOCS_CHANGED)
            assertThat(diffOne.markdown).contains("documentation changed: timing").doesNotContain("documentation changed: actions")
            assertThat(diffOne.markdown.lines().filter { it.startsWith("| Timing |") }).hasSize(1)
            assertThat(diffOne.markdown).doesNotContain("| Actions |").doesNotContain("| Annotations |")
        }
    }
}
