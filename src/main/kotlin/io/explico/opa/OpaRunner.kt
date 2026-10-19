/**
 * Runs the external `opa` binary; explico never parses Rego itself (spec §4).
 */
package io.explico.opa

import io.explico.model.RegoVersion
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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

    /**
     * Set once per process by `explico demo --fetch-opa` (spec §13.2) after a successful download,
     * so the freshly-fetched binary is used without needing to mutate `OPA_BIN` -- which, being an
     * environment variable, can't be changed for an already-running JVM. Checked before `OPA_BIN`.
     */
    var binaryOverride: Path? = null

    /** Resolves the `opa` binary: [binaryOverride] if set, else the `OPA_BIN` environment variable, else `opa` on `PATH`. */
    fun resolveBinary(): String = binaryOverride?.toString() ?: System.getenv("OPA_BIN") ?: "opa"

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
    fun parse(file: Path, regoVersion: RegoVersion = RegoVersion.V1): OpaModule {
        val stdout = run(*withVersion(regoVersion, "parse", "--format", "json", "--json-include", "locations", file.toString()))
        return opaJson.decodeFromString(OpaModule.serializer(), stdout)
    }

    /** Runs `opa inspect --annotations --format json <dir>` and decodes the METADATA annotations. */
    fun inspect(dir: Path, regoVersion: RegoVersion = RegoVersion.V1): OpaInspectResult {
        val stdout = run(*withVersion(regoVersion, "inspect", "--annotations", "--format", "json", dir.toString()))
        return opaJson.decodeFromString(OpaInspectResult.serializer(), stdout)
    }

    /**
     * Runs `opa eval --format json --input <inputFile> [--data <dir>]... <query>` (spec §6.7) and
     * returns the query's evaluated value (e.g. `data.<package>`'s rule-name -> value object), or
     * an empty object if the query was undefined.
     */
    fun eval(inputFile: Path, dataDirs: List<Path>, query: String, regoVersion: RegoVersion = RegoVersion.V1): JsonElement {
        val args = buildList {
            add("eval"); add("--format"); add("json"); add("--input"); add(inputFile.toString())
            dataDirs.forEach { add("--data"); add(dataArgument(it)) }
            add(query)
        }
        val stdout = run(*withVersion(regoVersion, *args.toTypedArray()))
        val decoded = opaJson.decodeFromString(OpaEvalResult.serializer(), stdout)
        return decoded.result.firstOrNull()?.expressions?.firstOrNull()?.value ?: JsonObject(emptyMap())
    }

    /**
     * Runs `opa eval --format json [--data <dir>]... data.<path>` (spec §14.41: reference data values) -- no
     * `--input`, since a plain data-document query never reads `input`. Returns null if [path] is undefined in
     * the supplied data (opa's own JSON then carries no `result` entry at all) -- never a guessed default, unlike
     * [eval]'s `data.<package>` queries, which are always defined for a loaded package and so can safely default
     * to an empty object.
     *
     * CHALLENGE finding: checks for the `result`/`expressions` ENTRY, not for a non-null `.value` -- a document
     * whose real value is JSON `null` decodes [OpaEvalExpression.value] to Kotlin null too (confirmed empirically:
     * kotlinx-serialization doesn't distinguish "the JSON literal `null`" from "the field was absent" for a
     * nullable [JsonElement] property), so chaining straight through `?.value` would have silently reported a
     * genuinely defined `null` value as "undefined" -- two different things this function must tell apart.
     */
    fun evalDataPath(dataDirs: List<Path>, path: List<String>, regoVersion: RegoVersion = RegoVersion.V1): JsonElement? {
        val query = "data." + path.joinToString(".")
        val args = buildList {
            add("eval"); add("--format"); add("json")
            dataDirs.forEach { add("--data"); add(dataArgument(it)) }
            add(query)
        }
        val stdout = run(*withVersion(regoVersion, *args.toTypedArray()))
        val decoded = opaJson.decodeFromString(OpaEvalResult.serializer(), stdout)
        val expression = decoded.result.firstOrNull()?.expressions?.firstOrNull() ?: return null
        return expression.value ?: JsonNull
    }

    /** Appends `--v0-compatible` for [RegoVersion.V0]; the flag is accepted by `parse`, `inspect` and `eval` alike. */
    private fun withVersion(regoVersion: RegoVersion, vararg args: String): Array<String> =
        if (regoVersion == RegoVersion.V0) arrayOf(*args, "--v0-compatible") else arrayOf(*args)

    /**
     * The value for `opa eval --data`. opa treats everything before the first ':' as a document PREFIX (`prefix:path`), so a
     * path containing a colon -- every Windows path with a drive letter (`D:\a\x` is read as prefix `D`, path `\a\x`), or a
     * POSIX directory with a colon in its name -- is mis-read. A `file://` URL is passed through opa's splitter unchanged (its
     * first ':' is the one in `://`) and opa converts it back to a native path, so such a path is given as one. A path with no
     * colon is passed exactly as before.
     */
    internal fun dataArgument(path: Path): String =
        if (path.toString().contains(':')) path.toAbsolutePath().toUri().toString() else path.toString()

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
            // `opa eval --format json` reports an evaluation error as JSON on STDOUT and leaves stderr empty, so fall back
            // to stdout, and finally to the bare exit code: never an empty message.
            val message = stderr.toString().ifBlank { stdout.toString().trim() }.ifBlank { "opa exited with code ${process.exitValue()}" }
            throw OpaInvocationException(message)
        }
        return stdout.toString()
    }
}
