/**
 * Tests for PolicyDiff (spec §7.2): identity resolution and category classification, driven by
 * real `.rego` variants parsed through `opa`, reusing Canonicalizer's fixture set where the
 * scenario is identical (rename/reformat/operand/metadata) plus dedicated fixtures for
 * ADDED/REMOVED, a control-id-preserving rename across package+rule-name, and duplicate
 * control-ids. Requires `opa` on PATH, same pattern as CanonicalizerTest.
 */
package io.explico.diff

import io.explico.Explico
import io.explico.model.PolicySet
import io.explico.opa.OpaRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Path

class PolicyDiffTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
        }
    }

    private val canonicalizerFixtures = Path.of("src/test/resources/diff/canonicalizer")
    private val policyDiffFixtures = Path.of("src/test/resources/diff/policydiff")

    private fun load(root: Path, variant: String): PolicySet = Explico.load(root.resolve(variant))

    @Test
    fun onlyInNewIsAdded() {
        val old = load(canonicalizerFixtures, "base")
        val new = PolicySet(load(canonicalizerFixtures, "base").packages + Explico.load(Path.of("src/test/resources/acceptance/policies/exemptions")).packages)
        val entries = PolicyDiff.diff(old, new)

        val added = entries.single { it.category == DiffCategory.ADDED }
        assertThat(added.controlId).isNull()
        assertThat(added.newPackage?.path).isEqualTo("release.exemptions")
        assertThat(added.newRule?.name).isEqualTo("exempt_service")
        assertThat(added.oldPackage).isNull()
        assertThat(added.oldRule).isNull()
    }

    @Test
    fun onlyInOldIsRemoved() {
        val old = PolicySet(load(canonicalizerFixtures, "base").packages + Explico.load(Path.of("src/test/resources/acceptance/policies/exemptions")).packages)
        val new = load(canonicalizerFixtures, "base")
        val entries = PolicyDiff.diff(old, new)

        val removed = entries.single { it.category == DiffCategory.REMOVED }
        assertThat(removed.oldPackage?.path).isEqualTo("release.exemptions")
        assertThat(removed.oldRule?.name).isEqualTo("exempt_service")
        assertThat(removed.newPackage).isNull()
        assertThat(removed.newRule).isNull()
    }

    @Test
    fun positionalVariableRenameIsUnchanged() {
        val entries = PolicyDiff.diff(load(canonicalizerFixtures, "base"), load(canonicalizerFixtures, "renamed-vars"))
        assertThat(entries.single().category).isEqualTo(DiffCategory.UNCHANGED)
    }

    @Test
    fun reformattingIsUnchanged() {
        val entries = PolicyDiff.diff(load(canonicalizerFixtures, "base"), load(canonicalizerFixtures, "reformatted"))
        assertThat(entries.single().category).isEqualTo(DiffCategory.UNCHANGED)
    }

    @Test
    fun aFileGenuinelyReformattedByRealOpaFmtIsUnchanged() {
        // spec §12 names `opa fmt` specifically; see CanonicalizerTest's matching test for how
        // this fixture was produced (real `opa fmt -w` output, checked in once).
        val entries = PolicyDiff.diff(load(canonicalizerFixtures, "base"), load(canonicalizerFixtures, "opa-fmt-applied"))
        assertThat(entries.single().category).isEqualTo(DiffCategory.UNCHANGED)
    }

    @Test
    fun anOperandChangeIsLogicChanged() {
        val entries = PolicyDiff.diff(load(canonicalizerFixtures, "base"), load(canonicalizerFixtures, "operand-changed"))
        assertThat(entries.single().category).isEqualTo(DiffCategory.LOGIC_CHANGED)
    }

    @Test
    fun aMetadataOnlyEditIsDocsChanged() {
        val entries = PolicyDiff.diff(load(canonicalizerFixtures, "base"), load(canonicalizerFixtures, "metadata-changed"))
        val entry = entries.single()
        assertThat(entry.category).isEqualTo(DiffCategory.DOCS_CHANGED)
        assertThat(entry.oldRule).isNotNull()
        assertThat(entry.newRule).isNotNull()
    }

    @Test
    fun aControlIdPreservingRenameWithIdenticalLogicIsUnchangedNeverRemovedPlusAdded() {
        val old = load(policyDiffFixtures, "rename-old")
        val new = load(policyDiffFixtures, "rename-new-same-logic")
        val entries = PolicyDiff.diff(old, new)

        assertThat(entries).hasSize(1)
        val entry = entries.single()
        assertThat(entry.category).isEqualTo(DiffCategory.UNCHANGED)
        assertThat(entry.controlId).isEqualTo("SAMPLE-001")
        assertThat(entry.oldPackage?.path).isEqualTo("diff.sample")
        assertThat(entry.oldRule?.name).isEqualTo("deny")
        assertThat(entry.newPackage?.path).isEqualTo("diff.renamed")
        assertThat(entry.newRule?.name).isEqualTo("violation")
    }

    @Test
    fun aControlIdPreservingRenameWithChangedLogicIsLogicChangedNeverRemovedPlusAdded() {
        val old = load(policyDiffFixtures, "rename-old")
        val new = load(policyDiffFixtures, "rename-new-different-logic")
        val entries = PolicyDiff.diff(old, new)

        assertThat(entries).hasSize(1)
        val entry = entries.single()
        assertThat(entry.category).isEqualTo(DiffCategory.LOGIC_CHANGED)
        assertThat(entry.controlId).isEqualTo("SAMPLE-001")
    }

    @Test
    fun duplicateControlIdsOnOldSideThrowsListingTheDuplicateIds() {
        val old = load(policyDiffFixtures, "duplicate-control-ids")
        val new = load(canonicalizerFixtures, "base")

        assertThatThrownBy { PolicyDiff.diff(old, new) }
            .isInstanceOf(DuplicateControlIdException::class.java)
            .extracting { (it as DuplicateControlIdException).controlIds }
            .isEqualTo(listOf("DUP-001"))
    }

    @Test
    fun duplicateControlIdsOnNewSideThrowsListingTheDuplicateIds() {
        val old = load(canonicalizerFixtures, "base")
        val new = load(policyDiffFixtures, "duplicate-control-ids")

        assertThatThrownBy { PolicyDiff.diff(old, new) }
            .isInstanceOf(DuplicateControlIdException::class.java)
            .extracting { (it as DuplicateControlIdException).controlIds }
            .isEqualTo(listOf("DUP-001"))
    }

    @Test
    fun addedAndRemovedCanBothAppearInOneDiffCall() {
        val old = Explico.load(Path.of("src/test/resources/acceptance/policies/exemptions"))
        val new = load(canonicalizerFixtures, "base")
        val entries = PolicyDiff.diff(old, new)

        assertThat(entries.map { it.category }).containsExactlyInAnyOrder(DiffCategory.REMOVED, DiffCategory.ADDED)
    }
}
