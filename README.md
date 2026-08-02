# explico

`explico` (Latin: *"I unfold, I explain"* — the counterpart to *rego*, *"I govern"*)
is a Kotlin/JVM tool that renders [OPA](https://www.openpolicyagent.org/)
Rego policy files into Markdown that non-technical control owners and
auditors can read, evaluates worked examples to show what a policy actually
does against real inputs, and reports structural changes between two
versions of a policy set. The target domain is SDLC compliance release
controls (change approvals, separation of duties, pipeline evidence,
artifact provenance, freeze windows) — the engine itself is domain-agnostic.

## Relationship to OPA and Rego

explico is a documentation and change-reporting layer *on top of* OPA, not
a replacement for any part of it, and not affiliated with or endorsed by
the Open Policy Agent project or Styra.

- **It drives the real `opa` binary rather than reimplementing Rego.**
  Every parse, metadata lookup, and evaluation goes through `opa parse`/
  `opa inspect`/`opa eval` as subprocess calls (see
  [`ARCHITECTURE.md`](ARCHITECTURE.md) for why). explico never contains its
  own Rego parser or evaluator, so its understanding of a policy can't
  silently drift from what OPA itself would do with it.
- **It reads standard OPA METADATA annotations only** — the same
  `# METADATA` YAML-block convention [documented by OPA itself](https://www.openpolicyagent.org/docs/latest/annotations/),
  not an explico-specific markup dialect. Any already-annotated,
  OPA-compliant policy set can be rendered as-is; nothing needs adding
  purely to make explico happy.
- **Targets OPA 1.x** (developed and tested against 1.19.0 specifically —
  see the Install section). `opa parse`'s JSON shape and the METADATA
  annotation format are both spec'd per-major-version by OPA; a 2.x line,
  if one ever exists, isn't assumed compatible without re-verifying.
- **Positioning vs. other Rego tooling**: [Regal](https://www.openpolicyagent.org/ecosystem/entry/regal)
  (Styra's Rego linter and language server) checks whether your Rego is
  *well-written* — style, bugs, best practices. explico doesn't lint
  anything; it assumes the policy is already valid and renders what it
  *means*. [Konstraint](https://github.com/plexsystems/konstraint) is the
  closest existing tool in spirit — it also generates a Markdown doc from
  METADATA annotations — but its `doc` subcommand is a byproduct of its
  primary job (converting Rego into Kubernetes Gatekeeper
  ConstraintTemplates), scoped to policies using Gatekeeper's `violation[]`
  convention. explico is a dedicated, general-purpose documentation-and-diff
  tool for any Rego policy set, with worked-example evaluation and a
  structural diff/change-report that Konstraint's docs generator doesn't
  have, demonstrated here via an SDLC release-governance policy set rather
  than a Kubernetes admission-control one.

## The core principle

**Every statement in the output is true by construction.** explico renders
only what it can derive mechanically from the Rego AST and its METADATA
annotations. It never paraphrases, infers intent, or guesses — anything it
cannot render faithfully is shown as clearly marked, verbatim source instead.
Rendering coverage is measured and reported, not hidden. Worked-example
verdicts come from real `opa eval` runs, never a prediction of what a rule
would do.

## Status

This is a proof of concept, built against
[`specs/explico-poc-specification.md`](specs/explico-poc-specification.md),
which is the authoritative design document. The full pipeline described
below is implemented and tested: `render`, `diff`, worked examples, and the
CLI. See [`CLAUDE.md`](CLAUDE.md) for the project's development conventions,
non-negotiables, and a full log of the judgment calls made where the spec
doesn't pin an exact detail.

## Quick demo

The fastest way to see explico do something real, no build tools and no
checkout of this repository needed — just a JDK and `opa`:

```
# from an empty/scratch directory (it writes ./explico-demo/ relative to
# your current directory) -- download the jar from this project's latest
# GitHub Release, then:
java -jar explico.jar demo
```

(No release has been tagged yet — see the Install section below for
building the jar locally instead: `./gradlew shadowJar`, then `java -jar
build/libs/explico-<version>.jar demo`.)

This extracts a small real policy set to `./explico-demo/`, renders it, and
prints exactly where to look:

```
Rendered 6 documents to explico-demo/docs/ (91% coverage).
Open explico-demo/docs/release-approvals.md to see a rendered control card.
```

`explico-demo/docs/index.md` is worth a look too — a table of every control
across all 6 rendered documents, with a coverage column per control. Running
`demo` again in the same directory refuses rather than overwriting
(`'explico-demo' already exists...`, exit code `1`) — remove the directory
first if you want to re-run it: `rm -rf explico-demo`.

No `opa` on `PATH`? Either install it (the Install section below names the
exact version) or add `--fetch-opa` to have explico download a pinned,
checksum-verified build itself: `java -jar explico.jar demo --fetch-opa`.

For everything else — every flag, every exit code, the full library API —
see [`docs/user-guide.md`](docs/user-guide.md). Writing policies you want
explico to render well? See [`docs/policy-authoring.md`](docs/policy-authoring.md).
Want a guided walkthrough of all five sample policies, not just one card?
See [`docs/tutorial.md`](docs/tutorial.md).

## Install

Requires:

- JDK 21. If you don't already have one, you don't need to install it
  yourself — this project's `settings.gradle.kts` applies Gradle's
  [foojay toolchain resolver](https://github.com/gradle/foojay-toolchains),
  so `./gradlew` auto-downloads a JDK 21 toolchain the first time it's needed.
- [`opa`](https://www.openpolicyagent.org/docs/latest/#running-opa),
  major version 1 (`opa version`), on `PATH` — or set the `OPA_BIN`
  environment variable to a specific binary. Developed and tested against
  opa 1.19.0 exactly (CI pins this version; the acceptance pack's frozen
  goldens were validated against it, so a different 1.x version *should*
  work but isn't guaranteed byte-for-byte).

### From source

Once you have the source (however you obtained it — clone, download, a
colleague's copy), from the directory containing this README:

```
./gradlew check
```

- `./gradlew check` — unit tests; does not require `opa` (tests that do are
  individually guarded and skip cleanly if it's absent).
- `./gradlew acceptanceTest` — the Tier-1/Tier-2 acceptance and golden tests
  against the acceptance pack shipped in `src/test/resources/acceptance/`.
  Requires `opa`.

### As a library

**Not yet published.** The Maven Central publishing pipeline
(`release.yml`, see `CLAUDE.md`) exists and is tested, but no release tag has
gone through it yet. Until a real `v0.1.0` tag is pushed, build and use it
locally instead:

```
./gradlew publishToMavenLocal
```

then, in a consuming project, add `mavenLocal()` to `repositories { }` and:

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.wakaleo:explico:0.1.0")
}
```

The published artifact's compiled metadata targets **Kotlin 2.3.0** — a
consumer on an older Kotlin Gradle plugin version will hit an opaque
`Module was compiled with an incompatible version of Kotlin` error with no
indication of the real cause. Match or exceed 2.3.0 in the consuming
project's own `kotlin("jvm")` plugin version.

## CLI usage

```
explico render <policyDir> --out <outDir> [--examples <dir>] [--data <dir>]
explico diff <oldDir> <newDir> --out <reportFile>
explico version
explico demo [--fetch-opa]
```

Run from a source checkout with `./gradlew run --args="..."`, via the
installed distribution (`./gradlew installDist`, then
`build/install/explico/bin/explico ...`), or via the standalone shadow jar
(`./gradlew shadowJar`, then `java -jar build/libs/explico-<version>.jar
...` — see Quick demo above). Full detail on every flag and exit code:
[`docs/user-guide.md`](docs/user-guide.md).

### Worked example

`samples/` mirrors the acceptance pack: five real release-governance policies,
worked-example fixtures, and a data document. Rendering it:

```
explico render samples/policies --out docs \
  --examples samples/examples --data samples/data/release/data.json
```

```
Rendering coverage: 21 of 23 conditions (91%)
```

The input policy (`samples/policies/approvals/change_approval.rego`):

```rego
package release.approvals

import rego.v1

# METADATA
# scope: document
# title: Production change approval
# description: |
#   Production deployments must reference an approved change ticket,
#   and the change author must not approve their own change.
# custom:
#   control-id: REL-001
#   frameworks:
#     - SOC 2 CC8.1
#     - ISO 27001 A.8.32
deny contains msg if {
	input.deployment.environment == "production"
	not input.change.ticket.approved
	msg := sprintf("release %v has no approved change ticket", [input.deployment.id])
}

deny contains msg if {
	input.deployment.environment == "production"
	input.change.author == input.change.approver
	msg := sprintf("change %v was approved by its author", [input.change.id])
}
```

...becomes this control card in `docs/release-approvals.md` (excerpt — the
full card also includes a package header and a coverage footer):

```markdown
## REL-001 — Production change approval

*Rule `deny` in package `release.approvals` — defined in `change_approval.rego`*
*Frameworks: SOC 2 CC8.1, ISO 27001 A.8.32*

Production deployments must reference an approved change ticket,
and the change author must not approve their own change.

**The rule matches when ANY of the following situations applies:**

### Situation 1 — all of the following are true

- `deployment ▸ environment` is `"production"`
- `change ▸ ticket ▸ approved` is absent or false

*Produces:* "release [deployment id] has no approved change ticket"

### Situation 2 — all of the following are true

- `deployment ▸ environment` is `"production"`
- `change ▸ author` is `change ▸ approver`

*Produces:* "change [change id] was approved by its author"

**Worked examples**

- **hotfix without change ticket** — ❌ denied *(Situation 1)*
  *"release rel-1002 has no approved change ticket"*
  - `deployment ▸ environment`: `"production"`
  - `change ▸ ticket ▸ approved`: absent
  - `change ▸ author`: `"asmith"`
  - `change ▸ approver`: `"bjones"`
- **self-approved change** — ❌ denied *(Situation 2)*
  *"change chg-2003 was approved by its author"*
  - `deployment ▸ environment`: `"production"`
  - `change ▸ ticket ▸ approved`: `true`
  - `change ▸ author`: `"asmith"`
  - `change ▸ approver`: `"asmith"`
- **approved standard release** — ✅ allowed
  - `deployment ▸ environment`: `"production"`
  - `change ▸ ticket ▸ approved`: `true`
  - `change ▸ author`: `"asmith"`
  - `change ▸ approver`: `"bjones"`
- **failed security scan** — ✅ allowed
  - `deployment ▸ environment`: `"staging"`
  - `change ▸ ticket ▸ approved`: `true`
  - `change ▸ author`: `"asmith"`
  - `change ▸ approver`: `"bjones"`

*Examples are evaluated against this policy version by OPA at generation time.*
```

Every message, verdict, and referenced value above came from a real `opa eval`
run against the fixtures in `samples/examples/` — none of it was predicted.
This isn't just an excerpt for the README: the full, real file is committed
at [`docs/sample-output/release-approvals.md`](docs/sample-output/release-approvals.md)
— generated by `./gradlew generateSampleDocs` (spec §13.7), and CI fails the
build if it ever drifts from what a fresh render actually produces.

`explico diff <oldDir> <newDir> --out report.md` produces a single Markdown
change report: a summary table of how many controls were added, removed,
had their logic change, had only docs change, or were unchanged, followed by
one section per changed control (a control-id-preserving rename is correctly
classified `UNCHANGED`/`LOGIC_CHANGED`, never a spurious remove-and-add pair).

## Library usage

```kotlin
import io.explico.Explico
import java.nio.file.Path

fun main() {
    val policySet = Explico.load(Path.of("samples/policies"))
    val examples = Explico.loadExamples(Path.of("samples/examples"))
    val rendered = Explico.render(
        policySet,
        Path.of("samples/policies"),
        examples,
        Path.of("samples/data/release/data.json"),
    )
    println(rendered.files["release-approvals.md"])
    println("Coverage: ${rendered.coverage.percent}%")

    // diff() takes two loaded PolicySets -- substitute your own old/new checkouts in practice.
    // Diffing samples/ against itself is a real, runnable no-op: every control comes back UNCHANGED.
    val report = Explico.diff(policySet, policySet)
    println(report.markdown)
}
```

### Exit codes (CLI)

| Code | Meaning |
|---|---|
| 0 | Success |
| 1 | Usage error (bad flags, missing required option, nonexistent path) |
| 2 | Rego parse error — `opa`'s own stderr passed through verbatim |
| 3 | `opa` binary missing or an incompatible version |
| 4 | Duplicate control-ids (`diff`) or duplicate fixture names (`render --examples`) |

## Limitations (by design — POC scope)

These are permanent, deliberate non-goals (spec §1.2), not gaps to be filled
in later. Reproduced verbatim from the spec so this list can't drift from it:

| # | Out of scope |
|---|---|
| 1 | No LLM integration of any kind. |
| 2 | No differential evaluation **across versions** / decision-flip analysis (future phase). Single-version fixture evaluation for worked examples IS in scope (§6.7). |
| 3 | No HTML output, no web server, no graphical rendering (Markdown only). |
| 4 | No inlining/flattening of helper rule bodies (references are rendered as links). |
| 5 | No support for: comprehensions, `every`, user-defined functions with parameters, `with` statements, `else` chains. These render via the fallback mechanism (§6.6). |
| 6 | No git integration. Diff takes two plain directories; the caller checks out versions themselves (e.g. via `git worktree`). |
| 7 | No Rego parsing in Kotlin. Parsing is delegated to the `opa` binary (§4). |

Item 5's constructs never produce guessed prose — they always render as a
clearly marked, verbatim-source fallback block naming the reason, and count
against the coverage percentage reported on every card and package.

## Documentation

- [`docs/user-guide.md`](docs/user-guide.md) — every CLI flag, every exit code, every public library function.
- [`docs/policy-authoring.md`](docs/policy-authoring.md) — writing Rego policies explico renders well: METADATA conventions, the distinct-message/Situation-label convention, control-id/frameworks, fallback and coverage as design, fixture authoring.
- [`docs/tutorial.md`](docs/tutorial.md) — a guided walkthrough of all five `samples/` policies through render → worked examples → diff, real output inline.
- [`ARCHITECTURE.md`](ARCHITECTURE.md) — the pipeline, extension points, and why there's no Kotlin Rego parser.
- [`specs/explico-poc-specification.md`](specs/explico-poc-specification.md) — the authoritative design spec.
- [`CLAUDE.md`](CLAUDE.md) — project non-negotiables, the development cycle, and every judgment call made session by session.
- [`src/test/resources/acceptance/README.md`](src/test/resources/acceptance/README.md) — the acceptance pack: sample policies, fixtures, and the frozen Tier-1 assertions that drive the renderer's development.
- `samples/` — the acceptance pack's policies/examples/data, for hands-on exploration (used in the worked example above).

## Contributing

**Adding a new recognised builtin's rendering** (spec §6.3) is the smallest,
most self-contained extension point:

1. If the builtin isn't already recognised as a condition, add it to
   `CONDITION_BUILTINS` in `parse/AstMapper.kt` (the mapper must classify it
   as a `Condition.BuiltinCall` before anything downstream can render it).
2. Add a row to `BUILTIN_TEMPLATES` in `render/ExpressionRenderer.kt`: the
   verb, its negated form, and which argument is the "subject" vs. the
   "other" operand (see `startswith`/`contains` for the common case, or
   `regex.match`/`glob.match` for a pattern-first signature).
3. Add a unit test in `ExpressionRendererTest` exercising both the
   affirmative and negated rendering.
4. If any acceptance-pack policy actually uses the builtin, regenerate the
   affected golden(s) with `-Dexplico.updateGolden=true` and review the diff
   before committing — golden regeneration is always a deliberate, reviewed
   act, never a side effect of getting a build green.

For anything larger (a new `Condition`/`Operand` shape, a new fallback
category), read `CLAUDE.md`'s "Hard rules" first — in particular, rule 2:
when the mapper can't classify a construct with full confidence, it must
fall back to verbatim source, never guess.

## License

Apache License 2.0 — see [`LICENSE`](LICENSE).
