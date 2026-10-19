/**
 * Unit tests for [PolicyDigest] (spec §14.40): a SHA-256 over a policy's `.rego` files, used to tie every
 * generated document to an exact policy version. Pure filesystem operations -- no opa needed.
 */
package io.explico.render

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PolicyDigestTest {

    private fun write(dir: Path, relativePath: String, content: String) {
        val file = dir.resolve(relativePath)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    @Test
    fun aSixtyFourCharacterLowercaseHexDigestIsReturned(@TempDir dir: Path) {
        write(dir, "p.rego", "package p\n")
        val digest = PolicyDigest.of(dir)
        assertThat(digest).hasSize(64).matches("[0-9a-f]{64}")
    }

    @Test
    fun theSamePoliciesAlwaysProduceTheSameDigest(@TempDir dir: Path) {
        write(dir, "a/p.rego", "package a\n\ndeny if input.x == 1\n")
        write(dir, "b/q.rego", "package b\n\nallow if input.y == 2\n")
        assertThat(PolicyDigest.of(dir)).isEqualTo(PolicyDigest.of(dir))
    }

    @Test
    fun theFileOrderOnDiskDoesNotMatterOnlyContentAndRelativePath(@TempDir dirA: Path, @TempDir dirB: Path) {
        write(dirA, "a/p.rego", "package a\n")
        write(dirA, "b/q.rego", "package b\n")
        // Written in the opposite order on disk -- the digest must still agree (sorted internally).
        write(dirB, "b/q.rego", "package b\n")
        write(dirB, "a/p.rego", "package a\n")
        assertThat(PolicyDigest.of(dirA)).isEqualTo(PolicyDigest.of(dirB))
    }

    @Test
    fun changingAnyByteOfAnyFileChangesTheDigest(@TempDir dir: Path) {
        write(dir, "p.rego", "package p\n\n# a comment\n")
        val before = PolicyDigest.of(dir)
        write(dir, "p.rego", "package p\n\n# a comment, edited\n")
        assertThat(PolicyDigest.of(dir)).isNotEqualTo(before)
    }

    @Test
    fun renamingAFileChangesTheDigestEvenWithIdenticalContent(@TempDir dir: Path) {
        write(dir, "p.rego", "package p\n")
        val before = PolicyDigest.of(dir)
        Files.delete(dir.resolve("p.rego"))
        write(dir, "renamed.rego", "package p\n")
        assertThat(PolicyDigest.of(dir)).isNotEqualTo(before)
    }

    @Test
    fun windowsLineEndingsHashTheSameAsUnixLineEndings(@TempDir dirUnix: Path, @TempDir dirWindows: Path) {
        write(dirUnix, "p.rego", "package p\n\ndeny if {\n\tinput.x == 1\n}\n")
        write(dirWindows, "p.rego", "package p\r\n\r\ndeny if {\r\n\tinput.x == 1\r\n}\r\n")
        assertThat(PolicyDigest.of(dirUnix)).isEqualTo(PolicyDigest.of(dirWindows))
    }

    @Test
    fun movingTheSameBytesFromContentIntoThePathChangesTheDigest(@TempDir dirA: Path, @TempDir dirB: Path) {
        // A different (path, content) split of similar bytes is a different policy set. (The length-prefix framing
        // itself is by construction: a .rego suffix makes a genuinely ambiguous concatenation impossible to build.)
        write(dirA, "ab.rego", "c")
        write(dirB, "a.rego", "bc")
        assertThat(PolicyDigest.of(dirA)).isNotEqualTo(PolicyDigest.of(dirB))
    }

    @Test
    fun onlyRegoFilesContribute(@TempDir dir: Path) {
        write(dir, "p.rego", "package p\n")
        val before = PolicyDigest.of(dir)
        write(dir, "README.md", "unrelated documentation\n")
        write(dir, "data.json", "{}")
        assertThat(PolicyDigest.of(dir)).isEqualTo(before)
    }

    @Test
    fun anEmptyPolicyDirectoryHasAStableDigest(@TempDir dir: Path) {
        assertThat(PolicyDigest.of(dir)).isEqualTo(PolicyDigest.of(dir)).hasSize(64)
    }

    @Test
    @DisplayName("CHALLENGE: a .rego file whose bytes are not valid UTF-8 does not crash the digest (or the whole render)")
    fun invalidUtf8BytesDoNotCrash(@TempDir dir: Path) {
        // 0xFF 0xFE is not a valid UTF-8 sequence; Files.readString() would throw on this (confirmed: it threw
        // before this test drove reading raw bytes instead of a decoded String) -- digesting one malformed file
        // must not abort the render of every other file the way Files.readString() would.
        Files.write(dir.resolve("p.rego"), byteArrayOf(0x70, 0x61, 0x63, 0x6b.toByte(), 0xFF.toByte(), 0xFE.toByte(), 0x0A))
        assertThat(PolicyDigest.of(dir)).hasSize(64)
    }

    @Test
    @DisplayName("CHALLENGE: only CRLF is normalised -- a lone CR (old Mac line endings) is left as its own byte")
    fun onlyCrlfIsNormalisedNotABareCr(@TempDir dirCr: Path, @TempDir dirLf: Path) {
        write(dirCr, "p.rego", "package p\rdeny if true\r")
        write(dirLf, "p.rego", "package p\ndeny if true\n")
        assertThat(PolicyDigest.of(dirCr)).isNotEqualTo(PolicyDigest.of(dirLf))
    }
}
