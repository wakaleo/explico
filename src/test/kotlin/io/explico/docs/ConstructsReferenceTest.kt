/**
 * `docs/reference/constructs.md` is a reader's reference of what each piece of Rego becomes. Every row is checked here against the real
 * pipeline (real `opa parse`, the mapper, the renderer), so the page cannot drift from the tool: change a phrase, or promote or demote a
 * construct, and this test fails until the table says the same. Same `assumeTrue(opa)` precedent as the other opa-driven unit tests.
 */
package io.explico.docs

import io.explico.Explico
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

class ConstructsReferenceTest {

    private val page = Files.readString(Path.of("docs/reference/constructs.md"))

    private data class Row(val rego: String, val expected: String)

    /** The first ```rego fence on the page: the helpers every row assumes. */
    private fun prelude(): String = Regex("```rego\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).find(page)!!.groupValues[1]

    /** The code span at the heart of a table cell: `x` or `` x `` (spaces inside the double form are padding); `\|` is a literal pipe. */
    private fun unspan(cell: String): String {
        val trimmed = cell.trim()
        val m = Regex("^(`+)\\s?(.*?)\\s?\\1$").find(trimmed) ?: error("not a code span: $cell")
        return m.groupValues[2].replace("\\|", "|")
    }

    private fun rows(sectionStart: String, sectionEnd: String?): List<Row> {
        val from = page.indexOf(sectionStart).also { check(it >= 0) { "missing section $sectionStart" } }
        val to = sectionEnd?.let { page.indexOf(it, from + 1) } ?: page.length
        return page.substring(from, to).lines()
            .filter { it.startsWith("|") && !it.startsWith("|---") && !it.startsWith("| Rego") }
            .map { line ->
                val cells = line.trim().removePrefix("|").removeSuffix("|").split(Regex("(?<!\\\\)\\|"))
                check(cells.size == 2) { "a row needs exactly two cells: $line" }
                Row(unspan(cells[0]), cells[1].trim())
            }
    }

    /** Statements separated by `;` at nesting depth zero (a `;` inside `{...}`, `[...]` or `(...)` belongs to that expression). */
    private fun statements(rego: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        val current = StringBuilder()
        for (ch in rego) {
            if (ch in "{[(") depth++
            if (ch in "}])") depth--
            if (ch == ';' && depth == 0) { out += current.toString().trim(); current.clear() } else current.append(ch)
        }
        out += current.toString().trim()
        return out
    }

    private fun flatten(conditions: JsonArray, depth: Int = 0): List<Pair<Int, JsonObject>> =
        conditions.flatMap { c ->
            val o = c.jsonObject
            listOf(depth to o) + flatten((o["conditions"] as? JsonArray) ?: JsonArray(emptyList()), depth + 1)
        }

    @Test
    @DisplayName("every row of docs/reference/constructs.md renders exactly as the page says")
    fun everyRowMatchesTheRealRenderer() {
        assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
        val rendered = rows("## Rendered as text", "## Shown as source")
        val source = rows("## Shown as source", null)
        // Not vacuous: the page really does carry a substantial table of each kind.
        assertThat(rendered.size).isGreaterThanOrEqualTo(40)
        assertThat(source.size).isGreaterThanOrEqualTo(6)

        val all = rendered + source
        val rego = prelude() + "\n" + all.withIndex().joinToString("\n") { (i, row) ->
            "r%02d if {\n\t%s\n}\n".format(i + 1, statements(row.rego).joinToString("\n\t"))
        }
        val dir = Files.createTempDirectory("constructs-ref")
        try {
            Files.writeString(dir.resolve("p.rego"), rego)
            val json = Json.parseToJsonElement(Explico.render(Explico.load(dir), dir, null, null, false, true).files.getValue("explico.json")).jsonObject
            val rules = json.getValue("packages").jsonArray.flatMap { it.jsonObject.getValue("rules").jsonArray }.associateBy { it.jsonObject.getValue("name").jsonPrimitive.content }

            val problems = mutableListOf<String>()
            all.forEachIndexed { i, row ->
                val name = "r%02d".format(i + 1)
                val conditions = flatten(rules.getValue(name).jsonObject.getValue("bodies").jsonArray.first().jsonObject.getValue("conditions").jsonArray)
                if (i < rendered.size) {
                    val actual = conditions.joinToString("<br>") { (depth, c) ->
                        check(c.getValue("rendered").jsonPrimitive.content == "true") { "${row.rego} was expected to render but is shown as source" }
                        "↳ ".repeat(depth) + "`` " + c.getValue("text").jsonPrimitive.content.replace("|", "\\|") + " ``"
                    }
                    if (actual != row.expected) problems += "${row.rego}\n   page:   ${row.expected}\n   actual: $actual"
                } else {
                    val reason = conditions.map { it.second }.firstOrNull { it.getValue("rendered").jsonPrimitive.content == "false" }?.getValue("reason")?.jsonPrimitive?.content
                    if (reason != unspan(row.expected)) problems += "${row.rego}\n   page:   ${row.expected}\n   actual reason: $reason"
                }
            }
            assertThat(problems).describedAs("rows that no longer match the renderer").isEmpty()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
