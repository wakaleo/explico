// Shown verbatim in docs/user-guide.md (spec §13.8): the load -> render -> write-files pattern,
// the shape almost every real caller actually needs (RenderedDocs.files is just a Map in memory
// until something writes it out). Compiled as part of the docsSnippets source set.
import io.explico.Explico
import java.nio.file.Files
import java.nio.file.Path

fun main() {
    val policyDir = Path.of("samples/policies")
    val outDir = Path.of("build/docs-snippet-output/kotlin")

    val policySet = Explico.load(policyDir)
    val rendered = Explico.render(policySet, policyDir)

    Files.createDirectories(outDir)
    rendered.files.forEach { (name, content) -> Files.writeString(outDir.resolve(name), content) }

    println("Wrote ${rendered.files.size} documents to $outDir (${rendered.coverage.percent}% coverage)")
}
