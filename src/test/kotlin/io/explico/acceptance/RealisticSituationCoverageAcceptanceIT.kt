/**
 * Acceptance tests for per-situation example coverage: the index's "No matching example" finding is per CONTROL, so a control with several situations passes as soon as ONE
 * fixture matches it, and a situation no fixture ever triggers (a newly added body, say) has no worked example and nobody is told. Authored from the control-owner journey
 * review, NOT from the frozen acceptance-pack README. Decisions (confirmed by the operator, option 1 of the golden-impact question: the sample pack's own REL-004 situation 3
 * is matched by no fixture, so the finding appears in the pack's index and its goldens are regenerated deliberately):
 *
 * - New advisory bullet after "No matching example": `- **Situation without a matching example (N):** <control> situation 2; <control> situations 2, 3 — add a fixture that
 *   makes each of them match`, N = the number of CONTROLS, in index order, only when example fixtures were supplied.
 * - A situation is covered when some fixture's outcome for the control is matched AND one of its messages is attributed to it (the card's own `*(Situation N)*`, scoped by
 *   `WorkedExamples.forGroup`). Only what can be CLAIMED is reported: a control with 2+ bodies whose messages all attribute; never a boolean rule, a body without a message
 *   template, an `else` chain, a function, a split default, a grouped control (--group-by-control), nor a control already under "No matching example".
 * - Summary line (RenderedDocs.findings) right after the "no matching example" line; JSON `quality.situationsWithoutExample` [{control, situations}], always present.
 *   NOT a gap: gaps and --fail-on-gaps are unchanged, whatever is required.
 * Fixtures: `realistic/situation-coverage/{policies,examples,examples-more,grouped}` (see its README). Facade only; the terminal block is in CliSituationCoverageProcessTest.
 */
package io.explico.acceptance

import io.explico.Explico
import io.explico.RenderOptions
import io.explico.RenderedDocs
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

@DisplayName("Realistic policies: per-situation example coverage")
class RealisticSituationCoverageAcceptanceIT {

    companion object {
        private val base = Path.of("src/test/resources/realistic/situation-coverage")
        private val policies = base.resolve("policies")

        private const val HEAD = "- **Situation without a matching example ("
        private const val HINT = "— add a fixture that makes each of them match"
        private const val NO_MATCH = "- **No matching example ("

        private fun render(dir: Path = policies, examples: Path? = base.resolve("examples"), group: Boolean = false, required: List<String>? = null): RenderedDocs {
            val set = Explico.load(dir)
            return Explico.render(set, RenderOptions(examples = examples?.let { Explico.loadExamples(it) }, json = true, groupByControl = group, required = required))
        }

        private lateinit var withExamples: RenderedDocs
        private lateinit var withExamplesAgain: RenderedDocs
        private lateinit var moreExamples: RenderedDocs
        private lateinit var withoutExamples: RenderedDocs
        private lateinit var grouped: RenderedDocs
        private lateinit var allRequired: RenderedDocs
        private lateinit var samplePack: RenderedDocs

        @JvmStatic
        @BeforeAll
        fun renderAll() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            withExamples = render()
            withExamplesAgain = render()
            moreExamples = render(examples = base.resolve("examples-more"))
            withoutExamples = render(examples = null)
            grouped = render(base.resolve("grouped/policies"), base.resolve("grouped/examples"), group = true)
            allRequired = render(required = listOf("title", "description", "frameworks", "fixture", "verdict"))
            val samples = Path.of("samples/policies")
            samplePack = Explico.render(Explico.load(samples), RenderOptions(examples = Explico.loadExamples(Path.of("samples/examples")), dataDir = Path.of("samples/data/release/data.json"), json = true))
        }

        /** The bullets of the index's "Documentation quality" section (empty when the section is absent). */
        private fun qualityLines(docs: RenderedDocs): List<String> {
            val lines = docs.files.getValue("index.md").lines()
            val start = lines.indexOf("## Documentation quality")
            if (start < 0) return emptyList()
            return lines.drop(start + 1).takeWhile { !it.startsWith("## ") }.filter { it.startsWith("- ") }
        }

        private fun bullet(docs: RenderedDocs): String? = qualityLines(docs).singleOrNull { it.startsWith(HEAD) }

