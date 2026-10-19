/** Public library facade (spec §8.1). Everything else in this library is internal. */
package io.explico

import io.explico.diff.DiffEntry
import io.explico.diff.DiffRenderer
import io.explico.diff.PolicyDiff
import io.explico.generate.ExampleGenerator
import io.explico.generate.GenerationResult
import io.explico.model.PolicySet
import io.explico.model.RegoVersion
import io.explico.opa.OpaRunner
import io.explico.parse.AstMapper
import io.explico.parse.ParsedFile
import io.explico.render.Coverage
import io.explico.render.CoverageSummary
import io.explico.render.ExampleSet
import io.explico.render.MarkdownRenderer
import io.explico.render.WorkedExample
import io.explico.render.WorkedExamples
import io.explico.render.parseExampleSet
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
)

/**
 * What `--generate-examples` achieved: [rulesWithExamples] of the [rulesConsidered] rules (cards with a body, not functions
 * or split defaults) received at least one verified generated example.
 */
public data class GeneratedExamplesSummary(val rulesWithExamples: Int, val rulesConsidered: Int)

/** The classified change entries between two policy versions, plus the rendered change report (spec §7). */
public data class DiffReport(val entries: List<DiffEntry>, val markdown: String)

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
     */
    @JvmStatic
    @JvmOverloads
    public fun render(
        policySet: PolicySet,
        policyDir: Path,
        examples: ExampleSet? = null,
        dataDir: Path? = null,
        generateExamples: Boolean = false,
    ): RenderedDocs {
        val examplesByPackage = evaluateWorkedExamples(policySet, policyDir, examples, dataDir)
        val generated = if (generateExamples) generateWorkedExamples(policySet, policyDir, dataDir, examplesByPackage) else null

        val files = policySet.packages.associate { pkg ->
            "${pkg.path.replace('.', '-')}.md" to MarkdownRenderer.renderPackage(
                pkg,
                policySet,
                examplesByPackage[pkg.path] ?: emptyMap(),
                generated?.byPackage?.get(pkg.path) ?: emptyMap(),
            )
        } + ("index.md" to MarkdownRenderer.renderIndex(policySet, examplesByPackage))

        val packageCoverages = policySet.packages.map { Coverage.of(it) }
        val overallCoverage = CoverageSummary(packageCoverages.sumOf { it.rendered }, packageCoverages.sumOf { it.total })
        return RenderedDocs(
            files = files,
            coverage = overallCoverage,
            generatedExamples = generated?.let { GeneratedExamplesSummary(it.rulesWithExamples, it.rulesConsidered) },
        )
    }

    /**
     * Classifies every control across [old] -> [new] (spec §7.2) and renders the change report
     * (spec §7.3). Throws [io.explico.diff.DuplicateControlIdException] if either side has two
     * rules sharing a control-id.
     */
    @JvmStatic
    public fun diff(old: PolicySet, new: PolicySet): DiffReport {
        val entries = PolicyDiff.diff(old, new)
        return DiffReport(entries, DiffRenderer.render(entries, old, new))
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
