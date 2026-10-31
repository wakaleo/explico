/**
 * The data-aware part of `diff` (spec §14.73): a threshold or a list that lives in the supplied DATA is part of what a control does, so a control whose
 * referenced data differs between two versions of the data is not unchanged.
 *
 * Pure: it compares values that were already resolved (by `OpaRunner.evalDataPath`, one plain `data.*` prefix at a time, never a rule) and decides which
 * entries are affected. It reads paths with [io.explico.render.ReferenceData.referencedDataPaths], the same function the Reference data block uses, so
 * "the data a control reads" has one definition.
 */
package io.explico.diff

import io.explico.model.Operand
import io.explico.model.PolicySet
import io.explico.model.RuleGroup
import io.explico.render.ReferenceData
import kotlinx.serialization.json.JsonElement
import java.util.Collections
import java.util.IdentityHashMap

/** One path a control reads whose value differs; `null` = undefined in that version of the data (distinct from a real JSON `null`). */
internal data class DataChange(val path: Operand.Path, val old: JsonElement?, val new: JsonElement?)

/** The changes found for each entry of a report, looked up by the entry object itself (an entry is a data class; two reports never share one). */
internal class DataChangeIndex(
    private val byEntry: IdentityHashMap<DiffEntry, List<DataChange>> = IdentityHashMap(),
    private val raised: Set<DiffEntry> = emptySet(),
) {
    fun of(entry: DiffEntry): List<DataChange> = byEntry[entry].orEmpty()

    /**
     * True when the entry was UNCHANGED or DOCS_CHANGED and only the data made it LOGIC_CHANGED: its logic hash is equal, so nothing about its Rego moved. For an entry that
     * was already LOGIC_CHANGED the data is one more change among others, and nothing may be said about its source from the data alone.
     */
    fun wasRaised(entry: DiffEntry): Boolean = entry in raised

    companion object {
        val NONE = DataChangeIndex()
    }
}

internal object DataChanges {

    /** The entries a report is built from, with the category of any entry whose data changed already raised, and the changes found. */
    data class Result(val entries: List<DiffEntry>, val changes: DataChangeIndex)

    /** Every distinct path a matched entry's old or new rule reads, in first-appearance order: what must be resolved against each version of the data. */
    fun pathsToResolve(entries: List<DiffEntry>, oldSet: PolicySet, newSet: PolicySet): List<Operand.Path> =
        entries.flatMap { pathsRead(it, oldSet, newSet) }.distinct()

    /**
     * Raises every UNCHANGED or DOCS_CHANGED entry whose referenced data differs to LOGIC_CHANGED (the data is part of the control's behaviour) and records the
     * changes of every such entry, LOGIC_CHANGED ones included. ADDED and REMOVED entries have only one side and are left alone.
     */
    fun apply(
        entries: List<DiffEntry>,
        oldSet: PolicySet,
        newSet: PolicySet,
        oldValues: Map<Operand.Path, JsonElement?>,
        newValues: Map<Operand.Path, JsonElement?>,
    ): Result {
        val index = IdentityHashMap<DiffEntry, List<DataChange>>()
        val raised: MutableSet<DiffEntry> = Collections.newSetFromMap(IdentityHashMap())
        val result = entries.map { entry ->
            val changes = pathsRead(entry, oldSet, newSet)
                .filter { oldValues[it] != newValues[it] }
                .map { DataChange(it, oldValues[it], newValues[it]) }
            val wasRaised = changes.isNotEmpty() && entry.category in RAISED
            val final = if (wasRaised) entry.copy(category = DiffCategory.LOGIC_CHANGED) else entry
            if (changes.isNotEmpty()) index[final] = changes
            if (wasRaised) raised += final
            final
        }
        return Result(result, DataChangeIndex(index, raised))
    }

    private val RAISED = setOf(DiffCategory.UNCHANGED, DiffCategory.DOCS_CHANGED)

    private fun pathsRead(entry: DiffEntry, oldSet: PolicySet, newSet: PolicySet): List<Operand.Path> {
        val oldRule: RuleGroup = entry.oldRule ?: return emptyList()
        val newRule: RuleGroup = entry.newRule ?: return emptyList()
        return (ReferenceData.referencedDataPaths(oldRule, oldSet) + ReferenceData.referencedDataPaths(newRule, newSet)).distinct()
    }
}
