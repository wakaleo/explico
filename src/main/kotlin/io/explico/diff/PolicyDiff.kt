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
     * has two rules sharing a control-id -- unless [groupByControl] (spec §14.57), which allows shared ids and identifies their rules by package and rule.
     */
    fun diff(old: PolicySet, new: PolicySet, groupByControl: Boolean = false): List<DiffEntry> {
        // Without grouping a shared control id stays an error, exactly as before (spec §7.1).
        if (!groupByControl) {
            requireNoDuplicateControlIds(old)
            requireNoDuplicateControlIds(new)
        }
        // With it (spec §14.57) an id used by two or more rules on EITHER side names several controls' rules, so each is told apart by package and rule;
        // decided over both versions at once, so a rule matches itself whichever side the id happens to be shared on.
        val shared = if (groupByControl) sharedControlIds(old) + sharedControlIds(new) else emptySet()

        val oldByIdentity = index(old, shared)
        val newByIdentity = index(new, shared)
        val identities = (oldByIdentity.keys + newByIdentity.keys).toSortedSet()

        return identities.map { identity -> classify(oldByIdentity[identity], newByIdentity[identity]) }
    }

    private data class Located(val pkg: PolicyPackage, val rule: RuleGroup)

    /**
     * What ties an old rule to a new one: [plain] is the control id, or `<package>.<rule>` for a rule with none; [packagePath] and [ruleName] are set
     * ONLY for a rule of a control id that is shared. A structured key, never a joined string, so an id containing any character cannot collide with another.
     */
    private data class Identity(val plain: String, val packagePath: String = "", val ruleName: String = "") : Comparable<Identity> {
        override fun compareTo(other: Identity): Int =
            compareValuesBy(this, other, { it.plain }, { it.packagePath }, { it.ruleName })
    }

    private fun sharedControlIds(policySet: PolicySet): Set<String> =
        policySet.packages.flatMap { pkg -> pkg.rules.mapNotNull { it.metadata?.controlId } }
            .groupingBy { it }.eachCount().filterValues { it > 1 }.keys

    private fun index(policySet: PolicySet, shared: Set<String>): Map<Identity, Located> {
        val result = linkedMapOf<Identity, Located>()
        val collisions = sortedSetOf<String>()
        for (pkg in policySet.packages) for (rule in pkg.rules) {
            val identity = identityOf(pkg, rule, shared)
            if (result.put(identity, Located(pkg, rule)) != null && identity.packagePath.isNotEmpty()) collisions += "${identity.plain} (rule ${identity.packagePath}.${identity.ruleName})"
        }
        // Two rules of one name AND one shared id in one package cannot be told apart: still an error with grouping, and it says which.
        if (collisions.isNotEmpty()) throw DuplicateControlIdException(collisions.toList())
        return result
    }

    private fun identityOf(pkg: PolicyPackage, rule: RuleGroup, shared: Set<String>): Identity {
        val controlId = rule.metadata?.controlId ?: return Identity("${pkg.path}.${rule.name}")
        return if (controlId in shared) Identity(controlId, pkg.path, rule.name) else Identity(controlId)
    }

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
