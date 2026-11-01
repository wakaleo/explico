/**
 * Acceptance tests for the diff report's header: which two versions it compares. Today a report does not say; `index.md` carries a policy digest (spec 14.40) but the diff
 * repeats nothing, so an auditor holding the report cannot tie it to a release or to the render docs. Authored from the same control-owner journey review as the other
 * diff increments, NOT from the frozen acceptance-pack README. Decisions (to be confirmed):
 *
 * - With `oldPolicyDir` AND `newPolicyDir` (together, or `IllegalArgumentException` naming both) the report gets a `**Compared**` block right under the disclaimer line,
 *   before `## Summary`: `- Old: \`sha256:<64 hex>\`` and `- New: \`sha256:<64 hex>\``, the SAME digest `render` prints in `index.md` for that directory (so: the `.rego`
 *   files only, line endings normalised, examples/data/other files ignored). Equal digests say so on the New line: `(same policies as Old)`.
 * - Optional `oldLabel`/`newLabel` name the versions: `- Old (v1.2): ...`. A label is plain text: not empty, no line break, no backtick, no leading/trailing space, at
 *   most 80 characters; invalid is an `IllegalArgumentException` naming the parameter, raised BEFORE any work (a nonexistent directory proves it). Labels need the
 *   directories (else `IllegalArgumentException` naming them).
 * - `DiffReport.oldDigest`/`newDigest` are `sha256:<hex>` (empty without the directories); the JSON gets a top-level `compared` {old, new: {digest, label?}} only then.
 * - Without the directories the report is exactly what it was (the committed golden).
 * Fixtures: `realistic/metadata-with-logic/{old,new,data}` (reused, not copied). Facade only; the CLI is pinned in CliDiffHeaderProcessTest.
 */
package io.explico.acceptance

import io.explico.DiffReport
import io.explico.DiffOptions
import io.explico.RenderOptions
import io.explico.Explico
import io.explico.VersionLabels
import io.explico.opa.OpaRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@DisplayName("Realistic policies: the diff report header")
class RealisticDiffHeaderAcceptanceIT {

    companion object {
        private val base = Path.of("src/test/resources/realistic/metadata-with-logic")
        private val oldDir = base.resolve("old")
        private val newDir = base.resolve("new")
        private val sha = Regex("sha256:[0-9a-f]{64}")

        private fun diff(old: Path = oldDir, new: Path = newDir, oldPolicyDir: Path? = old, newPolicyDir: Path? = new, oldLabel: String? = null, newLabel: String? = null, loadOld: Path = oldDir): DiffReport =
            Explico.diff(
                Explico.load(loadOld), Explico.load(new),
                DiffOptions(labels = if (oldLabel != null || newLabel != null) VersionLabels(oldLabel, newLabel) else null, digests = oldPolicyDir != null && newPolicyDir != null),
            )

        /** The digest `explico render` writes into `index.md` for [dir], optionally with data (which must not matter). */
        private fun renderDigest(dir: Path, data: Path? = null): String {
            val index = Explico.render(Explico.load(dir), RenderOptions(dataDir = data)).files.getValue("index.md")
            return sha.find(index)?.value ?: error("no digest in index.md of $dir")
        }

        private lateinit var plain: DiffReport
        private lateinit var plainAgain: DiffReport
        private lateinit var labelled: DiffReport
        private lateinit var identical: DiffReport
        private lateinit var withoutDirs: DiffReport
        private lateinit var oldIndexDigest: String
        private lateinit var newIndexDigest: String

        @JvmStatic
        @BeforeAll
        fun renderAll() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            plain = diff()
            plainAgain = diff()
            labelled = diff(oldLabel = "v1.2", newLabel = "v1.3")
            identical = diff(old = oldDir, new = oldDir)
            withoutDirs = diff(oldPolicyDir = null, newPolicyDir = null)
            oldIndexDigest = renderDigest(oldDir)
            newIndexDigest = renderDigest(newDir)
        }

