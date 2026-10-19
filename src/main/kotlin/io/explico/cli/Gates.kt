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

    /** Parses `--fail-on`: comma-separated, case-insensitive, duplicates dropped keeping the order given. UNCHANGED, an unknown name or an empty list is rejected. */
    fun parseFailOn(text: String): List<DiffCategory> {
        val names = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val valid = DIFF_CATEGORIES.joinToString(", ")
        require(names.isNotEmpty()) { "expected one or more of: $valid" }
        return names.map { name ->
            DIFF_CATEGORIES.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: throw IllegalArgumentException("'$name' is not a category to fail on; expected one or more of: $valid")
        }.distinct()
    }

    /** `--fail-on`: the matching controls (control id, else `package.rule`) in the diff report's own order, with the categories echoed as given. */
    fun failOn(entries: List<DiffEntry>, categories: List<DiffCategory>): String? {
        val matching = entries.filter { it.category in categories }
            .sortedWith(compareBy({ DIFF_REPORT_ORDER.indexOf(it.category) }, { it.controlId == null }, { it.controlId }))
        if (matching.isEmpty()) return null
        val labels = matching.map { entry ->
            entry.controlId ?: ChangeSummary.displayedTarget(entry).let { (packagePath, rule) -> "$packagePath.${rule.name}" }
        }
        return "explico: gate failed (--fail-on ${categories.joinToString(",") { it.name }}): ${labels.size} control(s): ${labels.joinToString(", ")}"
    }

    private val DIFF_REPORT_ORDER = listOf(DiffCategory.REMOVED, DiffCategory.ADDED, DiffCategory.LOGIC_CHANGED, DiffCategory.DOCS_CHANGED)
}
