/** Clikt CLI (spec §8.2): `render`, `diff`, `version`. */
package io.explico.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import io.explico.Explico
import io.explico.diff.DuplicateControlIdException
import io.explico.opa.OpaInvocationException
import io.explico.opa.OpaRunner
import io.explico.opa.OpaUnavailableException
import io.explico.render.DuplicateFixtureNameException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

fun main(args: Array<String>) {
    NoOpCliktCommand(name = "explico")
        .subcommands(RenderCommand(), DiffCommand(), VersionCommand())
        .main(args)
}

private class RenderCommand : CliktCommand(name = "render") {
    private val policyDir by argument(help = "Directory containing .rego policy files")
        .path(mustExist = true, canBeFile = false)
    private val out by option("--out", help = "Output directory for the rendered Markdown").path().required()
    private val examplesDir by option("--examples", help = "Directory of worked-example fixtures (*.json)")
        .path(mustExist = true, canBeFile = false)
    private val dataDir by option("--data", help = "Data document/directory for worked-example evaluation")
        .path(mustExist = true)

    override fun run() {
        requireOpaOrExit()
        try {
            val policySet = Explico.load(policyDir)
            val examples = examplesDir?.let { Explico.loadExamples(it) }
            val rendered = Explico.render(policySet, policyDir, examples, dataDir)
            replaceOutputDirectory(out, rendered.files)
            echo("Rendering coverage: ${rendered.coverage.rendered} of ${rendered.coverage.total} conditions (${rendered.coverage.percent}%)")
        } catch (e: OpaInvocationException) {
            failWith(2, e.stderr)
        } catch (e: DuplicateFixtureNameException) {
            failWith(4, e.message ?: "duplicate fixture name")
        }
    }
}

private class DiffCommand : CliktCommand(name = "diff") {
    private val oldDir by argument(help = "Old policy directory").path(mustExist = true, canBeFile = false)
    private val newDir by argument(help = "New policy directory").path(mustExist = true, canBeFile = false)
    private val out by option("--out", help = "Output file for the change report").path().required()

    override fun run() {
        requireOpaOrExit()
        try {
            val old = Explico.load(oldDir)
            val new = Explico.load(newDir)
            val report = Explico.diff(old, new)
            out.parent?.let { Files.createDirectories(it) }
            Files.writeString(out, report.markdown)
            echo(summarySection(report.markdown))
        } catch (e: OpaInvocationException) {
            failWith(2, e.stderr)
        } catch (e: DuplicateControlIdException) {
            failWith(4, e.message ?: "duplicate control-id")
        }
    }
}

private class VersionCommand : CliktCommand(name = "version") {
    override fun run() {
        echo("explico ${explicoVersion()}")
    }
}

private fun CliktCommand.requireOpaOrExit() {
    try {
        OpaRunner.requireCompatibleVersion()
    } catch (e: OpaUnavailableException) {
        failWith(3, e.message ?: "opa is not available")
    }
}

/** Prints [message] to stderr with no stack trace, then exits with [exitCode] (spec §8.2). */
private fun CliktCommand.failWith(exitCode: Int, message: String): Nothing {
    echo(message, err = true)
    throw ProgramResult(exitCode)
}

/** `--out` contents are fully replaced (spec §8.2): delete the whole directory first, then rewrite it. */
private fun replaceOutputDirectory(dir: Path, files: Map<String, String>) {
    if (Files.exists(dir)) {
        Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
    Files.createDirectories(dir)
    files.forEach { (name, content) -> Files.writeString(dir.resolve(name), content) }
}

/** Extracts just the `## Summary` table from the full report (spec §8.2: "prints the summary table to stdout"). */
private fun summarySection(markdown: String): String {
    val start = markdown.indexOf("## Summary").takeIf { it >= 0 } ?: return markdown
    val end = markdown.indexOf("## Changes", start).let { if (it == -1) markdown.length else it }
    return markdown.substring(start, end).trim()
}

private fun explicoVersion(): String =
    object {}.javaClass.getResourceAsStream("/explico-version.properties")?.use { stream ->
        java.util.Properties().apply { load(stream) }.getProperty("version")
    } ?: "unknown"
