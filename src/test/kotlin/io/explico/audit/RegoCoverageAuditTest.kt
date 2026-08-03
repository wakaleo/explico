/**
 * Permanent regression suite for spec §14's Rego-coverage audit. One (or more) assertion per probe
 * file under `src/test/resources/probes/`, each pinned to the classification this session's audit
 * confirmed by actually running it through the real pipeline (`opa parse` -> `AstMapper` ->
 * `ExpressionRenderer`) -- not a synthetic hand-built `Condition`. A future change that silently
 * reclassifies any of these constructs (widens a template, drops a fallback guard, etc.) fails here,
 * not just in a session's own manual review. Requires the `opa` binary, same pattern as
 * `CanonicalizerTest`/`PolicyDiffTest`/`DiffRendererTest` (CLAUDE.md's Test tiers §9 deviation).
 */
package io.explico.audit

import io.explico.Explico
import io.explico.model.Condition
import io.explico.model.PolicySet
import io.explico.opa.OpaRunner
import io.explico.render.ExpressionRenderer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class RegoCoverageAuditTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
        }
    }

    private val probesDir = Path.of("src/test/resources/probes")

    /** Loads exactly one probe file in isolation, so one probe's shape can never affect another's. */
    private fun loadProbe(fileName: String): PolicySet {
        val tmp = Files.createTempDirectory("probe-audit-")
        try {
            Files.copy(probesDir.resolve(fileName), tmp.resolve(fileName))
            return Explico.load(tmp)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    private fun conditionsOf(policySet: PolicySet, ruleName: String, bodyIndex: Int = 0): List<Condition> =
        policySet.packages.single().rules.single { it.name == ruleName }.bodies[bodyIndex].conditions

    private fun render(condition: Condition): String =
        ExpressionRenderer.render(condition) { ref -> "#${ref.packagePath}-${ref.ruleName}" }

    private fun assertUnrendered(condition: Condition, expectedReason: String) {
        assertThat(condition).isInstanceOf(Condition.Unrendered::class.java)
        assertThat((condition as Condition.Unrendered).reason).isEqualTo(expectedReason)
    }

    @Test
    fun nullLiteralComparisonFallsBack() {
        val conditions = conditionsOf(loadProbe("01-null-literal.rego"), "deny")
        assertThat(conditions).hasSize(1)
        assertUnrendered(conditions.single(), "unclassified")
    }

    @Test
    fun objectLiteralOperandComparisonFallsBack() {
        val conditions = conditionsOf(loadProbe("02-object-literal-operand.rego"), "deny")
        assertThat(conditions).hasSize(1)
        assertUnrendered(conditions.single(), "unclassified")
    }

    @Test
    fun localVarAssignThenCompareFallsBackForTheAssignmentButRendersTheComparison() {
        // Disclosed gap (CLAUDE.md, spec §5): the assignment itself becomes its own fallback bullet,
        // and the later `env == "production"` still renders -- with `env` shown as a bare backtick
        // value indistinguishable from a real path. Assertion pins the CURRENT behavior so a future
        // change is a deliberate, reviewed diff here, not a silent drift.
        val conditions = conditionsOf(loadProbe("03-var-assign-then-compare.rego"), "deny")
        assertThat(conditions).hasSize(2)
        assertUnrendered(conditions[0], "function-call")
        assertThat(render(conditions[1])).isEqualTo("`env` is `\"production\"`")
    }

    @Test
    fun unificationDestructureFallsBackForTheDestructureButRendersTheFollowUpComparison() {
        val conditions = conditionsOf(loadProbe("04-unification-destructure.rego"), "deny")
        assertThat(conditions).hasSize(2)
        assertUnrendered(conditions[0], "function-call")
        assertThat(render(conditions[1])).isEqualTo("`x` is `y`")
    }

    @Test
    fun unificationUsedAsPureComparisonFallsBack() {
        // `=` desugars to operator name "eq", not "equal" (which `==` produces) -- COMPARISON_OPERATORS
        // only maps "equal", so `=` is entirely unclassified today, never misclassified as a comparison.
        val conditions = conditionsOf(loadProbe("05-unification-as-comparison.rego"), "deny")
        assertThat(conditions).hasSize(1)
        assertUnrendered(conditions.single(), "function-call")
    }

    @Test
    fun someKeyValueInObjectFallsBackForTheBindingButRendersFollowUpComparisons() {
        val conditions = conditionsOf(loadProbe("06-some-kv-in-object.rego"), "deny")
        assertThat(conditions).hasSize(3)
        assertUnrendered(conditions[0], "unclassified")
        assertThat(render(conditions[1])).isEqualTo("`k` is `\"signed_off_by\"`")
        assertThat(render(conditions[2])).isEqualTo("`v` is `\"\"`")
    }

    @Test
    fun someDeclareMultipleWithoutInFallsBackSafelyNoCrash() {
        val conditions = conditionsOf(loadProbe("07-some-declare-multiple-no-in.rego"), "deny")
        assertThat(conditions).hasSize(5)
        assertUnrendered(conditions[0], "unclassified")
        assertUnrendered(conditions[1], "function-call")
        assertThat(render(conditions[2])).isEqualTo("`arr[i].role` is `\"release-manager\"`")
        assertThat(render(conditions[3])).isEqualTo("`arr[j].role` is `\"security\"`")
        assertThat(render(conditions[4])).isEqualTo("`i` is not `j`")
    }

    @Test
    fun multipleIndependentSomeInBothRenderFaithfully() {
        val conditions = conditionsOf(loadProbe("08-multiple-independent-some-in.rego"), "deny")
        assertThat(conditions).hasSize(4)
        assertThat(render(conditions[0])).isEqualTo("for some stage in `pipeline ▸ stages`")
        assertThat(render(conditions[1])).isEqualTo("for some check in `pipeline ▸ checks`")
        assertThat(render(conditions[2])).isEqualTo("`pipeline ▸ stages ▸ [each stage] ▸ status` is `\"failed\"`")
        assertThat(render(conditions[3])).isEqualTo("`pipeline ▸ checks ▸ [each check] ▸ status` is `\"failed\"`")
    }

    @Test
    fun nestedEveryFallsBackAsOneWholeBlock() {
        val conditions = conditionsOf(loadProbe("09-nested-every.rego"), "deny")
        assertThat(conditions).hasSize(1)
        assertUnrendered(conditions.single(), "every")
    }

    @Test
    fun withOverrideFallsBackInsteadOfHidingItself() {
        // Fixed finding (spec §14): before OpaExpr modeled `with`, this silently rendered as a plain
        // "see rule is_release_candidate" -- actively implying the real input was being tested.
        val conditions = conditionsOf(loadProbe("10-with-override.rego"), "deny")
        assertThat(conditions).hasSize(1)
        assertUnrendered(conditions.single(), "with-override")
    }

    @Test
    fun walkBuiltinFallsBackForBindingAndCallButRendersFollowUpComparison() {
        val conditions = conditionsOf(loadProbe("11-walk-builtin.rego"), "deny")
        assertThat(conditions).hasSize(3)
        assertUnrendered(conditions[0], "unclassified")
        assertUnrendered(conditions[1], "function-call")
        assertThat(render(conditions[2])).isEqualTo("`value` is `\"secret\"`")
    }

    @Test
    fun declareOnlySingleVarSomeNoLongerCrashesAndFallsBackSafely() {
        // The confirmed ERROR finding (spec §14): `some key` (no `in`) used to throw
        // JsonDecodingException and take down the entire render. The follow-up dynamic-ref
        // comparison itself still renders fine once the crash is fixed.
        val conditions = conditionsOf(loadProbe("12-dynamic-ref-root-var.rego"), "deny")
        assertThat(conditions).hasSize(2)
        assertUnrendered(conditions[0], "unclassified")
        assertThat(render(conditions[1])).isEqualTo("`input[key]` is `\"forbidden\"`")
    }

    @Test
    fun refHeadRuleReferenceRendersAsUnhumanizedVerbatimOperand() {
        val conditions = conditionsOf(loadProbe("13-ref-head-rule.rego"), "deny")
        assertThat(conditions).hasSize(1)
        assertThat(render(conditions.single())).isEqualTo("`fruit.apple.seeds` is greater than `10`")
    }

    @Test
    fun variableInRuleHeadReferenceRendersAsUnhumanizedVerbatimOperand() {
        val conditions = conditionsOf(loadProbe("14-var-in-rule-head-ref.rego"), "deny")
        assertThat(conditions).hasSize(1)
        assertThat(render(conditions.single())).isEqualTo("`users_by_role.admin.u1.name` is `\"asmith\"`")
    }

    @Test
    fun userDefinedFunctionCallInConditionPositionFallsBack() {
        val conditions = conditionsOf(loadProbe("15-user-defined-function-args.rego"), "deny")
        assertThat(conditions).hasSize(2)
        assertUnrendered(conditions[0], "function-call")
        assertThat(render(conditions[1])).isEqualTo("`change ▸ ticket ▸ approved` is absent or false")
    }

    @Test
    fun functionOverloadingRendersEachBodyIndependently() {
        // Function overloading needs no special AstMapper handling: multiple bodies for the same
        // name is exactly the pre-existing incremental-definitions mechanism.
        val policySet = loadProbe("16-function-overloading.rego")
        val denyConditions = conditionsOf(policySet, "deny")
        assertThat(render(denyConditions.single())).isEqualTo("`severity_of(input.finding.level)` is at least `2`")
        assertThat(render(conditionsOf(policySet, "severity_of", 0).single())).isEqualTo("`level` is `\"critical\"`")
        assertThat(render(conditionsOf(policySet, "severity_of", 1).single())).isEqualTo("`level` is `\"high\"`")
        assertThat(render(conditionsOf(policySet, "severity_of", 2).single()))
            .isEqualTo("`level` is not one of `\"critical\", \"high\"`")
    }

    @Test
    fun elseChainFallsBackAsOneWholeBlockCoveringEveryBranch() {
        // The confirmed silent-data-loss finding (spec §14): before OpaRule modeled `else`, the
        // whole else-branch simply vanished (ignoreUnknownKeys), and the card showed only the `if`
        // branch as though it were the rule's entire logic. Now demoted to a single Unrendered
        // condition whose source spans the whole chain (opa's own rule.location already does).
        val conditions = conditionsOf(loadProbe("17-else-chain.rego"), "verdict")
        assertThat(conditions).hasSize(1)
        assertUnrendered(conditions.single(), "else-chain")
        val source = (conditions.single() as Condition.Unrendered).sourceText
        assertThat(source).contains("\"blocked\"").contains("\"allowed\"").contains("else")
    }

    @Test
    fun partialObjectRuleWithPlainStringValueRendersFaithfully() {
        val policySet = loadProbe("18-partial-object-rule.rego")
        val rule = policySet.packages.single().rules.single { it.name == "deny_severity" }
        val body = rule.bodies.single()
        assertThat(render(body.conditions[0])).isEqualTo("`deployment ▸ environment` is `\"production\"`")
        assertThat(render(body.conditions[1])).isEqualTo("`change ▸ ticket ▸ approved` is absent or false")
        assertThat(body.producesValue).isEqualTo("no approved change ticket")
    }

    @Test
    fun structuredObjectValuedMessageRendersConditionsButProducesNoMessage() {
        // An object-valued message isn't a string or an sprintf call -- producesValue/messageTemplate
        // both gracefully stay null (no crash, no guessed rendering of the object literal).
        val policySet = loadProbe("19-structured-message-object.rego")
        val body = policySet.packages.single().rules.single { it.name == "deny" }.bodies.single()
        assertThat(render(body.conditions[0])).isEqualTo("`deployment ▸ environment` is `\"production\"`")
        assertThat(render(body.conditions[1])).isEqualTo("`change ▸ ticket ▸ approved` is absent or false")
        assertThat(body.producesValue).isNull()
    }

    @Test
    fun negatedPartialSetRuleReferenceFallsBackInsteadOfClaimingItDoesNotMatch() {
        // The confirmed MISLEADING finding (spec §14, empirically verified via real `opa eval`): a
        // partial rule is always defined (even empty), so `not partialRule` can never succeed --
        // "does not match" would describe permanently dead logic as a working conditional.
        val conditions = conditionsOf(loadProbe("20-not-over-partial-set-rule.rego"), "deny")
        assertThat(conditions).hasSize(1)
        assertUnrendered(conditions.single(), "partial-rule-reference")
    }

    @Test
    fun nonNegatedPartialSetRuleReferenceAlsoFallsBack() {
        val conditions = conditionsOf(loadProbe("40-non-negated-partial-set-rule-reference.rego"), "deny")
        assertThat(conditions).hasSize(1)
        assertUnrendered(conditions.single(), "partial-rule-reference")
    }

    @Test
    fun negatedCompleteRuleReferenceWithDefaultStillRendersFaithfully() {
        // Contrast case for the finding above: a COMPLETE rule (even with a `default`) behaves
        // exactly as "does not match" implies -- undefined and explicit-false are both captured.
        val conditions = conditionsOf(loadProbe("21-not-over-complete-rule-with-default.rego"), "deny")
        assertThat(conditions).hasSize(1)
        assertThat(conditions.single()).isInstanceOf(Condition.RuleReference::class.java)
        assertThat(render(conditions.single())).contains("does not match")
    }

    @Test
    fun neqComparisonRendersConsistentlyWithTheAlreadyAcceptedEqConvention() {
        val conditions = conditionsOf(loadProbe("22-neq-possibly-undefined.rego"), "deny")
        assertThat(conditions).hasSize(1)
        assertThat(render(conditions.single())).isEqualTo("`change ▸ author` is not `change ▸ approver`")
    }

    @Test
    fun countOfAPlainPathStillRendersAsAComparisonWithAnUnhumanizedOperand() {
        // Promotion-backlog candidate: the Condition itself renders (not a whole-condition fallback),
        // but count(...) itself is still Operand.Unrendered -- no Operand variant exists for it yet.
        val conditions = conditionsOf(loadProbe("23-count-emptiness-idiom-plain-path.rego"), "deny")
        assertThat(conditions).hasSize(1)
        assertThat(conditions.single()).isInstanceOf(Condition.Comparison::class.java)
        assertThat(render(conditions.single())).isEqualTo("`count(input.change.approvals)` is `0`")
    }

    @Test
    fun setAndArrayMembershipRenderIdenticallyAndFaithfully() {
        val policySet = loadProbe("24-set-vs-array-membership.rego")
        assertThat(render(conditionsOf(policySet, "deny", 0).single()))
            .isEqualTo("`deployment ▸ environment` is one of `\"production\", \"staging\"`")
        assertThat(render(conditionsOf(policySet, "deny", 1).single()))
            .isEqualTo("`change ▸ approver` is one of `change ▸ previous approvers`")
    }

    @Test
    fun arithmeticOperandRendersAsUnhumanizedVerbatimSource() {
        val conditions = conditionsOf(loadProbe("25-arithmetic-operand.rego"), "deny")
        assertThat(render(conditions.single()))
            .isEqualTo("`input.change.approvals_count + 1` is greater than `policy ▸ minimum approvals`")
    }

    @Test
    fun stringConcatOperandRendersAsUnhumanizedVerbatimSource() {
        val conditions = conditionsOf(loadProbe("26-string-concat-operand.rego"), "deny")
        assertThat(render(conditions.single()))
            .isEqualTo("`concat(\"/\", [input.change.namespace, input.change.name])` is `change ▸ full name`")
    }

    @Test
    fun defaultDeclarationFallsBackWithOpasOwnTruncatedLocationSpan() {
        // Disclosed opa quirk (spec §14): opa's own location span for a `default` rule covers only
        // the keyword itself, not the full "default allow := false" statement -- explico's fallback
        // is honest about what it received, but the received text is itself unhelpfully short.
        val policySet = loadProbe("27-default-declaration.rego")
        val defaultBody = policySet.packages.single().rules.single { it.name == "allow" }.bodies[0]
        assertUnrendered(defaultBody.conditions.single(), "unclassified")
        assertThat((defaultBody.conditions.single() as Condition.Unrendered).sourceText).isEqualTo("default")
        val realBody = conditionsOf(policySet, "allow", 1)
        assertThat(render(realBody[0])).isEqualTo("`deployment ▸ environment` is `\"production\"`")
        assertThat(render(realBody[1])).isEqualTo("`change ▸ ticket ▸ approved` is present and not false")
    }

    @Test
    fun defaultFunctionFallsBackTheSameWayAsADefaultRule() {
        val policySet = loadProbe("28-default-function.rego")
        val defaultBody = policySet.packages.single().rules.single { it.name == "risk_score" }.bodies[0]
        assertUnrendered(defaultBody.conditions.single(), "unclassified")
        val realBody = conditionsOf(policySet, "risk_score", 1)
        assertThat(render(realBody.single())).isEqualTo("`finding.severity` is `\"critical\"`")
    }

    @Test
    fun arrayComprehensionAsRawOperandFallsBackTheWholeCondition() {
        val conditions = conditionsOf(loadProbe("29-array-comprehension-raw-operand.rego"), "deny")
        assertThat(conditions).hasSize(1)
        assertUnrendered(conditions.single(), "comprehension")
    }

    @Test
    fun objectComprehensionAsRawOperandFallsBackTheWholeCondition() {
        val conditions = conditionsOf(loadProbe("30-object-comprehension-raw-operand.rego"), "deny")
        assertThat(conditions).hasSize(1)
        assertUnrendered(conditions.single(), "comprehension")
    }

    @Test
    fun compositeValueMembershipFallsBackForTheBindingButRendersTheMembershipCheck() {
        val conditions = conditionsOf(loadProbe("31-composite-value-membership.rego"), "deny")
        assertThat(conditions).hasSize(2)
        assertUnrendered(conditions[0], "function-call")
        assertThat(render(conditions[1])).isEqualTo("`1, 2` is one of `pairs`")
    }

    @Test
    fun globMatchRendersFaithfullyAgainstRealOpaOutput() {
        // Closes a previously-disclosed gap (CLAUDE.md session 4): only ever tested against
        // synthetic JSON before this probe, never real `opa parse` output.
        val conditions = conditionsOf(loadProbe("32-glob-match-real.rego"), "deny")
        assertThat(render(conditions.single())).isEqualTo("`artifact ▸ source branch` matches glob `\"release/*\"`")
    }

    @Test
    fun regexMatchRendersFaithfullyAgainstRealOpaOutput() {
        val conditions = conditionsOf(loadProbe("33-regex-match-real.rego"), "deny")
        assertThat(render(conditions.single())).isEqualTo("`deployment ▸ id` matches pattern `\"^rel-[0-9]+$\"`")
    }

    @Test
    fun timeNowNsOperandRendersAsUnhumanizedVerbatimSource() {
        val conditions = conditionsOf(loadProbe("34-time-now-ns-operand.rego"), "deny")
        assertThat(render(conditions.single())).isEqualTo("`time.now_ns()` is greater than `deployment ▸ timestamp`")
    }

    @Test
    fun objectGetOperandRendersAsUnhumanizedVerbatimSource() {
        val conditions = conditionsOf(loadProbe("35-object-get-operand.rego"), "deny")
        assertThat(render(conditions.single())).isEqualTo("`object.get(input.change, \"ticket\", \"none\")` is `\"none\"`")
    }

    @Test
    fun lowerAndUpperOperandsRenderAsUnhumanizedVerbatimSource() {
        val policySet = loadProbe("36-lower-upper-operand.rego")
        assertThat(render(conditionsOf(policySet, "deny", 0).single())).isEqualTo("`lower(input.change.author)` is `\"asmith\"`")
        assertThat(render(conditionsOf(policySet, "deny", 1).single())).isEqualTo("`upper(input.deployment.environment)` is `\"PRODUCTION\"`")
    }

    @Test
    fun countBareInConditionPositionFallsBackPerSpec() {
        val conditions = conditionsOf(loadProbe("37-count-condition-position.rego"), "deny")
        assertThat(conditions).hasSize(1)
        assertUnrendered(conditions.single(), "function-call")
    }

    @Test
    fun truthyOnANonBooleanFieldRendersTheFixedTypeAgnosticWording() {
        // The confirmed MISLEADING finding (spec §14 amendment): "is true" (original §6.3 wording)
        // was only ever exercised via negation over a boolean flag; a non-negated bare string field
        // makes it flatly wrong. Fixed to the type-agnostic mirror of the negated case.
        val conditions = conditionsOf(loadProbe("38-truthy-non-boolean-field.rego"), "deny")
        assertThat(conditions).hasSize(1)
        assertThat(render(conditions.single())).isEqualTo("`change ▸ author` is present and not false")
    }

    @Test
    fun stringInterpolationFallsBackForBothTheBindingAndTheMessage() {
        // `$"..."` desugars to a new "templatestring" term type explico doesn't model -- safely
        // falls back rather than crashing or guessing; the message stays silently absent (same as
        // any other unsupported message shape), not a regression specific to this construct.
        val policySet = loadProbe("39-string-interpolation.rego")
        val body = policySet.packages.single().rules.single { it.name == "deny" }.bodies.single()
        assertThat(body.conditions).hasSize(2)
        assertUnrendered(body.conditions[0], "function-call")
        assertUnrendered(body.conditions[1], "function-call")
        assertThat(body.producesValue).isNull()
        assertThat(body.messageTemplate).isNull()
    }
}
