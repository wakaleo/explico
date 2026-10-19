/**
 * Acceptance tests for `generateExamples` / `--generate-examples`: explico DERIVES worked-example inputs itself from a
 * rule's conditions, only to fill gaps the author's own fixtures leave, and labels them as generated. Authored from the
 * design review of the throwaway synthesizer prototype, not from the frozen README contract.
 *
 * What these pin (facade only; fixture `src/test/resources/realistic/generated-examples/`):
 *  - OFF by default: no generated text anywhere, and the output equals the call that never mentions the option.
 *  - ON: a separate `**Generated examples**` section after any author section, with a provenance footer, mechanical
 *    names (`satisfies every condition [of Situation N]`, `breaks only: <the broken condition>`), and verdicts that
 *    come from `opa eval` (a "satisfies" entry is matched, a "breaks only" entry is not).
 *  - A rule the solver cannot model gets NO section; a rule whose author fixtures already cover a match AND a non-match
 *    gets none; with only a matching author case it gets only near-misses (gap filling, never duplication).
 *  - Deterministic: two renders are byte-identical.
 *
 * `render` is given the new LAST parameter `generateExamples` by name, so this class does not compile until the
 * facade has it: that compile error is the expected first failure.
 */
package io.explico.acceptance

import io.explico.Explico
import io.explico.opa.OpaRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path

@DisplayName("Realistic policies: generated worked examples")
class RealisticGeneratedExamplesAcceptanceIT {

    companion object {
        private const val SECTION = "**Generated examples**"
        private const val FOOTER =
            "*Generated examples are derived mechanically from this rule's conditions and evaluated by OPA at generation time; " +
                "they illustrate the rule, they are not scenarios chosen by its author.*"

        private val dir = Path.of("src/test/resources/realistic/generated-examples")
        private val policies = dir.resolve("policies")

        /** No author fixtures: every rule starts with no examples at all. */
        private lateinit var withoutAuthorFixtures: String
        /** The same render again, to prove determinism. */
        private lateinit var withoutAuthorFixturesAgain: String
        /** Author fixtures present: some rules already have a match, a non-match, or both. */
        private lateinit var withAuthorFixtures: String
        private lateinit var withAuthorFixturesAgain: String
        /** Option off, two ways of saying so. */
        private lateinit var offExplicit: String
        private lateinit var offByDefault: String

        private fun render(examples: Boolean, generate: Boolean?): String {
            val set = Explico.load(policies)
            val fixtures = if (examples) Explico.loadExamples(dir.resolve("examples")) else null
            val docs = when (generate) {
                null -> Explico.render(set, policies, fixtures, null)
                else -> Explico.render(set, policies, fixtures, null, generateExamples = generate)
            }
            return docs.files.getValue("genex.md")
        }

        @JvmStatic
        @BeforeAll
        fun renderDocuments() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            withoutAuthorFixtures = render(examples = false, generate = true)
            withoutAuthorFixturesAgain = render(examples = false, generate = true)
            withAuthorFixtures = render(examples = true, generate = true)
            withAuthorFixturesAgain = render(examples = true, generate = true)
            offExplicit = render(examples = true, generate = false)
            offByDefault = render(examples = true, generate = null)
        }

        /** The card whose `## ` heading contains [marker], up to the next `## ` heading. */
        private fun card(doc: String, marker: String): String {
            val lines = doc.lines()
            val start = lines.indexOfFirst { it.startsWith("## ") && it.contains(marker) }
            assertThat(start).describedAs("a '## ' heading containing %s", marker).isGreaterThanOrEqualTo(0)
            val end = lines.drop(start + 1).indexOfFirst { it.startsWith("## ") }
            return lines.drop(start).take(if (end < 0) lines.size else end + 1).joinToString("\n")
        }

        /** Just the generated part of a card: from the section heading to the card's end. */
        private fun generated(card: String): String = card.substringAfter(SECTION, missingDelimiterValue = "")

