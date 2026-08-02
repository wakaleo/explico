/** Public library facade (spec §8.1). Everything else in this library is internal. */
package io.explico

import io.explico.diff.DiffEntry
import io.explico.diff.DiffRenderer
import io.explico.diff.PolicyDiff
import io.explico.model.PolicySet
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

/** The rendered Markdown documents for a policy set, keyed by output filename, plus overall coverage. */
public data class RenderedDocs(val files: Map<String, String>, val coverage: CoverageSummary)

/** The classified change entries between two policy versions, plus the rendered change report (spec §7). */
public data class DiffReport(val entries: List<DiffEntry>, val markdown: String)

/** Renders `.rego` policy directories into Markdown and reports rendering coverage. */
public object Explico {
    /** Parses every `.rego` file under [policyDir] (via the `opa` binary) into a [PolicySet]. */
    public fun load(policyDir: Path): PolicySet {
        val regoFiles = Files.walk(policyDir).use { paths ->
            paths.filter { it.toString().endsWith(".rego") }.sorted().toList()
        }
        val parsedFiles = regoFiles.map { file ->
            val relativePath = policyDir.relativize(file).toString().replace(java.io.File.separatorChar, '/')
            ParsedFile(relativePath, OpaRunner.parse(file))
        }
        val inspectResult = OpaRunner.inspect(policyDir)
        return AstMapper.mapPolicySet(parsedFiles, inspectResult)
    }

    /**
     * Loads worked-example fixtures from [dir] (spec §6.7's `*.json` fixture format), in
     * filename order. Throws [io.explico.render.DuplicateFixtureNameException] if two fixtures
     * share a `name`.
     */
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
     */
    public fun render(policySet: PolicySet, policyDir: Path, examples: ExampleSet? = null, dataDir: Path? = null): RenderedDocs {
        val examplesByPackage = evaluateWorkedExamples(policySet, policyDir, examples, dataDir)

        val files = policySet.packages.associate { pkg ->
            "${pkg.path.replace('.', '-')}.md" to MarkdownRenderer.renderPackage(pkg, policySet, examplesByPackage[pkg.path] ?: emptyMap())
        } + ("index.md" to MarkdownRenderer.renderIndex(policySet, examplesByPackage))

        val packageCoverages = policySet.packages.map { Coverage.of(it) }
        val overallCoverage = CoverageSummary(packageCoverages.sumOf { it.rendered }, packageCoverages.sumOf { it.total })
        return RenderedDocs(files = files, coverage = overallCoverage)
    }

    /**
     * Classifies every control across [old] -> [new] (spec §7.2) and renders the change report
     * (spec §7.3). Throws [io.explico.diff.DuplicateControlIdException] if either side has two
     * rules sharing a control-id.
     */
    public fun diff(old: PolicySet, new: PolicySet): DiffReport {
        val entries = PolicyDiff.diff(old, new)
        return DiffReport(entries, DiffRenderer.render(entries, old, new))
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
                    OpaRunner.eval(inputFiles.getValue(fixture), dataDirs, "data.${pkg.path}")
                }
            }
        } finally {
            inputFiles.values.forEach { Files.deleteIfExists(it) }
        }
    }
}
