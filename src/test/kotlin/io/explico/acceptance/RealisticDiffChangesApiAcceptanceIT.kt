/**
 * Acceptance tests for `DiffReport.changes` (spec §14.80 step 4): a consumer reads WHICH controls changed, and how, without touching the structural model. Authored from the 1.0 API review,
 * NOT from the frozen acceptance-pack README. Decisions pinned here (operator decisions of 2026-11-01; /tdd must match them):
 *
 * - `DiffChange(controlId, category, label, packagePath, ruleName, anchor)` in `io.explico.diff`, and `DiffReport.changes: List<DiffChange>` as the LAST field of `DiffReport`.
 * - It is a PROJECTION of what `explico.json`'s `changes` array already carries (category, controlId, label, package -> packagePath, rule -> ruleName, anchor): the SAME entries -- everything
 *   but UNCHANGED -- in the SAME (report) order, with the SAME strings, never recomputed. Pinned both as exact lists (from the fixtures) and field by field against the JSON.
 * - `DiffReport.entries` keeps listing every control, UNCHANGED included, but is opt-in (`@ExplicoStructuralModel`): this class must not need `DiffEntry`, `RuleGroup`, `Condition` ... by
 *   name, which is the point.
 *
 * Facade only. A wrong or empty `changes` is an assertion failure, never an exception.
 */
package io.explico.acceptance

import io.explico.DataVersions
import io.explico.DiffOptions
import io.explico.DiffReport
import io.explico.Explico
import io.explico.ExplicoStructuralModel
import io.explico.diff.DiffCategory
import io.explico.diff.DiffChange
import io.explico.model.PolicySet
import io.explico.opa.OpaRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path

@DisplayName("Realistic policies: DiffReport.changes")
class RealisticDiffChangesApiAcceptanceIT {

    companion object {
        private val diffAll = Path.of("src/test/resources/diff/diff-all-categories")
        private val grouped = Path.of("src/test/resources/realistic/group-by-control-diff")
        private val dataBase = Path.of("src/test/resources/realistic/data-aware-diff")

        private lateinit var all: DiffReport
        private lateinit var allAgain: DiffReport
        private lateinit var groupedReport: DiffReport
        private lateinit var dataReport: DiffReport
        private lateinit var identical: DiffReport

        @JvmStatic
        @BeforeAll
        fun diffAll() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            all = Explico.diff(Explico.load(diffAll.resolve("old")), Explico.load(diffAll.resolve("new")), DiffOptions())
            allAgain = Explico.diff(Explico.load(diffAll.resolve("old")), Explico.load(diffAll.resolve("new")), DiffOptions())
            groupedReport = Explico.diff(Explico.load(grouped.resolve("old")), Explico.load(grouped.resolve("new")), DiffOptions(groupByControl = true))
            val policies = Explico.load(dataBase.resolve("policies/old"))
            dataReport = Explico.diff(policies, policies, DiffOptions(data = DataVersions(dataBase.resolve("data/old.json"), dataBase.resolve("data/limit-changed.json"))))
            identical = Explico.diff(policies, policies, DiffOptions())
        }

        /** The `changes` array of the report's JSON, as the values `DiffChange` is a projection of. */
        private fun jsonChanges(report: DiffReport): List<JsonObject> =
            Json.parseToJsonElement(report.json).jsonObject.getValue("changes").jsonArray.map { it.jsonObject }

        private fun string(o: JsonObject, key: String): String? = o[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
    }

    @Test
    @DisplayName("diff-all-categories: the four changed controls, in report order; the renamed REL-002 and the untouched rule are not in it")
    fun allCategories() {
        assertThat(all.changes).containsExactly(
            DiffChange("REL-001", DiffCategory.REMOVED, "REL-001 — Production change approval", "release.approvals", "deny", "rel-001"),
            DiffChange("REL-005", DiffCategory.ADDED, "REL-005 — New deployment window check", "release.newcheck", "deny", "rel-005"),
            DiffChange("REL-003", DiffCategory.LOGIC_CHANGED, "REL-003 — Artifact provenance", "release.provenance", "deny", "rel-003"),
            DiffChange("REL-004", DiffCategory.DOCS_CHANGED, "REL-004 — Release governance evidence (updated)", "release.governance", "deny", null),
        )
    }

