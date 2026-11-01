/**
 * CHALLENGE cases for the per-fixture verdict diff (spec §14.74) that need the real `opa` binary and a small purpose-built policy each, written to a temp directory:
 * a fixture one side cannot evaluate, `--group-by-control`, Rego v0, a docs-only change, an empty examples set. The main contract is
 * RealisticFixtureVerdictDiffAcceptanceIT; these pin the edges around it.
 */
package io.explico.diff

import io.explico.Explico
import io.explico.DiffOptions
import io.explico.model.RegoVersion
import io.explico.opa.OpaRunner
import io.explico.render.ExampleSet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ExampleChangesChallengeTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
        }

        private fun meta(id: String, title: String = "Title $id") = """
            # METADATA
            # scope: document
            # title: $title
            # description: Description $id
            # custom:
            #   control-id: $id
            #   frameworks:
            #     - F 1
        """.trimIndent() + "\n"

        private fun write(dir: Path, name: String, text: String): Path = dir.resolve(name).also { Files.createDirectories(it.parent); Files.writeString(it, text) }

        private fun fixture(dir: Path, name: String, json: String) = write(dir, "examples/$name.json", """{"name": "$name", "input": $json}""")

        private fun diff(dir: Path, oldName: String = "old", newName: String = "new", group: Boolean = false, version: RegoVersion = RegoVersion.V1) =
            Explico.diff(Explico.load(dir.resolve(oldName), version), Explico.load(dir.resolve(newName), version), DiffOptions(groupByControl = group, examples = Explico.loadExamples(dir.resolve("examples")), digests = true))

        private const val DENY_AMOUNT = "deny contains msg if {\n\tinput.amount > %d\n\tmsg := \"too much\"\n}\n"
        private fun denyRule(id: String, pkg: String, limit: Int, title: String = "Title $id") = "package $pkg\n\nimport rego.v1\n\n" + meta(id, title) + DENY_AMOUNT.format(limit)
    }

    @Test
    @DisplayName("CHALLENGE: a fixture opa cannot evaluate on ONE side (a complete-rule conflict) is skipped there and not compared: no bogus flip, the others still are")
    fun fixtureFailsOnOneSide(@TempDir dir: Path) {
        write(dir, "old/a.rego", "package a\n\nimport rego.v1\n\n" + meta("C-1") + "check := \"x\" if input.p\n\ncheck := \"y\" if input.q\n")
        write(dir, "new/a.rego", "package a\n\nimport rego.v1\n\n" + meta("C-1") + "check := \"x\" if input.p\n")
        fixture(dir, "p-only", """{"p": true}""")
        fixture(dir, "both", """{"p": true, "q": true}""")
        fixture(dir, "q-only", """{"q": true}""")

        val report = diff(dir)

        assertThat(report.exampleChanges.map { it.fixture }).containsExactly("q-only")
        assertThat(report.markdown).contains("q-only: matched -> not matched").doesNotContain("both:")
    }

    @Test
    @DisplayName("--group-by-control: two rules of one shared control id, each in its own package, each report their own flips under a rule-qualified label")
    fun groupedControl(@TempDir dir: Path) {
        write(dir, "old/a.rego", denyRule("SHARED", "a", 100))
        write(dir, "old/b.rego", denyRule("SHARED", "b", 100))
        write(dir, "new/a.rego", denyRule("SHARED", "a", 50))
        write(dir, "new/b.rego", denyRule("SHARED", "b", 100))
        fixture(dir, "seventy", """{"amount": 70}""")

        val report = diff(dir, group = true)

        assertThat(report.exampleChanges).containsExactly(ExampleChange("SHARED", "seventy", "✅ allowed", "❌ denied"))
        assertThat(report.markdown.substringAfter("## Behaviour on your examples").substringBefore("## Changes"))
            .contains("- [SHARED — Title SHARED (a.deny)](#shared-a-deny)").doesNotContain("(b.deny)")
    }

    @Test
    @DisplayName("Rego v0 policies are evaluated with the set's own syntax version")
    fun regoV0(@TempDir dir: Path) {
        fun v0(limit: Int) = "package a\n\n" + meta("C-1") + "deny[msg] {\n\tinput.amount > $limit\n\tmsg := \"too much\"\n}\n"
        write(dir, "old/a.rego", v0(100))
        write(dir, "new/a.rego", v0(50))
        fixture(dir, "seventy", """{"amount": 70}""")

        val report = diff(dir, version = RegoVersion.V0)

        assertThat(report.exampleChanges).containsExactly(ExampleChange("C-1", "seventy", "✅ allowed", "❌ denied"))
    }

    @Test
    @DisplayName("a control that is only DOCS_CHANGED cannot flip: the section says nothing changed, and the category is untouched")
    fun docsOnly(@TempDir dir: Path) {
        write(dir, "old/a.rego", denyRule("C-1", "a", 100, title = "Old title"))
        write(dir, "new/a.rego", denyRule("C-1", "a", 100, title = "New title"))
        fixture(dir, "seventy", """{"amount": 70}""")

        val report = diff(dir)

        assertThat(report.entries.single().category).isEqualTo(DiffCategory.DOCS_CHANGED)
        assertThat(report.exampleChanges).isEmpty()
        assertThat(report.markdown).contains("No example changed outcome.")
    }

    @Test
    @DisplayName("an examples set with no fixture counts as not supplied: no section, no JSON key, no changes")
    fun emptyExamples(@TempDir dir: Path) {
        write(dir, "old/a.rego", denyRule("C-1", "a", 100))
        write(dir, "new/a.rego", denyRule("C-1", "a", 50))

        val report = Explico.diff(Explico.load(dir.resolve("old")), Explico.load(dir.resolve("new")), DiffOptions(examples = ExampleSet(emptyList()), digests = true))

        assertThat(report.markdown).doesNotContain("Behaviour on your examples")
        assertThat(report.json).doesNotContain("\"examples\"")
        assertThat(report.exampleChanges).isEmpty()
    }

    @Test
    @DisplayName("CHALLENGE: controls that cannot be compared (functions only) are said to be: no 'No example changed outcome.', and the comparison counts them")
    fun nothingComparable(@TempDir dir: Path) {
        fun fn(limit: Int) = "package a\n\nimport rego.v1\n\n" + meta("C-1") + "is_big(x) if x > $limit\n"
        write(dir, "old/a.rego", fn(100))
        write(dir, "new/a.rego", fn(50))
        fixture(dir, "seventy", """{"amount": 70}""")

        val report = diff(dir)

        assertThat(report.exampleComparison).isEqualTo(ExampleComparison(compared = 0, total = 1, functions = 1, unattributable = 0, fixturesOnOneVersion = 0))
        assertThat(report.markdown).contains("No example could be compared.").doesNotContain("No example changed outcome.")
        assertThat(report.markdown).contains("*Compared 0 of 1 controls present in both versions; not compared: 1 function(s).*")
    }

    @Test
    @DisplayName("CHALLENGE: a fixture one version cannot evaluate is counted in the comparison and withdraws the plain sentence")
    fun fixtureOnOneVersionIsCounted(@TempDir dir: Path) {
        write(dir, "old/a.rego", "package a\n\nimport rego.v1\n\n" + meta("C-1") + "check := \"x\" if input.p\n\ncheck := \"y\" if input.q\n")
        write(dir, "new/a.rego", "package a\n\nimport rego.v1\n\n" + meta("C-1") + "check := \"x\" if input.p\n")
        fixture(dir, "both", """{"p": true, "q": true}""")
        fixture(dir, "p-only", """{"p": true}""")

        val report = diff(dir)

        assertThat(report.exampleComparison).isEqualTo(ExampleComparison(1, 1, 0, 0, fixturesOnOneVersion = 1))
        assertThat(report.markdown).contains("No compared example changed outcome.").contains("1 fixture(s) evaluated on one version only")
    }
}
