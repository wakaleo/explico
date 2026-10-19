/** `Quality.gapDescriptions` (spec §14.47): one line per documentation gap, worded once by the library. No opa. */
package io.explico.render

import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RuleGroup
import io.explico.model.RuleMetadata
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class QualityGapsTest {

    private fun rule(id: String, title: String = "T", description: String? = "d", frameworks: List<String> = listOf("F")) =
        RuleGroup("r_$id", RuleMetadata(title, description, id, frameworks), null, emptyList())

    private fun set(vararg rules: RuleGroup) = PolicySet(listOf(PolicyPackage("p", rules.toList(), listOf("f.rego"))))

    @Test
    fun orderIsDescriptionThenFrameworkThenSharedTitleThenRequirementsThenUnlisted() {
        val policy = set(rule("C-1", "Shared", description = null), rule("C-2", "Shared", frameworks = emptyList()), rule("C-3", frameworks = listOf("Typo")))
        val gaps = Quality.gapDescriptions(Quality.findingsOf(policy), Quality.coverageOf(policy, listOf("Real")))
        assertThat(gaps).containsExactly(
            "control C-1 has no description",
            "control C-2 names no framework",
            "controls C-1, C-2 share the title \"Shared\"",
            "requirement Real has no control",
            "framework string F is not in the requirements list",
            "framework string Typo is not in the requirements list",
        )
    }

    @Test
    fun withoutARequirementsListOnlyQualityGapsAppear() {
        val policy = set(rule("C-1", description = null))
        assertThat(Quality.gapDescriptions(Quality.findingsOf(policy), null)).containsExactly("control C-1 has no description")
    }

    @Test
    fun aCompleteSetHasNoGaps() {
        val policy = set(rule("C-1"))
        assertThat(Quality.gapDescriptions(Quality.findingsOf(policy), Quality.coverageOf(policy, listOf("F")))).isEmpty()
    }

    @Test
    @DisplayName("CHALLENGE: a title shared by three controls is ONE gap listing all three; two different shared titles are two gaps sorted by title")
    fun sharedTitles() {
        val policy = set(rule("C-1", "B"), rule("C-2", "B"), rule("C-3", "B"), rule("C-4", "A"), rule("C-5", "A"))
        assertThat(Quality.gapDescriptions(Quality.findingsOf(policy), null)).containsExactly(
            "controls C-4, C-5 share the title \"A\"",
            "controls C-1, C-2, C-3 share the title \"B\"",
        )
    }
}
