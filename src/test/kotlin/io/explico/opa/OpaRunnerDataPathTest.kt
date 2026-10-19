/**
 * `opa eval --data <path>` treats everything before the first ':' as a document PREFIX (`prefix:path`), so a path containing a
 * colon -- every Windows path with a drive letter (`D:\a\...` became prefix `D` + path `\a\...`), or a POSIX directory with a
 * colon in its name -- was silently mis-read. Found by CI on Windows: every worked example failed for an absolute policy
 * directory. Reproduced here on POSIX with a directory whose name contains a colon. Needs opa.
 */
package io.explico.opa

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class OpaRunnerDataPathTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
        }
    }

    private fun policyIn(dir: Path) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("p.rego"), "package t\n\nimport rego.v1\n\nanswer := 42\n")
    }

    @Test
    fun aDataDirectoryWhoseNameContainsAColonIsStillLoadedAsItself(@TempDir tmp: Path) {
        assumeFalse(System.getProperty("os.name").lowercase().contains("windows"), "a colon is not valid in a Windows file name")
        val dir = tmp.resolve("pol:icies")
        policyIn(dir)
        val input = Files.writeString(tmp.resolve("in.json"), "{}")

        val value = OpaRunner.eval(input, listOf(dir), "data.t")

        assertThat(value.toString()).contains("\"answer\":42")
    }

    @Test
    fun aPlainPathIsPassedThroughUntouched(@TempDir tmp: Path) {
        val dir = tmp.resolve("policies")
        policyIn(dir)
        assertThat(OpaRunner.dataArgument(dir)).isEqualTo(dir.toString())
    }

    @Test
    fun aPathWithAColonBecomesAFileUrlSoOpaDoesNotReadAPrefixIntoIt(@TempDir tmp: Path) {
        assumeFalse(System.getProperty("os.name").lowercase().contains("windows"), "a colon is not valid in a Windows file name")
        val dir = tmp.resolve("pol:icies")
        policyIn(dir)
        val argument = OpaRunner.dataArgument(dir)
        assertThat(argument).startsWith("file:///").contains("pol:icies")
        // a file URL is passed through opa's prefix splitter unchanged: its first ':' is the one in "://"
        assertThat(argument.indexOf("://")).isEqualTo(argument.indexOf(':'))
    }
}
