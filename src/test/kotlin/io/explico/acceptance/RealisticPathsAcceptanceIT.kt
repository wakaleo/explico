/**
 * Acceptance tests for path/iteration rendering on the realistic policies under
 * `src/test/resources/realistic/policies/` (review items 5-7).
 *
 * NOT transcribed from the frozen acceptance-pack README: authored from the "improve rendered
 * output" review at the operator's request. They pin only what those items require -- a
 * `x := <path>[_]` binding substitutes like `some x in <path>`, `[_]` inside a path humanizes
 * instead of dumping raw source, a path used as a dynamic key humanizes too -- and never pin the
 * wording of the any-element segment. The shared invariant: no rendered BULLET (outside a fenced
 * source block) may contain raw `input.` text, a raw `[_]`, or a raw dotted `x.field` span.
 * Anything not mechanically derivable must still appear as fenced verbatim source (guards below).
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
import java.nio.file.Path

@DisplayName("Realistic policies: paths and iteration (review items 5-7)")
class RealisticPathsAcceptanceIT {

    companion object {
        private const val POLICIES_DIR = "src/test/resources/realistic/policies"
        private lateinit var files: Map<String, String>

        @JvmStatic
        @BeforeAll
        fun renderDocuments() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            val policyDir = Path.of(POLICIES_DIR)
            files = Explico.render(Explico.load(policyDir), policyDir).files
        }

        /** The `## ` section whose heading line contains [marker], up to the next `## ` heading. */
        private fun section(markdown: String, marker: String): String {
            val lines = markdown.lines()
            val start = lines.indexOfFirst { it.startsWith("## ") && it.contains(marker) }
            assertThat(start).describedAs("a '## ' heading containing %s", marker).isGreaterThanOrEqualTo(0)
            val end = lines.drop(start + 1).indexOfFirst { it.startsWith("## ") }
            return lines.drop(start).let { if (end < 0) it else it.take(end + 1) }.joinToString("\n")
        }

        /** Lines outside fenced ``` blocks. */
        private fun unfenced(markdown: String): List<String> {
            var inFence = false
            return markdown.lines().filter { line ->
                if (line.trim().startsWith("```")) { inFence = !inFence; false } else !inFence
            }
        }

        /** Condition bullets only: unfenced lines starting with "- ". */
        private fun bullets(markdown: String): List<String> = unfenced(markdown).filter { it.startsWith("- ") }

        /** Content of every fenced block, joined. */
        private fun fencedSource(markdown: String): String {
            var inFence = false
            return markdown.lines().filter { line ->
                if (line.trim().startsWith("```")) { inFence = !inFence; false } else inFence
            }.joinToString("\n")
        }

        private val RAW_DOTTED_SPAN = Regex("`[A-Za-z_][A-Za-z_0-9]*\\.[A-Za-z_][^`]*`")
    }

    @Nested
    @DisplayName("Invariant — no raw source text inside a rendered bullet")
    inner class NoRawSourceInBullets {

        @Test
        @DisplayName("k8s-admission.md bullets contain no raw `input.`, `[_]`, or dotted `x.field` span")
        fun k8sBulletsAreFullyHumanized() = assertHumanized("k8s-admission.md")

        @Test
        @DisplayName("terraform-s3.md bullets contain no raw `input.`, `[_]`, or dotted `x.field` span")
        fun terraformBulletsAreFullyHumanized() = assertHumanized("terraform-s3.md")

        @Test
        @DisplayName("authz.md bullets contain no raw `input.`, `[_]`, or dotted `x.field` span")
        fun authzBulletsAreFullyHumanized() = assertHumanized("authz.md")

        private fun assertHumanized(file: String) {
            for (bullet in bullets(files[file] ?: "")) {
                assertThat(bullet).describedAs("bullet in %s", file).doesNotContain("input.").doesNotContain("[_]")
                assertThat(RAW_DOTTED_SPAN.containsMatchIn(bullet)).describedAs("raw dotted span in %s: %s", file, bullet).isFalse()
            }
        }
    }

    @Nested
    @DisplayName("Item 5 — `x := <path>[_]` substitutes like `some x in <path>`")
    inner class AssignedIterationBinding {

        @Test
        @DisplayName("K8S-002: the `container := ...containers[_]` assignment is not shown as a source block")
        fun k8s002AssignmentDisappears() {
            val card = section(files["k8s-admission.md"] ?: "", "K8S-002")
            assertThat(fencedSource(card)).doesNotContain("container :=")
        }

        @Test
        @DisplayName("K8S-002: the later `container.image` check renders as a containers ▸ … ▸ image breadcrumb")
        fun k8s002LaterUseIsABreadcrumb() {
            val card = section(files["k8s-admission.md"] ?: "", "K8S-002")
            assertThat(card).contains("does not start with `\"registry.corp.example/\"`")
            val bullet = bullets(card).single { it.contains("does not start with") }
            assertThat(bullet).containsPattern("containers ▸ .*image")
        }

        @Test
        @DisplayName("terraform.s3.deny: the `rc := ...resource_changes[_]` assignment is not shown as a source block")
        fun terraformAssignmentDisappears() {
            val card = section(files["terraform-s3.md"] ?: "", "terraform.s3.deny")
            assertThat(fencedSource(card)).doesNotContain("rc :=")
        }

        @Test
        @DisplayName("terraform.s3.deny: `rc.type` renders as a resource changes ▸ … ▸ type breadcrumb")
        fun terraformLaterUseIsABreadcrumb() {
            val card = section(files["terraform-s3.md"] ?: "", "terraform.s3.deny")
            val bullet = bullets(card).single { it.contains("\"aws_s3_bucket\"") && it.contains("[each rc]") }
            assertThat(bullet).containsPattern("resource changes ▸ .*type")
        }
    }

    @Nested
    @DisplayName("Item 6 — `[_]` inside a path humanizes instead of falling back to raw source")
    inner class AnyIndexInPaths {

        @Test
        @DisplayName("`input.request.object.spec.containers[_].resources.limits` renders as a containers ▸ … ▸ limits breadcrumb")
        fun middleAnyIndexHumanizes() {
            val md = files["k8s-admission.md"] ?: ""
            val bullet = bullets(md).single { it.contains("limits") }
            assertThat(bullet).containsPattern("containers ▸ .*limits").contains("is `null`")
        }

        @Test
        @DisplayName("`rc.change.actions[_] == \"delete\"` renders as a … ▸ actions breadcrumb")
        fun trailingAnyIndexOnAnAssignedVariableHumanizes() {
            val md = files["terraform-s3.md"] ?: ""
            val bullet = bullets(md).single { it.contains("\"delete\"") }
            assertThat(bullet).containsPattern("resource changes ▸ .*actions")
        }

        @Test
        @DisplayName("`input.tags[_] == \"x\"` renders as a tags breadcrumb")
        fun trailingAnyIndexOnInputHumanizes() {
            val card = section(files["terraform-s3.md"] ?: "", "terraform.s3.tagged")
            val bullet = bullets(card).single { it.contains("\"x\"") }
            assertThat(bullet).contains("tags").doesNotContain("input.").doesNotContain("[_]")
        }
    }

    @Nested
    @DisplayName("Item 7 — a path used as a dynamic key humanizes")
    inner class DynamicKeyIsAPath {

        @Test
        @DisplayName("`data.grants[input.user.id]` bullets show a user ▸ id key, never raw `input.user.id`")
        fun dynamicKeyPathIsHumanized() {
            val md = files["authz.md"] ?: ""
            val grantBullets = bullets(md).filter { it.contains("grants") }
            assertThat(grantBullets).isNotEmpty()
            for (bullet in grantBullets) {
                assertThat(bullet).doesNotContain("input.user.id")
                assertThat(bullet).contains("user ▸ id")
            }
        }

        @Test
        @DisplayName("the grants collection and its per-element `resource`/`permission` checks still render")
        fun surroundingConditionsStillRender() {
            val md = files["authz.md"] ?: ""
            assertThat(md).contains("matches glob `\"read*\"`")
            assertThat(bullets(md).count { it.contains("grants") }).isGreaterThanOrEqualTo(3)
        }
    }

    @Nested
    @DisplayName("Fallback stays sacred — what is not mechanically derivable remains verbatim source")
    inner class FallbackGuards {

        @Test
        @DisplayName("K8S-003's set COMPREHENSION assignment stays fenced source; its set-literal and set-difference assignments are substituted away")
        fun nonPathAssignmentsStayFenced() {
            val src = fencedSource(section(files["k8s-admission.md"] ?: "", "K8S-003"))
            assertThat(src).contains("provided := {l | input.request.object.metadata.labels[l]}")
            assertThat(src).doesNotContain("required :=").doesNotContain("missing :=")
        }

        @Test
        @DisplayName("`every` in authz.md is rendered, no longer fenced source (spec §14.49)")
        fun unsupportedConstructsStayFenced() {
            val src = fencedSource(files["authz.md"] ?: "")
            assertThat(src).doesNotContain("every role in input.user.roles")
            assertThat(files["authz.md"] ?: "").contains("for every role in `user ▸ roles`")
        }
    }
}
