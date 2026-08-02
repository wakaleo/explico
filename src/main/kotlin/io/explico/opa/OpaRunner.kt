/**
 * Runs the external `opa` binary; explico never parses Rego itself (spec §4).
 */
package io.explico.opa

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** A non-zero exit from an `opa` invocation. [stderr] is opa's own stderr text, passed through verbatim. */
internal class OpaInvocationException(val stderr: String) : RuntimeException(stderr)

/** `opa` is missing or its version is incompatible (spec §4: requires major version 1). */
internal class OpaUnavailableException(message: String) : RuntimeException(message)

internal object OpaRunner {
    private const val TIMEOUT_SECONDS = 30L
    private const val REQUIRED_MAJOR_VERSION = 1

    /** Resolves the `opa` binary: the `OPA_BIN` environment variable if set, else `opa` on `PATH`. */
    fun resolveBinary(): String = System.getenv("OPA_BIN") ?: "opa"

    /** True if the resolved binary runs and reports major version 1. */
    fun isAvailable(): Boolean = detectedMajorVersion() == REQUIRED_MAJOR_VERSION

    /** Throws [OpaUnavailableException] with an actionable message if [isAvailable] is false. */
    fun requireCompatibleVersion() {
        if (!isAvailable()) {
            throw OpaUnavailableException(
                "explico requires opa version $REQUIRED_MAJOR_VERSION.x, but it could not be run " +
                    "(resolved binary: '${resolveBinary()}'). Install opa $REQUIRED_MAJOR_VERSION.x " +
                    "and ensure it is on PATH, or set the OPA_BIN environment variable to its path."
            )
        }
    }

    /** Runs `opa parse --format json --json-include locations <file>` and decodes the module AST. */
    fun parse(file: Path): OpaModule {
        val stdout = run("parse", "--format", "json", "--json-include", "locations", file.toString())
        return opaJson.decodeFromString(OpaModule.serializer(), stdout)
    }

    /** Runs `opa inspect --annotations --format json <dir>` and decodes the METADATA annotations. */
    fun inspect(dir: Path): OpaInspectResult {
        val stdout = run("inspect", "--annotations", "--format", "json", dir.toString())
        return opaJson.decodeFromString(OpaInspectResult.serializer(), stdout)
    }

    /**
     * Runs `opa eval --format json --input <inputFile> [--data <dir>]... <query>` (spec §6.7) and
     * returns the query's evaluated value (e.g. `data.<package>`'s rule-name -> value object), or
     * an empty object if the query was undefined.
     */
    fun eval(inputFile: Path, dataDirs: List<Path>, query: String): JsonElement {
        val args = buildList {
            add("eval"); add("--format"); add("json"); add("--input"); add(inputFile.toString())
            dataDirs.forEach { add("--data"); add(it.toString()) }
            add(query)
        }
        val stdout = run(*args.toTypedArray())
        val decoded = opaJson.decodeFromString(OpaEvalResult.serializer(), stdout)
        return decoded.result.firstOrNull()?.expressions?.firstOrNull()?.value ?: JsonObject(emptyMap())
    }

    private fun detectedMajorVersion(): Int? {
        val output = try {
            run("version")
        } catch (e: OpaInvocationException) {
            return null
        } catch (e: java.io.IOException) {
            return null
        }
        val versionLine = output.lineSequence().firstOrNull { it.startsWith("Version:") } ?: return null
        return versionLine.substringAfter("Version:").trim().substringBefore('.').toIntOrNull()
    }

    /** Invokes `opa` with [args], capturing stdout/stderr separately under a 30s timeout. */
    private fun run(vararg args: String): String {
        val command = listOf(resolveBinary()) + args
        val process = ProcessBuilder(command).start()

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutReader = Thread { stdout.append(process.inputStream.bufferedReader().readText()) }
        val stderrReader = Thread { stderr.append(process.errorStream.bufferedReader().readText()) }
        stdoutReader.start()
        stderrReader.start()

        val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            stdoutReader.join(TimeUnit.SECONDS.toMillis(1))
            stderrReader.join(TimeUnit.SECONDS.toMillis(1))
            throw OpaInvocationException(
                "opa invocation timed out after ${TIMEOUT_SECONDS}s: ${command.joinToString(" ")}"
            )
        }
        stdoutReader.join()
        stderrReader.join()

        if (process.exitValue() != 0) {
            throw OpaInvocationException(stderr.toString())
        }
        return stdout.toString()
    }
}
