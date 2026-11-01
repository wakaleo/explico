package io.explico.cli

import io.explico.ExplicoException
import io.explico.OpaFailedException
import io.explico.OpaNotAvailableException
import io.explico.diff.DuplicateControlIdException
import io.explico.render.DuplicateFixtureNameException

/**
 * The CLI's exit code for a library exception, decided by TYPE in this one place (spec §14.80 step 3; the codes are spec §8.2's): `2` opa rejected the input, `3` opa is missing or not 1.x,
 * `4` a duplicate control id or fixture name, and `1` for everything else in the family (a refused output path, a malformed `custom.mask`, any exception added later). A new
 * [ExplicoException] subclass with its own code goes here, in `ExceptionHierarchyTest`'s list and in the docs.
 */
internal fun exitCodeOf(e: ExplicoException): Int = when (e) {
    is OpaFailedException -> 2
    is OpaNotAvailableException -> 3
    is DuplicateControlIdException, is DuplicateFixtureNameException -> 4
    else -> 1
}

/** What the CLI prints for [e] on stderr, with no stack trace: opa's own text verbatim for a failure, otherwise the library's message (plus, for a duplicate id in `diff`, the way out). */
internal fun messageOf(e: ExplicoException, command: String): String = when {
    e is OpaFailedException -> e.stderr
    e is DuplicateControlIdException && command == "diff" ->
        (e.message ?: "duplicate control-id") + " -- if one control is split over several rules on purpose, pass --group-by-control to diff each rule separately"
    else -> e.message ?: e.javaClass.simpleName
}
