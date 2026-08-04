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
    fun reformattingInsideAnUnrenderedFallbackSpanYieldsADifferentLogicHash() {
        // Deliberate, disclosed behaviour, not a bug: an Unrendered condition/operand's sourceText
        // is verbatim source the tool couldn't classify, so it can't verify two differently
        // formatted spans mean the same thing -- unlike a classified construct, where formatting
        // is invisible because only the parsed structure is hashed. count({a | ...}) is exactly
        // this case (spec §5's disclosed operand-position-builtin gap): reformatting purely inside
        // the comprehension's whitespace changes the hash.
        val fallbackBase = denyRule("fallback-base")
        val fallbackReformatted = denyRule("fallback-reformatted")
        assertThat(Canonicalizer.logicHash(fallbackReformatted)).isNotEqualTo(Canonicalizer.logicHash(fallbackBase))
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
}
