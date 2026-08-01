/**
 * Verifies the DTOs decode the checked-in `opa parse` capture for
 * `change_approval.rego` (src/test/resources/ast/change_approval.json).
 * Requires no `opa` binary — this is what lets AstMapper (a later milestone)
 * be unit-tested without it too.
 */
package io.explico.opa

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class OpaJsonTest {

    private val capture = Files.readString(Path.of("src/test/resources/ast/change_approval.json"))

    @Test
    fun decodesPackagePath() {
        val module = opaJson.decodeFromString(OpaModule.serializer(), capture)

        val segments = module.pkg.path.map { it.type to it.value }
        assertThat(module.pkg.path).hasSize(3)
        assertThat(segments[0].first).isEqualTo("var")
        assertThat(segments[1].second.toString()).contains("release")
        assertThat(segments[2].second.toString()).contains("approvals")
    }

    @Test
    fun decodesBothRuleBodiesWithLocations() {
        val module = opaJson.decodeFromString(OpaModule.serializer(), capture)

        assertThat(module.rules).hasSize(2)
        module.rules.forEach { rule ->
            assertThat(rule.head.name).isEqualTo("deny")
            assertThat(rule.body).hasSize(3)
            assertThat(rule.location?.row).isEqualTo(if (rule === module.rules[0]) 16 else 22)
            rule.body.forEach { expr ->
                assertThat(expr.location).isNotNull()
                assertThat(expr.terms).isNotNull()
            }
        }
    }

    @Test
    fun decodesDocumentScopedMetadataWithControlIdAndFrameworks() {
        val module = opaJson.decodeFromString(OpaModule.serializer(), capture)

        assertThat(module.annotations).hasSize(1)
        val metadata = module.annotations.single()
        assertThat(metadata.scope).isEqualTo("document")
        assertThat(metadata.title).isEqualTo("Production change approval")
        assertThat(metadata.custom?.controlId).isEqualTo("REL-001")
        assertThat(metadata.custom?.frameworks).containsExactly("SOC 2 CC8.1", "ISO 27001 A.8.32")
    }

    @Test
    fun secondExpressionOfFirstBodyIsNegated() {
        val module = opaJson.decodeFromString(OpaModule.serializer(), capture)

        val negatedExpr = module.rules[0].body[1]
        assertThat(negatedExpr.negated).isTrue()
    }
}
