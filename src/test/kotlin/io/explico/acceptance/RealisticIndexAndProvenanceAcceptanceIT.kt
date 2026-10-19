/**
 * Acceptance tests for the compliance-owner quick wins on `index.md` and the package documents. Authored from a
 * compliance-owner review of the real output, not from the frozen README contract (the frozen IndexAcceptanceIT keeps passing
 * untouched). They pin:
 *  - TITLE LINKS: every index row's title links to its card, and every link in the index resolves to a real file and anchor.
 *  - `Coverage` becomes `Rendered`, and a legend explains it (a compliance reader reads "coverage" as control coverage).
 *  - A "Controls by framework" matrix, built from METADATA exactly as written.
 *  - A POLICY DIGEST (SHA-256 over the `.rego` files) tying every document to an exact policy version; line-ending
 *    differences must not change it and a changed comment must.
 * Facade only. Frameworks below were checked against the pack's real METADATA (REL-002 also names SOC 2 CC8.1).
 */
package io.explico.acceptance

import io.explico.Explico
import io.explico.opa.OpaRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@DisplayName("Index usability and provenance")
class RealisticIndexAndProvenanceAcceptanceIT {

    companion object {
        private val pack = Path.of("src/test/resources/acceptance/policies")
        private val examplesDir = Path.of("src/test/resources/acceptance/examples")
        private val dataFile = Path.of("src/test/resources/acceptance/data/release/data.json")
        private val noFrameworks = Path.of("src/test/resources/realistic/generated-examples/policies")

        private lateinit var withExamples: Map<String, String>
        private lateinit var withoutExamples: Map<String, String>
        private lateinit var withoutFrameworks: Map<String, String>

        fun render(policies: Path, examples: Boolean): Map<String, String> {
            val set = Explico.load(policies)
            return if (examples) Explico.render(set, policies, Explico.loadExamples(examplesDir), dataFile).files
            else Explico.render(set, policies).files
        }

        @JvmStatic
        @BeforeAll
        fun renderDocuments() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            withExamples = render(pack, examples = true)
            withoutExamples = render(pack, examples = false)
            withoutFrameworks = render(noFrameworks, examples = false)
        }

        private val digestPattern = Regex("Policy digest: `sha256:([0-9a-f]{64})`")

        fun digestOf(files: Map<String, String>): String {
            val match = digestPattern.find(files.getValue("index.md"))
            assertThat(match).describedAs("a full `Policy digest: sha256:<64 hex>` line in index.md").isNotNull()
            return match!!.groupValues[1]
        }

        /** The index's control tables: data rows (7 cells with examples, 6 without), excluding header and separator rows. */
        fun controlRows(index: String): List<String> =
            index.lines().filter { it.startsWith("| ") && !it.startsWith("| Control ID") && !it.startsWith("| Framework") && it.split("|").size >= 8 }

