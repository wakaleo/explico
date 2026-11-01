/**
 * `docs/json-output.md` documents the closed list of `facts` kinds of the diff JSON (spec §14.78). The page and [io.explico.diff.FactKind] must agree: every kind the code can emit is
 * documented, and the page names no kind the code does not have -- so a consumer reading the page is never surprised. No opa needed. `docs/**/*.md` is already a declared input of the
 * `test` task, so editing the page re-runs this.
 */
package io.explico.docs

import io.explico.diff.FactKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DiffFactsDocsTest {

    private val page = Files.readString(Path.of("docs/json-output.md"))

    /** The first column of the kinds table under the `## Typed facts` heading: `| \`kind\` | ... |`. */
    private fun documentedKinds(): List<String> {
        val start = page.indexOf("## Typed facts")
        assertThat(start).describedAs("a '## Typed facts' section in docs/json-output.md").isGreaterThanOrEqualTo(0)
        val end = page.indexOf("\n## ", start + 5).let { if (it < 0) page.length else it }
        return Regex("(?m)^\\| `([a-z]+(?:-[a-z]+)+)` \\|").findAll(page.substring(start, end)).map { it.groupValues[1] }.toList()
    }

    @Test
    @DisplayName("the documented kinds are exactly the FactKind wire names, each documented once")
    fun documentedKindsMatchTheCode() {
        val documented = documentedKinds()

        assertThat(documented).containsExactlyInAnyOrderElementsOf(FactKind.entries.map { it.wire })
        assertThat(documented.toSet()).hasSameSizeAs(documented)
    }
}
