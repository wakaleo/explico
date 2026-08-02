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

    private fun body(conditions: List<Condition>, producesValue: String? = null, messageTemplate: String? = null, row: Int = 16, file: String = "approvals/change_approval.rego") =
        RuleBody(conditions, producesValue, messageTemplate, SourceRef(file, row))

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
            val withDefault = RuleGroup("allow", metadata = null, default = DefaultValue("false"), bodies = listOf(body(listOf(envIsProd))))
            val withoutDefault = RuleGroup("deny", metadata = null, default = null, bodies = listOf(body(listOf(envIsProd))))
            val pkg = PolicyPackage("release.approvals", listOf(withDefault, withoutDefault), listOf("approvals/change_approval.rego"))

            assertThat(MarkdownRenderer.renderCard(withDefault, pkg, PolicySet(listOf(pkg)))).contains("**Default outcome:** false")
            assertThat(MarkdownRenderer.renderCard(withoutDefault, pkg, PolicySet(listOf(pkg)))).doesNotContain("Default outcome")
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

            assertThat(markdown).contains("| Control ID | Title | Package | Rule | Coverage | Source file |")
            assertThat(markdown).contains("Overall rendering coverage: 0 of 0 conditions (100%)")
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
    }
}
