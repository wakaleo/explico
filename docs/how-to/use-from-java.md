# Use explico from Java

**Goal:** render policies from your own Java code (a build step, a service, a test) rather than the command line.

## Add the dependency

The library is published to Maven Central as `io.github.wakaleo:explico`. It still needs the `opa` binary on the machine that runs it; explico
calls `opa` for all parsing and evaluation.

```kotlin
// build.gradle.kts
repositories {
    mavenCentral() // explico itself, and the Kotlin standard library and kotlinx-serialization it depends on
}

dependencies {
    implementation("io.github.wakaleo:explico:1.1.0")
}
```

```xml
<!-- pom.xml -->
<dependency>
  <groupId>io.github.wakaleo</groupId>
  <artifactId>explico</artifactId>
  <version>1.1.0</version>
</dependency>
```

Maven resolves from Central by default. Java 17 or newer is required (the library is compiled for Java 17), and `opa` 1.x must be on the `PATH` (or named by the `OPA_BIN` environment variable): `Explico.load` already runs it, so a missing `opa` fails there, with an `OpaNotAvailableException`.

The library jar contains only explico's own classes; the fat runnable jar (`explico-<version>-all.jar`) is a separate download for the command line.
Kotlin consumers have a minimum Kotlin version; see [CLI and library](../user-guide.md#adding-the-dependency).

## Load, render, write

`render()` returns the Markdown in memory (`RenderedDocs.getFiles()`, a map from file name to content). Writing it is your job. This is the code in
[`LoadRenderWriteFilesJava.java`](../../src/docsSnippets/java/LoadRenderWriteFilesJava.java), compiled by this project's build so it cannot drift from the real API:

```java
Path policyDir = Path.of("samples/policies");
Path outDir = Path.of("build/docs-snippet-output/java");

PolicySet policySet = Explico.load(policyDir);
RenderedDocs rendered = Explico.render(policySet);

Files.createDirectories(outDir);
for (Map.Entry<String, String> entry : rendered.getFiles().entrySet()) {
    Path target = outDir.resolve(entry.getKey());
    Files.createDirectories(target.getParent()); // a name may carry a directory, e.g. controls/sec-28.md with groupByControl
    Files.writeString(target, entry.getValue());
}

System.out.println("Wrote " + rendered.getFiles().size() + " documents to " + outDir
    + " (" + rendered.getCoverage().getPercent() + "% coverage)");
```

Points that surprise people:

- **The loaded `PolicySet` remembers its directory** (`getSourceDir()`), and `render` reads the policies again from there: worked examples re-run `opa eval` against the real files. A set you build by hand has no directory and cannot be rendered.
- **No `.INSTANCE`, no explicit nulls.** `Explico`'s members are static (the `INSTANCE` field exists, as on any Kotlin `object`, but you never need it), and `render`/`diff` take an optional options object (`render(policySet)`, `render(policySet, options)`), so you pass only what you need.
- **A file name can contain a directory** (`controls/<id>.md` with `groupByControl`), so create parent directories before writing.
- **Failures are exceptions.** Every expected failure extends `ExplicoException` (a Rego syntax error is `OpaFailedException`, a missing opa `OpaNotAvailableException`, …); an invalid argument stays an `IllegalArgumentException`. `OpaFailedException.getExitCode()` is opa's own exit code (1 for a syntax error), not the CLI's exit code `2`. See [Exceptions a library consumer can catch by type](../user-guide.md#exceptions-a-library-consumer-can-catch-by-type).
- **Policies bundled in your jar must be extracted first.** A classpath resource is not a filesystem path, so it cannot be passed to `load`.
  [Where policy files come from](../user-guide.md#where-policy-files-come-from-two-patterns) shows the extraction pattern.

## Options, the diff, and what to import

Examples, data and every other option travel in a `RenderOptions`, which Java builds fluently; `Explico.diff(oldSet, newSet)` returns a `DiffReport`, and `DiffOptions.builder()` takes the same kind of options (reference data, fixtures, version labels, digests). The types live in four packages: `io.explico` (the facade, the options and their small classes, the results and the exceptions), `io.explico.model` (only `PolicySet`, for most callers), `io.explico.diff` (`DiffChange`, `DiffCategory`) and `io.explico.render` (`ExampleSet`, the return type of `loadExamples`).

<!-- excerpt: src/docsSnippets/java/BuildersJava.java -->
```java
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
```

This is the code in [`BuildersJava.java`](../../src/docsSnippets/java/BuildersJava.java), compiled by this project's build:

<!-- excerpt: src/docsSnippets/java/BuildersJava.java -->
```java
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
```

- **`dataDir` takes a data file or a directory** of them, as `opa` reads either.
- **`DiffReport.getChanges()` is the stable way to read what changed**: each `DiffChange` has `getControlId()` (null for a rule with no control id), `getCategory()`, `getLabel()`, `getPackagePath()`, `getRuleName()` and `getAnchor()` (null only when another change in the report already claimed that anchor). You do not need the structural model (`getEntries()`), which is outside the compatibility promise; see [Compatibility](../compatibility.md).

The Kotlin version of the whole sequence is in the [tutorial](../tutorial.md#5-the-same-walkthrough-programmatically), and the facade's
every function is in the [API reference](https://wakaleo.github.io/explico/api/).

## Related

[Drift-check recipe and Gradle task](../user-guide.md#a-gradle-task-recipe) · [Install](../install.md)
