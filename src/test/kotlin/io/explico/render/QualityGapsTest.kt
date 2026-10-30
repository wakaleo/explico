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

    private fun gaps(policy: PolicySet, vararg names: String, examples: Map<String, Map<String, List<WorkedExample>>>? = null) =
        Quality.gapDescriptions(Quality.findingsOf(policy, null, examples), null, RequiredSet.of(names.toList(), examplesSupplied = true))

    @Test
    @DisplayName("spec §14.64: a title is missing when null, empty or only whitespace; it is a gap only when `title` is required, and never a displayed finding")
    fun missingTitle() {
        val policy = set(rule("C-1", title = ""), rule("C-2", title = "   "), rule("C-3", title = "Fine"))
        val findings = Quality.findingsOf(policy)
        assertThat(findings.missingTitle.map { it.label }).containsExactly("C-1", "C-2")
        assertThat(findings.isEmpty()).describedAs("a missing title is not a displayed finding").isTrue()
        assertThat(gaps(policy, "title")).containsExactly("control C-1 has no title", "control C-2 has no title")
        assertThat(gaps(policy, "description")).isEmpty()
    }

    @Test
    @DisplayName("spec §14.64: each required property words its own gap, ordered description, frameworks, title, fixture, verdict, then shared titles")
    fun wordingAndOrder() {
        val none = io.explico.model.RuleBody(emptyList(), null, null, io.explico.model.SourceRef("f.rego", 1), "")
        val fixture = Fixture("f", null, kotlinx.serialization.json.JsonObject(emptyMap()))
        val decision = kotlinx.serialization.json.buildJsonObject { put("compliant", kotlinx.serialization.json.JsonPrimitive(false)) }
        fun withBody(r: RuleGroup) = r.copy(bodies = listOf(none))
        val policy = set(
            withBody(rule("C-1", "Shared", description = null)), withBody(rule("C-2", "Shared", frameworks = emptyList())),
            withBody(rule("C-3", title = "")), withBody(rule("C-4")),
        )
        val examples = mapOf(
            "p" to mapOf(
                "r_C-1" to listOf(WorkedExample(fixture, true, emptyList(), emptyList(), null)),
                "r_C-2" to listOf(WorkedExample(fixture, true, emptyList(), emptyList(), null)),
                "r_C-3" to listOf(WorkedExample(fixture, true, emptyList(), emptyList(), null)),
                "r_C-4" to listOf(WorkedExample(fixture, false, emptyList(), emptyList(), null)),
            ),
        )
        val noDeclared = mapOf("p" to examples.getValue("p") + ("r_C-1" to listOf(WorkedExample(fixture, true, emptyList(), emptyList(), decision))))
        assertThat(gaps(policy, "verdict", "fixture", "title", "frameworks", "description", examples = noDeclared)).containsExactly(
            "control C-1 has no description",
            "control C-2 names no framework",
            "control C-3 has no title",
            "control C-4 has no matching example",
            "control C-1 returns an object but declares no custom.verdict",
            "controls C-1, C-2 share the title \"Shared\"",
        )
    }

    @Test
    @DisplayName("CHALLENGE: a control that is both never matched and an object gets both lines; nothing required means only shared titles and requirement gaps")
    fun bothAndNothing() {
        val none = io.explico.model.RuleBody(emptyList(), null, null, io.explico.model.SourceRef("f.rego", 1), "")
        val policy = set(rule("C-1").copy(bodies = listOf(none)))
        val fixture = Fixture("f", null, kotlinx.serialization.json.JsonObject(emptyMap()))
        val decision = kotlinx.serialization.json.buildJsonObject { put("compliant", kotlinx.serialization.json.JsonPrimitive(false)) }
        val examples = mapOf("p" to mapOf("r_C-1" to listOf(WorkedExample(fixture, false, emptyList(), emptyList(), decision))))
        assertThat(gaps(policy, "fixture", "verdict", examples = examples)).containsExactly(
            "control C-1 has no matching example", "control C-1 returns an object but declares no custom.verdict",
        )
        assertThat(gaps(set(rule("C-1", description = null), rule("C-2", "Same"), rule("C-3", "Same")))).containsExactly("controls C-2, C-3 share the title \"Same\"")
    }
}
