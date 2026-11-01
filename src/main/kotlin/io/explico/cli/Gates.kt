/**
 * The CI gates (spec §14.47): decisions and messages only, so they are testable without a process. A gate never suppresses output --
 * the caller writes the files and prints the normal stdout first -- and a failed gate is reported on stderr with exit code 5.
 */
package io.explico.cli

import io.explico.diff.ChangeSummary
import io.explico.diff.DiffCategory
import io.explico.diff.DiffEntry
import io.explico.render.CoverageSummary

internal object Gates {

    /** The exit code for a failed gate (documented next to 0-4). */
    const val EXIT_GATE_FAILED = 5

    private val DIFF_CATEGORIES = listOf(DiffCategory.ADDED, DiffCategory.REMOVED, DiffCategory.LOGIC_CHANGED, DiffCategory.DOCS_CHANGED)

    /** `--fail-under <percent>`: compared exactly (22 of 23 is 95.65%, so it passes 95 and fails 96), not on the floored percent shown; no conditions counts as 100%. */
    fun failUnder(coverage: CoverageSummary, percent: Int): String? =
        if (coverage.total > 0 && coverage.rendered * 100 < percent * coverage.total) {
            "explico: gate failed (--fail-under $percent): rendering coverage is ${coverage.percent}% (${coverage.rendered} of ${coverage.total} conditions)"
        } else {
            null
        }

    /** `--fail-on-gaps`: a count header and one indented line per gap (the library words them: `RenderedDocs.gaps`). */
    fun failOnGaps(gaps: List<String>): String? =
        if (gaps.isEmpty()) null else "explico: gate failed (--fail-on-gaps): ${gaps.size} gap(s)" + gaps.joinToString("") { "\n  - $it" }

    /** The name of the `--fail-on` category that gates on a changed example outcome (spec §14.74, renamed from `--fail-on-example-changes` in §14.80 step 5). It is not a [DiffCategory]. */
    const val EXAMPLE_CHANGES = "EXAMPLE_CHANGES"

    /** What `--fail-on` asked for: the [DiffCategory]s a control may be in, and whether a changed example outcome fails too. */
    data class FailOn(val categories: List<DiffCategory>, val exampleChanges: Boolean)

    /**
     * Parses `--fail-on`: comma-separated, case-insensitive, duplicates dropped keeping the order given. The names are the four [DiffCategory]s that are changes, and [EXAMPLE_CHANGES].
     * UNCHANGED, an unknown name or an empty list is rejected.
     */
    fun parseFailOn(text: String): FailOn {
        val names = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val valid = (DIFF_CATEGORIES.map { it.name } + EXAMPLE_CHANGES).joinToString(", ")
        require(names.isNotEmpty()) { "expected one or more of: $valid" }
        val categories = mutableListOf<DiffCategory>()
        var exampleChanges = false
        for (name in names) {
            val category = DIFF_CATEGORIES.firstOrNull { it.name.equals(name, ignoreCase = true) }
            when {
                category != null -> if (category !in categories) categories.add(category)
                name.equals(EXAMPLE_CHANGES, ignoreCase = true) -> exampleChanges = true
                else -> throw IllegalArgumentException("'$name' is not a category to fail on; expected one or more of: $valid")
            }
        }
        return FailOn(categories, exampleChanges)
    }

    /** `--fail-on`: the matching controls (control id, else `package.rule`) in the diff report's own order, with the categories echoed as given. */
    fun failOn(entries: List<DiffEntry>, categories: List<DiffCategory>): String? {
        val matching = entries.filter { it.category in categories }
            .sortedWith(compareBy({ DIFF_REPORT_ORDER.indexOf(it.category) }, { it.controlId == null }, { it.controlId }))
        if (matching.isEmpty()) return null
        // A control id shared by several rules (diff --group-by-control) is one failing RULE each: the id alone would repeat, so it names the rule too.
        val qualified = ChangeSummary.qualifiedIds(entries)
        val labels = matching.map { entry ->
            val (packagePath, rule) = ChangeSummary.displayedTarget(entry)
            when {
                entry.controlId == null -> "$packagePath.${rule.name}"
                entry.controlId in qualified -> "${entry.controlId} ($packagePath.${rule.name})"
                else -> entry.controlId
            }
        }
        return "explico: gate failed (--fail-on ${categories.joinToString(",") { it.name }}): ${labels.size} control(s): ${labels.joinToString(", ")}"
    }

    /** `--fail-on EXAMPLE_CHANGES` (spec §14.74): how many fixtures changed outcome, in how many controls (named in the report's own order). */
    fun failOnExampleChanges(changes: List<io.explico.diff.ExampleChange>, comparison: io.explico.diff.ExampleComparison? = null): String? {
        // A gate that could not look at anything must not pass: controls were present on both sides but none had examples that could be compared.
        if (comparison != null && comparison.total > 0 && comparison.compared == 0) {
            return "explico: gate failed (--fail-on EXAMPLE_CHANGES): no control could be compared (${comparison.total} present in both versions: ${comparison.functions} function(s), ${comparison.unattributable} with no examples attributable to the rule)"
        }
        if (changes.isEmpty()) return null
        val controls = changes.map { it.control }.distinct()
        return "explico: gate failed (--fail-on EXAMPLE_CHANGES): ${changes.size} example(s) changed outcome in ${controls.size} control(s): ${controls.joinToString(", ")}"
    }

    private val DIFF_REPORT_ORDER = listOf(DiffCategory.REMOVED, DiffCategory.ADDED, DiffCategory.LOGIC_CHANGED, DiffCategory.DOCS_CHANGED)
}
