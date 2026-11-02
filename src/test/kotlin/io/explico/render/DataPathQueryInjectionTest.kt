/**
 * Security review M1: `OpaRunner.evalDataPath` pasted bracket-string keys from a policy into the query text, so a hostile key could append a statement (and reach a builtin such as
 * `http.send`) during a render that is documented as never evaluating policy logic. Every segment must be ONE quoted key. Needs opa.
 */
package io.explico.render

import io.explico.opa.OpaRunner
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DataPathQueryInjectionTest {
    companion object {
        @JvmStatic @BeforeAll fun requireOpa() = assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
    }

    private fun data(dir: Path, json: String): Path = Files.writeString(dir.resolve("d.json"), json).let { dir }

    @Test
    @DisplayName("a key that carries a second statement is one undefined key, not a query")
    fun hostileKeyIsData(@TempDir tmp: Path) {
        val dir = data(tmp, """{"cfg": {}}""")
        // Unquoted, this query is `data.cfg; 1 == 1`, which opa evaluates to a value.
        assertThat(OpaRunner.evalDataPath(listOf(dir), listOf("cfg; 1 == 1"))).isNull()
        assertThat(OpaRunner.evalDataPath(listOf(dir), listOf("cfg\"]; 1 == 1; data[\"cfg"))).isNull()
    }

    @Test
    @DisplayName("a key with spaces, dots and quotes is looked up as written")
    fun oddKeysAreLooked(@TempDir tmp: Path) {
        val dir = data(tmp, """{"a b": {"c.d": 1, "e\"f": 2}}""")
        assertThat(OpaRunner.evalDataPath(listOf(dir), listOf("a b", "c.d"))).isEqualTo(JsonPrimitive(1))
        assertThat(OpaRunner.evalDataPath(listOf(dir), listOf("a b", "e\"f"))).isEqualTo(JsonPrimitive(2))
    }

    @Test
    @DisplayName("an ordinary path still resolves")
    fun ordinary(@TempDir tmp: Path) {
        val dir = data(tmp, """{"limits": {"max": 3}}""")
        assertThat(OpaRunner.evalDataPath(listOf(dir), listOf("limits", "max"))).isEqualTo(JsonPrimitive(3))
    }
}
