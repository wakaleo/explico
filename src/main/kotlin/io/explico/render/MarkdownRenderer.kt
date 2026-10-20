/**
 * Assembles control cards and pages from the domain model per spec §6.1/§6.2 --
 * ExpressionRenderer/PathHumanizer/Coverage do the leaf work, this assembles them.
 */
package io.explico.render

import io.explico.generate.GeneratedExample
import io.explico.generate.GeneratedExamples
import io.explico.model.Condition
import io.explico.model.Operand
import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RuleBody
import io.explico.model.RuleGroup
import kotlinx.serialization.json.JsonElement

internal object MarkdownRenderer {

    /** One `<package-path>.md` document: header, source files, then every rule's card (spec §6.1). */
    fun renderPackage(
        pkg: PolicyPackage,
        policySet: PolicySet,
        examplesByRule: Map<String, List<WorkedExample>> = emptyMap(),
        /** Generated worked examples (spec §14.39), by index into [pkg]'s rules; empty unless `--generate-examples`. */
        generatedByRuleIndex: Map<Int, GeneratedExamples> = emptyMap(),
        /** The full policy digest (spec §14.40), if the caller computed one; null prints no digest line at all. */
        policyDigest: String? = null,
        /** Spec §14.41's resolved `data.*` values for the whole render; null means no `dataDir` was supplied. */
        dataValues: Map<Operand.Path, JsonElement?>? = null,
        /** Links to the Rego source (spec §14.48); null leaves every location as plain text. */
        sourceLinks: SourceLinks? = null,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("# Package `${pkg.path}`")
        sb.appendLine()
        sb.appendLine("*Source files: ${pkg.sourceFiles.joinToString(", ") { file -> sourceLinks?.let { "[`$file`](${it.url(file, 1)})" } ?: "`$file`" }}*")
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
        pkg.metadata?.let { m ->
            if (m.authors.isNotEmpty() || m.relatedResources.isNotEmpty() || m.customFields.isNotEmpty()) {
                appendPassthroughLines(sb, m.authors, m.relatedResources, m.customFields)
                sb.appendLine()
            }
        }

        val anchorIds = cardAnchorIds(pkg)
        for ((index, rule) in pkg.rules.withIndex()) {
            sb.append(
                renderCard(
                    rule, pkg, policySet,
                    WorkedExamples.forGroup(rule, pkg, examplesByRule[rule.name] ?: emptyList()),
                    anchorIds[index],
                    generatedExamples = generatedByRuleIndex[index],
                    dataValues = dataValues,
                    sourceLinks = sourceLinks,
                ),
            )
            sb.appendLine()
            sb.appendLine("---")
            sb.appendLine()
        }
        val coverage = Coverage.of(pkg)
        sb.appendLine("*Package rendering coverage: ${coverage.rendered} of ${coverage.total} conditions (${coverage.percent}%)*")
        if (policyDigest != null) {
            sb.appendLine()
            sb.appendLine("*Policy digest: `sha256:${policyDigest.take(12)}…` (full value in index.md)*")
        }
        return sb.toString()
    }

