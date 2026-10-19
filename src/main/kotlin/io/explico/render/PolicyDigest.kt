/**
 * A content digest over a policy's `.rego` files (spec §14.40): ties every generated document to an exact policy
 * version, so a compliance reader can confirm the documentation in hand matches the policy that was actually
 * deployed. Deliberately digests only the `.rego` source -- never the examples, the data document, or explico's
 * own version -- so the same policies always produce the same digest, and output does not change on every
 * explico release just because the tool was upgraded.
 */
package io.explico.render

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.stream.Collectors

internal object PolicyDigest {

    /** A 64-character lowercase hex SHA-256 over every `.rego` file under [policyDir]. */
    fun of(policyDir: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val files = Files.walk(policyDir).use { paths ->
            paths.filter { it.toString().endsWith(".rego") }.collect(Collectors.toList())
        }
        // Sorted by '/'-separated RELATIVE path, not disk order: the digest must be the same regardless of which
        // order the filesystem happens to list files in, and the same on Windows (which uses '\') as on POSIX.
        val sorted = files
            .map { policyDir.relativize(it).toString().replace(java.io.File.separatorChar, '/') to it }
            .sortedBy { it.first }
        for ((relativePath, file) in sorted) {
            // Raw bytes, not Files.readString(): a .rego file saved in an encoding other than UTF-8 (or simply
            // corrupted) must not make digest computation -- and therefore the WHOLE render -- throw, the same
            // "never abort over one file" posture AstMapper's own per-rule isolation already takes. CRLF -> LF
            // so a Windows checkout of the same policies hashes identically to a Unix one; nothing else about
            // the content is normalised (a lone CR, or a real text change, still changes the digest).
            val content = normalizeCrlf(Files.readAllBytes(file))
            val pathBytes = relativePath.toByteArray(Charsets.UTF_8)
            // Each field is length-prefixed (not separated by a delimiter byte) so a path and a content byte
            // stream can never be split differently and still collide -- e.g. path "ab" + content "c" cannot hash
            // the same as path "a" + content "bc".
            digest.update(pathBytes.size.toLengthPrefix())
            digest.update(pathBytes)
            digest.update(content.size.toLengthPrefix())
            digest.update(content)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun Int.toLengthPrefix(): ByteArray = ByteBuffer.allocate(8).putLong(this.toLong()).array()

    /** Byte-level CR(0x0D) LF(0x0A) -> LF, with no charset decoding -- correct for any encoding, and for bytes that aren't text at all. */
    private fun normalizeCrlf(bytes: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            if (bytes[i] == CR && i + 1 < bytes.size && bytes[i + 1] == LF) {
                out.write(LF.toInt())
                i += 2
            } else {
                out.write(bytes[i].toInt())
                i += 1
            }
        }
        return out.toByteArray()
    }

    private const val CR: Byte = 0x0D
    private const val LF: Byte = 0x0A
}
