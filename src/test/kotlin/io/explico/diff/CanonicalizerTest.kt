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
}
