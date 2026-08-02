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
    fun renderPackage(pkg: PolicyPackage, policySet: PolicySet): String {
        val sb = StringBuilder()
        sb.appendLine("# Package `${pkg.path}`")
        sb.appendLine()
        sb.appendLine("*Source files: ${pkg.sourceFiles.joinToString(", ") { "`$it`" }}*")
        sb.appendLine()

        for (rule in pkg.rules) {
            sb.append(renderCard(rule, pkg, policySet))
            sb.appendLine()
            sb.appendLine("---")
            sb.appendLine()
        }
        val coverage = Coverage.of(pkg)
        sb.appendLine("*Package rendering coverage: ${coverage.rendered} of ${coverage.total} conditions (${coverage.percent}%)*")
        return sb.toString()
    }

    /** One control card for a [RuleGroup] (spec §6.2). */
    fun renderCard(rule: RuleGroup, pkg: PolicyPackage, policySet: PolicySet): String {
        val sb = StringBuilder()
        appendHeading(sb, rule, pkg)
        appendMetadataLines(sb, rule, pkg)

        val anchorFor: (Condition.RuleReference) -> String = { ref -> resolveAnchor(pkg.path, ref, policySet) }
        if (rule.bodies.size > 1) {
            appendMultiBodySituations(sb, rule, anchorFor)
        } else {
            appendSingleBody(sb, rule.bodies.single(), anchorFor)
        }

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

        sb.appendLine(metadata?.description ?: "*No description provided in policy metadata.*")
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

    /** `index.md`: a table of every control, sorted by control id (missing ids sort last by package+name), plus the overall coverage line (spec §6.1). */
    fun renderIndex(policySet: PolicySet): String {
        data class Row(val controlId: String?, val title: String, val packagePath: String, val ruleName: String, val coverage: CoverageSummary, val sourceFile: String)

        val rows = policySet.packages.flatMap { pkg ->
            pkg.rules.map { rule ->
                Row(
                    controlId = rule.metadata?.controlId,
                    title = rule.metadata?.title ?: rule.name,
                    packagePath = pkg.path,
                    ruleName = rule.name,
                    coverage = Coverage.of(rule),
                    sourceFile = rule.bodies.first().sourceLocation.file,
                )
            }
        }.sortedWith(compareBy({ it.controlId == null }, { it.controlId }, { it.packagePath }, { it.ruleName }))

        val sb = StringBuilder()
        sb.appendLine("# Control index")
        sb.appendLine()
        sb.appendLine("| Control ID | Title | Package | Rule | Coverage | Source file |")
        sb.appendLine("|---|---|---|---|---|---|")
        rows.forEach { row ->
            sb.appendLine("| ${row.controlId ?: "—"} | ${row.title} | `${row.packagePath}` | `${row.ruleName}` | ${row.coverage.percent}% | `${row.sourceFile}` |")
        }
        sb.appendLine()

        val overall = CoverageSummary(rows.sumOf { it.coverage.rendered }, rows.sumOf { it.coverage.total })
        sb.appendLine("*Overall rendering coverage: ${overall.rendered} of ${overall.total} conditions (${overall.percent}%)*")
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
