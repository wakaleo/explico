/**
 * Acceptance tests for the options-object API (spec §14.80 step 2): `Explico.render(policies, RenderOptions)` and `Explico.diff(old, new, DiffOptions)` replace the 14- and 10-parameter
 * forms. Authored from the 1.0 API review, NOT from the frozen acceptance-pack README. Decisions pinned here (operator decisions of 2026-11-01; /tdd must match them):
 *
 * - `PolicySet` gains a LAST field `sourceDir: Path? = null`. `Explico.load(dir)` sets it to `dir.toAbsolutePath().normalize()`; a hand-built `PolicySet(emptyList())` has none. `render` and `diff`
 *   read the policies again from it (worked examples, the digest), so the three `*Dir` parameters disappear.
 * - `RenderOptions(examples, dataDir, generateExamples, json, requirements, sourceUrl, controlId: ControlIdOptions?, groupByControl, companion: CompanionLink?, required)` and
 *   `DiffOptions(groupByControl, data: DataVersions?, examples, labels: VersionLabels?, digests)`; every default means "not asked", so `RenderOptions()` / `DiffOptions()` is the plain call.
 * - THE DIFF HEADER (golden safety): the `**Compared**` block appears ONLY when `digests == true` or `labels != null` -- never merely because the sets know their directory -- so
 *   `diff(old, new, DiffOptions())` stays byte-identical to today's output and the diff-all-categories golden does not move. `DiffReport.oldDigest`/`newDigest` are filled exactly when
 *   the block is shown, and are the SAME `sha256:` digest `render` writes into `index.md` for that directory.
 * - A set without a `sourceDir` cannot be rendered: `IllegalArgumentException` naming `sourceDir` and `Explico.load`. `diff` on such sets still works plainly, but examples, labels or
 *   `digests = true` need the directories and throw the same.
 * - Validation stays at the call, before any opa work, with today's messages: `IllegalArgumentException` (masks stay `InvalidMaskException`, duplicate ids `DuplicateControlIdException`).
 *   Impossible states are unrepresentable and need no test: one-sided data versions or labels, a link label without a link, a template without a pattern.
 *
 * SAFETY NET for the migration: the new API must reproduce the frozen Tier-2 goldens, `docs/sample-output` and the diff-all-categories golden BYTE FOR BYTE (read-only comparisons; no golden is
 * edited), and each option must demonstrably reach the renderer. Until /tdd removes the old long `render` and the old all-defaults `diff(old, new)` -- which the stage-1 stub would otherwise
 * shadow -- every diff call here passes its `DiffOptions` explicitly. Facade only.
 */
package io.explico.acceptance

import io.explico.CompanionLink
import io.explico.ControlIdOptions
import io.explico.DataVersions
import io.explico.DiffOptions
import io.explico.DiffReport
import io.explico.Explico
import io.explico.RenderOptions
import io.explico.RenderedDocs
import io.explico.VersionLabels
import io.explico.diff.DuplicateControlIdException
import io.explico.model.PolicySet
import io.explico.opa.OpaRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

@DisplayName("Realistic policies: the options-object API")
class RealisticOptionsApiAcceptanceIT {

