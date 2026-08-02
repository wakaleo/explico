/**
 * Process-level tests for `explico demo` (spec §13.2): the real built shadow jar invoked as a
 * subprocess, one temp working directory per test so "refuse if explico-demo/ exists" (exit 1) and
 * the real extraction+render can both be proven without interfering with each other. Requires
 * `opa` on PATH, same pattern as ShadowJarProcessTest/CliProcessTest.
 *
 * `--fetch-opa`'s real network/download path is deliberately NOT exercised here (it would make
 * `check` network-dependent and slow) -- it was manually verified end-to-end this session: a real
 * download from the real GitHub release, a real SHA-256 checksum match, and a successful demo run
 * using the downloaded binary. `OpaFetcherTest` covers the pure, testable platform-mapping logic.
 */
package io.explico.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.TimeUnit

class DemoCommandProcessTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(io.explico.opa.OpaRunner.isAvailable(), "opa binary not on PATH")
        }
    }

    private val shadowJarPath: Path by lazy {
        val version = javaClass.getResourceAsStream("/explico-version.properties")!!.use { stream ->
            Properties().apply { load(stream) }.getProperty("version")
        }
        val jar = Path.of("build/libs/explico-$version.jar").toAbsolutePath()
        check(Files.exists(jar)) { "Shadow jar not found at $jar -- run ./gradlew shadowJar first" }
        jar
    }

    private fun runDemo(args: List<String>, workingDir: Path, opaBin: String? = null): CliResult {
        val javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val command = listOf(javaBin, "-jar", shadowJarPath.toString(), "demo") + args
        val builder = ProcessBuilder(command).directory(workingDir.toFile())
        if (opaBin != null) builder.environment()["OPA_BIN"] = opaBin
        val process = builder.start()

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutThread = Thread { stdout.append(process.inputStream.bufferedReader().readText()) }
        val stderrThread = Thread { stderr.append(process.errorStream.bufferedReader().readText()) }
        stdoutThread.start()
        stderrThread.start()

        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            error("demo process timed out: ${command.joinToString(" ")}")
        }
        stdoutThread.join()
        stderrThread.join()
        return CliResult(process.exitValue(), stdout.toString(), stderr.toString())
    }

    @Test
    fun demoExtractsAndRendersWithATwoLinePointer(@TempDir tempDir: Path) {
        val result = runDemo(emptyList(), tempDir)

        assertThat(result.exitCode).isEqualTo(0)
        val lines = result.stdout.trim().lines()
        assertThat(lines).hasSize(2)
        assertThat(lines[0]).contains("Rendered").contains("documents").contains("%")
        assertThat(lines[1]).startsWith("Open ").contains("release-approvals.md")

        val demoDir = tempDir.resolve("explico-demo")
        assertThat(Files.exists(demoDir.resolve("policies/approvals/change_approval.rego"))).isTrue()
        assertThat(Files.exists(demoDir.resolve("examples/01-approved-standard-release.json"))).isTrue()
        assertThat(Files.exists(demoDir.resolve("data/release/data.json"))).isTrue()
        val approvalsDoc = Files.readString(demoDir.resolve("docs/release-approvals.md"))
        assertThat(approvalsDoc).isEqualTo(
            Files.readString(Path.of("src/test/resources/acceptance/expected/release-approvals.md")),
        )
    }

    @Test
    fun demoRefusesToOverwriteAnExistingDirectory(@TempDir tempDir: Path) {
        Files.createDirectories(tempDir.resolve("explico-demo"))
        Files.writeString(tempDir.resolve("explico-demo/user-file.txt"), "do not touch")

        val result = runDemo(emptyList(), tempDir)

        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.stderr).contains("explico-demo").contains("already exists")
        assertThat(Files.readString(tempDir.resolve("explico-demo/user-file.txt"))).isEqualTo("do not touch")
        assertThat(Files.exists(tempDir.resolve("explico-demo/policies"))).isFalse()
    }

    @Test
    fun demoMissingOpaExitsThreeNamingTheExactPinnedVersion(@TempDir tempDir: Path) {
        val result = runDemo(emptyList(), tempDir, opaBin = "/nonexistent/opa-binary-that-does-not-exist")

        assertThat(result.exitCode).isEqualTo(3)
        assertThat(result.stderr).contains("opa 1.19.0")
        assertThat(result.stderr).contains("--fetch-opa")
        assertThat(Files.exists(tempDir.resolve("explico-demo"))).isFalse()
    }
}
