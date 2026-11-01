# Use explico from Java

**Goal:** render policies from your own Java code (a build step, a service, a test) rather than the command line.

## Add the dependency

The library is published to Maven Central as `io.github.wakaleo:explico`. It still needs the `opa` binary on the machine that runs it; explico
calls `opa` for all parsing and evaluation.

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.wakaleo:explico:0.4.0")
}
```

```xml
<!-- pom.xml -->
<dependency>
  <groupId>io.github.wakaleo</groupId>
  <artifactId>explico</artifactId>
  <version>0.4.0</version>
</dependency>
```

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

- **Pass the policy directory to `render` as well as `load`.** Worked examples re-run `opa eval` against the real files, and the loaded model keeps
  only relative paths.
- **No `INSTANCE`, no explicit nulls.** `Explico`'s members are static, and `render`/`diff` take an optional options object (`render(policySet)`, `render(policySet, options)`), so you pass only what you need.
- **A file name can contain a directory** (`controls/<id>.md` with `groupByControl`), so create parent directories before writing.
- **Failures are exceptions.** Every expected failure extends `ExplicoException` (a Rego syntax error is `OpaFailedException`, a missing opa `OpaNotAvailableException`, …); an invalid argument stays an `IllegalArgumentException`. See [Exceptions a library consumer can catch by type](../user-guide.md#exceptions-a-library-consumer-can-catch-by-type).
- **Policies bundled in your jar must be extracted first.** A classpath resource is not a filesystem path, so it cannot be passed to `load`.
  [Where policy files come from](../user-guide.md#where-policy-files-come-from-two-patterns) shows the extraction pattern.

## Add examples and a diff

Examples, data and every other option travel in a `RenderOptions`, which Java builds fluently: `Explico.render(policySet, RenderOptions.builder().examples(Explico.loadExamples(dir)).dataDir(data).json(true).build())`. `Explico.diff(oldSet, newSet)` returns a `DiffReport` whose `markdown` is the report; `DiffOptions.builder()` takes the same kind of options (reference data, fixtures, version labels, digests). The
Kotlin version of the whole sequence is in the [tutorial](../tutorial.md#5-the-same-walkthrough-programmatically), and the facade's
every function is in the [API reference](https://wakaleo.github.io/explico/api/).

## Related

[Drift-check recipe and Gradle task](../user-guide.md#a-gradle-task-recipe) · [Install](../install.md)
