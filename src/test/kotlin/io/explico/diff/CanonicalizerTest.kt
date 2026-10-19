/**
 * Property tests for Canonicalizer (spec §7.1), driven by real `.rego` variants under
 * `src/test/resources/diff/canonicalizer/` parsed through the real `opa` binary --
 * not synthetic ASTs -- so the hash-stability invariants hold against actual parser
 * output, not just against however AstMapper happens to be exercised elsewhere.
 * Requires `opa` on PATH, same pattern as OpaRunnerSmokeTest.
 */
package io.explico.diff

import io.explico.Explico
import io.explico.model.RuleGroup
import io.explico.opa.OpaRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path

class CanonicalizerTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
        }
    }

    private val fixturesRoot = Path.of("src/test/resources/diff/canonicalizer")

    private fun denyRule(variant: String): RuleGroup {
        val policySet = Explico.load(fixturesRoot.resolve(variant))
        return policySet.packages.single().rules.single { it.name == "deny" }
    }

    private fun functionRule(variant: String): RuleGroup {
        val policySet = Explico.load(fixturesRoot.resolve(variant))
        return policySet.packages.single().rules.single { it.name == "f" }
    }

    @Test
    fun positionalVariableRenameYieldsTheSameLogicHash() {
        val base = denyRule("base")
        val renamed = denyRule("renamed-vars")
        assertThat(Canonicalizer.logicHash(renamed)).isEqualTo(Canonicalizer.logicHash(base))
    }

    @Test
    fun reformattingAndCommentEditsYieldTheSameLogicHash() {
        val base = denyRule("base")
        val reformatted = denyRule("reformatted")
        assertThat(Canonicalizer.logicHash(reformatted)).isEqualTo(Canonicalizer.logicHash(base))
    }

    @Test
    fun aFileGenuinelyReformattedByRealOpaFmtYieldsTheSameLogicHash() {
        // spec §12's acceptance criterion names `opa fmt` specifically -- the "reformatted"
        // fixture above is hand-edited, not opa's own output. This fixture is the real output of
        // `opa fmt -w` run against a mangled copy of base/sample.rego (mixed spaces/tabs, a
        // relocated comment); checked in once rather than shelling out to `opa fmt` at test time,
        // since the point is proving the hash is stable against a real opa-fmt diff, not
        // re-proving opa fmt itself works.
        val base = denyRule("base")
        val opaFmtApplied = denyRule("opa-fmt-applied")
        assertThat(Canonicalizer.logicHash(opaFmtApplied)).isEqualTo(Canonicalizer.logicHash(base))
    }

    @Test
    fun anOperandChangeYieldsADifferentLogicHash() {
        val base = denyRule("base")
        val operandChanged = denyRule("operand-changed")
        assertThat(Canonicalizer.logicHash(operandChanged)).isNotEqualTo(Canonicalizer.logicHash(base))
    }

    @Test
    fun anOperatorChangeYieldsADifferentLogicHash() {
        val base = denyRule("base")
        val operatorChanged = denyRule("operator-changed")
        assertThat(Canonicalizer.logicHash(operatorChanged)).isNotEqualTo(Canonicalizer.logicHash(base))
    }

    @Test
    fun addingABodyYieldsADifferentLogicHash() {
        val base = denyRule("base")
        val bodyAdded = denyRule("body-added")
        assertThat(Canonicalizer.logicHash(bodyAdded)).isNotEqualTo(Canonicalizer.logicHash(base))
    }

    @Test
    fun aMetadataOnlyEditYieldsTheSameLogicHashButADifferentMetadataHash() {
        val base = denyRule("base")
        val metadataChanged = denyRule("metadata-changed")
        assertThat(Canonicalizer.logicHash(metadataChanged)).isEqualTo(Canonicalizer.logicHash(base))
        assertThat(Canonicalizer.metadataHash(metadataChanged)).isNotEqualTo(Canonicalizer.metadataHash(base))
    }

    @Test
    fun metadataHashIsStableAndDeterministicAcrossRepeatedCalls() {
        val base = denyRule("base")
        assertThat(Canonicalizer.metadataHash(base)).isEqualTo(Canonicalizer.metadataHash(base))
    }

    @Test
    fun aProducedMessageChangeYieldsADifferentLogicHashEvenWithIdenticalConditions() {
        // Conditions are byte-for-byte identical between base and message-changed; only the
        // produced message text differs. The message is part of a rule's logic (spec §7.2's
        // DOCS_CHANGED is specifically about RuleMetadata, never about a produced value), so this
        // must be LOGIC_CHANGED, not DOCS_CHANGED or UNCHANGED.
        val base = denyRule("base")
        val messageChanged = denyRule("message-changed")
        assertThat(Canonicalizer.logicHash(messageChanged)).isNotEqualTo(Canonicalizer.logicHash(base))
    }

    @Test
    @DisplayName("CHALLENGE edge case (follow-up to review item 10): a conditional rule's yielded value is part of its logic -- \"high\" -> \"medium\" with identical conditions changes the hash")
    fun aYieldedValueChangeYieldsADifferentLogicHashEvenWithIdenticalConditions() {
        val base = denyRule("conditional-value-base")
        val changed = denyRule("conditional-value-changed")
        assertThat(Canonicalizer.logicHash(changed)).isNotEqualTo(Canonicalizer.logicHash(base))
    }

    @Test
    @DisplayName("CHALLENGE edge case: renaming a variable whose assignment is substituted inline leaves the logic hash unchanged -- the variable never reaches the model")
    fun renamingAnExpressionAssignedVariableYieldsTheSameLogicHash() {
        val base = denyRule("expression-assign-base")
        val renamed = denyRule("expression-assign-renamed")
        assertThat(Canonicalizer.logicHash(renamed)).isEqualTo(Canonicalizer.logicHash(base))
    }

    @Test
    @DisplayName("CHALLENGE edge case: changing an operand inside a substituted expression changes the logic hash")
    fun changingAnOperandInsideASubstitutedExpressionYieldsADifferentLogicHash() {
        val base = denyRule("expression-assign-base")
        val changed = denyRule("expression-assign-operand-changed")
        assertThat(Canonicalizer.logicHash(changed)).isNotEqualTo(Canonicalizer.logicHash(base))
    }

    @Test
    @DisplayName("CHALLENGE edge case (coverage audit rank 4): an operand-position function call hashes identically when its argument arrives via a substituted local variable")
    fun aFunctionCallArgumentViaASubstitutedLocalVariableYieldsTheSameLogicHash() {
        assertThat(Canonicalizer.logicHash(denyRule("function-call-var-argument"))).isEqualTo(Canonicalizer.logicHash(denyRule("function-call-base")))
    }

    @Test
    @DisplayName("CHALLENGE edge case (coverage audit rank 4): changing the called function, or its argument, changes the logic hash")
    fun changingTheCalledFunctionOrItsArgumentYieldsADifferentLogicHash() {
        val base = Canonicalizer.logicHash(denyRule("function-call-base"))
        assertThat(Canonicalizer.logicHash(denyRule("function-call-name-changed"))).isNotEqualTo(base)
        assertThat(Canonicalizer.logicHash(denyRule("function-call-argument-changed"))).isNotEqualTo(base)
    }

    @Test
    @DisplayName("spec §14 promotion: an array and a set literal of the SAME elements hash differently -- they are different Rego values")
    fun anArrayAndASetLiteralOfTheSameElementsYieldDifferentLogicHashes() {
        assertThat(Canonicalizer.logicHash(denyRule("literal-collection-array-same-elements")))
            .isNotEqualTo(Canonicalizer.logicHash(denyRule("literal-collection-set-base")))
    }

    @Test
    @DisplayName("spec §14 promotion: CHALLENGE edge case -- reformatting around a set literal (whitespace, a comment) yields the same logic hash")
    fun reformattingAroundASetLiteralYieldsTheSameLogicHash() {
        assertThat(Canonicalizer.logicHash(denyRule("literal-collection-set-reformatted")))
            .isEqualTo(Canonicalizer.logicHash(denyRule("literal-collection-set-base")))
    }

    @Test
    @DisplayName("spec §14.26: renaming a function parameter used as a ref-chain root yields the same logic hash")
    fun renamingAParameterRootedPathsParameterYieldsTheSameLogicHash() {
        assertThat(Canonicalizer.logicHash(functionRule("parameter-path-renamed-parameter")))
            .isEqualTo(Canonicalizer.logicHash(functionRule("parameter-path-base")))
    }

    @Test
    @DisplayName("spec §14.26: CHALLENGE edge case -- changing the field after a parameter-rooted path changes the logic hash")
    fun changingAParameterRootedPathsTailFieldYieldsADifferentLogicHash() {
        assertThat(Canonicalizer.logicHash(functionRule("parameter-path-tail-changed")))
            .isNotEqualTo(Canonicalizer.logicHash(functionRule("parameter-path-base")))
    }

    @Test
    fun reformattingInsideAnUnrenderedFallbackSpanYieldsADifferentLogicHash() {
        // Deliberate, disclosed behaviour, not a bug: an Unrendered condition/operand's sourceText
        // is verbatim source the tool couldn't classify, so it can't verify two differently
        // formatted spans mean the same thing -- unlike a classified construct, where formatting
        // is invisible because only the parsed structure is hashed. An `every` block is such a
        // construct (comprehensions used to be, until they were rendered): reformatting purely
        // inside its whitespace changes the hash.
        val fallbackBase = denyRule("fallback-base")
        val fallbackReformatted = denyRule("fallback-reformatted")
        assertThat(Canonicalizer.logicHash(fallbackReformatted)).isNotEqualTo(Canonicalizer.logicHash(fallbackBase))
    }

    @Test
    @DisplayName("comprehensions: reformatting and a comment change nothing; renaming its variables changes nothing")
    fun aRenderedComprehensionHashesStructurallyNotBySourceText() {
        val base = Canonicalizer.logicHash(denyRule("comprehension-base"))
        assertThat(Canonicalizer.logicHash(denyRule("comprehension-reformatted"))).isEqualTo(base)
        assertThat(Canonicalizer.logicHash(denyRule("comprehension-renamed-vars"))).isEqualTo(base)
    }

    @Test
    @DisplayName("comprehensions: changing a condition inside, or set -> array, changes the hash")
    fun changingAComprehensionsMeaningChangesTheHash() {
        val base = Canonicalizer.logicHash(denyRule("comprehension-base"))
        assertThat(Canonicalizer.logicHash(denyRule("comprehension-condition-changed"))).isNotEqualTo(base)
        assertThat(Canonicalizer.logicHash(denyRule("comprehension-kind-changed"))).isNotEqualTo(base)
    }

    @Test
    fun aRuleWithNoMetadataHashesDifferentlyFromOneWithMetadata() {
        // exempt_service (acceptance pack) has no METADATA annotation at all.
        val noMetadataPolicySet = Explico.load(Path.of("src/test/resources/acceptance/policies/exemptions"))
        val noMetadataRule = noMetadataPolicySet.packages.single().rules.single { it.name == "exempt_service" }
        val base = denyRule("base")
        assertThat(Canonicalizer.metadataHash(noMetadataRule)).isNotEqualTo(Canonicalizer.metadataHash(base))
    }

    @Test
    fun someKeyValueRenamingBothVariablesYieldsTheSameLogicHash() {
        // spec §14 promotion: `some k, v in ...` renamed to `some key, val in ...`, with every
        // later use renamed to match, is pure alpha-renaming -- same invariant already proven for
        // the single-variable form above (positionalVariableRenameYieldsTheSameLogicHash).
        val base = denyRule("some-kv-base")
        val renamed = denyRule("some-kv-renamed-vars")
        assertThat(Canonicalizer.logicHash(renamed)).isEqualTo(Canonicalizer.logicHash(base))
    }

    @Test
    fun someKeyValueTestingTheKeyInsteadOfTheValueYieldsADifferentLogicHash() {
        // Real gap this promotion closes: before it, BOTH `k` and `v` fell back to the generic
        // `Operand.Variable` fallback, alpha-aliased purely by order of first appearance -- since
        // both variants introduce exactly one new variable name at the same position (right after
        // the identical, unclassified `some k, v in ...` fallback line), "k == \"pending\"" and
        // "v == \"pending\"" aliased to the SAME v1 and hashed IDENTICALLY, even though one checks
        // the collection's keys and the other its values -- genuinely different logic silently
        // reported as UNCHANGED. Promoting the two-variable form fixes this: k and v now alias by
        // their distinct introduction order in the SomeIn condition itself (key before value), so
        // testing one vs. the other is correctly a different canonical shape.
        val base = denyRule("some-kv-base") // tests v (the value)
        val keyInstead = denyRule("some-kv-key-vs-value") // tests k (the key), same variable names throughout
        assertThat(Canonicalizer.logicHash(keyInstead)).isNotEqualTo(Canonicalizer.logicHash(base))
    }

    @Test
    fun pureUnificationEquivalentToDoubleEqualsHashesTheSame() {
        // spec §14 promotion: `input.a = input.b` (both sides already real, non-binding paths) now
        // promotes identically to `input.a == input.b` -- a real diff-quality improvement, not just
        // a rendering one. Before this promotion, `=` unconditionally fell back to a verbatim
        // Condition.Unrendered, so switching from `==` to an equivalent, equally-pure `=` would have
        // spuriously hashed differently (or even flipped DiffCategory to LOGIC_CHANGED) despite
        // identical real semantics.
        val doubleEquals = denyRule("eq-unification-base")
        val singleEquals = denyRule("eq-unification-equivalent")
        assertThat(Canonicalizer.logicHash(singleEquals)).isEqualTo(Canonicalizer.logicHash(doubleEquals))
    }

    @Test
    fun negatingAComparisonYieldsADifferentLogicHash() {
        // spec §14 amendment: `not input.a == input.b` vs. `input.a == input.b` -- genuinely
        // opposite logic (Condition.Comparison.negated is now part of the canonical shape), even
        // though every operand and the produced message are byte-identical. Before this fix,
        // Condition.Comparison had no negated field at all, so these would have hashed IDENTICALLY
        // -- a real, silent LOGIC_CHANGED-reported-as-UNCHANGED gap this fix closes.
        val positive = denyRule("negated-comparison-base")
        val negated = denyRule("negated-comparison-negated")
        assertThat(Canonicalizer.logicHash(negated)).isNotEqualTo(Canonicalizer.logicHash(positive))
    }

    @Test
    fun renamingAVariableUsedInsideADynamicBracketKeyYieldsTheSameLogicHash() {
        // Review item 7: `data.grants[u.id]` -- `u` bound by an earlier `some u in input.users` --
        // maps the dynamic key to PathSegment.KeyPath, whose own nested VarIndex("u") must go
        // through the SAME positional alias as every other local variable, recursively. Renamed
        // here to `user`/`g` (both the SomeIn var feeding the key, and the one bound by the
        // dynamic-keyed SomeIn itself) to prove the recursion actually applies aliasFor, not just
        // compiles.
        val base = denyRule("dynamic-key-base")
        val renamed = denyRule("dynamic-key-renamed-vars")
        assertThat(Canonicalizer.logicHash(renamed)).isEqualTo(Canonicalizer.logicHash(base))
    }

    @Test
    fun changingTheFieldUsedAsADynamicKeyYieldsADifferentLogicHash() {
        // Real gap this closes: before KeyPath existed, a dynamic key's nested path fell back to
        // Operand.Unrendered, and Operand.Unrendered's canonical shape is its OWN verbatim source
        // text -- so this would already have hashed differently by accident (comparing "u.id" vs
        // "u.name" as strings). Pinned anyway: the fix must not accidentally make a KeyPath's own
        // content invisible to the hash the way producesValue almost was (spec §7.1's own caveat).
        val byId = denyRule("dynamic-key-base")
        val byName = denyRule("dynamic-key-different-field")
        assertThat(Canonicalizer.logicHash(byName)).isNotEqualTo(Canonicalizer.logicHash(byId))
    }
}
