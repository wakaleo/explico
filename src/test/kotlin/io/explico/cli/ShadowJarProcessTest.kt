/**
 * Process-level test for the single runnable shadow jar (spec §13.1): `java -jar <jar> ...` as a
 * real, separate subprocess -- not `java -cp <classpath> MainKt` (CliProcessTest's approach),
 * which proves the classes/dependencies resolve correctly on a build classpath but says nothing
 * about whether the jar shadow actually produces is self-contained and runnable on its own.
 * Requires `opa` on PATH, same pattern as CliProcessTest. `./gradlew test` always builds the jar
 * first (`tasks.test { dependsOn(tasks.shadowJar) }` in build.gradle.kts).
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

class ShadowJarProcessTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(io.explico.opa.OpaRunner.isAvailable(), "opa binary not on PATH")
        }
    }

    private val policiesDir = Path.of("src/test/resources/acceptance/policies").toAbsolutePath()

    private val shadowJarPath: Path by lazy {
        val version = javaClass.getResourceAsStream("/explico-version.properties")!!.use { stream ->
            Properties().apply { load(stream) }.getProperty("version")
        }
        val jar = Path.of("build/libs/explico-$version.jar").toAbsolutePath()
        check(Files.exists(jar)) { "Shadow jar not found at $jar -- run ./gradlew shadowJar first" }
        jar
    }

    private fun runJar(args: List<String>, workingDir: Path, opaBin: String? = null): CliResult {
        val javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val command = listOf(javaBin, "-jar", shadowJarPath.toString()) + args
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
            error("Shadow jar process timed out: ${command.joinToString(" ")}")
        }
        stdoutThread.join()
        stderrThread.join()
        return CliResult(process.exitValue(), stdout.toString(), stderr.toString())
    }

    @Test
    fun javaJarVersionSucceedsWithNoStackTraceOrNativeAccessWarning() {
        val expectedVersion = javaClass.getResourceAsStream("/explico-version.properties")!!.use { stream ->
            Properties().apply { load(stream) }.getProperty("version")
        }
        val result = runJar(listOf("version"), Path.of("."))

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.stdout.trim()).isEqualTo("explico $expectedVersion")
        // The manifest's Enable-Native-Access attribute (build.gradle.kts) is specifically what
        // makes `java -jar` clean here -- applicationDefaultJvmArgs only covers the installed
        // distribution's generated start script, not a standalone jar launch.
        assertThat(result.stderr).doesNotContain("restricted method").doesNotContain("Exception")
    }

    @Test
    fun javaJarRenderSucceedsAgainstTheAcceptancePack(@TempDir tempDir: Path) {
        val out = tempDir.resolve("out")
        val result = runJar(listOf("render", policiesDir.toString(), "--out", out.toString()), tempDir)

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.stdout).contains("Rendering coverage:")
        assertThat(Files.exists(out.resolve("index.md"))).isTrue()
    }

    @Test
    fun javaJarMissingOpaExitsThreeWithAnActionableMessage(@TempDir tempDir: Path) {
        val out = tempDir.resolve("out")
        val result = runJar(
            listOf("render", policiesDir.toString(), "--out", out.toString()),
            tempDir,
            opaBin = "/nonexistent/opa-binary-that-does-not-exist",
        )

        assertThat(result.exitCode).isEqualTo(3)
        assertThat(result.stderr).contains("opa").contains("1.x")
    }
}
