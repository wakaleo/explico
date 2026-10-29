/**
 * Spec §14.55 through the facade, WITHOUT opa: a hand-built policy set rendered with `controlIdFrom`. What the acceptance test cannot reach
 * cheaply -- the awkward corners of a derived id once it flows through headings, anchors, the index and the quality findings. (With no
 * worked examples `render` never calls opa, so these are plain unit tests.)
 */
package io.explico

import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RuleBody
import io.explico.model.RuleGroup
import io.explico.model.RuleMetadata
import io.explico.model.SourceRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ControlIdFromFilenameRenderTest {

    private fun rule(name: String, file: String, metadata: RuleMetadata? = null) =
        RuleGroup(name, metadata, null, listOf(RuleBody(emptyList(), null, null, SourceRef(file, 1), "")))

    private fun pkg(path: String, vararg rules: RuleGroup) = PolicyPackage(path, rules.toList(), rules.map { (it.bodies.single().sourceLocation).file }.distinct())

    private fun render(dir: Path, vararg packages: PolicyPackage, from: String? = "ctrl_(?<id>[^.]+)", template: String = "CTRL-{id}", requirements: List<String> = emptyList()): RenderedDocs {
        Files.writeString(dir.resolve("p.rego"), "package p\n")
        return Explico.render(PolicySet(packages.toList()), dir, requirements = requirements, controlIdFrom = from, controlIdTemplate = template)
    }

    private fun anchors(markdown: String) = Regex("<a id=\"([^\"]*)\"></a>").findAll(markdown).map { it.groupValues[1] }.toList()

    @Test
    @DisplayName("CHALLENGE: the same derived id on rules in DIFFERENT files and packages, and one equal to a METADATA id elsewhere, render without error; each page's anchors stay unique")
    fun sharedIdsDoNotBreakTheRender(@TempDir dir: Path) {
        val explicit = rule("m", "other.rego", RuleMetadata("T", null, "CTRL-5", emptyList()))
        val docs = render(dir, pkg("one", rule("a", "ctrl_5.rego"), explicit), pkg("two", rule("b", "sub/ctrl_5.rego"), rule("c", "ctrl_5.rego")))

        for (page in listOf("one.md", "two.md")) {
            val ids = anchors(docs.files.getValue(page))
            assertThat(ids).describedAs(page).isNotEmpty().doesNotHaveDuplicates()
        }
        assertThat(docs.files.getValue("one.md")).contains("## CTRL-5 — a").contains("## CTRL-5 — T")
        assertThat(docs.files.getValue("two.md")).contains("## CTRL-5 — b").contains("## CTRL-5 — c")
        assertThat(docs.files.getValue("index.md").lines().count { it.startsWith("| CTRL-5 |") }).isEqualTo(4)
    }

    @Test
    @DisplayName("CHALLENGE: an id with markdown punctuation (brackets, hash, backtick) is shown as written in the heading, slugged for the anchor, and leaves every index row intact")
    fun markdownPunctuationInAnId(@TempDir dir: Path) {
        val docs = render(dir, pkg("one", rule("a", "ctrl_[x]#`y`.rego")))

        assertThat(docs.files.getValue("one.md")).contains("## CTRL-[x]#`y` — a").contains("<a id=\"ctrl-x-y\"></a>")
        val header = docs.files.getValue("index.md").lines().first { it.startsWith("| Control ID") }
        val row = docs.files.getValue("index.md").lines().first { it.startsWith("| CTRL-[x]#`y` |") }
        assertThat(row.count { it == '|' }).describedAs("same number of cells as the header").isEqualTo(header.count { it == '|' })
    }

    @Test
    @DisplayName("CHALLENGE: unicode in an id is shown as written; two ids that slug to the same anchor do not crash and keep anchors unique")
    fun unicodeAndSlugCollisions(@TempDir dir: Path) {
        val docs = render(dir, pkg("one", rule("a", "ctrl_é1.rego"), rule("b", "ctrl_1.rego")), from = "ctrl_(?<id>[^.]+)")

        val markdown = docs.files.getValue("one.md")
        assertThat(markdown).contains("## CTRL-é1 — a").contains("## CTRL-1 — b")
        assertThat(anchors(markdown)).doesNotHaveDuplicates().isNotEmpty()
    }

    @Test
    @DisplayName("an id refused as unsafe leaves the rule without an id (and the render goes on)")
    fun unsafeIdIsSkipped(@TempDir dir: Path) {
        val original = System.err
        val captured = java.io.ByteArrayOutputStream()
        System.setErr(java.io.PrintStream(captured, true, Charsets.UTF_8))
        val docs = try { render(dir, pkg("one", rule("a", "ctrl_x|y.rego"), rule("b", "ctrl_ok.rego"))) } finally { System.setErr(original) }

        assertThat(docs.files.getValue("one.md")).contains("## one.a").contains("## CTRL-ok — b")
        assertThat(captured.toString(Charsets.UTF_8).lines().filter { it.contains("not deriving a control id") }).hasSize(1)
    }

    @Test
    @DisplayName("a derived-id control has no description or framework, so the quality findings (and `gaps`) list it -- as they list any control without them")
    fun qualityFindingsSeeDerivedControls(@TempDir dir: Path) {
        val docs = render(dir, pkg("one", rule("a", "ctrl_5.rego"), rule("z", "plain.rego")))

        assertThat(docs.gaps).anyMatch { it.contains("CTRL-5") && it.contains("description") }
        assertThat(docs.gaps).anyMatch { it.contains("CTRL-5") && it.contains("framework") }
        assertThat(docs.gaps).noneMatch { it.contains("plain.rego") || it.contains("one.z") }
    }

    @Test
    @DisplayName("CHALLENGE: requirements -- a derived control declares no frameworks, so it covers no requirement")
    fun requirementsAreNotCoveredByDerivedControls(@TempDir dir: Path) {
        val docs = render(dir, pkg("one", rule("a", "ctrl_5.rego")), requirements = listOf("SOC 2 CC8.1"))

        assertThat(docs.gaps).anyMatch { it.contains("SOC 2 CC8.1") }
    }

    @Test
    @DisplayName("deterministic: two renders of the same set are byte-identical")
    fun deterministic(@TempDir dir: Path) {
        val packages = arrayOf(pkg("one", rule("a", "ctrl_2.rego"), rule("b", "ctrl_1.rego")), pkg("two", rule("c", "ctrl_3.rego")))
        assertThat(render(dir, *packages).files).isEqualTo(render(dir, *packages).files)
    }

    @Test
    @DisplayName("an invalid option is reported before anything is rendered")
    fun invalidOptionsFailFast(@TempDir dir: Path) {
        org.assertj.core.api.Assertions.assertThatThrownBy { render(dir, pkg("one", rule("a", "ctrl_5.rego")), from = "ctrl_(\\d+)") }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("(?<id>")
        org.assertj.core.api.Assertions.assertThatThrownBy { render(dir, pkg("one", rule("a", "ctrl_5.rego")), from = null, template = "CTRL-{id}") }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("control-id-from")
    }

    @Test
    @DisplayName("provenance: a derived id is marked on its card (with the file it came from), a METADATA id is not")
    fun cardSaysWhereTheIdCameFrom(@TempDir dir: Path) {
        val explicit = rule("m", "ctrl_6.rego", RuleMetadata("T", null, "SEC-6", emptyList()))
        val docs = render(dir, pkg("one", rule("a", "team/ctrl_5.rego"), explicit, rule("plain", "helpers.rego")))

        val cards = docs.files.getValue("one.md").split("\n---\n")
        assertThat(cards.single { it.contains("## CTRL-5 — a") }).contains("*Control id derived from the file name `ctrl_5.rego`*")
        assertThat(cards.single { it.contains("## SEC-6 — T") }).doesNotContain("derived from the file name")
        assertThat(cards.single { it.contains("## one.plain") }).doesNotContain("derived from the file name")
    }

    @Test
    @DisplayName("provenance: the index legend explains derived ids -- only when one exists")
    fun indexLegend(@TempDir dir: Path) {
        val withDerived = render(dir, pkg("one", rule("a", "ctrl_5.rego"))).files.getValue("index.md")
        assertThat(withDerived).contains("**Derived control ids**").contains("--control-id-from").contains("not declared in METADATA")

        val none = render(dir, pkg("one", rule("a", "helpers.rego"))).files.getValue("index.md")
        assertThat(none).doesNotContain("Derived control ids")
        assertThat(render(dir, pkg("one", rule("a", "ctrl_5.rego")), from = null, template = "{id}").files.getValue("index.md")).doesNotContain("Derived control ids")
    }

    @Test
    @DisplayName("provenance: explico.json flags a derived id (`controlIdDerived: true`) and has no such key otherwise")
    fun jsonFlag(@TempDir dir: Path) {
        Files.writeString(dir.resolve("p.rego"), "package p\n")
        val set = PolicySet(listOf(pkg("one", rule("a", "ctrl_5.rego"), rule("m", "ctrl_6.rego", RuleMetadata("T", null, "SEC-6", emptyList())), rule("p", "helpers.rego"))))
        val json = Explico.render(set, dir, json = true, controlIdFrom = "ctrl_(?<id>[^.]+)", controlIdTemplate = "CTRL-{id}").files.getValue("explico.json")
        val rules = kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject
        val byName = (rules.getValue("packages") as kotlinx.serialization.json.JsonArray).flatMap { (it as kotlinx.serialization.json.JsonObject).getValue("rules") as kotlinx.serialization.json.JsonArray }
            .associate { r -> (r as kotlinx.serialization.json.JsonObject).let { (it.getValue("name") as kotlinx.serialization.json.JsonPrimitive).content to it } }

        assertThat(byName.getValue("a")).containsKey("controlIdDerived")
        assertThat((byName.getValue("a").getValue("controlIdDerived") as kotlinx.serialization.json.JsonPrimitive).content).isEqualTo("true")
        assertThat(byName.getValue("m")).doesNotContainKey("controlIdDerived")
        assertThat(byName.getValue("p")).doesNotContainKey("controlIdDerived")
    }
}
