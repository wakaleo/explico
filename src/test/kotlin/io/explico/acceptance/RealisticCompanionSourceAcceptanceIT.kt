/**
 * Acceptance tests for FR-10 of the decision-object feature requests: a policy is often owned by a host-application module, not only by the `.rego`
 * file, so a second URL template (`companionUrl`, CLI: `--companion-url`) lets a card point at both. Decisions (asked of the operator): the template
 * takes `{file}`, `{line}` (as `sourceUrl`) plus `{package}` (the Rego package path, e.g. `app.billing`), at least one of `{file}`/`{package}`
 * required; the link is labelled by `companionLabel` (CLI: `--companion-label`, default `module`); it appears on each card's "defined in" line, on each
 * package page's source files and in `explico.json` (`companionUrl`), but NOT in the index. Opt-in: without it every file is byte-identical to before.
 * Authored from the feature request and the plan, NOT from the frozen acceptance-pack README. Fixtures: `realistic/companion-source/policies`
 * (`billing/charge.rego` charge_ok at line 8 and helper_rule at 10, `billing/refund.rego` refund_ok at 8 -- charge_ok and refund_ok share control id BIL-1 --
 * and `ops/audit.rego` audit_on at 8).
 */
package io.explico.acceptance

import io.explico.Explico
import io.explico.opa.OpaRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path

@DisplayName("Realistic policies: a companion source link (FR-10)")
class RealisticCompanionSourceAcceptanceIT {

    companion object {
        private const val SOURCE = "https://git.example.com/org/repo/blob/abc123/policies/{file}#L{line}"
        private const val COMPANION = "https://modules.example.com/{package}/src/{file}#L{line}"
        private fun module(packagePath: String, file: String, line: Int) = "https://modules.example.com/$packagePath/src/$file#L$line"

        private val policies = Path.of("src/test/resources/realistic/companion-source/policies")

        private lateinit var both: Map<String, String>
        private lateinit var bothAgain: Map<String, String>
        private lateinit var companionOnly: Map<String, String>
        private lateinit var labelled: Map<String, String>
        private lateinit var packageOnly: Map<String, String>
        private lateinit var grouped: Map<String, String>
        private lateinit var sourceOnly: Map<String, String>
        private lateinit var plain: Map<String, String>
        private lateinit var plainExplicit: Map<String, String>

        private fun render(
            sourceUrl: String? = null, companionUrl: String? = null, label: String = "module", group: Boolean = false, json: Boolean = true,
        ): Map<String, String> {
            val set = Explico.load(policies)
            return Explico.render(set, policies, null, null, false, json, emptyList(), sourceUrl, null, "{id}", group, companionUrl, label).files
        }

        @JvmStatic
        @BeforeAll
        fun renderAll() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            both = render(SOURCE, COMPANION)
            bothAgain = render(SOURCE, COMPANION)
            companionOnly = render(companionUrl = COMPANION)
            labelled = render(SOURCE, COMPANION, label = "Billing module")
            packageOnly = render(companionUrl = "https://hosts.example.com/m/{package}")
            grouped = render(SOURCE, COMPANION, group = true)
            sourceOnly = render(SOURCE)
            plain = render()
            val set = Explico.load(policies)
            plainExplicit = Explico.render(set, policies, null, null, false, true).files
        }

        private fun page(files: Map<String, String>, name: String): String {
            assertThat(files).describedAs("the rendered files").containsKey(name)
            return files.getValue(name)
        }

        /** The "defined in" line of the card for [rule] on [page]; an ASSERTION failure unless there is exactly one. */
        private fun definedIn(page: String, rule: String): String {
            val lines = page.lines().filter { it.startsWith("*Rule `$rule` in package") }
            assertThat(lines).describedAs("exactly one 'defined in' line for $rule").hasSize(1)
            return lines.single()
        }

        private fun sourceFilesLine(page: String): String {
            val lines = page.lines().filter { it.startsWith("*Source files:") }
            assertThat(lines).describedAs("exactly one 'Source files' line").hasSize(1)
            return lines.single()
        }

