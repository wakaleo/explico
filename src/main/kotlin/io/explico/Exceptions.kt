package io.explico

import java.nio.file.Path

/**
 * The base of every exception a library consumer is meant to catch by type (spec §14.80 step 3): one `catch (e: ExplicoException)` covers an opa failure, a missing opa, a duplicate
 * control id or fixture name, a malformed `custom.mask` and a refused output path, and the subclass says which. The family is closed; it is exactly:
 *
 * - [OpaFailedException] and [OpaNotAvailableException] (this package),
 * - [OutputDirectoryException] (this package),
 * - [io.explico.diff.DuplicateControlIdException],
 * - [io.explico.render.DuplicateFixtureNameException] and [io.explico.render.InvalidMaskException].
 *
 * Two kinds of failure are deliberately NOT in it: an invalid ARGUMENT (a bad template, pattern, label or required name, rendering a set that was not loaded) is an
 * [IllegalArgumentException], and a file-system problem (a policy directory that does not exist) is the file system's own [java.io.IOException].
 */
public open class ExplicoException @JvmOverloads constructor(message: String?, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * `opa` is missing, cannot be run, or is not major version 1 (the CLI exits `3`). The message names the binary that was tried and says how to fix it: install opa 1.x or set `OPA_BIN`.
 * When opa could not be launched at all, [cause] is the [java.io.IOException] from the process launch.
 */
public class OpaNotAvailableException @JvmOverloads constructor(message: String, cause: Throwable? = null) : ExplicoException(message, cause)

/**
 * `opa` ran and rejected the input, for example a Rego syntax error (the CLI exits `2`). [stderr] is opa's own text, passed through verbatim (opa's stdout when it reported the error
 * there, or `opa exited with code N` when it said nothing), and is also the message. [exitCode] is opa's real process exit code, or `-1` when opa did not finish within the time limit and
 * was stopped.
 */
public class OpaFailedException(public val exitCode: Int, public val stderr: String) : ExplicoException(stderr)

/**
 * An output path explico refuses to write to, because writing would delete something it must not (spec §14.80 step 1: a directory that is not empty and was not written by explico, one that
 * holds the policies, the home or working directory, a filesystem root, or an existing file). [path] is the path as the caller gave it; nothing was deleted or written.
 */
public class OutputDirectoryException(public val path: Path, message: String) : ExplicoException(message)
