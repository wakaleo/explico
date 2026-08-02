/**
 * Spawns the real CLI as a separate OS process (spec §8.2's exit codes are a process-boundary
 * contract, not something an in-process function call can verify). Reuses the test JVM's own
 * classpath -- Gradle's `Test` task launches with `-cp`, so `java.class.path` already has the
 * compiled classes and every runtime dependency.
 */
package io.explico.cli

import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal data class CliResult(val exitCode: Int, val stdout: String, val stderr: String)

internal fun runCli(args: List<String>, workingDir: Path, opaBin: String? = null): CliResult {
    val javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString()
    val classpath = System.getProperty("java.class.path")
    val command = listOf(javaBin, "-cp", classpath, "io.explico.cli.MainKt") + args

    val builder = ProcessBuilder(command).directory(workingDir.toFile())
    if (opaBin != null) builder.environment()["OPA_BIN"] = opaBin
    val process = builder.start()

    val stdout = process.inputStream.bufferedReader()
    val stderr = process.errorStream.bufferedReader()
    val stdoutText = StringBuilder()
    val stderrText = StringBuilder()
    val stdoutThread = Thread { stdoutText.append(stdout.readText()) }
    val stderrThread = Thread { stderrText.append(stderr.readText()) }
    stdoutThread.start()
    stderrThread.start()

    val finished = process.waitFor(30, TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        error("CLI process timed out: ${command.joinToString(" ")}")
    }
    stdoutThread.join()
    stderrThread.join()
    return CliResult(process.exitValue(), stdoutText.toString(), stderrText.toString())
}
