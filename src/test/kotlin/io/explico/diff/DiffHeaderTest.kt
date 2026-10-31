/**
 * Unit tests for the diff report header (spec §14.76): the `**Compared**` block in the Markdown and the `compared` object in the JSON, for every label combination,
 * the "same policies" marker, and an absent header leaving the report untouched. Hand-built, no opa.
 */
package io.explico.diff

import io.explico.model.PolicySet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class DiffHeaderTest {

    private val a = "sha256:" + "a".repeat(64)
    private val b = "sha256:" + "b".repeat(64)
    private val empty = PolicySet(emptyList())

    private fun markdown(header: DiffHeader?) = DiffRenderer.render(emptyList(), empty, empty, header = header)
    private fun json(header: DiffHeader?) = Json.parseToJsonElement(DiffJson.render(emptyList(), header = header)).jsonObject

    @Test
    @DisplayName("the block sits between the disclaimer and ## Summary: bold heading, two bullets, blank lines around")
    fun placement() {
        val lines = markdown(DiffHeader(a, b, null, null)).lines()
        val at = lines.indexOf("**Compared**")

        assertThat(lines[at - 1]).isEmpty()
        assertThat(lines[at - 2]).startsWith("*This report")
        assertThat(lines.subList(at, at + 5)).containsExactly("**Compared**", "", "- Old: `$a`", "- New: `$b`", "")
        assertThat(lines[at + 5]).isEqualTo("## Summary")
    }

    @Test
    @DisplayName("labels go in parentheses after Old / New, each independently")
    fun labels() {
        assertThat(markdown(DiffHeader(a, b, "v1", "v2")).lines()).contains("- Old (v1): `$a`", "- New (v2): `$b`")
        assertThat(markdown(DiffHeader(a, b, "v1", null)).lines()).contains("- Old (v1): `$a`", "- New: `$b`")
        assertThat(markdown(DiffHeader(a, b, null, "v2")).lines()).contains("- Old: `$a`", "- New (v2): `$b`")
    }

    @Test
    @DisplayName("equal digests: the New line says the policies are the same as Old, with or without labels; different digests never do")
    fun samePolicies() {
        assertThat(markdown(DiffHeader(a, a, null, null)).lines()).contains("- New: `$a` (same policies as Old)")
        assertThat(markdown(DiffHeader(a, a, "v1", "v1b")).lines()).contains("- New (v1b): `$a` (same policies as Old)")
        assertThat(markdown(DiffHeader(a, b, null, null))).doesNotContain("same policies")
    }

    @Test
    @DisplayName("no header: the report has no block and is exactly the default rendering")
    fun absent() {
        assertThat(markdown(null)).doesNotContain("Compared").isEqualTo(DiffRenderer.render(emptyList(), empty, empty))
    }

    @Test
    @DisplayName("a unicode label is shown verbatim")
    fun unicode() {
        assertThat(markdown(DiffHeader(a, b, "版本 é", null)).lines()).contains("- Old (版本 é): `$a`")
    }

    @Test
    @DisplayName("JSON: compared.old / compared.new with the digest, and a label only when one was given; absent without a header")
    fun jsonCompared() {
        val compared = json(DiffHeader(a, b, "v1", null)).getValue("compared").jsonObject
        val old = compared.getValue("old").jsonObject
        val new = compared.getValue("new").jsonObject

        assertThat(old.getValue("digest").jsonPrimitive.content).isEqualTo(a)
        assertThat(old.getValue("label").jsonPrimitive.content).isEqualTo("v1")
        assertThat(new.getValue("digest").jsonPrimitive.content).isEqualTo(b)
        assertThat(new).doesNotContainKey("label")
        assertThat(json(null)).doesNotContainKey("compared")
    }
}
