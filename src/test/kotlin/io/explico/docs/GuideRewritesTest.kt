/**
 * `docs/guides/policies-that-document-well.md` suggests rewrites that keep a policy's meaning. Every pair on the page is checked here, so the page cannot say anything untrue:
 * the "shown as source" rule really falls back (as a condition with the stated reason, or as the stated unrendered value), the rewrite renders completely (no fallback condition, no unrendered
 * value), and BOTH give the same answer on every sample input the page lists, evaluated by opa through the worked examples. A pair is announced by an HTML comment
 * `<!-- guide-check: name=<n> (reason=<r> | value=<text>) inputs=<json array> -->` followed by two ```rego fences defining `<n>_before` and `<n>_after`; the shared definitions
 * are the fence after `<!-- guide-prelude -->`. Same `assumeTrue(opa)` precedent as ConstructsReferenceTest.
 */
package io.explico.docs

import io.explico.Explico
import io.explico.RenderOptions
import io.explico.opa.OpaRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class GuideRewritesTest {

    private val page = Files.readString(Path.of("docs/guides/policies-that-document-well.md"))

    private data class Pair(val name: String, val reason: String?, val value: String?, val inputs: JsonArray, val before: String, val after: String)

    private val fence = Regex("```rego\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)

    private fun prelude(): String {
        val at = page.indexOf("<!-- guide-prelude -->")
        check(at >= 0) { "the page needs a <!-- guide-prelude --> marker" }
        return fence.find(page, at)!!.groupValues[1]
    }

    private fun pairs(): List<Pair> {
        val marker = Regex("<!-- guide-check: name=(\\S+) (?:reason=(\\S+)|value=(\\S+)) inputs=(.*?) -->")
        return marker.findAll(page).map { m ->
            val fences = fence.findAll(page, m.range.last).take(2).map { it.groupValues[1] }.toList()
            check(fences.size == 2) { "a guide-check needs two rego fences after it: ${m.value}" }
            Pair(m.groupValues[1], m.groupValues[2].ifEmpty { null }, m.groupValues[3].ifEmpty { null }, Json.parseToJsonElement(m.groupValues[4]).jsonArray, fences[0], fences[1])
        }.toList()
    }

    @Test
    @DisplayName("every rewrite on the guide falls back as stated, renders completely, and gives the same answer as the original on every listed input")
    fun everyPairHoldsUp() {
        assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
        val all = pairs()
        assertThat(all.size).describedAs("the guide carries at least two checked pairs").isGreaterThanOrEqualTo(2)

        val dir = Files.createTempDirectory("guide-rewrites")
        val examples = Files.createDirectory(dir.resolve("ex-dir"))
        val policies = Files.createDirectory(dir.resolve("policies"))
        try {
            Files.writeString(policies.resolve("p.rego"), prelude() + "\n" + all.joinToString("\n") { it.before + "\n" + it.after })
            all.forEach { pair ->
                pair.inputs.forEachIndexed { j, input ->
                    Files.writeString(examples.resolve("${pair.name}-%02d.json".format(j)), """{"name": "${pair.name}-$j", "input": $input}""")
                }
            }
            val docs = Explico.render(Explico.load(policies), RenderOptions(examples = Explico.loadExamples(examples), json = true))
            val json = Json.parseToJsonElement(docs.files.getValue("explico.json")).jsonObject
            val rules = json.getValue("packages").jsonArray.flatMap { it.jsonObject.getValue("rules").jsonArray }.associateBy { it.jsonObject.getValue("name").jsonPrimitive.content }
            val values = json.getValue("quality").jsonObject.getValue("valuesShownAsSource").jsonArray.map { it.jsonObject }

            val problems = mutableListOf<String>()
            for (pair in all) {
                val before = rules.getValue("${pair.name}_before").jsonObject
                val after = rules.getValue("${pair.name}_after").jsonObject
                fun conditions(rule: JsonObject) = rule.getValue("bodies").jsonArray.flatMap { it.jsonObject.getValue("conditions").jsonArray }.map { it.jsonObject }
                fun unrenderedValuesOf(rule: String) = values.filter { v -> v.getValue("places").jsonArray.any { it.jsonObject.getValue("rule").jsonPrimitive.content == rule } }.map { it.getValue("source").jsonPrimitive.content }

                if (pair.reason != null) {
                    val reasons = conditions(before).filter { it.getValue("rendered").jsonPrimitive.content == "false" }.map { it.getValue("reason").jsonPrimitive.content }
                    if (pair.reason !in reasons) problems += "${pair.name}_before was expected to fall back with reason ${pair.reason}, but its fallbacks are $reasons"
                } else if (pair.value !in unrenderedValuesOf("${pair.name}_before")) {
                    problems += "${pair.name}_before was expected to show the value ${pair.value} as source, but shows ${unrenderedValuesOf("${pair.name}_before")}"
                }
                if (conditions(after).any { it.getValue("rendered").jsonPrimitive.content == "false" }) problems += "${pair.name}_after still has a condition shown as source"
                if (unrenderedValuesOf("${pair.name}_after").isNotEmpty()) problems += "${pair.name}_after still has a value shown as source: ${unrenderedValuesOf("${pair.name}_after")}"

                // Worked examples cover every fixture of every pair: keep this pair's own, keyed by the full fixture name.
                fun answers(rule: JsonObject) = rule.getValue("workedExamples").jsonArray.map { it.jsonObject }
                    .filter { it.getValue("name").jsonPrimitive.content.startsWith("${pair.name}-") }
                    .associate { it.getValue("name").jsonPrimitive.content to it.getValue("matched").jsonPrimitive.content }
                val was = answers(before)
                val now = answers(after)
                if (was.size != pair.inputs.size) problems += "${pair.name}: expected ${pair.inputs.size} answers, got ${was.size}"
                if (was != now) problems += "${pair.name}: the rewrite answers differently:\n   before $was\n   after  $now"
                if (was.values.toSet() != setOf("true", "false")) problems += "${pair.name}: the sample inputs must include both a match and a non-match (got ${was.values.toSet()})"
            }
            assertThat(problems).describedAs("guide pairs that no longer hold").isEmpty()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