        private fun entries(docs: RenderedDocs): List<Pair<String, List<Int>>> {
            val quality = Json.parseToJsonElement(docs.files.getValue("explico.json")).jsonObject.getValue("quality").jsonObject
            assertThat(quality).describedAs("the quality object").containsKey("situationsWithoutExample")
            return quality.getValue("situationsWithoutExample").jsonArray.map { e ->
                e.jsonObject.getValue("control").jsonPrimitive.content to e.jsonObject.getValue("situations").jsonArray.map { it.jsonPrimitive.content.toInt() }
            }
        }
    }

    @Nested
    @DisplayName("The bullet")
    inner class Bullet {

        @Test
        @DisplayName("lists each control with the situations no fixture matches, ascending, in index order, with links and the fix hint; N counts controls")
        fun exactText() {
            assertThat(bullet(withExamples)).isEqualTo(
                "$HEAD" + "2):** [SC-1](sc1.md#sc-1) situation 2; [SC-7](sc7.md#sc-7) situations 2, 3 $HINT",
            )
        }

        @Test
        @DisplayName("it comes right after 'No matching example'")
        fun placement() {
            val lines = qualityLines(withExamples)
            val noMatch = lines.indexOfFirst { it.startsWith(NO_MATCH) }

            assertThat(noMatch).isGreaterThanOrEqualTo(0)
            assertThat(lines.indexOfFirst { it.startsWith(HEAD) }).isEqualTo(noMatch + 1)
        }

        @Test
        @DisplayName("a control with every situation covered, one situation, a boolean rule, a message that is not a template, or an else chain is not listed; SC-4, which no fixture matches, is only under 'No matching example'")
        fun notClaimable() {
            val text = bullet(withExamples)
            assertThat(text).describedAs("the situation bullet").isNotNull()
            for (id in listOf("SC-2", "SC-3", "SC-4", "SC-5", "SC-6", "SC-8")) assertThat(text!!).describedAs(id).doesNotContain(id)
            assertThat(qualityLines(withExamples).single { it.startsWith(NO_MATCH) }).contains("[SC-4](sc4.md#sc-4)").doesNotContain("SC-1")
        }

        @Test
        @DisplayName("once the missing fixture exists, the control disappears from the list and the count drops to 1")
        fun coveredBySecondFixtureSet() {
            assertThat(bullet(moreExamples)).isEqualTo("$HEAD" + "1):** [SC-1](sc1.md#sc-1) situation 2 $HINT")
        }

        @Test
        @DisplayName("without example fixtures there is no bullet, and a grouped control (--group-by-control) is skipped")
        fun absentOrSkipped() {
            assertThat(bullet(withoutExamples)).isNull()
            assertThat(bullet(grouped)).isNull()
            assertThat(withoutExamples.files.getValue("index.md")).doesNotContain("Situation without a matching example")
        }

        @Test
        @DisplayName("the sample pack's own REL-004 has a situation (3, the all_checks_passed reference) no fixture triggers, and says so")
        fun samplePack() {
            assertThat(bullet(samplePack)).isEqualTo(
                "$HEAD" + "1):** [REL-004](release-governance.md#rel-004) situation 3 $HINT",
            )
        }
    }

    @Nested
    @DisplayName("The summary lines and the gate")
    inner class Summary {

        @Test
        @DisplayName("RenderedDocs.findings words it right after the 'no matching example' line: plural, then singular")
        fun lines() {
            val plural = withExamples.findings
            assertThat(plural).containsSubsequence("1 control has no matching example", "2 controls have a situation no example matches")
            assertThat(plural.indexOf("2 controls have a situation no example matches")).isEqualTo(plural.indexOf("1 control has no matching example") + 1)
            assertThat(moreExamples.findings).contains("1 control has a situation no example matches")
            assertThat(withoutExamples.findings.none { it.contains("situation no example") }).isTrue()
        }

        @Test
        @DisplayName("it is a finding, not a gap: the gaps list is unchanged, even with every property required")
        fun notAGap() {
            assertThat(withExamples.gaps).isEmpty()
            assertThat(allRequired.gaps).containsExactly("control SC-4 has no matching example")
        }
    }

    @Nested
    @DisplayName("explico.json")
    inner class InTheJson {

        @Test
        @DisplayName("quality.situationsWithoutExample lists the same controls and situations as the index")
        fun entries() {
            assertThat(entries(withExamples)).containsExactly("SC-1" to listOf(2), "SC-7" to listOf(2, 3))
            assertThat(entries(moreExamples)).containsExactly("SC-1" to listOf(2))
        }

        @Test
        @DisplayName("the key is always present: an empty array without example fixtures and for a grouped control")
        fun alwaysPresent() {
            assertThat(entries(withoutExamples)).isEmpty()
            assertThat(entries(grouped)).isEmpty()
        }
    }

    @Nested
    @DisplayName("Determinism")
    inner class Determinism {

        @Test
        @DisplayName("two renders are byte-identical, and the finding makes the section non-empty")
        fun deterministic() {
            assertThat(withExamplesAgain.files.toList()).isEqualTo(withExamples.files.toList())
            assertThat(withExamplesAgain.findings).isEqualTo(withExamples.findings)
            assertThat(withExamples.files.getValue("index.md")).contains("## Documentation quality")
        }
    }
}
