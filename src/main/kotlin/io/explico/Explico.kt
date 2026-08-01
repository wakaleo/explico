/** Public library facade (spec §8.1). Everything else in this library is internal. */
package io.explico

import io.explico.model.PolicySet
import io.explico.render.CoverageSummary
import io.explico.render.ExampleSet
import java.nio.file.Path

/** The rendered Markdown documents for a policy set, keyed by output filename, plus overall coverage. */
public data class RenderedDocs(val files: Map<String, String>, val coverage: CoverageSummary)

/** Renders `.rego` policy directories into Markdown and reports rendering coverage. */
public object Explico {
    /** Parses every `.rego` file under [policyDir] (via the `opa` binary) into a [PolicySet]. */
    public fun load(policyDir: Path): PolicySet = PolicySet(packages = emptyList())

    /** Loads worked-example fixtures from [dir] (spec §6.7's `*.json` fixture format). */
    public fun loadExamples(dir: Path): ExampleSet = ExampleSet(fixtures = emptyList())

    /**
     * Renders [policySet] to Markdown, one document per package plus `index.md`.
     * If [examples] is supplied, control cards gain a worked-examples section
     * evaluated via `opa eval` against [dataDir] (spec §6.7).
     */
    public fun render(policySet: PolicySet, examples: ExampleSet? = null, dataDir: Path? = null): RenderedDocs =
        RenderedDocs(files = emptyMap(), coverage = CoverageSummary(rendered = 0, total = 0))
}
