/**
 * Assembles control cards and pages from the domain model per spec §6.1/§6.2 --
 * ExpressionRenderer/PathHumanizer/Coverage do the leaf work, this assembles them.
 */
package io.explico.render

import io.explico.model.Condition
import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RuleBody
import io.explico.model.RuleGroup

internal object MarkdownRenderer {

    /** One `<package-path>.md` document: header, source files, then every rule's card (spec §6.1). */
    fun renderPackage(pkg: PolicyPackage, policySet: PolicySet, examplesByRule: Map<String, List<WorkedExample>> = emptyMap()): String {
        val sb = StringBuilder()
        sb.appendLine("# Package `${pkg.path}`")
        sb.appendLine()
        sb.appendLine("*Source files: ${pkg.sourceFiles.joinToString(", ") { "`$it`" }}*")
        sb.appendLine()

        for (rule in pkg.rules) {
            sb.append(renderCard(rule, pkg, policySet, examplesByRule[rule.name] ?: emptyList()))
            sb.appendLine()
            sb.appendLine("---")
            sb.appendLine()
        }
        val coverage = Coverage.of(pkg)
        sb.appendLine("*Package rendering coverage: ${coverage.rendered} of ${coverage.total} conditions (${coverage.percent}%)*")
        return sb.toString()
    }

    /** One control card for a [RuleGroup] (spec §6.2), with an optional worked-examples section (spec §6.7). */
    fun renderCard(rule: RuleGroup, pkg: PolicyPackage, policySet: PolicySet, workedExamples: List<WorkedExample> = emptyList()): String {
        val sb = StringBuilder()
        appendHeading(sb, rule, pkg)
        appendMetadataLines(sb, rule, pkg)

        val anchorFor: (Condition.RuleReference) -> String = { ref -> resolveAnchor(pkg.path, ref, policySet) }
        if (rule.bodies.size > 1) {
            appendMultiBodySituations(sb, rule, anchorFor)
        } else {
            appendSingleBody(sb, rule.bodies.single(), anchorFor)
        }

        appendWorkedExamples(sb, rule, workedExamples)
        appendCoverageFooter(sb, rule)
        return sb.toString()
    }

    private fun appendHeading(sb: StringBuilder, rule: RuleGroup, pkg: PolicyPackage) {
        val controlId = rule.metadata?.controlId
        sb.appendLine(if (controlId != null) "## $controlId — ${rule.metadata.title ?: rule.name}" else "## ${pkg.path}.${rule.name}")
        sb.appendLine()
    }

    private fun appendMetadataLines(sb: StringBuilder, rule: RuleGroup, pkg: PolicyPackage) {
        val metadata = rule.metadata
        val sourceFile = rule.bodies.first().sourceLocation.file.substringAfterLast('/')
        sb.appendLine("*Rule `${rule.name}` in package `${pkg.path}` — defined in `$sourceFile`*")
        if (!metadata?.frameworks.isNullOrEmpty()) {
            sb.appendLine("*Frameworks: ${metadata.frameworks.joinToString(", ")}*")
        }
        sb.appendLine()

        // trimEnd(): a YAML "description: |" block literal preserves its own trailing newline,
        // which would otherwise double up with appendLine()'s own line break into an extra blank line.
        sb.appendLine(metadata?.description?.trimEnd() ?: "*No description provided in policy metadata.*")
        sb.appendLine()

        if (rule.default != null) {
            sb.appendLine("**Default outcome:** ${rule.default.rendered}")
            sb.appendLine()
        }
    }

    private fun appendMultiBodySituations(sb: StringBuilder, rule: RuleGroup, anchorFor: (Condition.RuleReference) -> String) {
        sb.appendLine("**The rule matches when ANY of the following situations applies:**")
        sb.appendLine()
        rule.bodies.forEachIndexed { index, body ->
            sb.appendLine("### Situation ${index + 1} — all of the following are true")
            sb.appendLine()
            appendConditions(sb, body.conditions, anchorFor)
            sb.appendLine()
            if (body.producesValue != null) {
                sb.appendLine("*Produces:* \"${body.producesValue}\"")
                sb.appendLine()
            }
        }
    }

    private fun appendSingleBody(sb: StringBuilder, body: RuleBody, anchorFor: (Condition.RuleReference) -> String) {
        sb.appendLine("**All of the following are true:**")
        sb.appendLine()
        appendConditions(sb, body.conditions, anchorFor)
        sb.appendLine()
        if (body.producesValue != null) {
            sb.appendLine("*Produces:* \"${body.producesValue}\"")
            sb.appendLine()
        }
    }

    /** Spec §6.7: up to 3 matching + 2 non-matching fixtures, each with its produced message(s) and referenced-path values. */
    private fun appendWorkedExamples(sb: StringBuilder, rule: RuleGroup, outcomes: List<WorkedExample>) {
        val selected = WorkedExamples.select(outcomes)
        if (selected.isEmpty()) return

        sb.appendLine("**Worked examples**")
        sb.appendLine()
        val referencedPaths = WorkedExamples.referencedPaths(rule)
        for (outcome in selected) {
            val outcomeWord = WorkedExamples.outcomeWord(rule.name, outcome.matched)
            // A single message's situation label goes on the summary line, matching spec's own
            // example. Multiple messages (a fixture matching more than one body at once) each get
            // their own label instead -- there's no single "the" situation to put on the summary
            // line in that case. Not spec-pinned; no pack fixture exercises single-message
            // multi-body attribution differently than this.
            val summaryLabel = if (outcome.messages.size == 1) outcome.situationLabels.single()?.let { " *(Situation $it)*" } ?: "" else ""
            sb.appendLine("- **${outcome.fixture.name}** — $outcomeWord$summaryLabel")
            if (outcome.messages.size == 1) {
                sb.appendLine("  *\"${outcome.messages.single()}\"*")
            } else if (outcome.messages.size > 1) {
                outcome.messages.zip(outcome.situationLabels).forEach { (message, label) ->
                    val label2 = label?.let { " *(Situation $it)*" } ?: ""
                    sb.appendLine("  *\"$message\"*$label2")
                }
            }
            for (path in referencedPaths) {
                val breadcrumb = PathHumanizer.humanize(path.segments).rendered
                val value = WorkedExamples.resolvePathValue(path, outcome.fixture.input)
                sb.appendLine("  - $breadcrumb: ${value?.let { "`$it`" } ?: "absent"}")
            }
        }
        sb.appendLine()
    }

