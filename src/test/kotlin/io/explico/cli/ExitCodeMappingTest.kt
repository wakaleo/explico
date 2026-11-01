/**
 * The CLI's exit code for each library exception, decided by TYPE in one place (spec §14.80 step 3): `exitCodeOf`. No opa. The documented codes (spec §8.2): 1 usage / refused output / malformed
 * mask, 2 opa rejected the input, 3 opa missing, 4 duplicate control ids or fixture names; any other ExplicoException is a plain failure, 1.
 *
 * (The stub's parameter is a Throwable only until the exceptions extend ExplicoException; /tdd narrows it to ExplicoException and these calls stay as they are.)
 */
package io.explico.cli

import io.explico.OpaFailedException
import io.explico.OpaNotAvailableException
import io.explico.OutputDirectoryException
import io.explico.ExplicoException
import io.explico.diff.DuplicateControlIdException
import io.explico.render.DuplicateFixtureNameException
import io.explico.render.InvalidMaskException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ExitCodeMappingTest {

    @Test
    @DisplayName("opa rejected the input -> 2, whatever opa's own exit code was")
    fun opaFailed() {
        assertThat(exitCodeOf(OpaFailedException(1, "rego_parse_error"))).isEqualTo(2)
        assertThat(exitCodeOf(OpaFailedException(137, "killed"))).isEqualTo(2)
    }

    @Test
    @DisplayName("opa missing or not 1.x -> 3")
    fun opaNotAvailable() {
        assertThat(exitCodeOf(OpaNotAvailableException("explico requires opa version 1.x"))).isEqualTo(3)
    }

    @Test
    @DisplayName("duplicate control ids and duplicate fixture names -> 4")
    fun duplicates() {
        assertThat(exitCodeOf(DuplicateControlIdException(listOf("A-1")))).isEqualTo(4)
        assertThat(exitCodeOf(DuplicateFixtureNameException("dup"))).isEqualTo(4)
    }

    @Test
    @DisplayName("a refused output path and a malformed custom.mask -> 1")
    fun refusals() {
        assertThat(exitCodeOf(OutputDirectoryException(Path.of("out"), "refusing to write to out"))).isEqualTo(1)
        assertThat(exitCodeOf(InvalidMaskException("a rule's custom.mask is malformed"))).isEqualTo(1)
    }

    @Test
    @DisplayName("any other ExplicoException is a plain failure, 1")
    fun others() {
        assertThat(exitCodeOf(object : ExplicoException("x") {})).isEqualTo(1)
    }
}
