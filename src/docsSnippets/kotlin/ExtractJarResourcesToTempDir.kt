// Shown verbatim in docs/user-guide.md (spec §13.8): the second resource-loading pattern, for
// when policies are shipped as jar resources rather than real files on disk. Explico.load needs an
// actual directory it can walk -- a classpath resource path (e.g. "/policies/foo.rego" inside a
// jar) is not a filesystem Path and can't be passed to it directly. Extract to a real temp
// directory first, exactly like explico's own `demo` command does for its embedded acceptance
// pack (Main.kt's extractDemoResources). Compiled *and* run as part of the docsSnippets source
// set, against a real embedded resource (src/docsSnippets/resources/policies/sample.rego) -- not
// pseudocode standing in for a real extraction loop.
import io.explico.Explico
import java.nio.file.Files
import java.nio.file.Path

/**
 * One resource path per embedded policy file. In a real project this list comes from a
 * build-generated manifest (see explico's own `generateDemoResources` Gradle task), since listing
 * a classpath *directory* is unreliable inside a jar depending on whether its zip index has
 * explicit directory entries -- a known gotcha, not a hypothetical one. Hardcoded here only
 * because this snippet embeds a single, fixed illustrative resource.
 */
private val embeddedPolicyResources = listOf("policies/sample.rego")

fun main() {
    val tempDir = Files.createTempDirectory("explico-policies-")
    try {
        for (resourceName in embeddedPolicyResources) {
            val resource = object {}.javaClass.getResourceAsStream("/$resourceName")
                ?: error("Embedded resource '$resourceName' not found on the classpath")
            val target = tempDir.resolve(resourceName)
            Files.createDirectories(target.parent)
            resource.use { Files.copy(it, target) }
        }

        val policySet = Explico.load(tempDir)
        println("Loaded ${policySet.packages.size} package(s) from extracted jar resources")
    } finally {
        tempDir.toFile().deleteRecursively()
    }
}
