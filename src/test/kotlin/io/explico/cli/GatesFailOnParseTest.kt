/**
 * `Gates.parseFailOn` after the §14.80 step 5 rename: `--fail-on` takes the four change categories and EXAMPLE_CHANGES, which is not a `DiffCategory`, so the result says which of the two kinds of
 * gate were asked for. Pure; the existing `GatesTest` still covers the category half (through `.categories`).
 */
package io.explico.cli

import io.explico.diff.DiffCategory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class GatesFailOnParseTest {

    @Test
    @DisplayName("EXAMPLE_CHANGES alone asks for the example gate and no category")
    fun exampleChangesAlone() {
        assertThat(Gates.parseFailOn("EXAMPLE_CHANGES")).isEqualTo(Gates.FailOn(emptyList(), true))
    }

    @Test
    @DisplayName("it mixes with categories in either order, case-insensitively; categories keep the order given and are deduplicated")
    fun mixed() {
        assertThat(Gates.parseFailOn("example_changes, logic_changed ,REMOVED,logic_changed")).isEqualTo(Gates.FailOn(listOf(DiffCategory.LOGIC_CHANGED, DiffCategory.REMOVED), true))
        assertThat(Gates.parseFailOn("ADDED,EXAMPLE_CHANGES,example_changes")).isEqualTo(Gates.FailOn(listOf(DiffCategory.ADDED), true))
    }

    @Test
    @DisplayName("without EXAMPLE_CHANGES the example gate is off")
    fun categoriesOnly() {
        assertThat(Gates.parseFailOn("DOCS_CHANGED")).isEqualTo(Gates.FailOn(listOf(DiffCategory.DOCS_CHANGED), false))
    }

    @Test
    @DisplayName("an unknown name, UNCHANGED, the old option's spelling and an empty list are rejected, naming all five valid values")
    fun rejects() {
        for (bad in listOf("UNCHANGED", "bogus", "EXAMPLE_CHANGES,bogus", "example-changes", "fail-on-example-changes", "", " , ")) {
            val e = assertThrows(IllegalArgumentException::class.java) { Gates.parseFailOn(bad) }
            assertThat(e.message).describedAs(bad).contains("ADDED, REMOVED, LOGIC_CHANGED, DOCS_CHANGED, EXAMPLE_CHANGES")
        }
    }
}
