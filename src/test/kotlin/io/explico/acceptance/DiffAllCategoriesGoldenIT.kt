/**
 * Tier-2 golden test (spec §9): the diff report ("Additional golden scenarios beyond the
 * acceptance pack") for an old/new variant of the acceptance-pack policies exercising every
 * [io.explico.diff.DiffCategory], including a control-id-preserving rule-name rename (REL-002,
 * `deny` -> `deny_stage`, unchanged package -- proves it lands UNCHANGED, never REMOVED+ADDED).
 * `-Dexplico.updateGolden=true` regenerates the golden, same convention as AcceptancePackGoldenIT.
 */
package io.explico.acceptance

import io.explico.Explico
import io.explico.opa.OpaRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DiffAllCategoriesGoldenIT {

    companion object {
        private const val OLD_DIR = "src/test/resources/diff/diff-all-categories/old"
        private const val NEW_DIR = "src/test/resources/diff/diff-all-categories/new"
        private const val EXPECTED_FILE = "src/test/resources/diff/diff-all-categories/expected/diff-report.md"
        private lateinit var markdown: String

        @JvmStatic
        @BeforeAll
        fun renderDiff() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            val old = Explico.load(Path.of(OLD_DIR))
            val new = Explico.load(Path.of(NEW_DIR))
            markdown = Explico.diff(old, new).markdown
        }
    }

    private val updateGolden = System.getProperty("explico.updateGolden") == "true"

    @Test
    fun exercisesEveryDiffCategory() {
        assertThat(markdown).contains("| REMOVED | 1 |")
        assertThat(markdown).contains("| ADDED | 1 |")
        assertThat(markdown).contains("| LOGIC_CHANGED | 1 |")
        assertThat(markdown).contains("| DOCS_CHANGED | 1 |")
        assertThat(markdown).contains("| UNCHANGED | 4 |")
    }

    @Test
    fun theControlIdPreservingRenameOfRel002LandsUnchangedNeverRemovedPlusAdded() {
        // REL-002's rule was renamed deny -> deny_stage; only its control-id ties old to new.
        assertThat(markdown).doesNotContain("REL-002")
    }

    @Test
    fun matchesGolden() {
        val expectedFile = Path.of(EXPECTED_FILE)
        if (updateGolden) {
            Files.createDirectories(expectedFile.parent)
            Files.writeString(expectedFile, markdown)
            return
        }
        assertThat(markdown).isEqualTo(Files.readString(expectedFile))
    }
}
