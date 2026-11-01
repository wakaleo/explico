/**
 * Unit tests for the removal notice of a REMOVED entry (spec §14.79): a rule with a control id is a control, any other removed rule (a helper) is a rule. Hand-built, no opa.
 */
package io.explico.diff

import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RuleGroup
import io.explico.model.RuleMetadata
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class RemovedNoticeTest {

    private fun removed(id: String?): String {
        val rule = RuleGroup("helper", id?.let { RuleMetadata("T", "d", it, emptyList()) }, null, emptyList())
        val pkg = PolicyPackage("p", listOf(rule), listOf("f.rego"))
        val entry = DiffEntry(DiffCategory.REMOVED, id, pkg, rule, null, null)
        return DiffRenderer.render(listOf(entry), PolicySet(listOf(pkg)), PolicySet(emptyList()))
    }

    @Test
    @DisplayName("a removed rule with a control id says control")
    fun control() {
        assertThat(removed("C-1")).contains("**⚠ This control has been removed.**").doesNotContain("This rule has been removed")
    }

    @Test
    @DisplayName("a removed rule without one says rule")
    fun rule() {
        assertThat(removed(null)).contains("**⚠ This rule has been removed.**").doesNotContain("This control has been removed")
    }
}
