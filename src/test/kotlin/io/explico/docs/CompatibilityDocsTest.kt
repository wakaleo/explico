/**
 * `docs/compatibility.md` (spec §14.80 step 6): what a consumer can rely on in 1.x, what they cannot, how a removal is announced, and how the two committed baselines are updated. No opa; the page is a
 * declared input of the `test` task, so editing it re-runs this. The page and the code must agree where they can: the JSON schema ids are the code's constants, the fact kinds are exactly `FactKind`,
 * the exit-code meanings are the CLI baseline's, and every new-1.0 name the promise rests on is spelled on the page.
 *
 * Pinned phrases (case-insensitive) are the decisions, not prose style: `a new fact kind means a new schema version` (the strict typed-facts rule, operator decision 2026-11-01),
 * `additive fields do not bump the schema`, `one minor release of warnings` before removal in the next major, `destructuring` of a marked property is `not warned about` yet equally unstable, and the
 * update commands `./gradlew updateKotlinAbi` and `-Dexplico.updateCli=true` as a `reviewed change`.
 */
package io.explico.docs

import io.explico.diff.DiffJson
import io.explico.diff.FactKind
import io.explico.render.JsonExport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class CompatibilityDocsTest {

    private val path = Path.of("docs/compatibility.md")

    private fun page(): String {
        assertThat(path).describedAs("the compatibility page docs/compatibility.md").exists()
        return Files.readString(path)
    }

    private fun lower() = page().lowercase()

    private fun section(heading: String): String {
        val text = page()
        val start = text.indexOf("\n$heading\n")
        assertThat(start).describedAs("a '%s' section in docs/compatibility.md", heading).isGreaterThanOrEqualTo(0)
        val end = text.indexOf("\n## ", start + heading.length + 2).let { if (it < 0) text.length else it }
        return text.substring(start, end)
    }

    @Test
    @DisplayName("the four sections exist")
    fun sections() {
        for (heading in listOf("## What is stable", "## What is not stable", "## Deprecation", "## Updating the baselines")) section(heading)
    }

    @Test
    @DisplayName("stable: the facade, the options, the results, the exceptions by name, the commands and every exit code with the baseline's meaning, and the two JSON schema ids as the code has them")
    fun stable() {
        val stable = section("## What is stable")

        for (name in listOf(
            "Explico", "load", "loadExamples", "render", "diff", "RenderOptions", "DiffOptions", "ControlIdOptions", "CompanionLink", "DataVersions", "VersionLabels",
            "RenderedDocs", "DiffReport", "changes", "markdown", "json", "PolicySet", "packages", "regoVersion", "sourceDir",
            "ExplicoException", "OpaFailedException", "OpaNotAvailableException", "OutputDirectoryException", "DuplicateControlIdException", "DuplicateFixtureNameException", "InvalidMaskException",
            "explico render", "explico diff", "explico demo", "explico version",
        )) assertThat(stable).describedAs("the stable section names %s", name).contains(name)
        for ((code, meaning) in listOf(
            0 to "success", 1 to "usage or refused output", 2 to "opa rejected the input", 3 to "opa missing or not 1.x", 4 to "duplicate control id or fixture name", 5 to "a CI gate failed",
        )) assertThat(Regex("(?m)^\\|\\s*`?$code`?\\s*\\|.*" + Regex.escape(meaning)).containsMatchIn(page())).describedAs("an exit-code row for %s: %s", code, meaning).isTrue()
        assertThat(stable).contains(JsonExport.SCHEMA).contains(DiffJson.SCHEMA)
        assertThat(lower()).contains("additive fields do not bump the schema")
    }

    @Test
    @DisplayName("stable includes the CLI's flags by reference: the page links both committed baselines by path")
    fun linksTheBaselines() {
        assertThat(page()).contains("api/explico.api").contains("api/explico.cli")
    }

    @Test
    @DisplayName("not stable: the Markdown wording and layout, the opt-in structural model (and that destructuring a marked property is not warned about but equally unstable), stderr text, and generated file names other than index.md, explico.json and controls/")
    fun notStable() {
        val notStable = section("## What is not stable").lowercase()

        assertThat(notStable).contains("markdown").contains("wording").contains("layout").contains("explicostructuralmodel").contains("stderr")
            .contains("index.md").contains("explico.json").contains("controls/")
            .contains("destructuring").contains("not warned about").contains("equally unstable")
    }

    @Test
    @DisplayName("deprecation: from 1.0, one minor release of warnings before a removal in the next major")
    fun deprecation() {
        val deprecation = section("## Deprecation").lowercase()

        assertThat(deprecation).contains("one minor release of warnings").contains("next major").contains("1.0")
    }

    @Test
    @DisplayName("typed diff facts are strict: a new fact kind means a new schema version, and the page lists exactly the FactKind wire names")
    fun strictFacts() {
        assertThat(lower()).contains("a new fact kind means a new schema version")
        val facts = section("## Typed diff facts")
        val documented = Regex("(?m)^\\| `([a-z]+(?:-[a-z]+)+)` \\|").findAll(facts).map { it.groupValues[1] }.toList()

        assertThat(documented).containsExactlyInAnyOrderElementsOf(FactKind.entries.map { it.wire })
        assertThat(documented.toSet()).hasSameSizeAs(documented)
    }

    @Test
    @DisplayName("updating: ./gradlew updateKotlinAbi for the API baseline and -Dexplico.updateCli=true for the CLI baseline, both a reviewed change")
    fun updating() {
        val updating = section("## Updating the baselines")

        assertThat(updating).contains("./gradlew updateKotlinAbi").contains("-Dexplico.updateCli=true").contains("checkKotlinAbi")
        assertThat(updating.lowercase()).contains("reviewed change")
    }

    @Test
    @DisplayName("the page is wired in: mkdocs nav, README, docs/index.md and the user guide link to compatibility.md")
    fun wiring() {
        assertThat(Files.readString(Path.of("mkdocs.yml"))).describedAs("mkdocs.yml nav").contains("compatibility.md")
        for (file in listOf("README.md", "docs/index.md", "docs/user-guide.md")) assertThat(Files.readString(Path.of(file))).describedAs("%s links the page", file).contains("compatibility.md")
    }
}
