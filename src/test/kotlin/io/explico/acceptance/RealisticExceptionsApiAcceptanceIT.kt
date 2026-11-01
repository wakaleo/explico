/**
 * Acceptance tests for the exception base (spec §14.80 step 3): a library consumer catches EVERY expected failure with one `catch (e: ExplicoException)` and tells them apart by type.
 * Authored from the 1.0 API review, NOT from the frozen acceptance-pack README. Decisions pinned here (operator decisions of 2026-11-01; /tdd must match them):
 *
 * - `ExplicoException` (public, in `io.explico`) is the base of: `OpaFailedException(exitCode, stderr)` (opa rejected the input; message == stderr), `OpaNotAvailableException` (opa missing / not
 *   1.x), `DuplicateControlIdException(controlIds)`, `DuplicateFixtureNameException(name)`, `OutputDirectoryException(path)` and `InvalidMaskException` (now PUBLIC). Packages do not move.
 * - `InvalidMaskException` is NOT an `IllegalArgumentException` any more: one consistent base wins over the old supertype (Kotlin has single inheritance; pre-1.0, no backward compatibility).
 * - An invalid ARGUMENT (a bad template, pattern, label or required name, rendering a set that was not loaded) stays an `IllegalArgumentException` and is NOT an ExplicoException; a missing
 *   directory stays a raw `java.io.IOException`. Those two groups are "unchanged" guards and pass already.
 * - The CLI's exit code per type is `ExitCodeMappingTest`'s; the missing-binary case is `OpaExceptionsTest`'s (a facade test cannot choose the opa binary).
 *
 * Facade only. A failure is observed as a returned Throwable, so a wrong type is an assertion failure, never an escaping exception.
 */
package io.explico.acceptance

import io.explico.ControlIdOptions
import io.explico.DiffOptions
import io.explico.Explico
import io.explico.ExplicoException
import io.explico.OpaFailedException
import io.explico.OutputDirectoryException
import io.explico.RenderOptions
import io.explico.diff.DuplicateControlIdException
import io.explico.model.PolicySet
import io.explico.opa.OpaRunner
import io.explico.render.DuplicateFixtureNameException
import io.explico.render.InvalidMaskException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@DisplayName("Realistic policies: one exception base")
class RealisticExceptionsApiAcceptanceIT {

    companion object {
        private val duplicateIds = Path.of("src/test/resources/diff/policydiff/duplicate-control-ids")
        private val maskBase = Path.of("src/test/resources/realistic/input-masking")

        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
        }

