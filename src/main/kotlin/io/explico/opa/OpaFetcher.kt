/**
 * Downloads a pinned, checksum-verified `opa` build (spec §13.2's `--fetch-opa`) using only the
 * JDK's own `java.net.http` client -- no new dependency, no retry/proxy machinery. Never a guessed
 * URL or an unverified binary: the asset name and its companion `.sha256` file both come from the
 * real GitHub release, confirmed against the actual v1.19.0 release assets, not assumed.
 */
package io.explico.opa

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat

/** No release asset exists for the running JVM's OS/architecture combination. */
internal class UnsupportedPlatformException(message: String) : RuntimeException(message)

/** The downloaded binary's SHA-256 didn't match the release's published checksum. */
internal class ChecksumMismatchException(message: String) : RuntimeException(message)

internal object OpaFetcher {
    private const val RELEASES_BASE = "https://github.com/open-policy-agent/opa/releases/download"

    /**
     * Downloads `opa` [version] for the current platform into [cacheDir]/[version]/, verifies its
     * SHA-256 against the release's own `.sha256` file, marks it executable, and returns its path.
     * Returns the cached path directly (no re-download) if it's already there from a previous run.
     */
    fun fetch(version: String, cacheDir: Path): Path {
        val assetName = assetNameForCurrentPlatform()
        val binaryName = if (assetName.endsWith(".exe")) "opa.exe" else "opa"
        val cachedBinary = cacheDir.resolve(version).resolve(binaryName)
        if (Files.exists(cachedBinary)) return cachedBinary

        // GitHub release asset downloads redirect (302) to a CDN URL -- HttpClient.newHttpClient()'s
        // default redirect policy is NEVER, confirmed empirically (a real fetch failed with a raw
        // HTTP 302 until this was added), not a defensive guess.
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        val binaryBytes = get(client, "$RELEASES_BASE/v$version/$assetName")
        val checksumText = String(get(client, "$RELEASES_BASE/v$version/$assetName.sha256"))
        val expectedHash = checksumText.trim().substringBefore(' ').substringBefore('\t')

        val actualHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(binaryBytes))
        if (!actualHash.equals(expectedHash, ignoreCase = true)) {
            throw ChecksumMismatchException(
                "Downloaded opa binary checksum mismatch for $assetName: expected $expectedHash, got $actualHash -- refusing to use it."
            )
        }

        // Write to a sibling temp file and move it into place atomically -- a process killed
        // mid-write must never leave a truncated file sitting at the trusted cache path, which
        // the early-return above treats as already-verified with no re-check.
        Files.createDirectories(cachedBinary.parent)
        val tempFile = Files.createTempFile(cachedBinary.parent, "opa-download-", ".tmp")
        try {
            Files.write(tempFile, binaryBytes)
            tempFile.toFile().setExecutable(true)
            Files.move(tempFile, cachedBinary, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tempFile)
        }
        return cachedBinary
    }

    private fun get(client: HttpClient, url: String): ByteArray {
        val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() != 200) throw IOException("GET $url failed: HTTP ${response.statusCode()}")
        return response.body()
    }

    /** `opa_<os>_<arch>[.exe]`, matching the real asset names in every opa GitHub release (e.g. `opa_darwin_arm64`). */
    internal fun assetNameForCurrentPlatform(
        osName: String = System.getProperty("os.name"),
        archName: String = System.getProperty("os.arch"),
    ): String {
        val lowerOs = osName.lowercase()
        val os = when {
            lowerOs.contains("mac") || lowerOs.contains("darwin") -> "darwin"
            lowerOs.contains("linux") -> "linux"
            lowerOs.contains("windows") -> "windows"
            else -> throw UnsupportedPlatformException("Unsupported OS for --fetch-opa: '$osName'")
        }
        val lowerArch = archName.lowercase()
        val arch = when {
            lowerArch.contains("aarch64") || lowerArch.contains("arm64") -> "arm64"
            lowerArch.contains("amd64") || lowerArch.contains("x86_64") -> "amd64"
            else -> throw UnsupportedPlatformException("Unsupported architecture for --fetch-opa: '$archName'")
        }
        if (os == "windows" && arch == "arm64") {
            throw UnsupportedPlatformException("opa has no windows/arm64 release; use --fetch-opa on a different platform or install opa manually")
        }
        return "opa_${os}_$arch" + if (os == "windows") ".exe" else ""
    }
}
