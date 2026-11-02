/**
 * The user guide documents every option the CLI has (spec §14.80 step 6): each option in the committed CLI surface (`CliSurface`, the real command tree, the source of `api/explico.cli`) must appear in
 * the guide's section for its command, so a new or renamed option cannot reach the baseline without reaching the people who read the guide. No opa; `docs/**/*.md` is already a declared input of `test`.
 */
package io.explico.docs

import io.explico.cli.CliSurface
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class UserGuideOptionsDocsTest {

    private val guide = Files.readString(Path.of("docs/user-guide.md"))

    /** The guide text from the heading [from] up to the heading [to] (exclusive): a command's section including its subsections. */
    private fun between(from: String, to: String): String {
        val start = guide.indexOf(from)
        val end = guide.indexOf(to, start + from.length)
        assertThat(start).describedAs("the guide heading '%s'", from).isGreaterThanOrEqualTo(0)
        assertThat(end).describedAs("the guide heading '%s' after it", to).isGreaterThan(start)
        return guide.substring(start, end)
    }

    private val sections = mapOf(
        "demo" to between("### `explico demo`", "## Exit codes"),
        "diff" to between("### `explico diff`", "### `explico version`"),
        "render" to between("### `explico render`", "### `explico diff`"),
    )

    @Test
    @DisplayName("every option of demo, diff and render appears in that command's section of the user guide")
    fun everyOptionIsDocumented() {
        val blocks = CliSurface.dump().trimEnd('\n').split("\n\n").associate { block -> block.lines().let { it.first() to it.drop(1) } }
        for ((command, section) in sections) {
            val options = blocks.entries.single { it.key.startsWith("explico $command") }.value.map { it.trim().substringBefore("  ") }
            assertThat(options).describedAs("the options of explico $command").isNotEmpty()
            for (option in options) {
                // Defined, not merely mentioned in passing: in the first cell of an options-table row (`| `--out <outDir>` | ... |`) or as a bullet that opens with it (`- **`--fetch-opa`**: ...`).
                val inATableRow = Regex("(?m)^\\|[^|\\n]*" + Regex.escape(option) + "(?![a-z-])").containsMatchIn(section)
                val asABullet = Regex("(?m)^- \\*\\*`" + Regex.escape(option) + "`").containsMatchIn(section)
                assertThat(inATableRow || asABullet).describedAs("explico $command: the guide defines %s (a table row or a bullet)", option).isTrue()
            }
        }
    }

    @Test
    @DisplayName("and the guide documents no option the CLI no longer has: the removed spellings are absent from the command sections")
    fun removedOptionsAreNotDocumented() {
        for (removed in listOf("--require ", "--require`", "--fail-on-example-changes", "--json <file>")) {
            for ((command, section) in sections) assertThat(section).describedAs("explico $command section mentions %s", removed).doesNotContain(removed)
        }
        assertThat(sections.getValue("diff")).doesNotContain("--force")
    }
}
