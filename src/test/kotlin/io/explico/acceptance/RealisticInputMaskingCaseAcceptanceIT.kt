/**
 * Review follow-up to FR-11 (input masking, spec §14.61): a rule often echoes an input value in ANOTHER CASE (`lower(input.email)`, which explico itself promotes), so a
 * masked string must be hidden whatever case the rule prints it in. Facade only. Fixture: `realistic/input-masking-case/` (the message is built with `lower(...)` from a
 * fixture e-mail written in mixed case).
 */
package io.explico.acceptance

import io.explico.Explico
import io.explico.RenderOptions
import io.explico.opa.OpaRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path

@DisplayName("Realistic policies: a masked value echoed in another case is still hidden")
class RealisticInputMaskingCaseAcceptanceIT {

    companion object {
        private val base = Path.of("src/test/resources/realistic/input-masking-case")
        private lateinit var files: Map<String, String>

        @JvmStatic
        @BeforeAll
        fun renderAll() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
            val policies = base.resolve("policies")
            files = Explico.render(Explico.load(policies), RenderOptions(examples = Explico.loadExamples(base.resolve("examples")), json = true)).files
        }
    }

    @Test
    @DisplayName("the message printed from lower(input.email) shows *** although the fixture holds Alice@Example.COM, in the card and in explico.json")
    fun lowerCasedEcho() {
        val card = files.getValue("maskcase.md")
        assertThat(card).contains("*\"\\*\\*\\* is blocked\"*")
        for ((name, text) in files) assertThat(text.lowercase()).describedAs(name).doesNotContain("alice@example.com")
        assertThat(files.getValue("explico.json")).contains("\"*** is blocked\"")
    }
}
