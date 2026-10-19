/**
 * Unit tests for [ReferenceData] (spec §14.41): which `data.*` paths a rule actually reads (deduped to their
 * plain prefix, loaded-rule paths excluded) and how a resolved value is displayed. Hand-built domain objects --
 * no opa needed; `OpaRunner.evalDataPath` (which does need opa) is covered separately.
 */
package io.explico.render

import io.explico.model.Condition
import io.explico.model.Operand
import io.explico.model.PathSegment
import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RuleBody
import io.explico.model.RuleGroup
import io.explico.model.SourceRef
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ReferenceDataTest {

    private fun body(conditions: List<Condition>) = RuleBody(conditions, null, null, SourceRef("f.rego", 1), "")
    private fun path(vararg names: String) = Operand.Path(names.map { PathSegment.Field(it) })
    private fun rule(name: String, vararg conditions: Condition) = RuleGroup(name, metadata = null, default = null, bodies = listOf(body(conditions.toList())))
    private fun truthy(p: Operand.Path) = Condition.Truthy(p, negated = false)

    @Nested
    inner class CollectingPaths {

        @Test
        fun aPlainDataPathInAComparisonIsCollected() {
            val condition = Condition.Comparison(path("input", "x"), io.explico.model.Operator.EQ, path("data", "limits", "max"))
            val policySet = PolicySet(listOf(PolicyPackage("p", listOf(rule("deny", condition)), listOf("f.rego"))))

            assertThat(ReferenceData.referencedDataPaths(policySet.packages[0].rules[0], policySet)).containsExactly(path("data", "limits", "max"))
        }

        @Test
        fun anInputRootedPathIsNotReferenceData() {
            val condition = truthy(path("input", "x"))
            val policySet = PolicySet(listOf(PolicyPackage("p", listOf(rule("deny", condition)), listOf("f.rego"))))

            assertThat(ReferenceData.referencedDataPaths(policySet.packages[0].rules[0], policySet)).isEmpty()
        }

        @Test
        fun theSameDataPathReferencedTwiceIsCollectedOnce() {
            val a = Condition.Truthy(path("data", "limits", "max"), negated = false)
            val b = Condition.Truthy(path("data", "limits", "max"), negated = true)
            val policySet = PolicySet(listOf(PolicyPackage("p", listOf(rule("deny", a, b)), listOf("f.rego"))))

            assertThat(ReferenceData.referencedDataPaths(policySet.packages[0].rules[0], policySet)).containsExactly(path("data", "limits", "max"))
        }

        @Test
        fun pathsAreCollectedInFirstAppearanceOrderAcrossBodies() {
            val ruleGroup = RuleGroup(
                "deny", metadata = null, default = null,
                bodies = listOf(
                    body(listOf(truthy(path("data", "b")))),
                    body(listOf(truthy(path("data", "a")), truthy(path("data", "b")))),
                ),
            )
            val policySet = PolicySet(listOf(PolicyPackage("p", listOf(ruleGroup), listOf("f.rego"))))

            assertThat(ReferenceData.referencedDataPaths(ruleGroup, policySet)).containsExactly(path("data", "b"), path("data", "a"))
        }

        @Test
        fun aSomeInCollectionOperandIsCollected() {
            val condition = Condition.SomeIn("w", path("data", "release", "freeze_windows"))
            val policySet = PolicySet(listOf(PolicyPackage("p", listOf(rule("deny", condition)), listOf("f.rego"))))

            assertThat(ReferenceData.referencedDataPaths(policySet.packages[0].rules[0], policySet)).containsExactly(path("data", "release", "freeze_windows"))
        }

        @Test
        fun aDataPathNestedInsideABuiltinCallIsCollected() {
            val condition = Condition.BuiltinCall("count", listOf(path("data", "release", "freeze_windows")), negated = false)
            val policySet = PolicySet(listOf(PolicyPackage("p", listOf(rule("deny", condition)), listOf("f.rego"))))

            assertThat(ReferenceData.referencedDataPaths(policySet.packages[0].rules[0], policySet)).containsExactly(path("data", "release", "freeze_windows"))
        }

        @Test
        fun aPathEndingInADynamicSegmentIsTruncatedToItsPlainPrefix() {
            // owner.name after `some owner in data.limits.owners`: data, limits, owners, VarIndex(owner), name.
            val fullPath = Operand.Path(listOf(PathSegment.Field("data"), PathSegment.Field("limits"), PathSegment.Field("owners"), PathSegment.VarIndex("owner"), PathSegment.Field("name")))
            val condition = truthy(fullPath)
            val policySet = PolicySet(listOf(PolicyPackage("p", listOf(rule("deny", condition)), listOf("f.rego"))))

            assertThat(ReferenceData.referencedDataPaths(policySet.packages[0].rules[0], policySet)).containsExactly(path("data", "limits", "owners"))
        }

        @Test
        fun theTruncatedPrefixDedupesWithTheSomeInsOwnCollection() {
            val collectionCondition = Condition.SomeIn("owner", path("data", "limits", "owners"))
            val tailPath = Operand.Path(listOf(PathSegment.Field("data"), PathSegment.Field("limits"), PathSegment.Field("owners"), PathSegment.VarIndex("owner"), PathSegment.Field("name")))
            val tailCondition = Condition.Comparison(path("input", "user", "name"), io.explico.model.Operator.NEQ, tailPath)
            val policySet = PolicySet(listOf(PolicyPackage("p", listOf(rule("deny", collectionCondition, tailCondition)), listOf("f.rego"))))

            assertThat(ReferenceData.referencedDataPaths(policySet.packages[0].rules[0], policySet)).containsExactly(path("data", "limits", "owners"))
        }

        @Test
        fun aDataPathNamingALoadedRuleInAnotherPackageIsExcluded() {
            val helperPkg = PolicyPackage("refdata.helpers", listOf(rule("admins")), listOf("h.rego"))
            val condition = Condition.Membership(negated = false, member = path("input", "user", "name"), collection = path("data", "refdata", "helpers", "admins"))
            val mainPkg = PolicyPackage("refdata.limits", listOf(rule("deny", condition)), listOf("f.rego"))
            val policySet = PolicySet(listOf(mainPkg, helperPkg))

            assertThat(ReferenceData.referencedDataPaths(mainPkg.rules[0], policySet)).isEmpty()
        }

        @Test
        fun aDataPathNamingALoadedRuleInTheSamePackageIsExcluded() {
            val helper = rule("is_exempt")
            val condition = truthy(path("data", "p", "is_exempt"))
            val mainRule = rule("deny", condition)
            val pkg = PolicyPackage("p", listOf(mainRule, helper), listOf("f.rego"))
            val policySet = PolicySet(listOf(pkg))

            assertThat(ReferenceData.referencedDataPaths(mainRule, policySet)).isEmpty()
        }

        @Test
        @DisplayName("CHALLENGE: a data path nested inside a comprehension's own body is collected too")
        fun aDataPathInsideAComprehensionBodyIsCollected() {
            val comprehension = Operand.Comprehension(
                kind = io.explico.model.ComprehensionKind.SET,
                term = path("input", "x"),
                valueTerm = null,
                body = listOf(Condition.SomeIn("x", path("data", "allowlist"))),
            )
            val condition = Condition.BuiltinCall("count", listOf(comprehension), negated = false)
            val policySet = PolicySet(listOf(PolicyPackage("p", listOf(rule("deny", condition)), listOf("f.rego"))))

            assertThat(ReferenceData.referencedDataPaths(policySet.packages[0].rules[0], policySet)).containsExactly(path("data", "allowlist"))
        }

        @Test
        @DisplayName("CHALLENGE: a path that is a strict ANCESTOR of a loaded package is excluded too, not just an exact match")
        fun anAncestorOfALoadedPackageIsExcluded() {
            // opa eval of an ancestor path (confirmed empirically: `opa eval data.release` over the acceptance
            // pack) evaluates EVERY rule nested under it -- the same risk this exclusion exists to avoid, just
            // one level up. "data.release" is an ancestor of "release.governance" below.
            val condition = truthy(path("data", "release"))
            val governancePkg = PolicyPackage("release.governance", listOf(rule("deny", condition)), listOf("f.rego"))
            val policySet = PolicySet(listOf(governancePkg))

            assertThat(ReferenceData.referencedDataPaths(governancePkg.rules[0], policySet)).isEmpty()
        }

        @Test
        fun aPathThatMerelyLooksLikeAPackageNameWithNoMatchingPrefixIsKept() {
            // "data.refdata.other" -- "refdata.other" names no loaded package, and no split of it names a
            // package+rule either: this is ordinary reference data, not a near-miss on the real rule path.
            val helperPkg = PolicyPackage("refdata.helpers", listOf(rule("admins")), listOf("h.rego"))
            val condition = truthy(path("data", "refdata", "other"))
            val mainPkg = PolicyPackage("refdata.limits", listOf(rule("deny", condition)), listOf("f.rego"))
            val policySet = PolicySet(listOf(mainPkg, helperPkg))

            assertThat(ReferenceData.referencedDataPaths(mainPkg.rules[0], policySet)).containsExactly(path("data", "refdata", "other"))
        }
    }

    @Nested
    inner class DottedSegments {

        @Test
        fun dropsTheLeadingDataFieldOnly() {
            assertThat(ReferenceData.dottedSegments(path("data", "limits", "regions"))).containsExactly("limits", "regions")
        }
    }

    @Nested
    inner class FormattingValues {

        private fun json(text: String) = Json.parseToJsonElement(text)

        @Test
        fun nullMeansUndefinedInTheSuppliedData() {
            assertThat(ReferenceData.formatValue(null)).isEqualTo("not defined in the supplied data")
        }

        @Test
        @DisplayName("CHALLENGE: a real JSON null value is shown as `null`, not the undefined phrase")
        fun aRealJsonNullIsShownAsNullNotUndefined() {
            assertThat(ReferenceData.formatValue(kotlinx.serialization.json.JsonNull)).isEqualTo("null")
        }

        @Test
        fun aSmallValueIsShownAsCompactJson() {
            assertThat(ReferenceData.formatValue(json("""["a","b"]"""))).isEqualTo("""["a","b"]""")
        }

        @Test
        fun anArrayOfExactlyFiveIsNotTruncated() {
            val five = json("""["a","b","c","d","e"]""")
            assertThat(ReferenceData.formatValue(five)).isEqualTo("""["a","b","c","d","e"]""")
        }

        @Test
        fun anArrayOfMoreThanFiveShowsTheFirstFiveThenAnEllipsisAndTheTotalCount() {
            val seven = json("""["a","b","c","d","e","f","g"]""")
            val formatted = ReferenceData.formatValue(seven)

            assertThat(formatted).contains("\"a\",\"b\",\"c\",\"d\",\"e\"", "…", "(7 items)").doesNotContain("\"f\"", "\"g\"")
        }

        @Test
        fun aNonArrayValueLongerThanTwoHundredCharactersIsCutWithAnEllipsis() {
            val longString = "x".repeat(250)
            val formatted = ReferenceData.formatValue(json("\"$longString\""))

            assertThat(formatted).hasSize(201).endsWith("…")
        }

        @Test
        fun aNonArrayValueOfExactlyTwoHundredCharactersIsNotCut() {
            val value = json("\"" + "x".repeat(198) + "\"") // 198 x's + 2 quote chars = 200 compact-JSON chars
            assertThat(ReferenceData.formatValue(value)).hasSize(200).doesNotContain("…")
        }
    }
}
