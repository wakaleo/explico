/** Clikt CLI (spec §8.2/§13.2): `render`, `diff`, `version`, `demo`. */
package io.explico.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.types.restrictTo
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import io.explico.CompanionLink
import io.explico.ControlIdOptions
import io.explico.DataVersions
import io.explico.DiffOptions
import io.explico.Explico
import io.explico.ExplicoException
import io.explico.OpaNotAvailableException
import io.explico.OutputDirectoryException
import io.explico.OutputGuard
import io.explico.OutputWriter
import io.explico.RenderOptions
import io.explico.VersionLabels
import io.explico.model.RegoVersion
import io.explico.opa.OpaFetcher
import io.explico.opa.OpaRunner
import io.explico.render.CompanionLinks
import io.explico.render.RequiredSet
import io.explico.render.ControlIdFromFilename
import io.explico.render.PlainLabel
import io.explico.render.SourceLinks
import java.nio.file.Files
import java.nio.file.Path

/** The exact opa version the demo's guidance recommends (spec §13.2), matching CI's pin (spec §10.2). */
private const val PINNED_OPA_VERSION = "1.19.0"

/** The one command tree (spec §14.80 step 6): `main` runs it and `CliSurface` dumps it, so the committed CLI baseline cannot describe anything but the real CLI. */
internal fun explicoCommandTree(): CoreCliktCommand = ExplicoCommand().subcommands(RenderCommand(), DiffCommand(), VersionCommand(), DemoCommand())

/** The CLI entry point. `internal` since the §14.80 freeze: it is the launcher (`Main-Class`, `java -cp ... io.explico.cli.MainKt`), not library API. */
internal fun main(args: Array<String>) {
    explicoCommandTree().main(args)
}

private class ExplicoCommand : NoOpCliktCommand(name = "explico") {
    override fun help(context: Context): String =
        "Turn Rego policies into documentation a reviewer can read -- every statement derived mechanically, never guessed"
}

/** One requirement per line; blank lines and lines starting with `#` (after leading spaces) are ignored, each line is trimmed, duplicates collapse keeping the first (spec §14.46). */
internal fun parseRequirements(text: String): List<String> =
    text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.distinct()

private fun parseRegoVersion(name: String): RegoVersion = if (name == "v0") RegoVersion.V0 else RegoVersion.V1

private class RenderCommand : CliktCommand(name = "render") {
    override fun help(context: Context): String = "Render a directory of Rego policies into Markdown documentation"

    private val policyDir by argument(help = "Directory containing .rego policy files")
        .path(mustExist = true, canBeFile = false)
    private val out by option(
        "--out",
        help = "Output DIRECTORY for the rendered Markdown; it is deleted and recreated on every run, so it must be empty, missing or a directory explico wrote earlier (see --force)",
    ).path().required()
    private val force by option(
        "--force",
        help = "Replace --out even when it is not empty and was not written by explico. Never overrides the safety refusals: --out may not be, or contain, the policies, --examples or --data, " +
            "the working directory, your home directory or a filesystem root",
    ).flag()
    private val examplesDir by option("--examples", help = "Directory of worked-example fixtures (*.json)")
        .path(mustExist = true, canBeFile = false)
    private val dataDir by option("--data", help = "Data document/directory for worked-example evaluation")
        .path(mustExist = true)
    private val regoVersion by option("--rego-version", help = "Rego syntax of the policies: v1 (default) or v0 (legacy, e.g. the Gatekeeper library)")
        .choice("v1", "v0").default("v1")
    private val generateExamples by option(
        "--generate-examples",
        help = "Also derive worked examples from each rule's conditions where --examples leaves a gap (each verified by opa eval; runs the policy)",
    ).flag()
    private val json by option("--json", help = "Also write explico.json, the machine-readable result (see docs/json-output.md)").flag()
    private val requirementsFile by option(
        "--requirements",
        help = "Text file listing the requirements to evidence, one per line (# comments allowed); adds a requirements-coverage table to index.md",
    ).path(mustExist = true, canBeDir = false)
    private val sourceUrl by option(
        "--source-url",
        help = "URL template linking every card to its Rego line, with {file} (required) and {line}, e.g. https://github.com/ORG/REPO/blob/COMMIT/policies/{file}#L{line}",
    ).convert { value -> try { SourceLinks.of(value); value } catch (e: IllegalArgumentException) { fail(e.message ?: "invalid template") } }
    private val controlIdFrom by option(
        "--control-id-from",
        help = "Derive a control id from each rule's source FILE NAME when its METADATA has none: a regex with a named group, e.g. 'ctrl_(?<id>\\d+)' (METADATA's control-id always wins; `render` only)",
    ).convert { value -> try { ControlIdFromFilename.validatePattern(value) } catch (e: IllegalArgumentException) { fail(e.message ?: "invalid pattern") } }
    private val controlIdTemplate by option(
        "--control-id-template",
        help = "How a derived id is written, with {id} for the captured text, e.g. 'CTRL-{id}' (default: {id}); needs --control-id-from",
    ).convert { value -> try { ControlIdFromFilename.validateTemplate(value) } catch (e: IllegalArgumentException) { fail(e.message ?: "invalid template") } }
    private val groupByControl by option(
        "--group-by-control",
        help = "Give every control id shared by two or more rules ONE page, controls/<id>.md, instead of scattering its cards over the package pages (the index lists it once)",
    ).flag()
    private val emitSituations by option(
        "--emit-situations",
        help = "Also write situations/<policy file>.rego: for each rule explico numbers into situations, a companion Rego rule (<name>__situation) saying which one applied, built from the rule's own statements, for an application that evaluates the policy itself (e.g. compiled to WebAssembly); needs Rego v1; write --out outside the policy directory",
    ).flag()
    private val companionUrl by option(
        "--companion-url",
        help = "A second URL template linking each card (and package page) to the module that owns the policy, with {file}, {package} (the Rego package) and {line}; needs {file} or {package}, e.g. https://host/modules/{package}/{file}#L{line}",
    ).convert { value -> try { CompanionLinks.of(value, "module"); value } catch (e: IllegalArgumentException) { fail(e.message ?: "invalid template") } }
    private val companionLabel by option(
        "--companion-label",
        help = "The text of the --companion-url link (default: module); plain text, without [ ] | ` * or a line break",
    ).convert { value -> try { CompanionLinks.validateLabel(value); value } catch (e: IllegalArgumentException) { fail(e.message ?: "invalid label") } }
    private val failUnder by option("--fail-under", help = "Exit 5 if overall rendering coverage is below this percent (0-100); the output is still written")
        .int().restrictTo(0..100)
    private val failOnGaps by option(
        "--fail-on-gaps",
        help = "Exit 5 if the documentation has gaps: a control with no description or framework (or whatever --must-have names), a shared title, or (with --requirements) a requirement with no control",
    ).flag()

