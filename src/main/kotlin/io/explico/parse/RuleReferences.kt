/**
 * Spec §14.68: which rules are referenced by ANOTHER rule, decided from opa's own AST -- a generic walk over every `var` and `ref` term of a rule (its head, body
 * expressions, `with` overrides and `else` branches), resolved with the mapper's own rules for naming a rule: a bare name in the same package, an import (a rule imported
 * by name, or a package imported under an alias) and a full `data.<package>.<rule>` path. A use that the domain model only keeps as verbatim source (inside a
 * comprehension, an `every`, a `with`) is therefore still seen. Conservative by construction: a local variable named like a rule counts as a use, which can only hide
 * a finding, never invent one. An OPA unit test (a rule named `test_*`) is not a user of the rule it exercises.
 */
package io.explico.parse

import io.explico.opa.OpaRule
import io.explico.opa.OpaTerm
import io.explico.opa.opaJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

internal object RuleReferences {

    /**
     * Adds to [used] every (package, rule) that some rule in [rules] -- all defined in package [currentPackage], whose imports are [aliases] (alias -> import path
     * without the `data.` root) -- refers to. [registry] is package -> every rule/function name defined there. A rule's reference to ITSELF (its own name, from any of
     * its definitions or `else` branches) does not count.
     */
    fun collect(currentPackage: String, aliases: Map<String, String>, rules: List<OpaRule>, registry: Map<String, Set<String>>, used: MutableSet<Pair<String, String>>) {
        for (rule in rules) {
            // An OPA unit test exercises a rule, it does not use it: its references must not hide the real entry point it tests.
            if (isTestRule(rule.head.name)) continue
            val self = rule.head.name?.let { currentPackage to it }
            Scan(currentPackage, aliases, registry) { target -> if (target != self) used += target }.rule(rule)
        }
    }

    /** OPA's own test convention: a rule named `test_*` is a unit test, `todo_test_*` a skipped one. */
    fun isTestRule(name: String?): Boolean = name != null && (name.startsWith("test_") || name.startsWith("todo_test_"))

    private class Scan(
        private val currentPackage: String,
        private val aliases: Map<String, String>,
        private val registry: Map<String, Set<String>>,
        private val mark: (Pair<String, String>) -> Unit,
    ) {
        fun rule(rule: OpaRule) {
            rule.head.value?.let { term(it) }
            rule.head.key?.let { term(it) }
            for (expr in rule.body) {
                expr.terms?.let { element(it) }
                expr.with?.let { element(it) }
            }
            rule.elseBranch?.let { rule(it) }
        }

        private fun term(term: OpaTerm) = element(opaJson.encodeToJsonElement(OpaTerm.serializer(), term))

        private fun element(element: JsonElement) {
            when (element) {
                is JsonArray -> element.forEach { element(it) }
                is JsonObject -> {
                    when ((element["type"] as? JsonPrimitive)?.content) {
                        "var" -> name(element["value"].textOrNull(), emptyList())
                        "ref" -> chain(element["value"] as? JsonArray)
                    }
                    // A ref's own root has been handled by [chain]; descending into it again only re-reads a bare name already considered. Every other value may nest terms.
                    element.forEach { (key, value) -> if (key != "location") element(value) }
                }
                else -> Unit
            }
        }

        private fun chain(terms: JsonArray?) {
            val items = terms?.mapNotNull { it as? JsonObject } ?: return
            val root = items.firstOrNull()?.takeIf { (it["type"] as? JsonPrimitive)?.content == "var" }?.get("value").textOrNull() ?: return
            val segments = items.drop(1).takeWhile { (it["type"] as? JsonPrimitive)?.content == "string" }.mapNotNull { it["value"].textOrNull() }
            name(root, segments)
        }

        /** [root] is a variable name, followed by the plain string segments of its ref chain (empty for a bare name). */
        private fun name(root: String?, segments: List<String>) {
            if (root == null) return
            if (root == "data") {
                for (k in 1 until segments.size) known(segments.subList(0, k).joinToString("."), segments[k])
                return
            }
            aliases[root]?.let { path ->
                // `import data.lib.pkg.rule` (a member by name) or `import data.lib.pkg [as alias]` (a package, the rule is the next segment).
                val split = path.lastIndexOf('.')
                if (split > 0) known(path.substring(0, split), path.substring(split + 1))
                segments.firstOrNull()?.let { known(path, it) }
            }
            known(currentPackage, root)
        }

        private fun known(packagePath: String, rule: String) {
            if (registry[packagePath]?.contains(rule) == true) mark(packagePath to rule)
        }

        private fun JsonElement?.textOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
