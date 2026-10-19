/**
 * Spec §14.46: documentation-quality findings and requirement coverage, computed from the domain model. Hand-built domain
 * objects -- no opa; the real index output is pinned by RealisticQualityAndRequirementsAcceptanceIT.
 */
package io.explico.render

import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RuleGroup
import io.explico.model.RuleMetadata
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class QualityTest {

    private fun rule(name: String, id: String?, title: String? = "T", description: String? = "d", frameworks: List<String> = listOf("F"), split: Boolean = false) =
        RuleGroup(name, if (id == null && title == null && description == null && frameworks.isEmpty()) null else RuleMetadata(title, description, id, frameworks), null, emptyList(), isSplitDefaultEntry = split)

    private fun set(vararg rules: RuleGroup, path: String = "p") = PolicySet(listOf(PolicyPackage(path, rules.toList(), listOf("f.rego"))))

    @Nested
    inner class Findings {

        @Test
        fun aCompleteControlHasNoFindings() {
            assertThat(Quality.findingsOf(set(rule("r", "C-1"))).isEmpty()).isTrue()
        }

        @Test
        fun missingDescriptionIsNullOrBlank() {
            val f = Quality.findingsOf(set(rule("a", "C-1", description = null), rule("b", "C-2", description = "   "), rule("c", "C-3")))
            assertThat(f.missingDescription.map { it.label }).containsExactly("C-1", "C-2")
        }

        @Test
        fun noFrameworkMeansAnEmptyList() {
            assertThat(Quality.findingsOf(set(rule("a", "C-1", frameworks = emptyList()))).noFramework.map { it.label }).containsExactly("C-1")
        }

        @Test
        fun duplicateTitlesAreExactAndCaseSensitive() {
            val f = Quality.findingsOf(set(rule("a", "C-1", title = "Same"), rule("b", "C-2", title = "Same"), rule("c", "C-3", title = "same"), rule("d", "C-4", title = "Other")))
            assertThat(f.duplicateTitles.map { it.first to it.second.map { c -> c.label } }).containsExactly("Same" to listOf("C-1", "C-2"))
        }

        @Test
        @DisplayName("CHALLENGE: a null title is never a duplicate of another null title")
        fun nullTitlesDoNotCollide() {
            val f = Quality.findingsOf(set(rule("a", "C-1", title = null), rule("b", "C-2", title = null)))
            assertThat(f.duplicateTitles).isEmpty()
        }

        @Test
        @DisplayName("CHALLENGE: id-less rules, rules with no metadata and split-default entries are never controls")
        fun onlyControlsAreChecked() {
            val f = Quality.findingsOf(set(rule("plain", null, title = null, description = null, frameworks = emptyList()), rule("noid", null, description = null), rule("dflt", "C-9", description = null, split = true)))
            assertThat(f.isEmpty()).isTrue()
        }

        @Test
        fun controlsComeInControlIdOrderAcrossPackages() {
            val policy = PolicySet(
                listOf(
                    PolicyPackage("z.pkg", listOf(rule("a", "C-2", description = null)), listOf("f.rego")),
                    PolicyPackage("a.pkg", listOf(rule("b", "C-1", description = null)), listOf("f.rego")),
                ),
            )
            val refs = Quality.findingsOf(policy).missingDescription
            assertThat(refs.map { it.label }).containsExactly("C-1", "C-2")
            assertThat(refs.map { it.link() }).containsExactly("[C-1](a-pkg.md#c-1)", "[C-2](z-pkg.md#c-2)")
        }

        @Test
        fun aControlWhoseCardClaimedNoAnchorIsLabelledWithoutALink() {
            val ref = Quality.ControlRef("C-1", "p", null)
            assertThat(ref.link()).isEqualTo("C-1")
        }
    }

    @Nested
    inner class Requirements {

        @Test
        fun rowsFollowTheListAndCountCoveredOnes() {
            val c = Quality.coverageOf(set(rule("a", "C-1", frameworks = listOf("X")), rule("b", "C-2", frameworks = listOf("X", "Y"))), listOf("Y", "X", "Z"))
            assertThat(c.items.map { it.requirement }).containsExactly("Y", "X", "Z")
            assertThat(c.items.map { it.controls.map { r -> r.label } }).containsExactly(listOf("C-2"), listOf("C-1", "C-2"), emptyList())
            assertThat(c.total).isEqualTo(3)
            assertThat(c.covered).isEqualTo(2)
        }

        @Test
        fun matchingIsExactAndCaseSensitive() {
            val c = Quality.coverageOf(set(rule("a", "C-1", frameworks = listOf("SOC 2 CC8.1"))), listOf("soc 2 cc8.1", "SOC 2 CC8.1 "))
            assertThat(c.covered).isEqualTo(1) // the trailing-space entry was trimmed by the library to the real string
            assertThat(c.items.map { it.requirement }).containsExactly("soc 2 cc8.1", "SOC 2 CC8.1")
        }

        @Test
        @DisplayName("CHALLENGE: blank entries are dropped and duplicates collapse, keeping the first position")
        fun listIsNormalised() {
            val c = Quality.coverageOf(set(rule("a", "C-1", frameworks = listOf("X"))), listOf("X", "", "  ", "Y", "X"))
            assertThat(c.items.map { it.requirement }).containsExactly("X", "Y")
        }

        @Test
        fun frameworkStringsNotOnTheListAreSortedCaseInsensitively() {
            val c = Quality.coverageOf(set(rule("a", "C-1", frameworks = listOf("beta", "Alpha", "Listed"))), listOf("Listed"))
            assertThat(c.unlisted).containsExactly("Alpha", "beta")
        }

        @Test
        fun anIdLessRuleNamingAFrameworkCountsAndIsLabelledByRuleName() {
            val c = Quality.coverageOf(set(rule("helper", null, frameworks = listOf("X"))), listOf("X"))
            assertThat(c.items.single().controls.map { it.label }).containsExactly("helper")
        }

        @Test
        fun noControlsMeansEveryRequirementIsAGap() {
            val c = Quality.coverageOf(PolicySet(emptyList()), listOf("X"))
            assertThat(c.covered).isEqualTo(0)
            assertThat(c.items.single().controls).isEmpty()
            assertThat(c.unlisted).isEmpty()
        }
    }
}