        fun copyOfPack(into: Path, crlf: Boolean = false, extra: String = ""): Path {
            val target = into.resolve("policies")
            pack.toFile().copyRecursively(target.toFile())
            Files.walk(target).use { paths ->
                paths.filter { it.toString().endsWith(".rego") }.forEach { file ->
                    var text = Files.readString(file).replace("\r\n", "\n")
                    if (extra.isNotEmpty() && file.fileName.toString() == "change_approval.rego") text += extra
                    if (crlf) text = text.replace("\n", "\r\n")
                    Files.writeString(file, text)
                }
            }
            return target
        }
    }

    @Nested
    @DisplayName("Every index row links to its card")
    inner class TitleLinks {

        @Test
        @DisplayName("the title cell of every control row is a link, and the control id stays the plain first cell")
        fun titlesAreLinks() {
            val rows = controlRows(withExamples.getValue("index.md"))
            assertThat(rows).hasSize(7) // 4 controls + 3 id-less rules
            for (row in rows) {
                val title = row.split("|")[2].trim()
                assertThat(title).describedAs(row).matches("\\[.+]\\([a-z0-9-]+\\.md#[a-z0-9-]+\\)")
            }
            assertThat(rows.first { it.contains("REL-001") }).startsWith("| REL-001 |")
        }

        @Test
        @DisplayName("a control's title links to the real card: [Production change approval](release-approvals.md#rel-001)")
        fun controlLinkIsExact() {
            assertThat(withExamples.getValue("index.md")).contains("[Production change approval](release-approvals.md#rel-001)")
        }

        @Test
        @DisplayName("a rule with no control id links to its package-rule anchor")
        fun idLessRuleLinkIsExact() {
            assertThat(withExamples.getValue("index.md"))
                .contains("[Mandatory check completeness](release-governance.md#release-governance-all-checks-passed)")
        }

        @Test
        @DisplayName("every link in the index resolves to a document that exists and carries that anchor")
        fun everyLinkResolves() {
            val index = withExamples.getValue("index.md")
            val links = Regex("\\]\\(([a-z0-9-]+\\.md)#([a-z0-9-]+)\\)").findAll(index).map { it.groupValues[1] to it.groupValues[2] }.toList()
            assertThat(links).isNotEmpty()
            for ((file, anchor) in links) {
                assertThat(withExamples).describedAs("document %s linked from the index", file).containsKey(file)
                assertThat(withExamples.getValue(file)).describedAs("anchor %s in %s", anchor, file).contains("<a id=\"$anchor\"></a>")
            }
        }
    }

    @Nested
    @DisplayName("The 'Coverage' column is renamed and explained")
    inner class RenamedColumnAndLegend {

        @Test
        @DisplayName("the header says Rendered (with and without examples) and no longer says Coverage")
        fun headerRenamed() {
            assertThat(withExamples.getValue("index.md")).contains("| Control ID | Title | Package | Rule | Rendered | Example coverage | Source file |")
            assertThat(withoutExamples.getValue("index.md")).contains("| Control ID | Title | Package | Rule | Rendered | Source file |")
            assertThat(withExamples.getValue("index.md")).doesNotContain("| Coverage |")
            assertThat(withoutExamples.getValue("index.md")).doesNotContain("| Coverage |")
        }

        @Test
        @DisplayName("a closing legend explains Rendered, and says it is not a statement about the control")
        fun legendExplainsRendered() {
            val index = withExamples.getValue("index.md")
            assertThat(index).contains("## How to read this index").contains("**Rendered**")
            assertThat(index).contains("says nothing about whether a control is effective or complete")
            assertThat(index.indexOf("## How to read this index")).isGreaterThan(index.indexOf("Overall rendering coverage"))
        }

        @Test
        @DisplayName("the Example coverage bullet appears only when examples were supplied")
        fun exampleCoverageBulletOnlyWithExamples() {
            assertThat(withExamples.getValue("index.md").substringAfter("## How to read this index")).contains("**Example coverage**").contains("✓ / ✓")
            val without = withoutExamples.getValue("index.md").substringAfter("## How to read this index")
            assertThat(without).contains("**Rendered**").doesNotContain("**Example coverage**")
        }
    }

    @Nested
    @DisplayName("Controls by framework")
    inner class FrameworkMatrix {

        @Test
        @DisplayName("a requirement-to-controls table exists, between the control tables and the overall line")
        fun sectionPlacedAfterTheTables() {
            val index = withExamples.getValue("index.md")
            assertThat(index).contains("## Controls by framework").contains("| Framework requirement | Controls |")
            assertThat(index.indexOf("## Controls by framework")).isGreaterThan(index.indexOf("## Rules without a control id"))
            assertThat(index.indexOf("## Controls by framework")).isLessThan(index.indexOf("Overall rendering coverage"))
        }

        @Test
        @DisplayName("each framework is one row listing the controls that name it, as links")
        fun rowsListControls() {
            val section = withExamples.getValue("index.md").substringAfter("## Controls by framework").substringBefore("Overall rendering coverage")
            fun row(framework: String) = section.lines().first { it.startsWith("| $framework |") }
            row("SOC 2 CC8.1").let {
                assertThat(it).contains("[REL-001](release-approvals.md#rel-001)", "[REL-002](release-evidence.md#rel-002)", "[REL-004](release-governance.md#rel-004)")
                assertThat(it.indexOf("REL-001")).isLessThan(it.indexOf("REL-002"))
                assertThat(it.indexOf("REL-002")).isLessThan(it.indexOf("REL-004"))
            }
            assertThat(row("ISO 27001 A.8.32")).contains("[REL-001](release-approvals.md#rel-001)", "[REL-003](release-provenance.md#rel-003)").doesNotContain("REL-002", "REL-004")
            assertThat(row("DORA Art. 9")).contains("[REL-004](release-governance.md#rel-004)").doesNotContain("REL-001")
            assertThat(row("SLSA v1.0 L2")).contains("[REL-003](release-provenance.md#rel-003)").doesNotContain("REL-001")
        }

        @Test
        @DisplayName("rows are sorted by framework name, case-insensitively")
        fun rowsSorted() {
            val section = withExamples.getValue("index.md").substringAfter("## Controls by framework")
            val order = listOf("DORA Art. 9", "ISO 27001 A.8.32", "SLSA v1.0 L2", "SOC 2 CC8.1").map { section.indexOf("| $it |") }
            assertThat(order).isSorted().doesNotContain(-1)
        }

        @Test
        @DisplayName("no section at all when no rule names a framework")
        fun absentWithoutFrameworks() {
            assertThat(withoutFrameworks.getValue("index.md")).doesNotContain("Controls by framework").doesNotContain("Framework requirement")
        }
    }

    @Nested
    @DisplayName("Policy digest")
    inner class PolicyDigest {

        @Test
        @DisplayName("the index shows the full SHA-256 directly under its title")
        fun digestUnderTheTitle() {
            val lines = withExamples.getValue("index.md").lines()
            assertThat(lines[0]).isEqualTo("# Control index")
            assertThat(lines[2]).matches("\\*Policy digest: `sha256:[0-9a-f]{64}`.*\\*")
        }

        @Test
        @DisplayName("every package document ends with the first 12 hex characters of the same digest")
        fun packageDocsCarryTheShortDigest() {
            val full = digestOf(withExamples)
            val packageDocs = withExamples.filterKeys { it != "index.md" }
            assertThat(packageDocs).hasSize(5)
            for ((name, doc) in packageDocs) {
                val last = doc.trimEnd().lines().last()
                assertThat(last).describedAs(name).isEqualTo("*Policy digest: `sha256:${full.take(12)}…` (full value in index.md)*")
            }
        }

        @Test
        @DisplayName("the digest depends only on the policy files: not on examples or data, and stable across renders")
        fun stableAndIndependentOfExamples() {
            assertThat(digestOf(withExamples)).isEqualTo(digestOf(withoutExamples))
            assertThat(digestOf(render(pack, examples = false))).isEqualTo(digestOf(withoutExamples))
        }

        @Test
        @DisplayName("a policy rewritten with Windows line endings has the SAME digest")
        fun lineEndingsDoNotChangeIt(@TempDir tmp: Path) {
            val crlf = copyOfPack(tmp, crlf = true)
            assertThat(Files.readString(crlf.resolve("approvals/change_approval.rego"))).contains("\r\n")
            assertThat(digestOf(render(crlf, examples = false))).isEqualTo(digestOf(withoutExamples))
        }

        @Test
        @DisplayName("changing a single comment in a policy changes the digest")
        fun aChangedCommentChangesIt(@TempDir tmp: Path) {
            val edited = copyOfPack(tmp, extra = "\n# reviewed\n")
            assertThat(digestOf(render(edited, examples = false))).isNotEqualTo(digestOf(withoutExamples))
        }

        @Test
        @DisplayName("a different policy set has a different digest")
        fun differentPoliciesDiffer() {
            assertThat(digestOf(withoutFrameworks)).isNotEqualTo(digestOf(withoutExamples))
        }
    }
}
