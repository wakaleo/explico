/**
 * MarkdownRenderer assembles control cards and pages from the already-tested pieces
 * (ExpressionRenderer, PathHumanizer, Coverage) per spec §6.1/§6.2/§6.5/§6.6. Built
 * directly from hand-constructed domain-model objects -- no opa, no AstMapper --
 * since card assembly is a pure function of the domain model.
 */
package io.explico.render

import io.explico.model.Condition
import io.explico.model.DefaultValue
import io.explico.model.Operand
import io.explico.model.Operator
import io.explico.model.PathSegment
import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RuleBody
import io.explico.model.RuleGroup
import io.explico.model.RuleMetadata
import io.explico.model.SourceRef
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class MarkdownRendererTest {

    private val envIsProd = Condition.Comparison(
        Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("deployment"), PathSegment.Field("environment"))),
        Operator.EQ,
        Operand.Literal("\"production\""),
    )
    private val approvedAbsent = Condition.Truthy(
        Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("change"), PathSegment.Field("ticket"), PathSegment.Field("approved"))),
        negated = true,
    )

    private fun body(conditions: List<Condition>, producesValue: String? = null, messageTemplate: String? = null, row: Int = 16, file: String = "approvals/change_approval.rego", yieldsValue: Operand? = null) =
        RuleBody(conditions, producesValue, messageTemplate, SourceRef(file, row), "", yieldsValue)

    @Nested
    inner class ControlCard {

        @Test
        fun titleUsesControlIdWhenMetadataPresent() {
            val rule = RuleGroup(
                name = "deny",
                metadata = RuleMetadata("Production change approval", "desc", "REL-001", listOf("SOC 2 CC8.1", "ISO 27001 A.8.32")),
                default = null,
                bodies = listOf(body(listOf(envIsProd, approvedAbsent), "release [deployment id] has no approved change ticket")),
            )
            val pkg = PolicyPackage("release.approvals", listOf(rule), listOf("approvals/change_approval.rego"))
            val markdown = MarkdownRenderer.renderCard(rule, pkg, PolicySet(listOf(pkg)))

            assertThat(markdown).contains("## REL-001 — Production change approval")
            assertThat(markdown).contains("*Frameworks: SOC 2 CC8.1, ISO 27001 A.8.32*")
        }

        @Test
        fun titleFallsBackToPackageDotRuleWhenNoControlId() {
            val rule = RuleGroup("exempt_service", metadata = null, default = null, bodies = listOf(body(listOf(envIsProd))))
            val pkg = PolicyPackage("release.exemptions", listOf(rule), listOf("exemptions/exemptions.rego"))
            val markdown = MarkdownRenderer.renderCard(rule, pkg, PolicySet(listOf(pkg)))

            assertThat(markdown).contains("## release.exemptions.exempt_service")
        }

        @Test
        fun titleFallsBackToRuleNameWhenMetadataHasNoTitle() {
            val rule = RuleGroup("deny", metadata = RuleMetadata(null, null, "REL-099", emptyList()), default = null, bodies = listOf(body(listOf(envIsProd))))
            val pkg = PolicyPackage("release.approvals", listOf(rule), listOf("approvals/change_approval.rego"))
            val markdown = MarkdownRenderer.renderCard(rule, pkg, PolicySet(listOf(pkg)))

            assertThat(markdown).contains("## REL-099 — deny")
        }

        @Test
        fun ruleAndPackageLineUsesBasenameOfSourceFileNotFullPath() {
            val rule = RuleGroup("deny", metadata = null, default = null, bodies = listOf(body(listOf(envIsProd), file = "approvals/change_approval.rego")))
            val pkg = PolicyPackage("release.approvals", listOf(rule), listOf("approvals/change_approval.rego"))
            val markdown = MarkdownRenderer.renderCard(rule, pkg, PolicySet(listOf(pkg)))

            assertThat(markdown).contains("*Rule `deny` in package `release.approvals` — defined in `change_approval.rego`*")
        }

        @Test
        fun noFrameworksLineWhenFrameworksListIsEmpty() {
            val rule = RuleGroup("deny", metadata = RuleMetadata("Title", "desc", "REL-001", emptyList()), default = null, bodies = listOf(body(listOf(envIsProd))))
            val pkg = PolicyPackage("release.approvals", listOf(rule), listOf("approvals/change_approval.rego"))
            val markdown = MarkdownRenderer.renderCard(rule, pkg, PolicySet(listOf(pkg)))

            assertThat(markdown).doesNotContain("Frameworks")
        }

        @Test
        fun descriptionVerbatimWhenPresent() {
            val rule = RuleGroup("deny", metadata = RuleMetadata("Title", "Production deployments must reference an approved change ticket.", "REL-001", emptyList()), default = null, bodies = listOf(body(listOf(envIsProd))))
            val pkg = PolicyPackage("release.approvals", listOf(rule), listOf("approvals/change_approval.rego"))
            val markdown = MarkdownRenderer.renderCard(rule, pkg, PolicySet(listOf(pkg)))

            assertThat(markdown).contains("Production deployments must reference an approved change ticket.")
        }

        @Test
        fun descriptionTrailingNewlineFromAYamlBlockLiteralDoesNotProduceAnExtraBlankLine() {
            // "description: |" YAML block literals preserve their own trailing "\n" -- Tier-2
            // reconciliation (session 5): trimEnd it so it doesn't double up with appendLine's own.
            val rule = RuleGroup("deny", metadata = RuleMetadata("Title", "Line one.\nLine two.\n", "REL-001", emptyList()), default = null, bodies = listOf(body(listOf(envIsProd))))
            val pkg = PolicyPackage("release.approvals", listOf(rule), listOf("approvals/change_approval.rego"))
            val markdown = MarkdownRenderer.renderCard(rule, pkg, PolicySet(listOf(pkg)))

            assertThat(markdown).doesNotContain("Line two.\n\n\n")
            assertThat(markdown).contains("Line two.\n\n**All of the following are true:**")
        }

        @Test
        fun visibleGapMarkerWhenDescriptionMissing() {
            val rule = RuleGroup("exempt_service", metadata = null, default = null, bodies = listOf(body(listOf(envIsProd))))
            val pkg = PolicyPackage("release.exemptions", listOf(rule), listOf("exemptions/exemptions.rego"))
            val markdown = MarkdownRenderer.renderCard(rule, pkg, PolicySet(listOf(pkg)))

            assertThat(markdown).contains("*No description provided in policy metadata.*")
        }

        @Test
        fun defaultOutcomeLineOnlyWhenDefaultPresent() {
            val withDefault = RuleGroup("allow", metadata = null, default = DefaultValue("false", SourceRef("f.rego", 1)), bodies = listOf(body(listOf(envIsProd))))
            val withoutDefault = RuleGroup("deny", metadata = null, default = null, bodies = listOf(body(listOf(envIsProd))))
            val pkg = PolicyPackage("release.approvals", listOf(withDefault, withoutDefault), listOf("approvals/change_approval.rego"))

            assertThat(MarkdownRenderer.renderCard(withDefault, pkg, PolicySet(listOf(pkg)))).contains("**Default outcome:** false")
            assertThat(MarkdownRenderer.renderCard(withoutDefault, pkg, PolicySet(listOf(pkg)))).doesNotContain("Default outcome")
        }

        @Test
        fun aRuleThatIsOnlyEverADefaultDeclarationRendersTheDefaultLineAndNoSituationOrAllOfHeading() {
            // CHALLENGE edge case (review item 3): a rule name with NO other body in the file at
            // all -- RuleGroup.bodies is empty. Must not crash (rule.bodies.single()/.first() on an
            // empty list) and must not claim a Situation/condition section that doesn't exist.
            val rule = RuleGroup("risk_score", metadata = null, default = DefaultValue("0", SourceRef("f.rego", 1)), bodies = emptyList())
            val pkg = PolicyPackage("scratch", listOf(rule), listOf("scratch.rego"))

            val markdown = MarkdownRenderer.renderCard(rule, pkg, PolicySet(listOf(pkg)))

            assertThat(markdown).contains("**Default outcome:** 0")
            assertThat(markdown).doesNotContain("Situation")
            assertThat(markdown).doesNotContain("All of the following are true")
            assertThat(markdown).contains("*Rendering coverage: 0 of 0 conditions")
            // No sibling at all -- plain id-less heading, unaffected by the split-default entry promotion.
            assertThat(markdown).contains("## scratch.risk_score")
        }

        @Test
        fun aSplitDefaultEntryGetsItsOwnHeadingAndARealSourceFileNotAQuestionMark() {
            // Review item 14's follow-up: isSplitDefaultEntry picks a distinct heading, and
            // DefaultValue's own sourceLocation (not "?") sources the "defined in" line.
            val rule = RuleGroup(
                "allow", metadata = null, default = DefaultValue("false", SourceRef("authz.rego", 5)),
                bodies = emptyList(), isSplitDefaultEntry = true,
            )
            val pkg = PolicyPackage("authz", listOf(rule), listOf("authz.rego"))
            val markdown = MarkdownRenderer.renderCard(rule, pkg, PolicySet(listOf(pkg)))

            assertThat(markdown).contains("## Default for authz.allow")
            assertThat(markdown).contains("defined in `authz.rego`")
            assertThat(markdown).doesNotContain("defined in `?`")
            assertThat(markdown).contains("**Default outcome:** false")
        }

        @Test
        fun singleBodyOmitsAnyPreambleAndSituationHeading() {
            val rule = RuleGroup("exempt_service", metadata = null, default = null, bodies = listOf(body(listOf(envIsProd))))
            val pkg = PolicyPackage("release.exemptions", listOf(rule), listOf("exemptions/exemptions.rego"))
            val markdown = MarkdownRenderer.renderCard(rule, pkg, PolicySet(listOf(pkg)))

            assertThat(markdown).doesNotContain("ANY")
            assertThat(markdown).doesNotContain("### Situation")
            assertThat(markdown).contains("**All of the following are true:**")
        }

        @Test
        fun multipleBodiesGetAnyPreambleAndNumberedSituations() {
            val rule = RuleGroup(
                "deny",
                metadata = null,
                default = null,
                bodies = listOf(
                    body(listOf(envIsProd), "release [deployment id] has no approved change ticket", row = 16),
                    body(listOf(approvedAbsent), "change [change id] was approved by its author", row = 22),
                ),
            )
            val pkg = PolicyPackage("release.approvals", listOf(rule), listOf("approvals/change_approval.rego"))
            val markdown = MarkdownRenderer.renderCard(rule, pkg, PolicySet(listOf(pkg)))

            assertThat(markdown).contains("**The rule matches when ANY of the following situations applies:**")
            assertThat(markdown).contains("### Situation 1 — all of the following are true")
            assertThat(markdown).contains("### Situation 2 — all of the following are true")
        }

        @Test
        fun conditionsRenderAsBulletsViaExpressionRenderer() {
            val rule = RuleGroup("deny", metadata = null, default = null, bodies = listOf(body(listOf(envIsProd, approvedAbsent))))
            val pkg = PolicyPackage("release.approvals", listOf(rule), listOf("approvals/change_approval.rego"))
            val markdown = MarkdownRenderer.renderCard(rule, pkg, PolicySet(listOf(pkg)))

            assertThat(markdown).contains("- `deployment ▸ environment` is `\"production\"`")
            assertThat(markdown).contains("- `change ▸ ticket ▸ approved` is absent or false")
        }

        @Test
        fun producesLineOnlyWhenProducesValuePresent() {
            val withMessage = RuleGroup("deny", metadata = null, default = null, bodies = listOf(body(listOf(envIsProd), "release [deployment id] has no approved change ticket")))
            val withoutMessage = RuleGroup("exempt_service", metadata = null, default = null, bodies = listOf(body(listOf(envIsProd), null)))
            val pkg = PolicyPackage("release.approvals", listOf(withMessage, withoutMessage), listOf("approvals/change_approval.rego"))

            assertThat(MarkdownRenderer.renderCard(withMessage, pkg, PolicySet(listOf(pkg))))
                .contains("*Produces:* \"release [deployment id] has no approved change ticket\"")
            assertThat(MarkdownRenderer.renderCard(withoutMessage, pkg, PolicySet(listOf(pkg)))).doesNotContain("*Produces:*")
        }

        @Test
        fun unrenderedConditionRendersAsWarningBlockWithVerbatimMultilineSource() {
            val everySource = "every check in input.pipeline.checks {\n\t\tcheck.status == \"passed\"\n\t}"
            val rule = RuleGroup("all_checks_passed", metadata = null, default = null, bodies = listOf(body(listOf(Condition.Unrendered(everySource, "every")))))
            val pkg = PolicyPackage("release.governance", listOf(rule), listOf("governance/release_governance.rego"))
            val markdown = MarkdownRenderer.renderCard(rule, pkg, PolicySet(listOf(pkg)))

            assertThat(markdown).contains("⚠ **not rendered — shown as source:**")
            assertThat(markdown).contains("```rego")
            assertThat(markdown).contains("every check in input.pipeline.checks {")
            assertThat(markdown).contains("check.status == \"passed\"")
            assertThat(markdown).contains("```")
        }

        @Test
        fun coverageFooterReportsRenderedOfTotalWithoutPercent() {
            val rule = RuleGroup("deny", metadata = null, default = null, bodies = listOf(body(listOf(envIsProd, approvedAbsent))))
            val pkg = PolicyPackage("release.approvals", listOf(rule), listOf("approvals/change_approval.rego"))
            val markdown = MarkdownRenderer.renderCard(rule, pkg, PolicySet(listOf(pkg)))

            assertThat(markdown).contains("*Rendering coverage: 2 of 2 conditions*")
            assertThat(markdown).doesNotContain("%")
        }

        @Test
        fun coverageFooterReportsLessThanFullAndListsFallbackCountWhenAConditionIsUnrendered() {
            val rule = RuleGroup(
                "deny", metadata = null, default = null,
                bodies = listOf(body(listOf(envIsProd, Condition.Unrendered("count({a | ...})", "comprehension")))),
            )
            val pkg = PolicyPackage("release.governance", listOf(rule), listOf("governance/release_governance.rego"))
            val markdown = MarkdownRenderer.renderCard(rule, pkg, PolicySet(listOf(pkg)))

            assertThat(markdown).contains("*Rendering coverage: 1 of 2 conditions*")
        }

        @Test
        fun coverageFooterListsUnrenderedOperandCountSeparatelyFromConditionCoverage() {
            val comparisonWithUnrenderedOperand = Condition.Comparison(Operand.Unrendered("count(input.x)"), Operator.EQ, Operand.Literal("0"))
            val rule = RuleGroup("deny", metadata = null, default = null, bodies = listOf(body(listOf(comparisonWithUnrenderedOperand))))
            val pkg = PolicyPackage("release.approvals", listOf(rule), listOf("approvals/change_approval.rego"))
            val markdown = MarkdownRenderer.renderCard(rule, pkg, PolicySet(listOf(pkg)))

            assertThat(markdown).contains("*Rendering coverage: 1 of 1 conditions")
            assertThat(markdown).contains("contains 1 unrendered value")
        }
    }

    @Nested
    inner class GroupedHelperAssignments {
        // Review item 12: consecutive "helper-assignment"-reasoned Unrendered conditions render
        // as ONE labelled block; everything else (a differently-reasoned Unrendered, or a real
        // rendered condition) breaks the run. Coverage must stay exactly as honest as before --
        // merging is presentation only.

        @Test
        fun twoConsecutiveHelperAssignmentsMergeIntoOneBlockWithThePluralLabel() {
            val required = Condition.Unrendered("required := {\"owner\", \"team\"}", "helper-assignment")
            val provided = Condition.Unrendered("provided := {l | input.request.object.metadata.labels[l]}", "helper-assignment")
            val missing = Condition.Unrendered("missing := required - provided", "helper-assignment")
            val countCheck = Condition.Comparison(Operand.BuiltinCall("count", listOf(Operand.Variable("missing"))), Operator.GT, Operand.Literal("0"))
            val rule = RuleGroup("deny", metadata = null, default = null, bodies = listOf(body(listOf(required, provided, missing, countCheck))))
            val pkg = PolicyPackage("k8s.admission", listOf(rule), listOf("k8s.rego"))
            val markdown = MarkdownRenderer.renderCard(rule, pkg, PolicySet(listOf(pkg)))

            assertThat(markdown.lines().count { it.trim().startsWith("- ⚠") }).isEqualTo(1)
            assertThat(markdown).containsIgnoringCase("helper definitions")
            val fenceStart = markdown.indexOf("```rego")
            val fenceEnd = markdown.indexOf("```", fenceStart + 1)
            val fence = markdown.substring(fenceStart, fenceEnd)
            assertThat(fence.indexOf("required :=")).isGreaterThanOrEqualTo(0)
            assertThat(fence.indexOf("required :=")).isLessThan(fence.indexOf("provided :="))
            assertThat(fence.indexOf("provided :=")).isLessThan(fence.indexOf("missing :="))
            assertThat(markdown.indexOf("`missing` is not empty")).isGreaterThan(fenceEnd)
            // Presentation only: still 3 Unrendered + 1 rendered, same as before merging existed.
            assertThat(markdown).contains("*Rendering coverage: 1 of 4 conditions (including 3 helper assignments shown as source)*")
        }

        @Test
        fun aSingleHelperAssignmentStillUsesTheSingularLabel() {
            val rule = RuleGroup("deny", metadata = null, default = null, bodies = listOf(body(listOf(Condition.Unrendered("a := count(input.xs)", "helper-assignment"), envIsProd))))
            val pkg = PolicyPackage("helpers", listOf(rule), listOf("helpers.rego"))
            val markdown = MarkdownRenderer.renderCard(rule, pkg, PolicySet(listOf(pkg)))

            assertThat(markdown).containsIgnoringCase("helper definition").doesNotContainIgnoringCase("helper definitions")
        }

        @Test
        fun aDifferentlyReasonedUnrenderedConditionIsNeverAbsorbedEvenWhenDirectlyAdjacent() {
            // CHALLENGE edge case: two Unrendered conditions sit back-to-back with NO rendered
            // condition between them, but only the first is a helper-assignment -- they must stay
            // two separate blocks, not one.
            val assignment = Condition.Unrendered("a := count(input.xs)", "helper-assignment")
            val unrelatedCall = Condition.Unrendered("json.is_valid(input.z)", "function-call")
            val rule = RuleGroup("deny", metadata = null, default = null, bodies = listOf(body(listOf(assignment, unrelatedCall, envIsProd))))
            val pkg = PolicyPackage("helpers", listOf(rule), listOf("helpers.rego"))
            val markdown = MarkdownRenderer.renderCard(rule, pkg, PolicySet(listOf(pkg)))

            assertThat(markdown.lines().count { it.trim().startsWith("- ⚠") }).isEqualTo(2)
            assertThat(markdown.split("```rego").size - 1).isEqualTo(2)
            assertThat(markdown).containsIgnoringCase("helper definition")
            assertThat(markdown).contains("⚠ **not rendered — shown as source:**")
        }

        @Test
        fun aBodyThatIsEntirelyOneHelperAssignmentRunRendersCleanlyWithNoCrash() {
            // CHALLENGE edge case (structural emptiness variant): nothing before or after the run.
            val rule = RuleGroup(
                "deny", metadata = null, default = null,
                bodies = listOf(body(listOf(Condition.Unrendered("a := count(input.x)", "helper-assignment"), Condition.Unrendered("b := count(input.y)", "helper-assignment")))),
            )
            val pkg = PolicyPackage("helpers", listOf(rule), listOf("helpers.rego"))
            val markdown = MarkdownRenderer.renderCard(rule, pkg, PolicySet(listOf(pkg)))

            assertThat(markdown.lines().count { it.trim().startsWith("- ⚠") }).isEqualTo(1)
            assertThat(markdown.split("```rego").size - 1).isEqualTo(1)
            assertThat(markdown).contains("*Rendering coverage: 0 of 2 conditions (including 2 helper assignments shown as source)*")
        }
    }

    @Nested
    inner class ElseChainAndCoverageBreakdown {

        private fun card(conditions: List<Condition>): String {
            val rule = RuleGroup("risk_level", metadata = null, default = null, bodies = listOf(body(conditions)))
            val pkg = PolicyPackage("authz", listOf(rule), listOf("authz.rego"))
            return MarkdownRenderer.renderCard(rule, pkg, PolicySet(listOf(pkg)))
        }

        @Test
        fun anElseChainHeaderExplainsTheConstructAndKeepsTheSourceVerbatim() {
            val chain = "risk_level := \"high\" if {\n\tinput.a > 1\n} else := \"low\""
            val markdown = card(listOf(Condition.Unrendered(chain, "else-chain")))
            val header = markdown.lines().single { it.startsWith("- ⚠") }
            assertThat(header).containsIgnoringCase("else chain").contains("in order").contains("first")
            assertThat(markdown).contains(chain.lines().first()).contains("} else := \"low\"")
            assertThat(markdown).contains("0 of 1 conditions")
        }

        @Test
        fun otherFallbackReasonsKeepTheGenericHeaderAndNeverMentionElse() {
            val markdown = card(listOf(Condition.Unrendered("every x in y { true }", "every")))
            assertThat(markdown.lines().single { it.startsWith("- ⚠") }).doesNotContainIgnoringCase("else")
        }

        @Test
        fun theCoverageFooterCountsHelperAssignmentsWithoutChangingTheFigures() {
            val markdown = card(listOf(Condition.Unrendered("a := count(x)", "helper-assignment"), envIsProd, Condition.Unrendered("json.is_valid(z)", "function-call")))
            assertThat(markdown).contains("*Rendering coverage: 1 of 3 conditions (including 1 helper assignment shown as source)*")
        }

        @Test
        fun noHelperAssignmentsMeansTheFooterIsExactlyAsBefore() {
            val markdown = card(listOf(envIsProd, Condition.Unrendered("json.is_valid(z)", "function-call")))
            assertThat(markdown).contains("*Rendering coverage: 1 of 2 conditions*").doesNotContain("helper")
        }
    }

    @Nested
    inner class IndexSplit {

        private fun ruleGroup(name: String, controlId: String?) = RuleGroup(
            name, metadata = controlId?.let { RuleMetadata("T-$it", "d", it, emptyList()) }, default = null, bodies = listOf(body(listOf(envIsProd))),
        )

        @Test
        fun controlsComeFirstThenAHeadingAndASecondTableOfIdLessRules() {
            val pkg = PolicyPackage("p", listOf(ruleGroup("helper", null), ruleGroup("deny", "C-2"), ruleGroup("allow", "C-1")), listOf("f.rego"))
            val lines = MarkdownRenderer.renderIndex(PolicySet(listOf(pkg))).lines()
            val heading = lines.indexOfFirst { it.startsWith("## ") }
            assertThat(lines[heading]).containsIgnoringCase("without a control id")
            assertThat(lines.take(heading).filter { it.startsWith("| C-") }.map { it.split("|")[1].trim() }).containsExactly("C-1", "C-2")
            assertThat(lines.drop(heading).filter { it.startsWith("| —") }).hasSize(1)
            assertThat(lines.count { it.startsWith("|---") }).isEqualTo(2)
        }

        @Test
        fun noIdLessRulesMeansNoHeadingAndOneTable() {
            val pkg = PolicyPackage("p", listOf(ruleGroup("deny", "C-1")), listOf("f.rego"))
            val markdown = MarkdownRenderer.renderIndex(PolicySet(listOf(pkg)))
            // Repinned deliberately (not Tier-1): narrowed from doesNotContain("## ") to the specific
            // heading this test is actually about, now that the always-present "## How to read this
            // index" legend is a second, unrelated "## " heading every index carries.
            assertThat(markdown).doesNotContain("## Rules without a control id")
            assertThat(markdown.lines().count { it.startsWith("|---") }).isEqualTo(1)
        }

        @Test
        fun onlyIdLessRulesStillRenderTheControlsHeaderThenTheSecondTable() {
            val pkg = PolicyPackage("p", listOf(ruleGroup("helper", null)), listOf("f.rego"))
            val markdown = MarkdownRenderer.renderIndex(PolicySet(listOf(pkg)))
            assertThat(markdown.lines().count { it.startsWith("|---") }).isEqualTo(2)
            assertThat(markdown).contains("## Rules without a control id")
        }

        @Test
        fun bothTablesKeepTheExampleCoverageColumnWhenExamplesAreSupplied() {
            val pkg = PolicyPackage("p", listOf(ruleGroup("helper", null), ruleGroup("deny", "C-1")), listOf("f.rego"))
            val markdown = MarkdownRenderer.renderIndex(PolicySet(listOf(pkg)), mapOf("p" to emptyMap<String, List<WorkedExample>>()).plus("q" to emptyMap()))
            assertThat(markdown.lines().count { it.startsWith("| Control ID") && it.contains("Example coverage") }).isEqualTo(2)
            assertThat(markdown.lines().filter { it.startsWith("| C-1") || it.startsWith("| —") }.all { it.contains("– / –") }).isTrue()
        }
    }

    @Nested
    inner class Anchors {

        private fun ruleReferenceCondition(pkg: String, name: String, negated: Boolean = false) =
            Condition.RuleReference(pkg, name, negated)

        @Test
        fun sameFileReferenceLinksWithBareAnchor() {
            val target = RuleGroup("is_release_candidate", metadata = null, default = null, bodies = listOf(body(listOf(envIsProd), file = "evidence/pipeline_evidence.rego")))
            val referencing = RuleGroup(
                "deny", metadata = null, default = null,
                bodies = listOf(body(listOf(ruleReferenceCondition("release.evidence", "is_release_candidate")), file = "evidence/pipeline_evidence.rego")),
            )
            val pkg = PolicyPackage("release.evidence", listOf(referencing, target), listOf("evidence/pipeline_evidence.rego"))
            val markdown = MarkdownRenderer.renderCard(referencing, pkg, PolicySet(listOf(pkg)))

            assertThat(markdown).contains("see rule [`is_release_candidate`](#release-evidence-is-release-candidate)")
        }

        @Test
        fun crossPackageReferenceLinksToOtherPackageFile() {
            val exemptRule = RuleGroup("exempt_service", metadata = null, default = null, bodies = listOf(body(listOf(envIsProd), file = "exemptions/exemptions.rego")))
            val exemptPkg = PolicyPackage("release.exemptions", listOf(exemptRule), listOf("exemptions/exemptions.rego"))
            val referencing = RuleGroup(
                "deny", metadata = null, default = null,
                bodies = listOf(body(listOf(ruleReferenceCondition("release.exemptions", "exempt_service", negated = true)), file = "evidence/pipeline_evidence.rego")),
            )
            val evidencePkg = PolicyPackage("release.evidence", listOf(referencing), listOf("evidence/pipeline_evidence.rego"))
            val policySet = PolicySet(listOf(evidencePkg, exemptPkg))
            val markdown = MarkdownRenderer.renderCard(referencing, evidencePkg, policySet)

            assertThat(markdown).contains("rule [`exempt_service`](release-exemptions.md#release-exemptions-exempt-service) does not match")
        }

        @Test
        fun anchorUsesControlIdWhenTargetHasOne() {
            val target = RuleGroup("deny", metadata = RuleMetadata("Title", "desc", "REL-001", emptyList()), default = null, bodies = listOf(body(listOf(envIsProd))))
            val referencing = RuleGroup("all_checks_passed", metadata = null, default = null, bodies = listOf(body(listOf(ruleReferenceCondition("release.approvals", "deny")))))
            val pkg = PolicyPackage("release.approvals", listOf(target, referencing), listOf("approvals/change_approval.rego"))
            val markdown = MarkdownRenderer.renderCard(referencing, pkg, PolicySet(listOf(pkg)))

            assertThat(markdown).contains("(#rel-001)")
        }
    }

    @Nested
    inner class PackagePage {

        @Test
        fun headerListsPackagePathAndSourceFiles() {
            val rule = RuleGroup("deny", metadata = null, default = null, bodies = listOf(body(listOf(envIsProd))))
            val pkg = PolicyPackage("release.approvals", listOf(rule), listOf("approvals/change_approval.rego"))
            val markdown = MarkdownRenderer.renderPackage(pkg, PolicySet(listOf(pkg)))

            assertThat(markdown).contains("# Package `release.approvals`")
            assertThat(markdown).contains("*Source files: `approvals/change_approval.rego`*")
        }

        @Test
        fun packageMetadataTitleAndDescriptionRenderBeforeFirstCard() {
            // Review item 4: package-scope METADATA shown on the package page.
            val rule = RuleGroup("deny", metadata = null, default = null, bodies = listOf(body(listOf(envIsProd))))
            val pkg = PolicyPackage(
                "k8s.admission", listOf(rule), listOf("k8s.rego"),
                metadata = io.explico.model.PackageMetadata("Container hardening", "Containers must not be privileged."),
            )
            val markdown = MarkdownRenderer.renderPackage(pkg, PolicySet(listOf(pkg)))

            assertThat(markdown).contains("**Container hardening**")
            assertThat(markdown).contains("Containers must not be privileged.")
            assertThat(markdown.indexOf("Container hardening")).isLessThan(markdown.indexOf("## "))
        }

        @Test
        fun noPackageMetadataOmitsTheBlockEntirely() {
            val rule = RuleGroup("deny", metadata = null, default = null, bodies = listOf(body(listOf(envIsProd))))
            val pkg = PolicyPackage("release.approvals", listOf(rule), listOf("approvals/change_approval.rego"))
            val markdown = MarkdownRenderer.renderPackage(pkg, PolicySet(listOf(pkg)))

            // No package metadata means no bold line at all between the source-files line and the
            // first card's own heading.
            val betweenSourcesAndFirstCard = markdown.substringAfter("*Source files:").substringBefore("## ")
            assertThat(betweenSourcesAndFirstCard).doesNotContain("**")
        }

        @Test
        fun packageFooterReportsCoverageWithPercent() {
            val rule = RuleGroup("deny", metadata = null, default = null, bodies = listOf(body(listOf(envIsProd, approvedAbsent))))
            val pkg = PolicyPackage("release.approvals", listOf(rule), listOf("approvals/change_approval.rego"))
            val markdown = MarkdownRenderer.renderPackage(pkg, PolicySet(listOf(pkg)))

            assertThat(markdown).contains("*Package rendering coverage: 2 of 2 conditions (100%)*")
        }

        @Test
        fun multipleCardsAreSeparatedByHorizontalRule() {
            val rule1 = RuleGroup("deny", metadata = null, default = null, bodies = listOf(body(listOf(envIsProd))))
            val rule2 = RuleGroup("is_release_candidate", metadata = null, default = null, bodies = listOf(body(listOf(envIsProd))))
            val pkg = PolicyPackage("release.evidence", listOf(rule1, rule2), listOf("evidence/pipeline_evidence.rego"))
            val markdown = MarkdownRenderer.renderPackage(pkg, PolicySet(listOf(pkg)))

            assertThat(markdown.split("---").size - 1).isEqualTo(2) // one after each card
        }

        @Test
        fun packageWithZeroRulesStillRendersAHeaderAndAFullCoverageFooterWithoutCrashing() {
            val pkg = PolicyPackage("release.emptypkg", emptyList(), listOf("emptypkg/empty.rego"))
            val markdown = MarkdownRenderer.renderPackage(pkg, PolicySet(listOf(pkg)))

            assertThat(markdown).contains("# Package `release.emptypkg`")
            assertThat(markdown).contains("*Package rendering coverage: 0 of 0 conditions (100%)*")
        }

        @Test
        @DisplayName("compliance quick win: a supplied policy digest ends the document as its first-12-hex short form")
        fun policyDigestIsTheLastLineAsItsShortForm() {
            val rule = RuleGroup("deny", metadata = null, default = null, bodies = listOf(body(listOf(envIsProd))))
            val pkg = PolicyPackage("release.approvals", listOf(rule), listOf("approvals/change_approval.rego"))
            val fullDigest = "0123456789abcdef".repeat(4)

            val markdown = MarkdownRenderer.renderPackage(pkg, PolicySet(listOf(pkg)), policyDigest = fullDigest)

            assertThat(markdown.trimEnd().lines().last()).isEqualTo("*Policy digest: `sha256:0123456789ab…` (full value in index.md)*")
        }

        @Test
        fun noDigestLineWhenNoneIsSupplied() {
            val rule = RuleGroup("deny", metadata = null, default = null, bodies = listOf(body(listOf(envIsProd))))
            val pkg = PolicyPackage("release.approvals", listOf(rule), listOf("approvals/change_approval.rego"))

            val markdown = MarkdownRenderer.renderPackage(pkg, PolicySet(listOf(pkg)))

            assertThat(markdown).doesNotContain("Policy digest")
        }
    }

    @Nested
    inner class Index {

        @Test
        fun listsAllControlsSortedByControlIdWithMissingIdsLast() {
            val rel001 = RuleGroup("deny", metadata = RuleMetadata("Production change approval", "d", "REL-001", emptyList()), default = null, bodies = listOf(body(listOf(envIsProd))))
            val rel002 = RuleGroup("deny", metadata = RuleMetadata("Pipeline evidence", "d", "REL-002", emptyList()), default = null, bodies = listOf(body(listOf(envIsProd), file = "evidence/pipeline_evidence.rego")))
            val noId = RuleGroup("exempt_service", metadata = null, default = null, bodies = listOf(body(listOf(envIsProd), file = "exemptions/exemptions.rego")))
            val policySet = PolicySet(
                listOf(
                    PolicyPackage("release.approvals", listOf(rel001), listOf("approvals/change_approval.rego")),
                    PolicyPackage("release.evidence", listOf(rel002), listOf("evidence/pipeline_evidence.rego")),
                    PolicyPackage("release.exemptions", listOf(noId), listOf("exemptions/exemptions.rego")),
                )
            )
            val markdown = MarkdownRenderer.renderIndex(policySet)

            val rel001Index = markdown.indexOf("REL-001")
            val rel002Index = markdown.indexOf("REL-002")
            val noIdIndex = markdown.indexOf("exempt_service")
            assertThat(rel001Index).isLessThan(rel002Index)
            assertThat(rel002Index).isLessThan(noIdIndex)
        }

        @Test
        fun overallCoverageLineIsPresent() {
            val rule = RuleGroup("deny", metadata = RuleMetadata("Title", "d", "REL-001", emptyList()), default = null, bodies = listOf(body(listOf(envIsProd, Condition.Unrendered("x", "unclassified")))))
            val policySet = PolicySet(listOf(PolicyPackage("release.approvals", listOf(rule), listOf("approvals/change_approval.rego"))))
            val markdown = MarkdownRenderer.renderIndex(policySet)

            assertThat(markdown).contains("Overall rendering coverage: 1 of 2 conditions (50%)")
        }

        @Test
        fun emptyPolicySetRendersAnEmptyTableWithoutCrashing() {
            val markdown = MarkdownRenderer.renderIndex(PolicySet(emptyList()))

            // Repinned deliberately (not Tier-1): the column is now "Rendered", not "Coverage" -- see
            // coverageColumnRenamedToRendered below for why.
            assertThat(markdown).contains("| Control ID | Title | Package | Rule | Rendered | Source file |")
            assertThat(markdown).contains("Overall rendering coverage: 0 of 0 conditions (100%)")
        }

        @Test
        fun aDefaultOnlyRuleWithNoBodiesStillGetsARowWithoutCrashing() {
            // CHALLENGE edge case (review item 3): renderIndex's row-building also used to call
            // rule.bodies.first() unguarded.
            val rule = RuleGroup("risk_score", metadata = null, default = DefaultValue("0", SourceRef("f.rego", 1)), bodies = emptyList())
            val policySet = PolicySet(listOf(PolicyPackage("scratch", listOf(rule), listOf("scratch.rego"))))

            val markdown = MarkdownRenderer.renderIndex(policySet)

            assertThat(markdown).contains("risk_score")
            assertThat(markdown).contains("100%")
        }

        @Test
        fun aSplitDefaultEntryGetsItsOwnIndexRowWithARealSourceFile() {
            val rule = RuleGroup(
                "allow", metadata = null, default = DefaultValue("false", SourceRef("authz.rego", 5)),
                bodies = emptyList(), isSplitDefaultEntry = true,
            )
            val policySet = PolicySet(listOf(PolicyPackage("authz", listOf(rule), listOf("authz.rego"))))

            val markdown = MarkdownRenderer.renderIndex(policySet)

            val row = markdown.lines().single { it.startsWith("| —") }
            assertThat(row).contains("`allow`").contains("`authz.rego`").contains("100%")
        }

        @Test
        fun exampleCoverageColumnAndCorpusGapsLineOnlyAppearWhenExamplesAreSupplied() {
            val rule = RuleGroup("deny", metadata = RuleMetadata("Title", "d", "REL-001", emptyList()), default = null, bodies = listOf(body(listOf(envIsProd))))
            val policySet = PolicySet(listOf(PolicyPackage("release.approvals", listOf(rule), listOf("approvals/change_approval.rego"))))

            val withoutExamples = MarkdownRenderer.renderIndex(policySet)
            assertThat(withoutExamples).doesNotContain("Example coverage")
            assertThat(withoutExamples).doesNotContain("corpus has gaps")

            val matchedOnly = WorkedExample(Fixture("f", null, buildJsonObject { }), matched = true, messages = listOf("x"), situationLabels = listOf(null))
            val withExamplesNoGap = MarkdownRenderer.renderIndex(policySet, mapOf("release.approvals" to mapOf("deny" to listOf(matchedOnly))))
            assertThat(withExamplesNoGap).contains("Example coverage")
            assertThat(withExamplesNoGap).contains("✓ / –")
            assertThat(withExamplesNoGap).doesNotContain("corpus has gaps") // this control HAS a matching example, so it's not a gap
        }

        @Test
        fun corpusGapsLineListsControlsWithNoMatchingExampleAtAll() {
            val rule = RuleGroup("deny", metadata = RuleMetadata("Title", "d", "REL-001", emptyList()), default = null, bodies = listOf(body(listOf(envIsProd))))
            val policySet = PolicySet(listOf(PolicyPackage("release.approvals", listOf(rule), listOf("approvals/change_approval.rego"))))

            val notMatchedOnly = WorkedExample(Fixture("f", null, buildJsonObject { }), matched = false, messages = emptyList(), situationLabels = emptyList())
            val markdown = MarkdownRenderer.renderIndex(policySet, mapOf("release.approvals" to mapOf("deny" to listOf(notMatchedOnly))))

            assertThat(markdown).contains("– / ✓")
            assertThat(markdown).contains("*1 controls have no fixture demonstrating them — the corpus has gaps.*")
        }

        @Test
        @DisplayName("compliance quick win: a control's title links to its own card")
        fun titleLinksToItsOwnCardAnchor() {
            val rel001 = RuleGroup("deny", metadata = RuleMetadata("Production change approval", "d", "REL-001", emptyList()), default = null, bodies = listOf(body(listOf(envIsProd))))
            val pkg = PolicyPackage("release.approvals", listOf(rel001), listOf("approvals/change_approval.rego"))
            val markdown = MarkdownRenderer.renderIndex(PolicySet(listOf(pkg)))

            assertThat(markdown).contains("[Production change approval](release-approvals.md#rel-001)")
            assertThat(markdown.lines().single { it.startsWith("| REL-001") }).startsWith("| REL-001 |")
        }

        @Test
        @DisplayName("compliance quick win: an id-less rule links to its package-rule anchor")
        fun idLessRuleTitleLinksToo() {
            val noId = RuleGroup("exempt_service", metadata = null, default = null, bodies = listOf(body(listOf(envIsProd), file = "exemptions/exemptions.rego")))
            val pkg = PolicyPackage("release.exemptions", listOf(noId), listOf("exemptions/exemptions.rego"))
            val markdown = MarkdownRenderer.renderIndex(PolicySet(listOf(pkg)))

            assertThat(markdown).contains("[exempt_service](release-exemptions.md#release-exemptions-exempt-service)")
        }

        @Test
        @DisplayName("compliance quick win: a rule whose card claimed no anchor (a duplicate) keeps a plain title")
        fun aDuplicateClaimKeepsAPlainTitle() {
            // Two same-named, id-less rules in one package: cardAnchorIds gives the first the slug and the
            // second null (same precedent renderCard already follows) -- the index must not link to a
            // nonexistent anchor.
            val first = RuleGroup("deny", metadata = null, default = null, bodies = listOf(body(listOf(envIsProd))))
            val second = RuleGroup("deny", metadata = null, default = null, bodies = listOf(body(listOf(envIsProd))))
            val pkg = PolicyPackage("scratch", listOf(first, second), listOf("scratch.rego"))
            val markdown = MarkdownRenderer.renderIndex(PolicySet(listOf(pkg)))

            val rows = markdown.lines().filter { it.startsWith("| —") }
            assertThat(rows).hasSize(2)
            assertThat(rows.count { it.contains("[deny]") }).isEqualTo(1)
            assertThat(rows.count { it.contains("| deny |") }).isEqualTo(1)
        }

        @Test
        @DisplayName("compliance quick win: the 'Coverage' column is renamed to 'Rendered', with and without examples")
        fun coverageColumnRenamedToRendered() {
            val rule = RuleGroup("deny", metadata = RuleMetadata("Title", "d", "REL-001", emptyList()), default = null, bodies = listOf(body(listOf(envIsProd))))
            val policySet = PolicySet(listOf(PolicyPackage("release.approvals", listOf(rule), listOf("approvals/change_approval.rego"))))

            val withoutExamples = MarkdownRenderer.renderIndex(policySet)
            assertThat(withoutExamples).contains("| Control ID | Title | Package | Rule | Rendered | Source file |")
            assertThat(withoutExamples).doesNotContain("| Coverage |")

            val matched = WorkedExample(Fixture("f", null, buildJsonObject { }), matched = true, messages = listOf("x"), situationLabels = listOf(null))
            val withExamples = MarkdownRenderer.renderIndex(policySet, mapOf("release.approvals" to mapOf("deny" to listOf(matched))))
            assertThat(withExamples).contains("| Control ID | Title | Package | Rule | Rendered | Example coverage | Source file |")
            assertThat(withExamples).doesNotContain("| Coverage |")
        }

        @Test
        @DisplayName("compliance quick win: a closing legend explains Rendered, and disclaims effectiveness")
        fun legendExplainsRenderedAndDisclaimsEffectiveness() {
            val rule = RuleGroup("deny", metadata = RuleMetadata("Title", "d", "REL-001", emptyList()), default = null, bodies = listOf(body(listOf(envIsProd))))
            val policySet = PolicySet(listOf(PolicyPackage("release.approvals", listOf(rule), listOf("approvals/change_approval.rego"))))

            val markdown = MarkdownRenderer.renderIndex(policySet)

            assertThat(markdown).contains("## How to read this index")
            assertThat(markdown).contains("**Rendered**")
            assertThat(markdown).contains("effective or complete")
            assertThat(markdown.indexOf("## How to read this index")).isGreaterThan(markdown.indexOf("Overall rendering coverage"))
        }

        @Test
        @DisplayName("compliance quick win: the legend's Example-coverage bullet appears only when examples were supplied")
        fun legendExampleCoverageBulletOnlyWithExamples() {
            val rule = RuleGroup("deny", metadata = RuleMetadata("Title", "d", "REL-001", emptyList()), default = null, bodies = listOf(body(listOf(envIsProd))))
            val policySet = PolicySet(listOf(PolicyPackage("release.approvals", listOf(rule), listOf("approvals/change_approval.rego"))))

            val withoutExamples = MarkdownRenderer.renderIndex(policySet)
            assertThat(withoutExamples.substringAfter("## How to read this index")).doesNotContain("**Example coverage**")

            val matched = WorkedExample(Fixture("f", null, buildJsonObject { }), matched = true, messages = listOf("x"), situationLabels = listOf(null))
            val withExamples = MarkdownRenderer.renderIndex(policySet, mapOf("release.approvals" to mapOf("deny" to listOf(matched))))
            assertThat(withExamples.substringAfter("## How to read this index")).contains("**Example coverage**")
        }

        @Test
        @DisplayName("compliance quick win: a framework-requirement matrix lists the controls naming each framework, sorted")
        fun frameworkMatrixListsControlsSorted() {
            val rel001 = RuleGroup("deny", metadata = RuleMetadata("A", "d", "REL-001", listOf("SOC 2 CC8.1", "ISO 27001 A.8.32")), default = null, bodies = listOf(body(listOf(envIsProd))))
            val rel002 = RuleGroup("deny", metadata = RuleMetadata("B", "d", "REL-002", listOf("SOC 2 CC8.1")), default = null, bodies = listOf(body(listOf(envIsProd), file = "evidence/pipeline_evidence.rego")))
            val policySet = PolicySet(
                listOf(
                    PolicyPackage("release.approvals", listOf(rel001), listOf("approvals/change_approval.rego")),
                    PolicyPackage("release.evidence", listOf(rel002), listOf("evidence/pipeline_evidence.rego")),
                ),
            )
            val markdown = MarkdownRenderer.renderIndex(policySet)

            assertThat(markdown).contains("## Controls by framework")
            assertThat(markdown).contains("| Framework requirement | Controls |")
            val socRow = markdown.lines().single { it.startsWith("| SOC 2 CC8.1 |") }
            assertThat(socRow).contains("[REL-001](release-approvals.md#rel-001)").contains("[REL-002](release-evidence.md#rel-002)")
            assertThat(socRow.indexOf("REL-001")).isLessThan(socRow.indexOf("REL-002"))
            val isoRow = markdown.lines().single { it.startsWith("| ISO 27001 A.8.32 |") }
            assertThat(isoRow).contains("[REL-001]").doesNotContain("REL-002")
            // Sorted case-insensitively: "ISO..." before "SOC..." alphabetically.
            assertThat(markdown.indexOf("| ISO 27001 A.8.32 |")).isLessThan(markdown.indexOf("| SOC 2 CC8.1 |"))
            assertThat(markdown.indexOf("## Controls by framework")).isLessThan(markdown.indexOf("Overall rendering coverage"))
        }

        @Test
        @DisplayName("compliance quick win: no framework matrix at all when no rule names a framework")
        fun noFrameworkMatrixWhenNoneDeclared() {
            val rule = RuleGroup("deny", metadata = RuleMetadata("Title", "d", "REL-001", emptyList()), default = null, bodies = listOf(body(listOf(envIsProd))))
            val policySet = PolicySet(listOf(PolicyPackage("release.approvals", listOf(rule), listOf("approvals/change_approval.rego"))))

            val markdown = MarkdownRenderer.renderIndex(policySet)

            assertThat(markdown).doesNotContain("Controls by framework").doesNotContain("Framework requirement")
        }

        @Test
        @DisplayName("CHALLENGE: a framework listed twice in one rule's own METADATA does not duplicate that rule in its row")
        fun aFrameworkRepeatedWithinOneRulesMetadataIsNotDuplicated() {
            val rule = RuleGroup("deny", metadata = RuleMetadata("A", "d", "REL-001", listOf("SOC 2 CC8.1", "SOC 2 CC8.1")), default = null, bodies = listOf(body(listOf(envIsProd))))
            val policySet = PolicySet(listOf(PolicyPackage("release.approvals", listOf(rule), listOf("approvals/change_approval.rego"))))

            val markdown = MarkdownRenderer.renderIndex(policySet)

            val row = markdown.lines().single { it.startsWith("| SOC 2 CC8.1 |") }
            assertThat(row.split("REL-001")).hasSize(2) // appears exactly once, not twice
        }

        @Test
        @DisplayName("CHALLENGE: two rules naming the framework with different case are DISTINCT rows (exactly as METADATA wrote them)")
        fun differentlyCasedFrameworkStringsAreDistinctRows() {
            val upper = RuleGroup("deny", metadata = RuleMetadata("A", "d", "REL-001", listOf("SOC 2 CC8.1")), default = null, bodies = listOf(body(listOf(envIsProd))))
            val lower = RuleGroup("deny", metadata = RuleMetadata("B", "d", "REL-002", listOf("soc 2 cc8.1")), default = null, bodies = listOf(body(listOf(envIsProd), file = "evidence/pipeline_evidence.rego")))
            val policySet = PolicySet(
                listOf(
                    PolicyPackage("release.approvals", listOf(upper), listOf("approvals/change_approval.rego")),
                    PolicyPackage("release.evidence", listOf(lower), listOf("evidence/pipeline_evidence.rego")),
                ),
            )

            val markdown = MarkdownRenderer.renderIndex(policySet)

            assertThat(markdown.lines().filter { it.startsWith("| SOC 2 CC8.1 |") || it.startsWith("| soc 2 cc8.1 |") }).hasSize(2)
            // Sorted case-insensitively, so same relative order is stable regardless of which happened to be typed first.
            assertThat(markdown.indexOf("| SOC 2 CC8.1 |")).isLessThan(markdown.indexOf("| soc 2 cc8.1 |"))
        }

        @Test
        @DisplayName("CHALLENGE: an id-less rule naming a framework is listed by its rule name, linked")
        fun idLessRuleInFrameworkMatrixIsLabelledByRuleName() {
            val rule = RuleGroup("exempt_service", metadata = RuleMetadata(null, null, null, listOf("SOC 2 CC8.1")), default = null, bodies = listOf(body(listOf(envIsProd), file = "exemptions/exemptions.rego")))
            val policySet = PolicySet(listOf(PolicyPackage("release.exemptions", listOf(rule), listOf("exemptions/exemptions.rego"))))

            val markdown = MarkdownRenderer.renderIndex(policySet)

            assertThat(markdown).contains("| SOC 2 CC8.1 | [exempt_service](release-exemptions.md#release-exemptions-exempt-service) |")
        }

        @Test
        @DisplayName("CHALLENGE: a rule whose card claimed no anchor (a duplicate) is listed unlinked in the matrix too, never a dead link")
        fun aDuplicateClaimStaysUnlinkedInTheMatrixToo() {
            val first = RuleGroup("deny", metadata = RuleMetadata(null, null, null, listOf("SOC 2 CC8.1")), default = null, bodies = listOf(body(listOf(envIsProd))))
            val second = RuleGroup("deny", metadata = RuleMetadata(null, null, null, listOf("SOC 2 CC8.1")), default = null, bodies = listOf(body(listOf(envIsProd))))
            val policySet = PolicySet(listOf(PolicyPackage("scratch", listOf(first, second), listOf("scratch.rego"))))

            val markdown = MarkdownRenderer.renderIndex(policySet)

            val row = markdown.lines().single { it.startsWith("| SOC 2 CC8.1 |") }
            // The first "deny" claimed the anchor and links; the second lost the claim (same precedent as the
            // title-link test above) and stays a plain, unlinked name -- never a link to a nonexistent anchor.
            assertThat(row).isEqualTo("| SOC 2 CC8.1 | [deny](scratch.md#scratch-deny), deny |")
        }

        @Test
        @DisplayName("compliance quick win: a supplied policy digest appears as a full SHA-256 under the title")
        fun policyDigestLineUnderTitle() {
            val rule = RuleGroup("deny", metadata = null, default = null, bodies = listOf(body(listOf(envIsProd))))
            val policySet = PolicySet(listOf(PolicyPackage("release.approvals", listOf(rule), listOf("approvals/change_approval.rego"))))

            val digest = "a".repeat(64)
            val markdown = MarkdownRenderer.renderIndex(policySet, policyDigest = digest)

            val lines = markdown.lines()
            assertThat(lines[0]).isEqualTo("# Control index")
            assertThat(lines[2]).isEqualTo("*Policy digest: `sha256:$digest`*")
        }

        @Test
        @DisplayName("no digest line at all when none is supplied (default), so existing direct callers are unaffected")
        fun noDigestLineWhenNotSupplied() {
            val rule = RuleGroup("deny", metadata = null, default = null, bodies = listOf(body(listOf(envIsProd))))
            val policySet = PolicySet(listOf(PolicyPackage("release.approvals", listOf(rule), listOf("approvals/change_approval.rego"))))

            val markdown = MarkdownRenderer.renderIndex(policySet)

            assertThat(markdown).doesNotContain("Policy digest")
        }
    }

    @Nested
    inner class WorkedExamplesSection {

        private val rule = RuleGroup(
            "deny", metadata = null, default = null,
            bodies = listOf(body(listOf(envIsProd, approvedAbsent), "release [deployment id] has no approved change ticket")),
        )
        private val pkg = PolicyPackage("release.approvals", listOf(rule), listOf("approvals/change_approval.rego"))
        private val policySet = PolicySet(listOf(pkg))

        @Test
        fun noWorkedExamplesSectionWhenNoOutcomesSupplied() {
            val markdown = MarkdownRenderer.renderCard(rule, pkg, policySet, emptyList())
            assertThat(markdown).doesNotContain("Worked examples")
        }

        @Test
        fun matchedExampleShowsOutcomeSituationLabelMessageAndReferencedPathValues() {
            val fixtureInput = buildJsonObject {
                put("deployment", buildJsonObject { put("environment", "production") })
            }
            val outcome = WorkedExample(
                fixture = Fixture("hotfix without change ticket", null, fixtureInput),
                matched = true,
                messages = listOf("release rel-1002 has no approved change ticket"),
                situationLabels = listOf(1),
            )
            val markdown = MarkdownRenderer.renderCard(rule, pkg, policySet, listOf(outcome))

            assertThat(markdown).contains("**Worked examples**")
            assertThat(markdown).contains("- **hotfix without change ticket** — ❌ denied *(Situation 1)*")
            assertThat(markdown).contains("  *\"release rel-1002 has no approved change ticket\"*")
            assertThat(markdown).contains("`deployment ▸ environment`: `\"production\"`")
            assertThat(markdown).contains("`change ▸ ticket ▸ approved`: absent")
        }

        @Test
        fun notMatchedExampleShowsOutcomeWordWithNoMessageLine() {
            val outcome = WorkedExample(Fixture("approved standard release", null, buildJsonObject { }), matched = false, messages = emptyList(), situationLabels = emptyList())
            val markdown = MarkdownRenderer.renderCard(rule, pkg, policySet, listOf(outcome))

            assertThat(markdown).contains("- **approved standard release** — ✅ allowed")
            assertThat(markdown).doesNotContain("*\"")
        }

        @Test
        fun workedExamplesSectionEndsWithTheProvenanceFooter() {
            // spec §13.6: a muted line restating that verdicts are freshly opa-evaluated, never
            // predicted, directly in the rendered artefact -- with a blank line on each side,
            // separating it from the fixture list above and the coverage footer below.
            val outcome = WorkedExample(Fixture("approved standard release", null, buildJsonObject { }), matched = false, messages = emptyList(), situationLabels = emptyList())
            val markdown = MarkdownRenderer.renderCard(rule, pkg, policySet, listOf(outcome))

            assertThat(markdown).contains(
                "\n\n*Examples are evaluated against this policy version by OPA at generation time.*\n\n*Rendering coverage:",
            )
        }

        @Test
        fun provenanceFooterIsAbsentWhenThereAreNoWorkedExamples() {
            val markdown = MarkdownRenderer.renderCard(rule, pkg, policySet, emptyList())
            assertThat(markdown).doesNotContain("evaluated against this policy version")
        }
    }
    @Nested
    inner class CardAnchors {

        private fun rule(name: String, controlId: String? = null, split: Boolean = false) = RuleGroup(
            name = name,
            metadata = controlId?.let { RuleMetadata("T", null, it, emptyList()) },
            default = null,
            bodies = if (split) emptyList() else listOf(body(listOf(envIsProd))),
            isSplitDefaultEntry = split,
        )

        private fun page(vararg rules: RuleGroup): String {
            val pkg = PolicyPackage("p.q", rules.toList(), listOf("a.rego"))
            return MarkdownRenderer.renderPackage(pkg, PolicySet(listOf(pkg)))
        }

        private fun ids(markdown: String) = Regex("<a id=\"([^\"]+)\"></a>").findAll(markdown).map { it.groupValues[1] }.toList()

        @Test
        fun anAnchorSitsOnItsOwnLineSeparatedFromTheHeadingSoTheHeadingStillRenders() {
            val markdown = page(rule("deny", "REL-001"))
            assertThat(markdown).contains("<a id=\"rel-001\"></a>\n\n## REL-001 — T")
        }

        @Test
        fun idLessCardUsesPackageDashRuleName() {
            assertThat(ids(page(rule("is_prod")))).containsExactly("p-q-is-prod")
        }

        @Test
        fun everyIdIsUniqueEvenWhenSeveralIdLessCardsShareAName() {
            val markdown = page(rule("allow"), rule("allow"), rule("other"))
            assertThat(ids(markdown)).containsExactly("p-q-allow", "p-q-other")
        }

        @Test
        fun aSplitDefaultEntryNeverStealsAnIdLessSiblingsId() {
            // default entry listed FIRST: the sibling must still own `p-q-allow`, since that is where links resolve.
            val markdown = page(rule("allow", split = true), rule("allow"))
            val ids = ids(markdown)
            assertThat(ids).containsExactly("p-q-allow")
            assertThat(markdown.indexOf("<a id=\"p-q-allow\">")).isGreaterThan(markdown.indexOf("Default for"))
        }

        @Test
        fun aSplitDefaultEntryAmongControlIdSiblingsGetsItsOwnUnclaimedId() {
            assertThat(ids(page(rule("allow", "AZ-001"), rule("allow", "AZ-002"), rule("allow", split = true))))
                .containsExactly("az-001", "az-002", "p-q-allow")
        }

        @Test
        fun controlIdWithOddCharactersIsSluggedTheSameWayLinksAre() {
            val pkg = PolicyPackage("p.q", listOf(rule("x", "Ctl_9.1/a b")), listOf("a.rego"))
            val target = Condition.RuleReference("p.q", "x", false)
            val link = ExpressionRenderer.render(target, anchorFor = { "#" + MarkdownRenderer.anchorIdOf(pkg.rules[0], "p.q") })
            val id = ids(MarkdownRenderer.renderPackage(pkg, PolicySet(listOf(pkg)))).single()
            assertThat(id).isEqualTo("ctl-9-1-a-b")
            assertThat(link).contains("(#$id)")
        }

        @Test
        fun everyRuleReferenceLinkResolvesToAnEmittedAnchor() {
            val a = rule("a", "X-1")
            val b = RuleGroup("b", null, null, listOf(body(listOf(Condition.RuleReference("p.q", "a", false)))))
            val pkg = PolicyPackage("p.q", listOf(a, b), listOf("a.rego"))
            val markdown = MarkdownRenderer.renderPackage(pkg, PolicySet(listOf(pkg)))
            assertThat(markdown).contains("(#x-1)").contains("<a id=\"x-1\"></a>")
        }
    }

    @Nested
    inner class ConditionalYieldedValuesFollowUp {

        @Test
        fun aYieldedValueAppearsAsItsOwnLineAfterTheConditions() {
            val rule = RuleGroup(
                name = "tier",
                metadata = null,
                default = null,
                bodies = listOf(body(listOf(envIsProd), yieldsValue = Operand.Literal("\"high\""))),
            )
            val pkg = PolicyPackage("p", listOf(rule), listOf("a.rego"))
            val markdown = MarkdownRenderer.renderCard(rule, pkg, PolicySet(listOf(pkg)))
            assertThat(markdown).contains("**Value:** `\"high\"`")
            assertThat(markdown.indexOf("**Value:**")).isGreaterThan(markdown.indexOf("`deployment ▸ environment`"))
        }

        @Test
        @DisplayName("CHALLENGE edge case: an unrendered yielded value never adds to the coverage footer's unrendered-value count")
        fun anUnrenderedYieldedValueDoesNotAffectTheCoverageFooter() {
            val rule = RuleGroup(
                name = "label",
                metadata = null,
                default = null,
                bodies = listOf(body(listOf(envIsProd), yieldsValue = Operand.Unrendered("sprintf(...)"))),
            )
            val pkg = PolicyPackage("p", listOf(rule), listOf("a.rego"))
            val markdown = MarkdownRenderer.renderCard(rule, pkg, PolicySet(listOf(pkg)))
            assertThat(markdown).contains("**Value:** `sprintf(...)`")
            assertThat(markdown).contains("*Rendering coverage: 1 of 1 conditions*")
            assertThat(markdown).doesNotContain("unrendered value")
        }

        @Test
        @DisplayName("CHALLENGE edge case: a body with zero conditions (every expr was an assignment) omits the empty 'All of the following are true' header")
        fun aZeroConditionBodyOmitsTheEmptyHeader() {
            val rule = RuleGroup(
                name = "limit",
                metadata = null,
                default = null,
                bodies = listOf(body(emptyList(), yieldsValue = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field("limit"))))),
            )
            val pkg = PolicyPackage("p", listOf(rule), listOf("a.rego"))
            val markdown = MarkdownRenderer.renderCard(rule, pkg, PolicySet(listOf(pkg)))
            assertThat(markdown).doesNotContain("All of the following are true")
            assertThat(markdown).contains("**Value:** `limit`")
        }

        @Test
        @DisplayName("guard: a body with no yielded value renders no Value line at all (unaffected by this promotion)")
        fun aBodyWithNoYieldedValueRendersNoValueLine() {
            val rule = RuleGroup(name = "flag", metadata = null, default = null, bodies = listOf(body(listOf(envIsProd))))
            val pkg = PolicyPackage("p", listOf(rule), listOf("a.rego"))
            val markdown = MarkdownRenderer.renderCard(rule, pkg, PolicySet(listOf(pkg)))
            assertThat(markdown).doesNotContain("**Value:**")
        }
    }

    @Nested
    @DisplayName("isLinkable (follow-up to review item 18, e.g. inside a diff report)")
    inner class LinkabilityOfCardReferences {

        private val targetPkg = PolicyPackage("other", listOf(RuleGroup("target", null, null, listOf(body(listOf(envIsProd))))), listOf("a.rego"))

        private fun cardReferencing(reference: Condition.RuleReference, isLinkable: (String, String) -> Boolean): String {
            val rule = RuleGroup("source", null, null, listOf(body(listOf(reference))))
            val pkg = PolicyPackage("p", listOf(rule), listOf("a.rego"))
            return MarkdownRenderer.renderCard(rule, pkg, PolicySet(listOf(pkg, targetPkg)), isLinkable = isLinkable)
        }

        @Test
        fun defaultIsLinkableIsAlwaysTrueSoAnOrdinaryRenderIsUnaffected() {
            val card = cardReferencing(Condition.RuleReference("other", "target", negated = false), isLinkable = { _, _ -> true })
            assertThat(card).contains("[`target`](other.md#other-target)")
        }

        @Test
        @DisplayName("CHALLENGE edge case: when isLinkable says the target has no card here, the reference is kept but unlinked")
        fun unlinkableTargetRendersWithoutALink() {
            val card = cardReferencing(Condition.RuleReference("other", "target", negated = false), isLinkable = { _, _ -> false })
            assertThat(card).doesNotContain("](").contains("`target`")
        }

        @Test
        @DisplayName("isLinkable is checked per (packagePath, ruleName), not globally -- one target can be linkable while another in the same card is not")
        fun isLinkableIsCheckedPerTarget() {
            val linkOnlyToOther = { pkg: String, _: String -> pkg == "other" }
            val samePkgRule = RuleGroup("source", null, null, listOf(body(listOf(Condition.RuleReference("p", "sibling", negated = false)))))
            val pkg = PolicyPackage("p", listOf(samePkgRule, RuleGroup("sibling", null, null, listOf(body(listOf(envIsProd))))), listOf("a.rego"))
            val card = MarkdownRenderer.renderCard(samePkgRule, pkg, PolicySet(listOf(pkg, targetPkg)), isLinkable = linkOnlyToOther)
            assertThat(card).doesNotContain("](").contains("`sibling`")
        }
    }
}
