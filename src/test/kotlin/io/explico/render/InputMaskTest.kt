/**
 * Spec §14.61: the input mask and the masked view of an example, on hand-built values (no opa). The real cards and JSON are pinned by
 * RealisticInputMaskingAcceptanceIT.
 */
package io.explico.render

import io.explico.model.Operand
import io.explico.model.PathSegment
import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RuleGroup
import io.explico.model.RuleMetadata
import io.explico.model.DeclaredProjection
import io.explico.opa.OpaCustomAnnotation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path

class InputMaskTest {

    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun masked(paths: List<String>, input: String) = InputMask.of(paths)!!.apply(obj(input))

    @Nested
    inner class Applying {

        @Test
        @DisplayName("a string at a dotted path becomes the marker and is recorded as a secret; everything else is untouched")
        fun dotted() {
            val applied = masked(listOf("a.b"), """{"a":{"b":"secret","c":"keep"},"d":1}""")
            assertThat(applied.input.toString()).isEqualTo("""{"a":{"b":"***","c":"keep"},"d":1}""")
            assertThat(applied.secrets.map { it.text }).containsExactly("secret")
        }

        @Test
        @DisplayName("the marker is ONE instance (so the path-value bullet can print it unquoted) and never an equal-looking user string")
        fun markerIdentity() {
            val applied = masked(listOf("a"), """{"a":"x","b":"***"}""")
            assertThat(applied.input["a"]).isSameAs(InputMask.MASK)
            assertThat(applied.input["b"]).isNotSameAs(InputMask.MASK)
        }

        @Test
        @DisplayName("[] masks the same field of every element; a scalar array and an empty array work; `people[]` alone masks every element")
        fun wildcard() {
            assertThat(masked(listOf("p[].e"), """{"p":[{"e":"x","k":1},{"e":"y"},{"k":2}]}""").input.toString())
                .isEqualTo("""{"p":[{"e":"***","k":1},{"e":"***"},{"k":2}]}""")
            assertThat(masked(listOf("tags[]"), """{"tags":["a","b"]}""").input.toString()).isEqualTo("""{"tags":["***","***"]}""")
            assertThat(masked(listOf("p[].e"), """{"p":[]}""").input.toString()).isEqualTo("""{"p":[]}""")
            assertThat(masked(listOf("m[][]"), """{"m":[["a"],["b","c"]]}""").input.toString()).isEqualTo("""{"m":[["***"],["***","***"]]}""")
        }

        @Test
        @DisplayName("an object or array at the path is hidden whole, and every string and number inside it is a secret")
        fun wholeComposite() {
            val applied = masked(listOf("who"), """{"who":{"name":"Ann","ids":[7,8],"ok":true}}""")
            assertThat(applied.input.toString()).isEqualTo("""{"who":"***"}""")
            assertThat(applied.secrets.map { it.text }).containsExactlyInAnyOrder("Ann", "7", "8")
        }

        @Test
        @DisplayName("a path that is absent, or runs through something that is not what it names, is ignored; a boolean or null at the path stays")
        fun ignored() {
            val input = """{"a":"s","n":null,"t":true,"x":"scalar"}"""
            for (path in listOf("zzz", "a.b", "x[].y", "n", "t", "q[].w")) {
                val applied = masked(listOf(path), input)
                assertThat(applied.input.toString()).describedAs(path).isEqualTo(input)
                assertThat(applied.isActive).describedAs(path).isFalse()
            }
        }

        @Test
        @DisplayName("secrets are distinct and longest first; an empty string is never a secret; no declared paths means no mask")
        fun secrets() {
            val applied = masked(listOf("a", "b", "c"), """{"a":"alice","b":"alice@example.com","c":""}""")
            assertThat(applied.secrets.map { it.text }).containsExactly("alice@example.com", "alice")
            assertThat(InputMask.of(null)).isNull()
            assertThat(InputMask.of(emptyList())).isNull()
        }
    }

