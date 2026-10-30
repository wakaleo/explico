/**
 * Spec §14.64: the set of documentation properties a control must have, as the caller names them. Pure; no opa.
 */
package io.explico.render

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class RequiredSetTest {

    @Test
    @DisplayName("null is description + frameworks, the behaviour of every earlier version")
    fun defaultSet() {
        assertThat(RequiredSet.of(null, examplesSupplied = false).names).containsExactly("description", "frameworks")
        assertThat(RequiredSet.DEFAULT.names).containsExactly("description", "frameworks")
    }

    @Test
    @DisplayName("names come out in canonical order whatever order they were given in, duplicates collapsed")
    fun canonicalOrder() {
        assertThat(RequiredSet.of(listOf("verdict", "title", "frameworks", "title", "fixture", "description"), examplesSupplied = true).names)
            .containsExactly("title", "description", "frameworks", "fixture", "verdict")
    }

    @Test
    @DisplayName("an empty list requires nothing")
    fun empty() {
        assertThat(RequiredSet.of(emptyList(), examplesSupplied = false).names).isEmpty()
    }

    @Test
    @DisplayName("contains answers by name")
    fun contains() {
        val set = RequiredSet.of(listOf("title"), examplesSupplied = false)
        assertThat("title" in set).isTrue()
        assertThat("description" in set).isFalse()
    }

    @Test
    @DisplayName("an unknown name is refused naming itself and the valid names; names are case-sensitive and exact")
    fun unknown() {
        for (bad in listOf("titel", "Title", " title", "")) {
            assertThatThrownBy { RequiredSet.of(listOf("description", bad), examplesSupplied = true) }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("'$bad' is not a documentation property; expected one or more of: title, description, frameworks, fixture, verdict")
        }
    }

    @Test
    @DisplayName("fixture and verdict need example fixtures: without them they are refused, naming the property")
    fun needsExamples() {
        for (name in listOf("fixture", "verdict")) {
            assertThatThrownBy { RequiredSet.of(listOf("title", name), examplesSupplied = false) }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("required '$name' needs example fixtures (pass an examples directory with at least one fixture)")
            assertThat(RequiredSet.of(listOf(name), examplesSupplied = true).names).containsExactly(name)
        }
        assertThat(RequiredSet.of(listOf("title", "description", "frameworks"), examplesSupplied = false).names).hasSize(3)
    }
}
