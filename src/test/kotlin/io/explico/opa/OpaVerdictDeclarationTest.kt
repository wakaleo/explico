/**
 * Spec §14.53: reading a rule's declared verdict, `custom.verdict: {outcome, reason}`. Decoding only -- no opa needed; the end-to-end
 * behaviour is pinned by RealisticVerdictProjectionAcceptanceIT.
 *
 * `outcome` (required) names the boolean field that is the verdict, `reason` (optional) the string field that explains it; both are
 * field PATHS (`a.b` walks nested objects). A wrongly-shaped declaration is not fatal and not guessed at: it yields no projection and a
 * `problem` text the caller turns into one stderr warning.
 */
package io.explico.opa

import io.explico.model.VerdictProjection
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class OpaVerdictDeclarationTest {

    private fun verdict(customJson: String): VerdictDeclaration =
        opaJson.decodeFromString(OpaCustomAnnotation.serializer(), customJson).verdict

    @Test
    @DisplayName("no `verdict` key: nothing declared, nothing to warn about")
    fun absent() {
        assertThat(verdict("""{"control-id":"X"}""")).isEqualTo(VerdictDeclaration(null, null))
    }

    @Test
    @DisplayName("outcome and reason are both read")
    fun outcomeAndReason() {
        assertThat(verdict("""{"verdict":{"outcome":"compliant","reason":"commentary"}}""").projection)
            .isEqualTo(VerdictProjection("compliant", "commentary"))
    }

    @Test
    @DisplayName("the reason is optional")
    fun outcomeOnly() {
        val declaration = verdict("""{"verdict":{"outcome":"compliant"}}""")
        assertThat(declaration.projection).isEqualTo(VerdictProjection("compliant", null))
        assertThat(declaration.problem).isNull()
    }

    @Test
    @DisplayName("a `custom` that is not an object declares nothing (same leniency as control-id)")
    fun nonObjectCustom() {
        val declaration = opaJson.decodeFromString(OpaCustomAnnotation.serializer(), "\"oops\"").verdict
        assertThat(declaration).isEqualTo(VerdictDeclaration(null, null))
    }

    @Test
    @DisplayName("CHALLENGE: dotted paths and unicode names are accepted as written")
    fun dottedAndUnicodeNames() {
        assertThat(verdict("""{"verdict":{"outcome":"result.allowed","reason":"detail.übereinstimmung"}}""").projection)
            .isEqualTo(VerdictProjection("result.allowed", "detail.übereinstimmung"))
    }

    @Test
    @DisplayName("CHALLENGE: outcome and reason naming the same field is accepted; every example then falls back at render time")
    fun sameFieldForBoth() {
        assertThat(verdict("""{"verdict":{"outcome":"ok","reason":"ok"}}""").projection).isEqualTo(VerdictProjection("ok", "ok"))
    }

    @Test
    @DisplayName("CHALLENGE: unknown extra keys inside `verdict` are ignored, like unknown keys elsewhere in METADATA")
    fun extraKeysIgnored() {
        assertThat(verdict("""{"verdict":{"outcome":"ok","note":"hello"}}""").projection).isEqualTo(VerdictProjection("ok", null))
    }

    @ParameterizedTest(name = "malformed: {0}")
    @ValueSource(
        strings = [
            """{"verdict":"compliant"}""",
            """{"verdict":["compliant"]}""",
            """{"verdict":null}""",
            """{"verdict":{}}""",
            """{"verdict":{"reason":"commentary"}}""",
            """{"verdict":{"outcome":true}}""",
            """{"verdict":{"outcome":["a"]}}""",
            """{"verdict":{"outcome":""}}""",
            """{"verdict":{"outcome":"ok","reason":["a"]}}""",
            """{"verdict":{"outcome":"ok","reason":3}}""",
            """{"verdict":{"outcome":"ok","reason":null}}""",
            """{"verdict":{"outcome":"ok","reason":""}}""",
            """{"verdict":{"outcome":"a..b"}}""",
            """{"verdict":{"outcome":".a"}}""",
            """{"verdict":{"outcome":"a."}}""",
            """{"verdict":{"outcome":"ok","reason":"a..b"}}""",
        ],
    )
    @DisplayName("a wrongly-shaped declaration yields NO projection and a problem to warn about")
    fun malformed(customJson: String) {
        val declaration = verdict(customJson)
        assertThat(declaration.projection).isNull()
        assertThat(declaration.problem).isNotNull().isNotBlank()
    }

    @Test
    @DisplayName("the problem says what is wrong, so the one warning is actionable")
    fun problemIsSpecific() {
        assertThat(verdict("""{"verdict":"compliant"}""").problem).contains("mapping")
        assertThat(verdict("""{"verdict":{"reason":"r"}}""").problem).contains("outcome")
        assertThat(verdict("""{"verdict":{"outcome":"ok","reason":3}}""").problem).contains("reason")
    }

    @Test
    @DisplayName("CHALLENGE: `verdict` is a reserved key -- even a malformed scalar one never leaks into the card's 'Custom:' passthrough line")
    fun verdictIsNeverACustomField() {
        val c = opaJson.decodeFromString(OpaCustomAnnotation.serializer(), """{"verdict":"compliant","owner":"platform"}""")
        assertThat(c.customFields).containsOnlyKeys("owner")
    }
}
