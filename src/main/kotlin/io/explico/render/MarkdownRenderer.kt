/**
 * Assembles control cards and pages from the domain model per spec §6.1/§6.2 --
 * ExpressionRenderer/PathHumanizer/Coverage do the leaf work, this assembles them.
 */
package io.explico.render

import io.explico.model.Condition
import io.explico.model.Operand
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

        // Package-scope METADATA (review item 4), shown before the first card -- title bold (no
        // separate heading level: the package already has its own "# Package" heading above), the
        // description verbatim (trimEnd(): same YAML block-literal trailing-newline fix as a rule's
        // own description, spec §6.2).
        pkg.metadata?.title?.let { title ->
            sb.appendLine("**$title**")
            sb.appendLine()
        }
        pkg.metadata?.description?.let { description ->
            sb.appendLine(description.trimEnd())
            sb.appendLine()
        }

        val anchorIds = cardAnchorIds(pkg)
        for ((index, rule) in pkg.rules.withIndex()) {
            sb.append(renderCard(rule, pkg, policySet, examplesByRule[rule.name] ?: emptyList(), anchorIds[index]))
            sb.appendLine()
            sb.appendLine("---")
            sb.appendLine()
        }
        val coverage = Coverage.of(pkg)
        sb.appendLine("*Package rendering coverage: ${coverage.rendered} of ${coverage.total} conditions (${coverage.percent}%)*")
        return sb.toString()
    }

    /** One control card for a [RuleGroup] (spec §6.2), with an optional worked-examples section (spec §6.7). */
    fun renderCard(
        rule: RuleGroup,
        pkg: PolicyPackage,
        policySet: PolicySet,
        workedExamples: List<WorkedExample> = emptyList(),
        anchorId: String? = null,
    ): String {
        val sb = StringBuilder()
        // Review item 18: an explicit anchor, because links target the control-id / package-rulename
        // slug (spec §6.5) while a heading's own GitHub slug is something else entirely. A blank line
        // follows it: an HTML block would otherwise swallow the `##` line and stop it being a heading.
        if (anchorId != null) {
            sb.appendLine("<a id=\"$anchorId\"></a>")
            sb.appendLine()
        }
        appendHeading(sb, rule, pkg)
        appendMetadataLines(sb, rule, pkg)

        val anchorFor: (Condition.RuleReference) -> String = { ref -> resolveAnchor(pkg.path, ref, policySet) }
        val functionAnchorFor: (Condition.FunctionCall) -> String = { call -> resolveFunctionAnchor(pkg.path, call, policySet) }
        val ruleValueAnchorFor: (Operand.RuleValueReference) -> String = { ref -> resolveRuleValueAnchor(pkg.path, ref, policySet) }
        when {
            // A complete rule's unconditional constant value (review item 10, finding 4) has no
            // real body at all -- just its own value line.
            rule.constantValue != null -> appendConstantValueLine(sb, rule.constantValue)
            // A rule that's only ever a `default` declaration (review item 3) has no body at all --
            // nothing to say beyond the heading/metadata/default lines already appended above.
            rule.bodies.isEmpty() -> Unit
            rule.bodies.size > 1 -> appendMultiBodySituations(sb, rule, anchorFor, functionAnchorFor, ruleValueAnchorFor)
            else -> appendSingleBody(sb, rule.bodies.single(), anchorFor, functionAnchorFor, ruleValueAnchorFor)
        }

        appendWorkedExamples(sb, rule, workedExamples)
        appendCoverageFooter(sb, rule)
        return sb.toString()
    }

    private fun appendHeading(sb: StringBuilder, rule: RuleGroup, pkg: PolicyPackage) {
        val controlId = rule.metadata?.controlId
        val heading = when {
            controlId != null -> "## $controlId — ${rule.metadata.title ?: rule.name}"
            // Review item 14's follow-up: a split-out default entry gets its own recognisable
            // heading rather than looking like an ordinary id-less card.
            rule.isSplitDefaultEntry -> "## Default for ${pkg.path}.${rule.name}"
            else -> "## ${pkg.path}.${rule.name}"
        }
        sb.appendLine(heading)
        sb.appendLine()
    }

    private fun appendMetadataLines(sb: StringBuilder, rule: RuleGroup, pkg: PolicyPackage) {
        val metadata = rule.metadata
        // "?" only for a default-only rule with zero bodies in this file (review item 3) -- should
        // never actually surface, since a RuleGroup is only ever constructed with at least one of
        // bodies/default non-empty, but this stays a graceful fallback rather than a crash either way.
        val sourceFile = (rule.bodies.firstOrNull()?.sourceLocation ?: rule.default?.sourceLocation ?: rule.constantValue?.sourceLocation)?.file?.substringAfterLast('/') ?: "?"
        sb.appendLine("*Rule `${rule.name}` in package `${pkg.path}` — defined in `$sourceFile`*")
        if (!metadata?.frameworks.isNullOrEmpty()) {
            sb.appendLine("*Frameworks: ${metadata.frameworks.joinToString(", ")}*")
        }
        // Review item 8: a user-defined function's own card declares its parameters, so a reader
        // landing here via a FunctionCall's link knows what the caller supplied them as.
        if (rule.parameters.isNotEmpty()) {
            sb.appendLine("*Parameters: ${rule.parameters.joinToString(", ") { "`$it`" }}*")
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

    private fun appendMultiBodySituations(
        sb: StringBuilder,
        rule: RuleGroup,
        anchorFor: (Condition.RuleReference) -> String,
        functionAnchorFor: (Condition.FunctionCall) -> String,
        ruleValueAnchorFor: (Operand.RuleValueReference) -> String,
    ) {
        sb.appendLine("**The rule matches when ANY of the following situations applies:**")
        sb.appendLine()
        rule.bodies.forEachIndexed { index, body ->
            sb.appendLine("### Situation ${index + 1} — all of the following are true")
            sb.appendLine()
            appendConditions(sb, body.conditions, anchorFor, functionAnchorFor, ruleValueAnchorFor)
            sb.appendLine()
            if (body.producesValue != null) {
                sb.appendLine("*Produces:* \"${body.producesValue}\"")
                sb.appendLine()
            }
        }
    }

    private fun appendSingleBody(
        sb: StringBuilder,
        body: RuleBody,
        anchorFor: (Condition.RuleReference) -> String,
        functionAnchorFor: (Condition.FunctionCall) -> String,
        ruleValueAnchorFor: (Operand.RuleValueReference) -> String,
    ) {
        sb.appendLine("**All of the following are true:**")
        sb.appendLine()
        appendConditions(sb, body.conditions, anchorFor, functionAnchorFor, ruleValueAnchorFor)
        sb.appendLine()
        if (body.producesValue != null) {
            sb.appendLine("*Produces:* \"${body.producesValue}\"")
            sb.appendLine()
        }
    }

    /** A complete rule's unconditional constant value (review item 10, finding 4) -- no conditions, no Situations, just the value it is. */
    private fun appendConstantValueLine(sb: StringBuilder, value: io.explico.model.ConstantValue) {
        sb.appendLine("**Value:** ${value.rendered}")
        sb.appendLine()
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
        // Provenance footer (spec §13.6): a muted, explicit restatement, directly in the rendered
        // artefact, of spec §6.7's own invariant that worked examples are never predicted -- always
        // freshly evaluated by opa against whatever policy version is being rendered right now.
        sb.appendLine("*Examples are evaluated against this policy version by OPA at generation time.*")
        sb.appendLine()
    }

    private fun appendCoverageFooter(sb: StringBuilder, rule: RuleGroup) {
        val coverage = Coverage.of(rule)
        val unrenderedOperands = Coverage.unrenderedOperandCount(rule)
        val footer = StringBuilder("*Rendering coverage: ${coverage.rendered} of ${coverage.total} conditions")
        val helpers = Coverage.helperAssignmentCount(rule)
        if (helpers > 0) {
            footer.append(" (including $helpers helper ${if (helpers == 1) "assignment" else "assignments"} shown as source)")
        }
        if (unrenderedOperands > 0) {
            footer.append("; contains $unrenderedOperands unrendered ${if (unrenderedOperands == 1) "value" else "values"}")
        }
        footer.append("*")
        sb.appendLine(footer.toString())
    }

    private const val HELPER_ASSIGNMENT_REASON = Coverage.HELPER_ASSIGNMENT_REASON
    private const val ELSE_CHAIN_REASON = "else-chain"

    private fun appendConditions(
        sb: StringBuilder,
        conditions: List<Condition>,
        anchorFor: (Condition.RuleReference) -> String,
        functionAnchorFor: (Condition.FunctionCall) -> String,
        ruleValueAnchorFor: (Operand.RuleValueReference) -> String,
    ) {
        var i = 0
        while (i < conditions.size) {
            val condition = conditions[i]
            if (condition is Condition.Unrendered && condition.reason == HELPER_ASSIGNMENT_REASON) {
                // Review item 12: consecutive helper-assignment fallbacks (and ONLY that reason --
                // a differently-reasoned Unrendered right next to one, e.g. a genuinely unresolved
                // call, is never absorbed) collapse into one labelled block. Presentation only:
                // each still counts as its own condition for coverage purposes (Coverage.of walks
                // the unchanged, un-merged `conditions` list), so merging never inflates the metric.
                val run = mutableListOf<Condition.Unrendered>()
                while (i < conditions.size) {
                    val next = conditions[i] as? Condition.Unrendered ?: break
                    if (next.reason != HELPER_ASSIGNMENT_REASON) break
                    run += next
                    i++
                }
                appendGroupedHelperAssignments(sb, run)
            } else if (condition is Condition.Unrendered && condition.reason == ELSE_CHAIN_REASON) {
                // Review item 13: a generic statement about how `else` works in Rego, NOT a claim
                // about this rule's own logic -- the branches themselves stay verbatim source.
                appendUnrenderedBlock(sb, "not rendered — else chain (branches are tried in order and the first that applies wins), shown as source", listOf(condition.sourceText))
                i++
            } else if (condition is Condition.Unrendered) {
                appendUnrenderedBlock(sb, "not rendered — shown as source", listOf(condition.sourceText))
                i++
            } else {
                sb.appendLine("- ${ExpressionRenderer.render(condition, anchorFor, functionAnchorFor, ruleValueAnchorFor)}")
                i++
            }
        }
    }

    private fun appendGroupedHelperAssignments(sb: StringBuilder, run: List<Condition.Unrendered>) {
        val label = if (run.size > 1) "helper definitions" else "helper definition"
        appendUnrenderedBlock(sb, "not rendered — grouped $label shown as source", run.map { it.sourceText })
    }

    private fun appendUnrenderedBlock(sb: StringBuilder, header: String, sourceTexts: List<String>) {
        sb.appendLine("- ⚠ **$header:**")
        sb.appendLine()
        sb.appendLine("  ```rego")
        sourceTexts.forEach { text -> text.lines().forEach { line -> sb.appendLine("  $line") } }
        sb.appendLine("  ```")
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
                    sourceFile = (rule.bodies.firstOrNull()?.sourceLocation ?: rule.default?.sourceLocation ?: rule.constantValue?.sourceLocation)?.file ?: "?",
                    hasMatching = outcomes.any { it.matched },
                    hasNotMatching = outcomes.any { !it.matched },
                )
            }
        }.sortedWith(compareBy({ it.controlId == null }, { it.controlId }, { it.packagePath }, { it.ruleName }))

        val sb = StringBuilder()
        sb.appendLine("# Control index")
        sb.appendLine()
        // Review item 17: controls (rows with a control id) first; rules without one -- helpers and
        // unannotated rules -- in their own table, so a reader scanning for controls isn't wading
        // through them. Same columns in both, including the example-coverage one.
        fun appendTable(tableRows: List<Row>) {
            if (hasExamples) {
                sb.appendLine("| Control ID | Title | Package | Rule | Coverage | Example coverage | Source file |")
                sb.appendLine("|---|---|---|---|---|---|---|")
            } else {
                sb.appendLine("| Control ID | Title | Package | Rule | Coverage | Source file |")
                sb.appendLine("|---|---|---|---|---|---|")
            }
            tableRows.forEach { row ->
                val prefix = "| ${row.controlId ?: "—"} | ${row.title} | `${row.packagePath}` | `${row.ruleName}` | ${row.coverage.percent}%"
                if (hasExamples) {
                    val exampleColumn = "${if (row.hasMatching) "✓" else "–"} / ${if (row.hasNotMatching) "✓" else "–"}"
                    sb.appendLine("$prefix | $exampleColumn | `${row.sourceFile}` |")
                } else {
                    sb.appendLine("$prefix | `${row.sourceFile}` |")
                }
            }
            sb.appendLine()
        }
        val (controls, withoutId) = rows.partition { it.controlId != null }
        appendTable(controls)
        if (withoutId.isNotEmpty()) {
            sb.appendLine("## Rules without a control id")
            sb.appendLine()
            appendTable(withoutId)
        }

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
    private fun resolveAnchor(currentPackagePath: String, ref: Condition.RuleReference, policySet: PolicySet): String =
        anchorForTarget(currentPackagePath, ref.packagePath, ref.ruleName, policySet)

    /** Same anchor convention as a [Condition.RuleReference] (review item 8): a called function is just another card, link-targeted the same way. */
    private fun resolveFunctionAnchor(currentPackagePath: String, call: Condition.FunctionCall, policySet: PolicySet): String =
        anchorForTarget(currentPackagePath, call.packagePath, call.functionName, policySet)

    /** Same anchor convention as [Condition.RuleReference]/[Condition.FunctionCall] (review item 10): a referenced rule's VALUE is just another card, link-targeted the same way. */
    private fun resolveRuleValueAnchor(currentPackagePath: String, reference: Operand.RuleValueReference, policySet: PolicySet): String =
        anchorForTarget(currentPackagePath, reference.packagePath, reference.ruleName, policySet)

    private fun anchorForTarget(currentPackagePath: String, targetPackagePath: String, targetName: String, policySet: PolicySet): String {
        val targetPackage = policySet.packages.first { it.path == targetPackagePath }
        val candidates = targetPackage.rules.filter { it.name == targetName }
        val targetRule = candidates.firstOrNull { !it.isSplitDefaultEntry } ?: candidates.first()
        val anchorId = anchorIdOf(targetRule, targetPackagePath)
        return if (targetPackagePath == currentPackagePath) "#$anchorId" else "${targetPackagePath.replace('.', '-')}.md#$anchorId"
    }

    /** The id [anchorForTarget] links to for [rule]: its control id if it has one, else `package-rulename`, slugged. */
    internal fun anchorIdOf(rule: RuleGroup, packagePath: String): String =
        slug(rule.metadata?.controlId ?: "$packagePath-${rule.name}")

    /**
     * One anchor id per card of [pkg] (aligned with `pkg.rules`), or null where the id was already
     * claimed -- ids must be unique per document. Non-default cards claim first, in source order,
     * so a split-default entry (which shares `package-rulename` with id-less siblings) can never
     * steal the id [anchorForTarget] resolves a link to.
     */
    internal fun cardAnchorIds(pkg: PolicyPackage): List<String?> {
        val claimed = mutableSetOf<String>()
        val result = arrayOfNulls<String>(pkg.rules.size)
        val order = pkg.rules.indices.sortedBy { pkg.rules[it].isSplitDefaultEntry }
        for (i in order) {
            val id = anchorIdOf(pkg.rules[i], pkg.path)
            if (claimed.add(id)) result[i] = id
        }
        return result.toList()
    }

    private fun slug(text: String): String = text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
}
