/**
 * Acceptance tests for FR-12 of the decision-object feature requests: `diff` should be aware of the situation model of FR-1. A changed commentary
 * string or a reordered `else` branch is already a LOGIC_CHANGED entry (the canonical hash sees the branches, in order, and their values), but its
 * "What changed" line only said "the rule's structure changed; see the source diff below". Now the LOGIC_CHANGED sub-bullets name the situation:
 *
 * - `situation N: value changed from <old> to <new>` -- a branch (an `else` branch, or one of several definitions of a name) matched by its conditions
 *   whose value changed; `otherwise: value changed ...` for the closing branch with no conditions; plain `value changed from <old> to <new>` for a rule
 *   with one body. The value texts are exactly what the cards show after `*Then:*` / `**Value:**`.
 * - `situations reordered: now in the order of the old situations 2, 1, otherwise` -- the same branches in a different order (it matters for an `else`
 *   chain: the first matching situation wins).
 * - `situation N removed (was: then <value>)` / `situation N added (then <value>)` -- a branch with no counterpart by its conditions (N is its number in the
 *   old / new version), next to the existing `condition removed/added` lines.
 * - the generic fallback sentence is NOT used when one of these applies; an unchanged chain is not reported; and editing the DECLARED verdict
 *   (`custom.verdict` in METADATA) stays a documentation change, as before.
 * The Markdown and `explico diff --json` carry the same lines. Authored from the feature request and the plan, NOT from the frozen acceptance-pack
 * README. Facade only. Fixtures: `realistic/diff-situations/{old,new}` (SIT-1 reordered, SIT-2 commentary, SIT-3 added+removed, SIT-4 unchanged,
 * SIT-5 single value, SIT-6 sibling definitions, SIT-7 declared verdict).
 */
package io.explico.acceptance

import io.explico.DiffReport
import io.explico.Explico
import io.explico.diff.DiffCategory
import io.explico.opa.OpaRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path

@DisplayName("Realistic policies: diff names the situation that changed (FR-12)")
class RealisticDiffSituationsAcceptanceIT {

    companion object {
        private val old = Path.of("src/test/resources/realistic/diff-situations/old")
        private val new = Path.of("src/test/resources/realistic/diff-situations/new")

        private lateinit var report: DiffReport
        private lateinit var again: DiffReport

        @JvmStatic
        @BeforeAll
        fun diffAll() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            report = Explico.diff(Explico.load(old), Explico.load(new))
            again = Explico.diff(Explico.load(old), Explico.load(new))
        }

