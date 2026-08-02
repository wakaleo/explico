/** Clikt CLI (spec §8.2/§13.2): `render`, `diff`, `version`, `demo`. */
package io.explico.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import io.explico.Explico
import io.explico.diff.DuplicateControlIdException
import io.explico.opa.OpaFetcher
import io.explico.opa.OpaInvocationException
import io.explico.opa.OpaRunner
import io.explico.opa.OpaUnavailableException
import io.explico.render.DuplicateFixtureNameException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

/** The exact opa version the demo's guidance recommends (spec §13.2), matching CI's pin (spec §10.2). */
private const val PINNED_OPA_VERSION = "1.19.0"

fun main(args: Array<String>) {
    NoOpCliktCommand(name = "explico")
        .subcommands(RenderCommand(), DiffCommand(), VersionCommand(), DemoCommand())
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

private class DemoCommand : CliktCommand(name = "demo") {
    private val fetchOpa by option(
        "--fetch-opa",
        help = "Download a pinned, checksum-verified opa $PINNED_OPA_VERSION binary if none is available",
    ).flag()

    override fun run() {
        val demoDir = Path.of("explico-demo")
        // Cheapest, opa-independent precondition first (spec §13.2): never partially extract or
        // touch opa before confirming there's actually somewhere safe to put the demo.
        if (Files.exists(demoDir)) {
            failWith(1, "'$demoDir' already exists in the current directory -- remove it or run the demo somewhere else.")
        }

        if (fetchOpa && !OpaRunner.isAvailable()) {
            fetchOpaOrExit()
        }
        if (!OpaRunner.isAvailable()) {
            failWith(
                3,
                "opa $PINNED_OPA_VERSION is required to run the demo, but it could not be run " +
                    "(resolved binary: '${OpaRunner.resolveBinary()}'). Install opa $PINNED_OPA_VERSION " +
                    "(https://www.openpolicyagent.org/docs/latest/#running-opa) and ensure it is on PATH, " +
                    "or set the OPA_BIN environment variable to its path, or re-run with --fetch-opa to " +
                    "download it automatically.",
            )
        }

        try {
            extractDemoResources(demoDir)
            val policyDir = demoDir.resolve("policies")
            val policySet = Explico.load(policyDir)
            val examples = Explico.loadExamples(demoDir.resolve("examples"))
            val rendered = Explico.render(policySet, policyDir, examples, demoDir.resolve("data/release/data.json"))
            val outDir = demoDir.resolve("docs")
            replaceOutputDirectory(outDir, rendered.files)

            echo("Rendered ${rendered.files.size} documents to $outDir/ (${rendered.coverage.percent}% coverage).")
            echo("Open ${outDir.resolve("release-approvals.md")} to see a rendered control card.")
        } catch (e: OpaInvocationException) {
            failWith(2, e.stderr)
        }
    }

    private fun fetchOpaOrExit() {
        try {
            val cacheDir = Path.of(System.getProperty("user.home"), ".explico", "opa-cache")
            val fetched = OpaFetcher.fetch(PINNED_OPA_VERSION, cacheDir)
            OpaRunner.binaryOverride = fetched
            echo("Downloaded opa $PINNED_OPA_VERSION to $fetched")
        } catch (e: Exception) {
            failWith(
                3,
                "Could not automatically fetch opa $PINNED_OPA_VERSION (${e.message}). Install it manually: " +
                    "https://www.openpolicyagent.org/docs/latest/#running-opa",
            )
        }
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

/**
 * Extracts the acceptance pack embedded as jar resources (spec §13.2) to [targetDir], driven by a
 * build-generated manifest (`/demo-manifest.txt`) rather than a classpath directory listing --
 * `getResource("policies")` on a directory name is unreliable inside a jar depending on whether
 * its zip index has explicit directory entries, but `getResourceAsStream` on a known file path
 * (what the manifest lists) always works, jar or exploded classpath alike.
 */
private fun extractDemoResources(targetDir: Path) {
    val manifestStream = resourceStream("/demo-manifest.txt")
        ?: error("Embedded demo-manifest.txt not found on the classpath -- this build is missing its demo resources.")
    val relativePaths = manifestStream.bufferedReader().readLines().filter { it.isNotBlank() }

    for (relativePath in relativePaths) {
        val resource = resourceStream("/$relativePath")
            ?: error("Embedded demo resource '$relativePath' listed in the manifest but not found -- the jar may be corrupt.")
        val target = targetDir.resolve(relativePath)
        resource.use {
            Files.createDirectories(target.parent)
            Files.copy(it, target)
        }
    }
}

private fun resourceStream(path: String) = object {}.javaClass.getResourceAsStream(path)

/** Extracts just the `## Summary` table from the full report (spec §8.2: "prints the summary table to stdout"). */
private fun summarySection(markdown: String): String {
    val start = markdown.indexOf("## Summary").takeIf { it >= 0 } ?: return markdown
    val end = markdown.indexOf("## Changes", start).let { if (it == -1) markdown.length else it }
    return markdown.substring(start, end).trim()
}

private fun explicoVersion(): String =
    resourceStream("/explico-version.properties")?.use { stream ->
        java.util.Properties().apply { load(stream) }.getProperty("version")
    } ?: "unknown"
