/**
 * Acceptance tests for realistic, multi-annotated policy shapes (Gatekeeper-style admission,
 * RBAC authz, Terraform plan checks) under `src/test/resources/realistic/policies/`.
 *
 * NOT transcribed from the frozen acceptance-pack README, which covers only the release pack:
 * these assertions were authored from the "improve rendered output" review, items 1-4, at the
 * operator's explicit request. They pin only what those items require (no card carries another
 * control's title/logic; no rule vanishes; `default` is not a Situation; package METADATA is
 * shown) and deliberately leave wording/ordering/layout choices open.
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

@DisplayName("Realistic policies (review items 1-4)")
class RealisticPoliciesAcceptanceIT {

    companion object {
        private const val POLICIES_DIR = "src/test/resources/realistic/policies"
        private lateinit var files: Map<String, String>

        @JvmStatic
        @BeforeAll
        fun renderDocuments() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            val policyDir = Path.of(POLICIES_DIR)
            val policySet = Explico.load(policyDir)
            files = Explico.render(policySet, policyDir).files
        }

        /** The `## ` section whose heading line contains [marker], up to the next `## ` heading. */
        private fun section(markdown: String, marker: String): String {
            val lines = markdown.lines()
            val start = lines.indexOfFirst { it.startsWith("## ") && it.contains(marker) }
            assertThat(start).describedAs("a '## ' heading containing %s", marker).isGreaterThanOrEqualTo(0)
            val end = lines.drop(start + 1).indexOfFirst { it.startsWith("## ") }
            return lines.drop(start).let { if (end < 0) it else it.take(end + 1) }.joinToString("\n")
        }
    }

    @Nested
    @DisplayName("Item 1 — several annotated rules sharing a name are not collapsed into one card")
    inner class PerRuleMetadata {

        @Test
        @DisplayName("k8s-admission.md has a card headed for each of K8S-001, K8S-002, K8S-003 with its own title")
        fun eachControlGetsItsOwnCard() {
            val md = files["k8s-admission.md"] ?: ""
            assertThat(md).contains("K8S-001 — No privileged containers")
            assertThat(md).contains("K8S-002 — Images from approved registry")
            assertThat(md).contains("K8S-003 — Require labels")
        }

        @Test
        @DisplayName("K8S-001's card holds the privileged check and not another control's logic")
        fun k8s001CardHoldsOnlyItsOwnLogic() {
            val card = section(files["k8s-admission.md"] ?: "", "K8S-001")
            assertThat(card).contains("security context ▸ privileged")
            assertThat(card).doesNotContain("registry.corp.example")
            assertThat(card).doesNotContain("required :=")
        }

        @Test
        @DisplayName("K8S-002's card holds the registry check and not another control's logic")
        fun k8s002CardHoldsOnlyItsOwnLogic() {
            val card = section(files["k8s-admission.md"] ?: "", "K8S-002")
            assertThat(card).contains("registry.corp.example")
            assertThat(card).doesNotContain("security context ▸ privileged")
            assertThat(card).doesNotContain("required :=")
        }

        @Test
        @DisplayName("K8S-003's card holds the labels logic and not another control's logic")
        fun k8s003CardHoldsOnlyItsOwnLogic() {
            val card = section(files["k8s-admission.md"] ?: "", "K8S-003")
            assertThat(card).contains("provided :=")
            assertThat(card).doesNotContain("security context ▸ privileged")
            assertThat(card).doesNotContain("registry.corp.example")
        }

        @Test
        @DisplayName("authz.md has separate cards for AZ-001 and AZ-002")
        fun authzControlsGetSeparateCards() {
            val md = files["authz.md"] ?: ""
            val az001 = section(md, "AZ-001")
            val az002 = section(md, "AZ-002")
            assertThat(az001).contains("Admins may do anything")
            assertThat(az001).doesNotContain("input.resource.owner").doesNotContain("resource ▸ owner")
            assertThat(az002).contains("Owners can edit own docs")
            assertThat(az002).doesNotContain("\"admin\"")
        }

        @Test
        @DisplayName("index.md lists every control id: K8S-001, K8S-002, K8S-003, AZ-001, AZ-002, TF-001")
        fun indexListsEveryControl() {
            val md = files["index.md"] ?: ""
            for (id in listOf("K8S-001", "K8S-002", "K8S-003", "AZ-001", "AZ-002", "TF-001")) {
                assertThat(md).describedAs("index.md mentions %s", id).contains(id)
            }
        }
    }

    @Nested
    @DisplayName("Item 2 — a rule with no simple head name is not silently dropped")
    inner class NoSilentDrop {

        @Test
        @DisplayName("terraform-s3.md renders the Gatekeeper-style violation[{\"msg\": m}] rule's condition")
        fun violationRuleConditionIsRendered() {
            val md = files["terraform-s3.md"] ?: ""
            assertThat(md).contains("`tags ▸ env` is not `\"prod\"`")
        }

        @Test
        @DisplayName("terraform-s3.md names the violation rule")
        fun violationRuleIsNamed() {
            assertThat(files["terraform-s3.md"] ?: "").contains("violation")
        }
    }

    @Nested
    @DisplayName("Item 3 — `default allow := false` is not a Situation")
    inner class DefaultDeclaration {

        @Test
        @DisplayName("No fallback block whose entire source is the bare word `default`")
        fun noBareDefaultSourceBlock() {
            val md = files["authz.md"] ?: ""
            assertThat(md.lines().map { it.trim() }).doesNotContain("default")
        }

        @Test
        @DisplayName("The default value `false` is stated on a line of its own mentioning 'default'")
        fun defaultValueIsStated() {
            val md = files["authz.md"] ?: ""
            assertThat(md.lines().any { Regex("(?i)default").containsMatchIn(it) && it.contains("false") })
                .describedAs("a line mentioning 'default' and its value")
                .isTrue()
        }

        @Test
        @DisplayName("The default is not counted as a Situation: AZ-001's single body is not headed 'Situation'")
        fun defaultIsNotASituationOfAnyCard() {
            val md = files["authz.md"] ?: ""
            val situationBodies = Regex("### Situation \\d+").findAll(md).count()
            // 8 non-default `allow` bodies exist in authz.rego; risk_level's else-chain is one more
            // body but lives in its own rule. Whatever the card split, the 9th "Situation" a
            // default would add must not appear.
            assertThat(situationBodies).isLessThanOrEqualTo(8)
        }
    }

    @Nested
    @DisplayName("Item 4 — package-scope METADATA is shown on the package page")
    inner class PackageMetadata {

        @Test
        @DisplayName("k8s-admission.md shows the package title and description")
        fun packageTitleAndDescriptionShown() {
            val md = files["k8s-admission.md"] ?: ""
            assertThat(md).contains("Container hardening")
            assertThat(md).contains("Containers must not be privileged.")
        }

        @Test
        @DisplayName("The package title appears before the first control card")
        fun packageMetadataPrecedesFirstCard() {
            val md = files["k8s-admission.md"] ?: ""
            assertThat(md.indexOf("Container hardening")).isGreaterThanOrEqualTo(0)
            assertThat(md.indexOf("Container hardening")).isLessThan(md.indexOf("K8S-00"))
        }
    }
}
