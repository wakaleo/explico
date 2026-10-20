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
    ): String {
        val packageCoverages = policySet.packages.map { Coverage.of(it) }
        val overall = CoverageSummary(packageCoverages.sumOf { it.rendered }, packageCoverages.sumOf { it.total })
        val document = buildJsonObject {
            put("schema", SCHEMA)
            put("policyDigest", policyDigest)
            put("coverage", coverage(overall))
            put("quality", qualityObject(Quality.findingsOf(policySet)))
            put("requirements", Quality.normalise(requirements).takeIf { it.isNotEmpty() }?.let { requirementsObject(Quality.coverageOf(policySet, it)) } ?: JsonNull)
            put("packages", buildJsonArray {
                for (pkg in policySet.packages) add(packageObject(pkg, policySet, examplesByPackage[pkg.path] ?: emptyMap(), generatedByPackage[pkg.path] ?: emptyMap(), dataValues, sourceLinks))
            })
        }
        return prettyJson(document)
    }

    private fun qualityObject(f: Quality.Findings) = buildJsonObject {
        put("missingDescription", buildJsonArray { f.missingDescription.forEach { add(JsonPrimitive(it.label)) } })
        put("noFramework", buildJsonArray { f.noFramework.forEach { add(JsonPrimitive(it.label)) } })
        put("duplicateTitles", buildJsonArray {
            f.duplicateTitles.forEach { (title, controls) ->
                add(buildJsonObject { put("title", title); put("controls", buildJsonArray { controls.forEach { add(JsonPrimitive(it.label)) } }) })
            }
        })
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
    ): JsonObject {
        val anchors = MarkdownRenderer.cardAnchorIds(pkg)
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
                    add(ruleObject(rule, pkg, policySet, anchors[index], WorkedExamples.forGroup(rule, pkg, examples[rule.name] ?: emptyList()), generated[index], dataValues, sourceLinks))
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
    ): JsonObject = buildJsonObject {
        val metadata = rule.metadata
        put("name", rule.name)
        put("controlId", nullable(metadata?.controlId))
        put("title", nullable(metadata?.title))
        put("description", nullable(metadata?.description?.trimEnd()))
        put("anchor", nullable(anchor))
        val location = MarkdownRenderer.ruleLocation(rule)
        put("sourceFile", nullable(location?.file))
        // Spec §14.48: always present (additive); the URL only when a template was given and the rule has a location.
        put("sourceLine", location?.let { JsonPrimitive(it.row) } ?: JsonNull)
        put("sourceUrl", nullable(if (location != null) sourceLinks?.url(location.file, location.row) else null))
        put("frameworks", buildJsonArray { metadata?.frameworks.orEmpty().forEach { add(JsonPrimitive(it)) } })
        put("authors", authors(metadata?.authors.orEmpty()))
        put("relatedResources", relatedResources(metadata?.relatedResources.orEmpty()))
        put("custom", custom(metadata?.customFields.orEmpty()))
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
        put("workedExamples", buildJsonArray { examples.forEach { add(exampleObject(rule, it.fixture.name, it, generated = false)) } })
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

    private fun exampleObject(rule: RuleGroup, name: String, outcome: WorkedExample, generated: Boolean): JsonObject = buildJsonObject {
        put("name", name)
        put("matched", outcome.matched)
        put("outcome", WorkedExamples.outcomeWord(rule.name, outcome.matched))
        put("messages", buildJsonArray { outcome.messages.forEach { add(JsonPrimitive(it)) } })
        put("situations", buildJsonArray { outcome.situationLabels.forEach { add(it?.let { n -> JsonPrimitive(n) } ?: JsonNull) } })
        if (generated) put("generated", true)
    }
}