    @Nested
    inner class Scrubbing {

        private fun scrub(text: String, vararg values: String) = masked(values.indices.map { "k$it" }, JsonObject(values.withIndex().associate { (i, v) -> "k$i" to (v.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(v)) }).toString()).scrub(text)

        @Test
        @DisplayName("a string secret is hidden wherever it occurs, even inside a word (over-masking is the safe side)")
        fun substring() {
            assertThat(scrub("alice and malice", "alice")).isEqualTo("*** and m***")
            assertThat(scrub("also", "al")).isEqualTo("***so")
        }

        @Test
        @DisplayName("a string secret is hidden in any case (a rule may print it through lower() or upper()), unicode included")
        fun anyCase() {
            assertThat(scrub("ALICE@EXAMPLE.COM and alice@example.com", "Alice@Example.com")).isEqualTo("*** and ***")
            assertThat(scrub("ÅNGSTRÖM", "Ångström")).isEqualTo("***")
        }

        @Test
        @DisplayName("overlapping secrets: the longer one is hidden whole, nothing of it is left")
        fun overlapping() {
            assertThat(scrub("mail alice@example.com now", "alice", "alice@example.com")).isEqualTo("mail *** now")
        }

        @Test
        @DisplayName("a number is hidden only as a whole token: 1 is not 11, 21, a1, 1.5 or 2.1")
        fun numberTokens() {
            assertThat(scrub("1 11 21 a1 1.5 2.1 x_1 (1) 1.", "1")).isEqualTo("*** 11 21 a1 1.5 2.1 x_1 (***) ***.")
            assertThat(scrub("account 987654321: ok", "987654321")).isEqualTo("account ***: ok")
        }

        @Test
        @DisplayName("unicode secrets work")
        fun unicode() {
            assertThat(scrub("Zoë Ångström wrote", "Zoë", "Ångström")).isEqualTo("*** *** wrote")
        }

        @Test
        @DisplayName("a JSON value is scrubbed in strings (also nested) and numbers; keys are left; the JSON TEXT of a secret with a quote or backslash is gone too")
        fun jsonValue() {
            val applied = masked(listOf("a", "n"), """{"a":"he said \"hi\" \\ there","n":42}""")
            val value = obj("""{"msg":"he said \"hi\" \\ there","list":[{"x":"about he said \"hi\" \\ there"},42,43],"a":"key stays"}""")
            val scrubbed = applied.scrub(value).toString()
            assertThat(scrubbed).doesNotContain("hi").doesNotContain("there").contains("43").contains("\"a\":\"key stays\"")
            assertThat(scrubbed).contains("\"list\":[{\"x\":\"about ***\"},\"***\",43]")
        }
    }

    @Nested
    inner class View {

        private val example = WorkedExample(
            Fixture("alice's change", "about alice", obj("""{"who":"alice","other":"bob"}""")),
            matched = true, messages = listOf("alice is blocked", "bob too"), situationLabels = listOf(1, null),
            value = obj("""{"reason":"alice again"}"""),
        )

        @Test
        @DisplayName("name, description, input, messages and value are all scrubbed; the verdict and labels are untouched")
        fun masksEverything() {
            val view = ExampleMasking.view(listOf("who"), "alice's change", example)
            assertThat(view.active).isTrue()
            assertThat(view.name).isEqualTo("***'s change")
            assertThat(view.example.fixture.description).isEqualTo("about ***")
            assertThat(view.example.fixture.input.toString()).isEqualTo("""{"who":"***","other":"bob"}""")
            assertThat(view.example.messages).containsExactly("*** is blocked", "bob too")
            assertThat(view.example.value.toString()).isEqualTo("""{"reason":"*** again"}""")
            assertThat(view.example.matched).isTrue()
            assertThat(view.example.situationLabels).containsExactly(1, null)
        }

        @Test
        @DisplayName("no mask, or a mask that hides nothing in this input: the example itself, not active")
        fun inactive() {
            assertThat(ExampleMasking.view(null, "n", example).example).isSameAs(example)
            assertThat(ExampleMasking.view(listOf("absent"), "n", example).active).isFalse()
        }

        @Test
        @DisplayName("inside an italic span the marker is escaped so it cannot open emphasis -- only when masking applied")
        fun italic() {
            assertThat(ExampleMasking.italic("*** is blocked", true)).isEqualTo("\\*\\*\\* is blocked")
            assertThat(ExampleMasking.italic("a *** b", false)).isEqualTo("a *** b")
        }

        @Test
        @DisplayName("a masked path prints as plain ***, not a quoted string, in the path-value bullet")
        fun pathBullet() {
            val path = Operand.Path(listOf("input", "who").map { PathSegment.Field(it) })
            val view = ExampleMasking.view(listOf("who"), "n", example)
            assertThat(WorkedExamples.resolvePathValue(path, view.example.fixture.input)).isEqualTo("***")
            assertThat(WorkedExamples.resolvePathValue(path, example.fixture.input)).isEqualTo("\"alice\"")
        }
    }

