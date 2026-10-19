/**
 * `opa eval --format json` reports an evaluation error as JSON on STDOUT and exits non-zero, leaving stderr empty. The
 * exception must still say what went wrong -- an empty message made a Windows-only failure undiagnosable.
 * Uses a fake `opa` shell script as the binary, so it is skipped on Windows.
 */
package io.explico.opa

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class OpaRunnerFailureOutputTest {

    @AfterEach
    fun restoreBinary() {
        OpaRunner.binaryOverride = null
    }

    private fun fakeOpa(dir: Path, script: String): Path {
        val file = dir.resolve("fake-opa")
        Files.writeString(file, "#!/bin/sh\n$script\n")
        file.toFile().setExecutable(true)
        return file
    }

    private fun evalWith(opa: Path, dir: Path) {
        OpaRunner.binaryOverride = opa
        val input = Files.writeString(dir.resolve("in.json"), "{}")
        OpaRunner.eval(input, listOf(dir), "data.p")
    }

    @Test
    fun anErrorOpaPrintsOnStdoutIsInTheExceptionWhenStderrIsEmpty(@TempDir dir: Path) {
        assumeFalse(System.getProperty("os.name").lowercase().contains("windows"), "needs a shell-script opa")
        val opa = fakeOpa(dir, """echo '{"errors":[{"message":"rego_type_error: boom"}]}'; exit 2""")
        assertThatThrownBy { evalWith(opa, dir) }
            .isInstanceOf(OpaInvocationException::class.java)
            .hasMessageContaining("rego_type_error: boom")
    }

    @Test
    fun stderrStillWinsWhenOpaWritesThere(@TempDir dir: Path) {
        assumeFalse(System.getProperty("os.name").lowercase().contains("windows"), "needs a shell-script opa")
        val opa = fakeOpa(dir, """echo 'some stdout'; echo 'real stderr problem' >&2; exit 2""")
        assertThatThrownBy { evalWith(opa, dir) }.hasMessageContaining("real stderr problem").hasMessageNotContaining("some stdout")
    }

    @Test
    fun withNeitherStreamTheExitCodeIsStillReported(@TempDir dir: Path) {
        assumeFalse(System.getProperty("os.name").lowercase().contains("windows"), "needs a shell-script opa")
        val opa = fakeOpa(dir, "exit 3")
        val failure = org.assertj.core.api.Assertions.catchThrowable { evalWith(opa, dir) }
        assertThat(failure).isInstanceOf(OpaInvocationException::class.java)
        assertThat(failure.message).contains("3")
    }
}
