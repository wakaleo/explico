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

Outputs, in `<out-dir>`:

| File | Contents |
|---|---|
| `results.tsv` | `file status rendered total class message` per file. `status` is `OK`, `LOAD_FAIL` or `RENDER_CRASH`. |
| `warnings.txt` | One line per rule body whose mapping threw and was isolated (spec §14.29): file and the stderr warning. Should be empty; any line is a mapper bug worth a test. |
| `fallbacks.txt` | The first line of every `⚠ not rendered` block, for ranking which constructs to promote next. |

**Rego v0 corpora** (e.g. the Gatekeeper library) are rejected by OPA 1.x by default. To measure them
anyway, point `OPA_BIN` at the wrapper, which adds `--v0-compatible`:

```sh
OPA_BIN=$PWD/scripts/corpus-benchmark/opa-v0 java -cp "build/install/explico/lib/*" \
    scripts/corpus-benchmark/Bench.java /tmp/corpus/gatekeeper-library /tmp/out/gatekeeper
```

Reading `LOAD_FAIL`: an `OpaInvocationException` is OPA itself rejecting the file -- v0 syntax, or an
*undefined function* because the file depends on another one that this one-file-at-a-time method does
not load (an artifact of the method, not a bug). Anything else (`IllegalArgumentException`,
`JsonDecodingException`, ...) is an explico bug.
