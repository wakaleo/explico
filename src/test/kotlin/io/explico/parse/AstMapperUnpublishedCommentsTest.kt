/**
 * Spec §14.65: `AstMapper.mapPolicySet` hands each package the plain comment blocks of its files. Driven from the real `opa parse` capture
 * `unpublished-comments.json` (the source is `realistic/unpublished-comments/policies/uc.rego`) plus two hand-written modules -- no `opa` needed.
 */
package io.explico.parse

import io.explico.model.CommentBlock
import io.explico.opa.OpaModule
import io.explico.opa.opaJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class AstMapperUnpublishedCommentsTest {

    private fun capture(): OpaModule = opaJson.decodeFromString(OpaModule.serializer(), Files.readString(Path.of("src/test/resources/ast/unpublished-comments.json")))

    private fun module(json: String): OpaModule = opaJson.decodeFromString(OpaModule.serializer(), json)

    @Test
    @DisplayName("the real capture: the header, the detached note and the plain lines before METADATA are blocks; remarks, body comments and METADATA are not")
    fun realCapture() {
        val set = AstMapper.mapPolicySet(listOf(ParsedFile("uc.rego", capture())))
        assertThat(set.packages.single().unpublishedComments).containsExactly(
            CommentBlock("uc.rego", 1, 3), CommentBlock("uc.rego", 23, 2), CommentBlock("uc.rego", 40, 3),
        )
    }

    @Test
    @DisplayName("a package split over several files carries the blocks of every file, sorted by file then row, each with its own file name")
    fun severalFiles() {
        val set = AstMapper.mapPolicySet(listOf(ParsedFile("z.rego", capture()), ParsedFile("a/b.rego", capture())))
        assertThat(set.packages.single().unpublishedComments.map { it.file to it.row }).containsExactly(
            "a/b.rego" to 1, "a/b.rego" to 23, "a/b.rego" to 40, "z.rego" to 1, "z.rego" to 23, "z.rego" to 40,
        )
    }

    @Test
    @DisplayName("CHALLENGE: a file with only a package line and comments (no rules) still contributes its blocks; a file with no comments contributes none")
    fun filesWithoutRules() {
        val commentsOnly = module(
            """
            {"package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"p"}]},
             "comments": [{"Text": "${b64(" one")}", "Location": {"file":"c.rego","row":2,"col":1,"text":"${b64("# one")}"}},
                          {"Text": "${b64(" two")}", "Location": {"file":"c.rego","row":3,"col":1,"text":"${b64("# two")}"}}]}
            """.trimIndent(),
        )
        val noComments = module("""{"package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"p"}]}}""")
        assertThat(AstMapper.mapPolicySet(listOf(ParsedFile("c.rego", commentsOnly))).packages.single().unpublishedComments).containsExactly(CommentBlock("c.rego", 2, 2))
        assertThat(AstMapper.mapPolicySet(listOf(ParsedFile("n.rego", noComments))).packages.single().unpublishedComments).isEmpty()
    }

    @Test
    @DisplayName("a bare `#` comment line: opa reports its text as null, which reads as an empty comment line -- it must not crash the load, and it belongs to its block")
    fun nullCommentText() {
        val module = module(
            """
            {"package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"p"}]},
             "comments": [{"Text": "${b64(" one")}", "Location": {"file":"c.rego","row":2,"col":1,"text":"${b64("# one")}"}},
                          {"Text": null, "Location": {"file":"c.rego","row":3,"col":1,"text":"${b64("#")}"}},
                          {"Text": "${b64(" two")}", "Location": {"file":"c.rego","row":4,"col":1,"text":"${b64("# two")}"}}]}
            """.trimIndent(),
        )
        assertThat(AstMapper.mapPolicySet(listOf(ParsedFile("c.rego", module))).packages.single().unpublishedComments).containsExactly(CommentBlock("c.rego", 2, 3))
    }

    @Test
    @DisplayName("a rule without a location text still has its own row as its span, so a comment at that row is not a block")
    fun ruleWithoutLocationText() {
        val module = module(
            """
            {"package": {"path": [{"type":"var","value":"data"},{"type":"string","value":"p"}]},
             "rules": [{"head": {"name":"r","ref":[{"type":"var","value":"r"}]}, "location": {"file":"r.rego","row":2,"col":1}}],
             "comments": [{"Text": "${b64(" a")}", "Location": {"file":"r.rego","row":2,"col":1}}, {"Text": "${b64(" b")}", "Location": {"file":"r.rego","row":3,"col":1}}]}
            """.trimIndent(),
        )
        assertThat(AstMapper.mapPolicySet(listOf(ParsedFile("r.rego", module))).packages.single().unpublishedComments).isEmpty()
    }

    private fun b64(s: String): String = java.util.Base64.getEncoder().encodeToString(s.toByteArray())
}
