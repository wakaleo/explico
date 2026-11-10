/**
 * The committed public API baseline `api/explico.api` (spec §14.80 step 6): Kotlin's built-in ABI validation dumps the public surface of the library (Kotlin visibility, so `internal` types are NOT in
 * it -- reflection cannot tell, internal compiles to JVM-public), `./gradlew updateKotlinAbi` rewrites it as a deliberate, reviewed act, and `checkKotlinAbi` (part of `check`) fails on any drift.
 * This test is the readable half: it pins WHAT is public so a change to the surface is a conscious edit of this list, not an accident. No opa; it reads the committed file and does not run Gradle.
 *
 * Pinned (from a throwaway dump on 2026-11-01, then the operator's 2026-11-02 decisions): the 85 public classes below; exactly seven `Explico` entry points (plus `INSTANCE`; `$default` synthetics
 * ignored); no internal type. `io/explico/cli/MainKt` was in the first dump because `fun main` was implicitly public; it was made `internal` (the launcher is not library API), which is why there is no
 * `io/explico/cli/` class here. `io/explico/render/Fixture$$serializer` (kotlinx-serialization machinery of a public data class) stays blessed. The experimental opt-in `ExperimentalAbiValidation` is part
 * of the Kotlin Gradle plugin already in use, pinned by its version: no new dependency.
 */
