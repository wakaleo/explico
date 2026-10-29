/**
 * Spec §14.54: reading a rule's declared `actions`, `annotations` and `timing` from `custom:` METADATA, the same lenient way as `verdict`
 * (§14.53). Decoding only -- no opa needed; the end-to-end behaviour is pinned by RealisticDeclaredProjectionsAcceptanceIT.
 *
 * Every value that names a field is a field PATH (non-empty, dot-separated, no empty segment). A wrongly-shaped declaration yields no
 * projection and a `problem` for the caller to warn about once; an absent one yields neither.
 */
package io.explico.opa

import io.explico.model.ActionsProjection
import io.explico.model.AnnotationsProjection
import io.explico.model.TimingProjection
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class OpaProjectionDeclarationTest {

    private fun custom(json: String): OpaCustomAnnotation = opaJson.decodeFromString(OpaCustomAnnotation.serializer(), json)

    @Test
    @DisplayName("nothing declared: no projection and nothing to warn about, for all three")
    fun absent() {
        val c = custom("""{"control-id":"X"}""")
        assertThat(c.actions).isEqualTo(Declaration<ActionsProjection>())
        assertThat(c.annotations).isEqualTo(Declaration<AnnotationsProjection>())
        assertThat(c.timing).isEqualTo(Declaration<TimingProjection>())
    }

    @Test
    @DisplayName("a `custom` that is not an object declares nothing")
    fun nonObjectCustom() {
        val c = custom("\"oops\"")
        assertThat(c.actions.projection).isNull()
        assertThat(c.actions.problem).isNull()
    }

    // ---- actions ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("actions: `at` and `label` are read; dotted paths and unknown extra keys (a `kind`, say) are accepted/ignored")
    fun actionsValid() {
        assertThat(custom("""{"actions":{"at":"actions","label":"action.predicate"}}""").actions.projection).isEqualTo(ActionsProjection("actions", "action.predicate"))
        assertThat(custom("""{"actions":{"at":"result.todo","label":"name","kind":"action._type"}}""").actions.projection).isEqualTo(ActionsProjection("result.todo", "name"))
    }

    @ParameterizedTest(name = "malformed actions: {0}")
    @ValueSource(
        strings = [
            """{"actions":"yes"}""",
            """{"actions":["a"]}""",
            """{"actions":null}""",
            """{"actions":{}}""",
            """{"actions":{"label":"l"}}""",
            """{"actions":{"at":"a"}}""",
            """{"actions":{"at":5,"label":"l"}}""",
            """{"actions":{"at":["a"],"label":"l"}}""",
            """{"actions":{"at":"a","label":true}}""",
            """{"actions":{"at":"","label":"l"}}""",
            """{"actions":{"at":"a","label":""}}""",
            """{"actions":{"at":"a..b","label":"l"}}""",
            """{"actions":{"at":"a","label":".l"}}""",
        ],
    )
    @DisplayName("a wrongly-shaped actions declaration yields no projection and a problem")
    fun actionsMalformed(json: String) {
        val d = custom(json).actions
        assertThat(d.projection).isNull()
        assertThat(d.problem).isNotNull().isNotBlank()
    }

    @Test
    @DisplayName("the actions problem names what is wrong")
    fun actionsProblemIsSpecific() {
        assertThat(custom("""{"actions":"yes"}""").actions.problem).contains("mapping")
        assertThat(custom("""{"actions":{"label":"l"}}""").actions.problem).contains("actions.at")
        assertThat(custom("""{"actions":{"at":"a"}}""").actions.problem).contains("actions.label")
    }

    // ---- annotations -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("annotations: `at` is read")
    fun annotationsValid() {
        assertThat(custom("""{"annotations":{"at":"annotations"}}""").annotations.projection).isEqualTo(AnnotationsProjection("annotations"))
        assertThat(custom("""{"annotations":{"at":"meta.status","note":"x"}}""").annotations.projection).isEqualTo(AnnotationsProjection("meta.status"))
    }

    @ParameterizedTest(name = "malformed annotations: {0}")
    @ValueSource(
        strings = [
            """{"annotations":"status"}""",
            """{"annotations":["status"]}""",
            """{"annotations":null}""",
            """{"annotations":{}}""",
            """{"annotations":{"at":5}}""",
            """{"annotations":{"at":["a"]}}""",
            """{"annotations":{"at":""}}""",
            """{"annotations":{"at":"a..b"}}""",
        ],
    )
    @DisplayName("a wrongly-shaped annotations declaration yields no projection and a problem")
    fun annotationsMalformed(json: String) {
        val d = custom(json).annotations
        assertThat(d.projection).isNull()
        assertThat(d.problem).isNotNull().isNotBlank()
    }

    // ---- timing ----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("timing: either field alone, or both, even naming the same field twice")
    fun timingValid() {
        assertThat(custom("""{"timing":{"dueBy":"dueBy","startBefore":"startBefore"}}""").timing.projection).isEqualTo(TimingProjection("dueBy", "startBefore"))
        assertThat(custom("""{"timing":{"dueBy":"dueBy"}}""").timing.projection).isEqualTo(TimingProjection("dueBy", null))
        assertThat(custom("""{"timing":{"startBefore":"t.start"}}""").timing.projection).isEqualTo(TimingProjection(null, "t.start"))
        assertThat(custom("""{"timing":{"dueBy":"when","startBefore":"when"}}""").timing.projection).isEqualTo(TimingProjection("when", "when"))
    }

    @ParameterizedTest(name = "malformed timing: {0}")
    @ValueSource(
        strings = [
            """{"timing":"soon"}""",
            """{"timing":["dueBy"]}""",
            """{"timing":null}""",
            """{"timing":{}}""",
            """{"timing":{"other":"x"}}""",
            """{"timing":{"dueBy":5}}""",
            """{"timing":{"dueBy":null}}""",
            """{"timing":{"dueBy":""}}""",
            """{"timing":{"startBefore":["x"]}}""",
            """{"timing":{"dueBy":"a..b"}}""",
            """{"timing":{"dueBy":"ok","startBefore":5}}""",
        ],
    )
    @DisplayName("a wrongly-shaped timing declaration yields no projection and a problem -- even when the other field is fine (no half a declaration)")
    fun timingMalformed(json: String) {
        val d = custom(json).timing
        assertThat(d.projection).isNull()
        assertThat(d.problem).isNotNull().isNotBlank()
    }

    @Test
    @DisplayName("the timing problem names what is wrong")
    fun timingProblemIsSpecific() {
        assertThat(custom("""{"timing":{}}""").timing.problem).contains("dueBy").contains("startBefore")
        assertThat(custom("""{"timing":{"dueBy":5}}""").timing.problem).contains("timing.dueBy")
        assertThat(custom("""{"timing":{"startBefore":5}}""").timing.problem).contains("timing.startBefore")
    }

    // ---- reserved keys ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("CHALLENGE: actions, annotations and timing are reserved keys -- even malformed scalar ones never leak into the 'Custom:' line")
    fun reservedKeys() {
        val c = custom("""{"actions":"yes","annotations":"x","timing":"soon","verdict":"v","owner":"platform"}""")
        assertThat(c.customFields).containsOnlyKeys("owner")
    }
}
