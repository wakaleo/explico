/** Public library facade (spec §8.1). Everything else in this library is internal. */
package io.explico

import io.explico.diff.ChangedSet
import io.explico.diff.DataChangeIndex
import io.explico.diff.DataChanges
import io.explico.diff.DiffEntry
import io.explico.diff.DiffHeader
import io.explico.diff.DiffJson
import io.explico.diff.DiffRenderer
import io.explico.diff.ExampleChanges
import io.explico.diff.PolicyDiff
import io.explico.generate.ExampleGenerator
import io.explico.generate.GenerationResult
import io.explico.model.Operand
import io.explico.model.PolicySet
import io.explico.model.RegoVersion
import io.explico.opa.OpaRunner
import io.explico.parse.AstMapper
import io.explico.parse.ParsedFile
import io.explico.render.CompanionLinks
import io.explico.render.InputMask
import io.explico.render.InvalidMaskException
import io.explico.render.ControlGroups
import io.explico.render.ControlIdFromFilename
import io.explico.render.Coverage
import io.explico.render.CoverageSummary
import io.explico.render.JsonExport
import io.explico.render.ExampleSet
import io.explico.render.MarkdownRenderer
import io.explico.render.PlainLabel
import io.explico.render.PolicyDigest
import io.explico.render.Quality
import io.explico.render.RequiredSet
import io.explico.render.SourceLinks
import io.explico.render.ReferenceData
import io.explico.render.WorkedExample
import io.explico.render.WorkedExamples
import io.explico.render.parseExampleSet
import kotlinx.serialization.json.JsonElement
import java.nio.file.Files
import java.nio.file.Path

/**
 * The rendered Markdown documents for a policy set, keyed by output filename, plus overall coverage.
 * [generatedExamples] is null unless generation was requested (`generateExamples = true`).
 */
public data class RenderedDocs @JvmOverloads constructor(
    val files: Map<String, String>,
    val coverage: CoverageSummary,
    val generatedExamples: GeneratedExamplesSummary? = null,
    /** One line per documentation gap (spec §14.47), always computed; empty when the documentation is complete. */
    val gaps: List<String> = emptyList(),
    /**
     * One line per kind of finding the index's *Documentation quality* section shows, in the section's order, worded here once (`2 controls have no description`).
     * Advisory -- unlike [gaps] nothing fails on it, and a control with no description is both a gap line and a finding line (worded differently, by design). Counts
     * are the index's (controls, comment blocks, conditions shown as source, shared titles); the example-based kinds appear only when example fixtures were given.
     * Empty when the section is absent. Spec §14.67.
     */
    val findings: List<String> = emptyList(),
) {
    /**
     * Replaces the directory [dir] with [files] (spec §14.80 step 1): the directory is deleted and recreated, so it is checked first and nothing is touched when it is refused.
     * Allowed: a directory that does not exist, is empty, or holds the marker file `.explico-output` (written here, with every directory this method writes). A non-empty
     * directory without the marker is refused unless [force]. Never allowed, whatever [force] says: a filesystem root, the user's home directory or a parent of it, the working
     * directory or a parent of it, an existing file, and a directory that is or contains one of the [protect]ed paths (pass the policy, example and data paths you rendered
     * from). Paths are compared by real path, so `..` and symlinks do not get round a refusal.
     *
     * @throws OutputDirectoryException when the write is refused; nothing has been deleted or written.
     */
    @JvmOverloads
    public fun writeTo(dir: Path, force: Boolean = false, protect: List<Path> = emptyList()) {
        OutputWriter.writeDirectory(dir, files, force, protect)
    }
}

/**
 * What `--generate-examples` achieved: [rulesWithExamples] of the [rulesConsidered] rules (cards with a body, not functions
 * or split defaults) received at least one verified generated example.
 */
public data class GeneratedExamplesSummary(val rulesWithExamples: Int, val rulesConsidered: Int)