    /**
     * One control card for a [RuleGroup] (spec §6.2), with an optional worked-examples section
     * (spec §6.7). [isLinkable] (follow-up to review item 18) says whether a given
     * (packagePath, ruleName) target actually has an anchor in the document being assembled --
     * always true for an ordinary whole-package render (every rule gets its own card there), but
     * NOT always true inside a diff report, where only a changed control gets a card at all.
     */
    fun renderCard(
        rule: RuleGroup,
        pkg: PolicyPackage,
        policySet: PolicySet,
        workedExamples: List<WorkedExample> = emptyList(),
        anchorId: String? = null,
        isLinkable: (packagePath: String, ruleName: String) -> Boolean = { _, _ -> true },
        /** Verified generated examples for this card (spec §14.39); null or empty renders nothing. */
        generatedExamples: GeneratedExamples? = null,
        /**
         * Every `data.*` path resolved for this render (spec §14.41), keyed by [ReferenceData.referencedDataPaths]'
         * own plain-prefix paths; null means no `dataDir` was supplied at all (the feature is off, not just empty).
         */
        dataValues: Map<Operand.Path, JsonElement?>? = null,
        /** Links to the Rego source (spec §14.48); null leaves the location as plain text. */
        sourceLinks: SourceLinks? = null,
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
        appendMetadataLines(sb, rule, pkg, sourceLinks)

        val anchorFor: (Condition.RuleReference) -> String? = { ref -> resolveAnchor(pkg.path, ref, policySet, isLinkable) }
        val functionAnchorFor: (Condition.FunctionCall) -> String? = { call -> resolveFunctionAnchor(pkg.path, call, policySet, isLinkable) }
        val ruleValueAnchorFor: (Operand.RuleValueReference) -> String? = { ref -> resolveRuleValueAnchor(pkg.path, ref, policySet, isLinkable) }
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

        appendReferenceData(sb, rule, policySet, dataValues)
        appendWorkedExamples(sb, rule, workedExamples)
        if (generatedExamples != null) appendGeneratedExamples(sb, rule, generatedExamples, anchorFor, functionAnchorFor, ruleValueAnchorFor)
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

    /** `name <email>`, a bare name, or a bare email -- exactly what the author declared, nothing inferred. */
    private fun authorText(author: io.explico.model.Author): String = when {
        author.name != null && author.email != null -> "${author.name} <${author.email}>"
        else -> author.name ?: author.email.orEmpty()
    }

    /**
     * Spec §14.44: what a match means, by the SAME name-based convention worked examples use ([WorkedExamples.outcomeWord],
     * spec §6.7), with that basis stated in the line itself so it is true by construction. Exact, case-sensitive names only;
     * no line for any other name, a function, a split default or a rule with no body -- an effect is never guessed.
     */
    private fun effectOfMatchLine(rule: RuleGroup): String? =
        effectOfMatchWord(rule)?.let { "**Effect of a match:** $it *(by the `${rule.name}` naming convention)*" }

    /** The outcome word (`❌ denied` / `✅ allowed`) when [rule] has an effect by naming convention, else null. Shared by the card and the JSON export. */
    internal fun effectOfMatchWord(rule: RuleGroup): String? {
        if (rule.name !in setOf("deny", "violation", "allow") || rule.parameters.isNotEmpty() || rule.isSplitDefaultEntry || rule.bodies.isEmpty()) return null
        return WorkedExamples.outcomeWord(rule.name, matched = true)
    }

    /**
     * Spec §14.43: the standard `authors` / `related_resources` fields and any other scalar `custom:` key, one line each in that
     * order, only when non-empty. ONE helper for a rule card and a package page, so their wording cannot diverge.
     */
    private fun appendPassthroughLines(
        sb: StringBuilder,
        authors: List<io.explico.model.Author>,
        relatedResources: List<io.explico.model.RelatedResource>,
        customFields: Map<String, String>,
    ) {
        if (authors.isNotEmpty()) sb.appendLine("*Authors: ${authors.joinToString(", ") { authorText(it) }}*")
        if (relatedResources.isNotEmpty()) {
            sb.appendLine("*Related resources: ${relatedResources.joinToString(", ") { "[${it.description ?: it.ref}](${it.ref})" }}*")
        }
        if (customFields.isNotEmpty()) sb.appendLine("*Custom: ${customFields.entries.joinToString(", ") { "${it.key}: ${it.value}" }}*")
    }

    private fun appendMetadataLines(sb: StringBuilder, rule: RuleGroup, pkg: PolicyPackage, sourceLinks: SourceLinks?) {
        val metadata = rule.metadata
        // "?" only for a default-only rule with zero bodies in this file (review item 3) -- should
        // never actually surface, since a RuleGroup is only ever constructed with at least one of
        // bodies/default non-empty, but this stays a graceful fallback rather than a crash either way.
        val location = ruleLocation(rule)
        // Spec §14.48: with a URL template the location is a link to the exact line (basename:row); no location, or no template, is today's plain text.
        val definedIn = if (location != null && sourceLinks != null) {
            "[`${location.file.substringAfterLast('/')}:${location.row}`](${sourceLinks.url(location.file, location.row)})"
        } else {
            "`${location?.file?.substringAfterLast('/') ?: "?"}`"
        }
        sb.appendLine("*Rule `${rule.name}` in package `${pkg.path}` — defined in $definedIn*")
        if (!metadata?.frameworks.isNullOrEmpty()) {
            sb.appendLine("*Frameworks: ${metadata.frameworks.joinToString(", ")}*")
        }
        appendPassthroughLines(sb, metadata?.authors.orEmpty(), metadata?.relatedResources.orEmpty(), metadata?.customFields.orEmpty())
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

        effectOfMatchLine(rule)?.let { line ->
            sb.appendLine(line)
            sb.appendLine()
        }

        if (rule.default != null) {
            sb.appendLine("**Default outcome:** ${rule.default.rendered}")
            sb.appendLine()
        }
    }

    private fun appendMultiBodySituations(
        sb: StringBuilder,
        rule: RuleGroup,
        anchorFor: (Condition.RuleReference) -> String?,
        functionAnchorFor: (Condition.FunctionCall) -> String?,
        ruleValueAnchorFor: (Operand.RuleValueReference) -> String?,
    ) {
        sb.appendLine("**The rule matches when ANY of the following situations applies:**")
        sb.appendLine()
        rule.bodies.forEachIndexed { index, body ->
            sb.appendLine("### Situation ${index + 1} — all of the following are true")
            sb.appendLine()
            appendConditions(sb, body.conditions, anchorFor, functionAnchorFor, ruleValueAnchorFor)
            sb.appendLine()
            if (body.yieldsValue != null) appendYieldsValueLine(sb, body.yieldsValue, ruleValueAnchorFor)
            if (body.producesValue != null) {
                sb.appendLine("*Produces:* \"${body.producesValue}\"")
                sb.appendLine()
            }
        }
    }

    private fun appendSingleBody(
        sb: StringBuilder,
        body: RuleBody,
        anchorFor: (Condition.RuleReference) -> String?,
        functionAnchorFor: (Condition.FunctionCall) -> String?,
        ruleValueAnchorFor: (Operand.RuleValueReference) -> String?,
    ) {
        // Follow-up to review item 10: a body with no real conditions at all -- every expr was a
        // local-variable assignment, e.g. `limit := n if { n := input.limit }` -- has nothing to
        // put under "All of the following are true", and an empty list there read as a bug, not a
        // feature. The header is skipped entirely rather than shown over nothing.
        if (body.conditions.isNotEmpty()) {
            sb.appendLine("**All of the following are true:**")
            sb.appendLine()
        }
        appendConditions(sb, body.conditions, anchorFor, functionAnchorFor, ruleValueAnchorFor)
        if (body.conditions.isNotEmpty()) sb.appendLine()
        if (body.yieldsValue != null) appendYieldsValueLine(sb, body.yieldsValue, ruleValueAnchorFor)
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

    /** The value a CONDITIONAL complete rule's body yields (follow-up to review item 10) -- see [RuleBody.yieldsValue]'s own KDoc. */
    private fun appendYieldsValueLine(sb: StringBuilder, value: Operand, ruleValueAnchorFor: (Operand.RuleValueReference) -> String?) {
        sb.appendLine("**Value:** ${ExpressionRenderer.renderValue(value, ruleValueAnchorFor)}")
        sb.appendLine()
    }

    /** Spec §6.7: up to 3 matching + 2 non-matching fixtures, each with its produced message(s) and referenced-path values. */
    /**
     * Spec §14.41: what every `data.*` document this rule's bodies actually read resolves to in the supplied
     * data -- a compliance reader sees `` `data ▸ release ▸ exempt services` `` on a card but not what it holds.
     * Nothing at all when [dataValues] is null (no `dataDir` supplied) or the rule reads no plain data document.
     */
    private fun appendReferenceData(sb: StringBuilder, rule: RuleGroup, policySet: PolicySet, dataValues: Map<Operand.Path, JsonElement?>?) {
        if (dataValues == null) return
        val paths = ReferenceData.referencedDataPaths(rule, policySet)
        if (paths.isEmpty()) return
        sb.appendLine("**Reference data**")
        sb.appendLine()
        for (path in paths) {
            val breadcrumb = PathHumanizer.humanize(path.segments).rendered
            val resolved = dataValues[path]
            val valueText = if (resolved == null) ReferenceData.formatValue(null) else "`${ReferenceData.formatValue(resolved)}`"
            sb.appendLine("- $breadcrumb: $valueText")
        }
        sb.appendLine()
        sb.appendLine("*Values are read from the supplied data by OPA at generation time.*")
        sb.appendLine()
    }

    private fun appendWorkedExamples(sb: StringBuilder, rule: RuleGroup, outcomes: List<WorkedExample>) {
        val selected = WorkedExamples.select(outcomes)
        if (selected.isEmpty()) return

        sb.appendLine("**Worked examples**")
        sb.appendLine()
        val referencedPaths = WorkedExamples.referencedPaths(rule)
        for (outcome in selected) appendExampleEntry(sb, rule, outcome.fixture.name, outcome, referencedPaths)
        sb.appendLine()
        // Provenance footer (spec §13.6): a muted, explicit restatement, directly in the rendered
        // artefact, of spec §6.7's own invariant that worked examples are never predicted -- always
        // freshly evaluated by opa against whatever policy version is being rendered right now.
        sb.appendLine("*Examples are evaluated against this policy version by OPA at generation time.*")
        sb.appendLine()
    }

    /**
     * One worked-example entry: the name line with its verdict word, the produced message(s), then the values of the input
     * paths the rule reads. Shared by the author's examples and generated ones, so both read identically.
     */
    private fun appendExampleEntry(sb: StringBuilder, rule: RuleGroup, name: String, outcome: WorkedExample, referencedPaths: List<Operand.Path>) {
        val outcomeWord = WorkedExamples.outcomeWord(rule.name, outcome.matched)
        // A single message's situation label goes on the summary line, matching spec's own
        // example. Multiple messages (a fixture matching more than one body at once) each get
        // their own label instead -- there's no single "the" situation to put on the summary
        // line in that case. Not spec-pinned; no pack fixture exercises single-message
        // multi-body attribution differently than this.
        val summaryLabel = if (outcome.messages.size == 1) outcome.situationLabels.single()?.let { " *(Situation $it)*" } ?: "" else ""
        sb.appendLine("- **$name** — $outcomeWord$summaryLabel")
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

    /**
     * Generated worked examples (spec §14.39), after the author's own section. Names are MECHANICAL -- what the input satisfies
     * or the one condition it breaks, rendered with the ordinary condition phrasing -- never a scenario the tool invented.
     * Renders nothing at all (no heading, no footer) when the rule received none.
     */
    /** The mechanical name of a generated example (spec §14.39), derived from its fields only; [renderCondition] words a broken condition. */
    internal fun generatedExampleName(rule: RuleGroup, entry: GeneratedExample, renderCondition: (Condition) -> String): String {
        val multiBody = rule.bodies.size > 1
        return when (entry.kind) {
            GeneratedExample.Kind.MATCHING ->
                if (multiBody) "satisfies every condition of Situation ${entry.bodyIndex + 1}" else "satisfies every condition"
            GeneratedExample.Kind.NEAR_MISS -> {
                val phrase = renderCondition(rule.bodies[entry.bodyIndex].conditions[entry.brokenConditionIndex!!])
                if (multiBody) "breaks only: $phrase in Situation ${entry.bodyIndex + 1}" else "breaks only: $phrase"
            }
        }
    }

    private fun appendGeneratedExamples(
        sb: StringBuilder,
        rule: RuleGroup,
        generated: GeneratedExamples,
        anchorFor: (Condition.RuleReference) -> String?,
        functionAnchorFor: (Condition.FunctionCall) -> String?,
        ruleValueAnchorFor: (Operand.RuleValueReference) -> String?,
    ) {
        val entries = generated.matching + generated.nearMisses
        if (entries.isEmpty()) return
        sb.appendLine("**Generated examples**")
        sb.appendLine()
        val referencedPaths = WorkedExamples.referencedPaths(rule)
        for (entry in entries) {
            val name = generatedExampleName(rule, entry) { ExpressionRenderer.render(it, anchorFor, functionAnchorFor, ruleValueAnchorFor) }
            appendExampleEntry(sb, rule, name, entry.outcome, referencedPaths)
        }
        sb.appendLine()
        sb.appendLine("*Generated examples are derived mechanically from this rule's conditions and evaluated by OPA at generation time; they illustrate the rule, they are not scenarios chosen by its author.*")
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
        anchorFor: (Condition.RuleReference) -> String?,
        functionAnchorFor: (Condition.FunctionCall) -> String?,
        ruleValueAnchorFor: (Operand.RuleValueReference) -> String?,
        indent: String = "",
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
                sb.appendLine("$indent- ${ExpressionRenderer.render(condition, anchorFor, functionAnchorFor, ruleValueAnchorFor)}")
                // Spec §14.49: an `every` block's conditions are nested one level under its head bullet (a block that could not be rendered in full is
                // never an Every, so everything nested here is a rendered bullet).
                if (condition is Condition.Every) appendConditions(sb, condition.body, anchorFor, functionAnchorFor, ruleValueAnchorFor, "$indent  ")
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
     *
     * Compliance-owner quick wins (spec §14.40, not from a frozen spec table -- a policy-owner review of real
     * output): every title links to its own card; the "Coverage" column is renamed "Rendered" and a closing
     * legend explains both columns, so neither is mistaken for a statement about the control; a "Controls by
     * framework" matrix appears whenever at least one rule's METADATA names one; and, when [policyDigest] is
     * supplied, a SHA-256 over the policy's `.rego` files appears under the title.
     */
    fun renderIndex(
        policySet: PolicySet,
        examplesByPackage: Map<String, Map<String, List<WorkedExample>>> = emptyMap(),
        /** The full policy digest (spec §14.40), if the caller computed one; null prints no digest line at all. */
        policyDigest: String? = null,
        /** The requirements the organisation must evidence (spec §14.46); empty adds nothing to the index. */
        requirements: List<String> = emptyList(),
        /** Links to the Rego source (spec §14.48); null leaves the Source file column as plain text. */
        sourceLinks: SourceLinks? = null,
    ): String {
        data class Row(
            val controlId: String?, val title: String, val packagePath: String, val ruleName: String,
            val coverage: CoverageSummary, val sourceFile: String, val sourceRow: Int?, val hasMatching: Boolean, val hasNotMatching: Boolean,
            // Compliance quick wins (a policy-owner review of the real output, not spec-pinned): the real anchor
            // id this row's card claimed (null for a duplicate name that lost the claim -- see cardAnchorIds --
            // so the title never links to a nonexistent anchor), and the frameworks its METADATA names, for the
            // title link and the "Controls by framework" matrix below.
            val anchorId: String?, val frameworks: List<String>,
        )

        val hasExamples = examplesByPackage.isNotEmpty()
        val rows = policySet.packages.flatMap { pkg ->
            val anchorIds = cardAnchorIds(pkg)
            pkg.rules.mapIndexed { index, rule ->
                val outcomes = WorkedExamples.forGroup(rule, pkg, examplesByPackage[pkg.path]?.get(rule.name) ?: emptyList())
                Row(
                    controlId = rule.metadata?.controlId,
                    title = rule.metadata?.title ?: rule.name,
                    packagePath = pkg.path,
                    ruleName = rule.name,
                    coverage = Coverage.of(rule),
                    sourceFile = ruleLocation(rule)?.file ?: "?",
                    sourceRow = ruleLocation(rule)?.row,
                    hasMatching = outcomes.any { it.matched },
                    hasNotMatching = outcomes.any { !it.matched },
                    anchorId = anchorIds[index],
                    frameworks = rule.metadata?.frameworks ?: emptyList(),
                )
            }
        }.sortedWith(compareBy({ it.controlId == null }, { it.controlId }, { it.packagePath }, { it.ruleName }))

        val sb = StringBuilder()
        sb.appendLine("# Control index")
        sb.appendLine()
        if (policyDigest != null) {
            sb.appendLine("*Policy digest: `sha256:$policyDigest`*")
            sb.appendLine()
        }
        // Review item 17: controls (rows with a control id) first; rules without one -- helpers and
        // unannotated rules -- in their own table, so a reader scanning for controls isn't wading
        // through them. Same columns in both, including the example-coverage one.
        //
        // Compliance quick win: the header reads "Rendered", not "Coverage" -- a compliance reader reads
        // "Coverage" as control coverage, which this column does not measure (see the closing legend).
        fun sourceCell(file: String, row: Int?): String =
            if (sourceLinks != null && row != null) "[`$file:$row`](${sourceLinks.url(file, row)})" else "`$file`"
        fun appendTable(tableRows: List<Row>) {
            if (hasExamples) {
                sb.appendLine("| Control ID | Title | Package | Rule | Rendered | Example coverage | Source file |")
                sb.appendLine("|---|---|---|---|---|---|---|")
            } else {
                sb.appendLine("| Control ID | Title | Package | Rule | Rendered | Source file |")
                sb.appendLine("|---|---|---|---|---|---|")
            }
            tableRows.forEach { row ->
                // Compliance quick win: the title links to the row's own card, the same anchor convention a
                // RuleReference already uses -- a reader scanning the index can jump straight to a control.
                val titleCell = titleLink(row.title, row.packagePath, row.anchorId)
                val prefix = "| ${row.controlId ?: "—"} | $titleCell | `${row.packagePath}` | `${row.ruleName}` | ${row.coverage.percent}%"
                if (hasExamples) {
                    val exampleColumn = "${if (row.hasMatching) "✓" else "–"} / ${if (row.hasNotMatching) "✓" else "–"}"
                    sb.appendLine("$prefix | $exampleColumn | ${sourceCell(row.sourceFile, row.sourceRow)} |")
                } else {
                    sb.appendLine("$prefix | ${sourceCell(row.sourceFile, row.sourceRow)} |")
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

        appendFrameworkMatrix(sb, rows.map { FrameworkRow(it.controlId, it.ruleName, it.packagePath, it.anchorId, it.frameworks) })
        appendRequirementsCoverage(sb, Quality.normalise(requirements).takeIf { it.isNotEmpty() }?.let { Quality.coverageOf(policySet, it) })

        val overall = CoverageSummary(rows.sumOf { it.coverage.rendered }, rows.sumOf { it.coverage.total })
        sb.appendLine("*Overall rendering coverage: ${overall.rendered} of ${overall.total} conditions (${overall.percent}%)*")

        if (hasExamples) {
            val gapCount = rows.count { !it.hasMatching }
            if (gapCount > 0) {
                sb.appendLine()
                sb.appendLine("*$gapCount controls have no fixture demonstrating them — the corpus has gaps.*")
            }
        }

        // Spec §14.44: a rule showing 0% (a helper whose only condition is an `every` block) reads like a failure; name every
        // rule below 100% -- table order, same title link as the table -- and say where its unrendered constructs are shown.
        val belowFull = rows.filter { it.coverage.percent < 100 }
        if (belowFull.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine(
                "*Below 100%: ${belowFull.joinToString(", ") { titleLink(it.title, it.packagePath, it.anchorId) }} -- " +
                    "each shows the constructs it could not render as source on its own card.*",
            )
        }

        appendQualityFindings(sb, Quality.findingsOf(policySet))

        // Compliance quick win: a closing legend, so "Rendered" is never mistaken for a statement about the
        // control itself -- it measures how much of the rule's logic explico could turn into prose, nothing more.
        sb.appendLine()
        sb.appendLine("## How to read this index")
        sb.appendLine()
        sb.appendLine(
            "- **Rendered** — the share of a rule's conditions explico could render as readable text; the rest " +
                "is shown as marked verbatim source on the rule's own card. It says nothing about whether a " +
                "control is effective or complete. A helper rule can show 0% because its only condition is a construct explico does " +
                "not render (such as a `with` override); that says nothing about the rules that call it.",
        )
        if (hasExamples) {
            sb.appendLine(
                "- **Example coverage** — `✓ / ✓` means at least one worked example where the rule matches and " +
                    "at least one where it does not; `–` means none.",
            )
        }
        return sb.toString()
    }

    /** RuleReference anchors (spec §6.5): control-id or package-rulename, same-file bare `#anchor` vs cross-file `<package>.md#anchor`. */
    private fun resolveAnchor(currentPackagePath: String, ref: Condition.RuleReference, policySet: PolicySet, isLinkable: (String, String) -> Boolean): String? =
        anchorForTarget(currentPackagePath, ref.packagePath, ref.ruleName, policySet, isLinkable)

    /** Same anchor convention as a [Condition.RuleReference] (review item 8): a called function is just another card, link-targeted the same way. */
    private fun resolveFunctionAnchor(currentPackagePath: String, call: Condition.FunctionCall, policySet: PolicySet, isLinkable: (String, String) -> Boolean): String? =
        anchorForTarget(currentPackagePath, call.packagePath, call.functionName, policySet, isLinkable)

    /** Same anchor convention as [Condition.RuleReference]/[Condition.FunctionCall] (review item 10): a referenced rule's VALUE is just another card, link-targeted the same way. */
    private fun resolveRuleValueAnchor(currentPackagePath: String, reference: Operand.RuleValueReference, policySet: PolicySet, isLinkable: (String, String) -> Boolean): String? =
        anchorForTarget(currentPackagePath, reference.packagePath, reference.ruleName, policySet, isLinkable)

    /**
     * Null when [isLinkable] says the target has no anchor in the document being assembled
     * (follow-up to review item 18) -- the caller renders a plain, unlinked name instead of a
     * dead link.
     */
    private fun anchorForTarget(
        currentPackagePath: String,
        targetPackagePath: String,
        targetName: String,
        policySet: PolicySet,
        isLinkable: (String, String) -> Boolean,
    ): String? {
        if (!isLinkable(targetPackagePath, targetName)) return null
        val targetPackage = policySet.packages.first { it.path == targetPackagePath }
        val candidates = targetPackage.rules.filter { it.name == targetName }
        val targetRule = candidates.firstOrNull { !it.isSplitDefaultEntry } ?: candidates.first()
        val anchorId = anchorIdOf(targetRule, targetPackagePath)
        return if (targetPackagePath == currentPackagePath) "#$anchorId" else "${docFileName(targetPackagePath)}#$anchorId"
    }

    /** Where [rule] is defined: its first body, else its `default`, else its constant value; null when it has no location at all. Shared by the card, the index and the JSON. */
    internal fun ruleLocation(rule: RuleGroup): io.explico.model.SourceRef? =
        rule.bodies.firstOrNull()?.sourceLocation ?: rule.default?.sourceLocation ?: rule.constantValue?.sourceLocation

    /** `[title](file#anchor)`, or the plain title when the rule's card claimed no anchor (a duplicate name). */
    private fun titleLink(title: String, packagePath: String, anchorId: String?): String =
        anchorId?.let { "[$title](${docFileName(packagePath)}#$it)" } ?: title

    /** The `<package-path>.md` file a package's own cards live in (spec §6.1's naming: dots become dashes). */
    internal fun docFileName(packagePath: String): String = "${packagePath.replace('.', '-')}.md"

    /** One index row's identity, as the "Controls by framework" matrix needs it: nothing about coverage or title. */
    private data class FrameworkRow(val controlId: String?, val ruleName: String, val packagePath: String, val anchorId: String?, val frameworks: List<String>)

    /**
     * Compliance quick win: a requirement-to-controls matrix, grouped by the exact framework strings METADATA
     * names (spec §6.3's `custom.frameworks`) -- the most common compliance artefact ("which controls satisfy
     * SOC 2 CC8.1?"), built from data every card already carries. Renders nothing at all when no rule in the
     * document names a framework.
     */
    private fun appendFrameworkMatrix(sb: StringBuilder, rows: List<FrameworkRow>) {
        // CHALLENGE edge case: a framework listed twice in one rule's own METADATA (YAML authoring slip) must not
        // duplicate that rule's link in its row -- `.distinct()` per row, before any cross-row grouping.
        val byFramework = rows.flatMap { row -> row.frameworks.distinct().map { it to row } }.groupBy({ it.first }, { it.second })
        if (byFramework.isEmpty()) return
        sb.appendLine("## Controls by framework")
        sb.appendLine()
        sb.appendLine("| Framework requirement | Controls |")
        sb.appendLine("|---|---|")
        byFramework.entries.sortedWith(compareBy({ it.key.lowercase() }, { it.key })).forEach { (framework, controlRows) ->
            val links = controlRows
                .sortedWith(compareBy({ it.controlId == null }, { it.controlId }, { it.ruleName }))
                .map { row ->
                    val label = row.controlId ?: row.ruleName
                    row.anchorId?.let { "[$label](${docFileName(row.packagePath)}#$it)" } ?: label
                }
            sb.appendLine("| $framework | ${links.joinToString(", ")} |")
        }
        sb.appendLine()
    }

    /**
     * Spec §14.46: which listed requirements have a control, in the LIST's order, with `**No control**` for a gap, and the framework
     * strings no listed requirement matches (a typo catcher). Nothing at all when [coverage] is null (no requirements supplied).
     */
    private fun appendRequirementsCoverage(sb: StringBuilder, coverage: Quality.RequirementsCoverage?) {
        if (coverage == null) return
        sb.appendLine("## Requirements coverage")
        sb.appendLine()
        sb.appendLine("*${coverage.covered} of ${coverage.total} requirements have at least one control.*")
        sb.appendLine()
        sb.appendLine("| Requirement | Controls |")
        sb.appendLine("|---|---|")
        for (item in coverage.items) {
            sb.appendLine("| ${item.requirement} | ${if (item.controls.isEmpty()) "**No control**" else item.controls.joinToString(", ") { it.link() }} |")
        }
        sb.appendLine()
        if (coverage.unlisted.isNotEmpty()) {
            sb.appendLine("*Framework strings not in the requirements list: ${coverage.unlisted.joinToString(", ")}.*")
            sb.appendLine()
        }
    }

    /** Spec §14.46: controls with no description, no framework, or a shared title. Nothing at all when there is nothing to report. */
    private fun appendQualityFindings(sb: StringBuilder, findings: Quality.Findings) {
        if (findings.isEmpty()) return
        // Blank line BEFORE (the heading follows the overall/below-100% line); none after -- the legend supplies its own.
        sb.appendLine()
        sb.appendLine("## Documentation quality")
        sb.appendLine()
        if (findings.missingDescription.isNotEmpty()) {
            sb.appendLine("- **Missing description (${findings.missingDescription.size}):** ${findings.missingDescription.joinToString(", ") { it.link() }}")
        }
        if (findings.noFramework.isNotEmpty()) {
            sb.appendLine("- **No framework (${findings.noFramework.size}):** ${findings.noFramework.joinToString(", ") { it.link() }}")
        }
        for ((title, controls) in findings.duplicateTitles) {
            sb.appendLine("- **Duplicate title (${controls.size}):** ${controls.joinToString(", ") { it.link() }} -- \"$title\"")
        }
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
