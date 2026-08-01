/**
 * Rendering coverage: the fraction of leaf [io.explico.model.Condition]s rendered as
 * structured text rather than fallback source blocks (spec §6.6).
 */
package io.explico.render

/** Part of the public facade return type [io.explico.RenderedDocs]. */
public data class CoverageSummary(val rendered: Int, val total: Int) {
    public val percent: Int get() = if (total == 0) 100 else (rendered * 100) / total
}
