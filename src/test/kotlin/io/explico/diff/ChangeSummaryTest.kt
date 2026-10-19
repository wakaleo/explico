/**
 * Unit tests for [ChangeSummary] (spec §14.42): the diff report's `## What changed` section. Hand-built domain
 * objects -- no opa needed; the real-fixture shapes (diff-all-categories, change-summary) are covered by
 * [io.explico.acceptance.RealisticDiffChangeSummaryAcceptanceIT].
 */
package io.explico.diff

import io.explico.model.Condition
import io.explico.model.ConstantValue
import io.explico.model.DefaultValue
import io.explico.model.Operand
import io.explico.model.Operator
import io.explico.model.PathSegment
import io.explico.model.PolicyPackage
import io.explico.model.RuleBody
import io.explico.model.RuleGroup
import io.explico.model.RuleMetadata
import io.explico.model.SourceRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ChangeSummaryTest {

    private fun path(vararg names: String) = Operand.Path(names.map { PathSegment.Field(it) })
    private fun body(conditions: List<Condition>, producesValue: String? = null) =
        RuleBody(conditions, producesValue, producesValue, SourceRef("f.rego", 1), "")
    private fun rule(
        name: String = "deny",
        controlId: String? = "C-1",
        title: String? = "Title",
        bodies: List<RuleBody> = emptyList(),
        default: DefaultValue? = null,
        constantValue: ConstantValue? = null,
    ) = RuleGroup(name, RuleMetadata(title, "d", controlId, emptyList()), default, bodies, constantValue = constantValue)
    private fun pkg(path: String, rule: RuleGroup) = PolicyPackage(path, listOf(rule), listOf("f.rego"))
    private fun entry(category: DiffCategory, controlId: String?, oldPkg: PolicyPackage?, oldRule: RuleGroup?, newPkg: PolicyPackage?, newRule: RuleGroup?) =
        DiffEntry(category, controlId, oldPkg, oldRule, newPkg, newRule)

    @Nested
    inner class Bullets {

        @Test
        fun removedBulletLinksToTheOldCardsAnchor() {
            val oldRule = rule(controlId = "REL-001", title = "Production change approval")
            val e = entry(DiffCategory.REMOVED, "REL-001", pkg("release.approvals", oldRule), oldRule, null, null)

            assertThat(ChangeSummary.bulletFor(e)).isEqualTo("- [REL-001 — Production change approval](#rel-001) — removed")
        }

        @Test
        fun addedBulletLinksToTheNewCardsAnchor() {
            val newRule = rule(controlId = "REL-005", title = "New deployment window check")
            val e = entry(DiffCategory.ADDED, "REL-005", null, null, pkg("release.newcheck", newRule), newRule)

            assertThat(ChangeSummary.bulletFor(e)).isEqualTo("- [REL-005 — New deployment window check](#rel-005) — added")
        }

        @Test
        fun logicChangedBulletLinksToTheNewCardsAnchor() {
            val newRule = rule(controlId = "REL-003", title = "Artifact provenance")
            val e = entry(DiffCategory.LOGIC_CHANGED, "REL-003", null, newRule, pkg("release.provenance", newRule), newRule)

            assertThat(ChangeSummary.bulletFor(e)).isEqualTo("- [REL-003 — Artifact provenance](#rel-003) — logic changed")
        }

        @Test
        fun docsChangedBulletIsPlainTextNamingExactlyTheFieldsThatDiffer() {
            val oldRule = rule(controlId = "REL-004", title = "Release governance evidence")
            val newRule = oldRule.copy(metadata = oldRule.metadata!!.copy(description = "d2"))
            val e = entry(DiffCategory.DOCS_CHANGED, "REL-004", pkg("release.governance", oldRule), oldRule, pkg("release.governance", newRule), newRule)

            assertThat(ChangeSummary.bulletFor(e)).isEqualTo("- REL-004 — Release governance evidence — documentation changed: description")
        }

        @Test
        fun docsChangedListsTitleDescriptionAndFrameworksInThatOrder() {
            val oldRule = rule(controlId = "C-1", title = "A").copy(metadata = RuleMetadata("A", "d1", "C-1", listOf("F1")))
            val newRule = oldRule.copy(metadata = RuleMetadata("B", "d2", "C-1", listOf("F1", "F2")))
            val e = entry(DiffCategory.DOCS_CHANGED, "C-1", pkg("p", oldRule), oldRule, pkg("p", newRule), newRule)

            assertThat(ChangeSummary.bulletFor(e)).isEqualTo("- C-1 — B — documentation changed: title, description, frameworks")
        }

        @Test
        fun aRuleWithNoControlIdIsLabelledByPackageAndRuleName() {
            val oldRule = rule(controlId = null, title = null, bodies = listOf(body(listOf(Condition.Truthy(path("input", "x"), false)))))
            val newRule = rule(controlId = null, title = null, bodies = listOf(body(listOf(Condition.Truthy(path("input", "y"), false)))))
            val e = entry(DiffCategory.LOGIC_CHANGED, null, pkg("release.exemptions", oldRule), oldRule, pkg("release.exemptions", newRule), newRule)

            assertThat(ChangeSummary.bulletFor(e)).isEqualTo("- [release.exemptions.deny](#release-exemptions-deny) — logic changed")
        }
    }

    @Nested
    inner class LogicChangeSubBullets {

        private fun comparisonTo(value: String) = Condition.Comparison(path("input", "x"), Operator.EQ, Operand.Literal("\"$value\""))

        @Test
        fun aChangedConditionIsRemovedThenAdded() {
            val oldRule = rule(bodies = listOf(body(listOf(comparisonTo("old")))))
            val newRule = rule(bodies = listOf(body(listOf(comparisonTo("new")))))
            val e = entry(DiffCategory.LOGIC_CHANGED, "C-1", pkg("p", oldRule), oldRule, pkg("p", newRule), newRule)

            assertThat(ChangeSummary.subBulletsFor(e)).containsExactly(
                "condition removed: `x` is `\"old\"`",
                "condition added: `x` is `\"new\"`",
            )
        }

        @Test
        fun aGainedConditionIsAddedOnlyNothingRemoved() {
            val oldRule = rule(bodies = listOf(body(listOf(comparisonTo("x")))))
            val newRule = rule(bodies = listOf(body(listOf(comparisonTo("x"), comparisonTo("y")))))
            val e = entry(DiffCategory.LOGIC_CHANGED, "C-1", pkg("p", oldRule), oldRule, pkg("p", newRule), newRule)

            assertThat(ChangeSummary.subBulletsFor(e)).containsExactly("condition added: `x` is `\"y\"`")
        }

        @Test
        fun unrenderedConditionsAreNeverRenderedAsPhrasesAndDoNotCountAsRemovedOrAdded() {
            val oldRule = rule(bodies = listOf(body(listOf(Condition.Unrendered("every x in y {}", "unclassified")))))
            val newRule = rule(bodies = listOf(body(listOf(Condition.Unrendered("every x in z {}", "unclassified")))))
            val e = entry(DiffCategory.LOGIC_CHANGED, "C-1", pkg("p", oldRule), oldRule, pkg("p", newRule), newRule)

            assertThat(ChangeSummary.subBulletsFor(e)).containsExactly("code shown as source changed (see the source diff below)")
        }

        @Test
        fun aChangedMessageIsRemovedThenAdded() {
            val oldRule = rule(bodies = listOf(body(listOf(comparisonTo("x")), producesValue = "old message")))
            val newRule = rule(bodies = listOf(body(listOf(comparisonTo("x")), producesValue = "new message")))
            val e = entry(DiffCategory.LOGIC_CHANGED, "C-1", pkg("p", oldRule), oldRule, pkg("p", newRule), newRule)

            assertThat(ChangeSummary.subBulletsFor(e)).containsExactly(
                "message removed: \"old message\"",
                "message added: \"new message\"",
            )
        }

        @Test
        fun aNullProducesValueNeverProducesAMessageBullet() {
            val oldRule = rule(bodies = listOf(body(listOf(comparisonTo("x")), producesValue = null)))
            val newRule = rule(bodies = listOf(body(listOf(comparisonTo("x"), comparisonTo("y")), producesValue = null)))
            val e = entry(DiffCategory.LOGIC_CHANGED, "C-1", pkg("p", oldRule), oldRule, pkg("p", newRule), newRule)

            assertThat(ChangeSummary.subBulletsFor(e)).containsExactly("condition added: `x` is `\"y\"`")
        }

        @Test
        fun aGainedBodyIsReportedAsABodyCountChange() {
            val oldRule = rule(bodies = listOf(body(listOf(comparisonTo("a")), producesValue = "a")))
            val newRule = rule(bodies = listOf(body(listOf(comparisonTo("a")), producesValue = "a"), body(listOf(comparisonTo("b")), producesValue = "b")))
            val e = entry(DiffCategory.LOGIC_CHANGED, "C-1", pkg("p", oldRule), oldRule, pkg("p", newRule), newRule)

            assertThat(ChangeSummary.subBulletsFor(e)).containsExactly(
                "condition added: `x` is `\"b\"`",
                "message added: \"b\"",
                "bodies: 1 -> 2",
            )
        }

        @Test
        fun aChangedDefaultOutcome() {
            val oldRule = rule(bodies = listOf(body(listOf(comparisonTo("a")))), default = DefaultValue("false", SourceRef("f.rego", 1)))
            val newRule = rule(bodies = listOf(body(listOf(comparisonTo("a")))), default = DefaultValue("true", SourceRef("f.rego", 1)))
            val e = entry(DiffCategory.LOGIC_CHANGED, "C-1", pkg("p", oldRule), oldRule, pkg("p", newRule), newRule)

            assertThat(ChangeSummary.subBulletsFor(e)).containsExactly("default outcome changed")
        }

        @Test
        fun aDefaultThatAppearsOnOnlyOneSideStillCountsAsChanged() {
            val oldRule = rule(bodies = listOf(body(listOf(comparisonTo("a")))), default = null)
            val newRule = rule(bodies = listOf(body(listOf(comparisonTo("a")))), default = DefaultValue("true", SourceRef("f.rego", 1)))
            val e = entry(DiffCategory.LOGIC_CHANGED, "C-1", pkg("p", oldRule), oldRule, pkg("p", newRule), newRule)

            assertThat(ChangeSummary.subBulletsFor(e)).containsExactly("default outcome changed")
        }

        @Test
        fun aChangedConstantValue() {
            val oldRule = rule(bodies = emptyList(), constantValue = ConstantValue("10", SourceRef("f.rego", 1)))
            val newRule = rule(bodies = emptyList(), constantValue = ConstantValue("20", SourceRef("f.rego", 1)))
            val e = entry(DiffCategory.LOGIC_CHANGED, "C-1", pkg("p", oldRule), oldRule, pkg("p", newRule), newRule)

            assertThat(ChangeSummary.subBulletsFor(e)).containsExactly("value changed")
        }

        @Test
        @DisplayName("the same conditions regrouped across bodies yields the honest structure-only fallback, never a guess")
        fun structureOnlyChangeFallsBackToTheHonestSentence() {
            val oldRule = rule(
                bodies = listOf(
                    body(listOf(comparisonTo("p"), comparisonTo("q")), producesValue = "first"),
                    body(listOf(comparisonTo("r")), producesValue = "second"),
                ),
            )
            val newRule = rule(
                bodies = listOf(
                    body(listOf(comparisonTo("p"), comparisonTo("r")), producesValue = "first"),
                    body(listOf(comparisonTo("q")), producesValue = "second"),
                ),
            )
            val e = entry(DiffCategory.LOGIC_CHANGED, "C-1", pkg("p", oldRule), oldRule, pkg("p", newRule), newRule)

            assertThat(ChangeSummary.subBulletsFor(e)).containsExactly("the rule's structure changed; see the source diff below")
        }

        @Test
        fun aMultisetOfUnrenderedSourceTextsThatIsMerelyReorderedIsNotReportedAsChanged() {
            val oldRule = rule(bodies = listOf(body(listOf(Condition.Unrendered("a", "r"), Condition.Unrendered("b", "r")))))
            val newRule = rule(bodies = listOf(body(listOf(Condition.Unrendered("b", "r"), Condition.Unrendered("a", "r")))))
            val e = entry(DiffCategory.LOGIC_CHANGED, "C-1", pkg("p", oldRule), oldRule, pkg("p", newRule), newRule)

            assertThat(ChangeSummary.subBulletsFor(e)).containsExactly("the rule's structure changed; see the source diff below")
        }

        @Test
        @DisplayName("CHALLENGE: a RuleReference/FunctionCall condition difference renders unlinked, never crashes")
        fun ruleReferenceAndFunctionCallConditionsRenderWithoutLinksOrCrashing() {
            val oldRule = rule(bodies = listOf(body(listOf(Condition.RuleReference("p", "is_exempt", negated = true)))))
            val newRule = rule(bodies = listOf(body(listOf(Condition.FunctionCall("p", "check", listOf(path("input", "x")), negated = false)))))
            val e = entry(DiffCategory.LOGIC_CHANGED, "C-1", pkg("p", oldRule), oldRule, pkg("p", newRule), newRule)

            val bullets = ChangeSummary.subBulletsFor(e)
            assertThat(bullets).hasSize(2)
            assertThat(bullets[0]).startsWith("condition removed: ")
            assertThat(bullets[1]).startsWith("condition added: ")
            assertThat(bullets.joinToString("\n")).doesNotContain("](")
        }

        @Test
        @DisplayName("CHALLENGE: several simultaneous changes each get their own bullet, in the documented order")
        fun severalSimultaneousChangesAreAllReportedInOrder() {
            val oldRule = rule(
                bodies = listOf(body(listOf(comparisonTo("a")), producesValue = "old msg")),
                default = DefaultValue("false", SourceRef("f.rego", 1)),
            )
            val newRule = rule(
                bodies = listOf(
                    body(listOf(comparisonTo("b")), producesValue = "new msg"),
                    body(listOf(Condition.Unrendered("every x in y {}", "unclassified"))),
                ),
                default = DefaultValue("true", SourceRef("f.rego", 1)),
            )
            val e = entry(DiffCategory.LOGIC_CHANGED, "C-1", pkg("p", oldRule), oldRule, pkg("p", newRule), newRule)

            assertThat(ChangeSummary.subBulletsFor(e)).containsExactly(
                "condition removed: `x` is `\"a\"`",
                "condition added: `x` is `\"b\"`",
                "message removed: \"old msg\"",
                "message added: \"new msg\"",
                "bodies: 1 -> 2",
                "default outcome changed",
                "code shown as source changed (see the source diff below)",
            )
        }

        @Test
        fun nonLogicChangedEntriesHaveNoSubBullets() {
            val oldRule = rule()
            val e = entry(DiffCategory.REMOVED, "C-1", pkg("p", oldRule), oldRule, null, null)

            assertThat(ChangeSummary.subBulletsFor(e)).isEmpty()
        }
    }
}
