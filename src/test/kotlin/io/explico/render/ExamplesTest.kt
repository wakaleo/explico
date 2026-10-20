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
    inner class SameNamedRuleGroups {

        private fun body(template: String?, row: Int) =
            RuleBody(emptyList(), template, template, SourceRef("f.rego", row), "")

        private fun group(name: String, vararg templates: String?) =
            RuleGroup(name, null, null, templates.mapIndexed { i, t -> body(t, i + 1) })

        private val fixture = Fixture("f", null, buildJsonObject { })

        private fun outcomesFor(pkg: io.explico.model.PolicyPackage, vararg messages: String): List<WorkedExample> =
            WorkedExamples.evaluatePackage(listOf(fixture), pkg, warn = {}) {
                buildJsonObject { put("deny", buildJsonArray { messages.forEach { add(JsonPrimitive(it)) } }) }
            }.getValue("deny")

        @Test
        fun everySameNamedGroupIsEvaluatedOncePerFixtureNotOncePerGroup() {
            val pkg = io.explico.model.PolicyPackage("pkg", listOf(group("deny", "a"), group("deny", "b")), listOf("f.rego"))
            assertThat(outcomesFor(pkg, "a")).hasSize(1)
        }

        @Test
        fun eachGroupSeesOnlyTheMessagesItsOwnBodiesCanProduce() {
            val first = group("deny", "bucket %v is not encrypted")
            val second = group("deny", "retention is too short")
            val pkg = io.explico.model.PolicyPackage("pkg", listOf(first, second), listOf("f.rego"))
            val outcomes = outcomesFor(pkg, "bucket logs is not encrypted")

            val forFirst = WorkedExamples.forGroup(first, pkg, outcomes).single()
            val forSecond = WorkedExamples.forGroup(second, pkg, outcomes).single()

            assertThat(forFirst.matched).isTrue()
            assertThat(forFirst.messages).containsExactly("bucket logs is not encrypted")
            assertThat(forSecond.matched).isFalse()
            assertThat(forSecond.messages).isEmpty()
        }

        @Test
        fun situationLabelsAreNumberedWithinTheGroupNotAcrossTheName() {
            val first = group("deny", "one")
            val second = group("deny", "two", "three")
            val pkg = io.explico.model.PolicyPackage("pkg", listOf(first, second), listOf("f.rego"))
            val forSecond = WorkedExamples.forGroup(second, pkg, outcomesFor(pkg, "three")).single()
            assertThat(forSecond.situationLabels).containsExactly(2)
        }

        @Test
        fun ambiguousAttributionShowsNoExamplesRatherThanAWrongOne() {
            // "%v" matches anything, so the message belongs to both groups: it cannot be attributed.
            val first = group("deny", "%v")
            val second = group("deny", "x is bad")
            val pkg = io.explico.model.PolicyPackage("pkg", listOf(first, second), listOf("f.rego"))
            val outcomes = outcomesFor(pkg, "x is bad")
            assertThat(WorkedExamples.forGroup(first, pkg, outcomes)).isEmpty()
            assertThat(WorkedExamples.forGroup(second, pkg, outcomes)).isEmpty()
        }

        @Test
        fun aMessageNoGroupClaimsShowsNoExamples() {
            val first = group("deny", "one")
            val second = group("deny", "two")
            val pkg = io.explico.model.PolicyPackage("pkg", listOf(first, second), listOf("f.rego"))
            assertThat(WorkedExamples.forGroup(first, pkg, outcomesFor(pkg, "mystery"))).isEmpty()
        }

        @Test
        fun groupsWithoutMessageTemplatesCannotBeAttributed() {
            val first = group("deny", null)
            val second = group("deny", "two")
            val pkg = io.explico.model.PolicyPackage("pkg", listOf(first, second), listOf("f.rego"))
            assertThat(WorkedExamples.forGroup(second, pkg, outcomesFor(pkg, "two"))).isEmpty()
        }

        @Test
        fun aSplitDefaultEntryNeverShowsPerRuleExamples() {
            val first = group("deny", "one")
            val second = group("deny", "two")
            val splitDefault = RuleGroup("deny", null, null, emptyList(), isSplitDefaultEntry = true)
            val pkg = io.explico.model.PolicyPackage("pkg", listOf(first, second, splitDefault), listOf("f.rego"))
            assertThat(WorkedExamples.forGroup(splitDefault, pkg, outcomesFor(pkg, "one"))).isEmpty()
        }

        @Test
        fun aLoneGroupKeepsItsOutcomesUntouched() {
            val only = group("deny", "one")
            val pkg = io.explico.model.PolicyPackage("pkg", listOf(only), listOf("f.rego"))
            val outcomes = outcomesFor(pkg, "anything")
            assertThat(WorkedExamples.forGroup(only, pkg, outcomes)).isEqualTo(outcomes)
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
        @org.junit.jupiter.api.DisplayName("cold-start finding: a path that is only the ARGUMENT of a nested operand call (`count(input.reviewers) < 2`) is listed, because it is why the rule fired")
        fun argumentsOfNestedOperandCallsAreListed() {
            val reviewers = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("deployment"), PathSegment.Field("reviewers")))
            val region = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("region")))
            val rule = RuleGroup(
                "deny", null, null,
                bodies = listOf(
                    RuleBody(
                        listOf(
                            Condition.Comparison(Operand.BuiltinCall("count", listOf(reviewers)), Operator.LT, Operand.Literal("2")),
                            Condition.Comparison(Operand.FunctionCall("p", "limit_of", listOf(region)), Operator.GT, Operand.Literal("1")),
                        ),
                        null, null, SourceRef("f.rego", 1), "",
                    ),
                ),
            )
            assertThat(WorkedExamples.referencedPaths(rule)).containsExactly(reviewers, region)
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

    @Nested
    inner class ObjectValuedVerdicts {
        // Review item 16: Gatekeeper-style object elements and partial-object rules. Before, any
        // object element threw IllegalArgumentException (jsonPrimitive) and an empty partial object
        // was reported as matched.

        private val rule = RuleGroup("deny", null, null, listOf(RuleBody(emptyList(), null, "x", SourceRef("f.rego", 1), "")))
        private val pkg = io.explico.model.PolicyPackage("pkg", listOf(rule), listOf("f.rego"))
        private fun outcome(value: kotlinx.serialization.json.JsonElement): WorkedExample =
            WorkedExamples.evaluatePackage(listOf(Fixture("f", null, buildJsonObject { })), pkg, warn = {}) {
                buildJsonObject { put("deny", value) }
            }.getValue("deny").single()

        private fun obj(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>) = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

        @Test
        fun anObjectElementIsShownAsItsMsgField() {
            val o = outcome(buildJsonArray { add(obj("msg" to JsonPrimitive("public acl"), "severity" to JsonPrimitive("high"))) })
            assertThat(o.matched).isTrue()
            assertThat(o.messages).containsExactly("public acl")
        }

        @Test
        fun aMixedSetOfStringsAndObjectsKeepsEveryMessageInOrder() {
            val o = outcome(buildJsonArray { add(JsonPrimitive("plain")); add(obj("msg" to JsonPrimitive("structured"))) })
            assertThat(o.messages).containsExactly("plain", "structured")
        }

        @Test
        fun anObjectElementWithoutAStringMsgIsShownAsVerbatimJsonNeverDropped() {
            val o = outcome(buildJsonArray { add(obj("code" to JsonPrimitive(5))); add(obj("msg" to JsonPrimitive(7))) })
            assertThat(o.matched).isTrue()
            assertThat(o.messages).containsExactly("{\"code\":5}", "{\"msg\":7}")
        }

        @Test
        fun aPartialObjectRuleDecodesItsStringifiedKeysAndMatchesWhenNonEmpty() {
            val o = outcome(obj("{\"msg\":\"not prod\"}" to JsonPrimitive(true), "\"plain key\"" to JsonPrimitive(true)))
            assertThat(o.matched).isTrue()
            assertThat(o.messages).containsExactly("not prod", "plain key")
        }

        @Test
        fun anEmptyPartialObjectIsNotAMatch() {
            val o = outcome(buildJsonObject { })
            assertThat(o.matched).isFalse()
            assertThat(o.messages).isEmpty()
        }

        @Test
        fun aPartialObjectKeyThatIsNotValidJsonIsItsOwnText() {
            val o = outcome(obj("not json {" to JsonPrimitive(true)))
            assertThat(o.messages).containsExactly("not json {")
        }

        @Test
        fun anObjectWithANonTrueValueIsAnOrdinaryCompleteRuleValueKeptVerbatim() {
            val o = outcome(obj("limit" to JsonPrimitive(5)))
            assertThat(o.matched).isTrue()
            assertThat(o.messages).containsExactly("{\"limit\":5}")
        }

        @Test
        fun determinismTwoEvaluationsOfTheSameValueGiveIdenticalMessages() {
            val value = obj("{\"msg\":\"b\"}" to JsonPrimitive(true), "{\"msg\":\"a\"}" to JsonPrimitive(true))
            assertThat(outcome(value).messages).isEqualTo(outcome(value).messages)
        }
    }
}
