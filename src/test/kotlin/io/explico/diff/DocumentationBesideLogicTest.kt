/**
 * Unit tests for documentation changes that travel with a logic change (spec §14.75): the "documentation changed" sub-bullet, the old/new block with only the rows that
 * differ, the JSON `fields`. Hand-built domain objects -- no opa; the real report is pinned by RealisticMetadataWithLogicDiffAcceptanceIT.
 */
package io.explico.diff

import io.explico.model.Condition
import io.explico.model.DeclaredProjection
import io.explico.model.Operand
import io.explico.model.Operator
import io.explico.model.PathSegment
import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RuleBody
import io.explico.model.RuleGroup
import io.explico.model.RuleMetadata
import io.explico.model.SourceRef
import io.explico.model.VerdictProjection
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.IdentityHashMap

class DocumentationBesideLogicTest {

    private val limits = Operand.Path(listOf(PathSegment.Field("data"), PathSegment.Field("limits")))
    private fun path(name: String) = Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field(name)))

    private fun meta(description: String? = "D", custom: Map<String, String> = emptyMap(), projection: DeclaredProjection? = null) =
        RuleMetadata("Title", description, "C-1", listOf("F"), customFields = custom, projection = projection)

    private fun rule(metadata: RuleMetadata?, field: String = "a", source: String = "deny contains msg if { true }") = RuleGroup(
        "deny", metadata, null, listOf(RuleBody(listOf(Condition.Comparison(path(field), Operator.EQ, Operand.Literal("1"))), null, null, SourceRef("f.rego", 1), source)),
    )

    private fun pkg(rule: RuleGroup) = PolicyPackage("p", listOf(rule), listOf("f.rego"))

    private fun entry(old: RuleGroup, new: RuleGroup, category: DiffCategory = DiffCategory.LOGIC_CHANGED) =
        DiffEntry(category, new.metadata?.controlId ?: old.metadata?.controlId, pkg(old), old, pkg(new), new)

    private fun render(e: DiffEntry, dataChanges: DataChangeIndex = DataChangeIndex.NONE) =
        DiffRenderer.render(listOf(e), PolicySet(listOf(e.oldPackage ?: e.newPackage!!)), PolicySet(listOf(e.newPackage ?: e.oldPackage!!)), dataChanges)

    @Nested
    @DisplayName("The sub-bullet")
    inner class SubBullet {

        @Test
        @DisplayName("comes after the logic sub-bullets and names the differing fields in table order")
        fun afterLogic() {
            val e = entry(rule(meta(), "a"), rule(meta(description = "D2", custom = mapOf("criticality" to "high")), "b"))

            val bullets = ChangeSummary.subBulletsFor(e)

            assertThat(bullets.last()).isEqualTo("documentation changed: description, custom fields")
            assertThat(bullets.first()).startsWith("condition removed:")
        }

        @Test
        @DisplayName("identical metadata: no documentation line, exactly as before")
        fun identical() {
            val e = entry(rule(meta(), "a"), rule(meta(), "b"))

            assertThat(ChangeSummary.subBulletsFor(e).none { it.startsWith("documentation changed") }).isTrue()
        }

        @Test
        @DisplayName("a data-raised entry (only the data and the docs differ): the data line, then the documentation line, and never the 'structure changed' fallback")
        fun raisedByData() {
            val e = entry(rule(meta(), "a"), rule(meta(description = "D2"), "a"), DiffCategory.LOGIC_CHANGED)

            val bullets = ChangeSummary.subBulletsFor(e, listOf(DataChange(limits, JsonPrimitive(1), JsonPrimitive(2))))

            assertThat(bullets).containsExactly("reference data changed: `data ▸ limits`: `1` -> `2`", "documentation changed: description")
        }

        @Test
        @DisplayName("the fallback is not suppressed: when nothing mechanical was named, it is still there, then the documentation line")
        fun fallbackKept() {
            val e = entry(rule(meta(), "a"), rule(meta(description = "D2"), "a"))

            assertThat(ChangeSummary.subBulletsFor(e)).containsExactly("the rule's structure changed; see the source diff below", "documentation changed: description")
        }

        @Test
        @DisplayName("a rule with no METADATA on either side adds nothing and does not crash")
        fun noMetadata() {
            val e = entry(rule(null, "a"), rule(null, "b"))

            assertThat(ChangeSummary.subBulletsFor(e).none { it.startsWith("documentation changed") }).isTrue()
        }

        @Test
        @DisplayName("LOGIC_CHANGED where the only metadata difference is a declared projection names that projection")
        fun projectionOnly() {
            val e = entry(rule(meta(), "a"), rule(meta(projection = DeclaredProjection(verdict = VerdictProjection("ok"))), "b"))

            assertThat(ChangeSummary.subBulletsFor(e).last()).isEqualTo("documentation changed: verdict")
        }
    }

    @Nested
    @DisplayName("The block in the section")
    inner class Block {

        private fun blockLines(markdown: String): List<String> {
            val lines = markdown.lines()
            val at = lines.indexOf("**Documentation changes**")
            if (at < 0) return emptyList()
            return lines.drop(at + 1).dropWhile { it.isBlank() }.takeWhile { it.startsWith("|") }
        }

        @Test
        @DisplayName("only the rows that differ, base rows included only if they differ, in table order")
        fun differingRowsOnly() {
            val e = entry(rule(meta(), "a"), rule(meta(description = "D2", custom = mapOf("owner" to "Risk")), "b"))

            assertThat(blockLines(render(e))).containsExactly("| | Old | New |", "|---|---|---|", "| Description | D | D2 |", "| Custom fields | — | owner: Risk |")
        }

        @Test
        @DisplayName("a projection-only difference gets its own row")
        fun projectionRow() {
            val e = entry(rule(meta(), "a"), rule(meta(projection = DeclaredProjection(verdict = VerdictProjection("ok", "why"))), "b"))

            assertThat(blockLines(render(e))).containsExactly("| | Old | New |", "|---|---|---|", "| Verdict | — | outcome `ok`, reason `why` |")
        }

        @Test
        @DisplayName("identical metadata, or none at all: no block and no heading")
        fun noBlock() {
            assertThat(render(entry(rule(meta(), "a"), rule(meta(), "b")))).doesNotContain("**Documentation changes**")
            assertThat(render(entry(rule(null, "a"), rule(null, "b")))).doesNotContain("**Documentation changes**")
        }

        @Test
        @DisplayName("CHALLENGE: a multi-line description keeps its <br> in the cell, and a difference that renders identically still gets its row")
        fun multiLineAndIdenticalCells() {
            val multi = entry(rule(meta(description = "one\ntwo"), "a"), rule(meta(description = "one\nthree"), "b"))
            val same = entry(rule(meta(description = "same"), "a"), rule(meta(description = "same  "), "b"))

            assertThat(blockLines(render(multi))).contains("| Description | one<br>two | one<br>three |")
            assertThat(blockLines(render(same))).contains("| Description | same | same |")
        }

        @Test
        @DisplayName("every one of the eleven fields, changed on its own beside a logic change, gets exactly its own row in the block and its name in the sub-bullet")
        fun everyFieldHasItsRow() {
            fun withProjection(projection: DeclaredProjection) = meta(projection = projection)
            val cases = listOf(
                Triple("title", "Title", RuleMetadata("Other", "D", "C-1", listOf("F"))),
                Triple("description", "Description", meta(description = "D2")),
                Triple("frameworks", "Frameworks", RuleMetadata("Title", "D", "C-1", listOf("F", "G"))),
                Triple("authors", "Authors", RuleMetadata("Title", "D", "C-1", listOf("F"), authors = listOf(io.explico.model.Author("A", null)))),
                Triple("related resources", "Related resources", RuleMetadata("Title", "D", "C-1", listOf("F"), relatedResources = listOf(io.explico.model.RelatedResource("r", null)))),
                Triple("custom fields", "Custom fields", meta(custom = mapOf("k" to "v"))),
                Triple("verdict", "Verdict", withProjection(DeclaredProjection(verdict = VerdictProjection("ok")))),
                Triple("actions", "Actions", withProjection(DeclaredProjection(actions = io.explico.model.ActionsProjection("a", "l")))),
                Triple("annotations", "Annotations", withProjection(DeclaredProjection(annotations = io.explico.model.AnnotationsProjection("x")))),
                Triple("timing", "Timing", withProjection(DeclaredProjection(timing = io.explico.model.TimingProjection("d", null)))),
                Triple("mask", "Mask", withProjection(DeclaredProjection(mask = listOf("p")))),
            )
            for ((name, label, changed) in cases) {
                val e = entry(rule(meta(), "a"), rule(changed, "b"))
                val rows = blockLines(render(e)).drop(2)

                assertThat(rows).describedAs(name).hasSize(1)
                assertThat(rows.single()).describedAs(name).startsWith("| $label | ")
                assertThat(ChangeSummary.subBulletsFor(e).last()).describedAs(name).isEqualTo("documentation changed: $name")
            }
        }

        @Test
        @DisplayName("ADDED and REMOVED never get the block, and a DOCS_CHANGED keeps its table with the three base rows and no new heading")
        fun otherCategories() {
            val r = rule(meta(), "a")
            val added = DiffEntry(DiffCategory.ADDED, "C-1", null, null, pkg(r), r)
            val removed = DiffEntry(DiffCategory.REMOVED, "C-1", pkg(r), r, null, null)
            val docs = entry(rule(meta(), "a"), rule(meta(description = "D2"), "a"), DiffCategory.DOCS_CHANGED)

            assertThat(render(added)).doesNotContain("**Documentation changes**")
            assertThat(render(removed)).doesNotContain("**Documentation changes**")
            val markdown = render(docs)
            assertThat(markdown).doesNotContain("**Documentation changes**")
            assertThat(markdown).contains("| Title | Title | Title |", "| Description | D | D2 |", "| Frameworks | F | F |")
            assertThat(markdown).doesNotContain("| Custom fields |")
        }
    }

    @Nested
    @DisplayName("The JSON")
    inner class Json_ {

        private fun fields(e: DiffEntry): List<String>? =
            Json.parseToJsonElement(DiffJson.render(listOf(e), DataChangeIndex(IdentityHashMap(), emptySet()))).jsonObject.getValue("changes").jsonArray.single().jsonObject["fields"]
                ?.jsonArray?.map { it.jsonPrimitive.content }

        @Test
        @DisplayName("a LOGIC_CHANGED change has fields only when its documentation also differs; a DOCS_CHANGED has them as always")
        fun fields() {
            assertThat(fields(entry(rule(meta(), "a"), rule(meta(description = "D2"), "b")))).containsExactly("description")
            assertThat(fields(entry(rule(meta(), "a"), rule(meta(), "b")))).isNull()
            assertThat(fields(entry(rule(meta(), "a"), rule(meta(description = "D2"), "a"), DiffCategory.DOCS_CHANGED))).containsExactly("description")
        }
    }
}
