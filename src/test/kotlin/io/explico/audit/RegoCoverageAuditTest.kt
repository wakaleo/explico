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
        ExpressionRenderer.render(
            condition,
            anchorFor = { ref -> "#${ref.packagePath}-${ref.ruleName}" },
            functionAnchorFor = { call -> "#${call.packagePath}-${call.functionName}" },
            ruleValueAnchorFor = { ref -> "#${ref.packagePath}-${ref.ruleName}" },
        )

    private fun assertUnrendered(condition: Condition, expectedReason: String) {
        assertThat(condition).isInstanceOf(Condition.Unrendered::class.java)
        assertThat((condition as Condition.Unrendered).reason).isEqualTo(expectedReason)
    }

    @Test
    fun nullLiteralComparisonNowRendersFaithfullyPromotedThisSession() {
        // Promoted (spec §14 backlog rank #1): `null` maps to Operand.Literal("null") -- trivial,
        // just never added before now.
        val conditions = conditionsOf(loadProbe("01-null-literal.rego"), "deny")
        assertThat(conditions).hasSize(1)
        assertThat(render(conditions.single())).isEqualTo("`change ▸ author` is `null`")
    }

    @Test
    fun objectLiteralOperandNowRendersFaithfullyPromotedThisSession() {
        // Promoted (spec §14 backlog rank #1): a small flat object literal (≤5 pairs, string keys,
        // scalar values) renders with opa's own already-sorted key order -- the source wrote
        // {"author": ..., "approved": ...} but opa's AST lists "approved" before "author"
        // (alphabetical), confirmed via a real `opa parse` run; this mapper never re-sorts anything
        // itself.
        val conditions = conditionsOf(loadProbe("02-object-literal-operand.rego"), "deny")
        assertThat(conditions).hasSize(1)
        assertThat(render(conditions.single()))
            .isEqualTo("`change` is `{\"approved\": true, \"author\": \"asmith\"}`")
    }

    @Test
    fun localVarAssignThenCompareNowSubstitutesInlinePromotedThisSession() {
        // Promoted (spec §5/§14 backlog rank #1): `env := input.deployment.environment` no longer
        // produces its own fallback bullet -- it's registered as a Substitution binding and
        // disappears entirely, with `env` substituted for the real path wherever it's used later.
        val conditions = conditionsOf(loadProbe("03-var-assign-then-compare.rego"), "deny")
        assertThat(conditions).hasSize(1)
        assertThat(render(conditions.single())).isEqualTo("`deployment ▸ environment` is `\"production\"`")
    }

    @Test
    fun unificationDestructureFallsBackForTheDestructureButRendersTheFollowUpComparison() {
        // Still NOT promoted, deliberately: `[x, y] = [...]` binds fresh x/y (destructuring, not
        // comparison) -- the left side maps to Operand.Unrendered (a non-scalar array literal),
        // which the `=`-promotion (spec §14) treats as a positive signal a binding may be in play,
        // so the whole condition stays in its pre-existing "function-call" fallback.
        val conditions = conditionsOf(loadProbe("04-unification-destructure.rego"), "deny")
        assertThat(conditions).hasSize(2)
        assertUnrendered(conditions[0], "function-call")
        assertThat(render(conditions[1])).isEqualTo("`x` is `y`")
    }

    @Test
    fun unificationUsedAsPureComparisonNowRendersFaithfullyPromotedThisSession() {
        // Promoted (spec §14 backlog rank #1): `=` desugars to operator name "eq", not "equal"
        // (which `==` produces). Both sides here are plain, already-real paths -- no fresh variable
        // is introduced anywhere in the expression -- so this is genuinely a pure equality test,
        // not destructuring, and renders identically to `==`.
        val conditions = conditionsOf(loadProbe("05-unification-as-comparison.rego"), "deny")
        assertThat(conditions).hasSize(1)
        assertThat(render(conditions.single())).isEqualTo("`change ▸ author` is `change ▸ approver`")
    }

    @Test
    fun negatedComparisonNowRendersTheActualNegatedMeaningFixedThisSession() {
        // Fixed (spec §14 amendment): `not input.a == input.b` is real, valid Rego (negated: true,
        // terms name still "equal") that previously silently rendered the POSITIVE form -- a
        // genuine MISLEADING finding, discovered while implementing the `=` promotion above.
        // Condition.Comparison now carries `negated`, so this renders "is not", not "is".
        val conditions = conditionsOf(loadProbe("41-negated-comparison.rego"), "deny")
        assertThat(conditions).hasSize(1)
        assertThat(render(conditions.single())).isEqualTo("`change ▸ author` is not `change ▸ approver`")
    }

    @Test
    fun someKeyValueInObjectNowRendersFaithfullyPromotedThisSession() {
        // Promoted (spec §14 backlog rank #1): `some k, v in obj` desugars to internal.member_3
        // and binds v as an iteration variable over the collection, like the single-variable form --
        // later bare uses of v extend the collection's breadcrumb with "[each v]". The KEY variable k
        // is NOT an element of the collection (it is the key itself), so a bare use renders as the
        // plain variable `k`, introduced by the "for some k, v in ..." bullet; the earlier
        // "[each k]" rendering claimed the value at k where the policy tests the key (fixed
        // alongside comprehension rendering, where an object comprehension's key exposed it).
        val conditions = conditionsOf(loadProbe("06-some-kv-in-object.rego"), "deny")
        assertThat(conditions).hasSize(3)
        assertThat(render(conditions[0])).isEqualTo("for some k, v in `change ▸ metadata`")
        assertThat(render(conditions[1])).isEqualTo("`k` is `\"signed_off_by\"`")
        assertThat(render(conditions[2])).isEqualTo("`change ▸ metadata ▸ [each v]` is `\"\"`")
    }

    @Test
    fun someDeclareMultipleWithoutInFallsBackSafelyNoCrash() {
        // `arr := input.change.approvals` is now promoted (spec §14 backlog rank #1) and disappears
        // from the conditions list -- but `arr[i]`/`arr[j]` still fall back, since `i`/`j` themselves
        // stay unbound (`some i, j` with no `in` never registers them as iteration variables).
        val conditions = conditionsOf(loadProbe("07-some-declare-multiple-no-in.rego"), "deny")
        assertThat(conditions).hasSize(4)
        assertUnrendered(conditions[0], "unclassified")
        assertThat(render(conditions[1])).isEqualTo("`arr[i].role` is `\"release-manager\"`")
        assertThat(render(conditions[2])).isEqualTo("`arr[j].role` is `\"security\"`")
        assertThat(render(conditions[3])).isEqualTo("`i` is not `j`")
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
    fun refHeadRuleReferenceNowRendersAsAHumanizedBreadcrumbPromotedThisSession() {
        // Promoted (spec §14 backlog rank #1, scoped to "exact full-path match" only): opa gives
        // `fruit.apple.seeds := 12` head.name == null (only head.ref, the full dotted path, is
        // populated) -- confirmed via a real `opa parse` run. A reference elsewhere that spells out
        // the SAME full path now resolves against the ref-head registry and humanizes exactly like
        // any other Field-segment path, rather than staying raw verbatim source.
        val conditions = conditionsOf(loadProbe("13-ref-head-rule.rego"), "deny")
        assertThat(conditions).hasSize(1)
        assertThat(render(conditions.single())).isEqualTo("`fruit ▸ apple ▸ seeds` is greater than `10`")
    }

    @Test
    fun variableInRuleHeadReferenceStaysUnrenderedNotPromoted() {
        // Deliberately NOT promoted (operator's explicit scoping choice): `users_by_role[role][id]`
        // has head.ref == [var(users_by_role), var(role), var(id)] -- confirmed via a real
        // `opa parse` run -- the 2nd/3rd segments are the rule's OWN local variables (dynamic keys),
        // not literal path segments, so there's no single static "full path" to register or match
        // against. buildRefHeadRuleRegistry's own type filter (every ref segment after the root must
        // be "string", never "var") excludes this rule naturally, with no separate check needed.
        val conditions = conditionsOf(loadProbe("14-var-in-rule-head-ref.rego"), "deny")
        assertThat(conditions).hasSize(1)
        assertThat(render(conditions.single())).isEqualTo("`users_by_role.admin.u1.name` is `\"asmith\"`")
    }

    @Test
    fun userDefinedFunctionCallInConditionPositionRendersAsALinkedCallPromotedThisSession() {
        // Was: fell back to a verbatim Condition.Unrendered -- review item 8 promotes a
        // same-package user-defined function call to Condition.FunctionCall, linked to the
        // function's own card, with its argument humanized the same way any other operand is.
        val conditions = conditionsOf(loadProbe("15-user-defined-function-args.rego"), "deny")
        assertThat(conditions).hasSize(2)
        val call = conditions[0] as Condition.FunctionCall
        assertThat(call.functionName).isEqualTo("is_production_like")
        assertThat(render(conditions[0])).contains("is_production_like").contains("deployment ▸ environment")
        assertThat(render(conditions[1])).isEqualTo("`change ▸ ticket ▸ approved` is absent or false")
    }

    @Test
    fun functionOverloadingRendersEachBodyIndependently() {
        // Function overloading needs no special AstMapper handling: multiple bodies for the same
        // name is exactly the pre-existing incremental-definitions mechanism.
        //
        // `severity_of(input.finding.level) >= 2` is an OPERAND-position call (the function
        // returns a value compared against 2). Coverage-audit rank 4 promoted it to
        // Operand.FunctionCall: a link to the function's own card plus its humanized argument,
        // where it used to stay a raw, visually-path-like Operand.Unrendered echo.
        val policySet = loadProbe("16-function-overloading.rego")
        val denyConditions = conditionsOf(policySet, "deny")
        assertThat(render(denyConditions.single())).isEqualTo(
            "result of function [`severity_of`](#probes.p16_function_overloading-severity_of) for `finding ▸ level` is at least `2`",
        )
        // `level` is a declared function parameter -- another review item 8 fix. Before, it was
        // never recognised as bound at all, so it silently rendered as a bare, visually-identical-
        // to-a-real-path Operand.Unrendered echo of its own source text (the exact "operand
        // fallback is visually indistinguishable from a real breadcrumb" risk CLAUDE.md's §14
        // review already flagged, just not yet connected to parameters specifically). Now
        // correctly identified and labelled.
        assertThat(render(conditionsOf(policySet, "severity_of", 0).single())).isEqualTo("parameter `level` is `\"critical\"`")
        assertThat(render(conditionsOf(policySet, "severity_of", 1).single())).isEqualTo("parameter `level` is `\"high\"`")
        assertThat(render(conditionsOf(policySet, "severity_of", 2).single()))
            .isEqualTo("parameter `level` is not one of `\"critical\", \"high\"`")
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
    fun countOfAPlainPathNowRendersFaithfullyPromotedThisSession() {
        // Promoted (spec §14): count(x) with a plain-path argument renders via Operand.BuiltinCall's
        // "the number of X" template. Review item 15 then gave the exact `count(x) == 0` emptiness
        // idiom this probe is named for its own phrase: "`x` is empty".
        val conditions = conditionsOf(loadProbe("23-count-emptiness-idiom-plain-path.rego"), "deny")
        assertThat(conditions).hasSize(1)
        assertThat(conditions.single()).isInstanceOf(Condition.Comparison::class.java)
        assertThat(render(conditions.single())).isEqualTo("`change ▸ approvals` is empty")
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
    fun arithmeticOperandNowRendersFaithfullyPromotedThisSession() {
        // Promoted (spec §14 backlog rank #1): `plus` (opa's own operator name for `+`) renders via
        // an infix-prose template, humanizing the path operand rather than leaving it as raw source.
        val conditions = conditionsOf(loadProbe("25-arithmetic-operand.rego"), "deny")
        assertThat(render(conditions.single()))
            .isEqualTo("`change ▸ approvals count` plus `1` is greater than `policy ▸ minimum approvals`")
    }

    @Test
    fun stringConcatOperandNowRendersFaithfullyPromotedThisSession() {
        // Promoted (spec §14 backlog rank #1): a literal array/set of joined elements explodes into
        // one operand per element, each humanized independently and comma-joined at render time.
        val conditions = conditionsOf(loadProbe("26-string-concat-operand.rego"), "deny")
        assertThat(render(conditions.single()))
            .isEqualTo("`change ▸ namespace`, `change ▸ name` joined with `\"/\"` is `change ▸ full name`")
    }

    @Test
    fun concatOfAWholeCollectionReferenceRendersAsASingleJoinedOperand() {
        // A path/var referencing an existing collection (concat("/", input.change.path_parts), not
        // a literal array) maps as a SINGLE whole-collection operand rather than exploded elements
        // -- both shapes reach the same "concat" promotion, since neither requires guessing.
        val conditions = conditionsOf(loadProbe("42-concat-whole-collection.rego"), "deny")
        assertThat(render(conditions.single()))
            .isEqualTo("`change ▸ path parts` joined with `\"/\"` is `change ▸ full name`")
    }

    @Test
    fun defaultDeclarationIsNoLongerARenderedBodyPromotedThisSession() {
        // Was: opa's own location span for a `default` rule covers only the keyword itself
        // ("default"), not the full "default allow := false" statement, so explico's fallback --
        // while honest about what it received -- was unhelpfully short, AND counted as a Situation
        // it isn't (review item 3). Fixed: `default` no longer becomes a body at all; it populates
        // RuleGroup.default (rendered from its own value's verbatim source, "false") instead, and
        // the rule's one real body is no longer pushed to index 1.
        val policySet = loadProbe("27-default-declaration.rego")
        val rule = policySet.packages.single().rules.single { it.name == "allow" }
        assertThat(rule.bodies).hasSize(1)
        assertThat(rule.default?.rendered).isEqualTo("false")
        val realBody = conditionsOf(policySet, "allow", 0)
        assertThat(render(realBody[0])).isEqualTo("`deployment ▸ environment` is `\"production\"`")
        assertThat(render(realBody[1])).isEqualTo("`change ▸ ticket ▸ approved` is present and not false")
    }

    @Test
    fun defaultFunctionIsNoLongerARenderedBodyPromotedThisSession() {
        // Same fix as the default-rule case above, applied to a default FUNCTION declaration
        // (`default risk_score(_) := 0`) -- effectiveName/mapRuleGroups treat it identically, since
        // a function's parameter list doesn't change how `head.name`/`head.default` are populated.
        val policySet = loadProbe("28-default-function.rego")
        val rule = policySet.packages.single().rules.single { it.name == "risk_score" }
        assertThat(rule.bodies).hasSize(1)
        assertThat(rule.default?.rendered).isEqualTo("0")
        val realBody = conditionsOf(policySet, "risk_score", 0)
        // Was: `finding.severity` (the parameter-rooted ref chain) stayed a raw, un-traced operand
        // here -- this was probe 28's own remaining raw-operand half, now promoted (spec §14.26):
        // `Operand.ParameterPath` renders the parameter and its tail as two distinct breadcrumb
        // spans rather than the bare, un-annotated field chain.
        assertThat(render(realBody.single())).isEqualTo("parameter `finding` ▸ `severity` is `\"critical\"`")
    }

    @Test
    fun arrayComprehensionAsAnOperandNowRendersWhenItsWholeBodyDoes() {
        // Was a whole-condition fallback; comprehensions are rendered when everything inside is.
        val conditions = conditionsOf(loadProbe("29-array-comprehension-raw-operand.rego"), "deny")
        assertThat(conditions).hasSize(1)
        assertThat(render(conditions.single())).isEqualTo(
            "(the list of `pipeline ▸ stages ▸ [each s] ▸ name` such that for some s in `pipeline ▸ stages`; " +
                "`pipeline ▸ stages ▸ [each s] ▸ status` is `\"failed\"`) is `[\"build\"]`",
        )
    }

    @Test
    fun objectComprehensionAsAnOperandNowRendersWhenItsWholeBodyDoes() {
        val conditions = conditionsOf(loadProbe("30-object-comprehension-raw-operand.rego"), "deny")
        assertThat(conditions).hasSize(1)
        assertThat(render(conditions.single())).isEqualTo(
            "(the object mapping `pipeline ▸ stages ▸ [each s] ▸ name` to `pipeline ▸ stages ▸ [each s] ▸ status` " +
                "such that for some s in `pipeline ▸ stages`) is `{}`",
        )
    }

    @Test
    fun compositeValueMembershipFallsBackForTheBindingButRendersTheMembershipCheck() {
        val conditions = conditionsOf(loadProbe("31-composite-value-membership.rego"), "deny")
        assertThat(conditions).hasSize(2)
        // "helper-assignment" (review item 12): `pairs := {...}` is an assignment the mapper
        // can't substitute, distinct from a bare unresolved call's "function-call".
        assertUnrendered(conditions[0], "helper-assignment")
        // Was: `` `1, 2` is one of `pairs` `` -- the MEMBER side (`[1, 2]`) is itself an array
        // literal, not the membership's collection side, so spec §14's promotion (collection
        // literals keep their Rego delimiters outside the one frozen bare-list position, the
        // collection side of a membership test) applies to it like any other operand position.
        // `` `[1, 2]` `` is strictly more faithful than the bare comma list, which read as two
        // separate values rather than one array.
        assertThat(render(conditions[1])).isEqualTo("`[1, 2]` is one of `pairs`")
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
    fun timeNowNsOperandNowRendersFaithfullyPromotedThisSession() {
        // Promoted (spec §14 backlog rank #1): takes no arguments, so the renderer's template is a
        // fixed phrase rather than one built around a rendered argument.
        val conditions = conditionsOf(loadProbe("34-time-now-ns-operand.rego"), "deny")
        assertThat(render(conditions.single())).isEqualTo("the current time is greater than `deployment ▸ timestamp`")
    }

    @Test
    fun objectGetOperandNowRendersFaithfullyPromotedThisSession() {
        // Promoted (spec §14 backlog rank #1): the key extends the object's own breadcrumb as a
        // KeyLiteral, exactly like a real bracket-string path segment would.
        val conditions = conditionsOf(loadProbe("35-object-get-operand.rego"), "deny")
        assertThat(render(conditions.single())).isEqualTo("`change ▸ \"ticket\"` (default `\"none\"`) is `\"none\"`")
    }

    @Test
    fun lowerAndUpperOperandsNowRenderFaithfullyPromotedThisSession() {
        // Promoted (spec §14): both were rank #2 in the promotion backlog (lowest risk, spec §6.3
        // already specified these exact templates).
        val policySet = loadProbe("36-lower-upper-operand.rego")
        assertThat(render(conditionsOf(policySet, "deny", 0).single())).isEqualTo("`change ▸ author` lowercased is `\"asmith\"`")
        assertThat(render(conditionsOf(policySet, "deny", 1).single())).isEqualTo("`deployment ▸ environment` uppercased is `\"PRODUCTION\"`")
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
    fun stringInterpolationFallsBackForTheMessageOnlyNowThatTheEarlierAssignmentIsPromoted() {
        // `x := input.deployment.id` is now promoted (spec §14 backlog rank #1) and disappears --
        // `deny` here is a plain `if` rule (no head key), so `msg` is an ordinary local variable,
        // not this rule's message var; `$"..."` desugars to a new "templatestring" term type
        // explico doesn't model, so its own assignment still safely falls back rather than
        // crashing or guessing.
        val policySet = loadProbe("39-string-interpolation.rego")
        val body = policySet.packages.single().rules.single { it.name == "deny" }.bodies.single()
        assertThat(body.conditions).hasSize(1)
        // "helper-assignment" (review item 12): `msg := $"..."` is an assignment the mapper can't
        // substitute (a templatestring term, not a plain path), distinct from "function-call".
        assertUnrendered(body.conditions.single(), "helper-assignment")
        assertThat(body.producesValue).isNull()
        assertThat(body.messageTemplate).isNull()
    }
}