    private val requiredProperties by option(
        "--must-have",
        help = "With --fail-on-gaps: the documentation properties a control must have, comma-separated from title, description, frameworks, fixture, verdict (default: description,frameworks). fixture and verdict need --examples",
    )

    override fun run() {
        // Spec §14.64 (renamed from --require in §14.80 step 5): --must-have only changes what --fail-on-gaps fails on, so alone it would silently do nothing.
        if (requiredProperties != null && !failOnGaps) throw UsageError("--must-have only changes what --fail-on-gaps fails on; add --fail-on-gaps")
        // Names are checked here, before anything else (and before opa is needed): an unknown one, or fixture/verdict with no --examples.
        val required = requiredProperties?.split(',')?.map { it.trim() }
        if (required != null) {
            try { RequiredSet.of(required, examplesSupplied = examplesDir != null) } catch (e: IllegalArgumentException) { throw UsageError(e.message ?: "invalid --must-have") }
        }
        // A template says how a derived id is written; with no pattern it would silently do nothing, so it is a usage error (checked before anything else).
        if (controlIdTemplate != null && controlIdFrom == null) throw UsageError("--control-id-template needs --control-id-from (it says how a derived id is written)")
        // Likewise a label with no link to label.
        if (companionLabel != null && companionUrl == null) throw UsageError("--companion-label needs --companion-url (it names the link that option adds)")
        // Spec §14.80: refuse a dangerous --out BEFORE opa is needed or any work is done; the same check runs again at the write, which is the only place anything is deleted.
        val inputs = listOfNotNull(policyDir, examplesDir, dataDir, requirementsFile)
        refusalExit { OutputGuard.checkDirectory(out, force, inputs) }
        requireOpaOrExit()
        try {
            val policySet = Explico.load(policyDir, parseRegoVersion(regoVersion))
            val examples = examplesDir?.let { reading(it) { Explico.loadExamples(it) } }
            val rendered = Explico.render(
                policySet,
                RenderOptions(
                    examples = examples, dataDir = dataDir, generateExamples = generateExamples, json = json,
                    requirements = requirementsFile?.let { file -> reading(file) { parseRequirements(Files.readString(file)) } } ?: emptyList(), sourceUrl = sourceUrl,
                    controlId = controlIdFrom?.let { ControlIdOptions(it, controlIdTemplate ?: ControlIdFromFilename.DEFAULT_TEMPLATE) },
                    groupByControl = groupByControl, companion = companionUrl?.let { CompanionLink(it, companionLabel ?: "module") }, required = required, emitSituations = emitSituations,
                ),
            )
            writing(out) { rendered.writeTo(out, force, inputs) }
            echo("Rendering coverage: ${rendered.coverage.rendered} of ${rendered.coverage.total} conditions (${rendered.coverage.percent}%)")
            rendered.generatedExamples?.let { echo("Generated examples for ${it.rulesWithExamples} of ${it.rulesConsidered} rules") }
            // Spec §14.67: what is suboptimal, without opening index.md. Nothing at all when there is nothing to say; printed before any gate message (a gate never suppresses output).
            if (rendered.findings.isNotEmpty()) {
                echo("Documentation quality (see index.md):")
                rendered.findings.forEach { echo("  - $it") }
            }
            val failures = listOfNotNull(
                failUnder?.let { Gates.failUnder(rendered.coverage, it) },
                if (failOnGaps) Gates.failOnGaps(rendered.gaps) else null,
            )
            if (failures.isNotEmpty()) failWith(Gates.EXIT_GATE_FAILED, failures.joinToString("\n"))
        } catch (e: ExplicoException) {
            // One place maps the family to exit codes (spec §14.80 step 3). Includes a malformed custom.mask (spec §14.61), refused rather than ignored: nothing has been written yet.
            failWith(exitCodeOf(e), messageOf(e, "render"))
        } catch (e: IllegalArgumentException) {
            // e.g. --must-have fixture with an examples directory that holds no fixture (spec §14.64); the library words it, nothing has been written yet.
            failWith(1, e.message ?: "invalid option")
        }
    }
}

