/**
 * 0.2.0 added parameters (with defaults) to public model constructors. Kotlin callers recompile, but a Java caller -- or
 * anything compiled against 0.1.x -- links to the OLD JVM signature and would fail with NoSuchMethodError unless it still
 * exists. `@JvmOverloads` keeps it. This pins the 0.1.1 constructor signatures of the model classes that grew.
 */
package io.explico

import io.explico.model.Operand
import io.explico.model.PackageMetadata
import io.explico.model.RuleBody
import io.explico.model.RuleMetadata
import io.explico.model.SourceRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ModelBinaryCompatibilityTest {

    @Test
    @DisplayName("RuleMetadata keeps its 0.1.1 four-argument constructor")
    fun ruleMetadataKeepsItsOldConstructor() {
        val ctor = RuleMetadata::class.java.getConstructor(String::class.java, String::class.java, String::class.java, List::class.java)
        val m = ctor.newInstance("T", "D", "C-1", listOf("F"))
        assertThat(m.authors).isEmpty()
        assertThat(m.relatedResources).isEmpty()
        assertThat(m.customFields).isEmpty()
    }

    @Test
    @DisplayName("PackageMetadata keeps its 0.1.1 two-argument constructor")
    fun packageMetadataKeepsItsOldConstructor() {
        val m = PackageMetadata::class.java.getConstructor(String::class.java, String::class.java).newInstance("T", "D")
        assertThat(m.authors).isEmpty()
    }

    @Test
    @DisplayName("RuleBody keeps its 0.1.1 six-argument constructor")
    fun ruleBodyKeepsItsOldConstructor() {
        val ctor = RuleBody::class.java.getConstructor(List::class.java, String::class.java, String::class.java, SourceRef::class.java, String::class.java, Operand::class.java)
        val b = ctor.newInstance(emptyList<Any>(), null, null, SourceRef("f.rego", 1), "", null)
        assertThat(b.messageArguments).isEmpty()
    }

    @Test
    @DisplayName("RuleMetadata keeps its 0.2.0 seven-argument constructor (before the declared projection, spec §14.53)")
    fun ruleMetadataKeepsItsZeroTwoZeroConstructor() {
        val ctor = RuleMetadata::class.java.getConstructor(
            String::class.java, String::class.java, String::class.java, List::class.java, List::class.java, List::class.java, Map::class.java,
        )
        val m = ctor.newInstance("T", "D", "C-1", listOf("F"), emptyList<Any>(), emptyList<Any>(), emptyMap<String, String>())
        assertThat(m.projection).isNull()
    }

    @Test
    @DisplayName("RuleBody keeps its 0.2.0 seven-argument constructor (before else chains, spec §14.52)")
    fun ruleBodyKeepsItsZeroTwoZeroConstructor() {
        val ctor = RuleBody::class.java.getConstructor(
            List::class.java, String::class.java, String::class.java, SourceRef::class.java, String::class.java, Operand::class.java, List::class.java,
        )
        val b = ctor.newInstance(emptyList<Any>(), null, null, SourceRef("f.rego", 1), "", null, emptyList<Any>())
        assertThat(b.elseBranches).isEmpty()
    }

    @Test
    @DisplayName("DeclaredProjection keeps its one-argument (verdict) constructor after actions, annotations and timing were added (spec §14.54)")
    fun declaredProjectionKeepsItsVerdictConstructor() {
        val ctor = io.explico.model.DeclaredProjection::class.java.getConstructor(io.explico.model.VerdictProjection::class.java)
        val p = ctor.newInstance(io.explico.model.VerdictProjection("ok"))
        assertThat(p.actions).isNull()
        assertThat(p.annotations).isNull()
        assertThat(p.timing).isNull()
    }
}
