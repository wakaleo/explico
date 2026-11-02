/**
 * CHALLENGE cases for the public opa exceptions (spec §14.80 step 3), driven with fake `opa` binaries (small shell scripts) so no real opa and no 30-second wait are needed: opa's real exit
 * code and stderr pass through, a silent failure still says something, a stalled opa is stopped with the documented exit code -1, and a binary that cannot be launched keeps its IOException
 * as the cause. POSIX only (a shell script as the binary).
 */
package io.explico.opa

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

class OpaRunnerChallengeTest {

    private fun fakeOpa(dir: Path, script: String): Path {
        assumeTrue(!System.getProperty("os.name").lowercase().contains("win"), "needs a POSIX shell")
        val file = dir.resolve("fake-opa")
        Files.writeString(file, "#!/bin/sh\n$script\n")
        file.toFile().setExecutable(true)
        return file
    }

    private fun <T> withBinary(binary: Path, block: () -> T): T {
        val saved = OpaRunner.binaryOverride
        OpaRunner.binaryOverride = binary
        try { return block() } finally { OpaRunner.binaryOverride = saved }
    }

    private fun parseWith(binary: Path, dir: Path): OpaFailedException {
        val rego = dir.resolve("p.rego"); Files.writeString(rego, "package p\n")
        return withBinary(binary) { assertThrows(OpaFailedException::class.java) { OpaRunner.parse(rego) } }
    }

    @Test
    @DisplayName("opa's real exit code and its stderr pass through unchanged")
    fun exitCodeAndStderr(@TempDir dir: Path) {
        val e = parseWith(fakeOpa(dir, "echo 'rego_parse_error: unexpected' >&2; exit 7"), dir)

        assertThat(e.exitCode).isEqualTo(7)
        assertThat(e.stderr).isEqualTo("rego_parse_error: unexpected\n")
        assertThat(e.message).isEqualTo(e.stderr)
    }

    @Test
    @DisplayName("opa reporting the error on stdout (as `opa eval --format json` does) keeps the stdout fallback and the real exit code")
    fun stdoutFallback(@TempDir dir: Path) {
        val e = parseWith(fakeOpa(dir, """echo '{"errors": [{"message": "boom"}]}'; exit 3"""), dir)

        assertThat(e.exitCode).isEqualTo(3)
        assertThat(e.stderr).contains("boom")
    }

    @Test
    @DisplayName("a silent failure is never an empty message: it says opa exited with the code")
    fun silentFailure(@TempDir dir: Path) {
        val e = parseWith(fakeOpa(dir, "exit 5"), dir)

        assertThat(e.exitCode).isEqualTo(5)
        assertThat(e.stderr).isEqualTo("opa exited with code 5")
    }

    @Test
    @DisplayName("an opa that does not finish in time is stopped and reported with exit code -1 and a message saying so")
    fun timeout(@TempDir dir: Path) {
        val saved = OpaRunner.timeoutSeconds
        OpaRunner.timeoutSeconds = 1
        try {
            val e = parseWith(fakeOpa(dir, "exec sleep 30"), dir)

            assertThat(e.exitCode).isEqualTo(-1).isEqualTo(OpaRunner.TIMED_OUT_EXIT_CODE)
            assertThat(e.stderr).contains("timed out after 1s")
        } finally {
            OpaRunner.timeoutSeconds = saved
        }
    }

    @Test
    @DisplayName("a binary that cannot be launched is OpaNotAvailableException with the IOException as its cause, and the message names the binary")
    fun launchFailureKeepsItsCause(@TempDir dir: Path) {
        val rego = dir.resolve("p.rego"); Files.writeString(rego, "package p\n")
        val missing = dir.resolve("no-such-binary")

        val e = withBinary(missing) { assertThrows(OpaNotAvailableException::class.java) { OpaRunner.parse(rego) } }

        assertThat(e.cause).isInstanceOf(java.io.IOException::class.java)
        assertThat(e.message).contains(missing.toString()).contains("OPA_BIN")
    }

    @Test
    @DisplayName("isAvailable stays a plain boolean: false, not an exception, for a binary that is missing or fails")
    fun isAvailableDoesNotThrow(@TempDir dir: Path) {
        assertThat(withBinary(dir.resolve("no-such-binary")) { OpaRunner.isAvailable() }).isFalse()
        assertThat(withBinary(fakeOpa(dir, "exit 1")) { OpaRunner.isAvailable() }).isFalse()
    }

    @Test
    @DisplayName("evalDataPath hands opa the query on stdin, never as an argument: Windows drops double quotes from arguments, which turned data[\"a\"] into data[a]")
    fun dataPathQueryTravelsOnStdin(@TempDir dir: Path) {
        val args = dir.resolve("args.txt"); val stdin = dir.resolve("stdin.txt")
        val fake = fakeOpa(dir, "echo \"$*\" > '$args'; cat > '$stdin'; echo '{}'")

        withBinary(fake) { OpaRunner.evalDataPath(listOf(dir), listOf("limits", "a \"b\"")) }

        assertThat(Files.readString(args)).contains("--stdin").doesNotContain("data[").doesNotContain("\"")
        assertThat(Files.readString(stdin).trim()).isEqualTo("data[\"limits\"][\"a \\\"b\\\"\"]")
    }
}