    private fun appendCoverageFooter(sb: StringBuilder, rule: RuleGroup) {
        val coverage = Coverage.of(rule)
        val unrenderedOperands = Coverage.unrenderedOperandCount(rule)
        val footer = StringBuilder("*Rendering coverage: ${coverage.rendered} of ${coverage.total} conditions")
        if (unrenderedOperands > 0) {
            footer.append("; contains $unrenderedOperands unrendered ${if (unrenderedOperands == 1) "value" else "values"}")
        }
        footer.append("*")
        sb.appendLine(footer.toString())
    }

    private fun appendConditions(sb: StringBuilder, conditions: List<Condition>, anchorFor: (Condition.RuleReference) -> String) {
        for (condition in conditions) {
            if (condition is Condition.Unrendered) {
                sb.appendLine("- ⚠ **not rendered — shown as source:**")
                sb.appendLine()
                sb.appendLine("  ```rego")
                condition.sourceText.lines().forEach { line -> sb.appendLine("  $line") }
                sb.appendLine("  ```")
            } else {
                sb.appendLine("- ${ExpressionRenderer.render(condition, anchorFor)}")
            }
        }
    }

    /**
     * `index.md`: a table of every control, sorted by control id (missing ids sort last by
     * package+name), plus the overall coverage line (spec §6.1). When [examplesByPackage] is
     * non-empty, gains an example-coverage column and a closing corpus-gaps line (spec §6.7).
     */
    fun renderIndex(policySet: PolicySet, examplesByPackage: Map<String, Map<String, List<WorkedExample>>> = emptyMap()): String {
        data class Row(
            val controlId: String?, val title: String, val packagePath: String, val ruleName: String,
            val coverage: CoverageSummary, val sourceFile: String, val hasMatching: Boolean, val hasNotMatching: Boolean,
        )

        val hasExamples = examplesByPackage.isNotEmpty()
        val rows = policySet.packages.flatMap { pkg ->
            pkg.rules.map { rule ->
                val outcomes = examplesByPackage[pkg.path]?.get(rule.name) ?: emptyList()
                Row(
                    controlId = rule.metadata?.controlId,
                    title = rule.metadata?.title ?: rule.name,
                    packagePath = pkg.path,
                    ruleName = rule.name,
                    coverage = Coverage.of(rule),
                    sourceFile = rule.bodies.first().sourceLocation.file,
                    hasMatching = outcomes.any { it.matched },
                    hasNotMatching = outcomes.any { !it.matched },
                )
            }
        }.sortedWith(compareBy({ it.controlId == null }, { it.controlId }, { it.packagePath }, { it.ruleName }))

        val sb = StringBuilder()
        sb.appendLine("# Control index")
        sb.appendLine()
        if (hasExamples) {
            sb.appendLine("| Control ID | Title | Package | Rule | Coverage | Example coverage | Source file |")
            sb.appendLine("|---|---|---|---|---|---|---|")
        } else {
            sb.appendLine("| Control ID | Title | Package | Rule | Coverage | Source file |")
            sb.appendLine("|---|---|---|---|---|---|")
        }
        rows.forEach { row ->
            val prefix = "| ${row.controlId ?: "—"} | ${row.title} | `${row.packagePath}` | `${row.ruleName}` | ${row.coverage.percent}%"
            if (hasExamples) {
                val exampleColumn = "${if (row.hasMatching) "✓" else "–"} / ${if (row.hasNotMatching) "✓" else "–"}"
                sb.appendLine("$prefix | $exampleColumn | `${row.sourceFile}` |")
            } else {
                sb.appendLine("$prefix | `${row.sourceFile}` |")
            }
        }
        sb.appendLine()

        val overall = CoverageSummary(rows.sumOf { it.coverage.rendered }, rows.sumOf { it.coverage.total })
        sb.appendLine("*Overall rendering coverage: ${overall.rendered} of ${overall.total} conditions (${overall.percent}%)*")

        if (hasExamples) {
            val gapCount = rows.count { !it.hasMatching }
            if (gapCount > 0) {
                sb.appendLine()
                sb.appendLine("*$gapCount controls have no fixture demonstrating them — the corpus has gaps.*")
            }
        }
        return sb.toString()
    }

    /** RuleReference anchors (spec §6.5): control-id or package-rulename, same-file bare `#anchor` vs cross-file `<package>.md#anchor`. */
    private fun resolveAnchor(currentPackagePath: String, ref: Condition.RuleReference, policySet: PolicySet): String {
        val targetPackage = policySet.packages.first { it.path == ref.packagePath }
        val targetRule = targetPackage.rules.first { it.name == ref.ruleName }
        val anchorId = slug(targetRule.metadata?.controlId ?: "${ref.packagePath}-${ref.ruleName}")
        return if (ref.packagePath == currentPackagePath) "#$anchorId" else "${ref.packagePath.replace('.', '-')}.md#$anchorId"
    }

    private fun slug(text: String): String = text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
}
