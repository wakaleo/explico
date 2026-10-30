/**
 * Acceptance tests for the "No control id" finding. Real corpora (Trivy, Gatekeeper) declare no control ids, so every control-level quality check stays silent for
 * them. The index's *Documentation quality* section (and `quality.noControlId`, and a finding line) now lists the id-less ENTRY POINTS: a rule with no control id,
 * at least one body, that is not a function and that no other rule uses. Stated mechanically -- no guess about what it is for. Decisions (to be confirmed):
 *
 * - "used" = referenced from any card of the loaded policies, wherever a card shows (or keeps as source) a condition or value: a plain reference, a constant used as an
 *   operand, an else branch, an `every` body, a comprehension, a `with`, and across packages through an import; a function, a constant, a default-only rule and
 *   a split default are never listed; labels are `<package>.<rule>`, linked to the card;
 * - advisory (not a gap), shown always, after "No framework" and before "No matching example"; the finding line follows the framework line;
 * - a rule given an id by `--control-id-from` is a control and not listed.
 * This DELIBERATELY conflicts with an existing non-Tier-1 test (RealisticQualityAndRequirementsAcceptanceIT.placementAndScope: an id-less rule is never in the
 * quality section), which /tdd repoints. Authored from the plan, NOT from the frozen acceptance-pack README. Facade only. Fixture: `realistic/no-control-id/policies`
 * (`nc.rego`: NC-1..NC-3 use every helper shape; unreferenced `deny`, `allow_other`, `entry_point`; unused function, constant and default-only rule; `nc2.rego`:
 * `shared_rule` used through an import, and its own unreferenced `deny`) and `realistic/quality-hints/policies` (controls only).
 */
package io.explico.acceptance

import io.explico.Explico
import io.explico.opa.OpaRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

@DisplayName("Realistic policies: rules with no control id")
class RealisticNoControlIdAcceptanceIT {

    companion object {
        private val policies = Path.of("src/test/resources/realistic/no-control-id/policies")
        private val examples = Path.of("src/test/resources/realistic/no-control-id/examples")
        private val clean = Path.of("src/test/resources/realistic/quality-hints/policies")

        private const val BULLET =
            "- **No control id (4):** [nc.allow_other](nc.md#nc-allow-other), [nc.deny](nc.md#nc-deny), [nc.entry_point](nc.md#nc-entry-point), [nc2.deny](nc2.md#nc2-deny) " +
                "— add custom.control-id to its METADATA (or derive one with --control-id-from) so the checks above apply to it"
        private const val LINE = "4 rules have no control id and are not used by another rule"

        private lateinit var plain: io.explico.RenderedDocs
        private lateinit var plainAgain: io.explico.RenderedDocs
        private lateinit var withExamples: io.explico.RenderedDocs
        private lateinit var derived: io.explico.RenderedDocs
        private lateinit var none: io.explico.RenderedDocs

        private fun render(dir: Path, examples: Path? = null, controlIdFrom: String? = null): io.explico.RenderedDocs {
            val set = Explico.load(dir)
            return Explico.render(set, dir, examples?.let { Explico.loadExamples(it) }, null, false, true, emptyList(), null, controlIdFrom)
        }

        @JvmStatic
        @BeforeAll
        fun renderAll() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            plain = render(policies)
            plainAgain = render(policies)
            withExamples = render(policies, examples)
            derived = render(policies, controlIdFrom = "(?<id>nc2?)\\.rego")
            none = render(clean)
        }

        private fun bullets(docs: io.explico.RenderedDocs): List<String> {
            val lines = docs.files.getValue("index.md").lines()
            val start = lines.indexOf("## Documentation quality")
            if (start < 0) return emptyList()
            return lines.drop(start + 1).takeWhile { !it.startsWith("## ") }.filter { it.startsWith("- ") }
        }

        /** The "No control id" bullet; an ASSERTION failure when there is none. */
        private fun noControlIdLine(docs: io.explico.RenderedDocs): String {
            val line = bullets(docs).firstOrNull { it.startsWith("- **No control id") }
            assertThat(line).describedAs("a 'No control id' bullet in ${bullets(docs)}").isNotNull()
            return line!!
        }

