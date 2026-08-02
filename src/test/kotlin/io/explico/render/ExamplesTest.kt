/**
 * Unit tests for §6.7 worked examples: fixture loading, verdict determination, body
 * attribution, selection, and referenced-path resolution. All pure -- no opa binary.
 */
package io.explico.render

import io.explico.model.Condition
import io.explico.model.Operand
import io.explico.model.Operator
import io.explico.model.PathSegment
import io.explico.model.RuleBody
import io.explico.model.RuleGroup
import io.explico.model.SourceRef
import io.explico.opa.OpaInvocationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ExamplesTest {

    private fun fixtureJson(name: String, input: String = "{}") = """{"name": "$name", "input": $input}"""

    @Nested
    inner class Loading {

        @Test
        fun parsesNameDescriptionAndInputInFilenameOrder() {
            val set = parseExampleSet(
                listOf(
                    """{"name": "a", "description": "first", "input": {"x": 1}}""",
                    """{"name": "b", "input": {"y": 2}}""",
                )
            )
            assertThat(set.fixtures).hasSize(2)
            assertThat(set.fixtures[0].name).isEqualTo("a")
            assertThat(set.fixtures[0].description).isEqualTo("first")
            assertThat(set.fixtures[1].description).isNull()
        }

        @Test
        fun duplicateNameThrows() {
            assertThatThrownBy {
                parseExampleSet(listOf(fixtureJson("dup"), fixtureJson("dup")))
            }.isInstanceOf(DuplicateFixtureNameException::class.java)
                .extracting { (it as DuplicateFixtureNameException).name }.isEqualTo("dup")
        }
    }

    @Nested
    inner class EvaluationAndFailureHandling {

        private val denyRule = RuleGroup("deny", null, null, listOf(RuleBody(emptyList(), "msg", "some message", SourceRef("f.rego", 1), "")))
        private val pkg = io.explico.model.PolicyPackage("pkg", listOf(denyRule), listOf("f.rego"))

        @Test
        fun aFixtureWhoseEvalThrowsIsSkippedWithAStderrWarningAndExcludedFromAllRules() {
            val fixtureA = Fixture("good", null, buildJsonObject { })
            val fixtureB = Fixture("bad", null, buildJsonObject { })
            val warnings = mutableListOf<String>()

            val outcomes = WorkedExamples.evaluatePackage(listOf(fixtureA, fixtureB), pkg, warn = { warnings.add(it) }) { fixture ->
                if (fixture.name == "bad") throw OpaInvocationException("boom: syntax error")
                buildJsonObject { put("deny", buildJsonArray { add(JsonPrimitive("x")) }) }
            }

            assertThat(warnings).hasSize(1)
            assertThat(warnings.single()).contains("bad").contains("boom: syntax error")
            assertThat(outcomes.getValue("deny")).hasSize(1) // only "good" survives
            assertThat(outcomes.getValue("deny").single().fixture.name).isEqualTo("good")
        }

        @Test
        fun successfulFixturesStillEvaluateAfterAFailedOne() {
            val fixtures = listOf(Fixture("fails", null, buildJsonObject { }), Fixture("succeeds", null, buildJsonObject { }))
            val outcomes = WorkedExamples.evaluatePackage(fixtures, pkg, warn = {}) { fixture ->
                if (fixture.name == "fails") throw OpaInvocationException("nope")
                buildJsonObject { put("deny", buildJsonArray { }) }
            }
            assertThat(outcomes.getValue("deny").map { it.fixture.name }).containsExactly("succeeds")
        }

        @Test
        fun setRuleMatchedWhenNonEmptyCapturesMessages() {
            val fixture = Fixture("f", null, buildJsonObject { })
            val outcomes = WorkedExamples.evaluatePackage(listOf(fixture), pkg, warn = {}) {
                buildJsonObject { put("deny", buildJsonArray { add(JsonPrimitive("some message")) }) }
            }
            val outcome = outcomes.getValue("deny").single()
            assertThat(outcome.matched).isTrue()
            assertThat(outcome.messages).containsExactly("some message")
        }

        @Test
        fun setRuleNotMatchedWhenEmpty() {
            val fixture = Fixture("f", null, buildJsonObject { })
            val outcomes = WorkedExamples.evaluatePackage(listOf(fixture), pkg, warn = {}) {
                buildJsonObject { put("deny", buildJsonArray { }) }
            }
            val outcome = outcomes.getValue("deny").single()
            assertThat(outcome.matched).isFalse()
            assertThat(outcome.messages).isEmpty()
        }

        @Test
        fun booleanRuleTrueIsMatchedFalseAndUndefinedAreNotMatched() {
            val boolRule = RuleGroup("exempt_service", null, null, listOf(RuleBody(emptyList(), null, null, SourceRef("f.rego", 1), "")))
            val boolPkg = io.explico.model.PolicyPackage("pkg", listOf(boolRule), listOf("f.rego"))
            val fixture = Fixture("f", null, buildJsonObject { })

            val trueOutcome = WorkedExamples.evaluatePackage(listOf(fixture), boolPkg, warn = {}) {
                buildJsonObject { put("exempt_service", true) }
            }.getValue("exempt_service").single()
            assertThat(trueOutcome.matched).isTrue()

            val undefinedOutcome = WorkedExamples.evaluatePackage(listOf(fixture), boolPkg, warn = {}) {
                buildJsonObject { } // key absent entirely, as real opa eval produces for an undefined complete rule with no default
            }.getValue("exempt_service").single()
            assertThat(undefinedOutcome.matched).isFalse()
        }
    }

    @Nested
    inner class BodyAttribution {

        private val pkg = io.explico.model.PolicyPackage("pkg", emptyList(), listOf("f.rego"))

        @Test
        fun distinctTemplatesGetSituationLabelsIncludingMultipleMatchesInOneFixture() {
            // Reproduces REL-004's real shape: 2 distinct-templated bodies, one fixture matches both.
            val rule = RuleGroup(
                "deny", null, null,
                bodies = listOf(
                    RuleBody(emptyList(), "no release manager approval is recorded", "no release manager approval is recorded", SourceRef("f.rego", 1), ""),
                    RuleBody(emptyList(), null, "deployment falls inside freeze window %v", SourceRef("f.rego", 2), ""),
                ),
            )
            val pkgWithRule = io.explico.model.PolicyPackage("pkg", listOf(rule), listOf("f.rego"))
            val fixture = Fixture("f", null, buildJsonObject { })

            val outcome = WorkedExamples.evaluatePackage(listOf(fixture), pkgWithRule, warn = {}) {
                buildJsonObject {
                    put(
                        "deny",
                        buildJsonArray {
                            add(JsonPrimitive("no release manager approval is recorded"))
                            add(JsonPrimitive("deployment falls inside freeze window year-end change freeze"))
                        },
                    )
                }
            }.getValue("deny").single()

            assertThat(outcome.situationLabels).containsExactly(1, 2)
        }

        @Test
        fun missingTemplateOnAnyBodyMeansNoLabelsAtAll() {
            val rule = RuleGroup(
                "deny", null, null,
                bodies = listOf(
                    RuleBody(emptyList(), "a", "a", SourceRef("f.rego", 1), ""),
                    RuleBody(emptyList(), null, null, SourceRef("f.rego", 2), ""), // var-rooted, unhumanisable AND no template
                ),
            )
            val pkgWithRule = io.explico.model.PolicyPackage("pkg", listOf(rule), listOf("f.rego"))
            val fixture = Fixture("f", null, buildJsonObject { })
            val outcome = WorkedExamples.evaluatePackage(listOf(fixture), pkgWithRule, warn = {}) {
                buildJsonObject { put("deny", buildJsonArray { add(JsonPrimitive("a")) }) }
            }.getValue("deny").single()
            assertThat(outcome.situationLabels).containsExactly(null as Int?)
        }

        @Test
        fun duplicateTemplatesMeanNoLabelsAtAll() {
            val rule = RuleGroup(
                "deny", null, null,
                bodies = listOf(
                    RuleBody(emptyList(), "a", "same text", SourceRef("f.rego", 1), ""),
                    RuleBody(emptyList(), "a", "same text", SourceRef("f.rego", 2), ""),
                ),
            )
            val pkgWithRule = io.explico.model.PolicyPackage("pkg", listOf(rule), listOf("f.rego"))
            val fixture = Fixture("f", null, buildJsonObject { })
            val outcome = WorkedExamples.evaluatePackage(listOf(fixture), pkgWithRule, warn = {}) {
                buildJsonObject { put("deny", buildJsonArray { add(JsonPrimitive("same text")) }) }
            }.getValue("deny").single()
            assertThat(outcome.situationLabels).containsExactly(null as Int?)
        }

        @Test
        fun sprintfTemplateMatchesTheSubstitutedRealMessage() {
            val rule = RuleGroup(
                "deny", null, null,
                bodies = listOf(RuleBody(emptyList(), "release [deployment id] has no approved change ticket", "release %v has no approved change ticket", SourceRef("f.rego", 1), "")),
            )
            val pkgWithRule = io.explico.model.PolicyPackage("pkg", listOf(rule), listOf("f.rego"))
            val fixture = Fixture("f", null, buildJsonObject { })
            val outcome = WorkedExamples.evaluatePackage(listOf(fixture), pkgWithRule, warn = {}) {
                buildJsonObject { put("deny", buildJsonArray { add(JsonPrimitive("release rel-1002 has no approved change ticket")) }) }
            }.getValue("deny").single()
            assertThat(outcome.situationLabels).containsExactly(1)
        }
    }

    @Nested
    inner class Selection {

        private fun outcome(name: String, matched: Boolean) = WorkedExample(Fixture(name, null, buildJsonObject { }), matched, emptyList(), emptyList())

        @Test
        fun capsAtThreeMatchingAndTwoNonMatchingMatchedGroupFirst() {
            val outcomes = listOf(
                outcome("m1", true), outcome("nm1", false), outcome("m2", true),
                outcome("nm2", false), outcome("m3", true), outcome("m4", true), outcome("nm3", false),
            )
            val selected = WorkedExamples.select(outcomes)
            assertThat(selected.map { it.fixture.name }).containsExactly("m1", "m2", "m3", "nm1", "nm2")
        }
    }

    @Nested
    inner class OutcomeWord {

        @Test
        fun denyAndViolationInvertBetweenDeniedAndAllowed() {
            assertThat(WorkedExamples.outcomeWord("deny", matched = true)).isEqualTo("❌ denied")
            assertThat(WorkedExamples.outcomeWord("deny", matched = false)).isEqualTo("✅ allowed")
            assertThat(WorkedExamples.outcomeWord("violation", matched = true)).isEqualTo("❌ denied")
            assertThat(WorkedExamples.outcomeWord("violation", matched = false)).isEqualTo("✅ allowed")
        }

        @Test
        fun allowInvertsBetweenAllowedAndDenied() {
            assertThat(WorkedExamples.outcomeWord("allow", matched = true)).isEqualTo("✅ allowed")
            assertThat(WorkedExamples.outcomeWord("allow", matched = false)).isEqualTo("❌ denied")
        }

        @Test
        fun anyOtherNameUsesPlainMatchedWords() {
            assertThat(WorkedExamples.outcomeWord("is_release_candidate", matched = true)).isEqualTo("matched")
            assertThat(WorkedExamples.outcomeWord("is_release_candidate", matched = false)).isEqualTo("not matched")
        }
    }

    @Nested
    inner class ReferencedPathsAndResolution {

        @Test
        fun dedupesAcrossBodiesInFirstAppearanceOrderAndDropsDataRootedPaths() {
            val envPath = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("deployment"), PathSegment.Field("environment")))
            val dataPath = Operand.Path(listOf(PathSegment.Field("data"), PathSegment.Field("release"), PathSegment.Field("exempt_services")))
            val approvedPath = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("change"), PathSegment.Field("ticket"), PathSegment.Field("approved")))

            val rule = RuleGroup(
                "deny", null, null,
                bodies = listOf(
                    RuleBody(listOf(Condition.Comparison(envPath, Operator.EQ, Operand.Literal("\"production\""))), null, null, SourceRef("f.rego", 1), ""),
                    RuleBody(
                        listOf(
                            Condition.Membership(false, envPath, dataPath), // envPath repeats -- should not duplicate
                            Condition.Truthy(approvedPath, true),
                        ),
                        null, null, SourceRef("f.rego", 2), "",
                    ),
                ),
            )
            val paths = WorkedExamples.referencedPaths(rule)
            assertThat(paths).containsExactly(envPath, approvedPath) // dataPath excluded, envPath deduped, order preserved
        }

        @Test
        fun someInCollectionIsExcludedButVarRootedElementAccessIsIncluded() {
            val collectionPath = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("pipeline"), PathSegment.Field("stages")))
            val statusPath = Operand.Path(
                listOf(PathSegment.Field("input"), PathSegment.Field("pipeline"), PathSegment.Field("stages"), PathSegment.VarIndex("stage"), PathSegment.Field("status"))
            )
            val rule = RuleGroup(
                "deny", null, null,
                bodies = listOf(
                    RuleBody(
                        listOf(
                            Condition.SomeIn("stage", collectionPath),
                            Condition.Comparison(statusPath, Operator.NEQ, Operand.Literal("\"passed\"")),
                        ),
                        null, null, SourceRef("f.rego", 1), "",
                    ),
                ),
            )
            val paths = WorkedExamples.referencedPaths(rule)
            assertThat(paths).containsExactly(statusPath)
        }

        @Test
        fun resolvesAPresentScalarValue() {
            val path = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("deployment"), PathSegment.Field("environment")))
            val input = buildJsonObject { put("deployment", buildJsonObject { put("environment", "production") }) }
            assertThat(WorkedExamples.resolvePathValue(path, input)).isEqualTo("\"production\"")
        }

        @Test
        fun resolvesToNullWhenPathIsMissing() {
            val path = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("change"), PathSegment.Field("ticket"), PathSegment.Field("approved")))
            val input = buildJsonObject { put("change", buildJsonObject { }) }
            assertThat(WorkedExamples.resolvePathValue(path, input)).isNull()
        }

        @Test
        fun keyLiteralSegmentResolvesByExactKey() {
            val path = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("artifact"), PathSegment.Field("labels"), PathSegment.KeyLiteral("signed-off-by")))
            val input = buildJsonObject { put("artifact", buildJsonObject { put("labels", buildJsonObject { put("signed-off-by", "bjones") }) }) }
            assertThat(WorkedExamples.resolvePathValue(path, input)).isEqualTo("\"bjones\"")
        }

        @Test
        fun anyIndexCollectsAcrossElementsCommaSeparated() {
            val path = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("pipeline"), PathSegment.Field("checks"), PathSegment.AnyIndex, PathSegment.Field("status")))
            val input = buildJsonObject {
                put(
                    "pipeline",
                    buildJsonObject {
                        put(
                            "checks",
                            buildJsonArray {
                                add(buildJsonObject { put("status", "passed") })
                                add(buildJsonObject { put("status", "failed") })
                            },
                        )
                    },
                )
            }
            assertThat(WorkedExamples.resolvePathValue(path, input)).isEqualTo("\"passed\", \"failed\"")
        }

        @Test
        fun anyIndexCapsAtThreeElementsWithEllipsis() {
            val path = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("items"), PathSegment.AnyIndex, PathSegment.Field("v")))
            val input = buildJsonObject {
                put(
                    "items",
                    buildJsonArray { repeat(5) { i -> add(buildJsonObject { put("v", i) }) } },
                )
            }
            assertThat(WorkedExamples.resolvePathValue(path, input)).isEqualTo("0, 1, 2, …")
        }
    }
}