package io.explico

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ApiBaselineTest {

    private val baseline = Path.of("api/explico.api")

    private val publicClasses = listOf(
        "io/explico/CompanionLink",
        "io/explico/ControlIdOptions",
        "io/explico/DataVersions",
        "io/explico/DiffOptions",
        "io/explico/DiffOptions\$Builder",
        "io/explico/DiffOptions\$Companion",
        "io/explico/DiffReport",
        "io/explico/Explico",
        "io/explico/ExplicoException",
        "io/explico/ExplicoStructuralModel",
        "io/explico/GeneratedExamplesSummary",
        "io/explico/OpaFailedException",
        "io/explico/OpaNotAvailableException",
        "io/explico/OutputDirectoryException",
        "io/explico/RenderOptions",
        "io/explico/RenderOptions\$Builder",
        "io/explico/RenderOptions\$Companion",
        "io/explico/RenderedDocs",
        "io/explico/VersionLabels",
        "io/explico/diff/DiffCategory",
        "io/explico/diff/DiffChange",
        "io/explico/diff/DiffEntry",
        "io/explico/diff/DuplicateControlIdException",
        "io/explico/diff/ExampleChange",
        "io/explico/diff/ExampleComparison",
        "io/explico/model/ActionsProjection",
        "io/explico/model/AnnotationsProjection",
        "io/explico/model/Author",
        "io/explico/model/CommentBlock",
        "io/explico/model/ComprehensionKind",
        "io/explico/model/Condition",
        "io/explico/model/Condition\$BuiltinCall",
        "io/explico/model/Condition\$Comparison",
        "io/explico/model/Condition\$Every",
        "io/explico/model/Condition\$FunctionCall",
        "io/explico/model/Condition\$Membership",
        "io/explico/model/Condition\$RuleReference",
        "io/explico/model/Condition\$SomeIn",
        "io/explico/model/Condition\$SomeKey",
        "io/explico/model/Condition\$Truthy",
        "io/explico/model/Condition\$Unrendered",
        "io/explico/model/ConstantValue",
        "io/explico/model/DeclaredProjection",
        "io/explico/model/DefaultValue",
        "io/explico/model/ElseBranch",
        "io/explico/model/LiteralCollection",
        "io/explico/model/ObjectField",
        "io/explico/model/Operand",
        "io/explico/model/Operand\$BuiltinCall",
        "io/explico/model/Operand\$Comprehension",
        "io/explico/model/Operand\$Formatted",
        "io/explico/model/Operand\$FunctionCall",
        "io/explico/model/Operand\$InlinedObject",
        "io/explico/model/Operand\$Literal",
        "io/explico/model/Operand\$Parameter",
        "io/explico/model/Operand\$ParameterPath",
        "io/explico/model/Operand\$Path",
        "io/explico/model/Operand\$RuleValueReference",
        "io/explico/model/Operand\$Unrendered",
        "io/explico/model/Operand\$Variable",
        "io/explico/model/Operator",
        "io/explico/model/PackageMetadata",
        "io/explico/model/PathSegment",
        "io/explico/model/PathSegment\$AnyIndex",
        "io/explico/model/PathSegment\$Field",
        "io/explico/model/PathSegment\$KeyLiteral",
        "io/explico/model/PathSegment\$KeyPath",
        "io/explico/model/PathSegment\$VarIndex",
        "io/explico/model/PolicyPackage",
        "io/explico/model/PolicySet",
        "io/explico/model/RegoVersion",
        "io/explico/model/RelatedResource",
        "io/explico/model/RuleBody",
        "io/explico/model/RuleGroup",
        "io/explico/model/RuleMetadata",
        "io/explico/model/SourceRef",
        "io/explico/model/TimingProjection",
        "io/explico/model/VerdictProjection",
        "io/explico/render/CoverageSummary",
        "io/explico/render/DuplicateFixtureNameException",
        "io/explico/render/ExampleSet",
        "io/explico/render/Fixture",
        "io/explico/render/Fixture\$\$serializer",
        "io/explico/render/Fixture\$Companion",
        "io/explico/render/InvalidMaskException"
    )

    private val explicoMembers = listOf(
        "public static final field INSTANCE Lio/explico/Explico;",
        "public static final fun diff (Lio/explico/model/PolicySet;Lio/explico/model/PolicySet;)Lio/explico/DiffReport;",
        "public static final fun diff (Lio/explico/model/PolicySet;Lio/explico/model/PolicySet;Lio/explico/DiffOptions;)Lio/explico/DiffReport;",
        "public static final fun load (Ljava/nio/file/Path;)Lio/explico/model/PolicySet;",
        "public static final fun load (Ljava/nio/file/Path;Lio/explico/model/RegoVersion;)Lio/explico/model/PolicySet;",
        "public static final fun loadExamples (Ljava/nio/file/Path;)Lio/explico/render/ExampleSet;",
        "public static final fun render (Lio/explico/model/PolicySet;)Lio/explico/RenderedDocs;",
        "public static final fun render (Lio/explico/model/PolicySet;Lio/explico/RenderOptions;)Lio/explico/RenderedDocs;"
    )

    private fun lines(): List<String> {
        assertThat(baseline).describedAs("the committed API baseline api/explico.api").exists()
        return Files.readString(baseline).lines()
    }

    /** The class headers of the dump: lines at column 0 that are not a closing brace (`public final class io/explico/Foo {`). */
    private fun classNames(): List<String> = lines().filter { it.isNotEmpty() && !it[0].isWhitespace() && it[0] != '}' }
        .map { Regex("^public .*?\\bclass (\\S+)").find(it)?.groupValues?.get(1) ?: error("not a class header: $it") }

    @Test
    @DisplayName("the baseline exists, is non-empty and uses LF line endings")
    fun exists() {
        val text = lines().joinToString("\n")

        assertThat(text).isNotBlank()
        assertThat(Files.readString(baseline)).doesNotContain("\r")
    }

    @Test
    @DisplayName("the public classes are exactly the 85 blessed ones: a new, removed or renamed public type is a conscious edit of this list")
    fun theBlessedSurface() {
        assertThat(classNames()).containsExactlyInAnyOrderElementsOf(publicClasses)
        assertThat(publicClasses).hasSize(85)
    }

    @Test
    @DisplayName("Explico has exactly seven entry points (load x2, loadExamples, render x2, diff x2) and its INSTANCE, ignoring `\$default` synthetics")
    fun theFacade() {
        val all = lines()
        val start = all.indexOf("public final class io/explico/Explico {")
        assertThat(start).describedAs("the Explico class in the baseline").isGreaterThanOrEqualTo(0)
        val members = all.drop(start + 1).takeWhile { it != "}" }.map { it.trim() }.filter { "\$default" !in it }

        assertThat(members).containsExactlyInAnyOrderElementsOf(explicoMembers)
    }

    @Test
    @DisplayName("no internal type is in the baseline: Kotlin visibility, not reflection, decided what is public")
    fun noInternals() {
        val text = lines().joinToString("\n")

        for (internal in listOf("OutputGuard", "OutputWriter", "OpaRunner", "AstMapper", "MarkdownRenderer", "ChangedSet", "InputSynthesizer", "Gates", "CliSurface", "ExitCodes")) {
            assertThat(text).describedAs("internal %s", internal).doesNotContain(internal)
        }
        assertThat(classNames().filter { it.startsWith("io/explico/cli/") }).isEmpty()
    }

    @Test
    @DisplayName("the build enables the validation: abiValidation with the experimental opt-in (a documented source check; Gradle is not run from here)")
    fun buildEnablesIt() {
        val build = Files.readString(Path.of("build.gradle.kts"))

        assertThat(build).contains("abiValidation").contains("ExperimentalAbiValidation")
    }
}