/** The classified change entries between two policy versions, plus the rendered change report (spec §7). */
public data class DiffReport @JvmOverloads constructor(
    @ExplicoStructuralModel val entries: List<DiffEntry>,
    val markdown: String,
    /** The machine-readable change report (schema `explico/diff/1`, spec §14.45). */
    val json: String = "",
    /** Fixtures whose outcome changed between the versions (empty unless `diff` was given example fixtures). */
    val exampleChanges: List<io.explico.diff.ExampleChange> = emptyList(),
    /** What the example comparison covered (spec §14.74); null unless `diff` was given example fixtures. */
    val exampleComparison: io.explico.diff.ExampleComparison? = null,
    /** `sha256:<hex>` of the old and the new policy directory (the digest `render` prints in `index.md`); empty unless `diff` was given both policy directories (spec §14.76). */
    val oldDigest: String = "",
    val newDigest: String = "",
    /** The changed controls (everything but `UNCHANGED`), in the report's order, as plain values that need no structural model: see [io.explico.diff.DiffChange]. The same list as `explico.json`'s `changes`. */
    val changes: List<io.explico.diff.DiffChange> = emptyList(),
) {
    /**
     * Writes [markdown] to [file], creating its parent directories and replacing a regular file (spec §14.80 step 1). An existing directory is refused (it would mean `--out`
     * pointing at a directory), and nothing can force it; no marker is written. A file needs no `force`, unlike [RenderedDocs.writeTo] which can replace a directory.
     *
     * @throws OutputDirectoryException when [file] is an existing directory.
     */
    public fun writeTo(file: Path) {
        OutputWriter.writeFile(file, markdown)
    }
}

/**
 * Renders `.rego` policy directories into Markdown and reports rendering coverage.
 *
 * Every member is `@JvmStatic` (spec §13.5): from Java, this is `Explico.load(dir)`, not
 * `Explico.INSTANCE.load(dir)` -- a Kotlin `object`'s members are otherwise only reachable through
 * the singleton instance from Java's side. [render]'s two optional trailing parameters are also
 * `@JvmOverloads`, so a Java caller can write `Explico.render(policySet, policyDir)` without
 * passing explicit `null`s Kotlin's default-parameter syntax doesn't generate for Java.
 */
public object Explico {
    /**
     * Parses every `.rego` file under [policyDir] (via the `opa` binary) into a [PolicySet]. Pass
     * [RegoVersion.V0] for policies written in the legacy Rego syntax (see [RegoVersion]); the default
     * is [RegoVersion.V1], which is what OPA 1.x accepts without flags.
     *
     * @throws OpaFailedException when `opa` rejects a file (a Rego syntax error); @throws OpaNotAvailableException when `opa` is missing or not 1.x; [java.io.IOException] when [policyDir] does not exist.
     */
    @JvmStatic
    @JvmOverloads
    public fun load(policyDir: Path, regoVersion: RegoVersion = RegoVersion.V1): PolicySet {
        val regoFiles = Files.walk(policyDir).use { paths ->
            paths.filter { it.toString().endsWith(".rego") }.sorted().toList()
        }
        val parsedFiles = regoFiles.map { file ->
            val relativePath = policyDir.relativize(file).toString().replace(java.io.File.separatorChar, '/')
            ParsedFile(relativePath, OpaRunner.parse(file, regoVersion))
        }
        val inspectResult = OpaRunner.inspect(policyDir, regoVersion)
        return AstMapper.mapPolicySet(parsedFiles, inspectResult).copy(regoVersion = regoVersion, sourceDir = policyDir.toAbsolutePath().normalize())
    }

    /**
     * Loads worked-example fixtures from [dir] (spec §6.7's `*.json` fixture format), in
     * filename order. Throws [io.explico.render.DuplicateFixtureNameException] if two fixtures
     * share a `name`.
     *
     * @throws java.io.IOException when [dir] does not exist.
     */
    @JvmStatic
    public fun loadExamples(dir: Path): ExampleSet {
        val jsonFiles = Files.list(dir).use { paths ->
            paths.filter { it.toString().endsWith(".json") }.sorted().toList()
        }
        return parseExampleSet(jsonFiles.map { Files.readString(it) })
    }