    @Test
    @DisplayName("field by field the same as explico.json's changes: same entries, same order, same strings (package -> packagePath, rule -> ruleName, null stays null)")
    fun projectionOfTheJson() {
        for (report in listOf(all, groupedReport, dataReport)) {
            val json = jsonChanges(report)
            assertThat(json).describedAs("the JSON has changes to compare").isNotEmpty()
            assertThat(report.changes).hasSameSizeAs(json)
            report.changes.zip(json).forEach { (change, o) ->
                assertThat(change.category.name).isEqualTo(string(o, "category"))
                assertThat(change.controlId).isEqualTo(string(o, "controlId"))
                assertThat(change.label).isEqualTo(string(o, "label"))
                assertThat(change.packagePath).isEqualTo(string(o, "package"))
                assertThat(change.ruleName).isEqualTo(string(o, "rule"))
                assertThat(change.anchor).isEqualTo(string(o, "anchor"))
            }
        }
    }

    @Test
    @DisplayName("a control id shared by several rules (groupByControl): each rule is its own change, labelled with its package and rule, a rule without a control id has a null controlId")
    fun sharedControlIds() {
        assertThat(groupedReport.changes).containsExactly(
            DiffChange("SEC-40", DiffCategory.REMOVED, "SEC-40 — Lifecycle (grp_life.drop)", "grp_life", "drop", "sec-40-grp-life-drop"),
            DiffChange("SEC-40", DiffCategory.ADDED, "SEC-40 — Lifecycle (grp_life.fresh)", "grp_life", "fresh", "sec-40-grp-life-fresh"),
            DiffChange("SEC-50", DiffCategory.ADDED, "SEC-50 — Gate extra (grp_grow.gate_extra)", "grp_grow", "gate_extra", "sec-50-grp-grow-gate-extra"),
            DiffChange("SEC-28", DiffCategory.LOGIC_CHANGED, "SEC-28 — Sign-off (grp_signoff.signed_off)", "grp_signoff", "signed_off", "sec-28-grp-signoff-signed-off"),
            DiffChange("SEC-70", DiffCategory.LOGIC_CHANGED, "SEC-70 — Single rule", "grp_single", "only", "sec-70"),
            DiffChange(null, DiffCategory.LOGIC_CHANGED, "grp_other.plain_changed", "grp_other", "plain_changed", "grp-other-plain-changed"),
            DiffChange("SEC-28", DiffCategory.DOCS_CHANGED, "SEC-28 — Signature is valid (grp_signoff.valid_signature)", "grp_signoff", "valid_signature", null),
        )
    }

    @Test
    @DisplayName("reference data that differs raises the control to LOGIC_CHANGED, and the change says so")
    fun dataRaisedChange() {
        assertThat(dataReport.changes).hasSize(1)
        assertThat(dataReport.changes.single().category).isEqualTo(DiffCategory.LOGIC_CHANGED)
        assertThat(dataReport.changes.single().controlId).isEqualTo("BANK-LIM-001")
        assertThat(dataReport.changes.single().label).startsWith("BANK-LIM-001")
    }

    @Test
    @DisplayName("identical sets change nothing: changes is empty, while the opt-in entries still lists every control as UNCHANGED")
    @OptIn(ExplicoStructuralModel::class)
    fun nothingChanged() {
        assertThat(identical.changes).isEmpty()
        assertThat(identical.entries).isNotEmpty()
        assertThat(identical.entries.all { it.category == DiffCategory.UNCHANGED }).isTrue()
        assertThat(jsonChanges(identical)).isEmpty()
    }

    @Test
    @DisplayName("a plain diff of two sets built by hand has no changes")
    fun handBuiltSets() {
        val hand = PolicySet(emptyList())

        assertThat(Explico.diff(hand, hand, DiffOptions()).changes).isEmpty()
    }

    @Test
    @DisplayName("two diffs of the same inputs give the same changes, in the same order")
    fun deterministic() {
        assertThat(allAgain.changes).isEqualTo(all.changes).isNotEmpty()
    }
}
