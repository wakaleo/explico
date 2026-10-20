/**
 * Free index variables (spec §14.51), from a real `opa parse` capture of `realistic/free-index/policies/free.rego` -- no opa needed.
 */
package io.explico.parse

import io.explico.model.Condition
import io.explico.model.Operand
import io.explico.model.PathSegment
import io.explico.opa.OpaModule
import io.explico.opa.opaJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class AstMapperFreeIndexTest {

    private val policySet by lazy {
        val module = opaJson.decodeFromString(OpaModule.serializer(), Files.readString(Path.of("src/test/resources/ast/free-index.json")))
        AstMapper.mapPolicySet(listOf(ParsedFile("free.rego", module)))
    }

    private fun conditions(rule: String): List<Condition> = policySet.packages.single().rules.single { it.name == rule }.bodies.single().conditions

    private fun path(vararg s: PathSegment) = Operand.Path(s.toList())
    private fun f(name: String) = PathSegment.Field(name)

    @Test
    @DisplayName("an index used once, declared or not, is the wildcard segment")
    fun singleUseIsAnyIndex() {
        for (rule in listOf("wildcard_undeclared", "wildcard_declared")) {
            val c = conditions(rule).single() as Condition.Comparison
            assertThat(c.left).describedAs(rule).isEqualTo(path(f("input"), f("pods"), PathSegment.AnyIndex, f("privileged")))
        }
    }

    @Test
    @DisplayName("an index used twice becomes a SomeKey over the first use's collection, then VarIndex on both uses")
    fun sharedIndexBinds() {
        val c = conditions("join_index")
        assertThat(c).hasSize(2)
        assertThat(c[0]).isEqualTo(Condition.SomeKey("i", path(f("input"), f("pods"))))
        val cmp = c[1] as Condition.Comparison
        assertThat(cmp.left).isEqualTo(path(f("input"), f("pods"), PathSegment.VarIndex("i"), f("name")))
        assertThat(cmp.right).isEqualTo(path(f("input"), f("names"), PathSegment.VarIndex("i")))
    }

    @Test
    @DisplayName("a bound index used as a value is a plain variable")
    fun valueUse() {
        val c = conditions("value_use")
        assertThat(c[0]).isInstanceOf(Condition.SomeKey::class.java)
        assertThat((c[2] as Condition.Comparison).left).isEqualTo(Operand.Variable("i"))
    }

    @Test
    @DisplayName("spec[field][_]: SomeKey for the key, then a SomeIn over that key's collection")
    fun mapKeyThenWildcard() {
        val c = conditions("map_key")
        val spec = path(f("input"), f("review"), f("object"), f("spec"))
        assertThat(c[0]).isEqualTo(Condition.SomeKey("field", spec))
        assertThat(c[1]).isEqualTo(Condition.SomeIn("container", Operand.Path(spec.segments + PathSegment.VarIndex("field"))))
        assertThat(c.filterIsInstance<Condition.Unrendered>()).isEmpty()
    }

    @Test
    @DisplayName("CHALLENGE: a first use under `not` is never bound; the later positive use is")
    fun negatedFirstUseIsNotBound() {
        val c = conditions("negated_first")
        assertThat(c.filterIsInstance<Condition.SomeKey>().map { it.collection }).containsExactly(path(f("input"), f("allowed")))
        val first = c[0] as Condition.Truthy
        assertThat(first.operand).isInstanceOf(Operand.Unrendered::class.java)
    }

    @Test
    @DisplayName("CHALLENGE: inside a comprehension a shared index is not bound -- the whole condition stays source")
    fun comprehensionDoesNotBind() {
        val only = conditions("comprehension_multi").single() as Condition.Unrendered
        assertThat(only.reason).isEqualTo("comprehension")
    }

    @Test
    @DisplayName("an index shared with the rule head counts as shared")
    fun headUseBinds() {
        assertThat(conditions("head_use").first()).isEqualTo(Condition.SomeKey("i", path(f("input"), f("pods"))))
    }

    @Test
    @DisplayName("CHALLENGE: under a function parameter there is no path to bind against, so nothing is bound")
    fun parameterRootedIsNotBound() {
        assertThat(conditions("param_rooted").filterIsInstance<Condition.SomeKey>()).isEmpty()
    }

    @Test
    @DisplayName("CHALLENGE: a wildcard directly under input names nothing to show (the breadcrumb would be empty), so the operand stays raw")
    fun rootWildcardIsRaw() {
        val c = conditions("root_wildcard").single() as Condition.Comparison
        assertThat(c.left).isInstanceOf(Operand.Unrendered::class.java)
    }

    @Test
    @DisplayName("a bound free index used as an every domain maps the domain through the binding")
    fun boundIndexAsEveryDomain() {
        val c = conditions("every_domain")
        assertThat(c[0]).isEqualTo(Condition.SomeKey("i", path(f("input"), f("groups"))))
        val every = c[2] as Condition.Every
        assertThat(every.collection).isEqualTo(path(f("input"), f("members"), PathSegment.VarIndex("i")))
        assertThat(every.body.single()).isInstanceOf(Condition.Comparison::class.java)
    }
}
