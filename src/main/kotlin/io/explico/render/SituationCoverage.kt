/**
 * Spec §14.77: which SITUATIONS (bodies) of a control no worked example demonstrates. The control-level "No matching example" finding passes a control as soon as one fixture
 * matches it, so a situation nobody triggers (a newly added body, say) is invisible. This answers it from the same outcomes the cards print, and ONLY when it can be claimed.
 */
package io.explico.render

import io.explico.model.RuleGroup

internal object SituationCoverage {

    /**
     * The situation numbers (1-based, ascending) of [rule] that no matched outcome in [outcomes] is attributed to, or null when nothing can be claimed: fewer than two
     * bodies; a function, a split-default entry or an `else` chain (its branches are ordered, not alternative situations); no fixture matches at all (that is "No matching
     * example"); or any matched outcome whose messages are not ALL attributed to a situation -- no messages (a boolean rule) or a message no body template accounts for --
     * or whose attribution is ambiguous (a message more than one body's template matches, [WorkedExamples.attributesUnambiguously]: the first match would credit it to the wrong situation).
     * [outcomes] are the card's own (`WorkedExamples.forGroup`), over every fixture, not the few a card displays.
     */
    fun uncovered(rule: RuleGroup, outcomes: List<WorkedExample>): List<Int>? {
        if (rule.bodies.size < 2 || rule.parameters.isNotEmpty() || rule.isSplitDefaultEntry || rule.bodies.any { it.elseBranches.isNotEmpty() }) return null
        val matched = outcomes.filter { it.matched }
        if (matched.isEmpty()) return null
        if (matched.any { it.messages.isEmpty() || it.situationLabels.any { label -> label == null } }) return null
        // The label is the FIRST body whose template matches: with overlapping templates that can credit a message to the wrong situation, so nothing is claimed.
        if (!WorkedExamples.attributesUnambiguously(rule, matched.flatMap { it.messages })) return null
        val covered = matched.flatMap { it.situationLabels }.filterNotNull().toSet()
        return (1..rule.bodies.size).filter { it !in covered }.takeIf { it.isNotEmpty() }
    }
}
