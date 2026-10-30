/**
 * Spec §14.57: `diff` with `groupByControl` on hand-built domain objects (no opa) -- identity decided per id over BOTH versions, the boundaries of that rule,
 * and how a shared id is worded in the report. The real-policy behaviour is pinned by RealisticGroupByControlDiffAcceptanceIT.
 */
package io.explico.diff

import io.explico.model.Condition
import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RuleBody
import io.explico.model.RuleGroup
import io.explico.model.RuleMetadata
import io.explico.model.SourceRef
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class GroupedDiffTest {

    /** A rule whose logic is its [message] (a different message is a different hash, so LOGIC_CHANGED); [title] differs only in documentation. */
    private fun rule(name: String, id: String?, message: String = "m", title: String? = null, vararg refs: Condition) =
        RuleGroup(name, id?.let { RuleMetadata(title, null, it, emptyList()) }, null, listOf(RuleBody(refs.toList(), null, message, SourceRef("$name.rego", 1), "$name if $message")))

    private fun set(vararg packages: Pair<String, List<RuleGroup>>) = PolicySet(packages.map { (path, rules) -> PolicyPackage(path, rules, listOf("f.rego")) })

    private fun diff(old: PolicySet, new: PolicySet, group: Boolean = true) = PolicyDiff.diff(old, new, group)

    private fun summary(entries: List<DiffEntry>) = entries.map { (it.newRule ?: it.oldRule)!!.name to it.category }

    @Test
    @DisplayName("without grouping a shared id still throws, naming it; with grouping it does not")
    fun theOptionRelaxesTheCheck() {
        val shared = set("p" to listOf(rule("a", "X"), rule("b", "X")))
        assertThatThrownBy { diff(shared, shared, group = false) }.isInstanceOf(DuplicateControlIdException::class.java).hasMessageContaining("X")
        assertThat(diff(shared, shared).map { it.category }).containsExactly(DiffCategory.UNCHANGED, DiffCategory.UNCHANGED)
    }

    @Test
    @DisplayName("an id shared only in the OLD version: the rule it keeps matches, the other is REMOVED")
    fun sharedOnlyInOld() {
        val entries = diff(set("p" to listOf(rule("a", "X"), rule("b", "X"))), set("p" to listOf(rule("a", "X"))))
        assertThat(summary(entries)).containsExactlyInAnyOrder("a" to DiffCategory.UNCHANGED, "b" to DiffCategory.REMOVED)
    }

    @Test
    @DisplayName("an id shared only in the NEW version: the rule it had matches, the other is ADDED")
    fun sharedOnlyInNew() {
        val entries = diff(set("p" to listOf(rule("a", "X"))), set("p" to listOf(rule("a", "X"), rule("b", "X"))))
        assertThat(summary(entries)).containsExactlyInAnyOrder("a" to DiffCategory.UNCHANGED, "b" to DiffCategory.ADDED)
    }

    @Test
    @DisplayName("a rule renamed inside a shared control is REMOVED + ADDED -- never guessed to be the same rule")
    fun renameInsideASharedControl() {
        val entries = diff(set("p" to listOf(rule("a", "X"), rule("b", "X"))), set("p" to listOf(rule("a", "X"), rule("c", "X"))))
        assertThat(summary(entries)).containsExactlyInAnyOrder("a" to DiffCategory.UNCHANGED, "b" to DiffCategory.REMOVED, "c" to DiffCategory.ADDED)
    }

    @Test
    @DisplayName("a rule moved to another package inside a shared control is REMOVED + ADDED")
    fun movedBetweenPackages() {
        val entries = diff(set("p" to listOf(rule("a", "X"), rule("b", "X"))), set("p" to listOf(rule("a", "X")), "q" to listOf(rule("b", "X"))))
        assertThat(entries.filter { it.controlId == "X" }.map { it.category to (it.newPackage ?: it.oldPackage)!!.path })
            .containsExactlyInAnyOrder(DiffCategory.UNCHANGED to "p", DiffCategory.REMOVED to "p", DiffCategory.ADDED to "q")
    }

    @Test
    @DisplayName("two rules of one name and one shared id in one package cannot be told apart: still an error with grouping, naming the id and the rule")
    fun identicalIdentityTwice() {
        val twin = set("p" to listOf(rule("r", "X", "one"), rule("r", "X", "two")))
        assertThatThrownBy { diff(twin, twin) }.isInstanceOf(DuplicateControlIdException::class.java).hasMessageContaining("X").hasMessageContaining("p.r")
    }

    @Test
    @DisplayName("ids that differ only by case are different controls")
    fun caseDiffers() {
        val entries = diff(set("p" to listOf(rule("a", "x-1"), rule("b", "X-1"))), set("p" to listOf(rule("a", "x-1"), rule("b", "X-1", message = "changed"))))
        assertThat(summary(entries)).containsExactlyInAnyOrder("a" to DiffCategory.UNCHANGED, "b" to DiffCategory.LOGIC_CHANGED)
        assertThat(ChangeSummary.qualifiedIds(entries)).isEmpty()
    }

    @Test
    @DisplayName("a '|' in an id cannot make two identities collide: control A (rules p.r and p.s) and a control literally called 'A|p|r' stay three entries")
    fun pipeInAnId() {
        val policy = set("p" to listOf(rule("r", "A"), rule("s", "A"), rule("t", "A|p|r")))
        assertThat(diff(policy, policy)).hasSize(3)
    }

    @Test
    @DisplayName("a control id that reads like another rule's package.rule does not collide with it: both are diffed, in the old and the new version alike")
    fun controlIdLikeAPackageRule() {
        val policy = set("release" to listOf(rule("deny", null), rule("other", "release.deny")))
        val entries = diff(policy, policy)
        assertThat(entries).hasSize(2)
        assertThat(entries.map { it.controlId }).containsExactlyInAnyOrder(null, "release.deny")
        val changed = set("release" to listOf(rule("deny", null, message = "changed"), rule("other", "release.deny")))
        assertThat(diff(policy, changed).single { it.category == DiffCategory.LOGIC_CHANGED }.controlId).isNull()
    }

    @Test
    @DisplayName("an id-less rule named like a shared control's rule is a different entry")
    fun idLessRuleWithTheSameName() {
        val policy = set("p" to listOf(rule("r", "A"), rule("s", "A")), "q" to listOf(rule("r", null)))
        assertThat(diff(policy, policy)).hasSize(3)
    }

    @Test
    @DisplayName("a single-rule control keeps the plain identity: renaming its package and rule is still ONE entry")
    fun singleRuleRename() {
        val entries = diff(set("p" to listOf(rule("a", "S"))), set("q" to listOf(rule("b", "S"))))
        assertThat(entries).hasSize(1)
        assertThat(entries.single().category).isEqualTo(DiffCategory.UNCHANGED)
    }

    @Test
    @DisplayName("entries come out in a deterministic order whatever order the packages and rules were read in")
    fun orderIsDeterministic() {
        val one = set("p" to listOf(rule("b", "X"), rule("a", "X")), "q" to listOf(rule("c", "X")))
        val two = set("q" to listOf(rule("c", "X")), "p" to listOf(rule("a", "X"), rule("b", "X")))
        assertThat(diff(one, one).map { it.newPackage!!.path to it.newRule!!.name }).isEqualTo(diff(two, two).map { it.newPackage!!.path to it.newRule!!.name })
    }

    @Test
    @DisplayName("an id is qualified only when it names several entries; a single-rule control and an id-less rule are worded as before")
    fun wording() {
        val old = set("p" to listOf(rule("a", "X", title = "Old"), rule("b", "X"), rule("s", "S"), rule("n", null)))
        val new = set("p" to listOf(rule("a", "X", title = "New"), rule("b", "X"), rule("s", "S", "changed"), rule("n", null, "changed")))
        val entries = diff(old, new)
        val qualified = ChangeSummary.qualifiedIds(entries)
        assertThat(qualified).containsExactly("X")
        val docs = entries.single { it.category == DiffCategory.DOCS_CHANGED }
        assertThat(ChangeSummary.bulletFor(docs, qualified)).isEqualTo("- X — New (p.a) — documentation changed: title")
        assertThat(ChangeSummary.bulletFor(entries.single { it.controlId == "S" }, qualified)).startsWith("- [S — s](#s)")
        assertThat(ChangeSummary.plainLabelOf(entries.single { it.controlId == null && it.category == DiffCategory.LOGIC_CHANGED }, qualified)).isEqualTo("p.n")
    }

    @Test
    @DisplayName("the report tells shared-id cards apart: rule-qualified headings and anchors, one anchor each; a card links across packages with a bare #anchor, to the target's qualified anchor")
    fun report() {
        val ref = Condition.RuleReference("q", "b", negated = false)
        val old = set("p" to listOf(rule("a", "X", "one", null, ref)), "q" to listOf(rule("b", "X")))
        val new = set("p" to listOf(rule("a", "X", "two", null, ref)), "q" to listOf(rule("b", "X", "changed")))
        val markdown = DiffRenderer.render(diff(old, new), old, new)
        assertThat(markdown.lines().filter { it.startsWith("## X") }).containsExactlyInAnyOrder("## X — a (p.a)", "## X — b (q.b)")
        val anchors = Regex("<a id=\"([^\"]*)\"></a>").findAll(markdown).map { it.groupValues[1] }.toList()
        assertThat(anchors).containsExactlyInAnyOrder("x-p-a", "x-q-b").doesNotHaveDuplicates()
        assertThat(markdown).contains("[`b`](#x-q-b)").doesNotContain(".md#")
    }

    @Test
    @DisplayName("a reference to a shared-id rule that has no card in the report (unchanged) stays unlinked")
    fun referenceToUncardedRule() {
        val ref = Condition.RuleReference("q", "b", negated = false)
        val old = set("p" to listOf(rule("a", "X", "one", null, ref)), "q" to listOf(rule("b", "X")))
        val new = set("p" to listOf(rule("a", "X", "two", null, ref)), "q" to listOf(rule("b", "X")))
        val markdown = DiffRenderer.render(diff(old, new), old, new)
        assertThat(markdown).doesNotContain("](#x-q-b)").contains("`b`")
    }

    @Test
    @DisplayName("everything unchanged: no change sections at all")
    fun nothingChanged() {
        val policy = set("p" to listOf(rule("a", "X"), rule("b", "X")))
        val markdown = DiffRenderer.render(diff(policy, policy), policy, policy)
        assertThat(markdown).doesNotContain("## What changed").doesNotContain("## Changes").contains("| UNCHANGED | 2 |")
    }

    @Test
    @DisplayName("--fail-on lists each failing rule of a shared control once, named by id and package.rule; a single-rule control stays by id")
    fun gateLabels() {
        val old = set("p" to listOf(rule("a", "X"), rule("b", "X"), rule("s", "S")))
        val new = set("p" to listOf(rule("a", "X", "x1"), rule("b", "X", "x2"), rule("s", "S", "x3")))
        val message = io.explico.cli.Gates.failOn(diff(old, new), listOf(DiffCategory.LOGIC_CHANGED))
        assertThat(message).endsWith("3 control(s): S, X (p.a), X (p.b)")
    }

    @Test
    @DisplayName("two rules whose names slug to one anchor: the first claims it, the other has no anchor (no duplicate id, plain bullet, null in the JSON)")
    fun slugCollisionKeepsAnchorsUnique() {
        val old = set("p" to listOf(rule("a_b", null, "one"), rule("a-b", null, "one")))
        val new = set("p" to listOf(rule("a_b", null, "two"), rule("a-b", null, "two")))
        val entries = diff(old, new)
        val markdown = DiffRenderer.render(entries, old, new)
        assertThat(Regex("<a id=\"p-a-b\"></a>").findAll(markdown).count()).isEqualTo(1)
        assertThat(markdown.lines().count { it.startsWith("- [") && "](#p-a-b)" in it }).isEqualTo(1)
        assertThat(markdown.lines().count { it.startsWith("- p.") }).isEqualTo(1)
        val anchors = Regex("\"anchor\": (null|\"p-a-b\")").findAll(DiffJson.render(entries)).map { it.groupValues[1] }.toList()
        assertThat(anchors).containsExactlyInAnyOrder("null", "\"p-a-b\"")
    }
}