    /**
     * Renders [policies] to Markdown, one document per package plus `index.md`, as [options] ask (spec §14.80 step 2); `RenderOptions()` is the plain render. The policies are
     * read again from [PolicySet.sourceDir] -- worked examples (spec §6.7) re-invoke `opa eval` against the actual `.rego` files, and the digest below is computed from them -- so
     * the set must come from [load]; a set built by hand throws [IllegalArgumentException].
     *
     * If [RenderOptions.examples] is supplied, control cards gain a worked-examples section evaluated via `opa eval` against [RenderOptions.dataDir]; a fixture whose evaluation
     * fails is skipped with a stderr warning. With [RenderOptions.generateExamples], rules whose author examples leave a gap also get a separately labelled **Generated examples**
     * section, each verified by `opa eval` before it is shown (spec §14.39); like author examples, generation executes the policy -- see SECURITY.md.
     *
     * Every render also computes a SHA-256 over the `.rego` files (spec §14.40) and shows it on `index.md` and every package document, so a reader can confirm which exact policy
     * version the documentation describes. It depends only on the policy source -- never the examples, the data, or explico's own version. When [RenderOptions.dataDir] is
     * supplied, a card whose bodies read a plain `data.*` document also gains a **Reference data** section (spec §14.41).
     *
     * Every option is validated before any (slow) `opa` work: a bad template, pattern, label or required property is an [IllegalArgumentException]; a malformed `custom.mask` is an
     * [io.explico.render.InvalidMaskException]; duplicate fixture names are a [io.explico.render.DuplicateFixtureNameException].
     *
     * @throws OpaFailedException when `opa` rejects the input while evaluating; @throws OpaNotAvailableException when `opa` is missing; (the unexpected-argument cases above stay [IllegalArgumentException]).
     */
    @JvmStatic
    @JvmOverloads
    public fun render(policies: PolicySet, options: RenderOptions = RenderOptions()): RenderedDocs {
        val policyDir = requireSourceDir(policies, "render")
        return renderPolicies(
            policies, policyDir, options.examples, options.dataDir, options.generateExamples, options.json, options.requirements, options.sourceUrl,
            options.controlId?.from, options.controlId?.template ?: ControlIdFromFilename.DEFAULT_TEMPLATE, options.groupByControl,
            options.companion?.url, options.companion?.label ?: "module", options.required,
        )
    }

    /** The directory [set] was loaded from, or an [IllegalArgumentException] saying where to get one (spec §14.80 step 2). */
    private fun requireSourceDir(set: PolicySet, what: String): Path =
        set.sourceDir ?: throw IllegalArgumentException(
            "$what needs the policy set's sourceDir (the directory it was loaded from, which it reads the policies from again), but this set has none: " +
                "build it with Explico.load(dir), not by hand",
        )

