/** `parseRequirements` (spec §14.46): the requirements-file format. No opa, no process. */
package io.explico.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ParseRequirementsTest {

    @Test
    fun commentsBlankLinesAndDuplicatesAreDroppedAndLinesTrimmed() {
        assertThat(parseRequirements("# header\nSOC 2 CC8.1\n\n  ISO 27001 A.8.32 \n   # indented comment\nSOC 2 CC8.1\n"))
            .containsExactly("SOC 2 CC8.1", "ISO 27001 A.8.32")
    }

    @Test
    @DisplayName("CHALLENGE: Windows line endings, a UTF-8 BOM-less file with non-ASCII, and a requirement that merely contains a # are kept intact")
    fun edgeCases() {
        assertThat(parseRequirements("A\r\nB\r\n")).containsExactly("A", "B")
        assertThat(parseRequirements("NIST 800-53 CM-3 ✓")).containsExactly("NIST 800-53 CM-3 ✓")
        assertThat(parseRequirements("Control #4")).containsExactly("Control #4")
    }

    @Test
    fun anEmptyFileHasNoRequirements() {
        assertThat(parseRequirements("")).isEmpty()
        assertThat(parseRequirements("# only a comment\n\n")).isEmpty()
    }
}