        private fun jsonLabels(docs: io.explico.RenderedDocs): List<String> {
            val quality = Json.parseToJsonElement(docs.files.getValue("explico.json")).jsonObject.getValue("quality").jsonObject
            assertThat(quality).describedAs("the quality object").containsKey("noControlId")
            return quality.getValue("noControlId").jsonArray.map { it.jsonPrimitive.content }
        }
    }

    @Test
    @DisplayName("the unreferenced id-less entry points are listed: package.rule, linked to the card, in index order, with the hint; a default and its rule are ONE entry")
    fun theBullet() {
        assertThat(bullets(plain)).contains(BULLET)
    }

    @Test
    @DisplayName("nothing that is used is listed: a plain reference, a constant, an else branch, an every body, a comprehension, a with and an import each count as use")
    fun usedRulesAreNotListed() {
        val line = noControlIdLine(plain)
        for (used in listOf("helper_plain", "max_items", "else_helper", "every_helper", "comp_helper", "helper_with", "shared_rule")) {
            assertThat(line).describedAs(used).doesNotContain(used)
        }
    }

    @Test
    @DisplayName("a function, a constant and a default-only rule that nothing uses are not entry points: not listed")
    fun notEntryPoints() {
        val line = noControlIdLine(plain)
        assertThat(line).doesNotContain("unused_fn").doesNotContain("unused_const").doesNotContain("flag_only")
    }

    @Test
    @DisplayName("two rules of one name in different packages are both listed and told apart by their package")
    fun sameNameInTwoPackages() {
        val line = noControlIdLine(plain)
        assertThat(line).contains("[nc.deny](nc.md#nc-deny)").contains("[nc2.deny](nc2.md#nc2-deny)")
    }

    @Test
    @DisplayName("the bullet sits after 'No framework' and before 'No matching example' and 'Shown as source'")
    fun position() {
        val shown = bullets(withExamples)
        val at = shown.indexOfFirst { it.startsWith("- **No control id") }
        assertThat(at).isGreaterThanOrEqualTo(0)
        assertThat(shown.indexOfFirst { it.startsWith("- **No matching example") }).isGreaterThan(at)
        assertThat(shown.indexOfFirst { it.startsWith("- **Shown as source") }).isGreaterThan(at)
    }

    @Test
    @DisplayName("the finding line is worded once, with the count, and comes before the example-based and later lines")
    fun findingLine() {
        assertThat(plain.findings.first()).isEqualTo(LINE)
        val withEx = withExamples.findings
        assertThat(withEx).contains(LINE)
        assertThat(withEx.indexOf(LINE)).isLessThan(withEx.indexOfFirst { it.contains("matching example") })
    }

    @Test
    @DisplayName("explico.json quality.noControlId has the same labels in the same order")
    fun json() {
        assertThat(jsonLabels(plain)).containsExactly("nc.allow_other", "nc.deny", "nc.entry_point", "nc2.deny")
        assertThat(jsonLabels(none)).isEmpty()
    }

    @Test
    @DisplayName("a rule given an id by --control-id-from is a control: no bullet, no line, an empty list")
    fun derivedIdsAreControls() {
        assertThat(derived.files.getValue("index.md")).doesNotContain("No control id")
        assertThat(derived.findings.none { "no control id" in it }).isTrue()
        assertThat(jsonLabels(derived)).isEmpty()
    }

    @Test
    @DisplayName("a policy set of controls only has no bullet and no line")
    fun nothingToReport() {
        assertThat(none.files.getValue("index.md")).doesNotContain("No control id")
        assertThat(none.findings.none { "no control id" in it }).isTrue()
    }

    @Test
    @DisplayName("it is advisory: gaps do not mention it")
    fun notAGap() {
        assertThat(plain.gaps).isEmpty()
    }

    @Test
    @DisplayName("two renders are byte-identical")
    fun deterministic() {
        assertThat(plainAgain.files.toList()).isEqualTo(plain.files.toList())
        assertThat(plainAgain.findings).isEqualTo(plain.findings)
    }

    @Test
    @DisplayName("the five-package sample (docs/sample-output) renders byte-for-byte as committed, with no such finding")
    fun sampleOutputUntouched() {
        val dir = Path.of("samples/policies")
        val docs = Explico.render(Explico.load(dir), dir, Explico.loadExamples(Path.of("samples/examples")), Path.of("samples/data/release/data.json"))
        assertThat(docs.findings.none { "no control id" in it }).isTrue()
        for ((name, text) in docs.files) {
            assertThat(text).describedAs("docs/sample-output/$name").isEqualTo(Files.readString(Path.of("docs/sample-output").resolve(name)))
        }
    }
}