        /** The sub-bullets (without the leading "  - ") under the "What changed" bullet of control [id]; an ASSERTION failure unless it has exactly one bullet. */
        private fun details(id: String, markdown: String = report.markdown): List<String> {
            val lines = markdown.lines()
            val label = Regex("^- (\\[)?" + Regex.escape(id) + " — ")
            val start = lines.indexOfFirst { label.containsMatchIn(it) }
            assertThat(start).describedAs("a 'What changed' bullet for $id").isGreaterThanOrEqualTo(0)
            return lines.drop(start + 1).takeWhile { it.startsWith("  - ") }.map { it.removePrefix("  - ") }
        }
    }

    @Nested
    @DisplayName("Reordered situations")
    inner class Reordered {

        @Test
        @DisplayName("SIT-1: two else branches swapped -> one line saying so, in terms of the old situation numbers; no generic fallback")
        fun reordered() {
            assertThat(details("SIT-1")).containsExactly("situations reordered: now in the order of the old situations 2, 1, otherwise")
        }

        @Test
        @DisplayName("an unchanged chain (SIT-4) is not in the report at all")
        fun unchangedChain() {
            assertThat(report.markdown).doesNotContain("SIT-4")
            assertThat(report.entries.single { it.controlId == "SIT-4" }.category).isEqualTo(DiffCategory.UNCHANGED)
        }
    }

    @Nested
    @DisplayName("A changed value")
    inner class ChangedValue {

        @Test
        @DisplayName("SIT-2: the commentary string inside situation 2's function call changed -> 'situation 2: value changed from ... to ...', worded as the card words the value")
        fun commentary() {
            assertThat(details("SIT-2")).containsExactly(
                // Spec §14.81 repin: the card now words this call as the object the function returns, and the sentence is worded as the card words it.
                "situation 2: value changed from an object, as returned by function `verdict`, with `commentary`: `\"Attestation has expired\"`; `compliant`: `false` " +
                    "to an object, as returned by function `verdict`, with `commentary`: `\"Attestation has lapsed\"`; `compliant`: `false`",
            )
        }

        @Test
        @DisplayName("SIT-5: a rule with one body: 'value changed from `10` to `20`', no situation number")
        fun singleBody() {
            assertThat(details("SIT-5")).containsExactly("value changed from `10` to `20`")
        }

        @Test
        @DisplayName("SIT-6: sibling definitions of one name are situations too: the second one's value changed")
        fun siblingDefinitions() {
            assertThat(details("SIT-6")).containsExactly("situation 2: value changed from `\"second\"` to `\"third\"`")
        }
    }

    @Nested
    @DisplayName("Added and removed situations")
    inner class AddedAndRemoved {

        @Test
        @DisplayName("SIT-3: a branch replaced by another with different conditions -> the existing condition lines, plus 'situation 2 removed (was: ...)' and 'situation 2 added (...)'")
        fun replaced() {
            assertThat(details("SIT-3")).containsExactlyInAnyOrder(
                "condition removed: `score` is greater than `60`",
                "condition added: `score` is greater than `75`",
                "situation 2 removed (was: then `\"silver\"`)",
                "situation 2 added (then `\"platinum\"`)",
            )
        }
    }

    @Nested
    @DisplayName("Classification and parity")
    inner class Classification {

        @Test
        @DisplayName("all five edits are LOGIC_CHANGED; the declared-verdict edit (SIT-7) stays DOCS_CHANGED, as before; SIT-4 and the helper function are UNCHANGED")
        fun categories() {
            val byId = report.entries.filter { it.controlId != null }.associate { it.controlId to it.category }
            assertThat(byId).containsEntry("SIT-1", DiffCategory.LOGIC_CHANGED).containsEntry("SIT-2", DiffCategory.LOGIC_CHANGED)
                .containsEntry("SIT-3", DiffCategory.LOGIC_CHANGED).containsEntry("SIT-5", DiffCategory.LOGIC_CHANGED).containsEntry("SIT-6", DiffCategory.LOGIC_CHANGED)
                .containsEntry("SIT-7", DiffCategory.DOCS_CHANGED).containsEntry("SIT-4", DiffCategory.UNCHANGED)
            assertThat(report.markdown).contains("| LOGIC_CHANGED | 5 |").contains("| DOCS_CHANGED | 1 |").contains("| UNCHANGED | 2 |")
            assertThat(details("SIT-7")).isEmpty()
            assertThat(report.markdown).contains("documentation changed: verdict")
        }

        @Test
        @DisplayName("the generic fallback sentence appears nowhere in this report: every change here is named")
        fun noGenericFallback() {
            assertThat(report.markdown).doesNotContain("the rule's structure changed")
        }

        @Test
        @DisplayName("explico diff --json carries the same lines as `details` of each change")
        fun jsonParity() {
            val changes = Json.parseToJsonElement(report.json).jsonObject.getValue("changes").jsonArray.map { it.jsonObject }
            for (id in listOf("SIT-1", "SIT-2", "SIT-3", "SIT-5", "SIT-6")) {
                val change = changes.single { it.getValue("controlId").jsonPrimitive.content == id }
                assertThat(change.getValue("details").jsonArray.map { it.jsonPrimitive.content }).describedAs(id).isEqualTo(details(id))
                assertThat(details(id)).describedAs("$id is named, not the generic fallback").doesNotContain("the rule's structure changed; see the source diff below")
            }
        }

        @Test
        @DisplayName("deterministic: two runs give the same Markdown and JSON")
        fun deterministic() {
            assertThat(again.markdown).isEqualTo(report.markdown)
            assertThat(again.json).isEqualTo(report.json)
        }

        @Test
        @DisplayName("diffing a version against itself reports nothing")
        fun selfDiff() {
            val same = Explico.diff(Explico.load(new), Explico.load(new))
            assertThat(same.markdown).doesNotContain("## What changed")
            assertThat(same.entries).allMatch { it.category == DiffCategory.UNCHANGED }
        }
    }
}