        private fun ruleJson(files: Map<String, String>, pkg: String, rule: String): JsonObject =
            Json.parseToJsonElement(page(files, "explico.json")).jsonObject.getValue("packages").jsonArray.map { it.jsonObject }
                .first { it.getValue("path").jsonPrimitive.content == pkg }.getValue("rules").jsonArray.map { it.jsonObject }
                .first { it.getValue("name").jsonPrimitive.content == rule }
    }

    @Nested
    @DisplayName("The card")
    inner class TheCard {

        @Test
        @DisplayName("the 'defined in' line keeps the source link and gains the companion link after it, labelled 'module', with the package and file filled in")
        fun cardLine() {
            val line = definedIn(page(both, "app-billing.md"), "charge_ok")
            val source = "[`charge.rego:8`](https://git.example.com/org/repo/blob/abc123/policies/billing/charge.rego#L8)"
            val companion = "[module](${module("app.billing", "billing/charge.rego", 8)})"
            assertThat(line).contains(source).contains(companion)
            assertThat(line.indexOf(source)).isLessThan(line.indexOf(companion))
        }

        @Test
        @DisplayName("each rule links its own file and line: refund_ok in refund.rego, helper_rule at line 10, audit_on in the other package")
        fun eachCardItsOwnLocation() {
            assertThat(definedIn(page(both, "app-billing.md"), "refund_ok")).contains("[module](${module("app.billing", "billing/refund.rego", 8)})")
            assertThat(definedIn(page(both, "app-billing.md"), "helper_rule")).contains("[module](${module("app.billing", "billing/charge.rego", 10)})")
            assertThat(definedIn(page(both, "app-ops.md"), "audit_on")).contains("[module](${module("app.ops", "ops/audit.rego", 8)})")
        }

        @Test
        @DisplayName("the label is an option: 'Billing module' replaces 'module'")
        fun label() {
            val line = definedIn(page(labelled, "app-billing.md"), "charge_ok")
            assertThat(line).contains("[Billing module](${module("app.billing", "billing/charge.rego", 8)})").doesNotContain("[module]")
        }

        @Test
        @DisplayName("without --source-url the file stays plain text and the companion link is still there")
        fun companionAlone() {
            val line = definedIn(page(companionOnly, "app-billing.md"), "charge_ok")
            assertThat(line).contains("defined in `charge.rego`").contains("[module](${module("app.billing", "billing/charge.rego", 8)})")
        }

        @Test
        @DisplayName("a template with {package} and no {file} or {line} is accepted: the link is the package-level URL")
        fun packageOnlyTemplate() {
            assertThat(definedIn(page(packageOnly, "app-billing.md"), "charge_ok")).contains("[module](https://hosts.example.com/m/app.billing)")
            assertThat(definedIn(page(packageOnly, "app-ops.md"), "audit_on")).contains("[module](https://hosts.example.com/m/app.ops)")
        }

        @Test
        @DisplayName("on a control page (--group-by-control) each rule's card carries its own companion link")
        fun controlPage() {
            val control = page(grouped, "controls/bil-1.md")
            assertThat(definedIn(control, "charge_ok")).contains("[module](${module("app.billing", "billing/charge.rego", 8)})")
            assertThat(definedIn(control, "refund_ok")).contains("[module](${module("app.billing", "billing/refund.rego", 8)})")
        }
    }

    @Nested
    @DisplayName("Package pages, the index and explico.json")
    inner class OtherDocuments {

        @Test
        @DisplayName("a package page's source files each gain the companion link for that file (line 1, as the source link)")
        fun packagePage() {
            val line = sourceFilesLine(page(both, "app-billing.md"))
            assertThat(line).contains("[module](${module("app.billing", "billing/charge.rego", 1)})").contains("[module](${module("app.billing", "billing/refund.rego", 1)})")
            assertThat(line).contains("(https://git.example.com/org/repo/blob/abc123/policies/billing/charge.rego#L1)")
        }

        @Test
        @DisplayName("the index is untouched by the companion link: identical to a render with the source URL alone")
        fun indexUnchanged() {
            assertThat(page(both, "index.md")).isEqualTo(page(sourceOnly, "index.md")).doesNotContain("[module]")
        }

        @Test
        @DisplayName("explico.json gives every rule its companionUrl, the same URL the card shows; the key is absent when no companion template was given")
        fun json() {
            val rule = ruleJson(both, "app.billing", "charge_ok")
            assertThat(rule).containsKey("companionUrl")
            val url = rule.getValue("companionUrl").jsonPrimitive.content
            assertThat(url).isEqualTo(module("app.billing", "billing/charge.rego", 8))
            assertThat(definedIn(page(both, "app-billing.md"), "charge_ok")).contains("($url)")
            assertThat(ruleJson(both, "app.ops", "audit_on").getValue("companionUrl").jsonPrimitive.content).isEqualTo(module("app.ops", "ops/audit.rego", 8))
            assertThat(ruleJson(sourceOnly, "app.billing", "charge_ok")).doesNotContainKey("companionUrl")
        }
    }

    @Nested
    @DisplayName("Off by default, determinism, validation")
    inner class Boundaries {

        @Test
        @DisplayName("without the option every file is exactly what it was: an explicit null is the default, and no companion text appears anywhere")
        fun offByDefault() {
            assertThat(plain).isEqualTo(plainExplicit)
            assertThat(plain.values.none { "[module]" in it || "companionUrl" in it }).isTrue()
            assertThat(sourceOnly.getValue("app-billing.md")).doesNotContain("[module]")
        }

        @Test
        @DisplayName("deterministic: two renders give the same files in the same order")
        fun deterministic() {
            assertThat(bothAgain.toList()).isEqualTo(both.toList())
        }

        @Test
        @DisplayName("a template with neither {file} nor {package} is rejected before any opa work, naming both placeholders")
        fun templateNeedsAPlaceholder() {
            val set = Explico.load(policies)
            assertThatThrownBy { Explico.render(set, policies, null, null, false, false, emptyList(), null, null, "{id}", false, "https://x.example.com/only-line#L{line}") }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("{file}").hasMessageContaining("{package}")
        }

        @Test
        @DisplayName("a template with only {file} is accepted (same rule as --source-url)")
        fun fileOnlyIsAccepted() {
            val files = render(companionUrl = "https://x.example.com/{file}")
            assertThat(definedIn(page(files, "app-ops.md"), "audit_on")).contains("[module](https://x.example.com/ops/audit.rego)")
        }

        @Test
        @DisplayName("a label that would break the link is rejected: blank, containing ']' or a line break")
        fun badLabels() {
            val set = Explico.load(policies)
            for (label in listOf("", "   ", "a]b", "a\nb")) {
                assertThatThrownBy { Explico.render(set, policies, null, null, false, false, emptyList(), null, null, "{id}", false, COMPANION, label) }
                    .describedAs("label '%s'", label.replace("\n", "\\n")).isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("label")
            }
        }
    }
}