    companion object {
        private val pack = Path.of("src/test/resources/acceptance")
        private val packPolicies = pack.resolve("policies")
        private val samples = Path.of("samples")
        private val realistic = Path.of("src/test/resources/realistic")
        private val diffAll = Path.of("src/test/resources/diff/diff-all-categories")
        private val sha = Regex("sha256:[0-9a-f]{64}")

        private fun load(dir: Path) = Explico.load(dir)

        /** The file [name] of [docs], a failure (not an exception) when the render produced nothing. */
        private fun file(docs: RenderedDocs, name: String): String {
            assertThat(docs.files).describedAs("the rendered files").containsKey(name)
            return docs.files.getValue(name)
        }

        private fun packOptions() = RenderOptions(examples = Explico.loadExamples(pack.resolve("examples")), dataDir = pack.resolve("data/release/data.json"))

        private lateinit var packDocs: RenderedDocs
        private lateinit var packDocsAgain: RenderedDocs
        private lateinit var sampleDocs: RenderedDocs

        @JvmStatic
        @BeforeAll
        fun renderAll() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            packDocs = Explico.render(load(packPolicies), packOptions())
            packDocsAgain = Explico.render(load(packPolicies), packOptions())
            sampleDocs = Explico.render(
                load(samples.resolve("policies")),
                RenderOptions(examples = Explico.loadExamples(samples.resolve("examples")), dataDir = samples.resolve("data/release/data.json")),
            )
        }
    }

    @Nested
    @DisplayName("Equivalence: the new API reproduces what the old one produced")
    inner class Equivalence {

        @Test
        @DisplayName("the acceptance pack renders to the frozen Tier-2 goldens, byte for byte, all six documents")
        fun goldens() {
            val names = listOf("index.md", "release-approvals.md", "release-evidence.md", "release-exemptions.md", "release-governance.md", "release-provenance.md")
            for (name in names) assertThat(file(packDocs, name)).describedAs(name).isEqualTo(Files.readString(pack.resolve("expected").resolve(name)))
        }

        @Test
        @DisplayName("samples/ renders to docs/sample-output, byte for byte (README.md and the marker are not renderer output)")
        fun sampleOutput() {
            val names = Files.list(Path.of("docs/sample-output")).use { s -> s.map { it.fileName.toString() }.filter { it.endsWith(".md") && it != "README.md" }.sorted().toList() }
            assertThat(names).hasSize(6)
            for (name in names) assertThat(file(sampleDocs, name)).describedAs(name).isEqualTo(Files.readString(Path.of("docs/sample-output").resolve(name)))
        }

        @Test
        @DisplayName("the plain diff of diff-all-categories equals its golden report, and carries no Compared block")
        fun diffGolden() {
            val report = Explico.diff(load(diffAll.resolve("old")), load(diffAll.resolve("new")), DiffOptions())

            assertThat(report.markdown).isEqualTo(Files.readString(diffAll.resolve("expected/diff-report.md")))
            assertThat(report.markdown).doesNotContain("**Compared**")
            assertThat(report.oldDigest).isEmpty()
        }

        @Test
        @DisplayName("two renders are byte-identical, and the pack's worked examples and reference data are there")
        fun deterministic() {
            assertThat(packDocsAgain.files.toList()).isEqualTo(packDocs.files.toList())
            assertThat(file(packDocs, "release-approvals.md")).contains("**Worked examples**")
            assertThat(file(packDocs, "release-exemptions.md")).contains("**Reference data**")
        }
    }

    @Nested
    @DisplayName("Every RenderOptions field reaches the renderer")
    inner class RenderOptionsReachTheRenderer {

        private val grouped = realistic.resolve("group-by-control/policies")
        private val companionPolicies = realistic.resolve("companion-source/policies")
        private val idPolicies = realistic.resolve("control-id-from-filename/policies")

        @Test
        @DisplayName("json: explico.json is written when asked and absent by default")
        fun json() {
            val on = Explico.render(load(grouped), RenderOptions(json = true))
            val off = Explico.render(load(grouped), RenderOptions())

            assertThat(on.files).containsKey("explico.json")
            assertThat(off.files).isNotEmpty().doesNotContainKey("explico.json")
        }

        @Test
        @DisplayName("groupByControl: a control id shared by several rules gets its own page under controls/")
        fun groupByControl() {
            val on = Explico.render(load(grouped), RenderOptions(groupByControl = true))
            val off = Explico.render(load(grouped), RenderOptions())

            assertThat(on.files).containsKey("controls/sec-28.md")
            assertThat(off.files).isNotEmpty().doesNotContainKey("controls/sec-28.md")
        }

        @Test
        @DisplayName("controlId: a file-name pattern and template derive CTRL-19 for a rule with no METADATA id, and nothing is derived without it")
        fun controlId() {
            val on = Explico.render(load(idPolicies), RenderOptions(controlId = ControlIdOptions("ctrl_(?<id>\\d+)", "CTRL-{id}")))
            val off = Explico.render(load(idPolicies), RenderOptions())

            assertThat(file(on, "fname_nineteen.md")).contains("## CTRL-19 — signed_off")
            assertThat(file(off, "fname_nineteen.md")).doesNotContain("CTRL-19")
        }

        @Test
        @DisplayName("the default template is {id}: ControlIdOptions(from) writes the bare captured number")
        fun controlIdDefaultTemplate() {
            val docs = Explico.render(load(idPolicies), RenderOptions(controlId = ControlIdOptions("ctrl_(?<id>\\d+)")))

            assertThat(file(docs, "fname_nineteen.md")).contains("## 19 — signed_off")
        }

        @Test
        @DisplayName("sourceUrl and companion: the 'defined in' line links the source, then the companion with the chosen label; the default label is 'module'")
        fun links() {
            val source = "https://git.example.com/org/repo/blob/abc123/policies/{file}#L{line}"
            val companion = "https://modules.example.com/{package}/src/{file}#L{line}"
            val both = Explico.render(load(companionPolicies), RenderOptions(sourceUrl = source, companion = CompanionLink(companion)))
            val labelled = Explico.render(load(companionPolicies), RenderOptions(sourceUrl = source, companion = CompanionLink(companion, "Billing module")))
            val plain = Explico.render(load(companionPolicies), RenderOptions())

            val line = file(both, "app-billing.md").lines().single { it.startsWith("*Rule `charge_ok` in package") }
            assertThat(line).contains("[`charge.rego:8`](https://git.example.com/org/repo/blob/abc123/policies/billing/charge.rego#L8)")
                .contains("[module](https://modules.example.com/app.billing/src/billing/charge.rego#L8)")
            assertThat(file(labelled, "app-billing.md")).contains("[Billing module](https://modules.example.com/app.billing/src/billing/charge.rego#L8)")
            assertThat(file(plain, "app-billing.md")).doesNotContain("example.com")
        }

        @Test
        @DisplayName("requirements: the index gains a requirements-coverage table, and has none by default")
        fun requirements() {
            val with = Explico.render(load(packPolicies), packOptions().copy(requirements = listOf("SOC 2 CC8.1", "ISO 27001 A.8.32")))

            assertThat(file(with, "index.md")).contains("## Requirements coverage").contains("SOC 2 CC8.1")
            assertThat(file(packDocs, "index.md")).doesNotContain("## Requirements coverage")
        }

        @Test
        @DisplayName("required: naming every property makes the missing fixture a gap; the default set reports none")
        fun required() {
            val dir = realistic.resolve("situation-coverage")
            val examples = Explico.loadExamples(dir.resolve("examples"))
            val all = Explico.render(load(dir.resolve("policies")), RenderOptions(examples = examples, required = listOf("title", "description", "frameworks", "fixture", "verdict")))
            val default = Explico.render(load(dir.resolve("policies")), RenderOptions(examples = examples))

            assertThat(all.gaps).containsExactly("control SC-4 has no matching example")
            assertThat(default.gaps).isEmpty()
        }

        @Test
        @DisplayName("generateExamples: generated examples are derived, verified and summarised when asked, and absent by default")
        fun generateExamples() {
            val dir = realistic.resolve("generated-examples/policies")
            val on = Explico.render(load(dir), RenderOptions(generateExamples = true))
            val off = Explico.render(load(dir), RenderOptions())

            assertThat(on.generatedExamples).isNotNull()
            assertThat(file(on, "genex.md")).contains("**Generated examples**")
            assertThat(off.generatedExamples).isNull()
            assertThat(file(off, "genex.md")).doesNotContain("Generated examples")
        }
    }

    @Nested
    @DisplayName("Every DiffOptions field reaches the diff")
    inner class DiffOptionsReachTheDiff {

        private val dataBase = realistic.resolve("data-aware-diff")
        private val verdictBase = realistic.resolve("fixture-verdict-diff")
        private val headerBase = realistic.resolve("metadata-with-logic")

        private fun renderDigest(dir: Path): String =
            sha.find(file(Explico.render(load(dir), RenderOptions()), "index.md"))?.value ?: error("no digest in index.md of $dir")

        @Test
        @DisplayName("groupByControl: a control id shared by several rules is an error by default and a diff with the option")
        fun groupByControl() {
            val old = load(realistic.resolve("group-by-control-diff/old"))
            val new = load(realistic.resolve("group-by-control-diff/new"))

            assertThat(catchThrowable { Explico.diff(old, new, DiffOptions()) }).isInstanceOf(DuplicateControlIdException::class.java)
            assertThat(Explico.diff(old, new, DiffOptions(groupByControl = true)).entries).isNotEmpty()
        }

        @Test
        @DisplayName("data: the same policies with different reference data are LOGIC_CHANGED, naming the data path; without the data versions nothing changed")
        fun data() {
            val policies = load(dataBase.resolve("policies/old"))

            val with = Explico.diff(policies, policies, DiffOptions(data = DataVersions(dataBase.resolve("data/old.json"), dataBase.resolve("data/limit-changed.json"))))
            val without = Explico.diff(policies, policies, DiffOptions())

            assertThat(with.markdown).contains("| LOGIC_CHANGED | 1 |").contains("`data ▸ limits ▸ single approver minor`")
            assertThat(without.markdown).doesNotContain("| LOGIC_CHANGED | 1 |")
        }

        @Test
        @DisplayName("examples: the report lists the fixtures whose outcome differs, and has no such section without them")
        fun examples() {
            val old = load(verdictBase.resolve("policies/old"))
            val new = load(verdictBase.resolve("policies/new"))
            val fixtures = Explico.loadExamples(verdictBase.resolve("examples"))

            assertThat(Explico.diff(old, new, DiffOptions(examples = fixtures)).markdown).contains("## Behaviour on your examples")
            assertThat(Explico.diff(old, new, DiffOptions()).markdown).doesNotContain("## Behaviour on your examples")
        }

        @Test
        @DisplayName("digests alone adds the Compared block with the same digest render writes into index.md, fills DiffReport.oldDigest/newDigest and the JSON 'compared'; the default has none of it")
        fun digests() {
            val old = load(headerBase.resolve("old"))
            val new = load(headerBase.resolve("new"))

            val shown = Explico.diff(old, new, DiffOptions(digests = true))
            val plain = Explico.diff(old, new, DiffOptions())

            assertThat(shown.markdown.lines()).contains("**Compared**", "- Old: `${renderDigest(headerBase.resolve("old"))}`", "- New: `${renderDigest(headerBase.resolve("new"))}`")
            assertThat(shown.oldDigest).isEqualTo(renderDigest(headerBase.resolve("old")))
            assertThat(shown.newDigest).isEqualTo(renderDigest(headerBase.resolve("new")))
            assertThat(shown.json).contains("\"compared\"")
            assertThat(plain.markdown).doesNotContain("**Compared**")
            assertThat(plain.json).doesNotContain("\"compared\"")
            assertThat(plain.oldDigest).isEmpty()
        }

        @Test
        @DisplayName("labels alone switch the Compared block on and name the versions")
        fun labels() {
            val old = load(headerBase.resolve("old"))
            val new = load(headerBase.resolve("new"))

            val report = Explico.diff(old, new, DiffOptions(labels = VersionLabels("v1.2", "v1.3")))

            assertThat(report.markdown.lines()).contains("- Old (v1.2): `${renderDigest(headerBase.resolve("old"))}`", "- New (v1.3): `${renderDigest(headerBase.resolve("new"))}`")
            assertThat(report.oldDigest).isEqualTo(renderDigest(headerBase.resolve("old")))
        }
    }

    @Nested
    @DisplayName("PolicySet.sourceDir")
    inner class SourceDir {

        @Test
        @DisplayName("load sets it to the absolute, normalised directory, however the path was written; a hand-built set has none")
        fun loaded() {
            val roundabout = Path.of("src/test/resources/realistic/../acceptance/policies")

            assertThat(load(packPolicies).sourceDir).isEqualTo(packPolicies.toAbsolutePath().normalize())
            assertThat(load(roundabout).sourceDir).isEqualTo(packPolicies.toAbsolutePath().normalize())
            assertThat(PolicySet(emptyList()).sourceDir).isNull()
        }

        @Test
        @DisplayName("render refuses a set with no sourceDir, saying where to get one")
        fun renderNeedsIt() {
            assertThatThrownBy { Explico.render(PolicySet(emptyList()), RenderOptions()) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("sourceDir").hasMessageContaining("Explico.load")
        }

        @Test
        @DisplayName("diff of hand-built sets works plainly, but examples, labels or digests need the directories and say so")
        fun diffNeedsItOnlyWhenAsked() {
            val hand = PolicySet(emptyList())

            assertThat(Explico.diff(hand, hand, DiffOptions()).entries).isEmpty()
            for (options in listOf(DiffOptions(digests = true), DiffOptions(labels = VersionLabels("a", "b")), DiffOptions(examples = Explico.loadExamples(pack.resolve("examples"))))) {
                assertThatThrownBy { Explico.diff(hand, hand, options) }.describedAs("%s", options)
                    .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("sourceDir").hasMessageContaining("Explico.load")
            }
        }
    }

    @Nested
    @DisplayName("Validation happens at the call, with today's messages")
    inner class Validation {

        private val set get() = load(packPolicies)

        private fun refused(message: String, options: RenderOptions) {
            assertThatThrownBy { Explico.render(set, options) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining(message)
        }

        @Test
        @DisplayName("a source URL template without {file}")
        fun sourceUrl() = refused("{file}", RenderOptions(sourceUrl = "https://example.com/blob/main/policies"))

        @Test
        @DisplayName("a control-id pattern without the named group id, and a template without {id}")
        fun controlId() {
            refused("named group `id`", RenderOptions(controlId = ControlIdOptions("ctrl_(\\d+)")))
            refused("{id}", RenderOptions(controlId = ControlIdOptions("ctrl_(?<id>\\d+)", "CTRL-")))
        }

        @Test
        @DisplayName("a companion template without {file} or {package}, and a label that is not plain text")
        fun companion() {
            refused("{file}", RenderOptions(companion = CompanionLink("https://example.com/modules")))
            refused("must be plain text", RenderOptions(companion = CompanionLink("https://example.com/{file}", "[module]")))
            refused("must be plain text", RenderOptions(companion = CompanionLink("https://example.com/{file}", "two\nlines")))
        }

        @Test
        @DisplayName("a required property that does not exist, and fixture/verdict without any example fixture")
        fun required() {
            refused("not a documentation property", RenderOptions(required = listOf("colour")))
            refused("needs example fixtures", RenderOptions(required = listOf("fixture")))
        }

        @Test
        @DisplayName("version labels must be plain text and are named oldLabel and newLabel")
        fun labels() {
            val old = load(realistic.resolve("metadata-with-logic/old"))
            assertThatThrownBy { Explico.diff(old, old, DiffOptions(labels = VersionLabels("two\nlines", "ok"))) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("oldLabel")
            assertThatThrownBy { Explico.diff(old, old, DiffOptions(labels = VersionLabels("ok", "`tick`"))) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("newLabel")
        }
    }
}
