/**
 * Spec §14.66: one table says, in a sentence, what explico needs for a construct to be rendered, keyed by the machine-readable reason a fallback carries.
 * Pure; no opa. The drift guard at the end reads the mapper's own source so a reason added or renamed there cannot silently fall to the default sentence.
 */
package io.explico.render

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class UnrenderedReasonsTest {

    private val expected = mapOf(
        "every" to "an every block is shown only when its domain is a plain path and every condition in its body can be rendered",
        "else-chain" to "an else chain is shown only when every branch can be rendered, and it is not one of several definitions of the same rule",
        "with-override" to "with replaces a value for one call, which explico does not model",
        "function-call" to "a call or unification explico cannot render: a built-in it does not model, a function it cannot find or whose arguments it cannot render, " +
            "or a pattern on the left of =",
        "helper-assignment" to "a local assignment whose value explico cannot substitute into the conditions that use it",
        "partial-rule-reference" to "a bare reference to a set or object rule is always defined, even when empty, so it is not shown as a yes/no test",
        "comprehension" to "a comprehension is shown only when its head and every condition inside it can be rendered",
        "mapping-error" to "explico could not map this expression (see the warning on stderr)",
        "unclassified" to "explico does not render this construct",
    )

    @Test
    @DisplayName("every known reason has exactly its sentence")
    fun sentences() {
        for ((reason, sentence) in expected) assertThat(UnrenderedReasons.why(reason)).describedAs(reason).isEqualTo(sentence)
    }

    @Test
    @DisplayName("any other reason, including an empty or odd one, gets the generic sentence rather than failing")
    fun unknownReasons() {
        for (reason in listOf("something-new", "", "Every", "with override", "`x`|y")) assertThat(UnrenderedReasons.why(reason)).describedAs("'$reason'").isEqualTo("explico does not render this construct")
    }

    @Test
    @DisplayName("the table lists exactly the known reasons")
    fun knownReasons() {
        assertThat(UnrenderedReasons.known).containsExactlyInAnyOrderElementsOf(expected.keys)
    }

    @Test
    @DisplayName("drift guard: every reason string the mapper attaches to a fallback is in the table (a new or renamed reason fails here, not at the default sentence)")
    fun mapperReasonsAreAllKnown() {
        val source = Files.readString(Path.of("src/main/kotlin/io/explico/parse/AstMapper.kt"))
        // What the mapper attaches AS a reason: the last literal of an Unrendered(..., "reason") call, an Unsupported("reason") argument, or the partial-rule ternary.
        val reasons = source.lines().flatMap { line ->
            Regex("Condition\\.Unrendered\\(.*,\\s*\"([a-z]+(?:-[a-z]+)*)\"\\)").findAll(line).map { it.groupValues[1] }.toList() +
                Regex("Unsupported\\(\"([a-z]+(?:-[a-z]+)*)\"").findAll(line).map { it.groupValues[1] }.toList() +
                Regex("\"(partial-rule-reference)\" else \"(unclassified)\"").findAll(line).flatMap { listOf(it.groupValues[1], it.groupValues[2]) }.toList()
        }.toSet()
        assertThat(reasons).describedAs("reasons found in AstMapper (parsed with regexes, so a new call shape may need this guard widened)").isNotEmpty()
        assertThat(reasons).describedAs("the guard really sees the mapper's reasons").contains("every", "else-chain", "with-override", "function-call", "helper-assignment", "partial-rule-reference", "unclassified", "mapping-error")
        assertThat(UnrenderedReasons.known).containsAll(reasons)
    }
}
