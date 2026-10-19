/**
 * Which `data.*` documents a rule actually reads, and how their resolved values are displayed (spec §14.41): a
 * compliance reader sees the breadcrumb `` `data ▸ release ▸ exempt services` `` on a card but not what it
 * contains -- this collects the real `data.*` paths (never a loaded policy rule, which is code, not supplied
 * data) and formats whatever OPA resolved them to.
 */
package io.explico.render

import io.explico.model.Condition
import io.explico.model.Operand
import io.explico.model.PathSegment
import io.explico.model.PolicySet
import io.explico.model.RuleGroup
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

internal object ReferenceData {

    private const val MAX_ARRAY_ELEMENTS = 5
    private const val MAX_VALUE_LENGTH = 200

    /**
     * Every distinct `data.`-rooted path [rule]'s bodies reference, truncated to its plain prefix (the segments
     * up to but not including the first [PathSegment.VarIndex]/[PathSegment.AnyIndex]/[PathSegment.KeyPath] --
     * spec §14.41's "only the plain prefix is evaluated"), deduped and in first-appearance order. A prefix that
     * names a loaded policy rule or package (code, not data the operator supplied) is excluded.
     */
    fun referencedDataPaths(rule: RuleGroup, policySet: PolicySet): List<Operand.Path> {
        val seen = LinkedHashSet<Operand.Path>()
        for (body in rule.bodies) {
            for (condition in body.conditions) {
                for (operand in Coverage.operandsOf(condition)) {
                    collectDataPaths(operand, seen)
                }
            }
        }
        return seen.filterNot { namesALoadedRuleOrPackage(it, policySet) }
    }

    private fun collectDataPaths(operand: Operand, into: MutableSet<Operand.Path>) {
        when (operand) {
            is Operand.Path -> if (operand.segments.firstOrNull() == PathSegment.Field("data")) into.add(plainPrefix(operand))
            is Operand.BuiltinCall -> operand.args.forEach { collectDataPaths(it, into) }
            is Operand.FunctionCall -> operand.args.forEach { collectDataPaths(it, into) }
            // CHALLENGE edge case: a comprehension's own body can reference data too (`count({x | some x in
            // data.allowlist; ...})`) -- same recursion shape Coverage.countUnrendered already uses for it.
            is Operand.Comprehension -> {
                collectDataPaths(operand.term, into)
                operand.valueTerm?.let { collectDataPaths(it, into) }
                operand.body.forEach { condition -> Coverage.operandsOf(condition).forEach { collectDataPaths(it, into) } }
            }
            else -> {}
        }
    }

    /** Segments up to (not including) the first dynamic segment; the leading `data` field is always plain, so this is never empty. */
    private fun plainPrefix(path: Operand.Path): Operand.Path =
        Operand.Path(path.segments.takeWhile { it is PathSegment.Field || it is PathSegment.KeyLiteral })

    /**
     * True when [path] (after the leading `data` field) names a loaded package, a loaded package plus one of
     * its rule names (checked at every split point, since the rule name can be reached at any depth --
     * `data.pkg.rule`, `data.a.b.rule`, ...), OR -- CHALLENGE finding -- is a strict ANCESTOR of a loaded
     * package's path. `opa eval` of an ancestor evaluates every rule nested under it to build that subtree of
     * the `data` document (confirmed empirically: `opa eval data.release` over a `release.*`-packaged policy set
     * runs every one of those packages' rules), the exact risk this exclusion exists to avoid, one level up. A
     * rule's own value is not data an operator supplied; showing it here would just repeat its card under a
     * misleading heading -- or, for an ancestor, silently execute policy logic from what looks like a plain
     * data lookup.
     */
    private fun namesALoadedRuleOrPackage(path: Operand.Path, policySet: PolicySet): Boolean {
        val names = path.segments.drop(1).mapNotNull { plainNameOrNull(it) }
        if (names.size != path.segments.size - 1) return false // a KeyLiteral key is still plain text; nothing else should reach here
        val candidatePackage = names.joinToString(".")
        if (policySet.packages.any { it.path == candidatePackage || it.path.startsWith("$candidatePackage.") }) return true
        for (splitAt in 1 until names.size) {
            val packagePath = names.subList(0, splitAt).joinToString(".")
            val ruleName = names[splitAt]
            if (policySet.packages.any { it.path == packagePath && it.rules.any { rule -> rule.name == ruleName } }) return true
        }
        return false
    }

    private fun plainNameOrNull(segment: PathSegment): String? = when (segment) {
        is PathSegment.Field -> segment.name
        is PathSegment.KeyLiteral -> segment.key
        else -> null
    }

    /** The dotted segments `OpaRunner.evalDataPath` queries: [path] without its leading `data` field. */
    fun dottedSegments(path: Operand.Path): List<String> = path.segments.drop(1).map { plainNameOrNull(it)!! }

    /**
     * [value]'s display text: `null` (undefined in the supplied data) says so in words; an array over
     * [MAX_ARRAY_ELEMENTS] shows the first five then an item count; anything else over [MAX_VALUE_LENGTH]
     * compact-JSON characters is cut with a trailing ellipsis. Never re-reads or re-parses the value -- just
     * formats the [JsonElement] OPA already resolved.
     */
    fun formatValue(value: JsonElement?): String {
        if (value == null) return "not defined in the supplied data"
        if (value is JsonArray && value.size > MAX_ARRAY_ELEMENTS) {
            val truncated = JsonArray(value.take(MAX_ARRAY_ELEMENTS)).toString() // e.g. ["a","b","c","d","e"]
            return "${truncated.dropLast(1)}…] (${value.size} items)"
        }
        val text = value.toString()
        return if (text.length > MAX_VALUE_LENGTH) text.take(MAX_VALUE_LENGTH) + "…" else text
    }
}
