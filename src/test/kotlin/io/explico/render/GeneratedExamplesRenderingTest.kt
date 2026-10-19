/**
 * How a rule card shows generated worked examples (spec §14.39): a separate section after the author's own, mechanical names,
 * the same entry format, one provenance footer, and nothing at all when a rule has none.
 */
package io.explico.render

import io.explico.generate.GeneratedExample
import io.explico.generate.GeneratedExamples
import io.explico.model.Condition
import io.explico.model.Operand
import io.explico.model.Operator
import io.explico.model.PathSegment
import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RuleBody
import io.explico.model.RuleGroup
import io.explico.model.SourceRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GeneratedExamplesRenderingTest {

    private val footer = "*Generated examples are derived mechanically from this rule's conditions and evaluated by OPA at generation time; " +
        "they illustrate the rule, they are not scenarios chosen by its author.*"

    private fun path(vararg fields: String) = Operand.Path(listOf<PathSegment>(PathSegment.Field("input")) + fields.map { PathSegment.Field(it) })
    private val encrypted = Condition.Comparison(path("encrypted"), Operator.EQ, Operand.Literal("false"))
    private val region = Condition.Comparison(path("region"), Operator.EQ, Operand.Literal("\"eu\""))

    private fun body(vararg conditions: Condition) = RuleBody(conditions.toList(), null, null, SourceRef("f.rego", 1), "")
    private fun rule(name: String, vararg bodies: RuleBody) = RuleGroup(name, null, null, bodies.toList())

    private fun outcome(inputJson: String, matched: Boolean, messages: List<String> = emptyList(), labels: List<Int?> = emptyList()) =
        WorkedExample(Fixture("generated-1", null, Json.parseToJsonElement(inputJson).jsonObject), matched, messages, labels)

    private fun render(rule: RuleGroup, generated: GeneratedExamples?, authored: List<WorkedExample> = emptyList()): String {
        val pkg = PolicyPackage("p", listOf(rule), listOf("f.rego"))
        return MarkdownRenderer.renderCard(rule, pkg, PolicySet(listOf(pkg)), authored, null, { _, _ -> true }, generated)
    }

    @Test
    fun aMatchingExampleOfASingleBodyRuleIsNamedByWhatItSatisfies() {
        val r = rule("unencrypted", body(encrypted))
        val generated = GeneratedExamples(listOf(GeneratedExample(GeneratedExample.Kind.MATCHING, 0, null, outcome("""{"encrypted": false}""", true, listOf("bucket x"), listOf(1)))), emptyList())
        val md = render(r, generated)
        assertThat(md).contains("**Generated examples**")
        assertThat(md).contains("- **satisfies every condition** — matched *(Situation 1)*")
        assertThat(md).contains("  *\"bucket x\"*")
        assertThat(md).contains("  - `encrypted`: `false`")
    }

    @Test
    fun aMultiBodyRuleNamesTheSituationItWasDerivedFrom() {
        val r = rule("two", body(encrypted), body(region))
        val generated = GeneratedExamples(
            listOf(
                GeneratedExample(GeneratedExample.Kind.MATCHING, 0, null, outcome("""{"encrypted": false}""", true, listOf("a"), listOf(1))),
                GeneratedExample(GeneratedExample.Kind.MATCHING, 1, null, outcome("""{"region": "eu"}""", true, listOf("b"), listOf(2))),
            ),
            emptyList(),
        )
        val md = render(r, generated)
        assertThat(md).contains("- **satisfies every condition of Situation 1** — matched *(Situation 1)*")
        assertThat(md).contains("- **satisfies every condition of Situation 2** — matched *(Situation 2)*")
    }

    @Test
    fun aNearMissIsNamedWithThePhraseOfTheConditionItBreaks() {
        val r = rule("unencrypted", body(encrypted, region))
        val generated = GeneratedExamples(emptyList(), listOf(GeneratedExample(GeneratedExample.Kind.NEAR_MISS, 0, 1, outcome("""{"encrypted": false, "region": "eu-other"}""", false))))
        val md = render(r, generated)
        assertThat(md).contains("- **breaks only: `region` is `\"eu\"`** — not matched")
    }

    @Test
    fun aNearMissOfAMultiBodyRuleSaysWhichSituation() {
        val r = rule("two", body(encrypted), body(region))
        val generated = GeneratedExamples(emptyList(), listOf(GeneratedExample(GeneratedExample.Kind.NEAR_MISS, 1, 0, outcome("""{"region": "eu-other"}""", false))))
        assertThat(render(r, generated)).contains("- **breaks only: `region` is `\"eu\"` in Situation 2** — not matched")
    }

    @Test
    fun verdictWordsFollowTheRuleNameLikeAuthorExamples() {
        val r = rule("deny", body(encrypted))
        val generated = GeneratedExamples(
            listOf(GeneratedExample(GeneratedExample.Kind.MATCHING, 0, null, outcome("""{"encrypted": false}""", true, listOf("m"), listOf(1)))),
            listOf(GeneratedExample(GeneratedExample.Kind.NEAR_MISS, 0, 0, outcome("""{"encrypted": true}""", false))),
        )
        val md = render(r, generated)
        assertThat(md).contains("— ❌ denied").contains("— ✅ allowed")
    }

    @Test
    fun theProvenanceFooterAppearsOnceAfterTheEntries() {
        val r = rule("unencrypted", body(encrypted))
        val generated = GeneratedExamples(
            listOf(GeneratedExample(GeneratedExample.Kind.MATCHING, 0, null, outcome("""{"encrypted": false}""", true, listOf("m"), listOf(1)))),
            listOf(GeneratedExample(GeneratedExample.Kind.NEAR_MISS, 0, 0, outcome("""{"encrypted": true}""", false))),
        )
        val md = render(r, generated)
        assertThat(md.windowed(footer.length).count { it == footer }).isEqualTo(1)
        assertThat(md.indexOf(footer)).isGreaterThan(md.indexOf("**breaks only"))
    }

    @Test
    fun theGeneratedSectionComesAfterTheAuthorsOwnSection() {
        val r = rule("unencrypted", body(encrypted))
        val generated = GeneratedExamples(listOf(GeneratedExample(GeneratedExample.Kind.MATCHING, 0, null, outcome("""{"encrypted": false}""", true, listOf("m"), listOf(1)))), emptyList())
        val md = render(r, generated, authored = listOf(outcome("""{"encrypted": true}""", false).copy(fixture = Fixture("author case", null, Json.parseToJsonElement("{}").jsonObject))))
        assertThat(md.indexOf("**Worked examples**")).isGreaterThanOrEqualTo(0).isLessThan(md.indexOf("**Generated examples**"))
        assertThat(md.indexOf("**Generated examples**")).isLessThan(md.indexOf("*Rendering coverage"))
    }

    @Test
    fun nothingGeneratedMeansNoSectionAndNoFooter() {
        val r = rule("unencrypted", body(encrypted))
        for (generated in listOf(null, GeneratedExamples(emptyList(), emptyList()))) {
            val md = render(r, generated)
            assertThat(md).doesNotContain("Generated examples").doesNotContain("satisfies every condition")
        }
    }
}
