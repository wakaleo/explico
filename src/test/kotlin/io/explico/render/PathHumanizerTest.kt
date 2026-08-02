/**
 * Parameterised from spec §6.4's example table and rules 1-8, plus the CHALLENGE
 * boundary cases from the /tdd process. Pure unit tests -- no opa binary, no fixtures.
 */
package io.explico.render

import io.explico.model.PathSegment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class PathHumanizerTest {

    data class Case(val description: String, val segments: List<PathSegment>, val rendered: String, val hasAnyIndex: Boolean)

    companion object {
        @JvmStatic
        fun specExamples(): Stream<Arguments> = listOf(
            // spec §6.4 "Examples" table, verbatim.
            Case(
                "two [_] in one path yield a single any-of flag",
                listOf(
                    PathSegment.Field("input"), PathSegment.Field("pipeline"), PathSegment.Field("stages"),
                    PathSegment.AnyIndex, PathSegment.Field("checks"), PathSegment.AnyIndex, PathSegment.Field("status"),
                ),
                "`pipeline ▸ stages ▸ checks ▸ status`",
                true,
            ),
            Case(
                "camelCase field splits and lowercases",
                listOf(PathSegment.Field("input"), PathSegment.Field("change"), PathSegment.Field("ticket"), PathSegment.Field("approvedBy")),
                "`change ▸ ticket ▸ approved by`",
                false,
            ),
            Case(
                "KeyLiteral renders quoted verbatim, not split",
                listOf(PathSegment.Field("input"), PathSegment.Field("artifact"), PathSegment.Field("labels"), PathSegment.KeyLiteral("signed-off-by")),
                """`artifact ▸ labels ▸ "signed-off-by"`""",
                false,
            ),
            Case(
                "leading data segment is kept, snake_case splits",
                listOf(PathSegment.Field("data"), PathSegment.Field("release"), PathSegment.Field("exempt_services")),
                "`data ▸ release ▸ exempt services`",
                false,
            ),
        ).map { Arguments.of(it) }.stream()

        @JvmStatic
        fun challengeCases(): Stream<Arguments> = listOf(
            Case(
                "single-letter field",
                listOf(PathSegment.Field("input"), PathSegment.Field("a")),
                "`a`",
                false,
            ),
            Case(
                "digits in a name stay attached, not split (sha256)",
                listOf(PathSegment.Field("input"), PathSegment.Field("sha256")),
                "`sha256`",
                false,
            ),
            Case(
                "consecutive capitals split from the following word (URLPath)",
                listOf(PathSegment.Field("input"), PathSegment.Field("URLPath")),
                "`url path`",
                false,
            ),
            Case(
                "mixed camelCase, snake_case and kebab-case in one name",
                listOf(PathSegment.Field("input"), PathSegment.Field("mixed_camelCase-kebab")),
                "`mixed camel case kebab`",
                false,
            ),
            Case(
                "quoted key literal containing spaces and unicode is verbatim",
                listOf(PathSegment.Field("input"), PathSegment.KeyLiteral("x café id")),
                """`"x café id"`""",
                false,
            ),
            Case(
                "VarIndex renders as [each x]",
                listOf(PathSegment.Field("input"), PathSegment.Field("pipeline"), PathSegment.Field("stages"), PathSegment.VarIndex("stage"), PathSegment.Field("status")),
                "`pipeline ▸ stages ▸ [each stage] ▸ status`",
                false,
            ),
        ).map { Arguments.of(it) }.stream()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("specExamples")
    @DisplayName("spec §6.4 example table")
    fun rendersSpecExamples(case: Case) {
        val result = PathHumanizer.humanize(case.segments)
        assertThat(result.rendered).isEqualTo(case.rendered)
        assertThat(result.hasAnyIndex).isEqualTo(case.hasAnyIndex)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("challengeCases")
    @DisplayName("CHALLENGE boundary cases")
    fun rendersChallengeCases(case: Case) {
        val result = PathHumanizer.humanize(case.segments)
        assertThat(result.rendered).isEqualTo(case.rendered)
        assertThat(result.hasAnyIndex).isEqualTo(case.hasAnyIndex)
    }

    // Note: "a var-rooted path whose variable has no `some` binding -> Unrendered" (spec §6.4 rule 7)
    // is NOT a PathHumanizer test case. AstMapper resolves that at mapping time -- when a var-rooted
    // ref has no symbol-table binding, it produces Operand.Unrendered directly (never Operand.Path),
    // so PathHumanizer never sees it. Already covered by
    // AstMapperTest.EdgeCases.varRootedPathWithNoSomeInBindingBecomesOperandUnrendered.
}
