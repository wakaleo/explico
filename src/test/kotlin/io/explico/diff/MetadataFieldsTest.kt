/**
 * Unit tests for [MetadataFields] (spec §14.75): the ONE list of documentation fields -- name, whether it differs, and the old/new cell as the diff table words it --
 * that the "documentation changed" sub-bullet, the old/new table and the JSON `fields` are all read from. Hand-built metadata, no opa.
 */
package io.explico.diff

import io.explico.model.ActionsProjection
import io.explico.model.AnnotationsProjection
import io.explico.model.Author
import io.explico.model.DeclaredProjection
import io.explico.model.RelatedResource
import io.explico.model.RuleMetadata
import io.explico.model.TimingProjection
import io.explico.model.VerdictProjection
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class MetadataFieldsTest {

    private fun meta(
        title: String? = "T", description: String? = "D", frameworks: List<String> = listOf("F"), authors: List<Author> = emptyList(),
        resources: List<RelatedResource> = emptyList(), custom: Map<String, String> = emptyMap(), projection: DeclaredProjection? = null,
    ) = RuleMetadata(title, description, "C-1", frameworks, authors, resources, custom, projection)

    private fun differing(old: RuleMetadata?, new: RuleMetadata?) = MetadataFields.of(old, new).filter { it.differs }.map { it.name }

    @Test
    @DisplayName("the fields come in a fixed order with their table labels")
    fun orderAndLabels() {
        assertThat(MetadataFields.of(meta(), meta()).map { it.label }).containsExactly(
            "Title", "Description", "Frameworks", "Authors", "Related resources", "Custom fields", "Verdict", "Actions", "Annotations", "Timing", "Mask",
        )
        assertThat(MetadataFields.of(meta(), meta()).filter { it.base }.map { it.name }).containsExactly("title", "description", "frameworks")
    }

    @Test
    @DisplayName("identical metadata, and two missing metadata, differ nowhere")
    fun identical() {
        assertThat(differing(meta(), meta())).isEmpty()
        assertThat(differing(null, null)).isEmpty()
    }

    @Test
    @DisplayName("each field differs on its own and is named exactly as DOCS_CHANGED has always named it")
    fun eachField() {
        val projection = DeclaredProjection(VerdictProjection("ok"), ActionsProjection("a", "l"), AnnotationsProjection("x"), TimingProjection("d", "s"), listOf("p"))
        val cases = listOf(
            "title" to meta(title = "T2"),
            "description" to meta(description = "D2"),
            "frameworks" to meta(frameworks = listOf("F", "G")),
            "authors" to meta(authors = listOf(Author("A", null))),
            "related resources" to meta(resources = listOf(RelatedResource("r", null))),
            "custom fields" to meta(custom = mapOf("k" to "v")),
            "verdict" to meta(projection = DeclaredProjection(verdict = projection.verdict)),
            "actions" to meta(projection = DeclaredProjection(actions = projection.actions)),
            "annotations" to meta(projection = DeclaredProjection(annotations = projection.annotations)),
            "timing" to meta(projection = DeclaredProjection(timing = projection.timing)),
            "mask" to meta(projection = DeclaredProjection(mask = projection.mask)),
        )
        for ((name, changed) in cases) assertThat(differing(meta(), changed)).describedAs(name).containsExactly(name)
    }

    @Test
    @DisplayName("several differing fields come out in table order")
    fun several() {
        assertThat(differing(meta(), meta(description = "D2", frameworks = listOf("F", "G"), custom = mapOf("criticality" to "high")))).containsExactly("description", "frameworks", "custom fields")
    }

    @Test
    @DisplayName("cells: a missing value is a dash, a multi-line description uses <br>, custom fields read key: value")
    fun cells() {
        val fields = MetadataFields.of(meta(title = null, description = "line one\nline two\n", custom = mapOf("a" to "1", "b" to "2")), meta(frameworks = emptyList()))
        fun cell(name: String) = fields.single { it.name == name }.let { it.oldCell to it.newCell }

        assertThat(cell("title")).isEqualTo("—" to "T")
        assertThat(cell("description")).isEqualTo("line one<br>line two" to "D")
        assertThat(cell("frameworks")).isEqualTo("F" to "—")
        assertThat(cell("custom fields")).isEqualTo("a: 1, b: 2" to "—")
        assertThat(cell("authors")).isEqualTo("—" to "—")
    }

    @Test
    @DisplayName("CHALLENGE: metadata missing on one side: every field the other side has is a difference, no crash")
    fun metadataMissingOnOneSide() {
        assertThat(differing(null, meta())).contains("title", "description", "frameworks")
        assertThat(MetadataFields.of(null, meta()).single { it.name == "title" }.oldCell).isEqualTo("—")
        assertThat(differing(meta(), null)).contains("title", "description", "frameworks")
    }

    @Test
    @DisplayName("CHALLENGE: a difference that renders identically (trailing whitespace) still differs, because values are compared, not cells")
    fun rendersIdentically() {
        val field = MetadataFields.of(meta(description = "same"), meta(description = "same  \n")).single { it.name == "description" }

        assertThat(field.differs).isTrue()
        assertThat(field.oldCell).isEqualTo(field.newCell)
    }
}
