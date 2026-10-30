/** `SourceLinks` (spec §14.48): the URL template and its percent-encoding. No opa, no files. */
package io.explico.render

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class SourceLinksTest {

    private val template = "https://git.example.com/org/repo/blob/abc/policies/{file}#L{line}"

    @Test
    fun nullTemplateMeansNoLinks() {
        assertThat(SourceLinks.of(null)).isNull()
    }

    @Test
    fun replacesBothPlaceholders() {
        assertThat(SourceLinks.of(template)!!.url("approvals/change_approval.rego", 16))
            .isEqualTo("https://git.example.com/org/repo/blob/abc/policies/approvals/change_approval.rego#L16")
    }

    @Test
    fun theLinePlaceholderIsOptionalAndEveryOccurrenceIsReplaced() {
        assertThat(SourceLinks.of("https://x.example/{file}")!!.url("a/b.rego", 3)).isEqualTo("https://x.example/a/b.rego")
        assertThat(SourceLinks.of("https://x.example/{file}?f={file}&l={line}&m={line}")!!.url("a.rego", 7))
            .isEqualTo("https://x.example/a.rego?f=a.rego&l=7&m=7")
    }

    @Test
    @DisplayName("a template without {file} is rejected, naming the placeholder")
    fun fileIsRequired() {
        assertThatThrownBy { SourceLinks.of("https://git.example.com/no-placeholder") }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("{file}")
        assertThatThrownBy { SourceLinks.of("https://git.example.com/{line}") }.hasMessageContaining("{file}")
        assertThatThrownBy { SourceLinks.of("") }.hasMessageContaining("{file}")
    }

    @Test
    @DisplayName("CHALLENGE: each path segment is percent-encoded so a name can never break the URL; '/' is kept")
    fun encoding() {
        val links = SourceLinks.of("https://h/{file}")!!
        assertThat(links.url("my policies/rule file.rego", 1)).isEqualTo("https://h/my%20policies/rule%20file.rego")
        assertThat(links.url("odd#name?.rego", 1)).isEqualTo("https://h/odd%23name%3F.rego")
        assertThat(links.url("100%/a&b=c.rego", 1)).isEqualTo("https://h/100%25/a%26b%3Dc.rego")
        assertThat(links.url("café/日本.rego", 1)).isEqualTo("https://h/caf%C3%A9/%E6%97%A5%E6%9C%AC.rego")
        assertThat(links.url("keep-these_chars.v1~x/y.rego", 1)).isEqualTo("https://h/keep-these_chars.v1~x/y.rego")
    }

    @Test
    @DisplayName("CHALLENGE: parentheses and brackets in a file name are encoded, so they cannot end the Markdown link early")
    fun parenthesesCannotBreakTheMarkdownLink() {
        val url = SourceLinks.of("https://h/{file}")!!.url("a(1)/b[2] copy.rego", 1)
        assertThat(url).isEqualTo("https://h/a%281%29/b%5B2%5D%20copy.rego").doesNotContain("(").doesNotContain(")").doesNotContain(" ")
    }

    @Test
    @DisplayName("CHALLENGE: a placeholder-looking sequence inside a file name is data, not a second substitution")
    fun fileNameIsNotReexpanded() {
        val links = SourceLinks.of("https://h/{file}#L{line}")!!
        assertThat(links.url("{line}.rego", 9)).isEqualTo("https://h/%7Bline%7D.rego#L9")
    }

    @Test
    fun aRootLevelFileHasASingleSegment() {
        assertThat(SourceLinks.of("https://h/{file}")!!.url("policy.rego", 2)).isEqualTo("https://h/policy.rego")
    }

    @Test
    @DisplayName("spec §14.70: a place is `file:row` as a code span, linked when there is a template: the text never changes, the URL is SourceLinks' own")
    fun placeText() {
        val links = SourceLinks.of("https://git.example.com/blob/abc/{file}#L{line}")
        assertThat(links.placeText("sub/x.rego", 14)).isEqualTo("[`sub/x.rego:14`](https://git.example.com/blob/abc/sub/x.rego#L14)")
        assertThat(SourceLinks.of(null).placeText("sub/x.rego", 14)).isEqualTo("`sub/x.rego:14`")
    }

    @Test
    @DisplayName("spec §14.70 CHALLENGE: a directory with a space and a `#` is percent-encoded in the link (the text keeps the name as written); a template without {line} links the file; {line} twice is replaced twice")
    fun placeTextEncodingAndTemplates() {
        assertThat(SourceLinks.of("https://h/{file}#L{line}").placeText("my dir/a#b.rego", 3)).isEqualTo("[`my dir/a#b.rego:3`](https://h/my%20dir/a%23b.rego#L3)")
        assertThat(SourceLinks.of("https://h/{file}").placeText("a.rego", 9)).isEqualTo("[`a.rego:9`](https://h/a.rego)")
        assertThat(SourceLinks.of("https://h/{file}?l={line}&m={line}").placeText("a.rego", 9)).isEqualTo("[`a.rego:9`](https://h/a.rego?l=9&m=9)")
    }
}
