/**
 * Spec §14.54: a rule's `custom.actions` / `annotations` / `timing` reach [io.explico.model.RuleMetadata.projection] next to the verdict, and a
 * wrongly-shaped one is ignored with exactly ONE stderr warning naming its key and the rule. Hand-built `opa inspect` entry -- no opa needed.
 */
package io.explico.parse

import io.explico.model.ActionsProjection
import io.explico.model.AnnotationsProjection
import io.explico.model.DeclaredProjection
import io.explico.model.TimingProjection
import io.explico.model.VerdictProjection
import io.explico.opa.OpaInspectResult
import io.explico.opa.OpaModule
import io.explico.opa.opaJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ProjectionMetadataMappingTest {

    private fun mapMetadata(custom: String): Pair<io.explico.model.RuleMetadata, String> {
        val module = opaJson.decodeFromString(
            OpaModule.serializer(),
            """{"package":{"path":[{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                "rules":[{"head":{"name":"decision","ref":[{"type":"var","value":"decision"}],"value":{"type":"boolean","value":true}},"location":{"row":3},"body":[]}]}""",
        )
        val inspect = opaJson.decodeFromString(
            OpaInspectResult.serializer(),
            """{"annotations":[{"annotations":{"scope":"document","title":"T","custom":$custom},"location":{"row":3,"file":"scratch.rego"},
                "path":[{"type":"var","value":"data"},{"type":"string","value":"scratch"},{"type":"string","value":"decision"}]}]}""",
        )
        val original = System.err
        val buffer = java.io.ByteArrayOutputStream()
        System.setErr(java.io.PrintStream(buffer, true, Charsets.UTF_8))
        try {
            val set = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module)), inspect)
            return set.packages.single().rules.single().metadata!! to buffer.toString(Charsets.UTF_8)
        } finally {
            System.setErr(original)
        }
    }

    private fun map(custom: String): Pair<DeclaredProjection?, String> = mapMetadata(custom).let { it.first.projection to it.second }

    private fun warnings(stderr: String) = stderr.lines().filter { it.isNotBlank() }

    @Test
    @DisplayName("all four declarations are mapped, with no warning")
    fun allFour() {
        val (projection, stderr) = map(
            """{"verdict":{"outcome":"ok","reason":"why"},"actions":{"at":"actions","label":"action.predicate"},"annotations":{"at":"annotations"},"timing":{"dueBy":"dueBy","startBefore":"startBefore"}}""",
        )

        assertThat(projection).isEqualTo(
            DeclaredProjection(
                VerdictProjection("ok", "why"), ActionsProjection("actions", "action.predicate"), AnnotationsProjection("annotations"), TimingProjection("dueBy", "startBefore"),
            ),
        )
        assertThat(stderr).isEmpty()
    }

    @Test
    @DisplayName("each is independent: declaring only one leaves the others null")
    fun independent() {
        assertThat(map("""{"timing":{"dueBy":"dueBy"}}""").first).isEqualTo(DeclaredProjection(timing = TimingProjection("dueBy", null)))
        assertThat(map("""{"actions":{"at":"a","label":"l"}}""").first).isEqualTo(DeclaredProjection(actions = ActionsProjection("a", "l")))
    }

    @Test
    @DisplayName("CHALLENGE: nothing valid declared -> the projection is NULL, not an all-null object (existing JSON and hashes depend on that)")
    fun nothingValidIsNull() {
        assertThat(map("""{"control-id":"X"}""").first).isNull()
        assertThat(map("""{"actions":"yes","timing":{}}""").first).isNull()
    }

    @Test
    @DisplayName("a malformed declaration is ignored with ONE warning naming its key, the rule and the problem")
    fun malformedIsWarnedAboutOnce() {
        val (projection, stderr) = map("""{"actions":{"at":"a"}}""")

        assertThat(projection).isNull()
        assertThat(warnings(stderr)).hasSize(1)
        assertThat(warnings(stderr).single()).startsWith("explico: warning:").contains("custom.actions").contains("scratch.decision").contains("actions.label")
    }

    @Test
    @DisplayName("each of the three keys warns under its own name")
    fun eachKeyWarnsUnderItsOwnName() {
        assertThat(warnings(map("""{"annotations":"x"}""").second).single()).contains("custom.annotations")
        assertThat(warnings(map("""{"timing":{}}""").second).single()).contains("custom.timing")
    }

    @Test
    @DisplayName("CHALLENGE: two malformed declarations on one rule -> exactly two warnings (one per key, never duplicated), in a fixed order; a valid third is kept")
    fun twoMalformedOnOneRule() {
        val (projection, stderr) = map("""{"timing":{}, "actions":"yes", "annotations":{"at":"status"}}""")

        assertThat(projection).isEqualTo(DeclaredProjection(annotations = AnnotationsProjection("status")))
        val lines = warnings(stderr)
        assertThat(lines).hasSize(2)
        assertThat(lines[0]).contains("custom.actions")
        assertThat(lines[1]).contains("custom.timing")
    }

    @Test
    @DisplayName("CHALLENGE: a malformed scalar is not shown as a custom field either (reserved keys)")
    fun malformedScalarsAreNotCustomFields() {
        val (metadata, stderr) = mapMetadata("""{"actions":"yes","annotations":"x","timing":"soon","owner":"p"}""")

        assertThat(metadata.customFields).containsOnlyKeys("owner")
        assertThat(warnings(stderr)).hasSize(3)
    }
}
