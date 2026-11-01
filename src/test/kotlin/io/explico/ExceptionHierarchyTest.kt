/**
 * The exceptions a library consumer can catch (spec §14.80 step 3), read by reflection from the compiled classes (no opa): one base, `ExplicoException`; exactly seven classes in all; none
 * of them an `IllegalArgumentException` (an invalid argument keeps that type and is NOT an ExplicoException). Packages are unchanged: `io.explico` for the base, the two opa exceptions and
 * `OutputDirectoryException`, `io.explico.diff` for `DuplicateControlIdException`, `io.explico.render` for `DuplicateFixtureNameException` and `InvalidMaskException`.
 *
 * Kotlin's `internal` compiles to a public JVM class, so reflection cannot tell `InvalidMaskException` is public; a source check below pins that /tdd removed the modifier.
 */
package io.explico

import io.explico.diff.DuplicateControlIdException
import io.explico.render.DuplicateFixtureNameException
import io.explico.render.InvalidMaskException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ExceptionHierarchyTest {

    private val subclasses = listOf(
        OpaNotAvailableException::class.java, OpaFailedException::class.java, OutputDirectoryException::class.java,
        DuplicateControlIdException::class.java, DuplicateFixtureNameException::class.java, InvalidMaskException::class.java,
    )

    @Test
    @DisplayName("every one extends ExplicoException and none is an IllegalArgumentException")
    fun hierarchy() {
        for (c in subclasses) {
            assertThat(ExplicoException::class.java.isAssignableFrom(c)).describedAs("%s extends ExplicoException", c.simpleName).isTrue()
            assertThat(IllegalArgumentException::class.java.isAssignableFrom(c)).describedAs("%s is not an IllegalArgumentException", c.simpleName).isFalse()
        }
        assertThat(IllegalArgumentException::class.java.isAssignableFrom(ExplicoException::class.java)).isFalse()
    }

    @Test
    @DisplayName("the library has exactly these six subclasses of ExplicoException: no other exception class sneaks into the family, public or not")
    fun theFamilyIsClosed() {
        val root = Path.of("build/classes/kotlin/main")
        val found = Files.walk(root.resolve("io/explico")).use { paths ->
            paths.filter { it.toString().endsWith(".class") }
                .map { root.relativize(it).toString().removeSuffix(".class").replace('/', '.').replace('\\', '.') }
                .toList()
        }.map { Class.forName(it, false, javaClass.classLoader) }
            .filter { ExplicoException::class.java.isAssignableFrom(it) && it != ExplicoException::class.java }
            .map { it.name }

        assertThat(found).containsExactlyInAnyOrderElementsOf(subclasses.map { it.name })
    }

    @Test
    @DisplayName("each carries what a caller needs: exitCode and stderr, controlIds, name, path; a message; and a cause where one makes sense")
    fun payloads() {
        val failed = OpaFailedException(2, "rego_parse_error: unexpected")
        assertThat(failed.exitCode).isEqualTo(2)
        assertThat(failed.stderr).isEqualTo("rego_parse_error: unexpected")
        assertThat(failed.message).isEqualTo(failed.stderr)
        assertThat(DuplicateControlIdException(listOf("A-1", "B-2")).controlIds).containsExactly("A-1", "B-2")
        assertThat(DuplicateControlIdException(listOf("A-1")).message).isNotBlank()
        assertThat(DuplicateFixtureNameException("dup").name).isEqualTo("dup")
        assertThat(DuplicateFixtureNameException("dup").message).contains("dup")
        assertThat(OutputDirectoryException(Path.of("out"), "refusing").path).isEqualTo(Path.of("out"))
        assertThat(OpaNotAvailableException("opa is missing").message).isEqualTo("opa is missing")
        assertThat(InvalidMaskException("malformed").message).isEqualTo("malformed")
    }

    @Test
    @DisplayName("the base takes a message and an optional cause")
    fun base() {
        val cause = IllegalStateException("root")

        assertThat(ExplicoException("m").cause).isNull()
        assertThat(ExplicoException("m", cause).cause).isSameAs(cause)
        assertThat(ExplicoException::class.java.getConstructor(String::class.java)).isNotNull()
        assertThat(ExplicoException::class.java.getConstructor(String::class.java, Throwable::class.java)).isNotNull()
    }

    @Test
    @DisplayName("InvalidMaskException is declared public (not internal): a consumer can catch it by name")
    fun invalidMaskIsPublic() {
        val source = Files.readString(Path.of("src/main/kotlin/io/explico/render/InputMask.kt"))

        assertThat(source).contains("class InvalidMaskException").doesNotContain("internal class InvalidMaskException")
    }
}
