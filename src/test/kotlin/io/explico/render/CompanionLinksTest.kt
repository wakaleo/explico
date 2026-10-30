/**
 * Spec §14.58: the companion link helper and where it appears, on hand-built domain objects (no opa). The real pages are pinned by
 * RealisticCompanionSourceAcceptanceIT.
 */
package io.explico.render

import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RuleBody
import io.explico.model.RuleGroup
import io.explico.model.SourceRef
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CompanionLinksTest {

    private fun links(template: String, label: String = "module") = CompanionLinks.of(template, label)!!

    @Nested
    inner class Urls {

        @Test
        @DisplayName("no template: no helper")
        fun none() {
            assertThat(CompanionLinks.of(null, "module")).isNull()
        }

        @Test
        @DisplayName("{package}, {file} and {line} are substituted at every occurrence")
        fun everyOccurrence() {
            assertThat(links("https://m/{package}/{file}?p={package}&l={line}&l2={line}").url("app.billing", "billing/charge.rego", 8))
                .isEqualTo("https://m/app.billing/billing/charge.rego?p=app.billing&l=8&l2=8")
        }

        @Test
        @DisplayName("a template with only {package} (no {file}, no {line}) is valid; so is one with only {file}")
        fun onePlaceholderIsEnough() {
            assertThat(links("https://m/{package}").url("a.b", "x.rego", 3)).isEqualTo("https://m/a.b")
            assertThat(links("https://m/{file}").url("a.b", "x.rego", 3)).isEqualTo("https://m/x.rego")
        }

        @Test
        @DisplayName("a file is encoded per segment keeping '/', a package as one string: space, '#', '?', '/', unicode and braces cannot break out of the URL")
        fun encoding() {
            val url = links("https://m/{package}|{file}").url("a b.c#d/e", "dir x/f#1?é.rego", 1)
            assertThat(url).isEqualTo("https://m/a%20b.c%23d%2Fe|dir%20x/f%231%3F%C3%A9.rego")
        }

        @Test
        @DisplayName("single pass: placeholder-looking text inside a package or file name is encoded, never substituted again")
        fun singlePass() {
            val url = links("https://m/{package}/{file}#L{line}").url("p{line}", "{file}{package}.rego", 7)
            assertThat(url).isEqualTo("https://m/p%7Bline%7D/%7Bfile%7D%7Bpackage%7D.rego#L7")
        }
    }

    @Nested
    inner class Validation {

        @Test
        @DisplayName("a template with neither {file} nor {package} is rejected, naming both")
        fun needsAPlaceholder() {
            assertThatThrownBy { CompanionLinks.of("https://m/#L{line}", "module") }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("{file}").hasMessageContaining("{package}")
        }

        @Test
        @DisplayName("a label that could break the link, a table or the italic line is rejected, naming the label; ordinary text, spaces and unicode are fine")
        fun labels() {
            for (bad in listOf("", " ", "a]b", "a[b", "a|b", "a`b", "a*b", "a\nb", "a\rb", "x](http://evil)")) {
                assertThatThrownBy { CompanionLinks.of("https://m/{file}", bad) }.describedAs("label '%s'", bad).isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("label")
            }
            assertThat(links("https://m/{file}", "Billing module (é)").link("p", "f.rego", 2)).isEqualTo("[Billing module (é)](https://m/f.rego)")
        }

        @Test
        @DisplayName("an invalid label is not an error without a template (nothing uses it)")
        fun labelWithoutTemplate() {
            assertThat(CompanionLinks.of(null, "a]b")).isNull()
        }
    }

    @Nested
    inner class Placement {

        private val located = RuleGroup("r", null, null, listOf(RuleBody(emptyList(), null, null, SourceRef("dir/r.rego", 4), "")))
        private val unlocated = RuleGroup("u", null, null, emptyList())
        private val pkg = PolicyPackage("a.b", listOf(located, unlocated), listOf("dir/r.rego"))
        private val set = PolicySet(listOf(pkg))
        private val companion = links("https://m/{package}/{file}#L{line}")

        @Test
        @DisplayName("a card with a location gets the link after the file, ' · ' apart, in the same italic span; one without a location gets none")
        fun card() {
            val line = MarkdownRenderer.renderCard(located, pkg, set, companion = companion).lines().first { it.startsWith("*Rule") }
            assertThat(line).isEqualTo("*Rule `r` in package `a.b` — defined in `r.rego` · [module](https://m/a.b/dir/r.rego#L4)*")
            assertThat(MarkdownRenderer.renderCard(unlocated, pkg, set, companion = companion)).doesNotContain("[module]")
        }

        @Test
        @DisplayName("without a companion the card line is the old one")
        fun cardWithout() {
            assertThat(MarkdownRenderer.renderCard(located, pkg, set).lines().first { it.startsWith("*Rule") }).isEqualTo("*Rule `r` in package `a.b` — defined in `r.rego`*")
        }

        @Test
        @DisplayName("a package page lists each source file with its link, linked or plain; none without a companion")
        fun packagePage() {
            val page = MarkdownRenderer.renderPackage(pkg, set, companion = companion)
            assertThat(page).contains("*Source files: `dir/r.rego` ([module](https://m/a.b/dir/r.rego#L1))*")
            assertThat(MarkdownRenderer.renderPackage(pkg, set)).contains("*Source files: `dir/r.rego`*")
        }

        @Test
        @DisplayName("the JSON has companionUrl for every rule when asked (null for a rule with no location) and no such key otherwise")
        fun json() {
            val with = Json.parseToJsonElement(JsonExport.render(set, "digest", emptyMap(), emptyMap(), null, companion = companion)).jsonObject
            val rules = with.getValue("packages").jsonArray.single().jsonObject.getValue("rules").jsonArray.map { it.jsonObject }
            assertThat(rules[0].getValue("companionUrl").toString()).isEqualTo("\"https://m/a.b/dir/r.rego#L4\"")
            assertThat(rules[1].getValue("companionUrl")).isEqualTo(JsonNull)
            assertThat(JsonExport.render(set, "digest", emptyMap(), emptyMap(), null)).doesNotContain("companionUrl")
        }
    }
}
