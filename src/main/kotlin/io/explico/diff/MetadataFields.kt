/**
 * The documentation fields of a rule's METADATA as the diff compares and words them (spec §14.75): for each, its name (as the "documentation changed" sub-bullet and the
 * JSON `fields` name it), whether the value differs between two versions, and the old/new table cell. ONE list, so the sub-bullet, the old/new table (a DOCS_CHANGED's, and
 * the block beside a logic change) and the JSON cannot disagree about what "differs" means. Values are compared, never the rendered cells.
 */
package io.explico.diff

import io.explico.model.ActionsProjection
import io.explico.model.AnnotationsProjection
import io.explico.model.Author
import io.explico.model.RelatedResource
import io.explico.model.RuleMetadata
import io.explico.model.TimingProjection
import io.explico.model.VerdictProjection

/** One documentation field: [name] is lower-case (`related resources`), [base] marks the three a DOCS_CHANGED table always shows. */
internal data class DocField(val name: String, val base: Boolean, val differs: Boolean, val oldCell: String, val newCell: String) {
    val label: String get() = name.replaceFirstChar { it.uppercase() }
}

internal object MetadataFields {

    /** Every field in table order, for [old] against [new] (either may be null: a rule with no METADATA). */
    fun of(old: RuleMetadata?, new: RuleMetadata?): List<DocField> = listOf(
        field("title", true, old?.title, new?.title, ::cellText),
        field("description", true, old?.description, new?.description, ::cellText),
        field("frameworks", true, old?.frameworks, new?.frameworks, ::frameworksText),
        field("authors", false, old?.authors.orEmpty(), new?.authors.orEmpty(), ::authorsText),
        field("related resources", false, old?.relatedResources.orEmpty(), new?.relatedResources.orEmpty(), ::resourcesText),
        field("custom fields", false, old?.customFields ?: emptyMap(), new?.customFields ?: emptyMap(), ::customText),
        field("verdict", false, old?.projection?.verdict, new?.projection?.verdict, ::verdictText),
        field("actions", false, old?.projection?.actions, new?.projection?.actions, ::actionsText),
        field("annotations", false, old?.projection?.annotations, new?.projection?.annotations, ::annotationsText),
        field("timing", false, old?.projection?.timing, new?.projection?.timing, ::timingText),
        field("mask", false, old?.projection?.mask, new?.projection?.mask, ::maskText),
    )

    private fun <T> field(name: String, base: Boolean, old: T, new: T, cell: (T) -> String) = DocField(name, base, old != new, cell(old), cell(new))

    private fun cellText(text: String?): String = text?.trimEnd()?.replace("\n", "<br>") ?: "—"

    private fun frameworksText(frameworks: List<String>?): String = frameworks?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "—"

    private fun authorsText(authors: List<Author>): String =
        authors.takeIf { it.isNotEmpty() }?.joinToString(", ") { a -> if (a.name != null && a.email != null) "${a.name} <${a.email}>" else a.name ?: a.email.orEmpty() } ?: "—"

    private fun resourcesText(resources: List<RelatedResource>): String =
        resources.takeIf { it.isNotEmpty() }?.joinToString(", ") { r -> if (r.description != null) "${r.description} (${r.ref})" else r.ref } ?: "—"

    private fun customText(fields: Map<String, String>): String = fields.takeIf { it.isNotEmpty() }?.entries?.joinToString(", ") { "${it.key}: ${it.value}" } ?: "—"

    private fun verdictText(verdict: VerdictProjection?): String = verdict?.let { "outcome `${it.outcome}`" + (it.reason?.let { r -> ", reason `$r`" } ?: "") } ?: "—"

    private fun actionsText(actions: ActionsProjection?): String = actions?.let { "at `${it.at}`, label `${it.label}`" } ?: "—"

    private fun annotationsText(annotations: AnnotationsProjection?): String = annotations?.let { "at `${it.at}`" } ?: "—"

    private fun timingText(timing: TimingProjection?): String =
        timing?.let { listOfNotNull(it.dueBy?.let { f -> "dueBy `$f`" }, it.startBefore?.let { f -> "startBefore `$f`" }).joinToString(", ") } ?: "—"

    private fun maskText(mask: List<String>?): String = mask?.takeIf { it.isNotEmpty() }?.joinToString(", ") { "`$it`" } ?: "—"
}
