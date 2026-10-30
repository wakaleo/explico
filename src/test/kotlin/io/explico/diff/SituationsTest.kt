/**
 * Spec §14.59: the situation-aware sub-bullets of a LOGIC_CHANGED rule, on hand-built domain objects (no opa). The real-policy report is pinned by
 * RealisticDiffSituationsAcceptanceIT.
 */
package io.explico.diff

import io.explico.model.Condition
import io.explico.model.ElseBranch
import io.explico.model.Operand
import io.explico.model.Operator
import io.explico.model.PathSegment
import io.explico.model.RuleBody
import io.explico.model.RuleGroup
import io.explico.model.SourceRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class SituationsTest {

    private fun cond(field: String, value: String = "1") = Condition.Comparison(Operand.Path(listOf(PathSegment.Field("input"), PathSegment.Field(field))), Operator.EQ, Operand.Literal(value))
    private fun lit(text: String) = Operand.Literal("\"$text\"")

    /** A branch: [conditions] by field name (none = the closing branch), yielding [value] (null: a boolean rule). */
    private class Branch(val fields: List<String>, val value: String?)
    private fun branch(value: String?, vararg fields: String) = Branch(fields.toList(), value)

    private fun body(conditions: List<Condition>, value: Operand?, elseBranches: List<ElseBranch> = emptyList()) =
        RuleBody(conditions, null, null, SourceRef("f.rego", 1), "", yieldsValue = value, elseBranches = elseBranches)

    private fun chain(vararg branches: Branch): RuleGroup {
        val first = branches.first()
        val rest = branches.drop(1).map { b -> ElseBranch(b.fields.map { cond(it) }, lit(b.value!!), SourceRef("f.rego", 2)) }
        return RuleGroup("r", null, null, listOf(body(first.fields.map { cond(it) }, lit(first.value!!), rest)))
    }

    /** Several definitions of one name, each its own body. */
    private fun definitions(vararg branches: Branch) =
        RuleGroup("r", null, null, branches.map { b -> body(b.fields.map { cond(it) }, b.value?.let { lit(it) }) })

    private fun bullets(old: RuleGroup, new: RuleGroup) = Situations.bullets(old, new)

    private val a = branch("A", "a")
    private val b = branch("B", "b")
    private val c = branch("C", "c")
    private val closing = branch("Z")

    @Test
    @DisplayName("two branches swapped: one reordered line in terms of the old numbers, 'otherwise' for the closing branch")
    fun reordered() {
        assertThat(bullets(chain(a, b, closing), chain(b, a, closing))).containsExactly("situations reordered: now in the order of the old situations 2, 1, otherwise")
    }

    @Test
    @DisplayName("a three-way rotation names every old number")
    fun threeWay() {
        assertThat(bullets(chain(a, b, c, closing), chain(c, a, b, closing))).containsExactly("situations reordered: now in the order of the old situations 3, 1, 2, otherwise")
    }

    @Test
    @DisplayName("an identical chain says nothing")
    fun identical() {
        assertThat(bullets(chain(a, b, closing), chain(a, b, closing))).isEmpty()
    }

    @Test
    @DisplayName("a changed value is named by the NEW situation number; the closing branch is 'otherwise'")
    fun valueChanged() {
        assertThat(bullets(chain(a, b, closing), chain(a, branch("B2", "b"), branch("Z2")))).containsExactly(
            "situation 2: value changed from `\"B\"` to `\"B2\"`",
            "otherwise: value changed from `\"Z\"` to `\"Z2\"`",
        )
    }

    @Test
    @DisplayName("a reorder that also changes a value reports both, reorder first")
    fun reorderAndValue() {
        assertThat(bullets(chain(a, b, closing), chain(b, branch("A2", "a"), closing))).containsExactly(
            "situations reordered: now in the order of the old situations 2, 1, otherwise",
            "situation 2: value changed from `\"A\"` to `\"A2\"`",
        )
    }

    @Test
    @DisplayName("a reorder that is also a removal: the matched situations' new order, then the removal")
    fun reorderAndRemoval() {
        assertThat(bullets(chain(a, b, c, closing), chain(b, a, closing))).containsExactly(
            "situations reordered: now in the order of the old situations 2, 1, otherwise",
            "situation 3 removed (was: then `\"C\"`)",
        )
    }

    @Test
    @DisplayName("a branch with other conditions AND another value is removed + added, numbered in the old / new version")
    fun replaced() {
        assertThat(bullets(chain(a, b, closing), chain(a, branch("D", "d"), closing))).containsExactly(
            "situation 2 removed (was: then `\"B\"`)",
            "situation 2 added (then `\"D\"`)",
        )
    }

    @Test
    @DisplayName("a branch whose conditions changed but whose value did not is not removed + added: the condition lines name it")
    fun rewordedBranch() {
        assertThat(bullets(chain(a, b, closing), chain(a, branch("B", "d"), closing))).isEmpty()
    }

    @Test
    @DisplayName("the same branch twice pairs first with first, second with second")
    fun duplicateKeys() {
        assertThat(bullets(chain(a, a, closing), chain(a, branch("A2", "a"), closing))).containsExactly("situation 2: value changed from `\"A\"` to `\"A2\"`")
    }

    @Test
    @DisplayName("sibling definitions are numbered as the card numbers them; reordering them says nothing (the order is not semantic), a changed value does")
    fun siblingDefinitions() {
        assertThat(bullets(definitions(branch("one", "a"), branch("two", "b")), definitions(branch("two", "b"), branch("one", "a")))).isEmpty()
        assertThat(bullets(definitions(branch("one", "a"), branch("two", "b")), definitions(branch("one", "a"), branch("three", "b")))).containsExactly("situation 2: value changed from `\"two\"` to `\"three\"`")
    }

    @Test
    @DisplayName("a rule with one body: 'value changed' with no number, even when its condition changed too")
    fun singleBody() {
        assertThat(bullets(definitions(branch("10", "a")), definitions(branch("20", "x")))).containsExactly("value changed from `\"10\"` to `\"20\"`")
    }

    @Test
    @DisplayName("a boolean rule (no value): no value, removed or added lines -- the condition and body-count lines already name it")
    fun booleanRules() {
        assertThat(bullets(definitions(branch(null, "a")), definitions(branch(null, "a"), branch(null, "b")))).isEmpty()
        assertThat(bullets(definitions(branch(null, "a"), branch(null, "b")), definitions(branch(null, "c"), branch(null, "d")))).isEmpty()
    }

    @Test
    @DisplayName("a value on only one side is not a value change")
    fun valueOnOneSide() {
        assertThat(bullets(definitions(branch(null, "a")), definitions(branch("v", "a")))).isEmpty()
    }

    @Test
    @DisplayName("an added definition WITH a value is named")
    fun addedDefinition() {
        assertThat(bullets(definitions(branch("one", "a")), definitions(branch("one", "a"), branch("two", "b")))).containsExactly("situation 2 added (then `\"two\"`)")
    }

    @Test
    @DisplayName("a chain on one side and a single body on the other: no reorder line; the lost branches are removed")
    fun chainVersusSingle() {
        val single = definitions(branch("A", "a"))
        assertThat(bullets(chain(a, b, closing), single)).containsExactly("situation 2 removed (was: then `\"B\"`)", "otherwise removed (was: then `\"Z\"`)")
    }

    @Test
    @DisplayName("an Unrendered condition keys its branch by its (trimmed) source: the same source pairs and so reorders; a different one is a different branch")
    fun unrenderedKey() {
        fun withUnrendered(source: String, vararg rest: Branch): RuleGroup {
            val first = body(listOf(Condition.Unrendered(source, "x")), lit("U"), rest.map { b -> ElseBranch(b.fields.map { cond(it) }, lit(b.value!!), SourceRef("f.rego", 2)) })
            return RuleGroup("r", null, null, listOf(first))
        }
        // old: [U(source) -> U, b -> B]; new: [b -> B, U(source, padded) -> U] -- the padded source is the same branch
        val old = withUnrendered("with x", b)
        val reordered = RuleGroup("r", null, null, listOf(body(listOf(cond("b")), lit("B"), listOf(ElseBranch(listOf(Condition.Unrendered("  with x ", "x")), lit("U"), SourceRef("f.rego", 2))))))
        assertThat(bullets(old, reordered)).containsExactly("situations reordered: now in the order of the old situations 2, 1")
        val different = RuleGroup("r", null, null, listOf(body(listOf(cond("b")), lit("B"), listOf(ElseBranch(listOf(Condition.Unrendered("with y", "x")), lit("U"), SourceRef("f.rego", 2))))))
        // Same value, other conditions: a reworded branch (the source-changed line names it), not a removal.
        assertThat(bullets(old, different)).isEmpty()
        val differentAndRevalued = RuleGroup("r", null, null, listOf(body(listOf(cond("b")), lit("B"), listOf(ElseBranch(listOf(Condition.Unrendered("with y", "x")), lit("V"), SourceRef("f.rego", 2))))))
        assertThat(bullets(old, differentAndRevalued)).containsExactly("situation 1 removed (was: then `\"U\"`)", "situation 2 added (then `\"V\"`)")
    }

    @Test
    @DisplayName("a value is worded verbatim, backticks and quotes included")
    fun verbatimValues() {
        val old = RuleGroup("r", null, null, listOf(body(listOf(cond("a")), Operand.Literal("\"say `hi`\""))))
        val new = RuleGroup("r", null, null, listOf(body(listOf(cond("a")), Operand.Literal("\"say \\\"bye\\\"\""))))
        assertThat(bullets(old, new)).containsExactly("value changed from `\"say `hi`\"` to `\"say \\\"bye\\\"\"`")
    }

    @Test
    @DisplayName("a rule with no bodies has no situations")
    fun noBodies() {
        assertThat(bullets(RuleGroup("r", null, null, emptyList()), chain(a, closing))).isEmpty()
    }

    @Test
    @DisplayName("deterministic: the same input gives the same lines")
    fun deterministic() {
        assertThat(bullets(chain(a, b, c, closing), chain(c, a, branch("D", "d"), closing))).isEqualTo(bullets(chain(a, b, c, closing), chain(c, a, branch("D", "d"), closing)))
    }
}
