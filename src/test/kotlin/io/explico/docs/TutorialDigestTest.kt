/**
 * `docs/tutorial.md` pastes the real head of a diff report, including the `sha256:` digest of `samples/policies` (spec §14.76). That digest changes whenever a sample `.rego`
 * file does, so it is checked here: the page cannot print a digest the tool would not. Only the OLD digest is checked -- the new one belongs to the reader's own edited copy
 * and is illustrative, as the page says. Needs no opa (the digest is computed from the files, exactly as `render` and `diff` compute it).
 */
package io.explico.docs

import io.explico.render.PolicyDigest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class TutorialDigestTest {

    @Test
    @DisplayName("the tutorial's Old digest is the digest of samples/policies, the value render prints in index.md")
    fun oldDigestIsCurrent() {
        val page = Files.readString(Path.of("docs/tutorial.md"))
        val pasted = Regex("- Old \\(samples\\): `(sha256:[0-9a-f]{64})`").find(page)?.groupValues?.get(1)

        assertThat(pasted).describedAs("an '- Old (samples): `sha256:...`' line in docs/tutorial.md").isNotNull()
        assertThat(pasted).describedAs("regenerate the block by running the tutorial's diff step (see CLAUDE.md, spec 14.76)").isEqualTo("sha256:${PolicyDigest.of(Path.of("samples/policies"))}")
    }
}
