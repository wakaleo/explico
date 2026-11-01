/**
 * Unit tests for the `--fail-on EXAMPLE_CHANGES` gate message (spec §14.74): counts and controls when something changed, and a refusal to pass when nothing could be compared.
 */
package io.explico.cli

import io.explico.diff.ExampleChange
import io.explico.diff.ExampleComparison
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class GatesExampleChangesTest {

    private val change = ExampleChange("C-1", "f", "✅ allowed", "❌ denied")

    @Test
    @DisplayName("changes: the example count, the control count and the controls in order, each control once")
    fun changed() {
        val message = Gates.failOnExampleChanges(listOf(change, change.copy(fixture = "g"), change.copy(control = "C-2")), ExampleComparison(2, 2, 0, 0, 0))

        assertThat(message).isEqualTo("explico: gate failed (--fail-on EXAMPLE_CHANGES): 3 example(s) changed outcome in 2 control(s): C-1, C-2")
    }

    @Test
    @DisplayName("nothing changed and something was compared: the gate passes")
    fun passes() {
        assertThat(Gates.failOnExampleChanges(emptyList(), ExampleComparison(2, 3, 1, 0, 0))).isNull()
        assertThat(Gates.failOnExampleChanges(emptyList(), ExampleComparison(0, 0, 0, 0, 0))).describedAs("no control on both sides: nothing to guard").isNull()
    }

    @Test
    @DisplayName("controls on both sides but none comparable: the gate fails saying so, rather than pass having looked at nothing")
    fun nothingComparable() {
        assertThat(Gates.failOnExampleChanges(emptyList(), ExampleComparison(0, 3, 2, 1, 0)))
            .isEqualTo("explico: gate failed (--fail-on EXAMPLE_CHANGES): no control could be compared (3 present in both versions: 2 function(s), 1 with no examples attributable to the rule)")
    }
}