private class DiffCommand : CliktCommand(name = "diff") {
    override fun help(context: Context): String = "Compare two policy versions and write a change report"

    private val oldDir by argument(help = "Old policy directory").path(mustExist = true, canBeFile = false)
    private val newDir by argument(help = "New policy directory").path(mustExist = true, canBeFile = false)
    private val out by option("--out", help = "Output FILE for the change report (an existing file is overwritten; a directory is refused)").path().required()
    private val regoVersion by option("--rego-version", help = "Rego syntax of the policies: v1 (default) or v0 (legacy, e.g. the Gatekeeper library)")
        .choice("v1", "v0").default("v1")
    private val json by option("--json", help = "Also write the machine-readable change report (JSON) to <out>.json, beside the report (see docs/json-output.md)").flag()
    private val groupByControl by option(
        "--group-by-control",
        help = "Allow a control id shared by several rules (one control split over files) instead of failing with exit 4: its rules are told apart by package and rule, one report entry each",
    ).flag()
    private val failOn by option(
        "--fail-on",
        help = "Exit 5 if any control is in one of these categories (comma-separated: ADDED, REMOVED, LOGIC_CHANGED, DOCS_CHANGED), or, with EXAMPLE_CHANGES, if any fixture of --examples changed outcome between the two versions; the report is still written",
    ).convert { value -> try { Gates.parseFailOn(value) } catch (e: IllegalArgumentException) { fail(e.message ?: "invalid categories") } }

    private val oldData by option(
        "--old-data",
        help = "Reference data of the old version (a file or directory). With --new-data, a control whose referenced data differs is reported as LOGIC_CHANGED",
    ).path(mustExist = true)
    private val newData by option("--new-data", help = "Reference data of the new version (a file or directory); see --old-data").path(mustExist = true)

    private val oldLabel by option("--old-label", help = "A name for the old version (e.g. v1.2), shown next to its digest in the report's Compared block")
    private val newLabel by option("--new-label", help = "A name for the new version, shown next to its digest in the report's Compared block")
    private val examplesDir by option(
        "--examples",
        help = "Example fixtures (as for render): the report lists, per control, the fixtures whose outcome differs between the two versions",
    ).path(mustExist = true, canBeFile = false)

