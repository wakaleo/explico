package io.explico.diff

/**
 * The changed controls of a diff, decided ONCE (spec §14.80 step 4): everything but [DiffCategory.UNCHANGED], in the report's order, with the ids that need qualifying and the anchors each
 * card owns. `explico.json`'s `changes` array and the library's `DiffReport.changes` both read it, so the two are the same list with the same strings, and neither recomputes a label, an
 * anchor or the order.
 */
internal class ChangedSet private constructor(val entries: List<DiffEntry>, val qualifiedIds: Set<String>, val anchors: ChangeAnchors) {

    /** [entry] as the plain value a library consumer reads; its strings are the ones the Markdown and the JSON show. */
    fun changeOf(entry: DiffEntry): DiffChange {
        val (packagePath, rule) = ChangeSummary.displayedTarget(entry)
        return DiffChange(entry.controlId, entry.category, ChangeSummary.plainLabelOf(entry, qualifiedIds), packagePath, rule.name, anchors.of(entry))
    }

    /** Every changed control, in report order. */
    val changes: List<DiffChange> get() = entries.map { changeOf(it) }

    companion object {
        /** The changed set of [all] (the classified entries of a diff, UNCHANGED included). */
        fun of(all: List<DiffEntry>): ChangedSet {
            val changed = all.filter { it.category != DiffCategory.UNCHANGED }.sortedWith(ChangeSummary.REPORT_ORDER)
            val qualified = ChangeSummary.qualifiedIds(all)
            return ChangedSet(changed, qualified, ChangeAnchors(changed, qualified))
        }
    }
}
