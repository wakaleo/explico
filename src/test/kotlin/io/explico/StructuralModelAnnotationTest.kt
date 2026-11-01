/**
 * Which part of the public model is promised stable and which is the opt-in structural model (spec §14.80 step 4), read by reflection from the compiled classes and, where the compiler
 * hides it from reflection, from the source. No opa.
 *
 * - STRUCTURAL = carries `@ExplicoStructuralModel` at class level: the 22 model types below `RegoVersion`/`PolicySet`/`PolicyPackage`, and `DiffEntry`. The scan below fails when a
 *   top-level class is added to `io.explico.model` without being classified here, so the decision is made consciously.
 * - STABLE = not annotated: everything else public. A stable class that exposes a structural type through a PROPERTY is annotated on that property (`PolicyPackage.rules`,
 *   `.metadata`, `.unpublishedComments`, `DiffReport.entries`), so Kotlin callers touching it get the warning while `PolicyPackage.path`, `sourceFiles` and `PolicySet.packages` stay clean.
 * - The marker is `@RequiresOptIn(level = WARNING)` with RUNTIME retention (so this test can see it); the library's own compilations opt in with a compiler argument, so neither the
 *   library nor its tests warn.
 *
 * Kotlin cannot show `@RequiresOptIn`'s level to reflection (BINARY retention), nor the compiler arguments, so those two are source checks, like `ExceptionHierarchyTest.invalidMaskIsPublic`.
 */
package io.explico

import io.explico.diff.DiffCategory
import io.explico.diff.DiffChange
import io.explico.diff.DiffEntry
import io.explico.diff.ExampleChange
import io.explico.diff.ExampleComparison
import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RegoVersion
import io.explico.render.CoverageSummary
import io.explico.render.ExampleSet
import io.explico.render.Fixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.annotation.RetentionPolicy
import java.nio.file.Files
import java.nio.file.Path

class StructuralModelAnnotationTest {

    private val structural = listOf(
        "RuleGroup", "RuleBody", "ElseBranch", "ConstantValue", "DefaultValue", "Condition", "Operand", "PathSegment", "Operator", "ComprehensionKind", "LiteralCollection",
        "SourceRef", "CommentBlock", "RuleMetadata", "PackageMetadata", "DeclaredProjection", "VerdictProjection", "ActionsProjection", "AnnotationsProjection", "TimingProjection",
        "Author", "RelatedResource",
    ).map { "io.explico.model.$it" } + "io.explico.diff.DiffEntry"

    private val stableModel = listOf("RegoVersion", "PolicySet", "PolicyPackage").map { "io.explico.model.$it" }

    private val stable = listOf(
        Explico::class.java, RenderedDocs::class.java, DiffReport::class.java, DiffCategory::class.java, DiffChange::class.java, ExampleChange::class.java, ExampleComparison::class.java,
        CoverageSummary::class.java, GeneratedExamplesSummary::class.java, ExampleSet::class.java, Fixture::class.java, RegoVersion::class.java, PolicySet::class.java, PolicyPackage::class.java,
        RenderOptions::class.java, DiffOptions::class.java, ControlIdOptions::class.java, CompanionLink::class.java, DataVersions::class.java, VersionLabels::class.java,
        ExplicoException::class.java, OpaFailedException::class.java, OpaNotAvailableException::class.java, OutputDirectoryException::class.java,
    )

    private fun topLevelClasses(): List<Class<*>> {
        val root = Path.of("build/classes/kotlin/main")
        return Files.walk(root.resolve("io/explico")).use { paths ->
            paths.filter { it.toString().endsWith(".class") }
                .map { root.relativize(it).toString().removeSuffix(".class").replace('/', '.').replace('\\', '.') }
                .toList()
        }.filter { !it.contains('$') }.map { Class.forName(it, false, javaClass.classLoader) }
    }

    /** True when the Kotlin property [name] of [owner] carries the marker (Kotlin keeps a PROPERTY-target annotation on a synthetic `getX$annotations` method). */
    private fun propertyMarked(owner: Class<*>, name: String): Boolean =
        owner.declaredMethods.firstOrNull { it.name == "get${name.replaceFirstChar { c -> c.uppercase() }}\$annotations" }?.isAnnotationPresent(ExplicoStructuralModel::class.java) ?: false

