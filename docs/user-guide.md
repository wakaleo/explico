# User guide

The exhaustive CLI and library reference: every flag, every exit code, every
public facade function. For a guided walkthrough instead, see
[`tutorial.md`](tutorial.md). For writing policies explico renders well, see
[`policy-authoring.md`](policy-authoring.md).

## CLI

Four subcommands: `render`, `diff`, `version`, `demo`.

### `explico render`

```
explico render <policyDir> --out <outDir> [--examples <dir>] [--data <dir>]
```

| Argument/flag | Required | Meaning |
|---|---|---|
| `<policyDir>` | yes | Directory containing `.rego` policy files. Scanned recursively. |
| `--out <outDir>` | yes | Output directory for the rendered Markdown. **Fully replaced on every run** — the whole directory is deleted and rewritten, so don't point it at a directory with other content in it. |
| `--examples <dir>` | no | Directory of worked-example fixtures (`*.json`, spec §6.7's format — see `policy-authoring.md`). When supplied, control cards gain a **Worked examples** section and `index.md` gains an example-coverage column. |
| `--data <dir>` | no | A data document (or directory) made available to `opa eval` alongside `--examples`, for policies that reference `data.*` paths beyond the fixture's own `input`. |

Writes one `<package-path>.md` file per Rego package plus `index.md`, and
prints a one-line coverage summary to stdout, e.g.:

```
Rendering coverage: 21 of 23 conditions (91%)
```

### `explico diff`

```
explico diff <oldDir> <newDir> --out <reportFile>
```

| Argument/flag | Required | Meaning |
|---|---|---|
| `<oldDir>` | yes | The "before" policy directory. |
| `<newDir>` | yes | The "after" policy directory. |
| `--out <reportFile>` | yes | Output file for the single Markdown change report. |

Classifies every control as `ADDED`, `REMOVED`, `LOGIC_CHANGED`,
`DOCS_CHANGED`, or `UNCHANGED` (a control-id-preserving rename lands as
`UNCHANGED` or `LOGIC_CHANGED`, never a spurious remove-and-add pair — see
`policy-authoring.md`'s control-id section for why that matters). Prints
just the report's `## Summary` table to stdout.

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

All four commands share the same exit-code contract:

| Code | Meaning |
|---|---|
| `0` | Success |
| `1` | Usage error — bad flags, a missing required option, a nonexistent path, or (for `demo`) `./explico-demo/` already existing |
| `2` | Rego parse error — `opa`'s own stderr passed through verbatim, never paraphrased |
| `3` | `opa` binary missing or an incompatible version (`render`/`diff`/`demo`); the message names the exact version required |
| `4` | Duplicate control-ids (`diff`) or duplicate fixture names (`render --examples`) |

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

- **`load(policyDir)`** — parses every `.rego` file under `policyDir` (via
  `opa parse`/`opa inspect`) into a `PolicySet`. `policyDir` must be kept
  around and passed to `render()` later — worked examples need to re-invoke
  `opa eval` against the real files, which the domain model alone can't
  locate.
- **`loadExamples(dir)`** — loads `*.json` fixtures from `dir`, in filename
  order. Throws `io.explico.render.DuplicateFixtureNameException` (public)
  if two fixtures share a `name`.
- **`render(policySet, policyDir, examples, dataDir)`** — returns
  `RenderedDocs(files: Map<String, String>, coverage: CoverageSummary)`, the
  rendered Markdown keyed by output filename plus overall coverage
  (`coverage.rendered`, `coverage.total`, `coverage.percent`).
- **`diff(old, new)`** — returns `DiffReport(entries: List<DiffEntry>,
  markdown: String)`. Each `DiffEntry` carries its `category`
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
