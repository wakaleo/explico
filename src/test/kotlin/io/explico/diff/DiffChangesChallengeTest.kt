/**
 * CHALLENGE cases for `DiffReport.changes` (spec §14.80 step 4) beyond the acceptance class: the JSON summary still counts the controls `changes` leaves out, a control-id-preserving rename is
 * absent, the report keeps its trailing-field shape for Java and for `copy`, and the projection is one helper (`ChangedSet`) for both the JSON and the facade. The real-diff cases need opa.
 */
package io.explico.diff

import io.explico.DiffOptions
import io.explico.DiffReport
import io.explico.Explico
import io.explico.opa.OpaRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path

class DiffChangesChallengeTest {

    private val diffAll = Path.of("src/test/resources/diff/diff-all-categories")

    @Test
    @DisplayName("a diff with nothing changed has no changes but its JSON summary still counts every unchanged control")
    fun summaryStillCountsTheUnchanged() {
        assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
        val set = Explico.load(diffAll.resolve("old"))

        val report = Explico.diff(set, set, DiffOptions())

        assertThat(report.changes).isEmpty()
        val summary = Json.parseToJsonElement(report.json).jsonObject.getValue("summary").jsonObject
        assertThat(summary.getValue("UNCHANGED").jsonPrimitive.int).isEqualTo(report.entries.size).isGreaterThan(0)
        assertThat(summary.getValue("REMOVED").jsonPrimitive.int).isZero()
    }

    @Test
    @DisplayName("a control whose rule was renamed under the same control id is UNCHANGED: it is in entries, not in changes")
    fun renameIsNotAChange() {
        assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")

        val report = Explico.diff(Explico.load(diffAll.resolve("old")), Explico.load(diffAll.resolve("new")), DiffOptions())

        assertThat(report.entries.single { it.controlId == "REL-002" }.category).isEqualTo(DiffCategory.UNCHANGED)
        assertThat(report.changes.map { it.controlId }).doesNotContain("REL-002")
        assertThat(report.changes.size + report.entries.count { it.category == DiffCategory.UNCHANGED }).isEqualTo(report.entries.size)
    }

    @Test
    @DisplayName("DiffReport keeps its short constructor for Java, defaults changes to empty, and equality and copy include the new trailing field")
    fun trailingFieldShape() {
        val change = DiffChange("A-1", DiffCategory.ADDED, "A-1 — T", "p", "r", "a-1")
        val plain = DiffReport(emptyList(), "# report")

        assertThat(DiffReport::class.java.getConstructor(List::class.java, String::class.java)).isNotNull()
        assertThat(plain.changes).isEmpty()
        assertThat(plain.copy(changes = listOf(change)).changes).containsExactly(change)
        assertThat(plain.copy(changes = listOf(change))).isNotEqualTo(plain)
        assertThat(plain.copy(changes = emptyList())).isEqualTo(plain)
    }

    @Test
    @DisplayName("the JSON and the facade read ONE projection: DiffReport.changes is ChangedSet's list for the same entries")
    @OptIn(io.explico.ExplicoStructuralModel::class)
    fun oneProjection() {
        assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
        val report = Explico.diff(Explico.load(diffAll.resolve("old")), Explico.load(diffAll.resolve("new")), DiffOptions())

        assertThat(ChangedSet.of(report.entries).changes).isEqualTo(report.changes)
    }
}
