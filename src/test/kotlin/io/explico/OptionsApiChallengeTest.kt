/**
 * CHALLENGE cases for the options-object API (spec §14.80 step 2) beyond the acceptance tests: options are values and reusable, an empty `required` is not `null`, an empty example set is
 * "not supplied" for a diff, and `PolicySet.sourceDir` takes part in equality. Needs opa (guarded).
 */
package io.explico

import io.explico.opa.OpaRunner
import io.explico.render.ExampleSet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class OptionsApiChallengeTest {

    companion object {
        private val pack = Path.of("src/test/resources/acceptance/policies")
        private val quality = Path.of("src/test/resources/realistic/quality/policies")

        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
        }
    }

    @Test
    @DisplayName("one RenderOptions value can be reused for several renders and is never changed by them")
    fun optionsAreReusable() {
        val options = RenderOptions(json = true, requirements = listOf("SOC 2 CC8.1"))
        val before = options.copy()

        val first = Explico.render(Explico.load(pack), options)
        val second = Explico.render(Explico.load(quality), options)
        val again = Explico.render(Explico.load(pack), options)

        assertThat(options).isEqualTo(before)
        assertThat(first.files).containsKey("explico.json")
        assertThat(second.files).containsKey("explico.json")
        assertThat(again.files.toList()).isEqualTo(first.files.toList())
    }

    @Test
    @DisplayName("required = emptyList() requires nothing, which is not the same as null (the default: description and frameworks)")
    fun emptyRequiredIsNotNull() {
        val set = Explico.load(quality)

        val default = Explico.render(set, RenderOptions())
        val none = Explico.render(set, RenderOptions(required = emptyList()))

        assertThat(default.gaps.size).describedAs("the default set finds more gaps in this fixture").isGreaterThan(none.gaps.size)
        assertThat(none.gaps).describedAs("only the gaps that are always gaps").containsExactly("controls Q-001, Q-002 share the title \"Shared\"")
        assertThat(none.files).isEqualTo(default.files)
    }

    @Test
    @DisplayName("an empty example set is 'not supplied' for a diff: no section, no error, even on sets that have directories")
    fun emptyExamplesAreNotSupplied() {
        val set = Explico.load(pack)

        val report = Explico.diff(set, set, DiffOptions(examples = ExampleSet(emptyList())))

        assertThat(report.markdown).doesNotContain("## Behaviour on your examples")
        assertThat(report.exampleComparison).isNull()
    }

    @Test
    @DisplayName("two loads of copies of one directory are unequal sets (sourceDir is part of equality) with equal packages: compare packages, not sets")
    fun sourceDirIsPartOfEquality(@TempDir tempDir: Path) {
        val copy = tempDir.resolve("copy")
        Files.walk(pack).use { paths ->
            paths.forEach { p ->
                val target = copy.resolve(pack.relativize(p).toString())
                if (Files.isDirectory(p)) Files.createDirectories(target) else Files.copy(p, target)
            }
        }

        val a = Explico.load(pack)
        val b = Explico.load(copy)

        assertThat(a).isNotEqualTo(b)
        assertThat(a.packages.map { it.path }).isEqualTo(b.packages.map { it.path })
        assertThat(a.copy(sourceDir = null)).isEqualTo(b.copy(sourceDir = null))
        assertThat(Explico.load(pack)).isEqualTo(a)
    }

    @Test
    @DisplayName("a set loaded from a relative path remembers an absolute directory, so it renders from anywhere")
    fun relativeLoadIsAbsolute() {
        val set = Explico.load(Path.of("src/test/resources/acceptance/policies"))

        assertThat(set.sourceDir).isNotNull().satisfies({ assertThat(it!!.isAbsolute).isTrue() })
        assertThat(Explico.render(set).files).containsKey("index.md")
    }
}
