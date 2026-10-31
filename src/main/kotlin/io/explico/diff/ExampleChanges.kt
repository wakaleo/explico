/**
 * The per-fixture verdict diff (spec §14.74): which of the author's own fixtures come out differently between two versions of a control.
 *
 * Pure: it compares outcomes OPA already produced (the worked-example path, one evaluation per fixture and package on each side). It never evaluates anything and
 * never infers meaning from a fixture's name -- only the verdict and the produced messages are compared, and the words are the cards' own ([WorkedExamples.outcomeWord]).
 */
package io.explico.diff

import io.explico.render.WorkedExample
import io.explico.render.WorkedExamples
import java.util.IdentityHashMap

/** The example changes found for each entry, looked up by the entry object itself (as [DataChangeIndex]). */
internal class ExampleChangeIndex(
    private val byEntry: IdentityHashMap<DiffEntry, List<ExampleChange>> = IdentityHashMap(),
    val comparison: ExampleComparison = ExampleComparison(0, 0, 0, 0, 0),
) {
    fun of(entry: DiffEntry): List<ExampleChange> = byEntry[entry].orEmpty()

    /** Every change in the report's own control order, then fixture order -- the library's `DiffReport.exampleChanges`. */
    fun flat(entries: List<DiffEntry>): List<ExampleChange> = entries.sortedWith(ChangeSummary.REPORT_ORDER).flatMap { of(it) }
}

/** Outcomes as `Explico` evaluates them: package path -> rule name -> one outcome per fixture, in fixture order. */
internal typealias ExampleOutcomes = Map<String, Map<String, List<WorkedExample>>>

internal object ExampleChanges {

    fun compute(entries: List<DiffEntry>, oldOutcomes: ExampleOutcomes, newOutcomes: ExampleOutcomes): ExampleChangeIndex {
        val index = IdentityHashMap<DiffEntry, List<ExampleChange>>()
        var total = 0
        var compared = 0
        var functions = 0
        var unattributable = 0
        val onOneVersion = sortedSetOf<String>()
        for (entry in entries) {
            val oldRule = entry.oldRule ?: continue
            val newRule = entry.newRule ?: continue
            val oldPackage = entry.oldPackage ?: continue
            val newPackage = entry.newPackage ?: continue
            total++
            // The scoping of the cards themselves: a function, a name shared by cards whose messages cannot be attributed, or no evaluated fixture yields nothing -- not compared.
            val before = WorkedExamples.forGroup(oldRule, oldPackage, oldOutcomes[oldPackage.path]?.get(oldRule.name).orEmpty())
            val after = WorkedExamples.forGroup(newRule, newPackage, newOutcomes[newPackage.path]?.get(newRule.name).orEmpty())
            if (before.isEmpty() || after.isEmpty()) {
                if (oldRule.parameters.isNotEmpty() || newRule.parameters.isNotEmpty()) functions++ else unattributable++
                continue
            }
            compared++

            val afterByName = after.associateBy { it.fixture.name }
            onOneVersion += before.map { it.fixture.name }.filter { it !in afterByName } + after.map { it.fixture.name }.filter { name -> before.none { it.fixture.name == name } }
            val control = entry.controlId ?: "${newPackage.path}.${newRule.name}"
            val changes = before.mapNotNull { was ->
                // A fixture one side skipped (its evaluation failed, with a warning) has nothing to compare with.
                val now = afterByName[was.fixture.name] ?: return@mapNotNull null
                val differs = was.matched != now.matched || (was.matched && was.messages.sorted() != now.messages.sorted())
                if (!differs) return@mapNotNull null
                ExampleChange(control, was.fixture.name, WorkedExamples.outcomeWord(oldRule.name, was.matched), WorkedExamples.outcomeWord(newRule.name, now.matched))
            }
            if (changes.isNotEmpty()) index[entry] = changes
        }
        return ExampleChangeIndex(index, ExampleComparison(compared, total, functions, unattributable, onOneVersion.size))
    }
}
