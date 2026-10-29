/**
 * Spec §14.55: deriving a rule's control id from its source FILE NAME (opt-in, `render` only). Pure -- hand-built domain objects, no opa.
 *
 * `pattern` is a regex with a named group `id`, applied with `find` to the file's BASE name; `template` writes the id (`CTRL-{id}`). Derived
 * only when the match succeeded, the `id` group took part and captured something. METADATA's own `control-id` always wins; only the id is
 * derived. Everything downstream reads the model, so the heading, anchor, links, index and JSON need no special case.
 */
package io.explico.render

import io.explico.model.PackageMetadata
import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RegoVersion
import io.explico.model.RuleBody
import io.explico.model.RuleGroup
import io.explico.model.RuleMetadata
import io.explico.model.SourceRef
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class ControlIdFromFilenameTest {

    private val pattern = "ctrl_(?<id>\\d+)"
    private fun derive(pattern: String = this.pattern, template: String = "CTRL-{id}") = ControlIdFromFilename.of(pattern, template)!!

    @Nested
    inner class Validation {

        @Test
        @DisplayName("no pattern -> nothing to do (null), with the default template")
        fun noPattern() {
            assertThat(ControlIdFromFilename.of(null, "{id}")).isNull()
        }

        @Test
        @DisplayName("a template WITHOUT a pattern is a mistake (it would silently do nothing), and says what to add")
        fun templateWithoutPattern() {
            assertThatThrownBy { ControlIdFromFilename.of(null, "CTRL-{id}") }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("control-id-from")
        }

        @ParameterizedTest(name = "invalid regular expression: {0}")
        @ValueSource(strings = ["ctrl_(?<id>\\d+", "ctrl_[", "*ctrl", "(?<id>a)(?<id>b)", "ctrl_(?<id>\\d+)\\"])
        @DisplayName("a pattern that is not a regular expression is reported with the pattern in the message")
        fun invalidSyntax(bad: String) {
            assertThatThrownBy { ControlIdFromFilename.of(bad, "{id}") }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("regular expression")
        }

        @ParameterizedTest(name = "no group named id: {0}")
        @ValueSource(strings = ["ctrl_(\\d+)", "ctrl_\\d+", "ctrl_(?<ident>\\d+)", "ctrl_(?:\\d+)", "ctrl_\\(?<id>\\d+"])
        @DisplayName("a pattern without the named group `id` says how to write it")
        fun missingGroup(bad: String) {
            assertThatThrownBy { ControlIdFromFilename.of(bad, "{id}") }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("(?<id>")
        }

        @ParameterizedTest(name = "template: {0}")
        @ValueSource(strings = ["CTRL-", "", "CTRL-{ID}", "CTRL-{ id }", "{}"])
        @DisplayName("a template without {id} says so")
        fun templateWithoutId(bad: String) {
            assertThatThrownBy { ControlIdFromFilename.of(pattern, bad) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("{id}")
        }

        @ParameterizedTest(name = "accepted: {0}")
        @ValueSource(strings = ["ctrl_(?<id>\\d+)", "(?i)ctrl_(?<id>\\d+)", "^(?<id>[A-Z]+-\\d+)", "(?<id>\\d+)\\.rego", "(?x) ctrl_ # the prefix\n (?<id>\\d+)", "(?:a|b)(?<id>c)?"])
        @DisplayName("valid patterns are accepted, including flags and comments mode (a trailing # comment must not hide the group)")
        fun accepted(good: String) {
            assertThat(ControlIdFromFilename.of(good, "{id}")).isNotNull()
        }

        @Test
        @DisplayName("the CLI validates the two options on their own, with the same messages")
        fun standaloneValidators() {
            assertThat(ControlIdFromFilename.validatePattern(pattern)).isEqualTo(pattern)
            assertThat(ControlIdFromFilename.validateTemplate("CTRL-{id}")).isEqualTo("CTRL-{id}")
            assertThatThrownBy { ControlIdFromFilename.validatePattern("ctrl_(\\d+)") }.hasMessageContaining("(?<id>")
            assertThatThrownBy { ControlIdFromFilename.validateTemplate("CTRL-") }.hasMessageContaining("{id}")
        }
    }

    @Nested
    inner class Matching {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource(
            delimiter = '|',
            value = [
                "ctrl_1123.rego | CTRL-1123",
                "team_a/ctrl_19_signoff.rego | CTRL-19",
                "a/b/c/ctrl_7.rego | CTRL-7",
                "my_ctrl_5_x.rego | CTRL-5",
                "ctrl_1_ctrl_2.rego | CTRL-1",
            ],
        )
        @DisplayName("the first match in the BASE name, wherever it is in the name")
        fun derives(file: String, expected: String) {
            assertThat(derive().idFor(file)).isEqualTo(expected)
        }

        @Test
        @DisplayName("CHALLENGE: Windows separators are handled -- only the base name is matched")
        fun windowsSeparators() {
            assertThat(derive().idFor("team_a\\ctrl_19_signoff.rego")).isEqualTo("CTRL-19")
            assertThat(derive().idFor("ctrl_99\\notes.rego")).isNull()
            assertThat(derive().idFor("C:\\policies\\ctrl_99\\sub/ctrl_4.rego")).isEqualTo("CTRL-4")
        }

        @ParameterizedTest(name = "no id from {0}")
        @ValueSource(strings = ["helpers.rego", "ctrl_99/notes.rego", "Ctrl_9.rego", "ctrl_.rego", "ctrl.rego", "", "/", "ctrl_99/"])
        @DisplayName("CHALLENGE: no match, a directory-only match, a different case, no digits, or nothing at all -> no id (and never a crash)")
        fun noId(file: String) {
            assertThat(derive().idFor(file)).isNull()
        }

        @Test
        @DisplayName("(?i) makes it case-insensitive")
        fun caseInsensitive() {
            assertThat(derive("(?i)ctrl_(?<id>\\d+)").idFor("Ctrl_9.rego")).isEqualTo("CTRL-9")
        }

        @Test
        @DisplayName("an empty capture derives nothing -- never a bare 'CTRL-'")
        fun emptyCapture() {
            assertThat(derive("ctrl_(?<id>\\d*)").idFor("ctrl_.rego")).isNull()
            assertThat(derive("ctrl_(?<id>\\d*)").idFor("ctrl_12.rego")).isEqualTo("CTRL-12")
        }

        @Test
        @DisplayName("a match in which the `id` group took no part derives nothing")
        fun nonParticipatingGroup() {
            assertThat(derive("ctrl_(?<id>\\d+)|helpers").idFor("helpers.rego")).isNull()
            assertThat(derive("(?<id>\\d+)?x").idFor("x.rego")).isNull()
        }

        @Test
        @DisplayName("CHALLENGE: regex special characters and unicode in the file name are captured as they are")
        fun specialCharactersAndUnicode() {
            assertThat(derive("ctrl_(?<id>[^.]+)").idFor("ctrl_a+b(c).rego")).isEqualTo("CTRL-a+b(c)")
            assertThat(derive("ctrl_(?<id>\\p{L}\\d)").idFor("ctrl_é1.rego")).isEqualTo("CTRL-é1")
            assertThat(derive("ctrl_(?<id>\\S+?)\\.rego").idFor("ctrl_日本語.rego")).isEqualTo("CTRL-日本語")
        }

        @Test
        @DisplayName("CHALLENGE: a backtracking-prone pattern does not hang on an ordinary file name (the pattern is operator-supplied and the input one short base name, so no timeout is used; this JDK short-circuits these shapes anyway)")
        fun aBacktrackingProneIsNotTreatedSpecially() {
            val started = System.nanoTime()
            derive("(?<id>(a+)+)b").idFor("a".repeat(24) + ".rego")
            assertThat((System.nanoTime() - started) / 1_000_000).describedAs("milliseconds").isLessThan(10_000)
        }
    }

    @Nested
    inner class Template {

        @Test
        @DisplayName("the default template is the captured text; {id} is replaced wherever it stands, every time")
        fun placeholder() {
            assertThat(derive(template = "{id}").idFor("ctrl_5.rego")).isEqualTo("5")
            assertThat(derive(template = "A-{id}-{id}").idFor("ctrl_5.rego")).isEqualTo("A-5-5")
        }

        @Test
        @DisplayName("CHALLENGE: replacement-special characters are LITERAL -- `$1`, backslashes and braces in the template or in the captured id")
        fun literal() {
            assertThat(derive(template = "X\$1\\{id}\\").idFor("ctrl_5.rego")).isEqualTo("X\$1\\5\\")
            assertThat(derive("ctrl_(?<id>[^.]+)", "{id}").idFor("ctrl_\$1.rego")).isEqualTo("\$1")
            // (a backslash inside a FILE name is a path separator, so only the base name after it is matched)
            assertThat(derive("(?<id>.+)\\.rego", "{id}").idFor("a\\\$1b.rego")).isEqualTo("\$1b")
            assertThat(derive("ctrl_(?<id>[^.]+)", "{id}").idFor("ctrl_{id}.rego")).isEqualTo("{id}")
        }
    }

    @Nested
    inner class UnsafeIds {

        @ParameterizedTest(name = "capture {0}")
        @ValueSource(strings = ["a|b", "|", "a\nb", "a\rb"])
        @DisplayName("CHALLENGE: an id that would break an index table row or a heading (a pipe or a line break) is NOT derived, and the reason is said once")
        fun refused(captured: String) {
            val warnings = mutableListOf<String>()
            val id = derive("(?s)ctrl_(?<id>.+)\\.rego", "CTRL-{id}").idFor("ctrl_$captured.rego") { warnings += it }

            assertThat(id).isNull()
            assertThat(warnings).hasSize(1)
            assertThat(warnings.single()).startsWith("explico: warning:").contains("ctrl_").contains("|")
        }

        @Test
        @DisplayName("a pipe supplied by the TEMPLATE is the operator's own choice for every id and is refused up front too")
        fun templateWithPipe() {
            assertThatThrownBy { ControlIdFromFilename.of(pattern, "A|{id}") }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("|")
            assertThatThrownBy { ControlIdFromFilename.of(pattern, "A\n{id}") }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        @DisplayName("an ordinary id with other punctuation (brackets, backticks, hash) is derived like a METADATA one -- shown as written")
        fun otherPunctuationIsAllowed() {
            assertThat(derive("ctrl_(?<id>[^.]+)").idFor("ctrl_[x]#1.rego")).isEqualTo("CTRL-[x]#1")
        }
    }

    @Nested
    inner class ApplyingToAPolicySet {

        private fun rule(name: String, file: String?, metadata: RuleMetadata? = null, split: Boolean = false) = RuleGroup(
            name, metadata, null,
            bodies = if (file == null) emptyList() else listOf(RuleBody(emptyList(), null, null, SourceRef(file, 1), "")),
            isSplitDefaultEntry = split,
        )

        private fun set(vararg rules: RuleGroup) = PolicySet(listOf(PolicyPackage("p", rules.toList(), listOf("f.rego"))), RegoVersion.V1)
        private fun idsOf(set: PolicySet) = set.packages.flatMap { it.rules }.map { it.name to it.metadata?.controlId }

        @Test
        @DisplayName("a rule with no METADATA gains one holding only the derived id")
        fun noMetadata() {
            val applied = derive().applyTo(set(rule("deny", "ctrl_1123.rego")))

            assertThat(applied.packages.single().rules.single().metadata).isEqualTo(RuleMetadata(null, null, "CTRL-1123", emptyList(), controlIdDerivedFrom = "ctrl_1123.rego"))
        }

        @Test
        @DisplayName("METADATA without an id keeps everything else it declares, and gains the id")
        fun metadataWithoutId() {
            val declared = RuleMetadata("Title", "Description", null, listOf("SOC 2"), customFields = mapOf("owner" to "p"))
            val applied = derive().applyTo(set(rule("check", "ctrl_55.rego", declared)))

            assertThat(applied.packages.single().rules.single().metadata).isEqualTo(declared.copy(controlId = "CTRL-55", controlIdDerivedFrom = "ctrl_55.rego"))
        }

        @Test
        @DisplayName("METADATA's own control-id always wins, and the rule is left exactly as it was")
        fun metadataWins() {
            val declared = RuleMetadata("Title", null, "SEC-77", emptyList())
            val original = rule("allow", "ctrl_77.rego", declared)
            val applied = derive().applyTo(set(original))

            assertThat(applied.packages.single().rules.single()).isSameAs(original)
        }

        @Test
        @DisplayName("every rule of a file gets the file's id; rules of other files get theirs; a rule that does not match is untouched")
        fun manyRules() {
            val applied = derive().applyTo(set(rule("a", "ctrl_8.rego"), rule("b", "ctrl_8.rego"), rule("c", "team/ctrl_9.rego"), rule("d", "helpers.rego")))

            assertThat(idsOf(applied)).containsExactly("a" to "CTRL-8", "b" to "CTRL-8", "c" to "CTRL-9", "d" to null)
        }

        @Test
        @DisplayName("CHALLENGE: a split-default entry is left alone (it has no body of its own; an id would replace its 'Default for ...' heading)")
        fun splitDefaultEntry() {
            val applied = derive().applyTo(set(rule("allow", "ctrl_3.rego"), rule("allow", "ctrl_3.rego", split = true)))

            assertThat(applied.packages.single().rules.map { it.metadata?.controlId }).containsExactly("CTRL-3", null)
        }

        @Test
        @DisplayName("provenance: the derived id records the file's BASE name (not its directory), and an id the METADATA declared records nothing")
        fun recordsWhereTheIdCameFrom() {
            val declared = RuleMetadata("T", null, "SEC-1", emptyList())
            val applied = derive().applyTo(set(rule("a", "team/sub\\ctrl_19_signoff.rego"), rule("b", "ctrl_77.rego", declared)))

            assertThat(applied.packages.single().rules.map { it.metadata?.controlIdDerivedFrom }).containsExactly("ctrl_19_signoff.rego", null)
        }

        @Test
        @DisplayName("a rule with no location at all (nothing to take a file name from) is left alone")
        fun noLocation() {
            assertThat(idsOf(derive().applyTo(set(rule("x", null))))).containsExactly("x" to null)
        }

        @Test
        @DisplayName("the file is the rule's FIRST body's file -- the same definition the card and the index use for 'defined in'")
        fun usesTheSharedRuleLocation() {
            val twoFiles = RuleGroup(
                "r", null, null,
                listOf(RuleBody(emptyList(), null, null, SourceRef("ctrl_1.rego", 1), ""), RuleBody(emptyList(), null, null, SourceRef("ctrl_2.rego", 1), "")),
            )
            assertThat(idsOf(derive().applyTo(set(twoFiles)))).containsExactly("r" to "CTRL-1")
        }

        @Test
        @DisplayName("everything else in the set is preserved: package paths and metadata, source files, the Rego version")
        fun restIsPreserved() {
            val original = PolicySet(
                listOf(PolicyPackage("p.q", listOf(rule("a", "ctrl_1.rego")), listOf("ctrl_1.rego"), PackageMetadata("T", "D"))),
                RegoVersion.V0,
            )
            val applied = derive().applyTo(original)

            assertThat(applied.regoVersion).isEqualTo(RegoVersion.V0)
            assertThat(applied.packages.single().path).isEqualTo("p.q")
            assertThat(applied.packages.single().sourceFiles).containsExactly("ctrl_1.rego")
            assertThat(applied.packages.single().metadata).isEqualTo(PackageMetadata("T", "D"))
        }

        @Test
        @DisplayName("a pattern matching nothing returns an equal set")
        fun nothingMatches() {
            val original = set(rule("a", "helpers.rego"))
            assertThat(derive("zzz_(?<id>\\d+)").applyTo(original)).isEqualTo(original)
        }

        @Test
        @DisplayName("CHALLENGE: the same id on rules in different files and packages, and one equal to a METADATA id elsewhere, are NOT errors here (render must not crash; diff is untouched)")
        fun duplicatesAreNotAnError() {
            val explicit = rule("m", "other.rego", RuleMetadata("T", null, "CTRL-5", emptyList()))
            val original = PolicySet(
                listOf(
                    PolicyPackage("one", listOf(rule("a", "ctrl_5.rego"), explicit), listOf("f"), null),
                    PolicyPackage("two", listOf(rule("b", "sub/ctrl_5.rego")), listOf("g"), null),
                ),
            )
            val applied = derive().applyTo(original)

            assertThat(applied.packages.flatMap { it.rules }.map { it.metadata?.controlId }).containsExactly("CTRL-5", "CTRL-5", "CTRL-5")
        }

        @Test
        @DisplayName("an unsafe id is skipped with ONE warning per file, however many rules the file has; the rules keep no id")
        fun unsafeIdWarnsOncePerFile() {
            val warnings = mutableListOf<String>()
            val applied = derive("ctrl_(?<id>.+)\\.rego").applyTo(set(rule("a", "ctrl_x|y.rego"), rule("b", "ctrl_x|y.rego"), rule("c", "ctrl_ok.rego"))) { warnings += it }

            assertThat(idsOf(applied)).containsExactly("a" to null, "b" to null, "c" to "CTRL-ok")
            assertThat(warnings).hasSize(1)
        }

        @Test
        @DisplayName("deterministic: applying twice gives equal sets")
        fun deterministic() {
            val original = set(rule("a", "ctrl_1.rego"), rule("b", "ctrl_2.rego"))
            assertThat(derive().applyTo(original)).isEqualTo(derive().applyTo(original))
        }
    }
}
