# User guide

The exhaustive CLI and library reference: every flag, every exit code, every
public facade function. For a guided walkthrough instead, see
[`tutorial.md`](tutorial.md). For writing policies explico renders well, see
[`policy-authoring.md`](policy-authoring.md).

## CLI

Four subcommands: `render`, `diff`, `version`, `demo`.

### `explico render`

```
explico render <policyDir> --out <outDir> [--examples <dir>] [--data <dir>] [--rego-version v1|v0] [--generate-examples] [--json] [--requirements <file>] [--fail-under <percent>] [--fail-on-gaps]
```

| Argument/flag | Required | Meaning |
|---|---|---|
| `<policyDir>` | yes | Directory containing `.rego` policy files. Scanned recursively. |
| `--out <outDir>` | yes | Output directory for the rendered Markdown. **Fully replaced on every run** — the whole directory is deleted and rewritten, so don't point it at a directory with other content in it. |
| `--examples <dir>` | no | Directory of worked-example fixtures (`*.json`, spec §6.7's format — see `policy-authoring.md`). When supplied, control cards gain a **Worked examples** section and `index.md` gains an example-coverage column. |
| `--data <dir>` | no | A data document (or directory) made available to `opa eval`, for policies that reference `data.*` paths beyond a fixture's own `input`. Independent of `--examples`: supplying it alone (no fixtures at all) also runs `opa eval` -- see *Reference data* below -- and makes every card whose bodies read a plain `data.*` document show what that document holds. |
| `--generate-examples` | no | Also derive worked examples from each rule's conditions where `--examples` leaves a gap (each verified by `opa eval`; labelled as generated; runs the policy). See *Generated examples* below. |
| `--json` | no | Also write `explico.json`, the machine-readable result (see [`json-output.md`](json-output.md)). Off by default; nothing else changes. |
| `--requirements <file>` | no | A text file listing the requirements your organisation must evidence, one per line (blank lines and `#` comments ignored, lines trimmed, duplicates collapsed). Adds a *Requirements coverage* table to `index.md`: each requirement in the file's order with the controls that name it, or **No control**; and the framework strings your controls use that are *not* on the list (a typo catcher). Matching is exact string equality with the METADATA `frameworks` strings. A missing file is a usage error (exit `1`). |
| `--fail-under <percent>` | no | CI gate: exit `5` if overall rendering coverage is below this integer (0-100). Compared exactly (22 of 23 conditions is 95.65%: it passes 95 and fails 96). The output is still written. |
| `--fail-on-gaps` | no | CI gate: exit `5` if the documentation has gaps — a control with no description or no framework, a title shared by controls, and (with `--requirements`) a requirement with no control or a framework string not on the list. Each gap is listed on stderr. |
| `--rego-version v1\|v0` | no | The Rego syntax of the policies: `v1` (default) or `v0` (legacy: `deny[msg] { ... }` with no `if`/`contains`, as in the Gatekeeper library). Passed to every `opa` call as `--v0-compatible`. Never auto-detected; v0 mode applies to the whole directory (v1 files that `import rego.v1` still parse). The `opa` binary must be 1.x either way. `diff` applies it to both directories. |

Writes one `<package-path>.md` file per Rego package plus `index.md`, and
prints a one-line coverage summary to stdout, e.g.:

```
Rendering coverage: 22 of 23 conditions (95%)
```

### Generated examples (`--generate-examples`)

`--generate-examples` (library: `generateExamples = true`) asks explico to **derive worked examples itself** for rules your own
`--examples` fixtures leave a gap for. It is off by default; without it the output is byte-identical to before.

- **How:** for each rule body, explico builds an `input` that satisfies the body's conditions, and "near-misses" that satisfy all but
  one. Every candidate is run through `opa eval`, and is kept **only if OPA agrees** (a "satisfies" input must make the rule match, a
  near-miss must not). The verdict and message you see are OPA's, never predicted. A body explico cannot model simply gets none.
- **Where:** a separate **Generated examples** section on the rule's card, after your own **Worked examples**, followed by a one-line
  note that these are derived mechanically and are not scenarios chosen by the author.
- **Names are mechanical, not semantic:** `satisfies every condition` (or `... of Situation N` for a multi-body rule), and
  `breaks only: <the condition it breaks>`. Only an author can name a real scenario ("hotfix without change ticket"); explico will not.
- **Gap filling only; your fixtures win:** a rule that already has a matching *and* a non-matching author example gets nothing; with only
  a matching one it gets near-misses only; with only a non-matching one it gets a matching example only; with none, both. At most 3
  matching (one per situation) and 2 near-misses per rule.
- **What it can model:** comparisons of an input field with a literal or another field, field presence/absence, set membership,
  `startswith`/`endswith`/`contains`, `is_string`/`is_number`/`is_boolean`, and `some x in <field>` / `[_]` iteration. It cannot model
  references to other rules, calls to your own functions, `data.*` lookups, `count(...)` comparisons, comprehensions and anything else
  shown as source. Policies written in a defensive, helper-heavy style get few generated examples (2 of 43 rules across 30
  Gatekeeper templates, about 5%; about 40-45% of the rule bodies in the sample pack and the showcase).
- **It runs the policy:** like your own examples, generation executes `opa eval` on the policy. Don't use it on a policy you don't trust
  (see [Security considerations](#security-considerations)).
- **Cost:** each distinct candidate input is one `opa eval` of the package, so generation time grows with the number of rules and conditions.
  It is fine for typical policy sets, but expect it to take noticeably longer than a plain render on a very large package.
- The CLI prints `Generated examples for N of M rules` after the coverage line. The index's *Example coverage* column still reflects
  **only your** fixtures: generated examples are illustrations, not evidence that the author considered the case.

### Reference data (`--data`)

Supplying `--data` (library: a non-null `dataDir`) on its own -- no `--examples` needed -- also makes every card whose bodies read a
plain `data.*` document show what that document actually holds in the supplied data, resolved by `opa eval`:

```markdown
**Reference data**

- `data ▸ release ▸ exempt services`: `["legacy-batch"]`

*Values are read from the supplied data by OPA at generation time.*
```

- **Only plain data, never policy code:** a `data.*` path that names a loaded rule or package (or any ancestor of one -- `opa eval` of
  an ancestor evaluates every rule nested under it) is never shown here; it's already a link or breadcrumb to that rule's own card.
- **Only the plain prefix:** `data.limits.owners[owner].name` (after `some owner in data.limits.owners`) shows one bullet for
  `` `data ▸ limits ▸ owners` ``, the whole document -- not one per element.
- **Undefined, not guessed:** a path the supplied data doesn't define says `not defined in the supplied data`, never a blank or a `0`.
- **It runs the policy's data -- not its rules.** Each path is checked first and queried only if it names no loaded rule or package, so
  this `opa eval` call never executes Rego logic; still, treat a `--data` document the same as any other input to `opa eval` (see
  [Security considerations](#security-considerations)) -- it's copied into the rendered Markdown verbatim.

### `explico diff`

```
explico diff <oldDir> <newDir> --out <reportFile> [--json <file>] [--fail-on <categories>] [--rego-version v1|v0]
```

| Argument/flag | Required | Meaning |
|---|---|---|
| `<oldDir>` | yes | The "before" policy directory. |
| `<newDir>` | yes | The "after" policy directory. |
| `--out <reportFile>` | yes | Output file for the single Markdown change report. |
| `--json <file>` | no | Also write the machine-readable change report (JSON) to this file. See [`json-output.md`](json-output.md). |
| `--fail-on <categories>` | no | CI gate: exit `5` if any control is in one of these categories — comma-separated, case-insensitive: `ADDED`, `REMOVED`, `LOGIC_CHANGED`, `DOCS_CHANGED`. The report is still written and the Summary still printed; stderr names the matching controls. |

Classifies every control as `ADDED`, `REMOVED`, `LOGIC_CHANGED`,
`DOCS_CHANGED`, or `UNCHANGED` (a control-id-preserving rename lands as
`UNCHANGED` or `LOGIC_CHANGED`, never a spurious remove-and-add pair — see
`policy-authoring.md`'s control-id section for why that matters). Prints
just the report's `## Summary` table to stdout.

Right after that table, the report itself gains a `## What changed` section: one line per changed control
(`removed`/`added`/`documentation changed: <fields>`/`logic changed`), the last with indented sub-bullets naming
exactly which condition, message, body count, default outcome, or fallback source changed — mechanically derived,
never a guess at what moved. See `samples/` or `src/test/resources/diff/change-summary/` for worked examples.

### `explico version`

```
explico version
```

Prints `explico <version>` and exits `0`. Doesn't check `opa` — that's what
`render`/`diff`/`demo` do, each reporting its own actionable message if it's
missing or incompatible.

### `explico demo`

```
explico demo [--fetch-opa]
```

Self-contained walkthrough needing nothing but the jar and (optionally)
`opa`: extracts a small real policy set (five real release-governance
policies, worked-example fixtures, and a data document — the same corpus
used throughout this project's own test suite) to `./explico-demo/`, renders
it with worked examples, and prints a two-line pointer to a real generated
file. See the README's Quick demo section for a full walkthrough.

- **Refuses to run if `./explico-demo/` already exists** (exit `1`) — never
  overwrites or merges into a directory that might have other content.
- **`--fetch-opa`**: if `opa` isn't already available, downloads a pinned,
  checksum-verified `opa` build (the same version this command's own
  "missing opa" message recommends) to a local cache
  (`~/.explico/opa-cache/<version>/`) and uses it for the demo run, instead
  of requiring you to install `opa` yourself first. Verified against the
  binary's own published SHA-256 checksum before use; refuses to run an
  unverified download.

## Exit codes

All four commands share the same exit-code contract (`5` applies to the gates on `render` and `diff`):

| Code | Meaning |
|---|---|
| `0` | Success |
| `1` | Usage error — bad flags, a missing required option, a nonexistent path, or (for `demo`) `./explico-demo/` already existing |
| `2` | Rego parse error — `opa`'s own stderr passed through verbatim, never paraphrased |
| `3` | `opa` binary missing or an incompatible version (`render`/`diff`/`demo`); the message names the exact version required |
| `4` | Duplicate control-ids (`diff`) or duplicate fixture names (`render --examples`) |
| `5` | A CI gate failed (`render --fail-under` / `--fail-on-gaps`, `diff --fail-on`). The output was still written and the normal stdout printed; the gate message is on stderr |

A non-zero exit is always accompanied by a clear, one-paragraph message on
stderr — never a raw stack trace.

## Library

```kotlin
object Explico {
    fun load(policyDir: Path): PolicySet
    fun loadExamples(dir: Path): ExampleSet
    fun render(policySet: PolicySet, policyDir: Path, examples: ExampleSet? = null, dataDir: Path? = null): RenderedDocs
    fun diff(old: PolicySet, new: PolicySet): DiffReport
}
```

- **`load(policyDir)`** / **`load(policyDir, RegoVersion.V0)`** — parses every `.rego` file under `policyDir` (via
  `opa parse`/`opa inspect`) into a `PolicySet`; `RegoVersion.V0` is for legacy-syntax policies (the version travels with the `PolicySet`, so `render()` evaluates worked examples the same way). `policyDir` must be kept
  around and passed to `render()` later — worked examples need to re-invoke
  `opa eval` against the real files, which the domain model alone can't
  locate.
- **`loadExamples(dir)`** — loads `*.json` fixtures from `dir`, in filename
  order. Throws `io.explico.render.DuplicateFixtureNameException` (public)
  if two fixtures share a `name`.
- **`render(policySet, policyDir, examples, dataDir, generateExamples = false, json = false, requirements = emptyList())`** — returns
  `RenderedDocs(files: Map<String, String>, coverage: CoverageSummary,
  generatedExamples: GeneratedExamplesSummary?)`, the rendered Markdown keyed by
  output filename plus overall coverage (`coverage.rendered`, `coverage.total`,
  `coverage.percent`). `generatedExamples` is null unless `generateExamples = true`,
  then `rulesWithExamples` of `rulesConsidered`. A trailing `json = true` also adds `explico.json` to `files`
  (see [`json-output.md`](json-output.md)); a further trailing `requirements: List<String>` adds the *Requirements coverage* section (see
  *Keeping your documentation complete* in [`policy-authoring.md`](policy-authoring.md)). `RenderedDocs.gaps` is always filled: one line
  per documentation gap, worded once for every consumer (empty when the documentation is complete).
- **`diff(old, new)`** — returns `DiffReport(entries: List<DiffEntry>,
  markdown: String, json: String)`. Each `DiffEntry` carries its `category`
  (`DiffCategory`: `ADDED`/`REMOVED`/`LOGIC_CHANGED`/`DOCS_CHANGED`/`UNCHANGED`),
  `controlId`, and the old/new `PolicyPackage`+`RuleGroup` (null on the
  absent side for `ADDED`/`REMOVED`). Throws
  `io.explico.diff.DuplicateControlIdException` (public) if either side has
  two rules sharing a control-id.

### Exceptions a library consumer can catch by type

Only two exception types are part of the public API surface and importable
outside the module:

- `io.explico.render.DuplicateFixtureNameException` (has a `name: String`)
- `io.explico.diff.DuplicateControlIdException` (has a `controlIds: List<String>`)

Everything else `load`/`render`/`diff` might throw (most commonly a Rego
syntax error, or `opa` being missing/incompatible) comes from an `internal`
exception type — not importable by name from outside the module, since it's
not part of the deliberately small public API surface (spec §8.1: "Everything
else is `internal`"). A consumer can still catch it generically
(`catch (e: RuntimeException)`) and inspect `e.message`, which always
contains `opa`'s own stderr verbatim for a parse failure, or an actionable
description for a missing/incompatible `opa` binary — the same messages the
CLI itself prints.

### Domain model

`PolicySet` → `PolicyPackage` → `RuleGroup` → `RuleBody` → a sealed
`Condition`/`Operand`/`PathSegment` hierarchy — the full parsed shape of a
policy set, and the type `DiffEntry` carries on each side. Every type is
public and KDoc'd; see `model/Model.kt` in the source (or the published
artifact's attached sources/javadoc jars) for the exhaustive, authoritative
field-level reference — not duplicated here, to avoid the two drifting apart.

## Programmatic use: load → render → write files

`render()` returns Markdown in memory (`RenderedDocs.files`, a
`Map<String, String>` keyed by output filename) — writing it to disk is the
caller's job, not something `render()` does itself. Every snippet below is a
real file, compiled as part of this project's own build (the `docsSnippets`
Gradle source set) — a snippet that stops compiling against the real
`Explico` API fails this project's build, so what's shown here can't drift
out of date the way a hand-copied, never-checked example can.

### Adding the dependency

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.3.0"   // or newer -- see the version note below
    application
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("io.github.wakaleo:explico:0.1.1")
}

application {
    mainClass.set("MainKt")   // or wherever your fun main() lives
}
```

- **Kotlin 2.3.0 or newer is required** — the published artifact is built with
  Kotlin 2.4, whose metadata a 2.3.x compiler can read (checked by the consumer
  smoke test) but a 2.2.x compiler cannot. An older `kotlin("jvm")` plugin version
  fails with an opaque `Module was compiled with an incompatible version of
  Kotlin` error that gives no hint the fix is simply bumping the plugin
  version.
- **A Kotlin compiler version that's too old for your *own* JDK can fail
  even more confusingly than that** — with no explico dependency involved at
  all. A `kotlin("jvm")` plugin from well before 2.3.0 running on a recent
  JDK can crash with an internal compiler error deep in the Kotlin/JVM
  frontend, not a readable version-mismatch message — confirmed directly:
  Kotlin 2.0.20 against a JDK 25 host produced exactly this crash, with
  nothing pointing at "bump your Kotlin version" as the fix. If you hit an
  unreadable internal-compiler stack trace anywhere in this setup, try
  2.3.0+ before assuming the problem is in your own code.
- **JVM 17 or newer** at runtime — the library's bytecode targets Java 17
  (checked by the compiler against the JDK 17 API surface), and CI runs the
  runnable jar on a real JDK 17. This project itself builds on a JDK 21
  toolchain.

With that scaffold in place, `samples/policies` (or your own policy
directory) needs to exist relative to wherever you run Gradle from — the
snippets below use `Path.of("samples/policies")` literally, matching this
project's own layout; substitute your own path.

**Kotlin** — [`src/docsSnippets/kotlin/LoadRenderWriteFilesKotlin.kt`](../src/docsSnippets/kotlin/LoadRenderWriteFilesKotlin.kt):

```kotlin
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
```

**Java** — [`src/docsSnippets/java/LoadRenderWriteFilesJava.java`](../src/docsSnippets/java/LoadRenderWriteFilesJava.java),
proving the `@JvmStatic`/`@JvmOverloads` ergonomics (spec §13.5): no
`Explico.INSTANCE`, no explicit `null` for `render`'s optional `examples`/
`dataDir` parameters when you don't need them:

```java
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
```

### Where policy files come from: two patterns

`Explico.load(policyDir)` needs a real filesystem `Path` it can walk — which
directory that is depends on whether your policies live as ordinary project
files or are embedded as resources inside a jar. **These are genuinely
different situations, not two ways of writing the same thing:**

**Pattern 1 — build-time generation from real project files (the common
case).** Your policies are `.rego` files checked into the same repository
(or a sibling one) as the code calling explico — same as every snippet
above, which reads straight from `samples/policies`. No extraction, no
temp directory: just point `load()` at the directory. This is what
`generateSampleDocs` (below) and this project's own `docsSnippets` do.

**Pattern 2 — policies embedded as jar resources, extracted at runtime.**
If your policies are bundled *inside* your application's jar (as `explico
demo`'s own acceptance-pack corpus is), **a classpath resource is not a
filesystem path and cannot be passed to `Explico.load` directly** — this is
the gotcha: `getResource("/policies")` returns a `jar:` URL, not something
`Path.of()` accepts, and even `getResourceAsStream` only gives you one file
at a time, never a directory `load()` can walk. Extract every embedded file
to a real temporary directory first, exactly like `explico demo`'s own
`Main.kt` (`extractDemoResources`) does for its embedded corpus. Real,
compiled, and *run* (not just compiled) as part of this project's own
build — [`src/docsSnippets/kotlin/ExtractJarResourcesToTempDir.kt`](../src/docsSnippets/kotlin/ExtractJarResourcesToTempDir.kt):

```kotlin
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
```

Listing which resources to extract has the same problem one level up:
enumerating a classpath *directory* from inside a jar is unreliable
(`getResource("/policies")` on a directory name behaves differently
depending on whether the jar's zip index has explicit directory entries),
so a fixed list of resource paths — generated at build time, not discovered
at runtime — is the reliable approach. `explico demo` solves this itself with
a build-generated manifest file (`generateDemoResources` in this project's
own `build.gradle.kts`) listing every embedded resource by relative path;
do the same in your own project rather than trying to list a directory at
runtime.

### Output-directory guidance

- **A dedicated output directory is recommended** — `render()`'s CLI form
  fully replaces `--out`'s target directory on every run (see the CLI table
  above), and the same expectation applies programmatically: don't point it
  at a directory holding anything you didn't generate.
- **Rendering alongside your policy sources is possible but not usually
  what you want**: output is organized **per Rego *package*** (one
  `<package-path>.md` file per package, plus `index.md`), not per source
  file — a package assembled from several `.rego` files across nested
  directories still produces one flat output file, so "next to the source"
  doesn't mean "next to *that* file" in any 1:1 sense. A dedicated directory
  keeps this mapping legible; scattering rendered files back among
  hand-written `.rego` sources tends to just confuse the two.

### A Gradle task recipe

The exact shape this project's own `generateSampleDocs` task uses to render
its own `samples/` into committed docs — reuse this directly, pointing
`args` at your own policy/example/data directories:

```kotlin
val generateSampleDocs by tasks.registering(JavaExec::class) {
    group = "documentation"
    description = "Renders samples/ into docs/sample-output/."
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.explico.cli.MainKt")
    args = listOf(
        "render", "samples/policies",
        "--out", "docs/sample-output",
        "--examples", "samples/examples",
        "--data", "samples/data/release/data.json",
    )
}
```

Runs the real CLI in-process against the current build's own classes —
`dependsOn(tasks.classes)` (not the published artifact) so the task always
renders with whatever's currently on disk, including uncommitted changes.

### The CI drift-check recipe

If rendered output is committed (recommended — it's real documentation,
reviewable in a diff, and doesn't require running explico just to read it),
CI should fail whenever a fresh render no longer matches what's committed —
otherwise the committed docs silently rot out of sync with the policies
they claim to describe. This project's own `ci.yml` runs this exact recipe
against its own `docs/sample-output/` (spec §13.7):

```yaml
- name: Regenerate sample docs and check for drift
  run: |
    ./gradlew generateSampleDocs --console=plain
    git diff --exit-code -- docs/sample-output
```

`git diff --exit-code` exits non-zero the instant regeneration produces
anything different from what's committed — a renderer change, a policy
edit, or a fixture edit that wasn't followed by re-running the task and
committing the result all fail the build the same way. Verified (in this
project's own history) to genuinely catch drift, not just pass trivially:
deliberately introducing a difference in the generated output made this
step fail with exit code `1`; regenerating and committing brought it back
to exit code `0`.

## Security considerations

- **`render` with neither `--examples` nor `--data` only parses.** It runs `opa parse` and `opa inspect`, which read
  the Rego but never evaluate it, so it is safe on untrusted policies.
- **`render --examples` (and `Explico.render(..., examples)`) executes the policy.** Each fixture is run through
  `opa eval`. Rego builtins can reach outside the process (`http.send`, `net.lookup_ip_addr`, `opa.runtime` exposing
  environment variables). Only generate worked examples for policies you trust, in an environment where that is acceptable.
- **`render --data` on its own also runs `opa eval`**, for the *Reference data* section (see above) -- but only ever
  queries a plain `data.*` document, never a path that names a loaded rule or package, so it cannot trigger a Rego
  builtin. Still, any data you supply is copied into the rendered Markdown verbatim.
- **The `opa` binary is yours to trust.** explico uses the `opa` on `PATH` (or `OPA_BIN`). `explico demo --fetch-opa`
  downloads the official release and verifies its SHA-256 checksum.

Reporting a vulnerability: see [`SECURITY.md`](../SECURITY.md).