        /** What [block] throws, or null: so a wrong type is an assertion failure, not an escaping exception. */
        private fun thrown(block: () -> Unit): Throwable? = try { block(); null } catch (t: Throwable) { t }
    }

    @Nested
    @DisplayName("One catch covers every expected failure")
    inner class OneCatch {

        @Test
        @DisplayName("opa rejecting the policies: OpaFailedException with opa's exit code and its own stderr as the message")
        fun opaFailed(@TempDir dir: Path) {
            Files.writeString(dir.resolve("broken.rego"), "package broken\n\nthis is not valid rego {{{\n")

            val t = thrown { Explico.load(dir) }

            assertThat(t).isInstanceOf(ExplicoException::class.java).isInstanceOf(OpaFailedException::class.java)
            val failed = t as OpaFailedException
            assertThat(failed.exitCode).isNotEqualTo(0)
            assertThat(failed.stderr).contains("broken.rego")
            assertThat(failed.message).isEqualTo(failed.stderr)
        }

        @Test
        @DisplayName("a control id shared by two rules in a diff: DuplicateControlIdException listing the ids")
        fun duplicateControlIds() {
            val set = Explico.load(duplicateIds)

            val t = thrown { Explico.diff(set, set, DiffOptions()) }

            assertThat(t).isInstanceOf(ExplicoException::class.java).isInstanceOf(DuplicateControlIdException::class.java)
            assertThat((t as DuplicateControlIdException).controlIds).isNotEmpty()
        }

        @Test
        @DisplayName("two fixtures with one name: DuplicateFixtureNameException naming it")
        fun duplicateFixtureNames(@TempDir dir: Path) {
            Files.writeString(dir.resolve("a.json"), """{"name": "dup", "input": {}}""")
            Files.writeString(dir.resolve("b.json"), """{"name": "dup", "input": {}}""")

            val t = thrown { Explico.loadExamples(dir) }

            assertThat(t).isInstanceOf(ExplicoException::class.java).isInstanceOf(DuplicateFixtureNameException::class.java)
            assertThat((t as DuplicateFixtureNameException).name).isEqualTo("dup")
        }

        @Test
        @DisplayName("a malformed custom.mask: InvalidMaskException naming custom.mask and the rule -- an ExplicoException, no longer an IllegalArgumentException")
        fun malformedMask() {
            val set = Explico.load(maskBase.resolve("malformed-scalar"))

            val t = thrown { Explico.render(set) }

            assertThat(t).isInstanceOf(ExplicoException::class.java).isInstanceOf(InvalidMaskException::class.java).isNotInstanceOf(IllegalArgumentException::class.java)
            assertThat(t!!.message).contains("custom.mask").contains("badmask.leaky")
        }

        @Test
        @DisplayName("writing into an existing file: OutputDirectoryException carrying the path")
        fun refusedOutput(@TempDir dir: Path) {
            val file = dir.resolve("out")
            Files.writeString(file, "I am a file")
            val docs = Explico.render(Explico.load(Path.of("src/test/resources/acceptance/policies")))

            val t = thrown { docs.writeTo(file) }

            assertThat(t).isInstanceOf(ExplicoException::class.java).isInstanceOf(OutputDirectoryException::class.java)
            assertThat((t as OutputDirectoryException).path).isEqualTo(file)
        }

        @Test
        @DisplayName("a duplicate id, a duplicate fixture name and a malformed mask are all caught by one `catch (e: ExplicoException)` and told apart by type")
        fun oneCatchBlock(@TempDir dir: Path) {
            val ids = Explico.load(duplicateIds)
            Files.writeString(dir.resolve("a.json"), """{"name": "dup", "input": {}}""")
            Files.writeString(dir.resolve("b.json"), """{"name": "dup", "input": {}}""")
            val masks = Explico.load(maskBase.resolve("malformed-scalar"))
            val actions = listOf<() -> Unit>(
                { Explico.diff(ids, ids, DiffOptions()) },
                { Explico.loadExamples(dir) },
                { Explico.render(masks) },
            )

            val caught = actions.map { action ->
                try { action(); null } catch (e: ExplicoException) { e.javaClass.simpleName } catch (e: Throwable) { "not an ExplicoException: ${e.javaClass.simpleName}" }
            }

            assertThat(caught).containsExactly("DuplicateControlIdException", "DuplicateFixtureNameException", "InvalidMaskException")
        }
    }

    @Nested
    @DisplayName("Unchanged: an invalid argument is an IllegalArgumentException, a missing directory an IOException -- neither is an ExplicoException")
    inner class NotExplicoExceptions {

        private val pack = Path.of("src/test/resources/acceptance/policies")

        @Test
        @DisplayName("a bad source URL template, a control-id pattern without the named group, and rendering a set built by hand")
        fun invalidArguments() {
            val set = Explico.load(pack)

            for (t in listOf(
                thrown { Explico.render(set, RenderOptions(sourceUrl = "https://example.com/no-placeholder")) },
                thrown { Explico.render(set, RenderOptions(controlId = ControlIdOptions("ctrl_(\\d+)"))) },
                thrown { Explico.render(PolicySet(emptyList())) },
            )) {
                assertThat(t).isInstanceOf(IllegalArgumentException::class.java).isNotInstanceOf(ExplicoException::class.java)
            }
        }

        @Test
        @DisplayName("a policy directory that does not exist is the file system's own exception")
        fun missingDirectory() {
            val t = thrown { Explico.load(Path.of("does-not-exist-anywhere")) }

            assertThat(t).isInstanceOf(java.io.IOException::class.java).isNotInstanceOf(ExplicoException::class.java)
        }
    }
}
