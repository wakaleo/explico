/**
 * The two opa failures as PUBLIC exceptions (spec §14.80 step 3): `OpaNotAvailableException` when opa cannot be run or is not 1.x, `OpaFailedException(exitCode, stderr)` when opa ran and
 * rejected the input. They replace the internal `OpaUnavailableException` / `OpaInvocationException` entirely, so a library consumer sees them from `Explico.load` itself, not only from the
 * CLI's up-front check. A missing binary used to surface as a raw `IOException` from `ProcessBuilder`. The "missing" cases need no opa; the "failed" ones do (guarded).
 */
package io.explico.opa

import io.explico.Explico
import io.explico.OpaFailedException
import io.explico.OpaNotAvailableException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class OpaExceptionsTest {

    private val missing = Path.of("/nonexistent/opa-binary")

    private fun <T> withBinary(binary: Path?, block: () -> T): T {
        val saved = OpaRunner.binaryOverride
        OpaRunner.binaryOverride = binary
        try { return block() } finally { OpaRunner.binaryOverride = saved }
    }

    @Test
    @DisplayName("a binary that cannot be run: requireCompatibleVersion throws OpaNotAvailableException naming the binary, opa 1.x and OPA_BIN")
    fun requireCompatibleVersion() {
        val e = withBinary(missing) { assertThrows(OpaNotAvailableException::class.java) { OpaRunner.requireCompatibleVersion() } }

        assertThat(e.message).contains("/nonexistent/opa-binary").contains("opa version 1").contains("OPA_BIN")
    }

    @Test
    @DisplayName("Explico.load with no usable opa throws OpaNotAvailableException, not a raw IOException from the process launch")
    fun loadWithoutOpa(@TempDir dir: Path) {
        Files.writeString(dir.resolve("p.rego"), "package p\n")

        val e = withBinary(missing) { assertThrows(OpaNotAvailableException::class.java) { Explico.load(dir) } }

        assertThat(e.message).contains("/nonexistent/opa-binary")
    }

    @Test
    @DisplayName("opa rejecting a file throws OpaFailedException: opa's own exit code (not 0), its stderr verbatim, which is also the message")
    fun syntaxError(@TempDir dir: Path) {
        assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
        val file = dir.resolve("broken.rego")
        Files.writeString(file, "package broken\n\nthis is not valid rego {{{\n")

        val e = assertThrows(OpaFailedException::class.java) { OpaRunner.parse(file) }

        assertThat(e.exitCode).isNotEqualTo(0)
        assertThat(e.stderr).contains("broken.rego")
        assertThat(e.message).isEqualTo(e.stderr)
    }
}