    override fun run() {
        // The JSON goes beside the report: --out with .json appended (so it can never be the report itself).
        val jsonOut = if (json) Path.of(out.toString() + ".json") else null
        val gateOnExamples = failOn?.exampleChanges == true
        // Cheapest preconditions first, before opa is needed or anything is written (spec §14.73, §14.74): these options only make sense together.
        if ((oldData == null) != (newData == null)) throw UsageError("--old-data and --new-data must be given together (only ${if (oldData != null) "--old-data" else "--new-data"} was given)")
        // Spec §14.76: the same plain-text rules as the library, reported against the option the user typed.
        oldLabel?.let { checkLabel(it, "--old-label") }
        newLabel?.let { checkLabel(it, "--new-label") }
        if (gateOnExamples && examplesDir == null) throw UsageError("--fail-on EXAMPLE_CHANGES needs --examples (there is nothing to compare without example fixtures)")
        // Spec §14.80: a directory given as an output file is refused before opa is needed.
        refusalExit { OutputGuard.checkFile(out); jsonOut?.let { OutputGuard.checkFile(it) } }
        requireOpaOrExit()
        try {
            val old = Explico.load(oldDir, parseRegoVersion(regoVersion))
            val new = Explico.load(newDir, parseRegoVersion(regoVersion))
            val examples = examplesDir?.let { reading(it) { Explico.loadExamples(it) } }
            // An empty examples directory is "not supplied" to the library; a gate on it would pass having looked at nothing.
            if (gateOnExamples && examples != null && examples.fixtures.isEmpty()) throw UsageError("--fail-on EXAMPLE_CHANGES: the --examples directory holds no fixture, so there is nothing to compare")
            // The CLI always showed the Compared block (spec §14.76), so it always asks for the digests.
            val report = Explico.diff(
                old, new,
                DiffOptions(
                    groupByControl = groupByControl, data = if (oldData != null && newData != null) DataVersions(oldData!!, newData!!) else null, examples = examples,
                    labels = if (oldLabel != null || newLabel != null) VersionLabels(oldLabel, newLabel) else null, digests = true,
                ),
            )
            writing(out) { report.writeTo(out) }
            jsonOut?.let { target -> writing(target) { OutputWriter.writeFile(target, report.json) } }
            echo(summarySection(report.markdown))
            val gateMessages = listOfNotNull(
                failOn?.takeIf { it.categories.isNotEmpty() }?.let { Gates.failOn(report.entries, it.categories) },
                if (gateOnExamples) Gates.failOnExampleChanges(report.exampleChanges, report.exampleComparison) else null,
            )
            if (gateMessages.isNotEmpty()) failWith(Gates.EXIT_GATE_FAILED, gateMessages.joinToString("\n"))
        } catch (e: ExplicoException) {
            failWith(exitCodeOf(e), messageOf(e, "diff"))
        }
    }
}

/** Throws a Clikt usage error (exit 1) naming [option] when [value] is not a plain-text label (spec §14.76). */
private fun checkLabel(value: String, option: String) {
    try {
        PlainLabel.validate(value, option)
    } catch (e: IllegalArgumentException) {
        throw UsageError(e.message ?: "$option is not plain text")
    }
}

private class VersionCommand : CliktCommand(name = "version") {
    override fun help(context: Context): String = "Print explico's version"

    override fun run() {
        echo("explico ${explicoVersion()}")
    }
}

private class DemoCommand : CliktCommand(name = "demo") {
    override fun help(context: Context): String = "Render the bundled sample policies into ./explico-demo to see what explico produces"

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
            val rendered = Explico.render(policySet, RenderOptions(examples = examples, dataDir = demoDir.resolve("data/release/data.json")))
            val outDir = demoDir.resolve("docs")
            writing(outDir) { rendered.writeTo(outDir) }

            echo("Rendered ${rendered.files.size} documents to $outDir/ (${rendered.coverage.percent}% coverage).")
            echo("Open ${outDir.resolve("release-approvals.md")} to see a rendered control card.")
        } catch (e: ExplicoException) {
            failWith(exitCodeOf(e), messageOf(e, "demo"))
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
    } catch (e: OpaNotAvailableException) {
        failWith(exitCodeOf(e), messageOf(e, "opa"))
    }
}

/** Prints [message] to stderr with no stack trace, then exits with [exitCode] (spec §8.2). */
private fun CliktCommand.failWith(exitCode: Int, message: String): Nothing {
    echo(message, err = true)
    throw ProgramResult(exitCode)
}

/**
 * Runs a write; a file-system failure (a dangling symlink, a parent that is a file, no permission) is exit 1 with ONE line naming [target], never a JVM stack trace (spec §8.2).
 * Wraps the write only, so an `IOException` from reading the inputs is never mislabelled. A refusal ([OutputDirectoryException]) passes through to the command's own handler.
 */
private fun CliktCommand.writing(target: Path, write: () -> Unit) {
    try { write() } catch (e: java.io.IOException) { failWith(1, "could not write $target: ${e.javaClass.simpleName}${e.message?.let { ": " + it.lines().first() } ?: ""}") }
}

/**
 * Reads an input; a file that cannot be read (not text, a dangling link, no permission) is exit 1 with ONE line naming [source], never a JVM stack trace (spec §8.2, security review L4).
 * Wraps the read only, so a write failure is never mislabelled.
 */
private fun <T> CliktCommand.reading(source: Path, read: () -> T): T =
    try { read() } catch (e: java.io.IOException) { failWith(1, "could not read $source: ${e.javaClass.simpleName}${e.message?.let { ": " + it.lines().first() } ?: ""}") }

/** Runs a pre-flight output check; a refusal is exit 1 with the guard's single line (spec §14.80), never a stack trace or Clikt's usage text. */
private fun CliktCommand.refusalExit(check: () -> Unit) {
    try { check() } catch (e: OutputDirectoryException) { failWith(1, e.message ?: "refusing to write the output") }
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