        private fun occurrences(text: String, needle: String) = text.windowed(needle.length).count { it == needle }
    }

    @Nested
    @DisplayName("The option is off by default")
    inner class OffByDefault {

        @Test
        @DisplayName("without the option nothing generated appears anywhere")
        fun noGeneratedTextWhenOff() {
            assertThat(offByDefault).doesNotContain("Generated examples").doesNotContain("satisfies every condition").doesNotContain("breaks only")
        }

        @Test
        @DisplayName("passing generateExamples = false is byte-identical to never mentioning it")
        fun explicitFalseEqualsDefault() {
            assertThat(offExplicit).isEqualTo(offByDefault)
        }
    }

    @Nested
    @DisplayName("GX-001 — a message argument must be defined for the rule to produce its message")
    inner class Unencrypted {

        @Test
        @DisplayName("with no author fixtures: a matching example and a near-miss are generated, under a labelled section")
        fun bothKindsWhenNothingAuthored() {
            val card = card(withoutAuthorFixtures, "GX-001")
            assertThat(card).contains(SECTION)
            assertThat(generated(card)).contains("**satisfies every condition**")
            assertThat(generated(card)).contains("**breaks only: `encrypted` is `false`**")
        }

        @Test
        @DisplayName("the matching example's verdict and message come from OPA, built from the generated value of `name`")
        fun verdictAndMessageFromOpa() {
            val entry = generated(card(withoutAuthorFixtures, "GX-001")).substringAfter("**satisfies every condition**")
            assertThat(entry.lineSequence().first()).startsWith(" — matched")
            assertThat(entry).containsPattern("\\*\"bucket \\S+ is not encrypted\"\\*")
        }

        @Test
        @DisplayName("the near-miss is NOT matched and produces no message")
        fun nearMissIsNotMatched() {
            val entry = generated(card(withoutAuthorFixtures, "GX-001")).substringAfter("**breaks only: `encrypted` is `false`**")
            assertThat(entry.lineSequence().first()).startsWith(" — not matched")
            assertThat(entry.lineSequence().drop(1).takeWhile { it.startsWith("  ") }.joinToString("\n")).doesNotContain("is not encrypted")
        }

        @Test
        @DisplayName("gap filling: author fixtures that never match leave only the matching example to generate")
        fun onlyMatchingWhenAuthoredNonMatchesExist() {
            val card = card(withAuthorFixtures, "GX-001")
            assertThat(generated(card)).contains("**satisfies every condition**").doesNotContain("breaks only")
        }
    }

    @Nested
    @DisplayName("GX-002 — iteration: the generated element's values are shown")
    inner class PrivilegedInProd {

        @Test
        @DisplayName("a matching example lists the referenced input values, including the iterated element's field")
        fun elementValuesShown() {
            val entry = generated(card(withoutAuthorFixtures, "GX-002")).substringAfter("**satisfies every condition**")
            assertThat(entry).contains("`env`: `\"production\"`")
            assertThat(entry).contains("`containers ▸ [each c] ▸ privileged`: `true`")
        }
    }

    @Nested
    @DisplayName("GX-003 — a multi-body rule gets one matching example per situation")
    inner class TwoWays {

        @Test
        @DisplayName("two matching entries, each labelled with its own situation")
        fun oneEntryPerSituation() {
            val generated = generated(card(withoutAuthorFixtures, "GX-003"))
            assertThat(generated).containsPattern("\\*\\*satisfies every condition of Situation 1\\*\\* — matched \\*\\(Situation 1\\)\\*")
            assertThat(generated).containsPattern("\\*\\*satisfies every condition of Situation 2\\*\\* — matched \\*\\(Situation 2\\)\\*")
            assertThat(generated).contains("\"kind a is not allowed\"").contains("\"size over ten\"")
        }
    }

    @Nested
    @DisplayName("GX-004 — a rule the solver cannot model gets nothing")
    inner class Unsolvable {

        @Test
        @DisplayName("a body that calls a function has no generated section and no provenance footer")
        fun noSectionNoFooter() {
            val card = card(withoutAuthorFixtures, "GX-004")
            assertThat(card).doesNotContain(SECTION).doesNotContain("Generated examples")
            assertThat(card(withAuthorFixtures, "GX-004")).doesNotContain(SECTION)
        }
    }

    @Nested
    @DisplayName("GX-005 — author fixtures already cover a match and a non-match")
    inner class FullyCovered {

        @Test
        @DisplayName("nothing is generated for it, and the author's own examples are still shown")
        fun nothingGenerated() {
            val card = card(withAuthorFixtures, "GX-005")
            assertThat(card).doesNotContain(SECTION).contains("**Worked examples**")
            assertThat(card).contains("— matched").contains("— not matched")
        }

        @Test
        @DisplayName("with no author fixtures at all the same rule gets both kinds")
        fun bothKindsWithoutFixtures() {
            assertThat(generated(card(withoutAuthorFixtures, "GX-005"))).contains("**satisfies every condition**").contains("**breaks only:")
        }
    }

    @Nested
    @DisplayName("GX-006 — author fixtures only ever match this rule")
    inner class OnlyMatchingAuthored {

        @Test
        @DisplayName("only near-misses are generated: one per condition that can be broken")
        fun onlyNearMisses() {
            val generated = generated(card(withAuthorFixtures, "GX-006"))
            assertThat(generated).doesNotContain("satisfies every condition")
            assertThat(generated).contains("**breaks only: `level` is `\"high\"`**").contains("**breaks only: `region` is `\"eu\"`**")
        }

        @Test
        @DisplayName("every near-miss is reported as not matched")
        fun nearMissesAreNotMatched() {
            val lines = generated(card(withAuthorFixtures, "GX-006")).lines().filter { it.startsWith("- **breaks only:") }
            assertThat(lines).hasSize(2).allSatisfy { assertThat(it).contains("— not matched") }
        }
    }

    @Nested
    @DisplayName("Across the document")
    inner class WholeDocument {

        @Test
        @DisplayName("each card with a generated section has the provenance footer exactly once; cards without one have none")
        fun footerOncePerGeneratedSection() {
            for (doc in listOf(withoutAuthorFixtures, withAuthorFixtures)) {
                val cards = doc.split("\n---\n").filter { it.contains("## GX-") }
                assertThat(cards).hasSize(6)
                for (c in cards) {
                    val hasSection = c.contains(SECTION)
                    assertThat(occurrences(c, FOOTER)).describedAs(c.lineSequence().first { it.startsWith("## ") }).isEqualTo(if (hasSection) 1 else 0)
                }
            }
        }

        @Test
        @DisplayName("a generated example's verdict always agrees with its name: satisfies is matched, breaks only is not")
        fun verdictsAgreeWithNames() {
            for (doc in listOf(withoutAuthorFixtures, withAuthorFixtures)) {
                val entries = doc.lines().filter { it.startsWith("- **satisfies every condition") || it.startsWith("- **breaks only:") }
                assertThat(entries).isNotEmpty()
                for (e in entries) {
                    if (e.startsWith("- **satisfies")) assertThat(e).contains("— matched")
                    else assertThat(e).contains("— not matched")
                }
            }
        }

        @Test
        @DisplayName("two renders with generation on are byte-identical")
        fun deterministic() {
            assertThat(withoutAuthorFixturesAgain).isEqualTo(withoutAuthorFixtures)
            assertThat(withAuthorFixturesAgain).isEqualTo(withAuthorFixtures)
        }
    }

    @Nested
    @DisplayName("Same-named rules (separate cards for one rule name)")
    inner class SameNamedRules {

        private val sameNameDir = Path.of("src/test/resources/realistic/same-name")

        private val doc: String by lazy {
            val set = Explico.load(sameNameDir.resolve("policies"))
            Explico.render(set, sameNameDir.resolve("policies"), null, null, generateExamples = true).files.getValue("samename.md")
        }

        @Test
        @DisplayName("each card's generated examples carry only the messages ITS body produces")
        fun attributedToTheCardTheyCameFrom() {
            val first = generated(card(doc, "SN-001"))
            val second = generated(card(doc, "SN-002"))
            assertThat(first).contains("is not encrypted").doesNotContain("production bucket is public")
            assertThat(second).contains("production bucket is public").doesNotContain("is not encrypted")
        }

        @Test
        @DisplayName("same-named boolean rules cannot be attributed, so they get no generated examples either")
        fun booleanSameNamedRulesGetNone() {
            assertThat(card(doc, "SN-003")).doesNotContain(SECTION)
            assertThat(card(doc, "SN-004")).doesNotContain(SECTION)
        }
    }

    @Nested
    @DisplayName("The summary and other policy dialects")
    inner class SummaryAndDialects {

        @Test
        @DisplayName("RenderedDocs.generatedExamples is null when off, and counts rules with examples out of rules considered when on")
        fun summaryReflectsTheOption() {
            val set = Explico.load(policies)
            assertThat(Explico.render(set, policies).generatedExamples).isNull()
            val on = Explico.render(set, policies, null, null, generateExamples = true).generatedExamples!!
            // six rules take part; GX-004 calls a function and cannot be derived
            assertThat(on.rulesConsidered).isEqualTo(6)
            assertThat(on.rulesWithExamples).isEqualTo(5)
        }

        @Test
        @DisplayName("Rego v0 policies get generated examples too, evaluated under v0")
        fun regoV0() {
            val v0Policies = Path.of("src/test/resources/realistic/rego-v0/policies")
            val set = Explico.load(v0Policies, io.explico.model.RegoVersion.V0)
            val doc = Explico.render(set, v0Policies, null, null, generateExamples = true).files.getValue("legacy-admission.md")
            val card = card(doc, "LEG-001")
            assertThat(card).contains(SECTION)
            assertThat(generated(card)).contains("**satisfies every condition**").contains("**breaks only:")
            assertThat(generated(card)).containsPattern("\\*\"container \\S+ is privileged\"\\*")
        }
    }
}
