/**
 * Spec §14.59: the situation-aware part of a LOGIC_CHANGED rule's "What changed" sub-bullets. A rule's situations are what its card shows as `Situation N`:
 * the branches of an `else` chain (decided in order, first match wins) or, for a name with several definitions, its bodies. Each is matched across the two
 * versions by its CONDITIONS, and the lines say what moved -- never why or whether it is stricter. Pure: reads the model only; the canonical hash and the
 * classification are untouched (they already see branch order and values -- this only words the difference).
 */
package io.explico.diff

import io.explico.model.Condition
import io.explico.model.RuleGroup
import io.explico.render.Coverage
import io.explico.render.ExpressionRenderer

internal object Situations {

    /** One branch: [key] is its conditions as the card words them (a fallback by its source), [value] what it yields as the card words it (null: none), [label] its name in text. */
    private class Situation(val key: List<String>, val value: String?, val label: String?)

    private class Version(val situations: List<Situation>, val isChain: Boolean)

    private val NO_RULE_VALUE_LINK: (io.explico.model.Operand.RuleValueReference) -> String? = { null }
    private val NO_RULE_LINK: (Condition.RuleReference) -> String? = { null }
    private val NO_FUNCTION_LINK: (Condition.FunctionCall) -> String? = { null }

    /** The extra sub-bullets for [old] -> [new], in a fixed order: reordered, value changes (new order), removed (old numbering), added (new numbering). Empty when none applies. */
    fun bullets(old: RuleGroup, new: RuleGroup): List<String> = facts(old, new).map { it.sentence }

    /** Spec §14.78: the same lines as [bullets], as typed facts (sentence only). */
    fun facts(old: RuleGroup, new: RuleGroup): List<ChangeFact> {
        val before = versionOf(old)
        val after = versionOf(new)
        if (before.situations.isEmpty() || after.situations.isEmpty()) return emptyList()

        // Pair equal keys in order of appearance (a key twice pairs first with first, second with second). A rule with ONE body on both sides is that body on both
        // sides, whatever changed in it.
        val pairs = mutableListOf<Pair<Int, Int>>()
        var unmatchedNew = after.situations.indices.toMutableList()
        var unmatchedOld = mutableListOf<Int>()
        if (!before.isChain && !after.isChain && before.situations.size == 1 && after.situations.size == 1) {
            pairs += 0 to 0
            unmatchedNew = mutableListOf()
        } else {
            for ((i, situation) in before.situations.withIndex()) {
                val j = unmatchedNew.firstOrNull { after.situations[it].key == situation.key }
                if (j == null) unmatchedOld += i else { pairs += i to j; unmatchedNew.remove(j) }
            }
        }
        // A branch whose CONDITIONS changed but which yields the same value is still the same situation: the `condition removed/added` lines name the edit, so it is
        // neither removed nor added. Best effort, by value alone: with two situations of one value only one of which was reworded the wrong pair may be taken, which
        // changes only these advisory removed/added lines -- never a hash, a category or the condition lines, and the source diff below the card still shows the truth.
        val reworded = mutableListOf<Int>()
        for (i in unmatchedOld.toList()) {
            val value = before.situations[i].value ?: continue
            val j = unmatchedNew.firstOrNull { after.situations[it].value == value } ?: continue
            reworded += i
            unmatchedNew.remove(j)
        }
        unmatchedOld.removeAll(reworded)

        val bullets = mutableListOf<ChangeFact>()
        val inNewOrder = pairs.sortedBy { it.second }
        if ((before.isChain || after.isChain) && inNewOrder.map { it.first } != inNewOrder.map { it.first }.sorted()) {
            bullets += ChangeFact.situationsReordered("situations reordered: now in the order of the old situations ${inNewOrder.joinToString(", ") { (i, _) -> before.situations[i].label?.removePrefix("situation ") ?: "?" }}")
        }
        for ((i, j) in inNewOrder) {
            val was = before.situations[i].value
            val now = after.situations[j].value
            if (was != null && now != null && was != now) {
                bullets += ChangeFact.situationValueChanged("${after.situations[j].label?.let { "$it: value" } ?: "value"} changed from $was to $now")
            }
        }
        // A situation with neither a value nor a chain around it (a boolean rule's extra body) is already named by the condition and body-count lines.
        fun named(version: Version, index: Int) = version.isChain || version.situations[index].value != null
        for (i in unmatchedOld.filter { named(before, it) }) bullets += ChangeFact.situationRemoved("${before.situations[i].label} removed (was: then ${before.situations[i].value ?: "no value"})")
        for (j in unmatchedNew.filter { named(after, it) }) bullets += ChangeFact.situationAdded("${after.situations[j].label} added (then ${after.situations[j].value ?: "no value"})")
        return bullets
    }

    private fun versionOf(rule: RuleGroup): Version {
        val isChain = rule.bodies.any { it.elseBranches.isNotEmpty() }
        val branches = rule.bodies.flatMap { body ->
            if (body.elseBranches.isEmpty()) listOf(body.conditions to body.yieldsValue)
            else listOf(body.conditions to body.yieldsValue) + body.elseBranches.map { it.conditions to it.yieldsValue }
        }
        var number = 0
        val situations = branches.mapIndexed { index, (conditions, value) ->
            // A chain numbers only branches with conditions and calls the closing one "otherwise" (as its card does); several definitions are numbered as listed;
            // a rule with one body has nothing to number.
            val label = when {
                isChain -> if (conditions.isEmpty()) "otherwise" else "situation ${++number}"
                branches.size > 1 -> "situation ${index + 1}"
                else -> null
            }
            Situation(keyOf(conditions), value?.let { ExpressionRenderer.renderValue(it, NO_RULE_VALUE_LINK) }, label)
        }
        return Version(situations, isChain)
    }

    private fun keyOf(conditions: List<Condition>): List<String> =
        Coverage.flatten(conditions).map { condition ->
            if (condition is Condition.Unrendered) "source: ${condition.sourceText.trim()}"
            else ExpressionRenderer.render(condition, NO_RULE_LINK, NO_FUNCTION_LINK, NO_RULE_VALUE_LINK)
        }
}
