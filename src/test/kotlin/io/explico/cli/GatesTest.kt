/** The CI gates' decisions and messages (spec §14.47), without a process or opa. */
package io.explico.cli

import io.explico.diff.DiffCategory
import io.explico.diff.DiffEntry
import io.explico.model.PolicyPackage
import io.explico.model.RuleGroup
import io.explico.model.RuleMetadata
import io.explico.render.CoverageSummary
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class GatesTest {

    @Nested
    inner class FailUnder {

        @Test
        fun compareExactlyNotOnTheFlooredPercent() {
            val c = CoverageSummary(22, 23) // 95.65%, displayed (floored) as 95%
            assertThat(Gates.failUnder(c, 95)).isNull()
            assertThat(Gates.failUnder(c, 96)).isEqualTo("explico: gate failed (--fail-under 96): rendering coverage is 95% (22 of 23 conditions)")
        }

        @Test
        @DisplayName("CHALLENGE: the boundary is inclusive -- exactly the threshold passes -- and zero conditions count as 100%")
        fun boundaries() {
            assertThat(Gates.failUnder(CoverageSummary(19, 20), 95)).isNull() // exactly 95.00%
            assertThat(Gates.failUnder(CoverageSummary(0, 0), 100)).isNull()
            assertThat(Gates.failUnder(CoverageSummary(0, 5), 1)).isNotNull()
            assertThat(Gates.failUnder(CoverageSummary(5, 5), 100)).isNull()
            assertThat(Gates.failUnder(CoverageSummary(1, 3), 0)).isNull()
        }

        @Test
        fun theMessageUsesTheSameFlooredPercentTheCliPrints() {
            assertThat(Gates.failUnder(CoverageSummary(2, 3), 100)).contains("rendering coverage is 66% (2 of 3 conditions)")
        }
    }

    @Nested
    inner class OnGaps {

        @Test
        fun noGapsNoMessage() {
            assertThat(Gates.failOnGaps(emptyList())).isNull()
        }

        @Test
        fun aHeaderWithTheCountThenOneIndentedLinePerGap() {
            assertThat(Gates.failOnGaps(listOf("a", "b"))).isEqualTo("explico: gate failed (--fail-on-gaps): 2 gap(s)\n  - a\n  - b")
        }
    }

    @Nested
    inner class FailOn {

        private fun rule(name: String, id: String?) = RuleGroup(name, id?.let { RuleMetadata("T", "d", it, emptyList()) }, null, emptyList())
        private fun pkg(r: RuleGroup) = PolicyPackage("p.q", listOf(r), listOf("f.rego"))
        private fun entry(category: DiffCategory, id: String?, name: String = "deny"): DiffEntry {
            val r = rule(name, id)
            val p = pkg(r)
            return when (category) {
                DiffCategory.REMOVED -> DiffEntry(category, id, p, r, null, null)
                DiffCategory.ADDED -> DiffEntry(category, id, null, null, p, r)
                else -> DiffEntry(category, id, p, r, p, r)
            }
        }

        @Test
        fun parsesCaseInsensitivelyKeepingTheOrderGivenAndDropsDuplicates() {
            assertThat(Gates.parseFailOn("logic_changed, REMOVED ,logic_changed")).containsExactly(DiffCategory.LOGIC_CHANGED, DiffCategory.REMOVED)
        }

        @Test
        @DisplayName("CHALLENGE: UNCHANGED, an unknown name and an empty list are rejected, naming the valid values")
        fun rejects() {
            for (bad in listOf("UNCHANGED", "bogus", "ADDED,bogus", "", " , ")) {
                val e = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) { Gates.parseFailOn(bad) }
                assertThat(e.message).describedAs(bad).contains("ADDED, REMOVED, LOGIC_CHANGED, DOCS_CHANGED")
            }
        }

        @Test
        fun listsMatchingControlsInReportOrderAndEchoesTheCategoriesAsGiven() {
            val entries = listOf(entry(DiffCategory.LOGIC_CHANGED, "REL-003"), entry(DiffCategory.UNCHANGED, "REL-002"), entry(DiffCategory.REMOVED, "REL-001"))
            val message = Gates.failOn(entries, listOf(DiffCategory.LOGIC_CHANGED, DiffCategory.REMOVED))
            // Report order is REMOVED before LOGIC_CHANGED, whatever order the entries (or the flag) came in.
            assertThat(message).isEqualTo("explico: gate failed (--fail-on LOGIC_CHANGED,REMOVED): 2 control(s): REL-001, REL-003")
        }

        @Test
        fun aRuleWithNoControlIdIsLabelledPackageDotRule() {
            assertThat(Gates.failOn(listOf(entry(DiffCategory.ADDED, null, "helper")), listOf(DiffCategory.ADDED)))
                .isEqualTo("explico: gate failed (--fail-on ADDED): 1 control(s): p.q.helper")
        }

        @Test
        fun nothingMatchingOrNothingToCompareIsNoMessage() {
            assertThat(Gates.failOn(listOf(entry(DiffCategory.UNCHANGED, "C-1")), listOf(DiffCategory.ADDED))).isNull()
            assertThat(Gates.failOn(emptyList(), listOf(DiffCategory.ADDED, DiffCategory.REMOVED))).isNull()
        }
    }
}
