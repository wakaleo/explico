# explico

*Latin: "I unfold, I explain" — the counterpart to* rego*, "I govern".*

**explico** is a Kotlin/JVM tool that renders [OPA](https://www.openpolicyagent.org/)
Rego policy files into Markdown that non-technical control owners and
auditors can read, and reports meaningful changes between two versions of a
policy set. The target domain is SDLC compliance release controls (change
approvals, separation of duties, pipeline evidence, artifact provenance,
freeze windows) — the engine itself is domain-agnostic.

## The core principle

**Every statement in the output is true by construction.** explico renders
only what it can derive mechanically from the Rego AST and its METADATA
annotations. It never paraphrases, infers intent, or guesses — anything it
cannot render faithfully is shown as clearly marked, verbatim source instead.
Rendering coverage is measured and reported, not hidden.

## Status

This is a proof of concept, built against
[`specs/explico-poc-specification.md`](specs/explico-poc-specification.md),
which is the authoritative design document. As of this writing, **milestone 1**
of the spec's §11 implementation order is complete:

- Gradle scaffold (Kotlin, JVM 21, Kotlin DSL)
- `OpaRunner` — `opa` binary discovery, version check, and `parse`/`inspect`
  invocation with timeout and stderr capture
- `opa parse`/`opa inspect` JSON DTOs
- The `Explico` facade and domain model compile, but `render`/`diff` are
  stubs returning empty results — the renderer, differ, and CLI have not
  been built yet.

In other words: **`explico render` and `explico diff` don't do anything
useful yet.** The sections below describe the target shape of the tool per
the spec, marked where they're not yet functional, so this README stays
honest rather than describing features that don't exist. See `CLAUDE.md`
for the project's development conventions and non-negotiables.

## Install

Requires:

- JDK 21
- [`opa`](https://www.openpolicyagent.org/docs/latest/#running-opa),
  major version 1 (`opa version`), on `PATH` — or set the `OPA_BIN`
  environment variable to a specific binary. Developed and tested against
  opa 1.19.

```
git clone <this repo>
cd explico
./gradlew check
```

- `./gradlew check` — unit and smoke tests; does not require `opa` for the
  unit tests, but the smoke tests (which exercise real `opa parse`/`opa
  inspect` calls) are skipped if `opa` isn't available.
- `./gradlew acceptanceTest` — the Tier-1 acceptance tests from
  `src/test/resources/acceptance/README.md`. Requires `opa`. These are
  currently expected to fail (see Status above) — they're written ahead of
  the renderer, per the project's TDD process.

## Planned usage

*(Target interface from the spec — not yet functional; `render`/`diff`
currently produce empty output.)*

CLI:

```
explico render <policyDir> --out <outDir> [--examples <dir>] [--data <dir>]
explico diff <oldDir> <newDir> --out <reportFile>
explico version
```

Library:

```kotlin
val policySet = Explico.load(Path.of("policies"))
val rendered = Explico.render(policySet)
println(rendered.files["release-approvals.md"])
```

### Exit codes (planned)

| Code | Meaning |
|---|---|
| 0 | Success |
| 1 | Usage error |
| 2 | Rego parse error (opa's stderr passed through verbatim) |
| 3 | `opa` binary missing or incompatible version |
| 4 | Duplicate control IDs |

## Limitations (by design — POC scope)

These are permanent, deliberate non-goals, not gaps to be filled in later.
Constructs in the second table always render as clearly marked, verbatim
source rather than guessed prose — that's the fallback mechanism, not a bug.

| Out of scope | |
|---|---|
| LLM integration | none, anywhere in the tool |
| Cross-version differential evaluation | single-version worked examples only; no decision-flip analysis |
| Output formats | Markdown only — no HTML, no web server, no graphical rendering |
| Helper rule bodies | referenced via links, never inlined or flattened |
| Git integration | diff takes two plain directories; check out versions yourself (e.g. `git worktree`) |
| Rego parsing in Kotlin | none — all parsing is delegated to the `opa` binary |

| Renders as fallback source | |
|---|---|
| Comprehensions | |
| `every` | |
| User-defined functions with parameters | |
| `with` statements | |
| `else` chains | |

## Documentation

- [`specs/explico-poc-specification.md`](specs/explico-poc-specification.md) — the authoritative design spec.
- [`CLAUDE.md`](CLAUDE.md) — project non-negotiables and the development cycle.
- [`src/test/resources/acceptance/README.md`](src/test/resources/acceptance/README.md) — the acceptance pack: sample policies, fixtures, and the frozen Tier-1 assertions that drive the renderer's development.
- `samples/` — the acceptance pack's policies/examples/data, for hands-on exploration.

## Contributing

Not yet applicable — there's no renderer or builtin-template mechanism to
extend. Once one exists (spec §6.3), this section will describe how to add
a new builtin template (table row + unit test + golden update).

## License

Apache License 2.0 — see [`LICENSE`](LICENSE).
