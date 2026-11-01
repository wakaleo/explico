/**
 * The shape of the public API after the options-object change (spec §14.80 step 2), read by reflection from the compiled classes (no opa). The 14-parameter `render` and the 10-parameter
 * `diff` are gone, not deprecated (operator decision: no backward compatibility): the only entry points are the seven below. Synthetic bridges (`$default`) are ignored.
 */
package io.explico

import io.explico.model.PolicySet
import io.explico.model.RegoVersion
import io.explico.render.ExampleSet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.nio.file.Path

class PublicApiShapeTest {

    private fun signature(m: java.lang.reflect.Method) = "${m.name}(${m.parameterTypes.joinToString(", ") { it.simpleName }})"

    @Test
    @DisplayName("Explico offers exactly load, loadExamples, render(policies[, options]) and diff(old, new[, options])")
    fun explicoEntryPoints() {
        val methods = Explico::class.java.declaredMethods.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }.map { signature(it) }

        assertThat(methods).containsExactlyInAnyOrder(
            "load(Path)", "load(Path, RegoVersion)", "loadExamples(Path)",
            "render(PolicySet)", "render(PolicySet, RenderOptions)",
            "diff(PolicySet, PolicySet)", "diff(PolicySet, PolicySet, DiffOptions)",
        )
    }

    @Test
    @DisplayName("every option class is a public data class with the pinned fields, in order")
    fun optionFields() {
        fun fields(c: Class<*>) = c.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.map { it.name }

        assertThat(fields(RenderOptions::class.java)).containsExactly(
            "examples", "dataDir", "generateExamples", "json", "requirements", "sourceUrl", "controlId", "groupByControl", "companion", "required",
        )
        assertThat(fields(DiffOptions::class.java)).containsExactly("groupByControl", "data", "examples", "labels", "digests")
        assertThat(fields(ControlIdOptions::class.java)).containsExactly("from", "template")
        assertThat(fields(CompanionLink::class.java)).containsExactly("url", "label")
        assertThat(fields(DataVersions::class.java)).containsExactly("old", "new")
        assertThat(fields(VersionLabels::class.java)).containsExactly("old", "new")
    }

    @Test
    @DisplayName("PolicySet ends with sourceDir; the defaults give the plain options; the small classes keep their short constructors for Java")
    fun defaultsAndConstructors() {
        assertThat(PolicySet::class.java.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.map { it.name }).containsExactly("packages", "regoVersion", "sourceDir")
        assertThat(RenderOptions()).isEqualTo(RenderOptions(null, null, false, false, emptyList(), null, null, false, null, null))
        assertThat(DiffOptions()).isEqualTo(DiffOptions(false, null, null, null, false))
        assertThat(ControlIdOptions("x(?<id>1)").template).isEqualTo("{id}")
        assertThat(CompanionLink("https://h/{file}").label).isEqualTo("module")
        assertThat(ControlIdOptions::class.java.getConstructor(String::class.java)).isNotNull()
        assertThat(CompanionLink::class.java.getConstructor(String::class.java)).isNotNull()
        assertThat(PolicySet::class.java.getConstructor(List::class.java)).isNotNull()
    }

    @Test
    @DisplayName("Java builders: each method sets the field of the same name, build() gives the same object as the constructor, and a fresh builder is the plain options")
    fun builders() {
        val examples = ExampleSet(emptyList())
        val built = RenderOptions.builder().examples(examples).dataDir(Path.of("d")).generateExamples(true).json(true).requirements(listOf("r"))
            .sourceUrl("https://h/{file}").controlId(ControlIdOptions("x(?<id>1)")).groupByControl(true).companion(CompanionLink("https://h/{file}")).required(listOf("title")).build()

        assertThat(built).isEqualTo(
            RenderOptions(examples, Path.of("d"), true, true, listOf("r"), "https://h/{file}", ControlIdOptions("x(?<id>1)"), true, CompanionLink("https://h/{file}"), listOf("title")),
        )
        assertThat(RenderOptions.builder().build()).isEqualTo(RenderOptions())

        val diff = DiffOptions.builder().groupByControl(true).data(DataVersions(Path.of("a"), Path.of("b"))).examples(examples).labels(VersionLabels("v1", "v2")).digests(true).build()
        assertThat(diff).isEqualTo(DiffOptions(true, DataVersions(Path.of("a"), Path.of("b")), examples, VersionLabels("v1", "v2"), true))
        assertThat(DiffOptions.builder().build()).isEqualTo(DiffOptions())
    }
}
