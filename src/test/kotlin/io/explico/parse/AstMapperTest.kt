/**
 * Unit tests for AstMapper (spec §5), driven entirely from the checked-in
 * `opa parse` captures in src/test/resources/ast/ -- no `opa` binary needed.
 */
package io.explico.parse

import io.explico.model.Condition
import io.explico.model.Operand
import io.explico.model.Operator
import io.explico.model.PathSegment
import io.explico.model.PolicySet
import io.explico.opa.OpaInspectResult
import io.explico.opa.OpaModule
import io.explico.opa.opaJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class AstMapperTest {

    private fun fixture(name: String): OpaModule {
        val json = Files.readString(Path.of("src/test/resources/ast/$name.json"))
        return opaJson.decodeFromString(OpaModule.serializer(), json)
    }

    private fun onlyFile(name: String, sourceFile: String): PolicySet =
        AstMapper.mapPolicySet(listOf(ParsedFile(sourceFile, fixture(name))))

    private fun wholePack(): PolicySet = AstMapper.mapPolicySet(
        listOf(
            ParsedFile("approvals/change_approval.rego", fixture("change_approval")),
            ParsedFile("evidence/pipeline_evidence.rego", fixture("pipeline_evidence")),
            ParsedFile("provenance/artifact_provenance.rego", fixture("artifact_provenance")),
            ParsedFile("governance/release_governance.rego", fixture("release_governance")),
            ParsedFile("exemptions/exemptions.rego", fixture("exemptions")),
        )
    )

    private fun inspectFixture(): OpaInspectResult {
        val json = Files.readString(Path.of("src/test/resources/ast/inspect_all.json"))
        return opaJson.decodeFromString(OpaInspectResult.serializer(), json)
    }

    private fun wholePackWithMetadata(): PolicySet = AstMapper.mapPolicySet(
        listOf(
            ParsedFile("approvals/change_approval.rego", fixture("change_approval")),
            ParsedFile("evidence/pipeline_evidence.rego", fixture("pipeline_evidence")),
            ParsedFile("provenance/artifact_provenance.rego", fixture("artifact_provenance")),
            ParsedFile("governance/release_governance.rego", fixture("release_governance")),
            ParsedFile("exemptions/exemptions.rego", fixture("exemptions")),
        ),
        inspectFixture(),
    )

    /** Base64-encodes like opa's own `location.text` does, for hand-built synthetic AST JSON below. */
    private fun b64(s: String): String = java.util.Base64.getEncoder().encodeToString(s.toByteArray())

    private fun moduleOf(json: String): OpaModule = opaJson.decodeFromString(OpaModule.serializer(), json)

    @Nested
    inner class ExemptionsRego {
        // Simplest file: single rule, single body, Membership against a data.* Path collection, no METADATA.

        @Test
        fun mapsPackagePathAndSourceFile() {
            val policySet = onlyFile("exemptions", "exemptions/exemptions.rego")
            assertThat(policySet.packages).hasSize(1)
            assertThat(policySet.packages[0].path).isEqualTo("release.exemptions")
            assertThat(policySet.packages[0].sourceFiles).containsExactly("exemptions/exemptions.rego")
        }

        @Test
        fun mapsMembershipOfDeploymentServiceAgainstDataPath() {
            val rule = onlyFile("exemptions", "exemptions/exemptions.rego").packages[0].rules.single()
            assertThat(rule.name).isEqualTo("exempt_service")
            assertThat(rule.metadata).isNull()
            assertThat(rule.default).isNull()

            val condition = rule.bodies.single().conditions.single() as Condition.Membership
            assertThat(condition.negated).isFalse()
            assertThat((condition.member as Operand.Path).segments).containsExactly(
                PathSegment.Field("input"), PathSegment.Field("deployment"), PathSegment.Field("service"),
            )
            assertThat((condition.collection as Operand.Path).segments).containsExactly(
                PathSegment.Field("data"), PathSegment.Field("release"), PathSegment.Field("exempt_services"),
            )
        }

        @Test
        fun noProducesValueForACompleteRule() {
            val rule = onlyFile("exemptions", "exemptions/exemptions.rego").packages[0].rules.single()
            assertThat(rule.bodies.single().producesValue).isNull()
        }
    }

    @Nested
    inner class ChangeApprovalRego {
        // REL-001: two bodies, eq-vs-literal, negated truthy, path-vs-path eq, sprintf messages with plain placeholders.

        @Test
        fun mapsTwoBodiesInSourceOrder() {
            val rule = onlyFile("change_approval", "approvals/change_approval.rego").packages[0].rules.single()
            assertThat(rule.name).isEqualTo("deny")
            assertThat(rule.bodies).hasSize(2)
            assertThat(rule.bodies[0].sourceLocation.row).isEqualTo(16)
            assertThat(rule.bodies[1].sourceLocation.row).isEqualTo(22)
        }

        @Test
        fun firstBodyIsEnvironmentEqAndNegatedTruthyWithSprintfPlaceholder() {
            val body = onlyFile("change_approval", "approvals/change_approval.rego").packages[0].rules.single().bodies[0]
            assertThat(body.conditions).hasSize(2)

            val eq = body.conditions[0] as Condition.Comparison
            assertThat(eq.op).isEqualTo(Operator.EQ)
            assertThat((eq.left as Operand.Path).segments).containsExactly(
                PathSegment.Field("input"), PathSegment.Field("deployment"), PathSegment.Field("environment"),
            )
            assertThat((eq.right as Operand.Literal).rendered).isEqualTo("\"production\"")

            val notApproved = body.conditions[1] as Condition.Truthy
            assertThat(notApproved.negated).isTrue()
            assertThat((notApproved.operand as Operand.Path).segments).containsExactly(
                PathSegment.Field("input"), PathSegment.Field("change"), PathSegment.Field("ticket"), PathSegment.Field("approved"),
            )

            assertThat(body.producesValue).isEqualTo("release [deployment id] has no approved change ticket")

            // spec §7.3: the whole rule's verbatim source, base64-decoded from the AST's own
            // rule-level location.text -- the same mechanism as a fallback's sourceText, never a
            // second file read sliced by SourceRef's row.
            assertThat(body.sourceText).isEqualTo(
                "deny contains msg if {\n" +
                    "\tinput.deployment.environment == \"production\"\n" +
                    "\tnot input.change.ticket.approved\n" +
                    "\tmsg := sprintf(\"release %v has no approved change ticket\", [input.deployment.id])\n" +
                    "}",
            )
        }

        @Test
        fun secondBodyIsPathVersusPathComparison() {
            val body = onlyFile("change_approval", "approvals/change_approval.rego").packages[0].rules.single().bodies[1]
            assertThat(body.conditions).hasSize(2)

            val authorEqApprover = body.conditions[1] as Condition.Comparison
            assertThat(authorEqApprover.op).isEqualTo(Operator.EQ)
            assertThat((authorEqApprover.left as Operand.Path).segments).containsExactly(
                PathSegment.Field("input"), PathSegment.Field("change"), PathSegment.Field("author"),
            )
            assertThat((authorEqApprover.right as Operand.Path).segments).containsExactly(
                PathSegment.Field("input"), PathSegment.Field("change"), PathSegment.Field("approver"),
            )

            assertThat(body.producesValue).isEqualTo("change [change id] was approved by its author")
        }
    }

    @Nested
    inner class PipelineEvidenceRego {
        // REL-002: same-package RuleReference, cross-package negated RuleReference (import alias), some-in, var-rooted path, set membership.

        @Test
        fun denyBodyReferencesIsReleaseCandidateAndNegatedCrossPackageExemptService() {
            val body = wholePack().packages.first { it.path == "release.evidence" }.rules.first { it.name == "deny" }.bodies.single()

            val samePackageRef = body.conditions[0] as Condition.RuleReference
            assertThat(samePackageRef.packagePath).isEqualTo("release.evidence")
            assertThat(samePackageRef.ruleName).isEqualTo("is_release_candidate")
            assertThat(samePackageRef.negated).isFalse()

            val crossPackageRef = body.conditions[1] as Condition.RuleReference
            assertThat(crossPackageRef.packagePath).isEqualTo("release.exemptions")
            assertThat(crossPackageRef.ruleName).isEqualTo("exempt_service")
            assertThat(crossPackageRef.negated).isTrue()
        }

        @Test
        fun someInBindsStageAndLaterVarRootedPathResolvesThroughIt() {
            val body = wholePack().packages.first { it.path == "release.evidence" }.rules.first { it.name == "deny" }.bodies.single()

            val someIn = body.conditions[2] as Condition.SomeIn
            assertThat(someIn.variable).isEqualTo("stage")
            assertThat((someIn.collection as Operand.Path).segments).containsExactly(
                PathSegment.Field("input"), PathSegment.Field("pipeline"), PathSegment.Field("stages"),
            )

            val statusNeq = body.conditions[3] as Condition.Comparison
            assertThat(statusNeq.op).isEqualTo(Operator.NEQ)
            assertThat((statusNeq.left as Operand.Path).segments).containsExactly(
                PathSegment.Field("input"), PathSegment.Field("pipeline"), PathSegment.Field("stages"),
                PathSegment.VarIndex("stage"), PathSegment.Field("status"),
            )
            assertThat((statusNeq.right as Operand.Literal).rendered).isEqualTo("\"passed\"")
        }

        @Test
        fun varRootedSprintfPlaceholderIsDeferredToNull() {
            // stage.name is var-rooted; the minimal placeholder helper only handles plain input.-rooted chains.
            val body = wholePack().packages.first { it.path == "release.evidence" }.rules.first { it.name == "deny" }.bodies.single()
            assertThat(body.producesValue).isNull()
            // messageTemplate stays populated regardless -- it doesn't try to render the arguments (spec §6.7).
            assertThat(body.messageTemplate).isEqualTo("pipeline stage %v did not pass")
        }

        @Test
        fun isReleaseCandidateMapsSetMembership() {
            val body = wholePack().packages.first { it.path == "release.evidence" }.rules.first { it.name == "is_release_candidate" }.bodies.single()
            val membership = body.conditions.single() as Condition.Membership
            assertThat(membership.negated).isFalse()
            assertThat((membership.member as Operand.Path).segments).containsExactly(
                PathSegment.Field("input"), PathSegment.Field("deployment"), PathSegment.Field("environment"),
            )
            assertThat((membership.collection as Operand.Literal).rendered).isEqualTo("\"production\", \"staging\"")
        }
    }

    @Nested
    inner class ArtifactProvenanceRego {
        // REL-003: negated builtin (startswith), quoted key-literal path, string-literal message (no sprintf).

        @Test
        fun negatedStartswithBuiltinCall() {
            val body = onlyFile("artifact_provenance", "provenance/artifact_provenance.rego").packages[0].rules.single().bodies[0]
            val startswith = body.conditions[1] as Condition.BuiltinCall
            assertThat(startswith.name).isEqualTo("startswith")
            assertThat(startswith.negated).isTrue()
            assertThat((startswith.args[0] as Operand.Path).segments).containsExactly(
                PathSegment.Field("input"), PathSegment.Field("artifact"), PathSegment.Field("source_branch"),
            )
            assertThat((startswith.args[1] as Operand.Literal).rendered).isEqualTo("\"release/\"")
        }

        @Test
        fun sprintfPlaceholderSplitsSnakeCaseFieldName() {
            val body = onlyFile("artifact_provenance", "provenance/artifact_provenance.rego").packages[0].rules.single().bodies[0]
            assertThat(body.producesValue).isEqualTo("artifact was built from branch [artifact source branch], not a release branch")
        }

        @Test
        fun quotedKeyLiteralPathAndStringLiteralMessage() {
            val body = onlyFile("artifact_provenance", "provenance/artifact_provenance.rego").packages[0].rules.single().bodies[1]
            val notSignedOff = body.conditions[1] as Condition.Truthy
            assertThat(notSignedOff.negated).isTrue()
            assertThat((notSignedOff.operand as Operand.Path).segments).containsExactly(
                PathSegment.Field("input"), PathSegment.Field("artifact"), PathSegment.Field("labels"),
                PathSegment.KeyLiteral("signed-off-by"),
            )
            assertThat(body.producesValue).isEqualTo("artifact carries no signed-off-by label")
        }
    }

    @Nested
    inner class ReleaseGovernanceRego {
        // REL-004: comprehension-wrapped-in-count -> Unrendered, every -> Unrendered, freeze-window var-rooted comparisons, same-package negated RuleReference.

        @Test
        fun hasThreeDenyBodiesAndAHelperRule() {
            val pkg = onlyFile("release_governance", "governance/release_governance.rego").packages[0]
            val deny = pkg.rules.single { it.name == "deny" }
            assertThat(deny.bodies).hasSize(3)
            assertThat(pkg.rules.map { it.name }).contains("all_checks_passed")
        }

        @Test
        fun countOfComprehensionFallsBackWithVerbatimSourceFromLocationText() {
            val body = onlyFile("release_governance", "governance/release_governance.rego").packages[0].rules.single { it.name == "deny" }.bodies[0]
            val fallback = body.conditions[1] as Condition.Unrendered
            assertThat(fallback.reason).isEqualTo("comprehension")
            assertThat(fallback.sourceText).startsWith("count({a |")
            assertThat(fallback.sourceText).contains("release-manager")
        }

        @Test
        fun stringLiteralMessageForTheComprehensionBody() {
            val body = onlyFile("release_governance", "governance/release_governance.rego").packages[0].rules.single { it.name == "deny" }.bodies[0]
            assertThat(body.producesValue).isEqualTo("no release manager approval is recorded")
            // A plain string literal needs no humanisation, so messageTemplate equals producesValue.
            assertThat(body.messageTemplate).isEqualTo("no release manager approval is recorded")
        }

        @Test
        fun freezeWindowBodyUsesSomeInAndVarRootedGteLteComparisons() {
            val body = onlyFile("release_governance", "governance/release_governance.rego").packages[0].rules.single { it.name == "deny" }.bodies[1]

            val someIn = body.conditions[1] as Condition.SomeIn
            assertThat(someIn.variable).isEqualTo("window")
            assertThat((someIn.collection as Operand.Path).segments).containsExactly(
                PathSegment.Field("data"), PathSegment.Field("release"), PathSegment.Field("freeze_windows"),
            )

            val gte = body.conditions[2] as Condition.Comparison
            assertThat(gte.op).isEqualTo(Operator.GTE)
            assertThat((gte.right as Operand.Path).segments).containsExactly(
                PathSegment.Field("data"), PathSegment.Field("release"), PathSegment.Field("freeze_windows"),
                PathSegment.VarIndex("window"), PathSegment.Field("start"),
            )

            val lte = body.conditions[3] as Condition.Comparison
            assertThat(lte.op).isEqualTo(Operator.LTE)
            assertThat((lte.right as Operand.Path).segments).containsExactly(
                PathSegment.Field("data"), PathSegment.Field("release"), PathSegment.Field("freeze_windows"),
                PathSegment.VarIndex("window"), PathSegment.Field("finish"),
            )

            // producesValue is null (window.name is var-rooted, can't be humanised for display -- session 2/3)
            // but messageTemplate stays populated: body attribution (§6.7) needs the template's
            // literal/wildcard shape, not a display rendering of the argument.
            assertThat(body.producesValue).isNull()
            assertThat(body.messageTemplate).isEqualTo("deployment falls inside freeze window %v")
        }

        @Test
        fun thirdBodyReferencesAllChecksPassedNegated() {
            val body = onlyFile("release_governance", "governance/release_governance.rego").packages[0].rules.single { it.name == "deny" }.bodies[2]
            val ref = body.conditions[1] as Condition.RuleReference
            assertThat(ref.packagePath).isEqualTo("release.governance")
            assertThat(ref.ruleName).isEqualTo("all_checks_passed")
            assertThat(ref.negated).isTrue()
            assertThat(body.producesValue).isEqualTo("not all mandatory checks passed")
        }

        @Test
        fun everyStatementFallsBackWithVerbatimSourceFromLocationText() {
            val body = onlyFile("release_governance", "governance/release_governance.rego").packages[0].rules.single { it.name == "all_checks_passed" }.bodies.single()
            val fallback = body.conditions.single() as Condition.Unrendered
            assertThat(fallback.reason).isEqualTo("every")
            assertThat(fallback.sourceText).startsWith("every check in")
            assertThat(fallback.sourceText).contains("check.status == \"passed\"")
        }
    }

    @Nested
    inner class MetadataAttachment {
        // opa inspect's own "path" field (packagePath + ruleName) is the match key, not
        // row-proximity -- opa has already resolved which rule an annotation belongs to,
        // including deduplicating a document-scoped annotation across multiple bodies
        // (confirmed: release.approvals.deny has 2 bodies but exactly 1 inspect entry).

        @Test
        fun documentScopedMetadataAttachesToAllBodiesOfAMultiBodyRule() {
            val rule = wholePackWithMetadata().packages.first { it.path == "release.approvals" }.rules.single { it.name == "deny" }
            val metadata = rule.metadata
            assertThat(metadata).isNotNull()
            assertThat(metadata!!.title).isEqualTo("Production change approval")
            assertThat(metadata.controlId).isEqualTo("REL-001")
            assertThat(metadata.frameworks).containsExactly("SOC 2 CC8.1", "ISO 27001 A.8.32")
        }

        @Test
        fun ruleScopedMetadataAttachesToASingleBodyRule() {
            val rule = wholePackWithMetadata().packages.first { it.path == "release.evidence" }.rules.single { it.name == "is_release_candidate" }
            val metadata = rule.metadata
            assertThat(metadata).isNotNull()
            assertThat(metadata!!.title).isEqualTo("Release candidate environments")
            assertThat(metadata.controlId).isNull()
            assertThat(metadata.frameworks).isEmpty()
        }

        @Test
        fun ruleWithNoMetadataAtAllStaysNull() {
            val rule = wholePackWithMetadata().packages.first { it.path == "release.exemptions" }.rules.single { it.name == "exempt_service" }
            assertThat(rule.metadata).isNull()
        }

        @Test
        fun noInspectResultLeavesAllMetadataNullExactlyAsBefore() {
            val rule = wholePack().packages.first { it.path == "release.approvals" }.rules.single { it.name == "deny" }
            assertThat(rule.metadata).isNull()
        }
    }

    @Nested
    inner class DeterminismAndStructure {

        @Test
        fun rulesWithinAPackageAreSortedByName() {
            val pkg = wholePack().packages.first { it.path == "release.evidence" }
            assertThat(pkg.rules.map { it.name }).isSorted()
        }

        @Test
        fun packagesAreSortedByPath() {
            val paths = wholePack().packages.map { it.path }
            assertThat(paths).isSorted()
        }

        @Test
        fun sourceFilesWithinAPackageAreSorted() {
            wholePack().packages.forEach { pkg ->
                assertThat(pkg.sourceFiles).isSorted()
            }
        }
    }

    @Nested
    inner class EdgeCases {
        // Constructs not exercised by the acceptance pack's five real files, hand-built as synthetic
        // AST JSON -- each term/expr shape below is grounded in the shapes already verified against
        // real `opa parse` output elsewhere in this file, just recombined into untested arrangements.

        @Test
        fun emptyFileListProducesAnEmptyPolicySet() {
            assertThat(AstMapper.mapPolicySet(emptyList()).packages).isEmpty()
        }

        @Test
        fun varRootedPathWithNoSomeInBindingBecomesOperandUnrendered() {
            // `stage.status == "x"` with no preceding `some stage in ...` in the same body.
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "deny", "key": {"type":"var","value":"msg"}, "ref": [{"type":"var","value":"deny"}]},
                      "body": [
                        {
                          "index": 0,
                          "location": {"file":"scratch.rego","row":1,"text":"${b64("stage.status == \"x\"")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"equal"}]},
                            {"type":"ref","location":{"text":"${b64("stage.status")}"},"value":[
                              {"type":"var","value":"stage","location":{"text":"${b64("stage")}"}},
                              {"type":"string","value":"status"}
                            ]},
                            {"type":"string","value":"x"}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val policySet = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module)))
            val condition = policySet.packages[0].rules[0].bodies[0].conditions[0] as Condition.Comparison
            val unrendered = condition.left as Operand.Unrendered
            assertThat(unrendered.sourceText).isEqualTo("stage.status")
        }

        @Test
        fun unboundMiddlePositionVarIndexBecomesOperandUnrendered() {
            // `input.pipeline.checks[i].status == "x"` -- bracket-index `i` is NOT bound anywhere
            // (no `some i in ...`), unlike a var-rooted path. Spec §6.4 rule 6: "Unbound: [x]" at the
            // PathHumanizer level is unreachable in the real pipeline -- AstMapper promotes the whole
            // operand to Unrendered instead, mirroring rule 7's root-position handling.
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "deny", "key": {"type":"var","value":"msg"}, "ref": [{"type":"var","value":"deny"}]},
                      "body": [
                        {
                          "index": 0,
                          "location": {"file":"scratch.rego","row":1,"text":"${b64("input.pipeline.checks[i].status == \"x\"")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"equal"}]},
                            {"type":"ref","location":{"text":"${b64("input.pipeline.checks[i].status")}"},"value":[
                              {"type":"var","value":"input"},
                              {"type":"string","value":"pipeline"},
                              {"type":"string","value":"checks"},
                              {"type":"var","value":"i"},
                              {"type":"string","value":"status"}
                            ]},
                            {"type":"string","value":"x"}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val policySet = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module)))
            val condition = policySet.packages[0].rules[0].bodies[0].conditions[0] as Condition.Comparison
            val unrendered = condition.left as Operand.Unrendered
            assertThat(unrendered.sourceText).isEqualTo("input.pipeline.checks[i].status")
        }

        @Test
        fun boundMiddlePositionVarIndexRendersLikeTheVarRootedCase() {
            // `some i in input.pipeline.checks` then `input.pipeline.checks[i].status == "x"` --
            // bracket-index `i` IS bound, so it becomes a normal VarIndex (renders "[each i]" via
            // PathHumanizer), not Unrendered.
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "deny", "key": {"type":"var","value":"msg"}, "ref": [{"type":"var","value":"deny"}]},
                      "body": [
                        {
                          "index": 0,
                          "location": {"file":"scratch.rego","row":1,"text":"${b64("some i in input.pipeline.checks")}"},
                          "terms": {"symbols":[
                            {"type":"call","value":[
                              {"type":"ref","value":[{"type":"var","value":"internal"},{"type":"string","value":"member_2"}]},
                              {"type":"var","value":"i"},
                              {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"pipeline"},{"type":"string","value":"checks"}]}
                            ]}
                          ]}
                        },
                        {
                          "index": 1,
                          "location": {"file":"scratch.rego","row":2,"text":"${b64("input.pipeline.checks[i].status == \"x\"")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"equal"}]},
                            {"type":"ref","value":[
                              {"type":"var","value":"input"},
                              {"type":"string","value":"pipeline"},
                              {"type":"string","value":"checks"},
                              {"type":"var","value":"i"},
                              {"type":"string","value":"status"}
                            ]},
                            {"type":"string","value":"x"}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val policySet = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module)))
            val condition = policySet.packages[0].rules[0].bodies[0].conditions[1] as Condition.Comparison
            val path = condition.left as Operand.Path
            assertThat(path.segments).containsExactly(
                PathSegment.Field("input"), PathSegment.Field("pipeline"), PathSegment.Field("checks"),
                PathSegment.VarIndex("i"), PathSegment.Field("status"),
            )
        }

        @Test
        fun messageAssignedFromAnythingOtherThanStringOrSprintfProducesNullWithoutGuessing() {
            // `msg := 5` -- excluded from conditions (it's still the head-value assignment), but not guessed at.
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "deny", "key": {"type":"var","value":"msg"}, "ref": [{"type":"var","value":"deny"}]},
                      "body": [
                        {
                          "index": 0,
                          "location": {"file":"scratch.rego","row":1,"text":"${b64("msg := 5")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"assign"}]},
                            {"type":"var","value":"msg"},
                            {"type":"number","value":5}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val body = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules[0].bodies[0]
            assertThat(body.conditions).isEmpty()
            assertThat(body.producesValue).isNull()
        }

        @Test
        fun unrecognisedFunctionCallInConditionPositionFallsBackAsFunctionCall() {
            // A hypothetical `some_custom_check(input.x)` -- not in the §6.3 builtin table.
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "allow", "ref": [{"type":"var","value":"allow"}]},
                      "body": [
                        {
                          "index": 0,
                          "location": {"file":"scratch.rego","row":1,"text":"${b64("some_custom_check(input.x)")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"some_custom_check"}]},
                            {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"x"}]}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val body = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules[0].bodies[0]
            val fallback = body.conditions.single() as Condition.Unrendered
            assertThat(fallback.reason).isEqualTo("function-call")
            assertThat(fallback.sourceText).isEqualTo("some_custom_check(input.x)")
        }

        @Test
        fun operandPositionBuiltinBareAsAConditionFallsBackTwoPositionRule() {
            // `count(input.x)` as a whole top-level condition -- count is operand-position only (spec §6.3).
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "allow", "ref": [{"type":"var","value":"allow"}]},
                      "body": [
                        {
                          "index": 0,
                          "location": {"file":"scratch.rego","row":1,"text":"${b64("count(input.x)")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"count"}]},
                            {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"x"}]}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val body = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules[0].bodies[0]
            val fallback = body.conditions.single() as Condition.Unrendered
            assertThat(fallback.reason).isEqualTo("function-call")
            assertThat(fallback.sourceText).isEqualTo("count(input.x)")
        }

        @Test
        fun conditionPositionBuiltinMisusedAsOperandFallsBackTwoPositionRuleViceVersa() {
            // `input.x == startswith(input.y, "a")` -- startswith is condition-position only; nested as an operand it's honestly Unrendered, not silently accepted.
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "allow", "ref": [{"type":"var","value":"allow"}]},
                      "body": [
                        {
                          "index": 0,
                          "location": {"file":"scratch.rego","row":1,"text":"${b64("input.x == startswith(input.y, \"a\")")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"equal"}]},
                            {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"x"}]},
                            {"type":"call","location":{"text":"${b64("startswith(input.y, \"a\")")}"},"value":[
                              {"type":"ref","value":[{"type":"var","value":"startswith"}]},
                              {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"y"}]},
                              {"type":"string","value":"a"}
                            ]}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val body = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules[0].bodies[0]
            val comparison = body.conditions.single() as Condition.Comparison
            val unrendered = comparison.right as Operand.Unrendered
            assertThat(unrendered.sourceText).isEqualTo("startswith(input.y, \"a\")")
        }

        @Test
        fun oversizedArrayLiteralBecomesOperandUnrendered() {
            // `input.x in [1,2,3,4,5,6]` -- more than the 5-scalar-element cap (spec §6.4).
            val elements = (1..6).joinToString(",") { """{"type":"number","value":$it}""" }
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "allow", "ref": [{"type":"var","value":"allow"}]},
                      "body": [
                        {
                          "index": 0,
                          "location": {"file":"scratch.rego","row":1,"text":"${b64("input.x in [1,2,3,4,5,6]")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"internal"},{"type":"string","value":"member_2"}]},
                            {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"x"}]},
                            {"type":"array","location":{"text":"${b64("[1,2,3,4,5,6]")}"},"value":[$elements]}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val body = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules[0].bodies[0]
            val membership = body.conditions.single() as Condition.Membership
            val unrendered = membership.collection as Operand.Unrendered
            assertThat(unrendered.sourceText).isEqualTo("[1,2,3,4,5,6]")
        }

        @Test
        fun boundLoopVariableUsedAloneAsAnOperandResolvesThroughTheSymbolTable() {
            // `some x in input.numbers; x > 10` -- the bound var used directly, not as a ref-chain root.
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "allow", "ref": [{"type":"var","value":"allow"}]},
                      "body": [
                        {
                          "index": 0,
                          "location": {"file":"scratch.rego","row":1,"text":"${b64("some x in input.numbers")}"},
                          "terms": {"symbols":[
                            {"type":"call","value":[
                              {"type":"ref","value":[{"type":"var","value":"internal"},{"type":"string","value":"member_2"}]},
                              {"type":"var","value":"x"},
                              {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"numbers"}]}
                            ]}
                          ]}
                        },
                        {
                          "index": 1,
                          "location": {"file":"scratch.rego","row":2,"text":"${b64("x > 10")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"gt"}]},
                            {"type":"var","value":"x"},
                            {"type":"number","value":10}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val body = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules[0].bodies[0]
            val comparison = body.conditions[1] as Condition.Comparison
            assertThat(comparison.op).isEqualTo(Operator.GT)
            assertThat((comparison.left as Operand.Path).segments).containsExactly(
                PathSegment.Field("input"), PathSegment.Field("numbers"), PathSegment.VarIndex("x"),
            )
            assertThat((comparison.right as Operand.Literal).rendered).isEqualTo("10")
        }

        @Test
        fun operandPositionBuiltinWithARenderableArgumentIsPromotedToBuiltinCall() {
            // `count(input.x) == 0` -- promoted (spec §14): the argument is renderable, so count(...)
            // is now Operand.BuiltinCall rather than Operand.Unrendered.
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "allow", "ref": [{"type":"var","value":"allow"}]},
                      "body": [
                        {
                          "index": 0,
                          "location": {"file":"scratch.rego","row":1,"text":"${b64("count(input.x) == 0")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"equal"}]},
                            {"type":"call","location":{"text":"${b64("count(input.x)")}"},"value":[
                              {"type":"ref","value":[{"type":"var","value":"count"}]},
                              {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"x"}]}
                            ]},
                            {"type":"number","value":0}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val body = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules[0].bodies[0]
            val comparison = body.conditions.single() as Condition.Comparison
            val builtinCall = comparison.left as Operand.BuiltinCall
            assertThat(builtinCall.name).isEqualTo("count")
            assertThat((builtinCall.args.single() as Operand.Path).segments).containsExactly(
                PathSegment.Field("input"), PathSegment.Field("x"),
            )
        }

        @Test
        fun operandPositionBuiltinWithAnUnboundArgumentWrapsAnUnrenderedOperandRatherThanFallingBackWhole() {
            // `count(x) == 0` where x is an unbound var -- mapVarOperand resolves this to
            // Operand.Unrendered("x") (Ok, not Unsupported), so it's wrapped inside the
            // Operand.BuiltinCall rather than demoting the whole call. Coverage.countUnrendered
            // recurses into BuiltinCall args specifically so this nested case still counts.
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "allow", "ref": [{"type":"var","value":"allow"}]},
                      "body": [
                        {
                          "index": 0,
                          "location": {"file":"scratch.rego","row":1,"text":"${b64("count(x) == 0")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"equal"}]},
                            {"type":"call","location":{"text":"${b64("count(x)")}"},"value":[
                              {"type":"ref","value":[{"type":"var","value":"count"}]},
                              {"type":"var","value":"x","location":{"text":"${b64("x")}"}}
                            ]},
                            {"type":"number","value":0}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val body = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules[0].bodies[0]
            val comparison = body.conditions.single() as Condition.Comparison
            val builtinCall = comparison.left as Operand.BuiltinCall
            assertThat(builtinCall.name).isEqualTo("count")
            val arg = builtinCall.args.single() as Operand.Unrendered
            assertThat(arg.sourceText).isEqualTo("x")
        }

        @Test
        fun objectGetWithAPathAndAStringKeyIsPromotedToBuiltinCall() {
            // `object.get(input.change, "ticket", "none") == "none"` -- spec §14 backlog rank #1.
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "allow", "ref": [{"type":"var","value":"allow"}]},
                      "body": [
                        {
                          "index": 0,
                          "location": {"file":"scratch.rego","row":1,"text":"${b64("object.get(input.change, \"ticket\", \"none\") == \"none\"")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"equal"}]},
                            {"type":"call","location":{"text":"${b64("object.get(input.change, \"ticket\", \"none\")")}"},"value":[
                              {"type":"ref","value":[{"type":"var","value":"object"},{"type":"string","value":"get"}]},
                              {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"change"}]},
                              {"type":"string","value":"ticket","location":{"text":"${b64("\"ticket\"")}"}},
                              {"type":"string","value":"none","location":{"text":"${b64("\"none\"")}"}}
                            ]},
                            {"type":"string","value":"none"}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val body = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules[0].bodies[0]
            val comparison = body.conditions.single() as Condition.Comparison
            val builtinCall = comparison.left as Operand.BuiltinCall
            assertThat(builtinCall.name).isEqualTo("object.get")
            assertThat((builtinCall.args[0] as Operand.Path).segments).containsExactly(
                PathSegment.Field("input"), PathSegment.Field("change"),
            )
            assertThat((builtinCall.args[1] as Operand.Literal).rendered).isEqualTo("\"ticket\"")
            assertThat((builtinCall.args[2] as Operand.Literal).rendered).isEqualTo("\"none\"")
        }

        @Test
        fun objectGetWithANonStringKeyIsNotPromoted() {
            // `object.get(input.change, 0, "none") == "none"` -- a numeric key has no
            // breadcrumb-extension rule (spec §14's own risk note); falls back rather than
            // guessing one, exactly like an unrecognised operand-position builtin would.
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "allow", "ref": [{"type":"var","value":"allow"}]},
                      "body": [
                        {
                          "index": 0,
                          "location": {"file":"scratch.rego","row":1,"text":"${b64("object.get(input.change, 0, \"none\") == \"none\"")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"equal"}]},
                            {"type":"call","location":{"text":"${b64("object.get(input.change, 0, \"none\")")}"},"value":[
                              {"type":"ref","value":[{"type":"var","value":"object"},{"type":"string","value":"get"}]},
                              {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"change"}]},
                              {"type":"number","value":0},
                              {"type":"string","value":"none"}
                            ]},
                            {"type":"string","value":"none"}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val body = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules[0].bodies[0]
            val comparison = body.conditions.single() as Condition.Comparison
            val unrendered = comparison.left as Operand.Unrendered
            assertThat(unrendered.sourceText).isEqualTo("object.get(input.change, 0, \"none\")")
        }

        @Test
        fun nestedNonScalarLiteralBecomesOperandUnrenderedEvenWhenSmall() {
            // `input.x in [1, [2, 3]]` -- only 2 elements, but one is nested (not scalar) -- spec §6.4's "or nested" clause.
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "allow", "ref": [{"type":"var","value":"allow"}]},
                      "body": [
                        {
                          "index": 0,
                          "location": {"file":"scratch.rego","row":1,"text":"${b64("input.x in [1, [2, 3]]")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"internal"},{"type":"string","value":"member_2"}]},
                            {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"x"}]},
                            {"type":"array","location":{"text":"${b64("[1, [2, 3]]")}"},"value":[
                              {"type":"number","value":1},
                              {"type":"array","value":[{"type":"number","value":2},{"type":"number","value":3}]}
                            ]}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val body = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules[0].bodies[0]
            val membership = body.conditions.single() as Condition.Membership
            val unrendered = membership.collection as Operand.Unrendered
            assertThat(unrendered.sourceText).isEqualTo("[1, [2, 3]]")
        }

        @Test
        fun regexMatchClassifiesAsBuiltinCallWithPatternAndValueInSourceOrder() {
            // `regex.match("^v[0-9]+$", input.artifact.version)` -- unexercised by the acceptance
            // pack. The function-name ref chain [var:regex, string:match] mirrors the already-real,
            // already-confirmed shape of "internal.member_2" (a dotted builtin name is a 2-element
            // ref chain), not a guess about a wholly new construct.
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "allow", "ref": [{"type":"var","value":"allow"}]},
                      "body": [
                        {
                          "index": 0,
                          "location": {"file":"scratch.rego","row":1,"text":"${b64("regex.match(\"^v[0-9]+$\", input.artifact.version)")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"regex"},{"type":"string","value":"match"}]},
                            {"type":"string","value":"^v[0-9]+$"},
                            {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"artifact"},{"type":"string","value":"version"}]}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val body = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules[0].bodies[0]
            val call = body.conditions.single() as Condition.BuiltinCall
            assertThat(call.name).isEqualTo("regex.match")
            assertThat(call.negated).isFalse()
            assertThat((call.args[0] as Operand.Literal).rendered).isEqualTo("\"^v[0-9]+$\"")
            assertThat((call.args[1] as Operand.Path).segments).containsExactly(
                PathSegment.Field("input"), PathSegment.Field("artifact"), PathSegment.Field("version"),
            )
        }

        @Test
        fun globMatchClassifiesAsBuiltinCallIncludingTheIgnoredDelimiterArgument() {
            // `glob.match("release/*", [], input.artifact.branch)` -- 3-arg form (spec §6.3's
            // `glob.match(p, _, v)`), unexercised by the acceptance pack.
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "allow", "ref": [{"type":"var","value":"allow"}]},
                      "body": [
                        {
                          "index": 0,
                          "location": {"file":"scratch.rego","row":1,"text":"${b64("glob.match(\"release/*\", [], input.artifact.branch)")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"glob"},{"type":"string","value":"match"}]},
                            {"type":"string","value":"release/*"},
                            {"type":"array","value":[]},
                            {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"artifact"},{"type":"string","value":"branch"}]}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val body = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules[0].bodies[0]
            val call = body.conditions.single() as Condition.BuiltinCall
            assertThat(call.name).isEqualTo("glob.match")
            assertThat(call.args).hasSize(3)
            assertThat((call.args[0] as Operand.Literal).rendered).isEqualTo("\"release/*\"")
            assertThat((call.args[2] as Operand.Path).segments).containsExactly(
                PathSegment.Field("input"), PathSegment.Field("artifact"), PathSegment.Field("branch"),
            )
        }
    }

    @Nested
    inner class LocalVariableSubstitution {

        @Test
        fun plainPathAssignmentDisappearsAndSubstitutesInlineOnBareUse() {
            // `env := input.deployment.environment; env == "production"` -- spec §5/§14 promotion.
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "allow", "ref": [{"type":"var","value":"allow"}]},
                      "body": [
                        {
                          "index": 0,
                          "location": {"file":"scratch.rego","row":1,"text":"${b64("env := input.deployment.environment")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"assign"}]},
                            {"type":"var","value":"env"},
                            {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"deployment"},{"type":"string","value":"environment"}]}
                          ]
                        },
                        {
                          "index": 1,
                          "location": {"file":"scratch.rego","row":2,"text":"${b64("env == \"production\"")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"equal"}]},
                            {"type":"var","value":"env"},
                            {"type":"string","value":"production"}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val body = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules[0].bodies[0]
            assertThat(body.conditions).hasSize(1)
            val comparison = body.conditions.single() as Condition.Comparison
            assertThat((comparison.left as Operand.Path).segments).containsExactly(
                PathSegment.Field("input"), PathSegment.Field("deployment"), PathSegment.Field("environment"),
            )
        }

        @Test
        fun plainPathAssignmentSubstitutesAsARefChainRootTooNotJustBareUse() {
            // `dep := input.deployment; dep.environment == "production"` -- the bound var used as a
            // ref-chain ROOT (`dep.environment`), continuing the chain with no extra segment (unlike
            // a some-in iteration binding, which would append "[each dep]").
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "allow", "ref": [{"type":"var","value":"allow"}]},
                      "body": [
                        {
                          "index": 0,
                          "location": {"file":"scratch.rego","row":1,"text":"${b64("dep := input.deployment")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"assign"}]},
                            {"type":"var","value":"dep"},
                            {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"deployment"}]}
                          ]
                        },
                        {
                          "index": 1,
                          "location": {"file":"scratch.rego","row":2,"text":"${b64("dep.environment == \"production\"")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"equal"}]},
                            {"type":"ref","value":[{"type":"var","value":"dep"},{"type":"string","value":"environment"}]},
                            {"type":"string","value":"production"}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val body = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules[0].bodies[0]
            assertThat(body.conditions).hasSize(1)
            val comparison = body.conditions.single() as Condition.Comparison
            assertThat((comparison.left as Operand.Path).segments).containsExactly(
                PathSegment.Field("input"), PathSegment.Field("deployment"), PathSegment.Field("environment"),
            )
        }

        @Test
        fun chainedPlainPathAssignmentsResolveTransitively() {
            // `a := input.change; b := a.author; b == "asmith"` -- b substitutes through a, which
            // itself substitutes through input.change, entirely disappearing from the output.
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "allow", "ref": [{"type":"var","value":"allow"}]},
                      "body": [
                        {
                          "index": 0,
                          "location": {"file":"scratch.rego","row":1,"text":"${b64("a := input.change")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"assign"}]},
                            {"type":"var","value":"a"},
                            {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"change"}]}
                          ]
                        },
                        {
                          "index": 1,
                          "location": {"file":"scratch.rego","row":2,"text":"${b64("b := a.author")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"assign"}]},
                            {"type":"var","value":"b"},
                            {"type":"ref","value":[{"type":"var","value":"a"},{"type":"string","value":"author"}]}
                          ]
                        },
                        {
                          "index": 2,
                          "location": {"file":"scratch.rego","row":3,"text":"${b64("b == \"asmith\"")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"equal"}]},
                            {"type":"var","value":"b"},
                            {"type":"string","value":"asmith"}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val body = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules[0].bodies[0]
            assertThat(body.conditions).hasSize(1)
            val comparison = body.conditions.single() as Condition.Comparison
            assertThat((comparison.left as Operand.Path).segments).containsExactly(
                PathSegment.Field("input"), PathSegment.Field("change"), PathSegment.Field("author"),
            )
        }

        @Test
        fun assignmentToANonPlainPathStillFallsBackAndLaterBareUseRendersAsVariable() {
            // `x := count(input.y); x > 0` -- spec §5's own explicit wording: a non-plain-path RHS
            // keeps the assignment as a visible fallback bullet, and later bare uses of x render as
            // Operand.Variable (known to be assigned, not a genuinely unbound/unknown name).
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "allow", "ref": [{"type":"var","value":"allow"}]},
                      "body": [
                        {
                          "index": 0,
                          "location": {"file":"scratch.rego","row":1,"text":"${b64("x := count(input.y)")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"assign"}]},
                            {"type":"var","value":"x"},
                            {"type":"call","location":{"text":"${b64("count(input.y)")}"},"value":[
                              {"type":"ref","value":[{"type":"var","value":"count"}]},
                              {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"y"}]}
                            ]}
                          ]
                        },
                        {
                          "index": 1,
                          "location": {"file":"scratch.rego","row":2,"text":"${b64("x > 0")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"gt"}]},
                            {"type":"var","value":"x"},
                            {"type":"number","value":0}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val body = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules[0].bodies[0]
            assertThat(body.conditions).hasSize(2)
            val fallback = body.conditions[0] as Condition.Unrendered
            assertThat(fallback.reason).isEqualTo("function-call")
            assertThat(fallback.sourceText).isEqualTo("x := count(input.y)")
            val comparison = body.conditions[1] as Condition.Comparison
            assertThat((comparison.left as Operand.Variable).name).isEqualTo("x")
        }
    }
}
