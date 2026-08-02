/**
 * Rendering coverage: the fraction of leaf [io.explico.model.Condition]s rendered as
 * structured text rather than fallback source blocks (spec §6.6).
 */
package io.explico.render

import io.explico.model.Condition
import io.explico.model.Operand
import io.explico.model.PolicyPackage
import io.explico.model.RuleGroup

/** Part of the public facade return type [io.explico.RenderedDocs]. */
public data class CoverageSummary(val rendered: Int, val total: Int) {
    public val percent: Int get() = if (total == 0) 100 else (rendered * 100) / total
}

internal object Coverage {

    /** Numerator: rendered conditions. Denominator: all conditions (spec §6.6). */
    fun of(conditions: List<Condition>): CoverageSummary =
        CoverageSummary(conditions.count { it !is Condition.Unrendered }, conditions.size)

    fun of(rule: RuleGroup): CoverageSummary = of(conditionsOf(rule))

    fun of(pkg: PolicyPackage): CoverageSummary = of(pkg.rules.flatMap { conditionsOf(it) })

    /** Operand-level fallbacks don't count against coverage but are reported separately (spec §6.6). */
    fun unrenderedOperandCount(conditions: List<Condition>): Int =
        conditions.sumOf { operandsOf(it).count { operand -> operand is Operand.Unrendered } }

    fun unrenderedOperandCount(rule: RuleGroup): Int = unrenderedOperandCount(conditionsOf(rule))

    private fun conditionsOf(rule: RuleGroup): List<Condition> = rule.bodies.flatMap { it.conditions }

    /** Exposed (not private) so §6.7's referenced-path collection can reuse this instead of a third copy. */
    fun operandsOf(condition: Condition): List<Operand> = when (condition) {
        is Condition.Comparison -> listOf(condition.left, condition.right)
        is Condition.Membership -> listOf(condition.member, condition.collection)
        is Condition.Truthy -> listOf(condition.operand)
        is Condition.BuiltinCall -> condition.args
        is Condition.SomeIn -> listOf(condition.collection)
        is Condition.RuleReference -> emptyList()
        is Condition.Unrendered -> emptyList()
    }
}
