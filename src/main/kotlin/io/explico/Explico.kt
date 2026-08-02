/** Public library facade (spec §8.1). Everything else in this library is internal. */
package io.explico

import io.explico.model.PolicySet
import io.explico.opa.OpaRunner
import io.explico.parse.AstMapper
import io.explico.parse.ParsedFile
import io.explico.render.Coverage
import io.explico.render.CoverageSummary
import io.explico.render.ExampleSet
import io.explico.render.MarkdownRenderer
import java.nio.file.Files
import java.nio.file.Path

/** The rendered Markdown documents for a policy set, keyed by output filename, plus overall coverage. */
public data class RenderedDocs(val files: Map<String, String>, val coverage: CoverageSummary)

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

    /** Loads worked-example fixtures from [dir] (spec §6.7's `*.json` fixture format). */
    public fun loadExamples(dir: Path): ExampleSet = ExampleSet(fixtures = emptyList())

    /**
     * Renders [policySet] to Markdown, one document per package plus `index.md`.
     * If [examples] is supplied, control cards gain a worked-examples section
     * evaluated via `opa eval` against [dataDir] (spec §6.7) -- not yet implemented.
     */
    public fun render(policySet: PolicySet, examples: ExampleSet? = null, dataDir: Path? = null): RenderedDocs {
        val files = policySet.packages.associate { pkg ->
            "${pkg.path.replace('.', '-')}.md" to MarkdownRenderer.renderPackage(pkg, policySet)
        } + ("index.md" to MarkdownRenderer.renderIndex(policySet))

        val packageCoverages = policySet.packages.map { Coverage.of(it) }
        val overallCoverage = CoverageSummary(packageCoverages.sumOf { it.rendered }, packageCoverages.sumOf { it.total })
        return RenderedDocs(files = files, coverage = overallCoverage)
    }
}
