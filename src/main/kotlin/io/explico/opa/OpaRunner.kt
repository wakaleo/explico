/**
 * Runs the external `opa` binary; explico never parses Rego itself (spec §4).
 */
package io.explico.opa

import java.util.concurrent.TimeUnit

internal object OpaRunner {
    private const val TIMEOUT_SECONDS = 30L

    /** Resolves the `opa` binary: `OPA_BIN` env var if set, else `opa` on `PATH`. */
    fun resolveBinary(): String = System.getenv("OPA_BIN") ?: "opa"

    /** True if the resolved binary runs and reports major version 1 (spec §4). */
    fun isAvailable(): Boolean {
        val output = runVersionCheck() ?: return false
        val versionLine = output.lineSequence().firstOrNull { it.startsWith("Version:") } ?: return false
        val majorVersion = versionLine.substringAfter("Version:").trim().substringBefore('.').toIntOrNull()
        return majorVersion == 1
    }

    private fun runVersionCheck(): String? = try {
        val process = ProcessBuilder(resolveBinary(), "version").start()
        val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            null
        } else if (process.exitValue() != 0) {
            null
        } else {
            process.inputStream.bufferedReader().readText()
        }
    } catch (e: java.io.IOException) {
        null
    }
}
