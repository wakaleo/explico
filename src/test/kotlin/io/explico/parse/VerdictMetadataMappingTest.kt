/**
 * Spec §14.53: a rule's `custom.verdict` reaches [io.explico.model.RuleMetadata.projection], and a wrongly-shaped one is ignored with exactly
 * ONE stderr warning naming `custom.verdict` and the rule. Driven by a hand-built `opa inspect` entry -- no opa needed.
 */
package io.explico.parse

import io.explico.model.DeclaredProjection
import io.explico.model.PolicySet
import io.explico.model.RuleMetadata
import io.explico.model.VerdictProjection
import io.explico.opa.OpaInspectResult
import io.explico.opa.OpaModule
import io.explico.opa.opaJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class VerdictMetadataMappingTest {

    private fun rule(name: String, row: Int) =
        """{"head":{"name":"$name","ref":[{"type":"var","value":"$name"}],"value":{"type":"boolean","value":true}},"location":{"row":$row},"body":[]}"""

    private fun entry(rule: String, row: Int, custom: String, scope: String = "document") =
        """{"annotations":{"scope":"$scope","title":"T","custom":$custom},"location":{"row":$row,"file":"scratch.rego"},
            "path":[{"type":"var","value":"data"},{"type":"string","value":"scratch"},{"type":"string","value":"$rule"}]}"""

    /** Maps a package with the given rules, each annotated by the given `custom` JSON; returns the set and everything written to stderr. */
    private fun map(vararg rulesAndCustom: Triple<String, Int, String>): Pair<PolicySet, String> {
        val module = opaJson.decodeFromString(
            OpaModule.serializer(),
            """{"package":{"path":[{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                "rules":[${rulesAndCustom.joinToString(",") { rule(it.first, it.second) }}]}""",
        )
        val inspect = opaJson.decodeFromString(
            OpaInspectResult.serializer(),
            """{"annotations":[${rulesAndCustom.joinToString(",") { entry(it.first, it.second, it.third) }}]}""",
        )
        val original = System.err
        val buffer = java.io.ByteArrayOutputStream()
        System.setErr(java.io.PrintStream(buffer, true, Charsets.UTF_8))
        try {
            return AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module)), inspect) to buffer.toString(Charsets.UTF_8)
        } finally {
            System.setErr(original)
        }
    }

    private fun metadataOf(set: PolicySet, name: String): RuleMetadata = set.packages.single().rules.single { it.name == name }.metadata!!

    @Test
    @DisplayName("a valid declaration becomes the rule's projection, with no warning")
    fun validDeclarationIsMapped() {
        val (set, stderr) = map(Triple("decision", 3, """{"verdict":{"outcome":"ok","reason":"why"}}"""))

        assertThat(metadataOf(set, "decision").projection).isEqualTo(DeclaredProjection(VerdictProjection("ok", "why")))
        assertThat(stderr).isEmpty()
    }

    @Test
    @DisplayName("no declaration: no projection, no warning")
    fun nothingDeclared() {
        val (set, stderr) = map(Triple("decision", 3, """{"control-id":"X"}"""))

        assertThat(metadataOf(set, "decision").projection).isNull()
        assertThat(stderr).isEmpty()
    }

    @Test
    @DisplayName("a malformed declaration is ignored with exactly one warning naming custom.verdict, the rule and what is wrong")
    fun malformedIsWarnedAboutOnce() {
        val (set, stderr) = map(Triple("decision", 3, """{"verdict":"compliant"}"""))

        assertThat(metadataOf(set, "decision").projection).isNull()
        val warnings = stderr.lines().filter { it.isNotBlank() }
        assertThat(warnings).hasSize(1)
        assertThat(warnings.single()).startsWith("explico: warning:").contains("custom.verdict").contains("scratch.decision").contains("mapping")
    }

    @Test
    @DisplayName("each annotated rule is judged on its own: one good, one bad -> one projection, one warning")
    fun rulesAreIndependent() {
        val (set, stderr) = map(
            Triple("good", 3, """{"verdict":{"outcome":"ok"}}"""),
            Triple("bad", 9, """{"verdict":{"reason":"why"}}"""),
        )

        assertThat(metadataOf(set, "good").projection).isEqualTo(DeclaredProjection(VerdictProjection("ok")))
        assertThat(metadataOf(set, "bad").projection).isNull()
        assertThat(stderr.lines().filter { it.isNotBlank() }).hasSize(1).allMatch { it.contains("scratch.bad") }
    }

    @Test
    @DisplayName("CHALLENGE: a malformed scalar `verdict` is not shown as a custom field either")
    fun malformedScalarIsNotACustomField() {
        val (set, _) = map(Triple("decision", 3, """{"verdict":"compliant","owner":"platform"}"""))

        assertThat(metadataOf(set, "decision").customFields).containsOnlyKeys("owner")
    }
}
