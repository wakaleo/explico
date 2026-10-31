/**
 * CHALLENGE cases for the diff report header (spec §14.76) that need the real `opa` binary: the header together with `--group-by-control` and with example fixtures.
 * The main contract is RealisticDiffHeaderAcceptanceIT.
 */
package io.explico.diff

import io.explico.Explico
import io.explico.opa.OpaRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path

class DiffHeaderChallengeTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
        }
    }

    @Test
    @DisplayName("with --group-by-control the header is there, once, before the Summary")
    fun groupedControl() {
        val old = Path.of("src/test/resources/realistic/metadata-with-logic/old")
        val new = Path.of("src/test/resources/realistic/metadata-with-logic/new")

        val report = Explico.diff(Explico.load(old), Explico.load(new), groupByControl = true, oldPolicyDir = old, newPolicyDir = new, oldLabel = "v1", newLabel = "v2")
        val lines = report.markdown.lines()

        assertThat(lines.count { it == "**Compared**" }).isEqualTo(1)
        assertThat(lines.indexOf("**Compared**")).isLessThan(lines.indexOf("## Summary"))
        assertThat(report.markdown).contains("- Old (v1): `sha256:").contains("- New (v2): `sha256:")
    }

    @Test
    @DisplayName("with example fixtures both sections are there, and the header comes first")
    fun withExamples() {
        val base = Path.of("src/test/resources/realistic/fixture-verdict-diff")
        val old = base.resolve("policies/old")
        val new = base.resolve("policies/new")

        val report = Explico.diff(Explico.load(old), Explico.load(new), examples = Explico.loadExamples(base.resolve("examples")), oldPolicyDir = old, newPolicyDir = new)
        val lines = report.markdown.lines()

        assertThat(lines.indexOf("**Compared**")).isGreaterThanOrEqualTo(0).isLessThan(lines.indexOf("## Behaviour on your examples"))
        assertThat(report.exampleChanges).isNotEmpty()
    }
}
