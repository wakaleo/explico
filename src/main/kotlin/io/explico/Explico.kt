/** Public library facade (spec §8.1). Everything else in this library is internal. */
package io.explico

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
)

/**
 * What `--generate-examples` achieved: [rulesWithExamples] of the [rulesConsidered] rules (cards with a body, not functions
 * or split defaults) received at least one verified generated example.
 */
public data class GeneratedExamplesSummary(val rulesWithExamples: Int, val rulesConsidered: Int)

/** The classified change entries between two policy versions, plus the rendered change report (spec §7). */
public data class DiffReport @JvmOverloads constructor(
    val entries: List<DiffEntry>,
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
)

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
        return AstMapper.mapPolicySet(parsedFiles, inspectResult).copy(regoVersion = regoVersion)
    }

    /**
     * Loads worked-example fixtures from [dir] (spec §6.7's `*.json` fixture format), in
     * filename order. Throws [io.explico.render.DuplicateFixtureNameException] if two fixtures
     * share a `name`.
     */
    @JvmStatic
    public fun loadExamples(dir: Path): ExampleSet {
        val jsonFiles = Files.list(dir).use { paths ->
            paths.filter { it.toString().endsWith(".json") }.sorted().toList()
        }
        return parseExampleSet(jsonFiles.map { Files.readString(it) })
    }

    /**
     * Renders [policySet] to Markdown, one document per package plus `index.md`. [policyDir] must
     * be the same directory [policySet] was loaded from -- worked examples (spec §6.7) re-invoke
     * `opa eval` against the actual `.rego` files, which the domain model alone can't locate. If
     * [examples] is supplied, control cards gain a worked-examples section evaluated via `opa
     * eval` against [dataDir]; a fixture whose evaluation fails is skipped with a stderr warning.
     *
     * With [generateExamples], rules whose author examples leave a gap (no matching case, or no non-matching case) also get a
     * separately labelled **Generated examples** section: inputs derived mechanically from the rule's conditions, each
     * VERIFIED by `opa eval` before it is shown (spec §14.39). Off by default; the output is then byte-identical to a render
     * that never mentions the option. Like author examples, generation executes the policy via `opa eval` -- see SECURITY.md.
     *
     * Every render also computes a SHA-256 over [policyDir]'s `.rego` files (spec §14.40) and shows it on `index.md` and
     * every package document, so a reader can confirm which exact policy version the documentation describes. It depends
     * only on the policy source -- never [examples], [dataDir], or explico's own version.
     *
     * When [dataDir] is supplied, a card whose bodies read a plain `data.*` document (not a loaded policy rule) also
     * gains a **Reference data** section showing what that document holds, resolved by `opa eval`, never read or
     * parsed in Kotlin (spec §14.41). Off entirely -- not merely empty -- without a [dataDir].
     */
    @JvmStatic
    @JvmOverloads
    public fun render(
        policySet: PolicySet,
        policyDir: Path,
        examples: ExampleSet? = null,
        dataDir: Path? = null,
        generateExamples: Boolean = false,
        /** When true, `files` also contains `explico.json` (schema `explico/render/1`, spec §14.45). Off by default. */
        json: Boolean = false,
        /** The requirements (framework strings) the organisation must evidence, for the index's requirements coverage (spec §14.46). */
        requirements: List<String> = emptyList(),
        /** A URL template (`{file}`, `{line}`) that links cards, package pages and the index to the Rego source (spec §14.48). Off by default. */
        sourceUrl: String? = null,
        /**
         * A regular expression with a named group `id`, matched against each rule's source FILE NAME (base name, not the directory), that derives a
         * control id for a rule whose METADATA does not declare one (spec §14.55). Null (the default) derives nothing and the output is unchanged.
         * METADATA's `control-id` always wins; only the id is derived, never a title. Must contain `(?<id>...)`, else [IllegalArgumentException].
         */
        controlIdFrom: String? = null,
        /** How a derived id is written: `{id}` is replaced by the `id` group, e.g. `CTRL-{id}` (default `{id}`). Must contain `{id}`, else [IllegalArgumentException]. */
        controlIdTemplate: String = "{id}",
        /**
         * When true, every control id shared by two or more rules gets ONE page, `controls/<id>.md`, with each rule as a sub-section (sorted by source path), instead
         * of its cards being scattered over the package pages; the index lists the control once and `explico.json` gains a `controls` array (spec §14.56). Off by
         * default, and the output is then unchanged. The returned `files` then contain keys with a `controls/` prefix, so a caller writing them must create the directory.
         */
        groupByControl: Boolean = false,
        /**
         * A second URL template, for the module that OWNS a policy (spec §14.58): `{file}` (the policy file's path, as for [sourceUrl]), `{package}` (the Rego package
         * path, e.g. `app.billing`) and `{line}`, all percent-encoded; at least one of `{file}` and `{package}` is required, else [IllegalArgumentException]. Each card's
         * *defined in* line and each package page's source files then gain a second link, labelled [companionLabel]; `explico.json` gains `companionUrl`.
         * Null (the default) adds nothing and the output is unchanged.
         */
        companionUrl: String? = null,
        /** The text of the second link (default `module`); must be non-blank plain text: no line break, `[`, `]`, `|`, backtick or `*`, else [IllegalArgumentException]. */
        companionLabel: String = "module",
        /**
         * Which documentation properties of a CONTROL count as gaps in [RenderedDocs.gaps] (what `--fail-on-gaps` fails on), by name: `title`, `description`, `frameworks`,
         * `fixture` (a matching worked example) and `verdict` (a declared `custom.verdict` on an object-valued rule). Null (the default) is `description` and `frameworks`,
         * exactly as before; an empty list requires none. Duplicate titles and requirement gaps are always gaps. It never changes the rendered files, except that
         * `explico.json` echoes the effective set as `quality.required`. An unknown name, or `fixture`/`verdict` without [examples], is an [IllegalArgumentException].
         * From Java the parameter is the last of the `@JvmOverloads` chain, so it is only reachable by passing every earlier argument.
         */
        required: List<String>? = null,
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
     * Classifies every control across [old] -> [new] (spec §7.2) and renders the change report (spec §7.3). Throws [io.explico.diff.DuplicateControlIdException]
     * if either side has two rules sharing a control-id (with [groupByControl], only if it cannot tell two rules apart: same id, package and rule name).
     *
     * With [dataOld] and [dataNew] (spec §14.73) the report also compares the reference data each control reads: the plain `data.*` documents its conditions
     * name (the same ones the Reference data block of `render` shows), as OPA reads them from each data file or directory. A control whose documents differ
     * is [io.explico.diff.DiffCategory.LOGIC_CHANGED] -- even when its Rego is identical -- with one `reference data changed` line per document. A document
     * no control reads is never reported, and no Rego rule is ever evaluated. Both or neither: giving only one throws [IllegalArgumentException].
     */
    @JvmStatic
    @JvmOverloads
    public fun diff(
        old: PolicySet,
        new: PolicySet,
        /**
         * When true, a control id shared by two or more rules (one control split over several files) is allowed instead of an error, and any such rule is
         * identified by (control id, package, rule) -- decided per id over BOTH versions. Off by default: shared ids still throw
         * [io.explico.diff.DuplicateControlIdException], exactly as before.
         */
        groupByControl: Boolean = false,
        /** The supplied data of the old version (a JSON/YAML file or a directory, as `render`'s data): see above. Null together with [dataNew] = data is not compared. */
        dataOld: Path? = null,
        /** The supplied data of the new version. */
        dataNew: Path? = null,
        /**
         * Author fixtures (spec §14.74): when given (and not empty), the SAME fixtures are evaluated by OPA against the old and the new policies -- each side with its own
         * data when [dataOld]/[dataNew] are given -- and every fixture whose outcome changed is reported per control (`## Behaviour on your examples`, JSON `examples`,
         * [DiffReport.exampleChanges]). Needs [oldPolicyDir] and [newPolicyDir], the directories the two sets were loaded from (opa re-reads the `.rego` files).
         */
        examples: ExampleSet? = null,
        oldPolicyDir: Path? = null,
        newPolicyDir: Path? = null,
        /**
         * Optional names for the two versions (spec §14.76), shown as `Old (v1.2)` / `New (v1.3)` in the report's **Compared** block and in the JSON. Plain text: not blank,
         * at most 80 characters, no leading/trailing space, no line break, tab or backtick. Need [oldPolicyDir] and [newPolicyDir].
         */
        oldLabel: String? = null,
        newLabel: String? = null,
    ): DiffReport {
        require((dataOld == null) == (dataNew == null)) {
            "dataOld and dataNew must be given together (only ${if (dataOld != null) "dataOld" else "dataNew"} was given)"
        }
        // Spec §14.76: all of this is checked before any file is read or opa is run, so a mistake is reported as the mistake it is.
        require((oldPolicyDir == null) == (newPolicyDir == null)) {
            "oldPolicyDir and newPolicyDir must be given together (only ${if (oldPolicyDir != null) "oldPolicyDir" else "newPolicyDir"} was given): " +
                "they are the directories the report digests and, for examples, the ones opa re-reads"
        }
        require(examples == null || oldPolicyDir != null) { "examples need both oldPolicyDir and newPolicyDir: opa re-reads the .rego files of each version to evaluate the fixtures" }
        require((oldLabel == null && newLabel == null) || oldPolicyDir != null) { "oldLabel and newLabel name the digests of oldPolicyDir and newPolicyDir, which were not given" }
        oldLabel?.let { PlainLabel.validate(it, "oldLabel") }
        newLabel?.let { PlainLabel.validate(it, "newLabel") }
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
        // Spec §14.76: which two versions this report compares -- the same digest `render` prints in index.md -- only when the directories are known.
        val header = if (oldPolicyDir != null && newPolicyDir != null) {
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
