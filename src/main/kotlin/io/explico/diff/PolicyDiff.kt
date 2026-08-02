/**
 * Identity resolution and change classification across two policy versions (spec §7.2).
 * Never evaluates whether a change makes a policy stricter or more permissive -- purely
 * structural, via [Canonicalizer]'s hashes.
 */
package io.explico.diff

import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RuleGroup

/** The five ways a control can compare across an old and a new [PolicySet] (spec §7.2). */
public enum class DiffCategory { ADDED, REMOVED, LOGIC_CHANGED, DOCS_CHANGED, UNCHANGED }

/**
 * One control's classification. [oldRule]/[oldPackage] are null for [DiffCategory.ADDED];
 * [newRule]/[newPackage] are null for [DiffCategory.REMOVED]; both are present otherwise --
 * possibly under a different package or rule name than each other, when a control-id-preserving
 * rename is what ties them together (spec §7.1: control-id wins over name matching).
 */
public data class DiffEntry(
    val category: DiffCategory,
    val controlId: String?,
    val oldPackage: PolicyPackage?,
    val oldRule: RuleGroup?,
    val newPackage: PolicyPackage?,
    val newRule: RuleGroup?,
)

/** Two rules share a control-id within one policy set (spec §7.1: "exit code 4 with an error listing them"). */
public class DuplicateControlIdException(public val controlIds: List<String>) :
    RuntimeException("Duplicate control-id(s) within one policy set: ${controlIds.joinToString(", ")}")

internal object PolicyDiff {

    /**
     * Classifies every control across [old] -> [new] by identity (spec §7.1: `custom.control-id`
     * if present, else `<package>.<rule name>`; a control-id on both sides wins over name
     * matching, so a control-id-preserving rename is the same control, never REMOVED+ADDED).
     * Entries are returned in identity order; [io.explico.diff.DiffRenderer] applies its own
     * category/control-id ordering on top. Throws [DuplicateControlIdException] if either side
     * has two rules sharing a control-id.
     */
    fun diff(old: PolicySet, new: PolicySet): List<DiffEntry> {
        requireNoDuplicateControlIds(old)
        requireNoDuplicateControlIds(new)

        val oldByIdentity = index(old)
        val newByIdentity = index(new)
        val identities = (oldByIdentity.keys + newByIdentity.keys).toSortedSet()

        return identities.map { identity -> classify(oldByIdentity[identity], newByIdentity[identity]) }
    }

    private data class Located(val pkg: PolicyPackage, val rule: RuleGroup)

    private fun index(policySet: PolicySet): Map<String, Located> =
        policySet.packages.flatMap { pkg -> pkg.rules.map { rule -> identityOf(pkg, rule) to Located(pkg, rule) } }.toMap()

    private fun identityOf(pkg: PolicyPackage, rule: RuleGroup): String =
        rule.metadata?.controlId ?: "${pkg.path}.${rule.name}"

    private fun requireNoDuplicateControlIds(policySet: PolicySet) {
        val controlIds = policySet.packages.flatMap { pkg -> pkg.rules.mapNotNull { it.metadata?.controlId } }
        val duplicates = controlIds.groupBy { it }.filterValues { it.size > 1 }.keys.sorted()
        if (duplicates.isNotEmpty()) throw DuplicateControlIdException(duplicates)
    }

    private fun classify(old: Located?, new: Located?): DiffEntry {
        val category = when {
            old == null -> DiffCategory.ADDED
            new == null -> DiffCategory.REMOVED
            Canonicalizer.logicHash(old.rule) != Canonicalizer.logicHash(new.rule) -> DiffCategory.LOGIC_CHANGED
            Canonicalizer.metadataHash(old.rule) != Canonicalizer.metadataHash(new.rule) -> DiffCategory.DOCS_CHANGED
            else -> DiffCategory.UNCHANGED
        }
        return DiffEntry(
            category = category,
            controlId = (new ?: old)!!.rule.metadata?.controlId,
            oldPackage = old?.pkg,
            oldRule = old?.rule,
            newPackage = new?.pkg,
            newRule = new?.rule,
        )
    }
}
