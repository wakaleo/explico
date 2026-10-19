/**
 * Acceptance tests for user-defined function calls and produced messages on the realistic
 * policies under `src/test/resources/realistic/` (review items 8 and 9).
 *
 * NOT transcribed from the frozen acceptance-pack README: authored from the "improve rendered
 * output" review at the operator's request. They pin only what those items require:
 * - 8: a call to a user-defined function renders as a bullet that links to the function's own
 *   card and names its arguments as humanized operands (never fenced source); the function's card
 *   declares its parameters, and its conditions call them parameters instead of an unrendered
 *   raw name. A call whose arguments can't be mapped, and an unsupported builtin, stay fenced.
 * - 9: a card's `Produces` line survives the common message shapes with humanized placeholders
 *   (never raw `x.field` text or an unsubstituted `%v`/`%s`), and the head's own message variable
 *   assignment is not shown as a fenced unrendered bullet.
 * Exact wording (the call phrase, the placeholder bracket style, the Parameters label beyond
 * "Parameters") is deliberately not pinned.
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

@DisplayName("Realistic policies: function calls and produced messages (review items 8-9)")
class RealisticCallsAndMessagesAcceptanceIT {

    companion object {
        private lateinit var files: Map<String, String>
        private lateinit var funcFiles: Map<String, String>

        private fun render(dir: String): Map<String, String> {
            val policyDir = Path.of(dir)
            return Explico.render(Explico.load(policyDir), policyDir).files
        }

        @JvmStatic
        @BeforeAll
        fun renderDocuments() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            files = render("src/test/resources/realistic/policies")
            funcFiles = render("src/test/resources/realistic/functions")
        }

        /** The `## ` section whose heading line contains [marker], up to the next `## ` heading. */
        private fun section(markdown: String, marker: String): String {
            val lines = markdown.lines()
            val start = lines.indexOfFirst { it.startsWith("## ") && it.contains(marker) }
            assertThat(start).describedAs("a '## ' heading containing %s", marker).isGreaterThanOrEqualTo(0)
            val end = lines.drop(start + 1).indexOfFirst { it.startsWith("## ") }
            return lines.drop(start).let { if (end < 0) it else it.take(end + 1) }.joinToString("\n")
        }

        private fun unfenced(markdown: String): List<String> {
            var inFence = false
            return markdown.lines().filter { line ->
                if (line.trim().startsWith("```")) { inFence = !inFence; false } else !inFence
            }
        }

        private fun bullets(markdown: String): List<String> = unfenced(markdown).filter { it.startsWith("- ") }

        private fun fencedSource(markdown: String): String {
            var inFence = false
            return markdown.lines().filter { line ->
                if (line.trim().startsWith("```")) { inFence = !inFence; false } else inFence
            }.joinToString("\n")
        }

        private fun producesLines(markdown: String): List<String> = unfenced(markdown).filter { it.contains("Produces") }

        /** A produced-message line is humanized: no raw dotted source, no unsubstituted format verb. */
        private fun assertHumanized(line: String) {
            assertThat(line).doesNotContain("%v").doesNotContain("%s")
            assertThat(Regex("[a-z_]+\\.[a-z_]+").containsMatchIn(line.substringAfter("Produces")))
                .describedAs("raw dotted source in: %s", line).isFalse()
        }
    }

    @Nested
    @DisplayName("Item 8 — calls to user-defined functions")
    inner class FunctionCalls {

        @Test
        @DisplayName("`not is_exempt(input.request.namespace)` links to the function's card and names `request ▸ namespace`")
        fun k8sCallLinksAndNamesItsArgument() {
            val card = section(files["k8s-admission.md"] ?: "", "k8s.admission.deny")
            assertThat(fencedSource(card)).doesNotContain("is_exempt(")
            val callBullets = bullets(card).filter { it.contains("is_exempt") }
            assertThat(callBullets).hasSize(1)
            assertThat(callBullets.single()).contains("](#k8s-admission-is-exempt)").contains("request ▸ namespace").doesNotContain("input.")
        }

        @Test
        @DisplayName("a positive call with a path argument and a literal-set argument names both, and links")
        fun twoArgumentCallNamesBothArguments() {
            val card = section(funcFiles["funcs.md"] ?: "", "funcs.deny")
            val matching = bullets(card).filter { it.contains("](#funcs-in-region)") && it.contains("us-east-1") }
            assertThat(matching).hasSize(1)
            assertThat(matching.single()).contains("region").contains("eu-west-1").doesNotContain("input.")
        }

        @Test
        @DisplayName("a one-argument call renders with its humanized argument and a link, not as source")
        fun oneArgumentCallIsNotFenced() {
            val card = section(funcFiles["funcs.md"] ?: "", "funcs.deny")
            val matching = bullets(card).filter { it.contains("](#funcs-is-prod)") }
            assertThat(matching).hasSize(1)
            assertThat(matching.single()).contains("deployment ▸ environment").doesNotContain("input.")
            assertThat(fencedSource(card)).doesNotContain("is_prod(")
        }

        @Test
        @DisplayName("the function's own card declares its parameter: `ns` in is_exempt(ns)")
        fun k8sFunctionCardDeclaresItsParameter() {
            val card = section(files["k8s-admission.md"] ?: "", "k8s.admission.is_exempt")
            val line = unfenced(card).filter { it.contains("Parameters", ignoreCase = true) }
            assertThat(line).describedAs("a Parameters line").hasSize(1)
            assertThat(line.single()).contains("`ns`")
        }

        @Test
        @DisplayName("is_exempt's condition calls `ns` a parameter, and its coverage footer reports no unrendered value")
        fun k8sFunctionConditionNamesTheParameter() {
            val card = section(files["k8s-admission.md"] ?: "", "k8s.admission.is_exempt")
            val matching = bullets(card).filter { it.contains("kube-system") }
            assertThat(matching).hasSize(1)
            assertThat(matching.single()).containsIgnoringCase("parameter").contains("ns")
            assertThat(card).doesNotContain("unrendered value")
        }

        @Test
        @DisplayName("a two-parameter function declares both parameters")
        fun twoParameterFunctionDeclaresBoth() {
            val card = section(funcFiles["funcs.md"] ?: "", "funcs.in_region")
            val lines = unfenced(card).filter { it.contains("Parameters", ignoreCase = true) }
            assertThat(lines).describedAs("a Parameters line").hasSize(1)
            assertThat(lines.single()).contains("`region`").contains("`allowed`")
        }
    }

    @Nested
    @DisplayName("Item 8 — fallback stays sacred")
    inner class FallbackStaysSacred {

        @Test
        @DisplayName("a call whose argument is a comprehension stays fenced source and is not linked")
        fun unmappableArgumentStaysFenced() {
            val card = section(funcFiles["funcs.md"] ?: "", "funcs.deny")
            assertThat(fencedSource(card)).contains("in_region(input.region, {r | some r in input.regions; net.cidr_is_valid(r)})")
            assertThat(bullets(card).count { it.contains("](#funcs-in-region)") }).isEqualTo(1)
        }

        @Test
        @DisplayName("json.is_valid now renders as a humanized bullet (spec §14 promotion; was the 'unsupported builtin stays fenced' example)")
        fun jsonIsValidNowRendersAsAHumanizedBullet() {
            val card = section(funcFiles["funcs.md"] ?: "", "funcs.deny")
            assertThat(fencedSource(card)).doesNotContain("json.is_valid")
            assertThat(bullets(card)).anySatisfy { assertThat(it).contains("items").contains("is valid JSON") }
        }

        @Test
        @DisplayName("authz.md's `every` block stays fenced source (guard: passes today)")
        fun authzEveryStaysFenced() {
            val src = fencedSource(files["authz.md"] ?: "")
            assertThat(src).contains("every role in input.user.roles")
        }
    }

    @Nested
    @DisplayName("Item 9 — produced messages survive the common shapes")
    inner class ProducedMessages {

        @Test
        @DisplayName("K8S-001: `sprintf(\"container %v is privileged\", [container.name])` shows a humanized Produces line")
        fun k8s001ProducesWithAnIterationVariablePlaceholder() {
            val lines = producesLines(section(files["k8s-admission.md"] ?: "", "K8S-001"))
            assertThat(lines).hasSize(1)
            assertThat(lines.single()).contains("is privileged").contains("name")
            assertHumanized(lines.single())
        }

        @Test
        @DisplayName("K8S-002: `sprintf(\"image %v not from approved registry\", [container.image])` shows a humanized Produces line")
        fun k8s002ProducesWithAnAssignedIterationVariablePlaceholder() {
            val lines = producesLines(section(files["k8s-admission.md"] ?: "", "K8S-002"))
            assertThat(lines).hasSize(1)
            assertThat(lines.single()).contains("not from approved registry").contains("image")
            assertHumanized(lines.single())
        }

        @Test
        @DisplayName("TF-001: `sprintf(\"%s lacks encryption\", [rc.address])` shows a humanized Produces line")
        fun tf001Produces() {
            val lines = producesLines(section(files["terraform-s3.md"] ?: "", "TF-001"))
            assertThat(lines).hasSize(1)
            assertThat(lines.single()).contains("lacks encryption").contains("address")
            assertHumanized(lines.single())
        }

        @Test
        @DisplayName("a two-placeholder message (`Bucket %s (%s) would be deleted`) shows both placeholders humanized")
        fun twoPlaceholderMessage() {
            val lines = producesLines(section(files["terraform-s3.md"] ?: "", "terraform.s3.deny"))
                .filter { it.contains("would be deleted") }
            assertThat(lines).hasSize(1)
            assertThat(lines.single()).contains("Bucket").contains("address").contains("region")
            assertHumanized(lines.single())
        }
    }

    @Nested
    @DisplayName("Item 9 — an object-valued head's message variable")
    inner class ObjectHeadMessage {

        @Test
        @DisplayName("`deny contains {\"msg\": msg, ...}` with `msg := \"public acl\"` shows Produces \"public acl\"")
        fun objectHeadProducesTheMessage() {
            val card = section(files["terraform-s3.md"] ?: "", "terraform.s3.deny")
            assertThat(producesLines(card).filter { it.contains("public acl") }).hasSize(1)
        }

        @Test
        @DisplayName("that `msg := \"public acl\"` is not shown as a fenced unrendered bullet")
        fun objectHeadMessageAssignmentIsNotFenced() {
            val card = section(files["terraform-s3.md"] ?: "", "terraform.s3.deny")
            assertThat(fencedSource(card)).doesNotContain("msg := \"public acl\"")
        }

        @Test
        @DisplayName("bracket-form `violation[{\"msg\": m}]` with `m := \"not prod\"` shows Produces and no fenced `m :=`")
        fun bracketFormHeadProducesTheMessage() {
            val card = section(files["terraform-s3.md"] ?: "", "terraform.s3.violation")
            assertThat(producesLines(card).filter { it.contains("not prod") }).hasSize(1)
            assertThat(fencedSource(card)).doesNotContain("m := ")
        }
    }
}
