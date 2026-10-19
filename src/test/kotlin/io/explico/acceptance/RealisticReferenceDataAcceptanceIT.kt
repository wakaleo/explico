/**
 * Acceptance tests for the "Reference data" block: a card that reads a plain `data.*` document shows what that document holds in
 * the supplied data, so a reader sees the exemption list, not just its name. Authored from a compliance-owner review of the real
 * output, not from the frozen README contract (the frozen Tier-1 assertions for these documents keep holding untouched).
 *
 * What these pin (facade only):
 *  - ON only when `render` is given a `dataDir`; without one NO document mentions reference data.
 *  - One bullet per distinct data path, naming only the plain prefix (up to the first iterated or dynamic segment), valued by
 *    OPA (`opa eval` of `data.<path>`); an undefined path says so; a long array is cut after 5 elements with an item count.
 *  - A `data.*` path that is really a loaded policy rule is not reference data and is skipped.
 *  - A one-line provenance footer per block; nothing else on the page changes; deterministic.
 * Fixtures: the frozen acceptance pack (+ its data file) and `src/test/resources/realistic/reference-data/`.
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

@DisplayName("Realistic policies: reference data values")
class RealisticReferenceDataAcceptanceIT {

    companion object {
        private const val BLOCK = "**Reference data**"
        private const val FOOTER = "*Values are read from the supplied data by OPA at generation time.*"

        private val packPolicies = Path.of("src/test/resources/acceptance/policies")
        private val packExamples = Path.of("src/test/resources/acceptance/examples")
        private val packData = Path.of("src/test/resources/acceptance/data/release/data.json")
        private val fixture = Path.of("src/test/resources/realistic/reference-data")

        private lateinit var pack: Map<String, String>
        private lateinit var packWithoutData: Map<String, String>
        private lateinit var fixtureDocs: Map<String, String>
        private lateinit var fixtureDocsAgain: Map<String, String>
        private lateinit var fixtureWithoutData: Map<String, String>

        private fun render(policies: Path, examples: Boolean, data: Path?): Map<String, String> {
            val set = Explico.load(policies)
            val exampleSet = if (examples) Explico.loadExamples(packExamples) else null
            return Explico.render(set, policies, exampleSet, data).files
        }

        @JvmStatic
        @BeforeAll
        fun renderDocuments() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            pack = render(packPolicies, examples = true, data = packData)
            packWithoutData = render(packPolicies, examples = true, data = null)
            fixtureDocs = render(fixture.resolve("policies"), examples = false, data = fixture.resolve("data"))
            fixtureDocsAgain = render(fixture.resolve("policies"), examples = false, data = fixture.resolve("data"))
            fixtureWithoutData = render(fixture.resolve("policies"), examples = false, data = null)
        }

        private fun count(text: String, needle: String) = text.windowed(needle.length).count { it == needle }

        /** The card whose anchor is [id]: from its `<a id>` to the next card's anchor (or the end of the document). */
        private fun card(markdown: String, id: String): String =
            markdown.substringAfter("<a id=\"$id\"></a>").substringBefore("<a id=\"")

        private fun block(card: String): String = card.substringAfter(BLOCK, "").substringBefore(FOOTER)

        private fun bullets(card: String): List<String> = block(card).lines().filter { it.startsWith("- ") }
    }

    @Nested
    @DisplayName("Helper with no metadata (release-exemptions.md)")
    inner class Exemptions {

        private val doc get() = pack.getValue("release-exemptions.md")

        @Test
        @DisplayName("shows the exempt-service list under a Reference data heading, exactly once")
        fun showsTheList() {
            val bullet = "- `data ▸ release ▸ exempt services`: `[\"legacy-batch\"]`"
            assertThat(doc).contains(BLOCK)
            assertThat(count(doc, bullet)).isEqualTo(1)
        }

        @Test
        @DisplayName("the block comes after the conditions and before the worked examples")
        fun placement() {
            assertThat(doc.indexOf(BLOCK)).isGreaterThan(doc.indexOf("`deployment ▸ service` is one of"))
            assertThat(doc.indexOf(BLOCK)).isLessThan(doc.indexOf("**Worked examples**"))
        }

        @Test
        @DisplayName("one provenance footer follows the block")
        fun footer() {
            assertThat(count(doc, FOOTER)).isEqualTo(1)
            assertThat(doc.indexOf(FOOTER)).isGreaterThan(doc.indexOf(BLOCK)).isLessThan(doc.indexOf("**Worked examples**"))
        }
    }

    @Nested
    @DisplayName("REL-004 — Release governance evidence (release-governance.md)")
    inner class Governance {

        private val card get() = card(pack.getValue("release-governance.md"), "rel-004")

        @Test
        @DisplayName("one bullet for the freeze-windows document, valued with the window object")
        fun oneBulletWithTheValue() {
            val named = bullets(card).filter { it.startsWith("- `data ▸ release ▸ freeze windows`: `") }
            assertThat(named).hasSize(1)
            assertThat(named.single())
                .contains("\"name\":\"year-end change freeze\"", "\"start\":1766966400", "\"finish\":1767657600")
        }

        @Test
        @DisplayName("the iterated sub-paths are not separate bullets (no `[each window]`)")
        fun noIteratedBullets() {
            assertThat(bullets(card)).hasSize(1)
            assertThat(block(card)).doesNotContain("[each window]")
        }

        @Test
        @DisplayName("the block sits before the worked examples, with one footer")
        fun placementAndFooter() {
            assertThat(count(card, FOOTER)).isEqualTo(1)
            assertThat(card.indexOf(BLOCK)).isLessThan(card.indexOf("**Worked examples**"))
        }

        @Test
        @DisplayName("the all_checks_passed helper card reads no data, so it gets no block")
        fun helperCardHasNoBlock() {
            assertThat(card(pack.getValue("release-governance.md"), "release-governance-all-checks-passed")).doesNotContain(BLOCK)
        }
    }

    @Nested
    @DisplayName("Documents that read no plain data")
    inner class NoReferenceData {

        @Test
        @DisplayName("REL-002 imports data.release.exemptions, a loaded rule package: no Reference data block")
        fun rulePackageIsSkipped() {
            assertThat(pack.getValue("release-evidence.md")).doesNotContain(BLOCK)
        }

        @Test
        @DisplayName("REL-001 and REL-003 read only input: no Reference data block")
        fun inputOnlyDocuments() {
            assertThat(pack.getValue("release-approvals.md")).doesNotContain(BLOCK)
            assertThat(pack.getValue("release-provenance.md")).doesNotContain(BLOCK)
            assertThat(pack.getValue("index.md")).doesNotContain(BLOCK)
        }
    }

    @Nested
    @DisplayName("Without a data directory")
    inner class WithoutData {

        @Test
        @DisplayName("no document mentions reference data (pack and fixture)")
        fun offWithoutDataDir() {
            for ((name, doc) in packWithoutData + fixtureWithoutData.mapKeys { "fixture/${it.key}" }) {
                assertThat(doc).describedAs(name).doesNotContain("Reference data").doesNotContain(FOOTER)
            }
        }
    }

    @Nested
    @DisplayName("Plain-prefix, long, undefined and rule-backed data (fixture)")
    inner class Fixture {

        private val doc get() = fixtureDocs.getValue("refdata-limits.md")

        @Test
        @DisplayName("RD-001: an array longer than 5 shows the first 5, an ellipsis and (7 items)")
        fun longArrayIsCut() {
            val bullet = bullets(card(doc, "rd-001")).single()
            assertThat(bullet).startsWith("- `data ▸ limits ▸ regions`: `")
            assertThat(bullet).contains("[\"eu-west-1\",\"eu-west-2\",\"eu-central-1\",\"us-east-1\",\"us-east-2\"", "…", "(7 items)")
            assertThat(bullet).doesNotContain("ap-south-1").doesNotContain("ap-northeast-1")
        }

        @Test
        @DisplayName("RD-002: a path the supplied data does not define says so")
        fun undefinedPath() {
            assertThat(bullets(card(doc, "rd-002")))
                .containsExactly("- `data ▸ limits ▸ missing`: not defined in the supplied data")
        }

        @Test
        @DisplayName("RD-003: only the plain prefix `settings ▸ owners` is shown, once, with OPA's value")
        fun plainPrefixOnly() {
            val bullets = bullets(card(doc, "rd-003"))
            assertThat(bullets).containsExactly("- `data ▸ limits ▸ settings ▸ owners`: `[{\"name\":\"ann\"},{\"name\":\"bob\"}]`")
            assertThat(block(card(doc, "rd-003"))).doesNotContain("[each owner]")
        }

        @Test
        @DisplayName("RD-004: a data path that is another package's partial rule is not reference data")
        fun ruleBackedPathIsSkipped() {
            val rd004 = card(doc, "rd-004")
            assertThat(rd004).contains("data ▸ refdata ▸ helpers ▸ admins").doesNotContain(BLOCK)
        }

        @Test
        @DisplayName("each card with a block has exactly one footer: three blocks, three footers")
        fun footerPerBlock() {
            assertThat(count(doc, BLOCK)).isEqualTo(3)
            assertThat(count(doc, FOOTER)).isEqualTo(3)
        }

        @Test
        @DisplayName("rendering twice gives byte-identical documents")
        fun deterministic() {
            assertThat(fixtureDocsAgain).isEqualTo(fixtureDocs)
        }
    }
}