        private fun compared(json: String): JsonObject? = Json.parseToJsonElement(json).jsonObject["compared"]?.jsonObject
    }

    @Nested
    @DisplayName("The Compared block")
    inner class Block {

        @Test
        @DisplayName("both digests appear in full, and they are the digests render puts in index.md for the same directories")
        fun digests() {
            assertThat(plain.markdown.lines()).contains("**Compared**", "- Old: `$oldIndexDigest`", "- New: `$newIndexDigest`")
            assertThat(plain.oldDigest).isEqualTo(oldIndexDigest)
            assertThat(plain.newDigest).isEqualTo(newIndexDigest)
            assertThat(oldIndexDigest).isNotEqualTo(newIndexDigest)
        }

        @Test
        @DisplayName("the block sits right under the disclaimer line and before ## Summary, once")
        fun placement() {
            val lines = plain.markdown.lines()
            val disclaimer = lines.indexOfFirst { it.startsWith("*This report") }

            assertThat(lines.count { it == "**Compared**" }).isEqualTo(1)
            assertThat(lines.indexOf("**Compared**")).isGreaterThan(disclaimer).isLessThan(lines.indexOf("## Summary"))
        }

        @Test
        @DisplayName("equal digests are said to be: old against old flags the New line, a real change does not")
        fun samePolicies() {
            assertThat(identical.markdown.lines()).contains("- New: `${identical.newDigest}` (same policies as Old)")
            assertThat(identical.oldDigest).isEqualTo(identical.newDigest)
            assertThat(plain.markdown).doesNotContain("same policies as Old")
        }

        @Test
        @DisplayName("the digest is the one render prints whatever else is supplied, and does not depend on line endings or on files that are not .rego")
        fun stableDigest(@TempDir tempDir: Path) {
            // Render with data: the digest must be the same value.
            assertThat(renderDigest(oldDir, base.resolve("data/old.json"))).isEqualTo(oldIndexDigest)
            // A copy with CRLF line endings, a README and a data file next to the policies.
            val copy = tempDir.resolve("old-copy")
            Files.walk(oldDir).use { paths ->
                paths.filter { Files.isRegularFile(it) }.forEach { file ->
                    val target = copy.resolve(oldDir.relativize(file).toString())
                    Files.createDirectories(target.parent)
                    Files.writeString(target, Files.readString(file).replace("\n", "\r\n"))
                }
            }
            Files.writeString(copy.resolve("README.txt"), "not a policy\n")
            Files.writeString(copy.resolve("data.json"), "{}\n")

            assertThat(diff(loadOld = copy).oldDigest).isEqualTo(oldIndexDigest)
        }

        @Test
        @DisplayName("without the policy directories there is no block, no digest, and the report is the committed diff-all-categories golden byte for byte")
        fun withoutDirectories() {
            assertThat(withoutDirs.markdown).doesNotContain("**Compared**")
            assertThat(withoutDirs.oldDigest).isEmpty()
            assertThat(withoutDirs.newDigest).isEmpty()
            val dir = Path.of("src/test/resources/diff/diff-all-categories")
            val golden = Explico.diff(Explico.load(dir.resolve("old")), Explico.load(dir.resolve("new")))
            assertThat(golden.markdown).isEqualTo(Files.readString(dir.resolve("expected/diff-report.md")))
        }
    }

    @Nested
    @DisplayName("Labels")
    inner class Labels {

        @Test
        @DisplayName("a label names its version in the bullet; without one the bullet is as before")
        fun shown() {
            assertThat(labelled.markdown.lines()).contains("- Old (v1.2): `$oldIndexDigest`", "- New (v1.3): `$newIndexDigest`")
            assertThat(plain.markdown).doesNotContain("- Old (").doesNotContain("- New (")
        }

        @Test
        @DisplayName("one label alone is fine: only that bullet carries it")
        fun oneLabel() {
            val report = diff(oldLabel = "v1.2")

            assertThat(report.markdown.lines()).contains("- Old (v1.2): `$oldIndexDigest`", "- New: `$newIndexDigest`")
        }

        @Test
        @DisplayName("a label that is not plain text is refused, naming the parameter, before any work: empty, a line break, a backtick, a leading or trailing space, 81 characters")
        fun invalid() {
            val old = Explico.load(oldDir)
            val nowhere = Path.of("does-not-exist-so-no-work-can-have-happened")
            val located = old.copy(sourceDir = nowhere.toAbsolutePath())
            // The same call is accepted with a good label, so the refusals below are about the LABEL.
            assertThat(diff(oldLabel = "release 2026-10 (rc1)").oldDigest).isEqualTo(oldIndexDigest)
            val bad = listOf("", "two\nlines", "back`tick", " leading", "trailing ", "x".repeat(81))
            for (label in bad) {
                assertThatThrownBy { Explico.diff(located, located, DiffOptions(labels = VersionLabels(old = label))) }
                    .describedAs("oldLabel '%s'", label).isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("oldLabel")
                assertThatThrownBy { Explico.diff(located, located, DiffOptions(labels = VersionLabels(new = label))) }
                    .describedAs("newLabel '%s'", label).isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("newLabel")
            }
            assertThat(diff(oldLabel = "x".repeat(80)).oldDigest).describedAs("80 characters is the limit, not 79").isEqualTo(oldIndexDigest)
        }

        @Test
        @DisplayName("labels and digests need the sets' directories: a set built by hand names sourceDir and Explico.load")
        fun needDirectories() {
            val hand = io.explico.model.PolicySet(emptyList())

            assertThatThrownBy { Explico.diff(hand, hand, DiffOptions(labels = VersionLabels(old = "v1"))) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("sourceDir").hasMessageContaining("Explico.load")
            assertThatThrownBy { Explico.diff(hand, hand, DiffOptions(digests = true)) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("sourceDir").hasMessageContaining("Explico.load")
        }
    }

    @Nested
    @DisplayName("JSON")
    inner class Json_ {

        @Test
        @DisplayName("a top-level compared object carries each digest, and a label only when one was given")
        fun compared() {
            val withLabels = compared(labelled.json)
            val withoutLabels = compared(plain.json)

            assertThat(withLabels).isNotNull()
            assertThat(withLabels!!.getValue("old").jsonObject.getValue("digest").jsonPrimitive.content).isEqualTo(oldIndexDigest)
            assertThat(withLabels.getValue("old").jsonObject.getValue("label").jsonPrimitive.content).isEqualTo("v1.2")
            assertThat(withLabels.getValue("new").jsonObject.getValue("label").jsonPrimitive.content).isEqualTo("v1.3")
            assertThat(withoutLabels).isNotNull()
            assertThat(withoutLabels!!.getValue("new").jsonObject).doesNotContainKey("label")
            assertThat(withoutLabels.getValue("new").jsonObject.getValue("digest").jsonPrimitive.content).isEqualTo(newIndexDigest)
        }

        @Test
        @DisplayName("without the directories there is no compared object, and the schema is unchanged")
        fun absent() {
            assertThat(compared(withoutDirs.json)).isNull()
            assertThat(Json.parseToJsonElement(plain.json).jsonObject.getValue("schema").jsonPrimitive.content).isEqualTo("explico/diff/1")
        }
    }

    @Nested
    @DisplayName("Determinism")
    inner class Determinism {

        @Test
        @DisplayName("two runs are byte-identical, Markdown and JSON")
        fun deterministic() {
            assertThat(plainAgain.markdown).isEqualTo(plain.markdown)
            assertThat(plainAgain.json).isEqualTo(plain.json)
        }
    }
}
