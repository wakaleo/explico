/** Public library facade (spec §8.1). Everything else in this library is internal. */
package io.explico

import io.explico.diff.DiffEntry
import io.explico.diff.DiffJson
import io.explico.diff.DiffRenderer
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
import io.explico.render.ControlGroups
import io.explico.render.ControlIdFromFilename
import io.explico.render.Coverage
import io.explico.render.CoverageSummary
import io.explico.render.JsonExport
import io.explico.render.ExampleSet
import io.explico.render.MarkdownRenderer
import io.explico.render.PolicyDigest
import io.explico.render.Quality
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
    ): RenderedDocs {
        // Validate the URL template before any (slow) opa work: a template without {file} is the caller's mistake.
        val sourceLinks = SourceLinks.of(sourceUrl)
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
            (if (json) mapOf("explico.json" to JsonExport.render(set, policyDigest, examplesByPackage, generated?.byPackage ?: emptyMap(), dataValues, requirements, sourceLinks, groups, companion)) else emptyMap())

        val packageCoverages = set.packages.map { Coverage.of(it) }
        val overallCoverage = CoverageSummary(packageCoverages.sumOf { it.rendered }, packageCoverages.sumOf { it.total })
        val normalisedRequirements = Quality.normalise(requirements)
        return RenderedDocs(
            files = files,
            coverage = overallCoverage,
            generatedExamples = generated?.let { GeneratedExamplesSummary(it.rulesWithExamples, it.rulesConsidered) },
            gaps = Quality.gapDescriptions(Quality.findingsOf(set, groups), normalisedRequirements.takeIf { it.isNotEmpty() }?.let { Quality.coverageOf(set, it, groups) }),
        )
    }

    /**
     * Classifies every control across [old] -> [new] (spec §7.2) and renders the change report
     * (spec §7.3). Throws [io.explico.diff.DuplicateControlIdException] if either side has two
     * rules sharing a control-id (with [groupByControl], only if it cannot tell two rules apart: same id, package and rule name).
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
    ): DiffReport {
        val entries = PolicyDiff.diff(old, new, groupByControl)
        return DiffReport(entries, DiffRenderer.render(entries, old, new), DiffJson.render(entries))
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
