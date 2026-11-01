/**
 * Regression for the review of the per-situation example coverage finding (spec §14.77), with the real `opa` binary: two bodies whose message templates overlap
 * (`%v needs review` is a generalisation of `payment %v needs review`). The card's `*(Situation N)*` takes the FIRST matching body, so a message of body 2 was credited to
 * situation 1 and the finding claimed situation 2 was untriggered when a fixture had triggered it. It must now say nothing, while clean templates are still reported.
 */
package io.explico.render

import io.explico.Explico
import io.explico.RenderOptions
import io.explico.opa.OpaRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SituationCoverageOverlapTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
        }

        private fun policy(first: String, second: String) = """
            package o

            import rego.v1

            # METADATA
            # scope: document
            # title: Two bodies
            # description: Two bodies.
            # custom:
            #   control-id: OV-1
            #   frameworks:
            #     - F 1
            deny contains msg if {
            	input.kind == "a"
            	msg := sprintf("$first", [input.id])
            }

            deny contains msg if {
            	input.kind == "b"
            	msg := sprintf("$second", [input.id])
            }
        """.trimIndent() + "\n"

        private fun index(dir: Path, first: String, second: String, vararg kinds: String): String {
            val policies = dir.resolve("p").also { Files.createDirectories(it) }
            Files.writeString(policies.resolve("o.rego"), policy(first, second))
            val examples = dir.resolve("ex").also { Files.createDirectories(it) }
            kinds.forEachIndexed { i, kind -> Files.writeString(examples.resolve("f$i.json"), """{"name": "fixture $kind", "input": {"kind": "$kind", "id": "P-1"}}""") }
            return Explico.render(Explico.load(policies), RenderOptions(examples = Explico.loadExamples(examples))).files.getValue("index.md")
        }
    }

    @Test
    @DisplayName("overlapping templates: only body 2 is triggered, yet nothing is claimed about situation 2 (no wrong bullet)")
    fun overlappingTemplatesClaimNothing(@TempDir dir: Path) {
        assertThat(index(dir, "%v needs review", "payment %v needs review", "b")).doesNotContain("Situation without a matching example")
    }

    @Test
    @DisplayName("distinct, non-overlapping templates are still reported: only body 1 is triggered, so situation 2 is listed")
    fun cleanTemplatesStillReported(@TempDir dir: Path) {
        assertThat(index(dir, "first %v", "second %v", "a").lines()).contains(
            "- **Situation without a matching example (1):** [OV-1](o.md#ov-1) situation 2 — add a fixture that makes each of them match",
        )
    }
}