    @Nested
    inner class Declaration {

        private fun mask(json: String) = OpaCustomAnnotation(obj(json)).mask

        @Test
        @DisplayName("valid paths, an empty list and no declaration")
        fun valid() {
            assertThat(mask("""{"mask":["a","a.b","p[].e","m[][]","with space.x"]}""").paths).containsExactly("a", "a.b", "p[].e", "m[][]", "with space.x")
            assertThat(mask("""{"mask":[]}""").paths).isEmpty()
            assertThat(mask("""{}""").paths).isNull()
            assertThat(mask("""{}""").problem).isNull()
        }

        @Test
        @DisplayName("everything else is a problem, never a partial list: a scalar, a mapping, an empty or malformed path, a non-string entry")
        fun problems() {
            for (bad in listOf("\"subjectId\"", "{\"a\":1}", "[\"\"]", "[\"a..b\"]", "[\".a\"]", "[\"a.\"]", "[\"[]\"]", "[\"a[\"]", "[\"a[x]\"]", "[\"[]a\"]", "[\"a.[].b\"]", "[7]", "[\"ok\", null]", "[\"ok\", true]")) {
                val declaration = mask("""{"mask":$bad}""")
                assertThat(declaration.problem).describedAs(bad).isNotNull()
                assertThat(declaration.paths).describedAs(bad).isNull()
            }
        }

        @Test
        @DisplayName("mask is a reserved custom key: never in the 'Custom:' passthrough, valid or not")
        fun reserved() {
            assertThat(OpaCustomAnnotation(obj("""{"mask":["a"],"owner":"x"}""")).customFields).containsOnlyKeys("owner")
            assertThat(OpaCustomAnnotation(obj("""{"mask":"a"}""")).customFields).isEmpty()
        }
    }

    @Nested
    inner class Refusing {

        private fun rule(name: String, projection: DeclaredProjection?) =
            RuleGroup(name, RuleMetadata("t", null, "C-$name", emptyList(), projection = projection), null, emptyList())

        @Test
        @DisplayName("every malformed declaration is listed, sorted, in ONE exception naming custom.mask and each rule -- before any opa work (the directory need not exist)")
        fun refuses() {
            val set = PolicySet(
                listOf(
                    PolicyPackage("z.pkg", listOf(rule("b", DeclaredProjection(maskProblem = "`mask` must be a list of input paths"))), listOf("f.rego")),
                    PolicyPackage("a.pkg", listOf(rule("a", DeclaredProjection(maskProblem = "entry 7 is not a string")), rule("fine", DeclaredProjection(mask = listOf("x")))), listOf("g.rego")),
                ),
            )
            assertThatThrownBy { io.explico.Explico.render(set, Path.of("does-not-exist")) }
                .isInstanceOf(IllegalArgumentException::class.java).isInstanceOf(InvalidMaskException::class.java)
                .hasMessageContaining("custom.mask on rule 'a.pkg.a': entry 7 is not a string")
                .hasMessageContaining("custom.mask on rule 'z.pkg.b'")
                .satisfies({ e -> assertThat(e.message!!.indexOf("a.pkg.a")).isLessThan(e.message!!.indexOf("z.pkg.b")) })
        }
    }
}