    @Test
    @DisplayName("the marker is a RequiresOptIn at WARNING level (never an error), visible at runtime")
    fun markerShape() {
        val retention = ExplicoStructuralModel::class.java.getAnnotation(java.lang.annotation.Retention::class.java)
        val source = Files.readString(Path.of("src/main/kotlin/io/explico/ExplicoStructuralModel.kt"))

        assertThat(retention?.value).isEqualTo(RetentionPolicy.RUNTIME)
        assertThat(source).contains("@RequiresOptIn(level = RequiresOptIn.Level.WARNING")
    }

    @Test
    @DisplayName("exactly the 22 model types (and DiffEntry) listed carry the marker at class level, and nothing else in the library does")
    fun theStructuralSetIsExact() {
        val marked = topLevelClasses().filter { it.isAnnotationPresent(ExplicoStructuralModel::class.java) }.map { it.name }

        assertThat(marked).containsExactlyInAnyOrderElementsOf(structural)
    }

    @Test
    @DisplayName("every top-level class in io.explico.model is classified: structural, or one of RegoVersion, PolicySet, PolicyPackage")
    fun noModelClassIsUnclassified() {
        val inModel = topLevelClasses().map { it.name }.filter { it.startsWith("io.explico.model.") && !it.endsWith("Kt") }

        assertThat(structural.filter { it.startsWith("io.explico.model.") } + stableModel).containsExactlyInAnyOrderElementsOf(inModel)
    }

    @Test
    @DisplayName("the stable surface is not annotated: the facade, results, options, exceptions, and the small value types a consumer reads")
    fun theStableSurfaceIsClean() {
        for (c in stable) assertThat(c.isAnnotationPresent(ExplicoStructuralModel::class.java)).describedAs("%s is stable", c.simpleName).isFalse()
    }

    @Test
    @DisplayName("a stable class that exposes a structural type does so through a marked property: PolicyPackage.rules, metadata, unpublishedComments and DiffReport.entries")
    fun markedProperties() {
        assertThat(propertyMarked(PolicyPackage::class.java, "rules")).describedAs("PolicyPackage.rules").isTrue()
        assertThat(propertyMarked(PolicyPackage::class.java, "metadata")).describedAs("PolicyPackage.metadata").isTrue()
        assertThat(propertyMarked(PolicyPackage::class.java, "unpublishedComments")).describedAs("PolicyPackage.unpublishedComments").isTrue()
        assertThat(propertyMarked(DiffReport::class.java, "entries")).describedAs("DiffReport.entries").isTrue()
    }

    @Test
    @DisplayName("and the properties that stay stable are clean: PolicyPackage.path and sourceFiles, PolicySet.packages, DiffReport.changes and markdown")
    fun unmarkedProperties() {
        assertThat(propertyMarked(PolicyPackage::class.java, "path")).describedAs("PolicyPackage.path").isFalse()
        assertThat(propertyMarked(PolicyPackage::class.java, "sourceFiles")).describedAs("PolicyPackage.sourceFiles").isFalse()
        assertThat(propertyMarked(PolicySet::class.java, "packages")).describedAs("PolicySet.packages").isFalse()
        assertThat(propertyMarked(DiffReport::class.java, "changes")).describedAs("DiffReport.changes").isFalse()
        assertThat(propertyMarked(DiffReport::class.java, "markdown")).describedAs("DiffReport.markdown").isFalse()
    }

    @Test
    @DisplayName("the library's own compilations opt in, so neither it nor its tests warn: main, test and the docs snippets all get -opt-in for the marker")
    fun ownCompilationsOptIn() {
        val build = Files.readString(Path.of("build.gradle.kts"))

        assertThat(build).contains("io.explico.ExplicoStructuralModel")
        assertThat(Regex("""(optIn\.add|-opt-in=)\(?"?(-opt-in=)?io\.explico\.ExplicoStructuralModel""").containsMatchIn(build)).describedAs("an opt-in argument for the marker").isTrue()
    }
}