    private fun renderPolicies(
        policySet: PolicySet,
        policyDir: Path,
        examples: ExampleSet?,
        dataDir: Path?,
        generateExamples: Boolean,
        json: Boolean,
        requirements: List<String>,
        sourceUrl: String?,
        controlIdFrom: String?,
        controlIdTemplate: String,
        groupByControl: Boolean,
        companionUrl: String?,
        companionLabel: String,
        required: List<String>?,
    ): RenderedDocs {
        // Validate the URL template before any (slow) opa work: a template without {file} is the caller's mistake.
        val sourceLinks = SourceLinks.of(sourceUrl)
        // Spec §14.61: a malformed custom.mask is refused, never ignored (it would show the values it was written to hide) -- before any opa work.
        requireValidMasks(policySet)
        // Spec §14.64: likewise the required set -- an unknown property, or one that needs example fixtures when there are none, is refused before any opa work.
        val requiredSet = RequiredSet.of(required, examplesSupplied = examples?.fixtures?.isNotEmpty() == true)
        warnUnmatchedMasks(policySet, examples)
        // Spec §14.58: likewise the companion template and its label.
        val companion = CompanionLinks.of(companionUrl, companionLabel)
        // Spec §14.55: likewise the control-id options -- an invalid pattern or template is the caller's mistake, found before any opa work.
        val controlIds = ControlIdFromFilename.of(controlIdFrom, controlIdTemplate)
        // From here on everything reads this set: a derived id is just a control-id on the model, so headings, anchors, links, the index and
        // the JSON need no special case. Without a pattern it IS the caller's set, unchanged.
        val set = controlIds?.applyTo(policySet) ?: policySet
        val examplesByPackage = evaluateWorkedExamples(set, policyDir, examples, dataDir)
        val generated = if (generateExamples) generateWorkedExamples(set, policyDir, dataDir, examplesByPackage) else null
        // Spec §14.40: ties every document to an exact policy version. Computed from policyDir alone -- never
        // examples, data, or explico's own version -- so it is independent of how render() was called.
        val policyDigest = PolicyDigest.of(policyDir)
        // Spec §14.41: null (not just empty) when no dataDir was supplied -- the whole "Reference data" section
        // is off, not merely empty, which is how MarkdownRenderer tells the two apart.
        val dataValues = dataDir?.let { resolveReferenceDataValues(set, policyDir, it) }

        // Spec §14.56: null unless asked for; every renderer below treats null as "no grouping", so the output is then exactly what it always was.
        val groups = if (groupByControl) ControlGroups.of(set) else null
        val files = set.packages.associate { pkg ->
            "${pkg.path.replace('.', '-')}.md" to MarkdownRenderer.renderPackage(
                pkg,
                set,
                examplesByPackage[pkg.path] ?: emptyMap(),
                generated?.byPackage?.get(pkg.path) ?: emptyMap(),
                policyDigest,
                dataValues,
                sourceLinks,
                groups,
                companion,
            )
        } + groups?.groups.orEmpty().associate { group ->
            group.page to MarkdownRenderer.renderControlPage(group, set, groups!!, examplesByPackage, generated?.byPackage ?: emptyMap(), policyDigest, dataValues, sourceLinks, companion)
        } + ("index.md" to MarkdownRenderer.renderIndex(set, examplesByPackage, policyDigest, requirements, sourceLinks, groups)) +
            (if (json) mapOf("explico.json" to JsonExport.render(set, policyDigest, examplesByPackage, generated?.byPackage ?: emptyMap(), dataValues, requirements, sourceLinks, groups, companion, requiredSet)) else emptyMap())

        val packageCoverages = set.packages.map { Coverage.of(it) }
        val overallCoverage = CoverageSummary(packageCoverages.sumOf { it.rendered }, packageCoverages.sumOf { it.total })
        val normalisedRequirements = Quality.normalise(requirements)
        val findings = Quality.findingsOf(set, groups, examplesByPackage)
        return RenderedDocs(
            files = files,
            coverage = overallCoverage,
            generatedExamples = generated?.let { GeneratedExamplesSummary(it.rulesWithExamples, it.rulesConsidered) },
            gaps = Quality.gapDescriptions(
                findings,
                normalisedRequirements.takeIf { it.isNotEmpty() }?.let { Quality.coverageOf(set, it, groups) },
                requiredSet,
            ),
            findings = Quality.findingLines(findings),
        )
    }

