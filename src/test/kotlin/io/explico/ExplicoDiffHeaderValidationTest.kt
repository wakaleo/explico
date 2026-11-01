/**
 * Unit tests for the order in which `Explico.diff` validates its header arguments (spec §14.76): everything is checked BEFORE any file is read or opa is run, so a
 * mistake is reported as the mistake it is. No opa needed: the policy sets are empty and the directories either do not exist (proving no access) or hold no policies.
 *
 * Migrated for the options API (spec §14.80 step 2): a set now carries its directory (`PolicySet.sourceDir`) instead of `diff` taking `oldPolicyDir`/`newPolicyDir`, so the
 * two "directories come together" / "label needs directories" tests, whose misuse can no longer be written, became "digests, labels and examples need a sourceDir".
 */
package io.explico

import io.explico.model.PolicySet
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ExplicoDiffHeaderValidationTest {

    private val set = PolicySet(emptyList())
    private val nowhere = Path.of("does-not-exist-so-nothing-may-read-it")
    private val located = PolicySet(emptyList(), sourceDir = nowhere.toAbsolutePath())

    @Test
    @DisplayName("digests, examples and labels need the sets' directories; a set with none names sourceDir and Explico.load (this covers the examples case too)")
    fun directoriesNeeded() {
        for (options in listOf(DiffOptions(digests = true), DiffOptions(examples = io.explico.render.ExampleSet(emptyList())), DiffOptions(labels = VersionLabels(old = "v1")))) {
            assertThatThrownBy { Explico.diff(set, set, options) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("sourceDir").hasMessageContaining("Explico.load")
        }
    }

    @Test
    @DisplayName("one set without a directory is enough to refuse, whichever side it is")
    fun oneSideMissing() {
        assertThatThrownBy { Explico.diff(located, set, DiffOptions(digests = true)) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("sourceDir")
        assertThatThrownBy { Explico.diff(set, located, DiffOptions(digests = true)) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("sourceDir")
    }

    @Test
    @DisplayName("an invalid label is refused naming its parameter BEFORE the directories are touched (they do not exist), oldLabel checked first")
    fun labelBeforeDirectories() {
        assertThatThrownBy { Explico.diff(located, located, DiffOptions(labels = VersionLabels(old = "bad`tick"))) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("oldLabel")
        assertThatThrownBy { Explico.diff(located, located, DiffOptions(labels = VersionLabels(new = " lead"))) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("newLabel")
        assertThatThrownBy { Explico.diff(located, located, DiffOptions(labels = VersionLabels(old = "", new = ""))) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("oldLabel").hasMessageNotContaining("newLabel")
    }

    @Test
    @DisplayName("CHALLENGE: a directory with no .rego file has the digest of nothing, and does not crash; the same directory on both sides says 'same policies'")
    fun emptyDirectory(@TempDir dir: Path) {
        Files.writeString(dir.resolve("README.txt"), "not a policy")

        val located = PolicySet(emptyList(), sourceDir = dir)
        val report = Explico.diff(located, located, DiffOptions(digests = true))

        assertThat(report.oldDigest).isEqualTo("sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        assertThat(report.newDigest).isEqualTo(report.oldDigest)
        assertThat(report.markdown).contains("(same policies as Old)")
    }

    @Test
    @DisplayName("CHALLENGE: a very long directory path gives the same digest as a short one with the same relative files (the digest never includes the location)")
    fun longPath(@TempDir dir: Path) {
        val shortDir = dir.resolve("p").also { Files.createDirectories(it) }
        val longDir = dir.resolve("d".repeat(100)).resolve("e".repeat(100)).resolve("p").also { Files.createDirectories(it) }
        for (d in listOf(shortDir, longDir)) Files.writeString(d.resolve("a.rego"), "package a\n")

        val report = Explico.diff(PolicySet(emptyList(), sourceDir = shortDir), PolicySet(emptyList(), sourceDir = longDir), DiffOptions(digests = true))

        assertThat(report.oldDigest).isEqualTo(report.newDigest)
    }
}
