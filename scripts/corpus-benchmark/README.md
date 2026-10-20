# Corpus benchmark

Renders every non-test `.rego` file under a directory with the **public** `Explico` API, one file at
a time (so one failure never hides the others), and reports how many files render, how many fail to
load, and the rendering coverage of the conditions that did render. It exists to measure explico
against Rego it was *not* written around -- the project's own fixtures are shaped by what we already
thought of.

```sh
./gradlew installDist
git clone --depth 1 https://github.com/aquasecurity/trivy-checks.git /tmp/corpus/trivy-checks
mkdir -p /tmp/out/trivy-checks
java -cp "build/install/explico/lib/*" scripts/corpus-benchmark/Bench.java /tmp/corpus/trivy-checks /tmp/out/trivy-checks
```

Optional third argument: `file` (default), `dir` or `all` -- how many files are loaded together. `dir` lets
same-package helpers in sibling files resolve; `all` loads the whole corpus at once so cross-package calls and
imports resolve too (one OPA rejection then fails the whole unit, so use it on all-Rego-v1 corpora such as
Trivy). Rows in `results.tsv` are then per unit, not per file.

Outputs, in `<out-dir>`:

| File | Contents |
|---|---|
| `results.tsv` | `file status rendered total class message` per file. `status` is `OK`, `LOAD_FAIL` or `RENDER_CRASH`. |
| `warnings.txt` | One line per rule body whose mapping threw and was isolated (spec §14.29): file and the stderr warning. Should be empty; any line is a mapper bug worth a test. |
| `reasons.tsv` | `file reason first-line-of-source` for every unrendered condition (from the JSON export): rank by reason, then by shape. |
| `fallbacks.txt` | The first line of every `⚠ not rendered` block, for ranking which constructs to promote next. |

**Rego v0 corpora** (e.g. the Gatekeeper library) are rejected by OPA 1.x by default. Pass `v0` as the
fourth argument (`RegoVersion.V0`, the same as the CLI's `--rego-version v0`):

```sh
java -cp "build/install/explico/lib/*" \
    scripts/corpus-benchmark/Bench.java /tmp/corpus/gatekeeper-library /tmp/out/gatekeeper dir v0
```

(`opa-v0` in this directory is the older wrapper approach, kept only for measuring versions of explico that predate `RegoVersion`.)

Reading `LOAD_FAIL`: an `OpaInvocationException` is OPA itself rejecting the file -- v0 syntax, or an
*undefined function* because the file depends on another one that this one-file-at-a-time method does
not load (an artifact of the method, not a bug). Anything else (`IllegalArgumentException`,
`JsonDecodingException`, ...) is an explico bug.

## Reading call fallbacks (finding from `dir` mode)

Loading a directory at a time barely changes coverage (Gatekeeper 62%, conftest 60%, Trivy 60%, Regal 48%),
because most unresolved plain-name calls are unresolvable by *any* tool: Trivy's `isManaged`/`result.new` are
not defined in its Rego at all (OPA itself rejects the corpus loaded whole: `undefined function`), and
Gatekeeper's `is_exempt`/`is_update` come from library templates concatenated at deploy time. A fallback is the
correct rendering there. The real, resolvable gap is alias-qualified calls into an imported package
(`kubernetes.command_has_flag(...)` after `import data.lib.kubernetes`).

Measured effect of resolving cross-package calls (Trivy's Kubernetes checks plus `lib/kubernetes`, loaded whole
with `all`, a one-line stub for Trivy's Go-side `result.new`): condition coverage 49% -> 63%, fallbacks 509 -> 352.

## Measuring Trivy properly

Per-file Trivy numbers understate coverage: most remaining fallbacks are calls into `lib/` that a one-file load cannot resolve. Copy `lib/` (minus `lib/cloud`, which OPA rejects as a unit, and all test files) plus
`checks/kubernetes` into one directory and render it whole: `explico render <dir> --out <out> --json`, then rank the `reason`/`source` of unrendered conditions in `explico.json`. 83% at the time of spec §14.50.
