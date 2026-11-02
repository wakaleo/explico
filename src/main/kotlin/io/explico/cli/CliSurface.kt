package io.explico.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.parse

/**
 * The CLI's whole surface as text (spec §14.80 step 6): every command with its arguments, every option with its kind, and the exit codes. The committed baseline `api/explico.cli` is this, byte for byte
 * (`CliBaselineTest`), so a change to the CLI is a visible, reviewed diff. It is read from the REAL command tree ([explicoCommandTree], the one `main` runs), never from help prose.
 *
 * Format: one block per command (`explico`, then the subcommands alphabetically), separated by one blank line. A block is the command line (`explico <sub> <arguments>`) and one line per option sorted by
 * its first long name, `  --name  KIND`, where KIND is `flag`, the metavar of a value option (`<path>`, `(v1|v0)`), or `required <metavar>`; `-h/--help` is not listed. The last block lists the exit codes.
 */
internal object CliSurface {

    /** The documented exit codes (spec §8.2), worded once; `docs/compatibility.md` carries the same meanings. */
    private val EXIT_CODES = listOf(
        0 to "success",
        1 to "usage or refused output",
        2 to "opa rejected the input",
        3 to "opa missing or not 1.x",
        4 to "duplicate control id or fixture name",
        5 to "a CI gate failed",
    )

    fun dump(): String {
        val root = explicoCommandTree()
        val blocks = listOf(block(root, "explico")) + root.registeredSubcommands().sortedBy { it.commandName }.map { block(it, "explico ${it.commandName}") } +
            ("exit codes\n" + EXIT_CODES.joinToString("\n") { (code, meaning) -> "  $code  $meaning" })
        return blocks.joinToString("\n\n", postfix = "\n")
    }

    private fun block(command: CoreCliktCommand, title: String): String {
        val context = contextOf(command)
        val arguments = command.registeredArguments().map { "<${it.name.lowercase()}>" }
        val options = command.registeredOptions().mapNotNull { option ->
            val help = option.parameterHelp(context) ?: return@mapNotNull null
            val longNames = help.names.filter { it.startsWith("--") }
            if ("--help" in longNames) return@mapNotNull null
            val name = longNames.sorted().first()
            // Clikt returns the bare metavar (`path`) and its help formatter adds the angle brackets; a choice set (`(v1|v0)`) is shown as it is.
            val kind = if (option.nvalues.last == 0) "flag" else (help.metavar ?: option.metavar(context) ?: error("option $name takes a value but has no metavar")).let { if (it.startsWith("(")) it else "<$it>" }
            name to (if ("required" in help.tags) "required $kind" else kind)
        }.sortedBy { it.first }
        return (listOf((listOf(title) + arguments).joinToString(" ")) + options.map { (name, kind) -> "  $name  $kind" }).joinToString("\n")
    }

    /**
     * Clikt builds a command's [Context] only while parsing, and its builder is not public. Parsing `--help` is the public way: the eager help option stops before any required option, argument or `run` is
     * touched, and the context stays readable afterwards. Nothing is printed or executed.
     */
    private fun contextOf(command: CoreCliktCommand): Context {
        try {
            command.parse(listOf("--help"))
        } catch (_: PrintHelpMessage) {
            // expected: the context now exists
        }
        return command.currentContext
    }
}