    /**
     * Classifies every control across [old] -> [new] (spec §7.2) and renders the change report (spec §7.3), as [options] ask (spec §14.80 step 2); `DiffOptions()` is the plain
     * report. Throws [io.explico.diff.DuplicateControlIdException] if either side has two rules sharing a control-id (with [DiffOptions.groupByControl], only if it cannot tell
     * two rules apart: same id, package and rule name).
     *
     * With [DiffOptions.data] (spec §14.73) the report also compares the reference data each control reads: the plain `data.*` documents its conditions name, as OPA reads them
     * from each data file or directory. A control whose documents differ is [io.explico.diff.DiffCategory.LOGIC_CHANGED] even when its Rego is identical, with one `reference data
     * changed` line per document; a document no control reads is never reported, and no Rego rule is ever evaluated. With [DiffOptions.examples] (spec §14.74) the SAME fixtures are
     * evaluated against both versions and every fixture whose outcome changed is reported per control. The report's **Compared** block (spec §14.76: the `sha256:` digests of the
     * two policy directories, the same ones `render` prints in `index.md`) appears only with [DiffOptions.digests] or [DiffOptions.labels].
     *
     * Examples, labels and digests read the policies again from each set's [PolicySet.sourceDir], so those sets must come from [load] ([IllegalArgumentException] otherwise); a plain
     * diff of sets built by hand works.
     */
    @JvmStatic
    @JvmOverloads
    public fun diff(old: PolicySet, new: PolicySet, options: DiffOptions = DiffOptions()): DiffReport {
        // Spec §14.76: the labels are checked before any file is read or opa is run, so a mistake is reported as the mistake it is.
        options.labels?.old?.let { PlainLabel.validate(it, "oldLabel") }
        options.labels?.new?.let { PlainLabel.validate(it, "newLabel") }
        val showHeader = options.digests || options.labels != null
        val needsDirs = showHeader || options.examples != null
        return diffPolicies(
            old, new, options.groupByControl, options.data?.old, options.data?.new, options.examples,
            if (needsDirs) requireSourceDir(old, "diff with examples, labels or digests") else null,
            if (needsDirs) requireSourceDir(new, "diff with examples, labels or digests") else null,
            options.labels?.old, options.labels?.new, showHeader,
        )
    }

    private fun diffPolicies(
        old: PolicySet,
        new: PolicySet,
        groupByControl: Boolean,
        dataOld: Path?,
        dataNew: Path?,
        examples: ExampleSet?,
        oldPolicyDir: Path?,
        newPolicyDir: Path?,
        oldLabel: String?,
        newLabel: String?,
        showHeader: Boolean,
    ): DiffReport {
        val compared = PolicyDiff.diff(old, new, groupByControl)

        var entries = compared
        var dataChanges = DataChangeIndex.NONE
        if (dataOld != null && dataNew != null) {
            val paths = DataChanges.pathsToResolve(compared, old, new)
            fun resolve(data: Path, regoVersion: RegoVersion) = paths.associateWith { OpaRunner.evalDataPath(listOf(data), ReferenceData.dottedSegments(it), regoVersion) }
            val result = DataChanges.apply(compared, old, new, resolve(dataOld, old.regoVersion), resolve(dataNew, new.regoVersion))
            entries = result.entries
            dataChanges = result.changes
        }

        // An examples set with no fixture is "not supplied": there is nothing to say about it, not even that nothing changed.
        val exampleChanges = if (examples != null && examples.fixtures.isNotEmpty()) {
            ExampleChanges.compute(entries, evaluateWorkedExamples(old, oldPolicyDir!!, examples, dataOld), evaluateWorkedExamples(new, newPolicyDir!!, examples, dataNew))
        } else {
            null
        }
        // Spec §14.76: which two versions this report compares -- the same digest `render` prints in index.md -- only when asked (digests or labels).
        val header = if (showHeader && oldPolicyDir != null && newPolicyDir != null) {
            DiffHeader("sha256:${PolicyDigest.of(oldPolicyDir)}", "sha256:${PolicyDigest.of(newPolicyDir)}", oldLabel, newLabel)
        } else {
            null
        }
        return DiffReport(
            entries,
            DiffRenderer.render(entries, old, new, dataChanges, dataSupplied = dataOld != null, exampleChanges = exampleChanges, header = header),
            DiffJson.render(entries, dataChanges, exampleChanges, header),
            exampleChanges?.flat(entries).orEmpty(),
            exampleChanges?.comparison,
            header?.oldDigest.orEmpty(),
            header?.newDigest.orEmpty(),
            ChangedSet.of(entries).changes,
        )
    }

