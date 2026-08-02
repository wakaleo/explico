// Shown verbatim in docs/user-guide.md (spec §13.8): the same load -> render -> write-files
// pattern as the Kotlin snippet, from Java -- proving @JvmStatic/@JvmOverloads (spec §13.5) work
// exactly as documented: Explico.load(...)/Explico.render(policySet, policyDir), no INSTANCE, no
// explicit nulls for render()'s optional parameters. Compiled as part of the docsSnippets source set.
package docs;

import io.explico.Explico;
import io.explico.RenderedDocs;
import io.explico.model.PolicySet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class LoadRenderWriteFilesJava {

    public static void main(String[] args) throws IOException {
        Path policyDir = Path.of("samples/policies");
        Path outDir = Path.of("build/docs-snippet-output/java");

        PolicySet policySet = Explico.load(policyDir);
        RenderedDocs rendered = Explico.render(policySet, policyDir);

        Files.createDirectories(outDir);
        for (Map.Entry<String, String> entry : rendered.getFiles().entrySet()) {
            Files.writeString(outDir.resolve(entry.getKey()), entry.getValue());
        }

        System.out.println("Wrote " + rendered.getFiles().size() + " documents to " + outDir
            + " (" + rendered.getCoverage().getPercent() + "% coverage)");
    }

    private LoadRenderWriteFilesJava() {
    }
}
