/**
 * Spec §14.53: projecting a rule's evaluated OBJECT value through its declared `custom.verdict`. Pure -- hand-built JSON, no opa.
 *
 * Strict by design: the outcome must be a real JSON boolean and the reason a real JSON string. Anything else returns null and the caller
 * keeps that one example as raw JSON -- a half-projection or a coerced value ("yes" read as true) would be a guess.
 */
package io.explico.render

import io.explico.model.VerdictProjection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class VerdictProjectorTest {

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
    private val declared = VerdictProjection("ok", "why")

    @Test
    @DisplayName("a true outcome reads as the field's own name, with the reason")
    fun trueOutcome() {
        assertThat(VerdictProjector.project(declared, json("""{"ok":true,"why":"fine"}"""))).isEqualTo(ProjectedVerdict(true, "ok", "fine"))
    }

    @Test
    @DisplayName("a false outcome reads 'not' plus the field's own name -- no vocabulary invented")
    fun falseOutcome() {
        assertThat(VerdictProjector.project(declared, json("""{"ok":false,"why":"nope"}"""))).isEqualTo(ProjectedVerdict(false, "not ok", "nope"))
    }

    @Test
    @DisplayName("with no reason declared the reason is null and extra fields are ignored")
    fun noReasonDeclared() {
        assertThat(VerdictProjector.project(VerdictProjection("ok"), json("""{"ok":true,"why":"fine","x":1}"""))).isEqualTo(ProjectedVerdict(true, "ok", null))
    }

    @Test
    @DisplayName("nothing declared -> nothing projected")
    fun nothingDeclared() {
        assertThat(VerdictProjector.project(null, json("""{"ok":true,"why":"fine"}"""))).isNull()
    }

    @ParameterizedTest(name = "outcome that cannot be used: {0}")
    @ValueSource(strings = ["""{"why":"w"}""", """{"ok":"true","why":"w"}""", """{"ok":"yes","why":"w"}""", """{"ok":1,"why":"w"}""", """{"ok":null,"why":"w"}""", """{"ok":[true],"why":"w"}""", """{"ok":{"a":true},"why":"w"}"""])
    @DisplayName("an outcome that is missing or not a real boolean keeps the example raw (a string 'true' is NOT read as true)")
    fun unusableOutcome(value: String) {
        assertThat(VerdictProjector.project(declared, json(value))).isNull()
    }

    @ParameterizedTest(name = "reason that cannot be used: {0}")
    @ValueSource(strings = ["""{"ok":true}""", """{"ok":true,"why":42}""", """{"ok":true,"why":null}""", """{"ok":true,"why":false}""", """{"ok":true,"why":["a"]}""", """{"ok":true,"why":{"a":"b"}}"""])
    @DisplayName("a declared reason that is missing or not a string keeps the example raw: half a projection would be a guess")
    fun unusableReason(value: String) {
        assertThat(VerdictProjector.project(declared, json(value))).isNull()
    }

    @ParameterizedTest(name = "value that is not an object: {0}")
    @ValueSource(strings = ["\"just a string\"", "true", "42", "null", "[true]", "[{\"ok\":true,\"why\":\"w\"}]"])
    @DisplayName("a value that is not an object at all stays raw (an array of objects is NOT projected element by element)")
    fun notAnObject(value: String) {
        assertThat(VerdictProjector.project(declared, json(value))).isNull()
    }

    @Test
    @DisplayName("a missing value (the rule was undefined for this input) projects nothing")
    fun noValue() {
        assertThat(VerdictProjector.project(declared, null)).isNull()
    }

    @Test
    @DisplayName("a dotted field path walks nested objects; the declared path is the word shown")
    fun dottedPath() {
        val projected = VerdictProjector.project(VerdictProjection("result.allowed", "result.detail.msg"), json("""{"result":{"allowed":false,"detail":{"msg":"blocked"}}}"""))
        assertThat(projected).isEqualTo(ProjectedVerdict(false, "not result.allowed", "blocked"))
    }

    @Test
    @DisplayName("CHALLENGE: a path through something that is not an object (a string, an array) is absent -> raw")
    fun pathThroughANonObject() {
        assertThat(VerdictProjector.project(VerdictProjection("result.allowed"), json("""{"result":"allowed"}"""))).isNull()
        assertThat(VerdictProjector.project(VerdictProjection("result.allowed"), json("""{"result":[{"allowed":true}]}"""))).isNull()
    }

    @Test
    @DisplayName("CHALLENGE: a dot in a path is ALWAYS a separator, so a literal key 'a.b' is not found -- stated limitation, never a silent mis-read")
    fun literalDottedKeyIsNotFound() {
        assertThat(VerdictProjector.project(VerdictProjection("a.b"), json("""{"a.b":true}"""))).isNull()
    }

    @Test
    @DisplayName("CHALLENGE: unicode field names and values are carried exactly")
    fun unicode() {
        assertThat(VerdictProjector.project(VerdictProjection("übereinstimmung", "grund"), json("""{"übereinstimmung":true,"grund":"✓ erlaubt – ok"}""")))
            .isEqualTo(ProjectedVerdict(true, "übereinstimmung", "✓ erlaubt – ok"))
    }

    @Test
    @DisplayName("CHALLENGE: outcome and reason naming the same field can never project (a boolean is not a string)")
    fun sameFieldForBoth() {
        assertThat(VerdictProjector.project(VerdictProjection("ok", "ok"), json("""{"ok":true}"""))).isNull()
    }

    @Test
    @DisplayName("CHALLENGE: an empty-string reason is still a string and is shown as given")
    fun emptyReason() {
        assertThat(VerdictProjector.project(declared, json("""{"ok":true,"why":""}"""))).isEqualTo(ProjectedVerdict(true, "ok", ""))
    }
}
