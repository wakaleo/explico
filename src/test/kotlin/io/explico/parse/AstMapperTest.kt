/**
 * Unit tests for AstMapper (spec §5), driven entirely from the checked-in
 * `opa parse` captures in src/test/resources/ast/ -- no `opa` binary needed.
 */
package io.explico.parse

import io.explico.model.Condition
import io.explico.model.LiteralCollection
import io.explico.model.Operand
import io.explico.model.Operator
import io.explico.model.PathSegment
import io.explico.model.PolicySet
import io.explico.model.RuleGroup
import io.explico.opa.OpaInspectResult
import io.explico.opa.OpaModule
import io.explico.opa.opaJson
import io.explico.render.PathHumanizer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
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
        fun varRootedSprintfPlaceholderHumanizesPromotedByReviewItem9() {
            // Was: stage.name is var-rooted; the original placeholder helper only handled plain
            // input.-rooted chains, so producesValue stayed null (a disclosed gap). Review item 9
            // promotes a var-rooted chain too, once the root is a KNOWN binding (here, `stage`
            // bound by `some stage in input.pipeline.stages`) -- the var's own name leads the
            // bracket phrase, since unlike "input" it's real content, not boilerplate.
            val body = wholePack().packages.first { it.path == "release.evidence" }.rules.first { it.name == "deny" }.bodies.single()
            assertThat(body.producesValue).isEqualTo("pipeline stage [stage name] did not pass")
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
        fun countOfComprehensionIsNowRenderedAsAComprehensionOperand() {
            // REL-004's `count({a | some a in ...; a.role == "release-manager"}) == 0` was the pack's
            // verbatim-source comprehension fallback; the operator approved rendering comprehensions
            // (see the acceptance README's amendment note), so it is now a Comparison over count(Comprehension).
            val body = onlyFile("release_governance", "governance/release_governance.rego").packages[0].rules.single { it.name == "deny" }.bodies[0]
            val comparison = body.conditions[1] as Condition.Comparison
            val count = comparison.left as Operand.BuiltinCall
            assertThat(count.name).isEqualTo("count")
            val comprehension = count.args.single() as Operand.Comprehension
            assertThat(comprehension.kind).isEqualTo(io.explico.model.ComprehensionKind.SET)
            assertThat(comprehension.body).hasSize(2)
            assertThat(comprehension.body[0]).isInstanceOf(Condition.SomeIn::class.java)
            assertThat((comprehension.body[1] as Condition.Comparison).right).isEqualTo(Operand.Literal("\"release-manager\""))
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

            // producesValue now humanizes window.name too (review item 9 promotion: `window` is a
            // known VarBinding.Iteration binding, same as `stage` above) -- was null (session 2/3's
            // disclosed gap). messageTemplate stays populated regardless: body attribution (§6.7)
            // needs the template's literal/wildcard shape, not a display rendering of the argument.
            assertThat(body.producesValue).isEqualTo("deployment falls inside freeze window [window name]")
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
            // `x := json.marshal(input.y); x > 0` -- spec §5's own explicit wording: a non-plain-path RHS
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
                          "location": {"file":"scratch.rego","row":1,"text":"${b64("x := json.marshal(input.y)")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"assign"}]},
                            {"type":"var","value":"x"},
                            {"type":"call","location":{"text":"${b64("json.marshal(input.y)")}"},"value":[
                              {"type":"ref","value":[{"type":"var","value":"json"},{"type":"string","value":"marshal"}]},
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
            // "helper-assignment" (review item 12), distinct from a bare unresolved CALL's
            // "function-call" -- this is specifically an assignment the mapper can't substitute.
            assertThat(fallback.reason).isEqualTo("helper-assignment")
            assertThat(fallback.sourceText).isEqualTo("x := json.marshal(input.y)")
            val comparison = body.conditions[1] as Condition.Comparison
            assertThat((comparison.left as Operand.Variable).name).isEqualTo("x")
        }
    }

    @Nested
    inner class SomeKeyValueForm {

        /** `internal.member_3(k, v, collection)` -- confirmed via a real `opa parse` run against `some k, v in ...`. */
        private fun someKvCollectionTerms(collectionRefJson: String): String = """
            {
              "symbols": [
                {
                  "type": "call",
                  "value": [
                    {"type":"ref","value":[{"type":"var","value":"internal"},{"type":"string","value":"member_3"}]},
                    {"type":"var","value":"k"},
                    {"type":"var","value":"v"},
                    $collectionRefJson
                  ]
                }
              ]
            }
        """.trimIndent()

        @Test
        fun theValueResolvesThroughTheCollectionAndTheKeyIsAPlainVariable() {
            // `some k, v in input.change.approvals; v == "approved"; k == "release-manager"` -- spec
            // §14 promotion: v binds as an iteration variable over the collection (an element of it);
            // k is its KEY, so a bare use is the plain variable `k` -- rendering it as
            // `approvals ▸ [each k]` (what this test used to pin) claimed the element at k where the
            // policy tests the key itself.
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
                          "location": {"file":"scratch.rego","row":1,"text":"${b64("some k, v in input.change.approvals")}"},
                          "terms": ${
            someKvCollectionTerms(
                """{"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"change"},{"type":"string","value":"approvals"}]}""",
            )
        }
                        },
                        {
                          "index": 1,
                          "location": {"file":"scratch.rego","row":2,"text":"${b64("v == \"approved\"")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"equal"}]},
                            {"type":"var","value":"v"},
                            {"type":"string","value":"approved"}
                          ]
                        },
                        {
                          "index": 2,
                          "location": {"file":"scratch.rego","row":3,"text":"${b64("k == \"release-manager\"")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"equal"}]},
                            {"type":"var","value":"k"},
                            {"type":"string","value":"release-manager"}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val body = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules[0].bodies[0]
            assertThat(body.conditions).hasSize(3)

            val someIn = body.conditions[0] as Condition.SomeIn
            assertThat(someIn.key).isEqualTo("k")
            assertThat(someIn.variable).isEqualTo("v")
            assertThat((someIn.collection as Operand.Path).segments).containsExactly(
                PathSegment.Field("input"), PathSegment.Field("change"), PathSegment.Field("approvals"),
            )

            val valueComparison = body.conditions[1] as Condition.Comparison
            assertThat((valueComparison.left as Operand.Path).segments).containsExactly(
                PathSegment.Field("input"), PathSegment.Field("change"), PathSegment.Field("approvals"), PathSegment.VarIndex("v"),
            )

            val keyComparison = body.conditions[2] as Condition.Comparison
            assertThat(keyComparison.left).isEqualTo(Operand.Variable("k"))
        }

        @Test
        fun valueVariableUsedAsARefChainRootExtendsWithVarIndexThenContinues() {
            // `some k, v in input.change.approvals; v.role == "release-manager"` -- mirrors the
            // already-established single-variable precedent (PipelineEvidenceRego's `stage.status`),
            // proving the two-variable form's value binding works as a chain ROOT too, not just bare.
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
                          "location": {"file":"scratch.rego","row":1,"text":"${b64("some k, v in input.change.approvals")}"},
                          "terms": ${
            someKvCollectionTerms(
                """{"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"change"},{"type":"string","value":"approvals"}]}""",
            )
        }
                        },
                        {
                          "index": 1,
                          "location": {"file":"scratch.rego","row":2,"text":"${b64("v.role == \"release-manager\"")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"equal"}]},
                            {"type":"ref","value":[{"type":"var","value":"v"},{"type":"string","value":"role"}]},
                            {"type":"string","value":"release-manager"}
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
            val comparison = body.conditions[1] as Condition.Comparison
            assertThat((comparison.left as Operand.Path).segments).containsExactly(
                PathSegment.Field("input"), PathSegment.Field("change"), PathSegment.Field("approvals"),
                PathSegment.VarIndex("v"), PathSegment.Field("role"),
            )
        }
    }

    @Nested
    inner class UnificationAsPureComparison {

        @Test
        fun freshBareVarOnOneSideStaysUnclassified() {
            // `x = input.deployment.environment`, x never bound before this point -- possibly a
            // fresh-variable bind (equivalent to `x := ...`), not provably a pure comparison, so
            // spec §14's promotion must NOT treat this as Condition.Comparison.
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
                          "location": {"file":"scratch.rego","row":1,"text":"${b64("x = input.deployment.environment")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"eq"}]},
                            {"type":"var","value":"x"},
                            {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"deployment"},{"type":"string","value":"environment"}]}
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
            val fallback = body.conditions.single() as Condition.Unrendered
            assertThat(fallback.reason).isEqualTo("function-call")
            assertThat(fallback.sourceText).isEqualTo("x = input.deployment.environment")
        }

        @Test
        fun aVariableAlreadyBoundByAnEarlierPlainPathAssignmentPromotesToComparison() {
            // `x := input.deployment.environment; x = "production"` -- x is already bound (to a
            // real path) BEFORE the `=` line, so this second line is genuinely a pure comparison,
            // not a bind -- must promote identically to `x == "production"`.
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
                          "location": {"file":"scratch.rego","row":1,"text":"${b64("x := input.deployment.environment")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"assign"}]},
                            {"type":"var","value":"x"},
                            {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"deployment"},{"type":"string","value":"environment"}]}
                          ]
                        },
                        {
                          "index": 1,
                          "location": {"file":"scratch.rego","row":2,"text":"${b64("x = \"production\"")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"eq"}]},
                            {"type":"var","value":"x"},
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
            // The `:=` line itself disappears entirely (spec §14.4, already-established substitution
            // behaviour) -- only the promoted comparison remains.
            assertThat(body.conditions).hasSize(1)
            val comparison = body.conditions.single() as Condition.Comparison
            assertThat(comparison.op).isEqualTo(Operator.EQ)
            assertThat((comparison.left as Operand.Path).segments).containsExactly(
                PathSegment.Field("input"), PathSegment.Field("deployment"), PathSegment.Field("environment"),
            )
            assertThat((comparison.right as Operand.Literal).rendered).isEqualTo("\"production\"")
        }

        @Test
        fun negatedUnificationPromotesToAComparisonWithNegatedSetRatherThanSilentlyDroppingIt() {
            // `not input.change.author = input.change.approver` -- real, valid Rego (confirmed via a
            // real `opa parse` run: negated: true, terms name still "eq"). Promoted (spec §14
            // amendment): Condition.Comparison now carries negated, so this renders the ACTUAL
            // "is not" semantics rather than the previously-fallback verbatim source.
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
                          "negated": true,
                          "location": {"file":"scratch.rego","row":1,"text":"${b64("not input.change.author = input.change.approver")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"eq"}]},
                            {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"change"},{"type":"string","value":"author"}]},
                            {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"change"},{"type":"string","value":"approver"}]}
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
            assertThat(comparison.negated).isTrue()
            assertThat(comparison.op).isEqualTo(Operator.EQ)
            assertThat((comparison.left as Operand.Path).segments).containsExactly(
                PathSegment.Field("input"), PathSegment.Field("change"), PathSegment.Field("author"),
            )
            assertThat((comparison.right as Operand.Path).segments).containsExactly(
                PathSegment.Field("input"), PathSegment.Field("change"), PathSegment.Field("approver"),
            )
        }
    }

    @Nested
    inner class ArithmeticOperands {

        @Test
        fun chainedPlusDesugarsToOpasOwnLeftAssociativeNestedCallTree() {
            // `input.a + input.b + input.c` desugars to plus(plus(a, b), c) -- confirmed via a real
            // `opa parse` run (spec §14 promotion). AstMapper needs no special-case logic for this:
            // the same single-call promotion rule applies recursively at each level.
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
                          "location": {"file":"scratch.rego","row":1,"text":"${b64("input.a + input.b + input.c > 0")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"gt"}]},
                            {"type":"call","value":[
                              {"type":"ref","value":[{"type":"var","value":"plus"}]},
                              {"type":"call","value":[
                                {"type":"ref","value":[{"type":"var","value":"plus"}]},
                                {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"a"}]},
                                {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"b"}]}
                              ]},
                              {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"c"}]}
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
            val outerPlus = comparison.left as Operand.BuiltinCall
            assertThat(outerPlus.name).isEqualTo("plus")
            val innerPlus = outerPlus.args[0] as Operand.BuiltinCall
            assertThat(innerPlus.name).isEqualTo("plus")
            assertThat((innerPlus.args[0] as Operand.Path).segments).containsExactly(PathSegment.Field("input"), PathSegment.Field("a"))
            assertThat((innerPlus.args[1] as Operand.Path).segments).containsExactly(PathSegment.Field("input"), PathSegment.Field("b"))
            assertThat((outerPlus.args[1] as Operand.Path).segments).containsExactly(PathSegment.Field("input"), PathSegment.Field("c"))
        }

        @Test
        fun unaryMinusDesugarsToMinusZeroXAndPromotesLikeAnyOtherBinaryArithmeticCall() {
            // `-input.a` desugars to minus(0, input.a) -- confirmed via a real `opa parse` run. No
            // special-casing needed: it's a genuine binary "minus" call like any other.
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
                          "location": {"file":"scratch.rego","row":1,"text":"${b64("-input.a > 0")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"gt"}]},
                            {"type":"call","value":[
                              {"type":"ref","value":[{"type":"var","value":"minus"}]},
                              {"type":"number","value":0},
                              {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"a"}]}
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
            val minus = comparison.left as Operand.BuiltinCall
            assertThat(minus.name).isEqualTo("minus")
            assertThat((minus.args[0] as Operand.Literal).rendered).isEqualTo("0")
            assertThat((minus.args[1] as Operand.Path).segments).containsExactly(PathSegment.Field("input"), PathSegment.Field("a"))
        }
    }

    @Nested
    inner class ConcatOperand {

        @Test
        fun aSetLiteralCollectionExplodesIntoOneOperandPerElementJustLikeAnArrayLiteral() {
            // `concat("/", {input.a, input.b})` -- a set literal, not an array. No probe exercises
            // this shape (probe 26 only covers array literals); mapConcatOperand treats "array" and
            // "set" identically per its own KDoc.
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
                          "location": {"file":"scratch.rego","row":1,"text":"${b64("concat(\"/\", {input.a, input.b}) == input.c")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"equal"}]},
                            {"type":"call","value":[
                              {"type":"ref","value":[{"type":"var","value":"concat"}]},
                              {"type":"string","value":"/"},
                              {"type":"set","value":[
                                {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"a"}]},
                                {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"b"}]}
                              ]}
                            ]},
                            {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"c"}]}
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
            val concat = comparison.left as Operand.BuiltinCall
            assertThat(concat.name).isEqualTo("concat")
            assertThat((concat.args[0] as Operand.Literal).rendered).isEqualTo("\"/\"")
            assertThat((concat.args[1] as Operand.Path).segments).containsExactly(PathSegment.Field("input"), PathSegment.Field("a"))
            assertThat((concat.args[2] as Operand.Path).segments).containsExactly(PathSegment.Field("input"), PathSegment.Field("b"))
        }

        @Test
        fun aComprehensionAsTheCollectionArgumentFallsBackTheWholeConcatCall() {
            // `concat(",", [x | some x in input.a])` -- the collection argument is ITSELF an
            // arraycomprehension term (not an "array" literal type), so it takes mapConcatOperand's
            // whole-collection-reference path, and mapOperand's own comprehension handling correctly
            // returns Unsupported, propagating to fall back the WHOLE concat call -- never a
            // partial/guessed rendering, exactly like every other operand-position promotion.
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
                          "location": {"file":"scratch.rego","row":1,"text":"${b64("concat(\",\", [x | some x in input.a]) == input.b")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"equal"}]},
                            {"type":"call","value":[
                              {"type":"ref","value":[{"type":"var","value":"concat"}]},
                              {"type":"string","value":","},
                              {"type":"arraycomprehension","value":{}}
                            ]},
                            {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"b"}]}
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
            assertThat(fallback.reason).isEqualTo("comprehension")
            assertThat(fallback.sourceText).isEqualTo("concat(\",\", [x | some x in input.a]) == input.b")
        }
    }

    @Nested
    inner class NullLiteralOperand {

        @Test
        fun aNullLiteralInASmallArrayLiteralNowPromotesTooDirectConsequenceOfNullsOwnPromotion() {
            // `input.a == ["x", null, 1]` -- confirmed via a real `opa parse` run that a `null`
            // element has term type "null", the same type mapOperand's own top-level null-literal
            // case now recognises. mapCollectionLiteral's allow-list needed the SAME type added, or
            // this array would still fall back despite null itself being promoted.
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
                          "location": {"file":"scratch.rego","row":1,"text":"${b64("input.a == [\"x\", null, 1]")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"equal"}]},
                            {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"a"}]},
                            {"type":"array","value":[
                              {"type":"string","value":"x"},
                              {"type":"null","value":{}},
                              {"type":"number","value":1}
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
            assertThat((comparison.right as Operand.Literal).rendered).isEqualTo("\"x\", null, 1")
        }
    }

    @Nested
    inner class ObjectLiteralOperand {

        /** `input.x == {...pairsJson}` -- the object term's own "value" is a list of [key, value] pairs, per a real `opa parse` run. */
        private fun objectComparisonModule(pairsJson: String, objectSourceText: String = "{...}"): OpaModule = moduleOf(
            """
            {
              "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
              "rules": [
                {
                  "head": {"name": "allow", "ref": [{"type":"var","value":"allow"}]},
                  "body": [
                    {
                      "index": 0,
                      "location": {"file":"scratch.rego","row":1,"text":"${b64("input.x == $objectSourceText")}"},
                      "terms": [
                        {"type":"ref","value":[{"type":"var","value":"equal"}]},
                        {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"x"}]},
                        {"type":"object","location":{"text":"${b64(objectSourceText)}"},"value":[$pairsJson]}
                      ]
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        private fun conditionOf(module: OpaModule): Condition =
            AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules[0].bodies[0].conditions.single()

        @Test
        fun emptyObjectLiteralPromotesToEmptyBraces() {
            val condition = conditionOf(objectComparisonModule("")) as Condition.Comparison
            assertThat((condition.right as Operand.Literal).rendered).isEqualTo("{}")
        }

        @Test
        fun aSixPairObjectLiteralExceedsTheCapAndTheWholeOperandFallsBack() {
            // Mirrors the array/set literal's own ≤5-element cap exactly. Like a non-scalar array
            // (mapCollectionLiteral), the object as a whole becomes Operand.Unrendered -- the
            // ENCLOSING comparison stays a real Condition.Comparison, exactly like an unbound bare
            // var doesn't demote its whole condition either.
            val sixPairs = (1..6).joinToString(",") { i ->
                """[{"type":"string","value":"k$i"},{"type":"number","value":$i}]"""
            }
            val objectSource = "{" + (1..6).joinToString(", ") { "\"k$it\": $it" } + "}"
            val condition = conditionOf(objectComparisonModule(sixPairs, objectSource)) as Condition.Comparison
            assertThat((condition.right as Operand.Unrendered).sourceText).isEqualTo(objectSource)
        }

        @Test
        fun aNonStringKeyLeavesTheWholeOperandUnrendered() {
            // `{1: "a"}` -- Rego allows a non-string key, but this promotion deliberately only
            // covers the overwhelmingly common `{"k": v}` shape, per its own KDoc.
            val numericKeyPair = """[{"type":"number","value":1},{"type":"string","value":"a"}]"""
            val objectSource = "{1: \"a\"}"
            val condition = conditionOf(objectComparisonModule(numericKeyPair, objectSource)) as Condition.Comparison
            assertThat((condition.right as Operand.Unrendered).sourceText).isEqualTo(objectSource)
        }

        @Test
        fun aNestedArrayValueLeavesTheWholeOperandUnrendered() {
            // `{"k": [1, 2]}` -- a non-scalar value; "flat" means every value must itself be a
            // SCALAR_TERM_TYPES member, never a nested array/object.
            val nestedPair = """[{"type":"string","value":"k"},{"type":"array","value":[{"type":"number","value":1}]}]"""
            val objectSource = "{\"k\": [1]}"
            val condition = conditionOf(objectComparisonModule(nestedPair, objectSource)) as Condition.Comparison
            assertThat((condition.right as Operand.Unrendered).sourceText).isEqualTo(objectSource)
        }

        @Test
        fun aNullValuePromotesJustLikeAnyOtherScalarValue() {
            // `{"k": null}` -- null qualifies as a SCALAR_TERM_TYPES member per its own promotion.
            val nullPair = """[{"type":"string","value":"k"},{"type":"null","value":{}}]"""
            val condition = conditionOf(objectComparisonModule(nullPair)) as Condition.Comparison
            assertThat((condition.right as Operand.Literal).rendered).isEqualTo("{\"k\": null}")
        }
    }

    @Nested
    inner class RefHeadRuleReference {

        /** `fruit.apple.seeds := 12` -- head.name null, head.ref fully literal (probe 13's own real shape, confirmed via opa parse). */
        private val refHeadRuleJson = """
            {
              "head": {"ref": [{"type":"var","value":"fruit"},{"type":"string","value":"apple"},{"type":"string","value":"seeds"}], "value": {"type":"number","value":12}, "assign": true}
            }
        """.trimIndent()

        @Test
        fun aReferenceWithExtraSegmentsBeyondTheRulesOwnPathStaysUnrenderedNeverGuessed() {
            // `fruit.apple.seeds.extra` -- one segment beyond the registered rule's own exact
            // path. The FULL-chain-only match requirement means this correctly does NOT match,
            // rather than guessing that ".extra" indexes into whatever `fruit.apple.seeds`
            // produces.
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    $refHeadRuleJson,
                    {
                      "head": {"name": "allow", "ref": [{"type":"var","value":"allow"}]},
                      "body": [
                        {
                          "index": 0,
                          "location": {"file":"scratch.rego","row":1,"text":"${b64("fruit.apple.seeds.extra > 10")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"gt"}]},
                            {"type":"ref","location":{"text":"${b64("fruit.apple.seeds.extra")}"},"value":[{"type":"var","value":"fruit"},{"type":"string","value":"apple"},{"type":"string","value":"seeds"},{"type":"string","value":"extra"}]},
                            {"type":"number","value":10}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val allowBody = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules.single { it.name == "allow" }.bodies[0]
            val comparison = allowBody.conditions.single() as Condition.Comparison
            assertThat((comparison.left as Operand.Unrendered).sourceText).isEqualTo("fruit.apple.seeds.extra")
        }

        @Test
        fun aLocalBindingWithTheSameRootNameTakesPriorityOverTheRefHeadRegistry() {
            // `some fruit in input.baskets; fruit.apple.seeds > 10` -- "fruit" is ALSO the ref-head
            // rule's own root name, but a local `some...in` binding must win: the reference resolves
            // through VarBinding.Iteration (breadcrumb + "[each fruit]"), never the ref-head registry,
            // avoiding any possible ambiguity between a local variable and a same-named rule.
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    $refHeadRuleJson,
                    {
                      "head": {"name": "allow", "ref": [{"type":"var","value":"allow"}]},
                      "body": [
                        {
                          "index": 0,
                          "location": {"file":"scratch.rego","row":1,"text":"${b64("some fruit in input.baskets")}"},
                          "terms": {
                            "symbols": [
                              {"type":"call","value":[
                                {"type":"ref","value":[{"type":"var","value":"internal"},{"type":"string","value":"member_2"}]},
                                {"type":"var","value":"fruit"},
                                {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"baskets"}]}
                              ]}
                            ]
                          }
                        },
                        {
                          "index": 1,
                          "location": {"file":"scratch.rego","row":2,"text":"${b64("fruit.apple.seeds > 10")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"gt"}]},
                            {"type":"ref","value":[{"type":"var","value":"fruit"},{"type":"string","value":"apple"},{"type":"string","value":"seeds"}]},
                            {"type":"number","value":10}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val allowBody = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules.single { it.name == "allow" }.bodies[0]
            assertThat(allowBody.conditions).hasSize(2)
            val comparison = allowBody.conditions[1] as Condition.Comparison
            assertThat((comparison.left as Operand.Path).segments).containsExactly(
                PathSegment.Field("input"), PathSegment.Field("baskets"), PathSegment.VarIndex("fruit"),
                PathSegment.Field("apple"), PathSegment.Field("seeds"),
            )
        }
    }

    @Nested
    inner class MultipleAnnotatedBodiesSharingAName {
        // Review item 1: several independently-`scope: rule`-annotated rules can share one Rego
        // name (e.g. three separately-annotated `deny contains msg if {...}` bodies) -- the old
        // (package, name) -> metadata index collapsed all of them into one card. Fixed by resolving
        // each `opa inspect` entry against the SPECIFIC OpaRule it decorates (file + row).

        @Test
        fun separatelyRuleScopedAnnotationsOnTheSameNameProduceSeparateRuleGroups() {
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "location": {"row": 5},
                      "head": {"name": "deny", "key": {"type":"var","value":"msg"}, "ref": [{"type":"var","value":"deny"}]},
                      "body": [
                        {
                          "index": 0,
                          "location": {"row":6,"text":"${b64("input.a")}"},
                          "terms": {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"a"}],"location":{"text":"${b64("input.a")}"}}
                        }
                      ]
                    },
                    {
                      "location": {"row": 10},
                      "head": {"name": "deny", "key": {"type":"var","value":"msg"}, "ref": [{"type":"var","value":"deny"}]},
                      "body": [
                        {
                          "index": 0,
                          "location": {"row":11,"text":"${b64("input.b")}"},
                          "terms": {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"b"}],"location":{"text":"${b64("input.b")}"}}
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val inspectResult = opaJson.decodeFromString(
                OpaInspectResult.serializer(),
                """
                {
                  "annotations": [
                    {
                      "annotations": {"scope":"rule","title":"First control","custom":{"control-id":"X-001"}},
                      "location": {"file":"scratch.rego","row":5},
                      "path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"},{"type":"string","value":"deny"}]
                    },
                    {
                      "annotations": {"scope":"rule","title":"Second control","custom":{"control-id":"X-002"}},
                      "location": {"file":"scratch.rego","row":10},
                      "path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"},{"type":"string","value":"deny"}]
                    }
                  ]
                }
                """.trimIndent()
            )

            val rules = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module)), inspectResult).packages.single().rules
            assertThat(rules).hasSize(2)
            assertThat(rules.map { it.metadata?.controlId }).containsExactlyInAnyOrder("X-001", "X-002")

            val first = rules.single { it.metadata?.controlId == "X-001" }
            assertThat(first.bodies).hasSize(1)
            val firstCondition = first.bodies.single().conditions.single() as Condition.Truthy
            assertThat((firstCondition.operand as Operand.Path).segments).containsExactly(PathSegment.Field("input"), PathSegment.Field("a"))

            val second = rules.single { it.metadata?.controlId == "X-002" }
            assertThat(second.bodies).hasSize(1)
            val secondCondition = second.bodies.single().conditions.single() as Condition.Truthy
            assertThat((secondCondition.operand as Operand.Path).segments).containsExactly(PathSegment.Field("input"), PathSegment.Field("b"))
        }

        @Test
        fun unannotatedBodiesSharingANameWithAnnotatedOnesStillGroupTogetherSeparately() {
            // A third, unannotated body with the same name must not merge into either annotated
            // card, nor get lost -- it forms its own (name, null-metadata) group.
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "location": {"row": 5},
                      "head": {"name": "deny", "key": {"type":"var","value":"msg"}, "ref": [{"type":"var","value":"deny"}]},
                      "body": [
                        {
                          "index": 0,
                          "location": {"row":6,"text":"${b64("input.a")}"},
                          "terms": {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"a"}],"location":{"text":"${b64("input.a")}"}}
                        }
                      ]
                    },
                    {
                      "location": {"row": 10},
                      "head": {"name": "deny", "key": {"type":"var","value":"msg"}, "ref": [{"type":"var","value":"deny"}]},
                      "body": [
                        {
                          "index": 0,
                          "location": {"row":11,"text":"${b64("input.c")}"},
                          "terms": {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"c"}],"location":{"text":"${b64("input.c")}"}}
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val inspectResult = opaJson.decodeFromString(
                OpaInspectResult.serializer(),
                """
                {
                  "annotations": [
                    {
                      "annotations": {"scope":"rule","title":"First control","custom":{"control-id":"X-001"}},
                      "location": {"file":"scratch.rego","row":5},
                      "path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"},{"type":"string","value":"deny"}]
                    }
                  ]
                }
                """.trimIndent()
            )

            val rules = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module)), inspectResult).packages.single().rules
            assertThat(rules).hasSize(2)
            val unannotated = rules.single { it.metadata == null }
            assertThat(unannotated.bodies).hasSize(1)
        }
    }

    @Nested
    inner class DefaultDeclarations {
        // Review item 3: `default <name> := <value>` must never become a Situation/body -- it
        // populates RuleGroup.default instead.

        @Test
        fun defaultDeclarationIsExcludedFromBodiesAndPopulatesRuleGroupDefault() {
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "default": true,
                      "location": {"row": 1},
                      "head": {"name":"allow","value":{"type":"boolean","value":false,"location":{"text":"${b64("false")}"}},"ref":[{"type":"var","value":"allow"}]},
                      "body": []
                    },
                    {
                      "location": {"row": 3},
                      "head": {"name":"allow","ref":[{"type":"var","value":"allow"}]},
                      "body": [
                        {
                          "index": 0,
                          "location": {"row":4,"text":"${b64("input.x")}"},
                          "terms": {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"x"}],"location":{"text":"${b64("input.x")}"}}
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val rule = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages.single().rules.single()
            assertThat(rule.bodies).hasSize(1)
            assertThat(rule.default?.rendered).isEqualTo("false")
            // A single sibling keeps the default attached to that one card (review item 14's
            // follow-up) -- nothing split, so this is NOT a split-default entry.
            assertThat(rule.isSplitDefaultEntry).isFalse()
        }

        @Test
        fun aRuleThatIsOnlyEverADefaultDeclarationStillProducesACard() {
            // CHALLENGE edge case: no other body in the file at all shares this name.
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "default": true,
                      "location": {"row": 1},
                      "head": {"name":"risk_score","value":{"type":"number","value":0,"location":{"text":"${b64("0")}"}},"ref":[{"type":"var","value":"risk_score"}]},
                      "body": []
                    }
                  ]
                }
                """.trimIndent()
            )
            val pkg = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages.single()
            val rule = pkg.rules.single()
            assertThat(rule.name).isEqualTo("risk_score")
            assertThat(rule.bodies).isEmpty()
            assertThat(rule.default?.rendered).isEqualTo("0")
            // No sibling body at all predates the split-default promotion and is unaffected by it.
            assertThat(rule.isSplitDefaultEntry).isFalse()
        }

        @Test
        fun aDefaultWhoseNameHasSeveralSiblingCardsBecomesItsOwnSeparateEntry() {
            // review item 14's follow-up: K8S-001/K8S-002-style separately-annotated bodies sharing
            // one Rego name, PLUS a default -- the default becomes one entry of its own, excluded
            // from both sibling cards, so neither repeats "Default outcome" and a changed default
            // doesn't show up as a LOGIC_CHANGED entry per sibling with an empty source diff.
            fun annotatedAllowBody(row: Int, controlId: String, bodyRow: Int) = """
                {
                  "location": {"row": $row},
                  "head": {"name":"allow","ref":[{"type":"var","value":"allow"}]},
                  "annotations": [{"scope":"rule","title":"$controlId title","custom":{"control-id":"$controlId"}}],
                  "body": [
                    {
                      "index": 0,
                      "location": {"row":$bodyRow,"text":"${b64("input.x")}"},
                      "terms": {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"x"}],"location":{"text":"${b64("input.x")}"}}
                    }
                  ]
                }
            """.trimIndent()
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "default": true,
                      "location": {"row": 1},
                      "head": {"name":"allow","value":{"type":"boolean","value":false,"location":{"text":"${b64("false")}"}},"ref":[{"type":"var","value":"allow"}]},
                      "body": []
                    },
                    ${annotatedAllowBody(5, "AZ-001", 6)},
                    ${annotatedAllowBody(10, "AZ-002", 11)},
                    ${annotatedAllowBody(15, "AZ-003", 16)}
                  ]
                }
                """.trimIndent()
            )
            val inspectResult = opaJson.decodeFromString(
                OpaInspectResult.serializer(),
                """
                {
                  "annotations": [
                    {"annotations": {"scope":"rule","title":"AZ-001 title","custom":{"control-id":"AZ-001"}}, "location": {"file":"scratch.rego","row":5}, "path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"},{"type":"string","value":"allow"}]},
                    {"annotations": {"scope":"rule","title":"AZ-002 title","custom":{"control-id":"AZ-002"}}, "location": {"file":"scratch.rego","row":10}, "path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"},{"type":"string","value":"allow"}]},
                    {"annotations": {"scope":"rule","title":"AZ-003 title","custom":{"control-id":"AZ-003"}}, "location": {"file":"scratch.rego","row":15}, "path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"},{"type":"string","value":"allow"}]}
                  ]
                }
                """.trimIndent()
            )
            val rules = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module)), inspectResult).packages.single().rules

            val annotated = rules.filter { it.metadata != null }
            assertThat(annotated).hasSize(3)
            assertThat(annotated).allSatisfy { assertThat(it.default).isNull(); assertThat(it.isSplitDefaultEntry).isFalse() }

            val defaultEntries = rules.filter { it.default != null }
            assertThat(defaultEntries).hasSize(1)
            val defaultEntry = defaultEntries.single()
            assertThat(defaultEntry.isSplitDefaultEntry).isTrue()
            assertThat(defaultEntry.metadata).isNull()
            assertThat(defaultEntry.bodies).isEmpty()
            assertThat(defaultEntry.default?.rendered).isEqualTo("false")
            assertThat(defaultEntry.name).isEqualTo("allow")
        }

        @Test
        fun aSplitDefaultEntryCarriesTheSourceLocationOfItsOwnDefaultDeclaration() {
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "default": true,
                      "location": {"row": 7},
                      "head": {"name":"allow","value":{"type":"boolean","value":false,"location":{"text":"${b64("false")}"}},"ref":[{"type":"var","value":"allow"}]},
                      "body": []
                    },
                    {
                      "location": {"row": 10},
                      "head": {"name":"allow","ref":[{"type":"var","value":"allow"}]},
                      "annotations": [{"scope":"rule","title":"First","custom":{"control-id":"X-001"}}],
                      "body": [
                        {"index":0,"location":{"row":11,"text":"${b64("input.a")}"},"terms":{"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"a"}],"location":{"text":"${b64("input.a")}"}}}
                      ]
                    },
                    {
                      "location": {"row": 15},
                      "head": {"name":"allow","ref":[{"type":"var","value":"allow"}]},
                      "annotations": [{"scope":"rule","title":"Second","custom":{"control-id":"X-002"}}],
                      "body": [
                        {"index":0,"location":{"row":16,"text":"${b64("input.b")}"},"terms":{"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"b"}],"location":{"text":"${b64("input.b")}"}}}
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val inspectResult = opaJson.decodeFromString(
                OpaInspectResult.serializer(),
                """
                {
                  "annotations": [
                    {"annotations": {"scope":"rule","title":"First","custom":{"control-id":"X-001"}}, "location": {"file":"scratch.rego","row":10}, "path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"},{"type":"string","value":"allow"}]},
                    {"annotations": {"scope":"rule","title":"Second","custom":{"control-id":"X-002"}}, "location": {"file":"scratch.rego","row":15}, "path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"},{"type":"string","value":"allow"}]}
                  ]
                }
                """.trimIndent()
            )
            val rules = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module)), inspectResult).packages.single().rules
            val defaultEntry = rules.single { it.isSplitDefaultEntry }
            assertThat(defaultEntry.default?.sourceLocation?.file).isEqualTo("scratch.rego")
            assertThat(defaultEntry.default?.sourceLocation?.row).isEqualTo(7)
        }
    }

    @Nested
    inner class BracketFormPartialRuleGetsAName {
        // Review item 2: `violation[{"msg": m}] if {...}` -- the older bracket-key partial-rule
        // sugar (common in Gatekeeper-style policies) -- has `head.name == null`; opa only
        // populates `head.ref == [var(violation), object(...)]`. Must not be silently dropped.

        @Test
        fun bracketFormPartialRuleIsNamedAndItsConditionRenders() {
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "location": {"row": 1},
                      "head": {
                        "key": {"type":"object","value":[[{"type":"string","value":"msg"},{"type":"var","value":"m"}]]},
                        "value": {"type":"boolean","value":true},
                        "ref": [
                          {"type":"var","value":"violation"},
                          {"type":"object","value":[[{"type":"string","value":"msg"},{"type":"var","value":"m"}]]}
                        ]
                      },
                      "body": [
                        {
                          "index": 0,
                          "location": {"row":2,"text":"${b64("input.tags.env != \"prod\"")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"neq"}]},
                            {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"tags"},{"type":"string","value":"env"}]},
                            {"type":"string","value":"prod"}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val pkg = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages.single()
            val rule = pkg.rules.single()
            assertThat(rule.name).isEqualTo("violation")
            val comparison = rule.bodies.single().conditions.single() as Condition.Comparison
            assertThat(comparison.op).isEqualTo(Operator.NEQ)
            assertThat((comparison.left as Operand.Path).segments).containsExactly(
                PathSegment.Field("input"), PathSegment.Field("tags"), PathSegment.Field("env"),
            )
        }

        @Test
        fun refHeadedDocumentRuleWithNoKeyIsStillExcludedUnaffectedByTheBracketPartialRulePromotion() {
            // CHALLENGE edge case: effectiveName must NOT also start recognising the genuinely
            // different, deliberately out-of-scope ref-headed document-rule case
            // (`fruit.apple.seeds := 12`, spec §14) -- that shape has `head.key == null`.
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "location": {"row": 1},
                      "head": {
                        "value": {"type":"number","value":12},
                        "ref": [
                          {"type":"var","value":"fruit"},
                          {"type":"string","value":"apple"},
                          {"type":"string","value":"seeds"}
                        ]
                      },
                      "body": []
                    }
                  ]
                }
                """.trimIndent()
            )
            val pkg = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages.single()
            assertThat(pkg.rules).isEmpty()
        }
    }

    @Nested
    inner class PackageScopeMetadata {
        // Review item 4: package-scope METADATA (opa's own `module.annotations`, no `opa inspect`
        // needed) is shown on the package page.

        @Test
        fun packageScopeAnnotationPopulatesPolicyPackageMetadata() {
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "annotations": [
                    {"scope":"package","title":"Container hardening","description":"Containers must not be privileged."}
                  ],
                  "rules": []
                }
                """.trimIndent()
            )
            val pkg = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages.single()
            assertThat(pkg.metadata?.title).isEqualTo("Container hardening")
            assertThat(pkg.metadata?.description).isEqualTo("Containers must not be privileged.")
        }

        @Test
        fun noPackageAnnotationLeavesPackageMetadataNull() {
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": []
                }
                """.trimIndent()
            )
            val pkg = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages.single()
            assertThat(pkg.metadata).isNull()
        }

        @Test
        fun ruleScopedAnnotationIsNeverMistakenForPackageScopeMetadata() {
            // CHALLENGE edge case: a rule-scoped annotation must not leak into PolicyPackage.metadata.
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "location": {"row": 1},
                      "head": {"name": "allow", "ref": [{"type":"var","value":"allow"}]},
                      "annotations": [{"scope":"rule","title":"Not a package title"}],
                      "body": [
                        {
                          "index": 0,
                          "location": {"row":2,"text":"${b64("input.x")}"},
                          "terms": {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"x"}],"location":{"text":"${b64("input.x")}"}}
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val pkg = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages.single()
            assertThat(pkg.metadata).isNull()
        }
    }

    @Nested
    inner class PathHumanizationReviewFixes {
        // Review items 5-7, all grounded in real `opa parse` output confirmed during
        // implementation: a bare `_` is renamed by opa to a gensym var (`$0`, `$1`, ...) at parse
        // time, so wildcard detection must read the term's own SOURCE TEXT, never `stringValueOf`.

        @Test
        fun wildcardAnywhereInAPathIsDetectedBySourceTextNotByTheGensymName() {
            // `input.a[_].b == "v"` -- review item 6. Before the fix, the wildcard's gensym name
            // ("$0") never matched the old `name == "_"` check, so the WHOLE operand fell back to
            // raw verbatim source -- confirmed broken for every real policy, since no acceptance-pack
            // fixture or probe ever used `[_]` (CLAUDE.md's own disclosed "untested by fixture" note).
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
                          "location": {"row":1,"text":"${b64("input.a[_].b == \"v\"")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"equal"}]},
                            {"type":"ref","value":[
                              {"type":"var","value":"input"},
                              {"type":"string","value":"a"},
                              {"type":"var","value":"$0","location":{"text":"${b64("_")}"}},
                              {"type":"string","value":"b"}
                            ]},
                            {"type":"string","value":"v"}
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
            assertThat((comparison.left as Operand.Path).segments).containsExactly(
                PathSegment.Field("input"), PathSegment.Field("a"), PathSegment.AnyIndex, PathSegment.Field("b"),
            )
        }

        @Test
        fun twoWildcardsInOnePathAreBothDroppedWithOneSharedFlag() {
            // CHALLENGE edge case: multiple `[_]` in one path.
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
                          "location": {"row":1,"text":"${b64("input.a[_].b[_] == \"v\"")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"equal"}]},
                            {"type":"ref","value":[
                              {"type":"var","value":"input"},
                              {"type":"string","value":"a"},
                              {"type":"var","value":"$0","location":{"text":"${b64("_")}"}},
                              {"type":"string","value":"b"},
                              {"type":"var","value":"$1","location":{"text":"${b64("_")}"}}
                            ]},
                            {"type":"string","value":"v"}
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
            val path = comparison.left as Operand.Path
            assertThat(path.segments).containsExactly(
                PathSegment.Field("input"), PathSegment.Field("a"), PathSegment.AnyIndex, PathSegment.Field("b"), PathSegment.AnyIndex,
            )
            val humanized = PathHumanizer.humanize(path.segments)
            assertThat(humanized.rendered).isEqualTo("`a ▸ b`")
            assertThat(humanized.hasAnyIndex).isTrue()
        }

        @Test
        fun assignedTrailingWildcardPromotesToASomeInBindingLikeTheRealSomeInForm() {
            // `x := input.a.b[_]; x.c == "v"` -- review item 5: a trailing wildcard assignment is
            // semantically identical to `some x in input.a.b`, so it renders through that SAME
            // Condition.SomeIn + VarBinding.Iteration mechanism, not a silent substitution.
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
                          "location": {"row":1,"text":"${b64("x := input.a.b[_]")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"assign"}]},
                            {"type":"var","value":"x"},
                            {"type":"ref","value":[
                              {"type":"var","value":"input"},
                              {"type":"string","value":"a"},
                              {"type":"string","value":"b"},
                              {"type":"var","value":"$0","location":{"text":"${b64("_")}"}}
                            ]}
                          ]
                        },
                        {
                          "index": 1,
                          "location": {"row":2,"text":"${b64("x.c == \"v\"")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"equal"}]},
                            {"type":"ref","value":[{"type":"var","value":"x"},{"type":"string","value":"c"}]},
                            {"type":"string","value":"v"}
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
            val someIn = body.conditions[0] as Condition.SomeIn
            assertThat(someIn.variable).isEqualTo("x")
            assertThat((someIn.collection as Operand.Path).segments).containsExactly(PathSegment.Field("input"), PathSegment.Field("a"), PathSegment.Field("b"))
            val comparison = body.conditions[1] as Condition.Comparison
            assertThat((comparison.left as Operand.Path).segments).containsExactly(
                PathSegment.Field("input"), PathSegment.Field("a"), PathSegment.Field("b"), PathSegment.VarIndex("x"), PathSegment.Field("c"),
            )
        }

        @Test
        fun assignedMiddleWildcardIsNotPromotedAndFallsThroughToPlainSubstitution() {
            // CHALLENGE edge case: only a TRAILING wildcard qualifies for SomeIn promotion -- a
            // middle one (`x := input.a[_].b`) stays a plain Substitution. Not maximally
            // informative (no explicit "for some" bullet), but still honest: the AnyIndex survives
            // in the substituted path and PathHumanizer's existing handling drops it with the
            // "any of:" flag on every later use, same as before this review's changes existed.
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
                          "location": {"row":1,"text":"${b64("x := input.a[_].b")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"assign"}]},
                            {"type":"var","value":"x"},
                            {"type":"ref","value":[
                              {"type":"var","value":"input"},
                              {"type":"string","value":"a"},
                              {"type":"var","value":"$0","location":{"text":"${b64("_")}"}},
                              {"type":"string","value":"b"}
                            ]}
                          ]
                        },
                        {
                          "index": 1,
                          "location": {"row":2,"text":"${b64("x == \"v\"")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"equal"}]},
                            {"type":"var","value":"x"},
                            {"type":"string","value":"v"}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val body = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules[0].bodies[0]
            // The assignment disappears (plain Substitution, not SomeIn, not Unrendered) -- only
            // the bare comparison remains.
            assertThat(body.conditions).hasSize(1)
            val comparison = body.conditions.single() as Condition.Comparison
            assertThat((comparison.left as Operand.Path).segments).containsExactly(
                PathSegment.Field("input"), PathSegment.Field("a"), PathSegment.AnyIndex, PathSegment.Field("b"),
            )
        }

        @Test
        fun dynamicKeyRootedAtInputHumanizesAsItsOwnBracketedSubBreadcrumb() {
            // `data.x[input.a.b] == "v"` -- review item 7.
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
                          "location": {"row":1,"text":"${b64("data.x[input.a.b] == \"v\"")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"equal"}]},
                            {"type":"ref","value":[
                              {"type":"var","value":"data"},
                              {"type":"string","value":"x"},
                              {"type":"ref","value":[
                                {"type":"var","value":"input"},
                                {"type":"string","value":"a"},
                                {"type":"string","value":"b"}
                              ]}
                            ]},
                            {"type":"string","value":"v"}
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
            val path = comparison.left as Operand.Path
            assertThat(path.segments).containsExactly(
                PathSegment.Field("data"), PathSegment.Field("x"),
                PathSegment.KeyPath(listOf(PathSegment.Field("input"), PathSegment.Field("a"), PathSegment.Field("b"))),
            )
            assertThat(PathHumanizer.humanize(path.segments).rendered).isEqualTo("`data ▸ x ▸ [a ▸ b]`")
        }

        @Test
        fun dynamicKeyContainingAQuotedBracketKeyHumanizesBoth() {
            // CHALLENGE edge case: a KeyLiteral segment nested INSIDE a KeyPath's own segments
            // (`data.x[input.labels["key with spaces"]]`) -- both rendering conventions compose.
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
                          "location": {"row":1,"text":"${b64("data.x[input.labels[\"key with spaces\"]] == \"v\"")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"equal"}]},
                            {"type":"ref","value":[
                              {"type":"var","value":"data"},
                              {"type":"string","value":"x"},
                              {"type":"ref","value":[
                                {"type":"var","value":"input"},
                                {"type":"string","value":"labels"},
                                {"type":"string","value":"key with spaces","location":{"text":"${b64("\"key with spaces\"")}"}}
                              ]}
                            ]},
                            {"type":"string","value":"v"}
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
            val path = comparison.left as Operand.Path
            assertThat(path.segments).containsExactly(
                PathSegment.Field("data"), PathSegment.Field("x"),
                PathSegment.KeyPath(listOf(PathSegment.Field("input"), PathSegment.Field("labels"), PathSegment.KeyLiteral("key with spaces"))),
            )
            assertThat(PathHumanizer.humanize(path.segments).rendered).isEqualTo("`data ▸ x ▸ [labels ▸ \"key with spaces\"]`")
        }

        @Test
        fun dynamicKeyThatDoesNotResolveFallsBackForTheWholeContainingPath() {
            // CHALLENGE edge case: `data.x[z.a]` where `z` has no binding at all anywhere --
            // never guessed as a KeyPath; the WHOLE operand falls back to Operand.Unrendered,
            // same conservative posture as every other unresolvable segment in this mapper.
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
                          "location": {"row":1,"text":"${b64("data.x[z.a] == \"v\"")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"equal"}]},
                            {"type":"ref","value":[
                              {"type":"var","value":"data"},
                              {"type":"string","value":"x"},
                              {"type":"ref","value":[
                                {"type":"var","value":"z"},
                                {"type":"string","value":"a"}
                              ]}
                            ],"location":{"text":"${b64("data.x[z.a]")}"}},
                            {"type":"string","value":"v"}
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
            assertThat(unrendered.sourceText).isEqualTo("data.x[z.a]")
        }
    }

    @Nested
    inner class CrossPackageFunctionCalls {
        // Real-world corpus benchmark follow-up: a call into an imported package's function.

        private fun seg(vararg names: String) = names.joinToString(",") { """{"type":"string","value":"$it"}""" }

        /** `lib.kube` defines `check(x)` (arity 1); `caller` imports per [importsJson] and calls [callName] with [argCount] arguments. */
        private fun callerBody(importsJson: String, callNameJson: String, callText: String, argCount: Int): Condition {
            val lib = moduleOf(
                """
                {"package": {"path": [{"type":"var","value":"data"},${seg("lib", "kube")}]},
                 "rules": [{"head": {"name": "check", "args": [{"type":"var","value":"x"}], "ref": [{"type":"var","value":"check"}]}, "body": []}]}
                """.trimIndent()
            )
            val args = (1..argCount).joinToString(",") { """{"type":"ref","value":[{"type":"var","value":"input"},${seg("a$it")}]}""" }
            val caller = moduleOf(
                """
                {"package": {"path": [{"type":"var","value":"data"},${seg("caller")}]},
                 "imports": [$importsJson],
                 "rules": [{"head": {"name": "deny", "ref": [{"type":"var","value":"deny"}]},
                   "body": [{"index": 0, "location": {"row":1,"text":"${b64(callText)}"},
                     "terms": [{"type":"ref","value":[$callNameJson]}, $args]}]}]}
                """.trimIndent()
            )
            val set = AstMapper.mapPolicySet(listOf(ParsedFile("lib.rego", lib), ParsedFile("caller.rego", caller)))
            return set.packages.single { it.path == "caller" }.rules.single().bodies.single().conditions.single()
        }

        private fun importOf(alias: String?, vararg path: String) =
            """{"path": {"type":"ref","value":[{"type":"var","value":"data"},${seg(*path)}]}${alias?.let { ""","alias":"$it"""" } ?: ""}}"""

        private fun assertResolved(condition: Condition) {
            val call = condition as Condition.FunctionCall
            assertThat(call.packagePath).isEqualTo("lib.kube")
            assertThat(call.functionName).isEqualTo("check")
        }

        @Test
        fun anImportAliasLeadsToTheDefiningPackage() {
            assertResolved(callerBody(importOf(null, "lib", "kube"), """{"type":"var","value":"kube"},${seg("check")}""", "kube.check(input.a1)", 1))
        }

        @Test
        fun anExplicitAsAliasReplacesTheLastPathSegment() {
            assertResolved(callerBody(importOf("k", "lib", "kube"), """{"type":"var","value":"k"},${seg("check")}""", "k.check(input.a1)", 1))
        }

        @Test
        fun theOriginalNameIsNotAnAliasOnceRenamed() {
            // `import data.lib.kube as k` makes `kube` undefined in Rego; the mapper must not resolve it either.
            val c = callerBody(importOf("k", "lib", "kube"), """{"type":"var","value":"kube"},${seg("check")}""", "kube.check(input.a1)", 1)
            assertThat((c as Condition.Unrendered).reason).isEqualTo("function-call")
        }

        @Test
        fun aParentPackageImportExtendsThroughTheRemainingSegments() {
            assertResolved(callerBody(importOf(null, "lib"), """{"type":"var","value":"lib"},${seg("kube", "check")}""", "lib.kube.check(input.a1)", 1))
        }

        @Test
        fun aFullDataPathNeedsNoImport() {
            assertResolved(callerBody("", """{"type":"var","value":"data"},${seg("lib", "kube", "check")}""", "data.lib.kube.check(input.a1)", 1))
        }

        @Test
        fun anArityMismatchStaysAFallback() {
            val c = callerBody(importOf(null, "lib", "kube"), """{"type":"var","value":"kube"},${seg("check")}""", "kube.check(input.a1, input.a2)", 2)
            assertThat((c as Condition.Unrendered).reason).isEqualTo("function-call")
        }

        @Test
        fun anUndefinedFunctionInALoadedPackageStaysAFallback() {
            val c = callerBody(importOf(null, "lib", "kube"), """{"type":"var","value":"kube"},${seg("missing")}""", "kube.missing(input.a1)", 1)
            assertThat((c as Condition.Unrendered).reason).isEqualTo("function-call")
        }

        @Test
        fun aPackageThatIsNotLoadedStaysAFallback() {
            val c = callerBody(importOf(null, "other", "pkg"), """{"type":"var","value":"pkg"},${seg("check")}""", "pkg.check(input.a1)", 1)
            assertThat((c as Condition.Unrendered).reason).isEqualTo("function-call")
        }

        @Test
        fun aBuiltinNamespaceIsNeverMistakenForAPackage() {
            // `strings.any_prefix_match` with no `import data.strings`: no alias, not a data path.
            val c = callerBody("", """{"type":"var","value":"strings"},${seg("any_prefix_match")}""", "strings.any_prefix_match(input.a1)", 1)
            assertThat((c as Condition.Unrendered).reason).isEqualTo("function-call")
        }
    }

    @Nested
    inner class PartialRuleIteration {
        // Corpus-benchmark follow-up: `x := kube.containers[_]` over a PARTIAL rule's value.

        private fun seg(vararg names: String) = names.joinToString(",") { """{"type":"string","value":"$it"}""" }
        private val wildcard = """{"type":"var","value":"${'$'}0","location":{"text":"${b64("_")}"}}"""

        private fun libRule(name: String, partial: Boolean) =
            if (partial) """{"head": {"name": "$name", "key": {"type":"var","value":"c"}, "ref": [{"type":"var","value":"$name"}]}, "body": []}"""
            else """{"head": {"name": "$name", "ref": [{"type":"var","value":"$name"}]}, "body": []}"""

        /** `lib.kube` defines [ruleName] (partial or complete); `caller` imports it and runs [bodyExprs]. */
        private fun callerBody(ruleName: String, partial: Boolean, vararg bodyExprs: String): List<Condition> {
            val lib = moduleOf("""{"package": {"path": [{"type":"var","value":"data"},${seg("lib", "kube")}]}, "rules": [${libRule(ruleName, partial)}]}""")
            val caller = moduleOf(
                """
                {"package": {"path": [{"type":"var","value":"data"},${seg("caller")}]},
                 "imports": [{"path": {"type":"ref","value":[{"type":"var","value":"data"},${seg("lib", "kube")}]}}],
                 "rules": [{"head": {"name": "deny", "ref": [{"type":"var","value":"deny"}]}, "body": [${bodyExprs.joinToString(",")}]}]}
                """.trimIndent()
            )
            val set = AstMapper.mapPolicySet(listOf(ParsedFile("lib.rego", lib), ParsedFile("caller.rego", caller)))
            return set.packages.single { it.path == "caller" }.rules.single().bodies.single().conditions
        }

        private fun assign(text: String, valueTermsJson: String) = """
            {"index": 0, "location": {"row":1,"text":"${b64(text)}"}, "terms": [
              {"type":"ref","value":[{"type":"var","value":"assign"}]}, {"type":"var","value":"x"}, $valueTermsJson]}"""

        private fun ruleDocument(vararg tail: PathSegment) =
            listOf(PathSegment.Field("data"), PathSegment.Field("lib"), PathSegment.Field("kube"), PathSegment.Field("containers")) + tail

        @Test
        fun anAliasedPartialRuleWithAWildcardBecomesASomeInOverTheRulesDocumentPath() {
            val conditions = callerBody(
                "containers", true,
                assign("x := kube.containers[_]", """{"type":"ref","value":[{"type":"var","value":"kube"},${seg("containers")},$wildcard]}"""),
            )
            val someIn = conditions.single() as Condition.SomeIn
            assertThat(someIn.variable).isEqualTo("x")
            assertThat((someIn.collection as Operand.Path).segments).isEqualTo(ruleDocument())
        }

        @Test
        fun laterUsesOfTheBoundVariableExtendThatPath() {
            val conditions = callerBody(
                "containers", true,
                assign("x := kube.containers[_]", """{"type":"ref","value":[{"type":"var","value":"kube"},${seg("containers")},$wildcard]}"""),
                """{"index": 1, "location": {"row":2,"text":"${b64("x.image == \"a\"")}"}, "terms": [
                    {"type":"ref","value":[{"type":"var","value":"equal"}]},
                    {"type":"ref","value":[{"type":"var","value":"x"},${seg("image")}]}, {"type":"string","value":"a"}]}""",
            )
            val comparison = conditions[1] as Condition.Comparison
            assertThat((comparison.left as Operand.Path).segments).isEqualTo(ruleDocument(PathSegment.VarIndex("x"), PathSegment.Field("image")))
        }

        @Test
        fun aCompleteRuleWithATailIsNotTreatedAsAPartialRuleDocument() {
            val conditions = callerBody(
                "containers", false,
                assign("x := kube.containers[_]", """{"type":"ref","value":[{"type":"var","value":"kube"},${seg("containers")},$wildcard]}"""),
            )
            assertThat(conditions.single()).isInstanceOf(Condition.Unrendered::class.java)
        }

        @Test
        fun anUnboundDynamicIndexAfterTheRuleKeepsTheWholeAssignmentAsAFallback() {
            val conditions = callerBody(
                "containers", true,
                assign("x := kube.containers[i].name", """{"type":"ref","value":[{"type":"var","value":"kube"},${seg("containers")},{"type":"var","value":"i"},${seg("name")}]}"""),
            )
            assertThat((conditions.single() as Condition.Unrendered).reason).isEqualTo("helper-assignment")
        }

        @Test
        fun aLocalVariableNamedLikeTheAliasTakesPriority() {
            // `kube := input.k; x := kube.containers[_]` -- the local wins, so this is plain substitution, not the rule.
            val conditions = callerBody(
                "containers", true,
                """{"index": 0, "location": {"row":1,"text":"${b64("kube := input.k")}"}, "terms": [
                    {"type":"ref","value":[{"type":"var","value":"assign"}]}, {"type":"var","value":"kube"},
                    {"type":"ref","value":[{"type":"var","value":"input"},${seg("k")}]}]}""",
                assign("x := kube.containers[_]", """{"type":"ref","value":[{"type":"var","value":"kube"},${seg("containers")},$wildcard]}"""),
            )
            val someIn = conditions.single() as Condition.SomeIn
            assertThat((someIn.collection as Operand.Path).segments).containsExactly(PathSegment.Field("input"), PathSegment.Field("k"), PathSegment.Field("containers"))
        }
    }

    @Nested
    inner class FixedArityOperandBuiltins {

        private fun comparisonLeft(callName: String, vararg argJson: String): Operand {
            val module = moduleOf(
                """
                {"package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                 "rules": [{"head": {"name": "deny", "ref": [{"type":"var","value":"deny"}]},
                   "body": [{"index": 0, "location": {"row":1,"text":"${b64("$callName(...) == 1")}"},
                     "terms": [{"type":"ref","value":[{"type":"var","value":"equal"}]},
                       {"type":"call","location":{"text":"${b64("$callName(...)")}"},"value":[
                         {"type":"ref","value":[${callName.split('.').joinToString(",") { """{"type":"${if (it == callName.split('.').first()) "var" else "string"}","value":"$it"}""" }}]}
                         ${argJson.joinToString("") { ",$it" }}]},
                       {"type":"number","value":1}]}]}]}
                """.trimIndent()
            )
            val body = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages.single().rules.single().bodies.single()
            return (body.conditions.single() as Condition.Comparison).left
        }

        private val pathArg = """{"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"s"}]}"""
        private val strArg = """{"type":"string","value":":"}"""

        @Test
        fun theExactArityIsPromotedToABuiltinCall() {
            assertThat(comparisonLeft("split", pathArg, strArg)).isEqualTo(Operand.BuiltinCall("split", listOf(Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("s"))), Operand.Literal("\":\""))))
            assertThat(comparisonLeft("replace", pathArg, strArg, strArg)).isInstanceOf(Operand.BuiltinCall::class.java)
            assertThat(comparisonLeft("array.concat", pathArg, pathArg)).isInstanceOf(Operand.BuiltinCall::class.java)
        }

        @Test
        fun anyOtherArityStaysRawSource() {
            // opa's own checker rejects these, but the mapper must not rely on that.
            assertThat(comparisonLeft("split", pathArg)).isInstanceOf(Operand.Unrendered::class.java)
            assertThat(comparisonLeft("replace", pathArg, strArg)).isInstanceOf(Operand.Unrendered::class.java)
            assertThat(comparisonLeft("abs", pathArg, pathArg)).isInstanceOf(Operand.Unrendered::class.java)
        }

        @Test
        fun anArgumentThatIsItselfUnrenderableKeepsTheCallButIsCountedInside() {
            val unbound = """{"type":"var","value":"nowhere"}"""
            val call = comparisonLeft("split", unbound, strArg) as Operand.BuiltinCall
            assertThat(call.args.first()).isInstanceOf(Operand.Unrendered::class.java)
        }
    }

    @Nested
    inner class ComprehensionOperands {

        private fun seg(vararg names: String) = names.joinToString(",") { """{"type":"string","value":"$it"}""" }
        private val someAInXs = """{"index": 0, "location": {"row":1,"text":"${b64("some a in input.xs")}"}, "terms": {"symbols": [{"type":"call","value":[
            {"type":"ref","value":[{"type":"var","value":"internal"},{"type":"string","value":"member_2"}]},
            {"type":"var","value":"a"}, {"type":"ref","value":[{"type":"var","value":"input"},${seg("xs")}]}]}]}}"""
        private val aRoleIsM = """{"index": 1, "location": {"row":1,"text":"${b64("a.role == \"m\"")}"}, "terms": [
            {"type":"ref","value":[{"type":"var","value":"equal"}]},
            {"type":"ref","value":[{"type":"var","value":"a"},${seg("role")}]}, {"type":"string","value":"m"}]}"""

        /** A rule `deny if count(<comprehension>) > 0`, optionally preceded by [precedingExprs]; returns its first body's conditions. */
        private fun conditionsFor(compJson: String, vararg precedingExprs: String): List<Condition> {
            val module = moduleOf(
                """
                {"package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                 "rules": [{"head": {"name": "deny", "ref": [{"type":"var","value":"deny"}]},
                   "body": [${precedingExprs.joinToString("") { "$it," }}
                     {"index": 9, "location": {"row":5,"text":"${b64("count(...) > 0")}"}, "terms": [
                       {"type":"ref","value":[{"type":"var","value":"gt"}]},
                       {"type":"call","location":{"text":"${b64("count(...)")}"},"value":[{"type":"ref","value":[{"type":"var","value":"count"}]}, $compJson]},
                       {"type":"number","value":0}]}]}]}
                """.trimIndent()
            )
            return AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages.single().rules.single().bodies.single().conditions
        }

        private fun setComp(headJson: String, vararg bodyExprs: String) =
            """{"type":"setcomprehension","value":{"term": $headJson, "body": [${bodyExprs.joinToString(",")}]}}"""

        private fun comprehensionIn(conditions: List<Condition>): Operand.Comprehension =
            ((conditions.last() as Condition.Comparison).left as Operand.BuiltinCall).args.single() as Operand.Comprehension

        @Test
        fun aFullyRenderableSetComprehensionIsBuiltWithItsHeadResolvedThroughTheBodysBindings() {
            val comp = comprehensionIn(conditionsFor(setComp("""{"type":"var","value":"a"}""", someAInXs, aRoleIsM)))
            assertThat(comp.kind).isEqualTo(io.explico.model.ComprehensionKind.SET)
            assertThat((comp.term as Operand.Path).segments).containsExactly(PathSegment.Field("input"), PathSegment.Field("xs"), PathSegment.VarIndex("a"))
            assertThat(comp.body).hasSize(2)
        }

        @Test
        fun theComprehensionsOwnVariablesDoNotLeakIntoTheEnclosingBody() {
            // After the comprehension, `a` is NOT bound in the rule body: a later use of `a` is unbound.
            val conditions = conditionsFor(setComp("""{"type":"var","value":"a"}""", someAInXs, aRoleIsM))
            assertThat(conditions).hasSize(1) // only the count comparison; no SomeIn leaked out
        }

        @Test
        fun aVariableBoundOutsideIsVisibleInsideTheBody() {
            val outerSome = """{"index": 0, "location": {"row":1,"text":"${b64("some o in input.os")}"}, "terms": {"symbols": [{"type":"call","value":[
                {"type":"ref","value":[{"type":"var","value":"internal"},{"type":"string","value":"member_2"}]},
                {"type":"var","value":"o"}, {"type":"ref","value":[{"type":"var","value":"input"},${seg("os")}]}]}]}}"""
            val usesOuter = """{"index": 1, "location": {"row":1,"text":"${b64("a.team == o.name")}"}, "terms": [
                {"type":"ref","value":[{"type":"var","value":"equal"}]},
                {"type":"ref","value":[{"type":"var","value":"a"},${seg("team")}]},
                {"type":"ref","value":[{"type":"var","value":"o"},${seg("name")}]}]}"""
            val comp = comprehensionIn(conditionsFor(setComp("""{"type":"var","value":"a"}""", someAInXs, usesOuter), outerSome))
            val cmp = comp.body[1] as Condition.Comparison
            assertThat((cmp.right as Operand.Path).segments).containsExactly(PathSegment.Field("input"), PathSegment.Field("os"), PathSegment.VarIndex("o"), PathSegment.Field("name"))
        }

        @Test
        fun anUnbindableHeadKeepsTheWholeComprehensionAsAFallback() {
            val conditions = conditionsFor(setComp("""{"type":"var","value":"nowhere"}""", someAInXs, aRoleIsM))
            assertThat((conditions.single() as Condition.Unrendered).reason).isEqualTo("comprehension")
        }

        @Test
        fun anUnrenderableConditionInsideKeepsTheWholeComprehensionAsAFallback() {
            val unsupported = """{"index": 1, "location": {"row":1,"text":"${b64("net.cidr_is_valid(a)")}"}, "terms": [
                {"type":"ref","value":[{"type":"var","value":"net"},{"type":"string","value":"cidr_is_valid"}]},
                {"type":"var","value":"a"}]}"""
            val conditions = conditionsFor(setComp("""{"type":"var","value":"a"}""", someAInXs, unsupported))
            assertThat((conditions.single() as Condition.Unrendered).reason).isEqualTo("comprehension")
        }

        @Test
        fun aWithOverrideInsideKeepsTheWholeComprehensionAsAFallback() {
            val withExpr = """{"index": 1, "location": {"row":1,"text":"${b64("a.role == \"m\" with input as {}")}"}, "with": [{"target": {"type":"ref","value":[{"type":"var","value":"input"}]}, "value": {"type":"object","value":[]}}],
                "terms": [{"type":"ref","value":[{"type":"var","value":"equal"}]}, {"type":"ref","value":[{"type":"var","value":"a"},${seg("role")}]}, {"type":"string","value":"m"}]}"""
            val conditions = conditionsFor(setComp("""{"type":"var","value":"a"}""", someAInXs, withExpr))
            assertThat((conditions.single() as Condition.Unrendered).reason).isEqualTo("comprehension")
        }

        @Test
        fun aNestedComprehensionIsRenderedWhenItsOwnBodyIs() {
            val inner = setComp("""{"type":"var","value":"b"}""", someAInXs.replace("\"a\"", "\"b\"").replace("some a in", "some b in"))
            val nestedCompare = """{"index": 1, "location": {"row":1,"text":"${b64("count({b | ...}) > 0")}"}, "terms": [
                {"type":"ref","value":[{"type":"var","value":"gt"}]},
                {"type":"call","location":{"text":"${b64("count(...)")}"},"value":[{"type":"ref","value":[{"type":"var","value":"count"}]}, $inner]},
                {"type":"number","value":0}]}"""
            val comp = comprehensionIn(conditionsFor(setComp("""{"type":"var","value":"a"}""", someAInXs, nestedCompare)))
            assertThat(((comp.body[1] as Condition.Comparison).left as Operand.BuiltinCall).args.single()).isInstanceOf(Operand.Comprehension::class.java)
        }

        @Test
        fun aComprehensionMissingItsBodyFallsBackInsteadOfCrashing() {
            val conditions = conditionsFor("""{"type":"setcomprehension","value":{"term":{"type":"var","value":"a"}}}""")
            assertThat((conditions.single() as Condition.Unrendered).reason).isEqualTo("comprehension")
        }
    }

    @Nested
    inner class FunctionCallsAndMessagePlaceholders {
        // Review items 8-9.

        @Test
        fun userDefinedFunctionCallPromotesToFunctionCallWithItsArgumentResolved() {
            // `not is_exempt(input.request.namespace)` -- review item 8: a same-package call to a
            // declared function (head.args != null) promotes to Condition.FunctionCall.
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "is_exempt", "args": [{"type":"var","value":"ns"}], "ref": [{"type":"var","value":"is_exempt"}]},
                      "body": []
                    },
                    {
                      "head": {"name": "deny", "ref": [{"type":"var","value":"deny"}]},
                      "body": [
                        {
                          "index": 0,
                          "negated": true,
                          "location": {"row":1,"text":"${b64("is_exempt(input.request.namespace)")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"is_exempt"}]},
                            {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"request"},{"type":"string","value":"namespace"}]}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val pkg = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages.single()
            val denyBody = pkg.rules.single { it.name == "deny" }.bodies.single()
            val call = denyBody.conditions.single() as Condition.FunctionCall
            assertThat(call.packagePath).isEqualTo("scratch")
            assertThat(call.functionName).isEqualTo("is_exempt")
            assertThat(call.negated).isTrue()
            assertThat((call.args.single() as Operand.Path).segments).containsExactly(
                PathSegment.Field("input"), PathSegment.Field("request"), PathSegment.Field("namespace"),
            )
            // the function's own card declares its parameter.
            assertThat(pkg.rules.single { it.name == "is_exempt" }.parameters).containsExactly("ns")
        }

        @Test
        fun crossPackageFunctionCallIsNotResolvedStaysAsFallback() {
            // CHALLENGE edge case / disclosed limitation: review item 8's promotion is same-package
            // only. `helper.is_prod(input.x)` -- a 2-segment dotted call name -- never matches any
            // key in the (bare-name-keyed) same-package function registry, so it falls back exactly
            // as before this promotion, never a crash, never a partial/guessed resolution.
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "deny", "ref": [{"type":"var","value":"deny"}]},
                      "body": [
                        {
                          "index": 0,
                          "location": {"row":1,"text":"${b64("helper.is_prod(input.x)")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"helper"},{"type":"string","value":"is_prod"}]},
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
            val unrendered = body.conditions.single() as Condition.Unrendered
            assertThat(unrendered.reason).isEqualTo("function-call")
        }

        @Test
        fun varRootedSprintfPlaceholderStaysNullWhenTheVariableIsNotAPlainPath() {
            // CHALLENGE edge case: `x := count(input.y); msg := sprintf("count is %v", [x])` -- `x`
            // is bound (VarBinding.NonPath), but not to a plain path, so there's no meaningful
            // field chain to humanize -- stays null rather than guessing "[x]".
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
                          "location": {"row":1,"text":"${b64("x := count(input.y)")}"},
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
                          "location": {"row":2,"text":"${b64("msg := sprintf(\"count is %v\", [x])")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"assign"}]},
                            {"type":"var","value":"msg"},
                            {"type":"call","value":[
                              {"type":"ref","value":[{"type":"var","value":"sprintf"}]},
                              {"type":"string","value":"count is %v"},
                              {"type":"array","value":[{"type":"var","value":"x","location":{"text":"${b64("x")}"}}]}
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
            assertThat(body.producesValue).isNull()
            assertThat(body.messageTemplate).isEqualTo("count is %v")
        }

        @Test
        fun sprintfWithOneResolvableAndOneUnresolvablePlaceholderStaysNullForTheWholeMessage() {
            // CHALLENGE edge case: mixing a promotable placeholder (a var-rooted plain field
            // chain) with a genuinely unresolvable one (an unbound var) must not produce a
            // partially-rendered message -- spec §6.7's existing all-or-nothing rule for
            // renderProducesValue, unaffected by this promotion.
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
                          "location": {"row":1,"text":"${b64("some container in input.containers")}"},
                          "terms": {
                            "symbols": [
                              {"type":"call","value":[
                                {"type":"ref","value":[{"type":"var","value":"internal"},{"type":"string","value":"member_2"}]},
                                {"type":"var","value":"container"},
                                {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"containers"}]}
                              ]}
                            ]
                          }
                        },
                        {
                          "index": 1,
                          "location": {"row":2,"text":"${b64("msg := sprintf(\"%v and %v\", [container.name, other])")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"assign"}]},
                            {"type":"var","value":"msg"},
                            {"type":"call","value":[
                              {"type":"ref","value":[{"type":"var","value":"sprintf"}]},
                              {"type":"string","value":"%v and %v"},
                              {"type":"array","value":[
                                {"type":"ref","value":[{"type":"var","value":"container"},{"type":"string","value":"name"}]},
                                {"type":"var","value":"other","location":{"text":"${b64("other")}"}}
                              ]}
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
            assertThat(body.producesValue).isNull()
            assertThat(body.messageTemplate).isEqualTo("%v and %v")
        }

        @Test
        fun objectHeadMessageVarWithANonVarMsgValueStaysNullRatherThanGuessing() {
            // CHALLENGE edge case: `deny contains {"msg": "literal", "severity": "high"}` -- the
            // "msg" key's own value is a string literal, not a bare var, so there's no variable to
            // attribute an assignment to -- messageVarOf stays null, same as "no msg key at all".
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {
                        "name": "deny",
                        "key": {"type":"object","value":[[{"type":"string","value":"msg"},{"type":"string","value":"literal"}],[{"type":"string","value":"severity"},{"type":"string","value":"high"}]]},
                        "value": {"type":"boolean","value":true},
                        "ref": [{"type":"var","value":"deny"}]
                      },
                      "body": [
                        {
                          "index": 0,
                          "location": {"row":1,"text":"${b64("input.x")}"},
                          "terms": {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"x"}],"location":{"text":"${b64("input.x")}"}}
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val body = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules[0].bodies[0]
            assertThat(body.producesValue).isNull()
            assertThat(body.messageTemplate).isNull()
        }
    }

    @Nested
    inner class MoreBuiltinsReviewItem11 {

        @Test
        fun netCidrContainsAndATypeCheckPromoteToConditionPositionBuiltinCall() {
            // `net.cidr_contains("10.0.0.0/8", input.client.ip)` and `is_string(input.user.id)`.
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
                          "location": {"row":1,"text":"${b64("net.cidr_contains(\"10.0.0.0/8\", input.client.ip)")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"net"},{"type":"string","value":"cidr_contains"}]},
                            {"type":"string","value":"10.0.0.0/8"},
                            {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"client"},{"type":"string","value":"ip"}]}
                          ]
                        },
                        {
                          "index": 1,
                          "location": {"row":2,"text":"${b64("is_string(input.user.id)")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"is_string"}]},
                            {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"user"},{"type":"string","value":"id"}]}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val body = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules[0].bodies[0]
            val cidr = body.conditions[0] as Condition.BuiltinCall
            assertThat(cidr.name).isEqualTo("net.cidr_contains")
            assertThat(cidr.args).hasSize(2)
            val isString = body.conditions[1] as Condition.BuiltinCall
            assertThat(isString.name).isEqualTo("is_string")
            assertThat((isString.args.single() as Operand.Path).segments).containsExactly(
                PathSegment.Field("input"), PathSegment.Field("user"), PathSegment.Field("id"),
            )
        }

        @Test
        fun typeCheckWithAnUnmappableComprehensionArgumentFallsBackWhole() {
            // `is_string({x | some x in input.xs})` -- CHALLENGE edge case: the SAME "whole call
            // falls back on an unsupported arg" guard every other CONDITION_BUILTINS member
            // already has, now exercised for a newly-promoted name.
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
                          "location": {"row":1,"text":"${b64("is_string({x | some x in input.xs})")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"is_string"}]},
                            {"type":"setcomprehension","value":{"term":{"type":"var","value":"x"},"body":[]}}
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
            assertThat(fallback.reason).isEqualTo("comprehension")
        }

        @Test
        fun sumAndObjectKeysPromoteToOperandPositionBuiltinCallEvenNested() {
            // `sum(input.amounts) > 1000` and `count(object.keys(input.labels)) > 3`.
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
                          "location": {"row":1,"text":"${b64("sum(input.amounts) > 1000")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"gt"}]},
                            {"type":"call","location":{"text":"${b64("sum(input.amounts)")}"},"value":[
                              {"type":"ref","value":[{"type":"var","value":"sum"}]},
                              {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"amounts"}]}
                            ]},
                            {"type":"number","value":1000}
                          ]
                        },
                        {
                          "index": 1,
                          "location": {"row":2,"text":"${b64("count(object.keys(input.labels)) > 3")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"gt"}]},
                            {"type":"call","location":{"text":"${b64("count(object.keys(input.labels))")}"},"value":[
                              {"type":"ref","value":[{"type":"var","value":"count"}]},
                              {"type":"call","location":{"text":"${b64("object.keys(input.labels)")}"},"value":[
                                {"type":"ref","value":[{"type":"var","value":"object"},{"type":"string","value":"keys"}]},
                                {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"labels"}]}
                              ]}
                            ]},
                            {"type":"number","value":3}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val body = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules[0].bodies[0]
            val sumComparison = body.conditions[0] as Condition.Comparison
            val sumCall = sumComparison.left as Operand.BuiltinCall
            assertThat(sumCall.name).isEqualTo("sum")
            assertThat((sumCall.args.single() as Operand.Path).segments).containsExactly(PathSegment.Field("input"), PathSegment.Field("amounts"))

            val countComparison = body.conditions[1] as Condition.Comparison
            val countCall = countComparison.left as Operand.BuiltinCall
            assertThat(countCall.name).isEqualTo("count")
            val keysCall = countCall.args.single() as Operand.BuiltinCall
            assertThat(keysCall.name).isEqualTo("object.keys")
            assertThat((keysCall.args.single() as Operand.Path).segments).containsExactly(PathSegment.Field("input"), PathSegment.Field("labels"))
        }

        @Test
        fun operandPositionBuiltinWithAnUnmappableComprehensionArgumentFallsBackWhole() {
            // `sum({x | some x in input.xs}) > 0` -- CHALLENGE edge case: same guard as the
            // condition-position one above, exercised for a newly-promoted OPERAND builtin.
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
                          "location": {"row":1,"text":"${b64("sum({x | some x in input.xs}) > 0")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"gt"}]},
                            {"type":"call","location":{"text":"${b64("sum({x | some x in input.xs})")}"},"value":[
                              {"type":"ref","value":[{"type":"var","value":"sum"}]},
                              {"type":"setcomprehension","value":{"term":{"type":"var","value":"x"},"body":[]}}
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
            val fallback = body.conditions.single() as Condition.Unrendered
            assertThat(fallback.reason).isEqualTo("comprehension")
        }
    }

    @Nested
    inner class JsonIsValidBuiltin {
        // Spec §14 promotion (coverage-audit backlog): json.is_valid returns `false` (not
        // undefined) for both invalid JSON and a non-string argument, confirmed via a real
        // `opa eval` run -- so it joins the unary type-check family rather than needing a new
        // model shape. It is a DOTTED builtin name, same shape as net.cidr_contains/regex.match.

        @Test
        fun dottedNamePromotesToConditionPositionBuiltinCall() {
            // `json.is_valid(input.payload)`.
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
                          "location": {"row":1,"text":"${b64("json.is_valid(input.payload)")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"json"},{"type":"string","value":"is_valid"}]},
                            {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"payload"}]}
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
            assertThat(call.name).isEqualTo("json.is_valid")
            assertThat(call.negated).isFalse()
            assertThat((call.args.single() as Operand.Path).segments).containsExactly(PathSegment.Field("input"), PathSegment.Field("payload"))
        }

        @Test
        fun negatedFormCarriesNegatedTrue() {
            // `not json.is_valid(input.payload)` -- both polarities are faithful (opa returns
            // `false`, never undefined, for an invalid or non-string argument), so there is no
            // reason this builtin should be excluded from the negated form every other member of
            // CONDITION_BUILTINS already supports.
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
                          "negated": true,
                          "location": {"row":1,"text":"${b64("not json.is_valid(input.payload)")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"json"},{"type":"string","value":"is_valid"}]},
                            {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"payload"}]}
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
            assertThat(call.negated).isTrue()
        }

        @Test
        @DisplayName("CHALLENGE edge case: an unmappable (comprehension) argument falls back whole, same guard as every other CONDITION_BUILTINS member")
        fun unmappableArgumentFallsBackWhole() {
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
                          "location": {"row":1,"text":"${b64("json.is_valid({x | some x in input.xs})")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"json"},{"type":"string","value":"is_valid"}]},
                            {"type":"setcomprehension","value":{"term":{"type":"var","value":"x"},"body":[]}}
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
            assertThat(fallback.reason).isEqualTo("comprehension")
        }
    }

    @Nested
    inner class RuleValueReferencesReviewItem10 {

        @Test
        fun bareSamePackageReferenceToACompleteRulePromotesToRuleValueReference() {
            // `count(input.items) > threshold`, "threshold" an ordinary complete rule in the same package.
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "threshold", "value": {"type":"number","value":5}},
                      "body": [
                        {"index":0,"location":{"row":1,"text":"${b64("input.enabled")}"},"terms":{"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"enabled"}]}}
                      ]
                    },
                    {
                      "head": {"name": "allow", "ref": [{"type":"var","value":"allow"}]},
                      "body": [
                        {
                          "index": 0,
                          "location": {"row":2,"text":"${b64("count(input.items) > threshold")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"gt"}]},
                            {"type":"call","location":{"text":"${b64("count(input.items)")}"},"value":[
                              {"type":"ref","value":[{"type":"var","value":"count"}]},
                              {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"items"}]}
                            ]},
                            {"type":"var","value":"threshold"}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val policySet = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module)))
            val comparison = policySet.packages[0].rules.first { it.name == "allow" }.bodies[0].conditions.single() as Condition.Comparison
            val reference = comparison.right as Operand.RuleValueReference
            assertThat(reference.packagePath).isEqualTo("scratch")
            assertThat(reference.ruleName).isEqualTo("threshold")
        }

        @Test
        @DisplayName("guard: a bare reference to a PARTIAL rule's value is never promoted, same reasoning as ruleReferenceIfKnown")
        fun bareSamePackageReferenceToAPartialRuleStaysUnrendered() {
            // `count(flagged) > 1`, "flagged" a partial (contains) rule in the same package.
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "flagged", "key": {"type":"var","value":"x"}, "ref": [{"type":"var","value":"flagged"}]},
                      "body": [
                        {"index":0,"location":{"row":1,"text":"${b64("input.x")}"},"terms":{"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"x"}]}}
                      ]
                    },
                    {
                      "head": {"name": "warn", "ref": [{"type":"var","value":"warn"}]},
                      "body": [
                        {
                          "index": 0,
                          "location": {"row":2,"text":"${b64("count(flagged) > 1")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"gt"}]},
                            {"type":"call","location":{"text":"${b64("count(flagged)")}"},"value":[
                              {"type":"ref","value":[{"type":"var","value":"count"}]},
                              {"type":"var","value":"flagged"}
                            ]},
                            {"type":"number","value":1}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val policySet = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module)))
            val comparison = policySet.packages[0].rules.first { it.name == "warn" }.bodies[0].conditions.single() as Condition.Comparison
            val countCall = comparison.left as Operand.BuiltinCall
            assertThat(countCall.args.single()).isInstanceOf(Operand.Unrendered::class.java)
        }

        @Test
        @DisplayName("guard (regression): a genuinely unknown bare var -- no rule, no binding -- still falls back to Operand.Unrendered")
        fun bareReferenceToNoKnownRuleOrBindingStaysUnrendered() {
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
                          "location": {"row":1,"text":"${b64("count(input.items) > mystery")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"gt"}]},
                            {"type":"call","location":{"text":"${b64("count(input.items)")}"},"value":[
                              {"type":"ref","value":[{"type":"var","value":"count"}]},
                              {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"items"}]}
                            ]},
                            {"type":"var","value":"mystery","location":{"text":"${b64("mystery")}"}}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val policySet = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module)))
            val comparison = policySet.packages[0].rules.first { it.name == "allow" }.bodies[0].conditions.single() as Condition.Comparison
            val fallback = comparison.right as Operand.Unrendered
            assertThat(fallback.sourceText).isEqualTo("mystery")
        }

        @Test
        fun importAliasReferenceToACompleteRuleInAnotherPackagePromotes() {
            // `config.max_items` with `import data.config` in a different package.
            val configModule = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"config"}]},
                  "rules": [
                    {
                      "head": {"name": "max_items", "value": {"type":"number","value":10}},
                      "body": [
                        {"index":0,"location":{"row":1,"text":"${b64("input.x")}"},"terms":{"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"x"}]}}
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val checksModule = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"checks"}]},
                  "imports": [{"path": {"type":"ref","value":[{"type":"var","value":"data"},{"type":"string","value":"config"}]}}],
                  "rules": [
                    {
                      "head": {"name": "allow", "ref": [{"type":"var","value":"allow"}]},
                      "body": [
                        {
                          "index": 0,
                          "location": {"row":1,"text":"${b64("5 < config.max_items")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"lt"}]},
                            {"type":"number","value":5},
                            {"type":"ref","value":[{"type":"var","value":"config"},{"type":"string","value":"max_items"}]}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val policySet = AstMapper.mapPolicySet(listOf(ParsedFile("config.rego", configModule), ParsedFile("checks.rego", checksModule)))
            val comparison = policySet.packages.first { it.path == "checks" }.rules.first { it.name == "allow" }.bodies[0].conditions.single() as Condition.Comparison
            val reference = comparison.right as Operand.RuleValueReference
            assertThat(reference.packagePath).isEqualTo("config")
            assertThat(reference.ruleName).isEqualTo("max_items")
        }

        @Test
        @DisplayName("guard: an import-alias reference to a PARTIAL rule in another package is never promoted")
        fun importAliasReferenceToAPartialRuleStaysUnrendered() {
            val configModule = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"config"}]},
                  "rules": [
                    {
                      "head": {"name": "flagged", "key": {"type":"var","value":"x"}, "ref": [{"type":"var","value":"flagged"}]},
                      "body": [
                        {"index":0,"location":{"row":1,"text":"${b64("input.x")}"},"terms":{"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"x"}]}}
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val checksModule = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"checks"}]},
                  "imports": [{"path": {"type":"ref","value":[{"type":"var","value":"data"},{"type":"string","value":"config"}]}}],
                  "rules": [
                    {
                      "head": {"name": "allow", "ref": [{"type":"var","value":"allow"}]},
                      "body": [
                        {
                          "index": 0,
                          "location": {"row":1,"text":"${b64("5 < config.flagged")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"lt"}]},
                            {"type":"number","value":5},
                            {"type":"ref","value":[{"type":"var","value":"config"},{"type":"string","value":"flagged"}]}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val policySet = AstMapper.mapPolicySet(listOf(ParsedFile("config.rego", configModule), ParsedFile("checks.rego", checksModule)))
            val comparison = policySet.packages.first { it.path == "checks" }.rules.first { it.name == "allow" }.bodies[0].conditions.single() as Condition.Comparison
            assertThat(comparison.right).isInstanceOf(Operand.Unrendered::class.java)
        }

        @Test
        fun fullDataPathReferenceToACompleteRuleInAnotherPackagePromotes() {
            // `data.config.max_items`, no import at all.
            val configModule = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"config"}]},
                  "rules": [
                    {
                      "head": {"name": "max_items", "value": {"type":"number","value":10}},
                      "body": [
                        {"index":0,"location":{"row":1,"text":"${b64("input.x")}"},"terms":{"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"x"}]}}
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val checksModule = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"checks"}]},
                  "rules": [
                    {
                      "head": {"name": "allow", "ref": [{"type":"var","value":"allow"}]},
                      "body": [
                        {
                          "index": 0,
                          "location": {"row":1,"text":"${b64("5 < data.config.max_items")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"lt"}]},
                            {"type":"number","value":5},
                            {"type":"ref","value":[{"type":"var","value":"data"},{"type":"string","value":"config"},{"type":"string","value":"max_items"}]}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val policySet = AstMapper.mapPolicySet(listOf(ParsedFile("config.rego", configModule), ParsedFile("checks.rego", checksModule)))
            val comparison = policySet.packages.first { it.path == "checks" }.rules.first { it.name == "allow" }.bodies[0].conditions.single() as Condition.Comparison
            val reference = comparison.right as Operand.RuleValueReference
            assertThat(reference.packagePath).isEqualTo("config")
            assertThat(reference.ruleName).isEqualTo("max_items")
        }

        @Test
        @DisplayName("guard: a full data path to a PARTIAL rule is never promoted to a link -- unlike the bare/alias cases, it falls through to an ordinary data Path, not Unrendered, since a comparison over it makes no tautological-truthiness claim the way a bare condition-position reference would")
        fun fullDataPathToAPartialRuleFallsThroughToAnOrdinaryPathNotALink() {
            val configModule = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"config"}]},
                  "rules": [
                    {
                      "head": {"name": "flagged", "key": {"type":"var","value":"x"}, "ref": [{"type":"var","value":"flagged"}]},
                      "body": [
                        {"index":0,"location":{"row":1,"text":"${b64("input.x")}"},"terms":{"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"x"}]}}
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val checksModule = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"checks"}]},
                  "rules": [
                    {
                      "head": {"name": "allow", "ref": [{"type":"var","value":"allow"}]},
                      "body": [
                        {
                          "index": 0,
                          "location": {"row":1,"text":"${b64("5 < data.config.flagged")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"lt"}]},
                            {"type":"number","value":5},
                            {"type":"ref","value":[{"type":"var","value":"data"},{"type":"string","value":"config"},{"type":"string","value":"flagged"}]}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val policySet = AstMapper.mapPolicySet(listOf(ParsedFile("config.rego", configModule), ParsedFile("checks.rego", checksModule)))
            val comparison = policySet.packages.first { it.path == "checks" }.rules.first { it.name == "allow" }.bodies[0].conditions.single() as Condition.Comparison
            val path = comparison.right as Operand.Path
            assertThat(path.segments).containsExactly(
                PathSegment.Field("data"), PathSegment.Field("config"), PathSegment.Field("flagged"),
            )
        }

        @Test
        @DisplayName("guard: a full data path with no rule behind it stays an ordinary data Path, not promoted")
        fun fullDataPathWithNoRuleBehindItStaysAnOrdinaryPath() {
            val configModule = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"config"}]},
                  "rules": [
                    {
                      "head": {"name": "max_items", "value": {"type":"number","value":10}},
                      "body": [
                        {"index":0,"location":{"row":1,"text":"${b64("input.x")}"},"terms":{"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"x"}]}}
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val checksModule = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"checks"}]},
                  "rules": [
                    {
                      "head": {"name": "allow", "ref": [{"type":"var","value":"allow"}]},
                      "body": [
                        {
                          "index": 0,
                          "location": {"row":1,"text":"${b64("5 < data.config.other_field")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"lt"}]},
                            {"type":"number","value":5},
                            {"type":"ref","value":[{"type":"var","value":"data"},{"type":"string","value":"config"},{"type":"string","value":"other_field"}]}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val policySet = AstMapper.mapPolicySet(listOf(ParsedFile("config.rego", configModule), ParsedFile("checks.rego", checksModule)))
            val comparison = policySet.packages.first { it.path == "checks" }.rules.first { it.name == "allow" }.bodies[0].conditions.single() as Condition.Comparison
            val path = comparison.right as Operand.Path
            assertThat(path.segments).containsExactly(
                PathSegment.Field("data"), PathSegment.Field("config"), PathSegment.Field("other_field"),
            )
        }

        @Test
        @DisplayName("regression (real crash found this session): a data-rooted chain with a composite, non-string segment -- e.g. `data.grants[input.user.id]` -- must not crash, and keeps its ordinary KeyPath rendering")
        fun dataRootedChainWithACompositeSegmentDoesNotCrashAndStaysAnOrdinaryKeyPath() {
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "allow", "key": {"type":"var","value":"grant"}, "ref": [{"type":"var","value":"allow"}]},
                      "body": [
                        {
                          "index": 0,
                          "location": {"row":1,"text":"${b64("some grant in data.grants[input.user.id]")}"},
                          "terms": {
                            "symbols": [
                              {"type":"call","value":[
                                {"type":"ref","value":[{"type":"var","value":"internal"},{"type":"string","value":"member_2"}]},
                                {"type":"var","value":"grant"},
                                {"type":"ref","value":[
                                  {"type":"var","value":"data"},
                                  {"type":"string","value":"grants"},
                                  {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"user"},{"type":"string","value":"id"}]}
                                ]}
                              ]}
                            ]
                          }
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val policySet = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module)))
            val someIn = policySet.packages[0].rules.first { it.name == "allow" }.bodies[0].conditions.single() as Condition.SomeIn
            val collection = someIn.collection as Operand.Path
            assertThat(collection.segments).containsExactly(
                PathSegment.Field("data"),
                PathSegment.Field("grants"),
                PathSegment.KeyPath(listOf(PathSegment.Field("input"), PathSegment.Field("user"), PathSegment.Field("id"))),
            )
        }
    }

    @Nested
    inner class ConstantValueRulesReviewItem10 {

        @Test
        fun bodylessConstantRulePromotesToConstantValueWithEmptyBodies() {
            // `max_items := 10` -- no `if` at all. opa's own synthetic body expr's location.text
            // equals the value's own location.text ("10" == "10"), the mechanical signal.
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "max_items", "value": {"type":"number","value":10,"location":{"text":"${b64("10")}"}}},
                      "location": {"row":5},
                      "body": [
                        {"index":0,"location":{"row":5,"text":"${b64("10")}"},"terms":{"type":"boolean","value":true,"location":{"text":"${b64("10")}"}}}
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val rule = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules.first { it.name == "max_items" }
            assertThat(rule.constantValue?.rendered).isEqualTo("10")
            assertThat(rule.bodies).isEmpty()
        }

        @Test
        @DisplayName("guard: `x := 10 if true` written explicitly keeps the ordinary (unclassified) body rendering -- the body expr's own source text is \"true\", not \"10\"")
        fun explicitIfTrueIsNotTreatedAsAConstant() {
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "max_items", "value": {"type":"number","value":10,"location":{"text":"${b64("10")}"}}},
                      "location": {"row":5},
                      "body": [
                        {"index":0,"location":{"row":5,"text":"${b64("true")}"},"terms":{"type":"boolean","value":true,"location":{"text":"${b64("true")}"}}}
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val rule = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules.first { it.name == "max_items" }
            assertThat(rule.constantValue).isNull()
            assertThat(rule.bodies).hasSize(1)
            val fallback = rule.bodies.single().conditions.single() as Condition.Unrendered
            assertThat(fallback.reason).isEqualTo("unclassified")
        }

        @Test
        @DisplayName("guard: a rule with a real condition in its body keeps the ordinary rendering, never promoted")
        fun aRealConditionIsNeverTreatedAsAConstant() {
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "max_items", "value": {"type":"number","value":10,"location":{"text":"${b64("10")}"}}},
                      "location": {"row":5},
                      "body": [
                        {"index":0,"location":{"row":5,"text":"${b64("input.y")}"},"terms":{"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"y"}]}}
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val rule = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules.first { it.name == "max_items" }
            assertThat(rule.constantValue).isNull()
            assertThat(rule.bodies).hasSize(1)
        }

        @Test
        @DisplayName("guard: a PARTIAL rule (head.key != null) is never treated as a constant, even with the exact same synthetic body shape")
        fun aPartialRuleIsNeverTreatedAsAConstant() {
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "x", "key": {"type":"var","value":"k"}, "value": {"type":"number","value":10,"location":{"text":"${b64("10")}"}}, "ref": [{"type":"var","value":"x"}]},
                      "location": {"row":5},
                      "body": [
                        {"index":0,"location":{"row":5,"text":"${b64("10")}"},"terms":{"type":"boolean","value":true,"location":{"text":"${b64("10")}"}}}
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val rule = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules.first { it.name == "x" }
            assertThat(rule.constantValue).isNull()
        }

        @Test
        @DisplayName("guard: a name with MULTIPLE sibling bodies is never treated as a constant, even when one body looks like the synthetic shape")
        fun aNameWithSeveralBodiesIsNeverTreatedAsAConstant() {
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "x", "value": {"type":"number","value":10,"location":{"text":"${b64("10")}"}}},
                      "location": {"row":5},
                      "body": [
                        {"index":0,"location":{"row":5,"text":"${b64("10")}"},"terms":{"type":"boolean","value":true,"location":{"text":"${b64("10")}"}}}
                      ]
                    },
                    {
                      "head": {"name": "x", "value": {"type":"number","value":20,"location":{"text":"${b64("20")}"}}},
                      "location": {"row":6},
                      "body": [
                        {"index":0,"location":{"row":6,"text":"${b64("input.y")}"},"terms":{"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"y"}]}}
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val rule = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules.first { it.name == "x" }
            assertThat(rule.constantValue).isNull()
            assertThat(rule.bodies).hasSize(2)
        }
    }

    @Nested
    inner class ConditionalYieldedValuesFollowUp {

        @Test
        fun anExplicitStringValueOnABodyWithRealConditionsPromotesToYieldsValue() {
            // `tier := "high" if input.risk > 80`.
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "tier", "value": {"type":"string","value":"high","location":{"text":"${b64("\"high\"")}"}}},
                      "body": [
                        {
                          "index": 0,
                          "location": {"row":1,"text":"${b64("input.risk > 80")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"gt"}]},
                            {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"risk"}]},
                            {"type":"number","value":80}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val body = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules.first { it.name == "tier" }.bodies.single()
            assertThat(body.conditions).hasSize(1)
            assertThat((body.yieldsValue as Operand.Literal).rendered).isEqualTo("\"high\"")
        }

        @Test
        @DisplayName("guard: an implicit boolean true (no location on head.value) never gets a yielded value")
        fun anImplicitBooleanTrueNeverYieldsAValue() {
            // `flag if input.enabled` -- opa synthesizes head.value with NO location at all.
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "flag", "value": {"type":"boolean","value":true}},
                      "body": [
                        {"index":0,"location":{"row":1,"text":"${b64("input.enabled")}"},"terms":{"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"enabled"}]}}
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val body = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules.first { it.name == "flag" }.bodies.single()
            assertThat(body.yieldsValue).isNull()
        }

        @Test
        @DisplayName("an EXPLICIT boolean true (head.value has a real location) is distinguished from the implicit case and DOES yield a value")
        fun anExplicitBooleanTrueDoesYieldAValue() {
            // `explicit := true if input.on`.
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "explicit", "value": {"type":"boolean","value":true,"location":{"text":"${b64("true")}"}}},
                      "body": [
                        {"index":0,"location":{"row":1,"text":"${b64("input.on")}"},"terms":{"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"on"}]}}
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val body = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules.first { it.name == "explicit" }.bodies.single()
            assertThat((body.yieldsValue as Operand.Literal).rendered).isEqualTo("true")
        }

        @Test
        @DisplayName("guard: a PARTIAL rule (head.key != null) never yields a value, even with an explicit head.value")
        fun aPartialRuleNeverYieldsAValueEvenWithAnExplicitHeadValue() {
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "x", "key": {"type":"var","value":"k"}, "value": {"type":"string","value":"v","location":{"text":"${b64("\"v\"")}"}}, "ref": [{"type":"var","value":"x"}]},
                      "body": [
                        {"index":0,"location":{"row":1,"text":"${b64("input.y")}"},"terms":{"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"y"}]}}
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val body = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules.first { it.name == "x" }.bodies.single()
            assertThat(body.yieldsValue).isNull()
        }

        @Test
        @DisplayName("a local variable assigned then used as the head value substitutes inline, computed AFTER the body's own assignments are mapped")
        fun aLocalVariableAssignedThenUsedAsTheHeadValueSubstitutesInline() {
            // `limit := n if { n := input.limit }`.
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "limit", "value": {"type":"var","value":"n","location":{"text":"${b64("n")}"}}},
                      "body": [
                        {
                          "index": 0,
                          "location": {"row":1,"text":"${b64("n := input.limit")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"assign"}]},
                            {"type":"var","value":"n"},
                            {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"limit"}]}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val body = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules.first { it.name == "limit" }.bodies.single()
            assertThat(body.conditions).isEmpty()
            assertThat((body.yieldsValue as Operand.Path).segments).containsExactly(PathSegment.Field("input"), PathSegment.Field("limit"))
        }

        @Test
        @DisplayName("an unrenderable head value (e.g. a bare sprintf call) falls back to verbatim Operand.Unrendered, never a guess")
        fun anUnrenderableHeadValueFallsBackToVerbatimUnrendered() {
            // `label := sprintf("%v-x", [input.name]) if input.named`.
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "label", "value": {"type":"call","location":{"text":"${b64("sprintf(\"%v-x\", [input.name])")}"},"value":[
                        {"type":"ref","value":[{"type":"var","value":"sprintf"}]},
                        {"type":"string","value":"%v-x"},
                        {"type":"array","value":[{"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"name"}]}]}
                      ]}},
                      "body": [
                        {"index":0,"location":{"row":1,"text":"${b64("input.named")}"},"terms":{"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"named"}]}}
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val body = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules.first { it.name == "label" }.bodies.single()
            val fallback = body.yieldsValue as Operand.Unrendered
            assertThat(fallback.sourceText).isEqualTo("sprintf(\"%v-x\", [input.name])")
        }

        @Test
        @DisplayName("a bare reference to another rule as the head value promotes to a RuleValueReference, same as any other operand position")
        fun aBareReferenceToAnotherRuleAsTheHeadValuePromotes() {
            // `tier := threshold if input.x` -- combines this follow-up with review item 10's own promotion.
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "threshold", "value": {"type":"number","value":5}},
                      "body": [
                        {"index":0,"location":{"row":1,"text":"${b64("input.enabled")}"},"terms":{"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"enabled"}]}}
                      ]
                    },
                    {
                      "head": {"name": "tier", "value": {"type":"var","value":"threshold","location":{"text":"${b64("threshold")}"}}},
                      "body": [
                        {"index":0,"location":{"row":2,"text":"${b64("input.x")}"},"terms":{"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"x"}]}}
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val body = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules.first { it.name == "tier" }.bodies.single()
            val reference = body.yieldsValue as Operand.RuleValueReference
            assertThat(reference.packagePath).isEqualTo("scratch")
            assertThat(reference.ruleName).isEqualTo("threshold")
        }
    }

    @Nested
    inner class ExpressionAssignmentSubstitution {

        private fun ref(vararg parts: String) =
            """{"type":"ref","value":[${parts.joinToString(",") { """{"type":"${if (it == parts.first() && (it == "input" || it == "data")) "var" else "string"}","value":"$it"}""" }}]}"""
        private fun op(name: String) = """{"type":"ref","value":[{"type":"var","value":"$name"}]}"""
        private fun call(name: String, vararg args: String) = """{"type":"call","value":[${op(name)},${args.joinToString(",")}]}"""
        private fun v(name: String) = """{"type":"var","value":"$name","location":{"text":"${b64(name)}"}}"""
        private fun assign(target: String, rhs: String) = """{"index":0,"location":{"row":1,"text":"${b64("$target := ...")}"},"terms":[${op("assign")},{"type":"var","value":"$target"},$rhs]}"""
        private fun compare(opName: String, left: String, right: String) = """{"index":1,"location":{"row":2,"text":"${b64("cmp")}"},"terms":[${op(opName)},$left,$right]}"""
        private val input = { field: String -> ref("input", field) }

        private fun rules(vararg bodies: Pair<String, List<String>>): List<io.explico.model.RuleGroup> {
            val json = """{"package":{"path":[{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},"rules":[${
                bodies.joinToString(",") { (name, exprs) -> """{"head":{"name":"$name","ref":[${op(name)}]},"body":[${exprs.joinToString(",")}]}""" }
            }]}"""
            return AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", moduleOf(json)))).packages[0].rules
        }

        private fun conditionsOf(vararg exprs: String) = rules("r" to exprs.toList()).single().bodies.single().conditions

        @Test
        fun aFullyRenderedExpressionIsSubstitutedAndTheAssignmentDisappears() {
            val conditions = conditionsOf(
                assign("x", call("plus", call("count", input("y")), """{"type":"number","value":1}""")),
                compare("gt", v("x"), """{"type":"number","value":5}"""),
            )
            val comparison = conditions.single() as Condition.Comparison
            val plus = comparison.left as Operand.BuiltinCall
            assertThat(plus.name).isEqualTo("plus")
            assertThat((plus.args[0] as Operand.BuiltinCall).name).isEqualTo("count")
        }

        @Test
        @DisplayName("guard: an unbound variable anywhere in the right-hand side keeps the helper-assignment fallback")
        fun anUnboundVariableInTheExpressionStaysAFallback() {
            val conditions = conditionsOf(
                assign("x", call("plus", v("nowhere"), """{"type":"number","value":1}""")),
                compare("gt", v("x"), """{"type":"number","value":5}"""),
            )
            assertThat((conditions[0] as Condition.Unrendered).reason).isEqualTo("helper-assignment")
            assertThat((conditions[1] as Condition.Comparison).left).isEqualTo(Operand.Variable("x"))
        }

        @Test
        @DisplayName("guard: an unmappable argument NESTED inside count(...) disqualifies the whole expression")
        fun aNestedUnmappableArgumentStaysAFallback() {
            val conditions = conditionsOf(
                assign("x", call("count", call("json.marshal", input("y")))),
                compare("gt", v("x"), """{"type":"number","value":0}"""),
            )
            assertThat((conditions[0] as Condition.Unrendered).reason).isEqualTo("helper-assignment")
        }

        @Test
        @DisplayName("guard: a self-referencing right-hand side (`x := x + 1`) sees x unbound and stays a fallback")
        fun selfReferenceStaysAFallback() {
            val conditions = conditionsOf(assign("x", call("plus", v("x"), """{"type":"number","value":1}""")))
            assertThat((conditions.single() as Condition.Unrendered).reason).isEqualTo("helper-assignment")
        }

        @Test
        @DisplayName("bindings are per body: the same variable name in two bodies substitutes each body's own expression")
        fun bindingsArePerBody() {
            val result = rules(
                "a" to listOf(assign("x", call("count", input("p"))), compare("gt", v("x"), """{"type":"number","value":0}""")),
                "b" to listOf(assign("x", call("count", input("q"))), compare("gt", v("x"), """{"type":"number","value":0}""")),
            )
            fun countedField(name: String): List<PathSegment> {
                val left = (result.first { it.name == name }.bodies.single().conditions.single() as Condition.Comparison).left as Operand.BuiltinCall
                return (left.args.single() as Operand.Path).segments
            }
            assertThat(countedField("a").last()).isEqualTo(PathSegment.Field("p"))
            assertThat(countedField("b").last()).isEqualTo(PathSegment.Field("q"))
        }

        @Test
        @DisplayName("transitive: `y := x` where x is itself an expression binding substitutes the same operand")
        fun transitiveThroughAnExpressionBinding() {
            val conditions = conditionsOf(
                assign("x", call("count", input("p"))),
                assign("y", v("x")),
                compare("gt", v("y"), """{"type":"number","value":0}"""),
            )
            assertThat(((conditions.single() as Condition.Comparison).left as Operand.BuiltinCall).name).isEqualTo("count")
        }

        @Test
        @DisplayName("an expression-bound variable used as a `some ... in` collection is NOT treated as a path -- the iteration falls back")
        fun expressionBindingAsSomeInCollectionFallsBack() {
            val someIn = """{"index":1,"location":{"row":2,"text":"${b64("some i in xs")}"},"terms":{"symbols":[{"type":"call","value":[{"type":"ref","value":[{"type":"var","value":"internal"},{"type":"string","value":"member_2"}]},{"type":"var","value":"i"},${v("xs")}]}]}}"""
            val conditions = conditionsOf(assign("xs", call("plus", input("a"), input("b"))), someIn)
            assertThat(conditions.single()).isInstanceOf(Condition.Unrendered::class.java)
        }

        @Test
        @DisplayName("guard: an expression-bound variable used as a ref-chain ROOT (`x.field`) keeps the conservative whole-operand fallback")
        fun expressionBindingAsRefRootFallsBack() {
            val rooted = """{"type":"ref","value":[{"type":"var","value":"x"},{"type":"string","value":"field"}],"location":{"text":"${b64("x.field")}"}}"""
            val conditions = conditionsOf(
                assign("x", call("plus", input("a"), input("b"))),
                compare("gt", rooted, """{"type":"number","value":0}"""),
            )
            assertThat((conditions.single() as Condition.Comparison).left).isInstanceOf(Operand.Unrendered::class.java)
        }
    }

    @Nested
    inner class OperandPositionFunctionCalls {

        private fun op(name: String) = """{"type":"ref","value":[{"type":"var","value":"$name"}]}"""
        private fun path(vararg parts: String) =
            """{"type":"ref","value":[{"type":"var","value":"input"},${parts.joinToString(",") { """{"type":"string","value":"$it"}""" }}]}"""
        private fun call(name: String, vararg args: String) = """{"type":"call","value":[${(listOf(op(name)) + args).joinToString(",")}]}"""
        private fun function(name: String, vararg params: String, body: String = """{"index":0,"location":{"row":1,"text":"${b64("input.ok")}"},"terms":${path("ok")}}""") =
            """{"head":{"name":"$name","args":[${params.joinToString(",") { """{"type":"var","value":"$it"}""" }}],"ref":[${op(name)}]},"body":[$body]}"""
        private fun compare(left: String, right: String) =
            """{"index":0,"location":{"row":2,"text":"${b64("cmp")}"},"terms":[${op("gt")},$left,$right]}"""
        private fun rule(vararg exprs: String) = """{"head":{"name":"r","ref":[${op("r")}]},"body":[${exprs.joinToString(",")}]}"""
        private val one = """{"type":"number","value":1}"""

        private fun conditions(vararg ruleJsons: String): List<Condition> {
            val json = """{"package":{"path":[{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},"rules":[${ruleJsons.joinToString(",")}]}"""
            return AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", moduleOf(json)))).packages[0].rules.first { it.name == "r" }.bodies.single().conditions
        }

        private fun left(vararg ruleJsons: String) = (conditions(*ruleJsons).single() as Condition.Comparison).left

        @Test
        fun aKnownSamePackageFunctionCallBecomesAnOperandFunctionCall() {
            val operand = left(function("f", "x"), rule(compare(call("f", path("a")), one))) as Operand.FunctionCall
            assertThat(operand.packagePath).isEqualTo("scratch")
            assertThat(operand.functionName).isEqualTo("f")
            assertThat((operand.args.single() as Operand.Path).segments.last()).isEqualTo(PathSegment.Field("a"))
        }

        @Test
        @DisplayName("disclosed limitation: opa emits NO head.args for a zero-parameter `f() := 1` (confirmed via a real `opa parse` run), so the registry cannot see it and the call stays Unrendered, same as in condition position")
        fun zeroParameterFunctionIsInvisibleToTheRegistryAndStaysUnrendered() {
            val noArgsHead = """{"head":{"name":"f","value":{"type":"number","value":1},"ref":[${op("f")}]},"body":[{"index":0,"location":{"row":1,"text":"${b64("input.ok")}"},"terms":${path("ok")}}]}"""
            assertThat(left(noArgsHead, rule(compare(call("f"), one)))).isInstanceOf(Operand.Unrendered::class.java)
        }

        @Test
        @DisplayName("guard: a call whose argument count does not match the declared parameters stays Unrendered")
        fun wrongArityStaysUnrendered() {
            assertThat(left(function("f", "x"), rule(compare(call("f", path("a"), path("b")), one)))).isInstanceOf(Operand.Unrendered::class.java)
        }

        @Test
        @DisplayName("guard: a cross-package (dotted) call stays Unrendered")
        fun crossPackageCallStaysUnrendered() {
            val dotted = """{"type":"call","value":[{"type":"ref","value":[{"type":"var","value":"other"},{"type":"string","value":"f"}]},${path("a")}]}"""
            assertThat(left(function("f", "x"), rule(compare(dotted, one)))).isInstanceOf(Operand.Unrendered::class.java)
        }

        @Test
        @DisplayName("guard: an undefined function stays Unrendered")
        fun undefinedFunctionStaysUnrendered() {
            assertThat(left(rule(compare(call("nope", path("a")), one)))).isInstanceOf(Operand.Unrendered::class.java)
        }

        @Test
        @DisplayName("an unbound-variable argument stays wrapped inside the call, so the coverage footer still counts it (mirrors BuiltinCall)")
        fun unboundArgumentIsWrappedAndCounted() {
            val all = conditions(function("f", "x"), rule(compare(call("f", """{"type":"var","value":"nowhere","location":{"text":"${b64("nowhere")}"}}"""), one)))
            val operand = (all.single() as Condition.Comparison).left as Operand.FunctionCall
            assertThat(operand.args.single()).isInstanceOf(Operand.Unrendered::class.java)
            assertThat(io.explico.render.Coverage.unrenderedOperandCount(all)).isEqualTo(1)
        }

        @Test
        @DisplayName("a comprehension argument makes the whole condition fall back, never a partial call")
        fun comprehensionArgumentFallsBackTheWholeCondition() {
            val comprehension = """{"type":"setcomprehension","value":{"term":{"type":"var","value":"e"},"body":[]}}"""
            val only = conditions(function("f", "x"), rule(compare(call("f", comprehension), one))).single()
            assertThat(only).isInstanceOf(Condition.Unrendered::class.java)
        }

        @Test
        @DisplayName("a call nested in another call's arguments, and a call inside arithmetic, both map")
        fun nestedAndArithmeticCalls() {
            val nested = left(function("f", "x"), function("g", "y"), rule(compare(call("f", call("g", path("a"))), one))) as Operand.FunctionCall
            assertThat((nested.args.single() as Operand.FunctionCall).functionName).isEqualTo("g")
            val arithmetic = left(function("f", "x"), rule(compare(call("plus", call("f", path("a")), one), one))) as Operand.BuiltinCall
            assertThat(arithmetic.args[0]).isInstanceOf(Operand.FunctionCall::class.java)
        }

        @Test
        @DisplayName("a function calling itself recursively maps without looping (mapping is purely syntactic)")
        fun recursiveFunctionDoesNotLoop() {
            val selfCall = function("f", "x", body = compare(call("f", """{"type":"var","value":"x"}"""), one))
            val rules = AstMapper.mapPolicySet(
                listOf(ParsedFile("scratch.rego", moduleOf("""{"package":{"path":[{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},"rules":[$selfCall]}"""))),
            ).packages[0].rules
            val operand = (rules.single().bodies.single().conditions.single() as Condition.Comparison).left as Operand.FunctionCall
            assertThat(operand.args.single()).isEqualTo(Operand.Parameter("x"))
        }

        @Test
        @DisplayName("an assignment from a function call is substituted at its use (it is a fully rendered expression)")
        fun assignmentFromAFunctionCallSubstitutes() {
            val assign = """{"index":0,"location":{"row":1,"text":"${b64("s := f(input.a)")}"},"terms":[${op("assign")},{"type":"var","value":"s"},${call("f", path("a"))}]}"""
            val used = compare("""{"type":"var","value":"s","location":{"text":"${b64("s")}"}}""", one).replace(""""index":0""", """"index":1""")
            val all = conditions(function("f", "x"), rule(assign, used))
            assertThat((all.single() as Condition.Comparison).left).isInstanceOf(Operand.FunctionCall::class.java)
        }
    }

    @Nested
    inner class ParameterRootedOperandPaths {
        // Spec §14.26: coverage audit probe 28's remaining half -- a ref chain rooted at a
        // function parameter, e.g. `finding.severity` inside `risk_score(finding) := ...`.

        private fun functionBody(name: String, param: String, vararg exprs: String): String =
            """{"head":{"name":"$name","args":[{"type":"var","value":"$param"}],"ref":[{"type":"var","value":"$name"}]},"body":[${exprs.joinToString(",")}]}"""

        private fun comparison(sourceText: String, left: String, right: String, index: Int = 0): String =
            """{"index":$index,"location":{"text":"${b64(sourceText)}"},"terms":[{"type":"ref","value":[{"type":"var","value":"equal"}]},$left,$right]}"""

        private fun refChain(vararg parts: Pair<String, String>): String {
            // Each part is ("var"|"string", value) -- the root, then each dotted/bracket segment.
            val terms = parts.joinToString(",") { (type, value) -> """{"type":"$type","value":"$value"}""" }
            return """{"type":"ref","value":[$terms]}"""
        }

        private fun ruleOf(funcJson: String): RuleGroup {
            val module = moduleOf(
                """{"package":{"path":[{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},"rules":[$funcJson]}""",
            )
            return AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules.single()
        }

        @Test
        @DisplayName("a plain field tail on a parameter promotes to Operand.ParameterPath")
        fun plainFieldTailPromotes() {
            val rule = ruleOf(
                functionBody(
                    "risk_score", "finding",
                    comparison("finding.severity == \"critical\"", refChain("var" to "finding", "string" to "severity"), """{"type":"string","value":"critical"}"""),
                ),
            )
            val left = (rule.bodies.single().conditions.single() as Condition.Comparison).left as Operand.ParameterPath
            assertThat(left.name).isEqualTo("finding")
            assertThat(left.segments).containsExactly(PathSegment.Field("severity"))
        }

        @Test
        @DisplayName("a nested field tail (two segments) maps both")
        fun nestedFieldTailMapsBothSegments() {
            val rule = ruleOf(
                functionBody(
                    "risk_score", "finding",
                    comparison(
                        "finding.owner.team == \"platform\"",
                        refChain("var" to "finding", "string" to "owner", "string" to "team"),
                        """{"type":"string","value":"platform"}""",
                    ),
                ),
            )
            val left = (rule.bodies.single().conditions.single() as Condition.Comparison).left as Operand.ParameterPath
            assertThat(left.name).isEqualTo("finding")
            assertThat(left.segments).containsExactly(PathSegment.Field("owner"), PathSegment.Field("team"))
        }

        @Test
        @DisplayName("a key-literal tail (bracket string index) maps too")
        fun keyLiteralTailMaps() {
            val bracketChain = """{"type":"ref","value":[{"type":"var","value":"res"},{"type":"string","value":"labels"},{"type":"string","value":"env","location":{"text":"${b64("\"env\"")}"}}]}"""
            val rule = ruleOf(
                functionBody(
                    "has_label", "res",
                    comparison("""res.labels["env"] == "prod"""", bracketChain, """{"type":"string","value":"prod"}"""),
                ),
            )
            val left = (rule.bodies.single().conditions.single() as Condition.Comparison).left as Operand.ParameterPath
            assertThat(left.name).isEqualTo("res")
            assertThat(left.segments).containsExactly(PathSegment.Field("labels"), PathSegment.KeyLiteral("env"))
        }

        @Test
        @DisplayName("guard: an unmappable tail (unbound dynamic index) keeps the whole-operand raw fallback, not a partial ParameterPath")
        fun unmappableTailFallsBackWhole() {
            // `res.items[idx].ok` with `idx` unbound -- a bare path used as a boolean condition.
            val chain = """{"type":"ref","location":{"text":"${b64("res.items[idx].ok")}"},"value":[
                {"type":"var","value":"res"},
                {"type":"string","value":"items"},
                {"type":"var","value":"idx","location":{"text":"${b64("idx")}"}},
                {"type":"string","value":"ok"}
            ]}"""
            val module = moduleOf(
                """{"package":{"path":[{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},"rules":[
                    {"head":{"name":"bad","args":[{"type":"var","value":"res"}],"ref":[{"type":"var","value":"bad"}]},
                     "body":[{"index":0,"location":{"text":"${b64("res.items[idx].ok")}"},"terms":$chain}]}
                ]}""",
            )
            val rule = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules.single()
            val condition = rule.bodies.single().conditions.single() as Condition.Truthy
            val unrendered = condition.operand as Operand.Unrendered
            assertThat(unrendered.sourceText).isEqualTo("res.items[idx].ok")
        }

        @Test
        @DisplayName("guard: a bare parameter use with no chain (`x in {\"a\",\"b\"}`) stays Operand.Parameter, not a zero-segment ParameterPath")
        fun bareParameterUseIsUnaffected() {
            val module = moduleOf(
                """{"package":{"path":[{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},"rules":[
                    {"head":{"name":"in_set","args":[{"type":"var","value":"x"}],"ref":[{"type":"var","value":"in_set"}]},
                     "body":[{"index":0,"location":{"text":"${b64("x in {\"a\", \"b\"}")}"},"terms":[
                        {"type":"ref","value":[{"type":"var","value":"internal"},{"type":"string","value":"member_2"}]},
                        {"type":"var","value":"x"},
                        {"type":"set","value":[{"type":"string","value":"a"},{"type":"string","value":"b"}]}
                     ]}]}
                ]}""",
            )
            val rule = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules.single()
            val condition = rule.bodies.single().conditions.single() as Condition.Membership
            assertThat(condition.member).isEqualTo(Operand.Parameter("x"))
        }
    }

    @Nested
    inner class LiteralCollectionKind {
        // Spec §14 promotion: a small scalar array/set literal now records WHICH Rego syntax
        // produced it, so the renderer can add faithful delimiters outside the one frozen
        // bare-list position (a membership test's own collection side).

        private fun comparisonWithCollection(collectionTerm: String): Condition.Comparison {
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
                          "location": {"text":"${b64("cmp")}"},
                          "terms": [
                            {"type":"ref","value":[{"type":"var","value":"equal"}]},
                            {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"x"}]},
                            $collectionTerm
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val body = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules[0].bodies[0]
            return body.conditions.single() as Condition.Comparison
        }

        @Test
        fun anArrayLiteralIsTaggedArray() {
            val right = comparisonWithCollection("""{"type":"array","value":[{"type":"string","value":"a"},{"type":"string","value":"b"}]}""").right as Operand.Literal
            assertThat(right.rendered).isEqualTo("\"a\", \"b\"")
            assertThat(right.collection).isEqualTo(LiteralCollection.ARRAY)
        }

        @Test
        fun aSetLiteralIsTaggedSet() {
            val right = comparisonWithCollection("""{"type":"set","value":[{"type":"string","value":"a"},{"type":"string","value":"b"}]}""").right as Operand.Literal
            assertThat(right.collection).isEqualTo(LiteralCollection.SET)
        }

        @Test
        @DisplayName("guard: a scalar literal (not array/set) stays untagged -- collection is null")
        fun aScalarLiteralStaysUntagged() {
            val right = comparisonWithCollection("""{"type":"string","value":"a"}""").right as Operand.Literal
            assertThat(right.collection).isNull()
        }
    }
    @Nested
    inner class PerRuleFailureIsolation {
        // Real-world benchmark finding: one mapper exception on one rule used to abort the WHOLE
        // render. The trigger below is deliberately malformed AST (a `var` term whose value is an
        // array) that opa never emits -- a stable stand-in for "opa output the mapper does not
        // understand", which will not stop crashing when specific real-world sites are later fixed.

        private val brokenTerm = """{"type":"var","value":[]}"""

        private fun module(): OpaModule = moduleOf(
            """
            {
              "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
              "rules": [
                {
                  "head": {"name": "good", "ref": [{"type":"var","value":"good"}]},
                  "location": {"row": 3, "text": "${b64("good if input.ok")}"},
                  "body": [{"index":0,"location":{"row":3,"text":"${b64("input.ok")}"},"terms":{"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"ok"}]}}]
                },
                {
                  "head": {"name": "broken", "ref": [{"type":"var","value":"broken"}]},
                  "location": {"row": 7, "text": "${b64("broken if weird == 1")}"},
                  "body": [{"index":0,"location":{"row":7,"text":"${b64("weird == 1")}"},"terms":[{"type":"ref","value":[{"type":"var","value":"equal"}]},$brokenTerm,{"type":"number","value":1}]}]
                }
              ]
            }
            """.trimIndent()
        )

        private fun mapCapturingStderr(): Pair<PolicySet, String> {
            val original = System.err
            val buffer = java.io.ByteArrayOutputStream()
            System.setErr(java.io.PrintStream(buffer, true, Charsets.UTF_8))
            try {
                return AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module()))) to buffer.toString(Charsets.UTF_8)
            } finally {
                System.setErr(original)
            }
        }

        @Test
        @DisplayName("a rule whose mapping throws becomes a verbatim-source fallback body; the mapper does not abort")
        fun failingRuleFallsBackToItsVerbatimSource() {
            val (policySet, _) = mapCapturingStderr()
            val broken = policySet.packages.single().rules.single { it.name == "broken" }
            val fallback = broken.bodies.single().conditions.single() as Condition.Unrendered
            assertThat(fallback.reason).isEqualTo("mapping-error")
            assertThat(fallback.sourceText).isEqualTo("broken if weird == 1")
            assertThat(broken.bodies.single().sourceText).isEqualTo("broken if weird == 1")
            assertThat(broken.bodies.single().sourceLocation.row).isEqualTo(7)
        }

        @Test
        @DisplayName("a sibling rule in the same file is unaffected by the failing one")
        fun siblingRuleStillMapsNormally() {
            val (policySet, _) = mapCapturingStderr()
            val good = policySet.packages.single().rules.single { it.name == "good" }
            assertThat(good.bodies.single().conditions.single()).isInstanceOf(Condition.Truthy::class.java)
        }

        @Test
        @DisplayName("the failure is never swallowed silently: a stderr warning names the rule, file, row and exception")
        fun failureIsReportedOnStderr() {
            val (_, stderr) = mapCapturingStderr()
            assertThat(stderr).contains("warning").contains("broken").contains("scratch.rego").contains("7").contains("IllegalArgumentException")
            assertThat(stderr).doesNotContain("good")
            // The throwing mapper method is named, so a user's bug report points straight at the construct.
            assertThat(stderr).contains("in stringValueOf")
        }
    }

    @Nested
    inner class RefChainsWithCompositeTerms {
        // Real-world benchmark finding (Regal/Trivy/Gatekeeper corpora): a ref chain's HEAD is not
        // always a plain variable -- `util.any_set_item(x).lines[row]` heads on a call, `[a, b][i]`
        // on an array -- and a dotted path can carry a composite SEGMENT. Both used to throw
        // (stringValueOf on a non-primitive) and, before failure isolation, abort the whole render.
        // The honest answer is the same fallback every unrecognised operand gets: its verbatim source.

        private fun comparisonWith(refTerm: String): Operand {
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [
                    {
                      "head": {"name": "r", "ref": [{"type":"var","value":"r"}]},
                      "body": [{"index":0,"location":{"row":1,"text":"${b64("cmp")}"},"terms":[
                        {"type":"ref","value":[{"type":"var","value":"equal"}]},
                        $refTerm,
                        {"type":"number","value":1}
                      ]}]
                    }
                  ]
                }
                """.trimIndent()
            )
            val body = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules[0].bodies[0]
            return (body.conditions.single() as Condition.Comparison).left
        }

        @Test
        @DisplayName("a call-headed chain (`f(x).lines[0]`) falls back to its verbatim source, no exception")
        fun callHeadedChainFallsBack() {
            val chain = """{"type":"ref","location":{"text":"${b64("f(input.x).lines")}"},"value":[
                {"type":"call","value":[{"type":"ref","value":[{"type":"var","value":"f"}]},{"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"x"}]}]},
                {"type":"string","value":"lines"}
            ]}"""
            assertThat((comparisonWith(chain) as Operand.Unrendered).sourceText).isEqualTo("f(input.x).lines")
        }

        @Test
        @DisplayName("an array-headed chain (`[a, b][0]`) falls back to its verbatim source")
        fun arrayHeadedChainFallsBack() {
            val chain = """{"type":"ref","location":{"text":"${b64("[1, 2][0]")}"},"value":[
                {"type":"array","value":[{"type":"number","value":1},{"type":"number","value":2}]},
                {"type":"number","value":0}
            ]}"""
            assertThat((comparisonWith(chain) as Operand.Unrendered).sourceText).isEqualTo("[1, 2][0]")
        }

        @Test
        @DisplayName("CHALLENGE edge case: an UNBOUND variable head followed by a composite segment (`x[input.k]`) falls back instead of throwing")
        fun unboundHeadWithCompositeSegmentFallsBack() {
            val chain = """{"type":"ref","location":{"text":"${b64("x[input.k]")}"},"value":[
                {"type":"var","value":"x"},
                {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"k"}]}
            ]}"""
            assertThat((comparisonWith(chain) as Operand.Unrendered).sourceText).isEqualTo("x[input.k]")
        }
    }

    @Nested
    inner class RegistriesAndMetadataTolerateNonPlainTerms {
        // Real-world benchmark finding: two whole-load crashes OUTSIDE the per-rule isolation
        // boundary -- the function registry (and a function card's Parameters line) read every
        // declared parameter as a plain name, but Rego allows PATTERN parameters (`f([a, b])`); and
        // the METADATA index read every element of an annotation's path as a plain name, but a
        // ref-headed rule's path can carry other terms.

        private fun patternParameterFunction() = moduleOf(
            """
            {
              "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
              "rules": [
                {
                  "head": {"name": "pair_ok", "ref": [{"type":"var","value":"pair_ok"}],
                           "args": [{"type":"array","location":{"text":"${b64("[a, b]")}"},"value":[{"type":"var","value":"a"},{"type":"var","value":"b"}]}]},
                  "location": {"row": 3, "text": "${b64("pair_ok([a, b]) if a == b")}"},
                  "body": [{"index":0,"location":{"row":3,"text":"${b64("a == b")}"},"terms":[
                    {"type":"ref","value":[{"type":"var","value":"equal"}]},{"type":"var","value":"a","location":{"text":"${b64("a")}"}},{"type":"var","value":"b","location":{"text":"${b64("b")}"}}]}]
                },
                {
                  "head": {"name": "r", "ref": [{"type":"var","value":"r"}]},
                  "location": {"row": 6, "text": "${b64("r if pair_ok(input.p)")}"},
                  "body": [{"index":0,"location":{"row":6,"text":"${b64("pair_ok(input.p)")}"},"terms":[
                    {"type":"ref","value":[{"type":"var","value":"pair_ok"}]},{"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"p"}]}]}]
                }
              ]
            }
            """.trimIndent()
        )

        @Test
        @DisplayName("a function with a PATTERN parameter loads; its Parameters line shows the pattern's verbatim source")
        fun patternParameterIsShownVerbatim() {
            val rules = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", patternParameterFunction()))).packages[0].rules
            assertThat(rules.single { it.name == "pair_ok" }.parameters).containsExactly("[a, b]")
        }

        @Test
        @DisplayName("the pattern-parameter function is still a registered function: a one-argument call to it resolves")
        fun patternParameterFunctionStillResolvesCalls() {
            val rules = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", patternParameterFunction()))).packages[0].rules
            assertThat(rules.single { it.name == "r" }.bodies.single().conditions.single()).isInstanceOf(Condition.FunctionCall::class.java)
        }

        @Test
        @DisplayName("an annotation entry whose path carries a non-name element is skipped, not fatal")
        fun annotationPathWithNonNameElementIsSkipped() {
            val inspect = opaJson.decodeFromString(
                OpaInspectResult.serializer(),
                """
                {"annotations":[
                  {"annotations":{"title":"T","scope":"rule"},"location":{"file":"scratch.rego","row":3},
                   "path":[{"type":"var","value":"data"},{"type":"string","value":"scratch"},{"type":"call","value":[]}]}
                ]}
                """.trimIndent(),
            )
            val policySet = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", patternParameterFunction())), inspect)
            assertThat(policySet.packages[0].rules.map { it.name }).contains("pair_ok", "r")
            assertThat(policySet.packages[0].rules.all { it.metadata == null }).isTrue()
        }
    }

    @Nested
    inner class SprintfFormatVerbAlignment {
        // Real-world benchmark finding (Gatekeeper corpus): a sprintf format with MORE placeholders than
        // arguments crashed the mapper (index out of bounds). Fixing it exposed a quieter faithfulness
        // bug in the same loop: only `%v`/`%s` consumed an argument, so any other verb (`%d`, `%q`)
        // was copied as text WITHOUT consuming one, and every later `%v` took the WRONG argument --
        // the card showed "%d of [total]" for a message that really reads "5 of x". The rule now:
        // produce a display message only when every verb is `%v`/`%s`/`%%` and verbs and arguments
        // line up exactly; otherwise producesValue is null (no Produces line), never a misaligned one.

        private fun producesValueOf(format: String, vararg argPaths: String): String? {
            val args = argPaths.joinToString(",") { """{"type":"ref","location":{"text":"${b64("input.$it")}"},"value":[{"type":"var","value":"input"},{"type":"string","value":"$it"}]}""" }
            val module = moduleOf(
                """
                {
                  "package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"scratch"}]},
                  "rules": [{
                    "head": {"name": "deny", "key": {"type":"var","value":"msg"}, "ref": [{"type":"var","value":"deny"}]},
                    "body": [{"index":0,"location":{"row":1,"text":"${b64("msg := sprintf(...)")}"},"terms":[
                      {"type":"ref","value":[{"type":"var","value":"assign"}]},
                      {"type":"var","value":"msg"},
                      {"type":"call","value":[
                        {"type":"ref","value":[{"type":"var","value":"sprintf"}]},
                        {"type":"string","value":${opaJson.encodeToString(kotlinx.serialization.serializer<String>(), format)}},
                        {"type":"array","value":[$args]}
                      ]}
                    ]}]
                  }]
                }
                """.trimIndent()
            )
            val body = AstMapper.mapPolicySet(listOf(ParsedFile("scratch.rego", module))).packages[0].rules[0].bodies[0]
            // A null message must come from the format logic, never from failure isolation swallowing a crash.
            assertThat(body.conditions.filterIsInstance<Condition.Unrendered>().map { it.reason }).doesNotContain("mapping-error")
            return body.producesValue
        }

        @Test
        fun aMatchingFormatStillProducesTheDisplayMessage() {
            assertThat(producesValueOf("%v of %s", "total", "name")).isEqualTo("[total] of [name]")
        }

        @Test
        @DisplayName("a literal %% is one percent sign and consumes no argument")
        fun doublePercentIsALiteralPercent() {
            assertThat(producesValueOf("%v%% done", "pct")).isEqualTo("[pct]% done")
        }

        @Test
        @DisplayName("more placeholders than arguments no longer crashes: no display message")
        fun tooFewArgumentsYieldsNoDisplayMessage() {
            assertThat(producesValueOf("%v and %v", "only")).isNull()
        }

        @Test
        @DisplayName("more arguments than placeholders: no display message (Go would print %!(EXTRA ...))")
        fun tooManyArgumentsYieldsNoDisplayMessage() {
            assertThat(producesValueOf("just %v", "a", "b")).isNull()
        }

        @Test
        @DisplayName("a verb other than %v/%s (here %d) yields no display message instead of misaligning later arguments")
        fun otherVerbYieldsNoDisplayMessage() {
            assertThat(producesValueOf("%d of %v", "total", "name")).isNull()
        }

        @Test
        @DisplayName("CHALLENGE edge case: a trailing lone % yields no display message")
        fun trailingPercentYieldsNoDisplayMessage() {
            assertThat(producesValueOf("100%", "x")).isNull()
        }
    }

}