    /**
     * Spec §14.61: a mask path that hides nothing in any example (a typo, the wrong case, a leading `input.`) would let the very values it was written for through
     * without a sign, so each is warned about once on stderr. Nothing to say without example fixtures.
     */
    private fun warnUnmatchedMasks(policySet: PolicySet, examples: ExampleSet?) {
        val inputs = examples?.fixtures?.map { it.input }?.takeIf { it.isNotEmpty() } ?: return
        for (pkg in policySet.packages) for (rule in pkg.rules) {
            val unmatched = InputMask.of(rule.metadata?.projection?.mask)?.unmatchedPaths(inputs) ?: continue
            for (path in unmatched.sorted()) {
                val hint = if (path.startsWith("input.")) " -- paths are keys INSIDE the fixture input: drop the leading 'input.'" else " -- check the spelling and case (paths are keys inside the fixture input)"
                System.err.println("explico: warning: custom.mask path '$path' on rule '${pkg.path}.${rule.name}' hides no string or number in any example fixture$hint")
            }
        }
    }

    /** Throws [InvalidMaskException] listing every rule (sorted) whose `custom.mask` is malformed. */
    private fun requireValidMasks(policySet: PolicySet) {
        val problems = policySet.packages.flatMap { pkg ->
            pkg.rules.mapNotNull { rule -> rule.metadata?.projection?.maskProblem?.let { "custom.mask on rule '${pkg.path}.${rule.name}': $it" } }
        }.distinct().sorted()
        if (problems.isNotEmpty()) throw InvalidMaskException("a rule's custom.mask is malformed and would not hide what it names -- fix it: ${problems.joinToString("; ")}")
    }

    /**
     * Resolves every distinct `data.*` path any rule in [policySet] reads (spec §14.41), deduped across the
     * whole render -- one `opa eval` per distinct path, not per card, so a path shared by several rules (or
     * referenced from both the original collection and an iterated tail that truncates to the same prefix) is
     * only ever evaluated once.
     */
    private fun resolveReferenceDataValues(policySet: PolicySet, policyDir: Path, dataDir: Path): Map<Operand.Path, JsonElement?> {
        val paths = LinkedHashSet<Operand.Path>()
        for (pkg in policySet.packages) {
            for (rule in pkg.rules) paths.addAll(ReferenceData.referencedDataPaths(rule, policySet))
        }
        val dataDirs = listOf(policyDir, dataDir)
        return paths.associateWith { path -> OpaRunner.evalDataPath(dataDirs, ReferenceData.dottedSegments(path), policySet.regoVersion) }
    }

    /** Derives and verifies generated examples; each candidate is evaluated through a short-lived temp input file. */
    private fun generateWorkedExamples(
        policySet: PolicySet,
        policyDir: Path,
        dataDir: Path?,
        authorOutcomes: Map<String, Map<String, List<WorkedExample>>>,
    ): GenerationResult {
        val dataDirs = listOfNotNull(policyDir, dataDir)
        return ExampleGenerator.generate(policySet, authorOutcomes) { packagePath, fixture ->
            val inputFile = Files.createTempFile("explico-generated-", ".json")
            try {
                Files.writeString(inputFile, fixture.input.toString())
                OpaRunner.eval(inputFile, dataDirs, "data.$packagePath", policySet.regoVersion)
            } finally {
                Files.deleteIfExists(inputFile)
            }
        }
    }

    private fun evaluateWorkedExamples(
        policySet: PolicySet,
        policyDir: Path,
        examples: ExampleSet?,
        dataDir: Path?,
    ): Map<String, Map<String, List<WorkedExample>>> {
        if (examples == null || examples.fixtures.isEmpty()) return emptyMap()

        val inputFiles = examples.fixtures.associateWith { fixture ->
            Files.createTempFile("explico-fixture-", ".json").also { Files.writeString(it, fixture.input.toString()) }
        }
        try {
            val dataDirs = listOfNotNull(policyDir, dataDir)
            return policySet.packages.associate { pkg ->
                pkg.path to WorkedExamples.evaluatePackage(examples.fixtures, pkg) { fixture ->
                    OpaRunner.eval(inputFiles.getValue(fixture), dataDirs, "data.${pkg.path}", policySet.regoVersion)
                }
            }
        } finally {
            inputFiles.values.forEach { Files.deleteIfExists(it) }
        }
    }
}
