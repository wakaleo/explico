package io.explico.diff

/**
 * One changed control of a diff report, read without touching the structural model: a projection of the entries `explico.json`'s `changes` array carries (same entries -- everything but
 * [DiffCategory.UNCHANGED] -- same order, same strings), so the two cannot disagree. [label] is the heading the report shows; [anchor] is the id of the card in the report, or null when it
 * has none (a documentation-only change has no card, and a control whose id is already taken by an earlier card has none either). [packagePath] and [ruleName] are the rule the report
 * shows for the control: the new one, or the old one for a removal.
 */
public data class DiffChange(
    val controlId: String?,
    val category: DiffCategory,
    val label: String,
    val packagePath: String,
    val ruleName: String,
    val anchor: String?,
)
