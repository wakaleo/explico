/**
 * The machine-readable result (spec §14.45, schema `explico/render/1`): the same facts the Markdown states, as JSON, for tools that
 * should not parse Markdown. Nothing is re-derived here -- every condition's `text` is the exact phrase the card shows (rendered
 * with no links), coverage is [Coverage], examples come from the same [WorkedExamples] outcomes, reference data from the same
 * resolved values -- so the JSON can never claim more than the card. Keys are written in a fixed order, so output is byte-identical.
 */
package io.explico.render

import io.explico.generate.GeneratedExample
import io.explico.generate.GeneratedExamples
import io.explico.model.Author
import io.explico.model.Condition
import io.explico.model.Operand
import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RelatedResource
import io.explico.model.RuleBody
import io.explico.model.RuleGroup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object JsonExport {

    const val SCHEMA = "explico/render/1"

    /** [prettyJson] is also the diff export's printer: 2-space indent, trailing newline. */
    private val printer = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun prettyJson(element: JsonElement): String = printer.encodeToString(JsonElement.serializer(), element) + "\n"

    fun render(
        policySet: PolicySet,
        policyDigest: String,
        examplesByPackage: Map<String, Map<String, List<WorkedExample>>>,
        generatedByPackage: Map<String, Map<Int, GeneratedExamples>>,
        dataValues: Map<Operand.Path, JsonElement?>?,
        requirements: List<String> = emptyList(),
        sourceLinks: SourceLinks? = null,
        /** Spec §14.56: grouped controls; null when grouping is off (then the document has no `controls` key and is unchanged). */
        groups: ControlGroups? = null,
        /** Spec §14.58: the second link; null (the default) leaves the document without any `companionUrl`. */
        companion: CompanionLinks? = null,
        /** Spec §14.64: echoed as `quality.required`. */
        required: RequiredSet = RequiredSet.DEFAULT,
    ): String {
        val packageCoverages = policySet.packages.map { Coverage.of(it) }
        val overall = CoverageSummary(packageCoverages.sumOf { it.rendered }, packageCoverages.sumOf { it.total })
        val document = buildJsonObject {
            put("schema", SCHEMA)
            put("policyDigest", policyDigest)
            put("coverage", coverage(overall))
            put("quality", qualityObject(Quality.findingsOf(policySet, groups, examplesByPackage), required))
            put("requirements", Quality.normalise(requirements).takeIf { it.isNotEmpty() }?.let { requirementsObject(Quality.coverageOf(policySet, it, groups)) } ?: JsonNull)
            if (groups != null) put("controls", controlsArray(groups))
            put("packages", buildJsonArray {
                for (pkg in policySet.packages) add(packageObject(pkg, policySet, examplesByPackage[pkg.path] ?: emptyMap(), generatedByPackage[pkg.path] ?: emptyMap(), dataValues, sourceLinks, groups, companion))
            })
        }
        return prettyJson(document)
    }

    private fun qualityObject(f: Quality.Findings, required: RequiredSet) = buildJsonObject {
        put("missingDescription", buildJsonArray { f.missingDescription.forEach { add(JsonPrimitive(it.label)) } })
        put("noFramework", buildJsonArray { f.noFramework.forEach { add(JsonPrimitive(it.label)) } })
        put("noMatchingExample", buildJsonArray { f.noMatchingExample.forEach { add(JsonPrimitive(it.label)) } })
        put("rawDecisionObject", buildJsonArray { f.rawDecisionObject.forEach { add(JsonPrimitive(it.label)) } })
        put("duplicateTitles", buildJsonArray {
            f.duplicateTitles.forEach { (title, controls) ->
                add(buildJsonObject { put("title", title); put("controls", buildJsonArray { controls.forEach { add(JsonPrimitive(it.label)) } }) })
            }
        })
        put("unpublishedComments", buildJsonArray {
            f.unpublishedComments.forEach { add(buildJsonObject { put("file", it.file); put("row", it.row); put("lines", it.lines) }) }
        })
        // Spec §14.64: which of these properties the caller made gaps (the effective set, canonical order).
        put("required", buildJsonArray { required.names.forEach { add(JsonPrimitive(it)) } })
    }

    /** Spec §14.56: one entry per grouped control, in id order -- its page, the page-level anchor, its title, and its rules in the order the page shows them. */
    private fun controlsArray(groups: ControlGroups) = buildJsonArray {
        groups.groups.forEach { group ->
            add(buildJsonObject {
                put("controlId", group.controlId)
                put("page", group.page)
                put("anchor", group.anchor)
                put("title", nullable(group.title))
                put("rules", buildJsonArray {
                    group.members.forEach { member ->
                        add(buildJsonObject {
                            put("package", member.packagePath)
                            put("rule", member.rule.name)
                            put("sourceFile", nullable(MarkdownRenderer.ruleLocation(member.rule)?.file))
                            put("anchor", nullable(member.anchor))
                        })
                    }
                })
            })
        }
    }

    private fun requirementsObject(c: Quality.RequirementsCoverage) = buildJsonObject {
        put("total", c.total)
        put("covered", c.covered)
        put("items", buildJsonArray {
            c.items.forEach { item ->
                add(buildJsonObject { put("requirement", item.requirement); put("controls", buildJsonArray { item.controls.forEach { add(JsonPrimitive(it.label)) } }) })
            }
        })
        put("unlisted", buildJsonArray { c.unlisted.forEach { add(JsonPrimitive(it)) } })
    }

    private fun coverage(c: CoverageSummary) = buildJsonObject {
        put("rendered", c.rendered)
        put("total", c.total)
        put("percent", c.percent)
    }

    private fun nullable(text: String?): JsonElement = text?.let { JsonPrimitive(it) } ?: JsonNull

    private fun authors(authors: List<Author>) = buildJsonArray {
        authors.forEach { add(buildJsonObject { put("name", nullable(it.name)); put("email", nullable(it.email)) }) }
    }

    private fun relatedResources(resources: List<RelatedResource>) = buildJsonArray {
        resources.forEach { add(buildJsonObject { put("ref", it.ref); put("description", nullable(it.description)) }) }
    }

    private fun custom(fields: Map<String, String>) = buildJsonObject { fields.entries.sortedBy { it.key }.forEach { put(it.key, it.value) } }

    private fun packageObject(
        pkg: PolicyPackage,
        policySet: PolicySet,
        examples: Map<String, List<WorkedExample>>,
        generated: Map<Int, GeneratedExamples>,
        dataValues: Map<Operand.Path, JsonElement?>?,
        sourceLinks: SourceLinks?,
        groups: ControlGroups?,
        companion: CompanionLinks?,
    ): JsonObject {
        val anchors = MarkdownRenderer.cardAnchorIds(pkg, groups)
        return buildJsonObject {
            put("path", pkg.path)
            put("file", MarkdownRenderer.docFileName(pkg.path))
            put("title", nullable(pkg.metadata?.title))
            put("description", nullable(pkg.metadata?.description))
            put("authors", authors(pkg.metadata?.authors.orEmpty()))
            put("relatedResources", relatedResources(pkg.metadata?.relatedResources.orEmpty()))
            put("custom", custom(pkg.metadata?.customFields.orEmpty()))
            put("coverage", coverage(Coverage.of(pkg)))
            put("rules", buildJsonArray {
                pkg.rules.forEachIndexed { index, rule ->
                    val placement = groups?.placementOf(pkg.path, index)
                    add(ruleObject(rule, pkg, policySet, placement?.member?.anchor ?: anchors[index], WorkedExamples.forGroup(rule, pkg, examples[rule.name] ?: emptyList()), generated[index], dataValues, sourceLinks, placement?.group?.page, companion))
                }
            })
        }
    }

    private fun ruleObject(
        rule: RuleGroup,
        pkg: PolicyPackage,
        policySet: PolicySet,
        anchor: String?,
        examples: List<WorkedExample>,
        generated: GeneratedExamples?,
        dataValues: Map<Operand.Path, JsonElement?>?,
        sourceLinks: SourceLinks?,
        controlPage: String?,
        companion: CompanionLinks?,
    ): JsonObject = buildJsonObject {
        val metadata = rule.metadata
        put("name", rule.name)
        put("controlId", nullable(metadata?.controlId))
        // Spec §14.55: present (true) only when the id was derived from the file name rather than declared; absent otherwise.
        if (metadata?.controlIdDerivedFrom != null) put("controlIdDerived", true)
        put("title", nullable(metadata?.title))
        put("description", nullable(metadata?.description?.trimEnd()))
        put("anchor", nullable(anchor))
        // Spec §14.56: present only for a rule moved to a control page (its `anchor` is then the id of its section THERE).
        if (controlPage != null) put("controlPage", controlPage)
        val location = MarkdownRenderer.ruleLocation(rule)
        put("sourceFile", nullable(location?.file))
        // Spec §14.48: always present (additive); the URL only when a template was given and the rule has a location.
        put("sourceLine", location?.let { JsonPrimitive(it.row) } ?: JsonNull)
        put("sourceUrl", nullable(if (location != null) sourceLinks?.url(location.file, location.row) else null))
        // Spec §14.58: present only when a companion template was given (then null for a rule with no location); the same URL the card links.
        if (companion != null) put("companionUrl", nullable(location?.let { companion.url(pkg.path, it.file, it.row) }))
        put("frameworks", buildJsonArray { metadata?.frameworks.orEmpty().forEach { add(JsonPrimitive(it)) } })
        put("authors", authors(metadata?.authors.orEmpty()))
        put("relatedResources", relatedResources(metadata?.relatedResources.orEmpty()))
        put("custom", custom(metadata?.customFields.orEmpty()))
        // Spec §14.53, §14.54: each declared projection, only when it was declared (every other export is unchanged).
        metadata?.projection?.verdict?.let { v ->
            put("verdict", buildJsonObject { put("outcome", v.outcome); put("reason", nullable(v.reason)) })
        }
        metadata?.projection?.actions?.let { a -> put("actions", buildJsonObject { put("at", a.at); put("label", a.label) }) }
        metadata?.projection?.annotations?.let { n -> put("annotations", buildJsonObject { put("at", n.at) }) }
        metadata?.projection?.timing?.let { t -> put("timing", buildJsonObject { put("dueBy", nullable(t.dueBy)); put("startBefore", nullable(t.startBefore)) }) }
        // Spec §14.61: the declared mask paths (not secret: they name where, never what), only when declared.
        metadata?.projection?.mask?.let { m -> put("mask", buildJsonArray { m.forEach { add(JsonPrimitive(it)) } }) }
        put("parameters", buildJsonArray { rule.parameters.forEach { add(JsonPrimitive(it)) } })
        // "❌ denied" -> "denied": the same convention (and exclusions) as the card's line, minus the glyph.
        put("effectOfMatch", nullable(MarkdownRenderer.effectOfMatchWord(rule)?.substringAfter(' ')))
        put("default", nullable(rule.default?.rendered))
        put("constantValue", nullable(rule.constantValue?.rendered))
        put("isSplitDefaultEntry", rule.isSplitDefaultEntry)
        put("coverage", coverage(Coverage.of(rule)))
        put("bodies", buildJsonArray { rule.bodies.forEach { add(bodyObject(it)) } })
        put("referenceData", buildJsonArray {
            if (dataValues != null) ReferenceData.referencedDataPaths(rule, policySet).forEach { add(referenceDatum(it, dataValues[it])) }
        })
        // Spec §14.62: a function is not matched by an input, so it has no key at all (an ordinary rule with no fixtures has an empty list).
        if (rule.parameters.isEmpty()) put("workedExamples", buildJsonArray { examples.forEach { add(exampleObject(rule, it.fixture.name, it, generated = false)) } })
        put("generatedExamples", buildJsonArray {
            generated?.let { g ->
                (g.matching + g.nearMisses).forEach { entry ->
                    add(exampleObject(rule, MarkdownRenderer.generatedExampleName(rule, entry, ::phrase), entry.outcome, generated = true))
                }
            }
        })
    }

    /** A condition's phrase exactly as its card bullet words it, with no links. */
    private fun phrase(condition: Condition): String =
        ExpressionRenderer.render(condition, { null }, { null }, { null })

    private fun bodyObject(body: RuleBody): JsonObject = buildJsonObject {
        put("conditions", buildJsonArray { body.conditions.forEach { add(conditionObject(it)) } })
        put("produces", nullable(body.producesValue))
        put("yields", nullable(body.yieldsValue?.let { ExpressionRenderer.renderValue(it) { null } }))
        // Spec §14.52: present only for an `else` chain (so every other export is unchanged). `conditions`/`yields` above are the chain's first
        // branch; these are the ones after it, in order. An empty `conditions` is the closing "Otherwise" branch.
        if (body.elseBranches.isNotEmpty()) {
            put("elseBranches", buildJsonArray {
                body.elseBranches.forEach { branch ->
                    add(buildJsonObject {
                        put("conditions", buildJsonArray { branch.conditions.forEach { add(conditionObject(it)) } })
                        put("yields", ExpressionRenderer.renderValue(branch.yieldsValue) { null })
                        put("sourceLine", branch.sourceLocation.row)
                    })
                }
            })
        }
    }

    private fun conditionObject(condition: Condition): JsonObject = buildJsonObject {
        val kind = when (condition) {
            is Condition.Comparison -> "comparison"
            is Condition.Membership -> "membership"
            is Condition.Truthy -> "truthy"
            is Condition.BuiltinCall -> "builtinCall"
            is Condition.SomeIn -> "someIn"
            is Condition.RuleReference -> "ruleReference"
            is Condition.FunctionCall -> "functionCall"
            is Condition.Unrendered -> "unrendered"
            is Condition.Every -> "every"
            is Condition.SomeKey -> "someKey"
        }
        val unrendered = condition as? Condition.Unrendered
        put("kind", kind)
        put("rendered", unrendered == null)
        put("text", if (unrendered == null) JsonPrimitive(phrase(condition)) else JsonNull)
        put("source", nullable(unrendered?.sourceText))
        put("reason", nullable(unrendered?.reason))
        // Spec §14.49: an `every` carries its own conditions, nested; `text` is the head phrase (the card's head bullet).
        if (condition is Condition.Every) put("conditions", buildJsonArray { condition.body.forEach { add(conditionObject(it)) } })
        put("link", when (condition) {
            is Condition.RuleReference -> buildJsonObject { put("package", condition.packagePath); put("rule", condition.ruleName) }
            is Condition.FunctionCall -> buildJsonObject { put("package", condition.packagePath); put("rule", condition.functionName) }
            else -> JsonNull
        })
    }

    private fun referenceDatum(path: Operand.Path, value: JsonElement?): JsonObject = buildJsonObject {
        put("path", "data." + ReferenceData.dottedSegments(path).joinToString("."))
        put("breadcrumb", PathHumanizer.humanize(path.segments).rendered.trim('`'))
        put("defined", value != null)
        put("value", value ?: JsonNull)
    }

    private fun exampleObject(rule: RuleGroup, shownName: String, shownOutcome: WorkedExample, generated: Boolean): JsonObject = buildJsonObject {
        // Spec §14.61: the same masked view the card reads.
        val view = ExampleMasking.view(rule.metadata?.projection?.mask, shownName, shownOutcome)
        val name = view.name
        val outcome = view.example
        put("name", name)
        put("matched", outcome.matched)
        put("outcome", WorkedExamples.outcomeWord(rule.name, outcome.matched))
        put("messages", buildJsonArray { outcome.messages.forEach { add(JsonPrimitive(it)) } })
        put("situations", buildJsonArray { outcome.situationLabels.forEach { add(it?.let { n -> JsonPrimitive(n) } ?: JsonNull) } })
        // Spec §14.53, §14.54: what each declaration found in THIS example -- the same result the card reads, so the two cannot disagree. A key is
        // present only when its projection applied (`remediation` and `annotations` may then be empty lists; `timing` carries null for an unusable instant).
        val projected = ExampleProjections.of(rule.metadata?.projection, outcome)
        projected.verdict?.let { v ->
            put("verdict", buildJsonObject { put("outcome", v.outcome); put("text", v.text); put("reason", nullable(v.reason)) })
        }
        projected.remediation?.let { labels -> put("remediation", buildJsonArray { labels.forEach { add(JsonPrimitive(it)) } }) }
        projected.annotations?.let { statuses -> put("annotations", buildJsonArray { statuses.forEach { add(JsonPrimitive(it)) } }) }
        projected.timing?.let { t -> put("timing", buildJsonObject { put("dueBy", nullable(t.dueBy)); put("startBefore", nullable(t.startBefore)) }) }
        if (generated) put("generated", true)
    }
}
