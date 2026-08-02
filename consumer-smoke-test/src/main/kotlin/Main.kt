// Consumer smoke test (spec §10, session 7): proves the published `explico` artifact is usable
// from another Gradle project, not just from inside its own build. Calls the real public facade
// against the repo's own `samples/` and asserts real, specific output -- not just "it ran".
// Exercises all three top-level facade entry points (spec §12: "Explico.load/render/diff").
import io.explico.Explico
import io.explico.diff.DiffCategory
import java.nio.file.Path

fun main() {
    val samplesDir = Path.of("../samples/policies")
    check(samplesDir.toFile().isDirectory) {
        "samples/policies not found at ${samplesDir.toAbsolutePath()} -- run from the consumer-smoke-test directory"
    }

    val policySet = Explico.load(samplesDir)
    check(policySet.packages.isNotEmpty()) { "Explico.load(samples/policies) produced no packages" }

    val rendered = Explico.render(policySet, samplesDir)
    check(rendered.files.containsKey("index.md")) { "render() did not produce index.md" }
    val index = rendered.files.getValue("index.md")
    check(index.contains("REL-001")) { "index.md is missing the expected REL-001 control" }

    val approvalsDoc = rendered.files.entries.firstOrNull { it.key.contains("approvals") }?.value
        ?: error("no rendered document for the release.approvals package")
    check(approvalsDoc.contains("## REL-001")) { "approvals document is missing the REL-001 card heading" }
    check(rendered.coverage.total > 0) { "overall coverage total was zero" }

    // Diffing the same policy set against itself is a trivial case, but it's still a real call
    // through the published artifact's diff/ package (DiffCategory is public API too), not an
    // in-process shortcut -- every entry must be UNCHANGED, and the report carries its mandatory
    // disclaimer line.
    val report = Explico.diff(policySet, policySet)
    check(report.entries.isNotEmpty()) { "diff(policySet, policySet) produced no entries" }
    check(report.entries.all { it.category == DiffCategory.UNCHANGED }) {
        "diffing a policy set against itself produced a non-UNCHANGED entry: ${report.entries.filter { it.category != DiffCategory.UNCHANGED }}"
    }
    check(report.markdown.contains("This report shows structural changes only")) {
        "diff report is missing its mandatory disclaimer line"
    }

    println(
        "CONSUMER SMOKE TEST PASSED: ${rendered.files.size} documents rendered, " +
            "coverage ${rendered.coverage.rendered}/${rendered.coverage.total} (${rendered.coverage.percent}%), " +
            "diff() classified ${report.entries.size} controls (all UNCHANGED, as expected)",
    )
}
