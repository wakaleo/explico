/**
 * Edge cases for [DiffJson] (spec §14.45): labels and anchors for rules without a control id or title, an empty diff, and the
 * order of changes. Hand-built domain objects -- no opa; real shapes are in RealisticJsonExportAcceptanceIT.
 */
package io.explico.diff

import io.explico.model.PolicyPackage
import io.explico.model.RuleGroup
import io.explico.model.RuleMetadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class DiffJsonTest {

    private fun rule(name: String, id: String?, title: String? = null) = RuleGroup(name, id?.let { RuleMetadata(title, "d", it, emptyList()) }, null, emptyList())
    private fun pkg(path: String, r: RuleGroup) = PolicyPackage(path, listOf(r), listOf("f.rego"))
    private fun parse(entries: List<DiffEntry>) = Json.parseToJsonElement(DiffJson.render(entries)).jsonObject

    @Test
    fun noEntriesIsAValidDocumentWithZeroCountsAndNoChanges() {
        val doc = parse(emptyList())
        assertThat(doc.getValue("changes").jsonArray).isEmpty()
        assertThat(doc.getValue("summary").jsonObject.values.map { it.jsonPrimitive.int }).containsOnly(0)
        assertThat(DiffJson.render(emptyList())).endsWith("}\n")
    }

    @Test
    @DisplayName("CHALLENGE: a rule with no control id is labelled package.rule, has a null controlId, and still links by its slug")
    fun idLessRule() {
        val r = rule("exempt", null)
        val change = parse(listOf(DiffEntry(DiffCategory.REMOVED, null, pkg("release.ex", r), r, null, null))).getValue("changes").jsonArray.single().jsonObject
        assertThat(change.getValue("controlId")).isEqualTo(JsonNull)
        assertThat(change.getValue("label").jsonPrimitive.content).isEqualTo("release.ex.exempt")
        assertThat(change.getValue("anchor").jsonPrimitive.content).isEqualTo("release-ex-exempt")
        assertThat(change.containsKey("details")).isFalse()
    }

    @Test
    fun aControlWithNoTitleFallsBackToItsRuleNameInTheLabel() {
        val r = rule("deny", "C-1", title = null)
        val change = parse(listOf(DiffEntry(DiffCategory.ADDED, "C-1", null, null, pkg("p", r), r))).getValue("changes").jsonArray.single().jsonObject
        assertThat(change.getValue("label").jsonPrimitive.content).isEqualTo("C-1 — deny")
    }

    @Test
    fun docsChangedHasFieldsAndNoAnchorAndUnchangedIsOnlyCounted() {
        val old = rule("deny", "C-1", "Old")
        val new = rule("deny", "C-1", "New")
        val unchanged = rule("allow", "C-2", "Same")
        val doc = parse(
            listOf(
                DiffEntry(DiffCategory.UNCHANGED, "C-2", pkg("p", unchanged), unchanged, pkg("p", unchanged), unchanged),
                DiffEntry(DiffCategory.DOCS_CHANGED, "C-1", pkg("p", old), old, pkg("p", new), new),
            ),
        )
        val change = doc.getValue("changes").jsonArray.single().jsonObject
        assertThat(change.getValue("anchor")).isEqualTo(JsonNull)
        assertThat(change.getValue("fields").jsonArray.map { it.jsonPrimitive.content }).containsExactly("title")
        assertThat(doc.getValue("summary").jsonObject.getValue("UNCHANGED").jsonPrimitive.int).isEqualTo(1)
    }
}
