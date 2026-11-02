// Shown verbatim in docs/how-to/use-from-java.md: the options builders and the diff report's changes, from Java, with the imports a newcomer needs.
// Compiled as part of the docsSnippets source set, so it cannot drift from the real API.
package docs;

import io.explico.CompanionLink;
import io.explico.ControlIdOptions;
import io.explico.DiffOptions;
import io.explico.DiffReport;
import io.explico.Explico;
import io.explico.ExplicoException;
import io.explico.OpaFailedException;
import io.explico.OpaNotAvailableException;
import io.explico.RenderOptions;
import io.explico.RenderedDocs;
import io.explico.VersionLabels;
import io.explico.diff.DiffChange;
import io.explico.model.PolicySet;
import io.explico.render.ExampleSet;

import java.io.IOException;
import java.nio.file.Path;

public final class BuildersJava {

    public static void main(String[] args) throws IOException {
        Path policies = Path.of("samples/policies");
        try {
            PolicySet policySet = Explico.load(policies);
            ExampleSet examples = Explico.loadExamples(Path.of("samples/examples"));

            RenderOptions options = RenderOptions.builder()
                .examples(examples)
                .dataDir(Path.of("samples/data/release/data.json")) // a data file, or a directory of them
                .json(true)
                .controlId(new ControlIdOptions("ctrl_(?<id>\\d+)", "CTRL-{id}"))
                .companion(new CompanionLink("https://modules.example.com/{package}/src/{file}#L{line}", "module"))
                .build();
            RenderedDocs rendered = Explico.render(policySet, options);
            System.out.println("Rendered " + rendered.getFiles().size() + " documents");

            DiffOptions diffOptions = DiffOptions.builder().digests(true).labels(new VersionLabels("v1", "v2")).build();
            DiffReport report = Explico.diff(policySet, policySet, diffOptions);
            for (DiffChange change : report.getChanges()) {
                System.out.println(change.getCategory() + " " + change.getControlId() + " " + change.getLabel()
                    + " (" + change.getPackagePath() + "." + change.getRuleName() + ", anchor " + change.getAnchor() + ")");
            }
        } catch (OpaNotAvailableException e) {
            System.err.println("Install opa 1.x or set OPA_BIN: " + e.getMessage());
        } catch (OpaFailedException e) {
            System.err.println("opa rejected the policies (opa's exit code " + e.getExitCode() + "): " + e.getStderr());
        } catch (ExplicoException e) {
            System.err.println("explico refused: " + e.getMessage());
        }
    }
}
