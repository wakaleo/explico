/**
 * Spec §14.54: projecting a rule's evaluated OBJECT value through its declared `actions`, `annotations` and `timing`, and combining them with
 * the verdict into one per-example result. Pure -- hand-built JSON, no opa.
 *
 * STRICT, like the verdict: a projection that the value does not fully support is null (the example simply lacks it), never a partial list and
 * never a coerced value. Empty strings are not usable labels / statuses / instants (they would render as nothing).
 */
package io.explico.render

import io.explico.model.ActionsProjection
import io.explico.model.AnnotationsProjection
import io.explico.model.DeclaredProjection
import io.explico.model.TimingProjection
import io.explico.model.VerdictProjection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ExampleProjectionsTest {

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    @Nested
    inner class Remediation {
        private val declared = ActionsProjection("actions", "action.predicate")
        private fun labels(value: String, projection: ActionsProjection? = declared) = ExampleProjections.remediation(projection, json(value))

        @Test
        @DisplayName("every element's label, in array order")
        fun inOrder() {
            assertThat(labels("""{"actions":[{"action":{"predicate":"b"}},{"action":{"predicate":"a"}}]}""")).containsExactly("b", "a")
        }

        @Test
        @DisplayName("an empty array is an EMPTY list (offered nothing), not null")
        fun emptyArray() {
            assertThat(labels("""{"actions":[]}""")).isEmpty()
        }

        @Test
        @DisplayName("dotted `at` and label paths walk nested objects")
        fun dotted() {
            assertThat(labels("""{"result":{"todo":[{"x":{"name":"fix"}}]}}""", ActionsProjection("result.todo", "x.name"))).containsExactly("fix")
        }

        @ParameterizedTest(name = "unusable: {0}")
        @ValueSource(
            strings = [
                """{}""",
                """{"actions":null}""",
                """{"actions":"none"}""",
                """{"actions":{"action":{"predicate":"p"}}}""",
                """{"actions":5}""",
                """{"actions":[{"action":{}}]}""",
                """{"actions":[{"action":{"predicate":7}}]}""",
                """{"actions":[{"action":{"predicate":true}}]}""",
                """{"actions":[{"action":{"predicate":null}}]}""",
                """{"actions":[{"action":{"predicate":""}}]}""",
                """{"actions":[{"action":{"predicate":"ok"}},{"action":{}}]}""",
                """{"actions":[{"action":{"predicate":"ok"}},null]}""",
                """{"actions":[{"action":{"predicate":"ok"}},"loose string"]}""",
                """{"actions":[[{"action":{"predicate":"ok"}}]]}""",
            ],
        )
        @DisplayName("anything the value does not fully support -> null: absent is not empty, and there is never a partial list")
        fun unusable(value: String) {
            assertThat(labels(value)).isNull()
        }

        @Test
        @DisplayName("not an object, or nothing declared -> null")
        fun notAnObjectOrUndeclared() {
            assertThat(labels("""["a"]""")).isNull()
            assertThat(labels("\"just a string\"")).isNull()
            assertThat(ExampleProjections.remediation(declared, null)).isNull()
            assertThat(ExampleProjections.remediation(null, json("""{"actions":[]}"""))).isNull()
        }

        @Test
        @DisplayName("CHALLENGE: duplicate labels are kept (two actions can share a label), unicode is carried exactly, and a long array keeps its order")
        fun duplicatesUnicodeLong() {
            assertThat(labels("""{"actions":[{"action":{"predicate":"a"}},{"action":{"predicate":"a"}}]}""")).containsExactly("a", "a")
            assertThat(labels("""{"actions":[{"action":{"predicate":"überprüfen ✓"}}]}""")).containsExactly("überprüfen ✓")
            val many = (1..1000).joinToString(",") { """{"action":{"predicate":"p$it"}}""" }
            assertThat(labels("""{"actions":[$many]}""")).hasSize(1000).startsWith("p1", "p2").endsWith("p1000")
        }

        @Test
        @DisplayName("CHALLENGE: a label with backticks or line breaks is carried raw -- making it safe to show is the renderer's job")
        fun awkwardLabelsAreCarriedRaw() {
            assertThat(labels("""{"actions":[{"action":{"predicate":"a`b\nc"}}]}""")).containsExactly("a`b\nc")
        }
    }

    @Nested
    inner class Annotations {
        private val declared = AnnotationsProjection("annotations")
        private fun statuses(value: String) = ExampleProjections.annotations(declared, json(value))

        @Test
        @DisplayName("an array of strings, in order; empty is an empty list")
        fun strings() {
            assertThat(statuses("""{"annotations":["expired","overdue"]}""")).containsExactly("expired", "overdue")
            assertThat(statuses("""{"annotations":[]}""")).isEmpty()
        }

        @ParameterizedTest(name = "unusable: {0}")
        @ValueSource(
            strings = [
                """{}""", """{"annotations":null}""", """{"annotations":"a"}""", """{"annotations":{"a":"b"}}""", """{"annotations":[1]}""",
                """{"annotations":[true]}""", """{"annotations":["a",null]}""", """{"annotations":["a",{"b":1}]}""", """{"annotations":["a",["b"]]}""", """{"annotations":["a",""]}""",
            ],
        )
        @DisplayName("any element that is not a non-empty string, or `at` not an array -> null (a status is a word, never coerced)")
        fun unusable(value: String) {
            assertThat(statuses(value)).isNull()
        }

        @Test
        @DisplayName("CHALLENGE: markdown-special text is carried raw; `at` pointing at a boolean (the verdict outcome, say) is simply not an array")
        fun specialsAndSameFieldAsOutcome() {
            assertThat(statuses("""{"annotations":["a*b","c_d","line\nbreak"]}""")).containsExactly("a*b", "c_d", "line\nbreak")
            assertThat(ExampleProjections.annotations(AnnotationsProjection("compliant"), json("""{"compliant":true}"""))).isNull()
        }
    }

    @Nested
    inner class Timing {
        private fun timing(value: String, projection: TimingProjection) = ExampleProjections.timing(projection, json(value))
        private val both = TimingProjection("dueBy", "startBefore")

        @Test
        @DisplayName("both instants are carried verbatim -- no reformatting, whatever the string looks like")
        fun verbatim() {
            assertThat(timing("""{"dueBy":"2025-07-09 00:00:00 +0000 UTC","startBefore":"soon-ish"}""", both))
                .isEqualTo(ProjectedTiming("2025-07-09 00:00:00 +0000 UTC", "soon-ish"))
        }

        @Test
        @DisplayName("only the DECLARED fields are read, even when the value has the other")
        fun onlyDeclared() {
            assertThat(timing("""{"dueBy":"d","startBefore":"s"}""", TimingProjection("dueBy", null))).isEqualTo(ProjectedTiming("d", null))
            assertThat(timing("""{"dueBy":"d","startBefore":"s"}""", TimingProjection(null, "startBefore"))).isEqualTo(ProjectedTiming(null, "s"))
        }

        @ParameterizedTest(name = "unusable value: {0}")
        @ValueSource(strings = ["""{"dueBy":null,"startBefore":null}""", """{"dueBy":"","startBefore":""}""", """{"dueBy":5,"startBefore":true}""", """{"dueBy":["x"],"startBefore":{"a":"b"}}""", """{}"""])
        @DisplayName("a null, empty-string, non-string or missing instant is omitted; the result is still PRESENT (the value was an object), with nulls")
        fun omitted(value: String) {
            assertThat(timing(value, both)).isEqualTo(ProjectedTiming(null, null))
        }

        @Test
        @DisplayName("one usable and one not: only the usable one")
        fun oneOfTwo() {
            assertThat(timing("""{"dueBy":5,"startBefore":"2025-06-09T00:00:00Z"}""", both)).isEqualTo(ProjectedTiming(null, "2025-06-09T00:00:00Z"))
        }

        @Test
        @DisplayName("CHALLENGE: the same path for both fields gives the same value twice")
        fun samePathForBoth() {
            assertThat(timing("""{"when":"x"}""", TimingProjection("when", "when"))).isEqualTo(ProjectedTiming("x", "x"))
        }

        @Test
        @DisplayName("a value that is not an object, or no value, -> null")
        fun notAnObject() {
            assertThat(timing("""["2025-01-01"]""", both)).isNull()
            assertThat(timing("\"2025-01-01\"", both)).isNull()
            assertThat(ExampleProjections.timing(both, null)).isNull()
            assertThat(ExampleProjections.timing(null, json("""{"dueBy":"d"}"""))).isNull()
        }
    }

    @Nested
    inner class Combined {
        private val fixture = Fixture("f", null, buildJsonObject { })
        private val all = DeclaredProjection(
            VerdictProjection("ok", "why"), ActionsProjection("actions", "p"), AnnotationsProjection("annotations"), TimingProjection("dueBy", null),
        )
        private fun example(value: String?, matched: Boolean = true) = WorkedExample(fixture, matched, emptyList(), emptyList(), value?.let { json(it) })

        @Test
        @DisplayName("all four are computed together from one value")
        fun allFour() {
            val projected = ExampleProjections.of(all, example("""{"ok":false,"why":"nope","actions":[{"p":"fix"}],"annotations":["late"],"dueBy":"d"}"""))

            assertThat(projected.verdict).isEqualTo(ProjectedVerdict(false, "not ok", "nope"))
            assertThat(projected.remediation).containsExactly("fix")
            assertThat(projected.annotations).containsExactly("late")
            assertThat(projected.timing).isEqualTo(ProjectedTiming("d", null))
        }

        @Test
        @DisplayName("independent: a value the actions cannot use does not stop the verdict, status or timing")
        fun independent() {
            val projected = ExampleProjections.of(all, example("""{"ok":true,"why":"fine","actions":"none","annotations":["a"],"dueBy":"d"}"""))

            assertThat(projected.remediation).isNull()
            assertThat(projected.verdict).isNotNull()
            assertThat(projected.annotations).containsExactly("a")
            assertThat(projected.timing).isNotNull()
        }

        @Test
        @DisplayName("an example where the rule did not match, or has no value, carries nothing -- even with a stale value")
        fun notMatched() {
            assertThat(ExampleProjections.of(all, example("""{"ok":true,"why":"w","actions":[],"annotations":[],"dueBy":"d"}""", matched = false))).isEqualTo(ProjectedExample())
            assertThat(ExampleProjections.of(all, example(null))).isEqualTo(ProjectedExample())
        }

        @Test
        @DisplayName("nothing declared -> nothing projected")
        fun nothingDeclared() {
            assertThat(ExampleProjections.of(null, example("""{"ok":true}"""))).isEqualTo(ProjectedExample())
        }
    }
}
