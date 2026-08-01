# explico — POC Specification

**Version:** 1.0 (POC)
**Status:** Ready for implementation
**Target implementer:** Claude Code (AI-assisted, TDD-first)

---

## 1. Purpose

`explico` (Latin: *"I unfold, I explain"* — the counterpart to *rego*, *"I govern"*)
is a Kotlin/JVM tool that renders OPA Rego policy files into Markdown that
non-technical control owners and auditors can read, and reports meaningful changes
between two versions of a policy set.

The core design principle: **every statement in the output is true by construction.**
The tool renders only what it can derive mechanically from the Rego AST and its
METADATA annotations. Anything it cannot render faithfully is shown as clearly-marked
source code, never paraphrased. Rendering coverage is measured and reported, not hidden.

The target domain is **SDLC compliance release controls**: policies that gate
software releases — change-ticket approvals, separation of duties (author vs.
approver), pipeline check evidence, artifact provenance/signing, deployment freeze
windows. The engine itself is domain-agnostic; the domain determines the vocabulary
used in all samples, golden fixtures, and documentation examples.

This is a POC. Prefer the simplest implementation that satisfies the acceptance
criteria. No speculative abstraction, no plugin systems, no configuration files.

### 1.1 Goals

- G1: Render a directory of `.rego` files into one Markdown document per package,
  presenting each rule as a "control card": metadata, decision structure
  (ANY-of / ALL-of tree), and humanised leaf conditions.
- G2: Report the difference between two versions of a policy directory as a
  Markdown change report, categorising each control as added, removed,
  logic-changed, docs-changed, or unchanged.
- G3: Report rendering coverage (% of leaf expressions rendered as structured
  text vs. fallback source blocks) per package and overall.
- G4: Be usable both as a CLI and as a JVM library (public API with KDoc).
- G5: Given a directory of example input fixtures, evaluate each against the
  policy set via `opa eval` and render the observed verdicts as a
  "Worked examples" section on each control card (§6.7).

### 1.2 Non-goals (explicitly out of scope for the POC)

- No LLM integration of any kind.
- No differential evaluation **across versions** / decision-flip analysis (future
  phase). Single-version fixture evaluation for worked examples IS in scope (§6.7).
- No HTML output, no web server, no graphical rendering (Markdown only).
- No inlining/flattening of helper rule bodies (references are rendered as links).
- No support for: comprehensions, `every`, user-defined functions with parameters,
  `with` statements, `else` chains. These render via the fallback mechanism (§6.6).
- No git integration. Diff takes two plain directories; the caller checks out
  versions themselves (e.g. via `git worktree`).
- No Rego parsing in Kotlin. Parsing is delegated to the `opa` binary (§4).

---

## 2. Constraints and principles

- **Language/runtime:** Kotlin (latest stable 2.x), JVM toolchain Java 21.
- **Build:** Gradle (Kotlin DSL). Single module. `application` plugin for the CLI,
  `maven-publish` so the module can be consumed as a library.
- **Dependencies (keep to exactly these):**
  - `org.jetbrains.kotlinx:kotlinx-serialization-json` — parsing `opa` JSON output.
  - `com.github.ajalt.clikt:clikt` — CLI argument parsing.
  - Test: JUnit 5, AssertJ.
  - Nothing else. No DI framework, no logging framework (use `System.err`).
- **Simplicity rules:**
  - Data classes + top-level/object functions. No interfaces with a single
    implementation. No inheritance hierarchies unless the AST model requires a
    sealed hierarchy (it does — that is the one place).
  - Fail fast with clear error messages; no retry logic, no partial recovery
    beyond the per-expression fallback mechanism.
- **Determinism:** identical input directories must produce byte-identical output.
  Sort everything that could vary (file iteration order, map keys, rule order
  within a package by source position).

---

## 3. Module layout

```
explico/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/                        (wrapper)
├── README.md                      (§10)
├── ARCHITECTURE.md                (§10)
├── src/
│   ├── main/kotlin/io/explico/
│   │   ├── opa/                   OpaRunner.kt, OpaJson.kt (DTOs for opa output)
│   │   ├── model/                 Model.kt (domain model, §5)
│   │   ├── parse/                 AstMapper.kt (opa JSON → domain model)
│   │   ├── render/                PathHumanizer.kt, ExpressionRenderer.kt,
│   │   │                          MarkdownRenderer.kt, Coverage.kt
│   │   ├── diff/                  Canonicalizer.kt, PolicyDiff.kt, DiffRenderer.kt
│   │   ├── Explico.kt             public library facade
│   │   └── cli/Main.kt            Clikt commands
│   └── test/
│       ├── kotlin/io/explico/     unit + golden tests
│       └── resources/golden/      fixture .rego trees + expected .md output (§9)
```

Package base: `io.explico` (adjust to taste; keep it consistent).

---

## 4. External dependency: the `opa` binary

`explico` shells out to `opa`; it never parses Rego itself.

- **Discovery:** use the `OPA_BIN` environment variable if set, else `opa` on `PATH`.
- **Version check:** on startup run `opa version`. Require major version 1
  (i.e. ≥ 1.0.0). If missing or too old, exit with code 3 and a message that
  names the required version and how to set `OPA_BIN`.
- **Commands used:**
  1. `opa parse --format json <file.rego>` — one invocation per file. Produces the
     module AST (package, imports, rules, expressions, terms, with source locations).
  2. `opa inspect --annotations --format json <dir>` — one invocation per policy
     directory. Produces METADATA annotations with their scopes and locations.
  3. `opa eval --format json --input <file> [--data <dir>] "data.<package>"` —
     one invocation per fixture per package, for worked examples (§6.7).
- **Invocation:** `ProcessBuilder`, capture stdout/stderr separately, 30s timeout
  per invocation. Non-zero exit → throw `OpaInvocationException` carrying the
  stderr text (this is how Rego syntax errors surface to the user; pass them
  through verbatim).
- `OpaJson.kt` contains `@Serializable` DTOs for **only the fields we consume**.
  Configure `Json { ignoreUnknownKeys = true }` so OPA upgrades don't break us.

---

## 5. Domain model (`model/Model.kt`)

All immutable data classes / sealed interfaces. This model is the library's public
API surface together with the render/diff entry points.

```kotlin
data class PolicySet(val packages: List<PolicyPackage>)

data class PolicyPackage(
    val path: String,                 // e.g. "kubernetes.admission"
    val rules: List<RuleGroup>,       // sorted by name
    val sourceFiles: List<String>,    // relative paths, sorted
)

/** All rule definitions sharing one name in one package. Bodies are OR-ed. */
data class RuleGroup(
    val name: String,                 // e.g. "deny"
    val metadata: RuleMetadata?,      // from METADATA annotations (rule or document scope)
    val default: DefaultValue?,       // e.g. default allow := false
    val bodies: List<RuleBody>,       // each body = one "Situation" (ANY-of)
)

data class RuleMetadata(
    val title: String?,
    val description: String?,
    val controlId: String?,           // custom.control-id
    val frameworks: List<String>,     // custom.frameworks (list of strings), else empty
)

data class DefaultValue(val rendered: String)   // e.g. "false"

/** One rule body: conditions are AND-ed. */
data class RuleBody(
    val conditions: List<Condition>,
    val producesValue: String?,       // rendered head value/key if present (e.g. the msg), else null
    val sourceLocation: SourceRef,
)

sealed interface Condition {
    data class Comparison(val left: Operand, val op: Operator, val right: Operand) : Condition
    data class Membership(val negated: Boolean, val member: Operand, val collection: Operand) : Condition
    data class Truthy(val operand: Operand, val negated: Boolean) : Condition   // bare ref / not ref
    data class BuiltinCall(val name: String, val args: List<Operand>, val negated: Boolean) : Condition
    data class SomeIn(val variable: String, val collection: Operand) : Condition
    data class RuleReference(val packagePath: String, val ruleName: String, val negated: Boolean) : Condition
    /** Fallback: anything the mapper cannot classify. */
    data class Unrendered(val sourceText: String, val reason: String) : Condition
}

enum class Operator { EQ, NEQ, GT, GTE, LT, LTE }

sealed interface Operand {
    data class Path(val segments: List<PathSegment>) : Operand    // input/data references
    data class Literal(val rendered: String) : Operand            // scalars, small arrays/sets
    data class Variable(val name: String) : Operand
    data class Unrendered(val sourceText: String) : Operand
}

sealed interface PathSegment {
    data class Field(val name: String) : PathSegment
    object AnyIndex : PathSegment                                  // [_]
    data class VarIndex(val name: String) : PathSegment            // [x]
    data class KeyLiteral(val key: String) : PathSegment           // ["x-header"]
}

data class SourceRef(val file: String, val row: Int)
```

Mapping notes (`parse/AstMapper.kt`):

- A rule with multiple definitions (same name) → one `RuleGroup` with multiple bodies.
  Incremental definitions (`deny contains msg if { ... }`) likewise.
- `default x := v` → `DefaultValue`.
- The head value: for `deny contains msg if {...}` where the body assigns `msg`,
  capture the assigned string (if it is a string literal or a `sprintf` call, render
  the format string with `%v`/`%s` placeholders replaced by the humanised operand
  path in brackets, e.g. `"release [deployment id] has no approved change ticket"`).
  If it is anything else, `producesValue = null` — do not guess.
- Assignments (`:=`, `=` used as binding) whose left side is a local variable and
  whose right side is a plain path: record the binding in a per-body symbol table
  and **substitute the path inline** wherever the variable is used later in the
  same body, so intermediate variables disappear from the output. If the right
  side is anything other than a plain path, the assignment becomes `Unrendered`
  and later uses of the variable render as `Variable`.
- An expression that references another rule defined in the policy set (same or
  imported package) → `RuleReference`.
- Everything else → `Condition.Unrendered` with `reason` set to a short machine
  category (`"comprehension"`, `"every"`, `"function-call"`, `"with"`, `"else"`,
  `"unclassified"`). `sourceText` is recovered from the original file using the
  AST location (row/col span) — read the file, slice the lines. Do not attempt to
  pretty-print from the AST.
- Metadata: match `opa inspect` annotations to rules by file + row proximity
  (annotation location immediately precedes the rule) and by scope
  (`rule` applies to the following rule; `document` applies to all bodies of that
  rule name; `package` metadata attaches to the package, used only for the page
  header). `custom.control-id` and `custom.frameworks` are read if present.

---

## 6. Rendering (`render/`)

### 6.1 Output shape

`render` produces, in the output directory:

- One `<package-path>.md` per package (dots replaced by `-`,
  e.g. `kubernetes-admission.md`).
- `index.md` — table of all controls: control id, title, package, rule name,
  coverage %, source file. Sorted by control id (missing ids sort last by
  package+name).

### 6.2 Control card format

For each `RuleGroup`, emit:

```markdown
## REL-021 — Production release approval

*Rule `deny` in package `release.controls` — defined in `approvals.rego`*
*Frameworks: SOC 2 CC8.1, ISO 27001 A.8.32*

Production deployments must reference an approved change ticket, and the change
author must not be the sole approver.

**Default outcome:** false

**The rule matches when ANY of the following situations applies:**

### Situation 1 — all of the following are true

- `deployment ▸ environment` is `"production"`
- `change ▸ ticket ▸ approved` is absent or false

*Produces:* "release [deployment id] has no approved change ticket"

### Situation 2 — all of the following are true

- `deployment ▸ environment` is `"production"`
- `change ▸ author` is `change ▸ approver`
- rule [`has_release_manager_approval`](#release-controls-has_release_manager_approval) does not match *(referenced rule)*
- ⚠ **not rendered — shown as source:**

  ```rego
  count({a | some a in input.change.approvals; a.role == "release-manager"}) == 0
  ```
```

Formatting rules:

- Title line: `## <control-id> — <title>`; if no control id, `## <package>.<rule name>`;
  if no title, use the rule name.
- The `**Default outcome:**` line appears only when the rule has a `default`
  declaration; omit it entirely otherwise.
- Description (from metadata) verbatim under the header. If absent, emit
  `*No description provided in policy metadata.*` — make the gap visible.
- One `### Situation N` per rule body, in source order. If there is exactly one
  body, omit the "ANY" preamble and the situation heading; list conditions directly
  under "**All of the following are true:**".
- A path that begins with an `AnyIndex` segment context is prefixed `any of:`
  (see 6.4).
- `Unrendered` conditions render as the ⚠ block above, with the source in a
  `rego` fenced block, verbatim.

### 6.3 Condition phrasing (`ExpressionRenderer.kt`)

| Condition | Rendered as |
|---|---|
| `Comparison(l, EQ, r)` | `<l> is <r>` |
| `Comparison(l, NEQ, r)` | `<l> is not <r>` |
| `Comparison(l, GT, r)` | `<l> is greater than <r>` (analogous for GTE/LT/LTE: "at least", "less than", "at most") |
| `Membership(false, m, c)` | `<m> is one of <c>` |
| `Membership(true, m, c)` | `<m> is not one of <c>` |
| `Truthy(p, false)` | `<p> is true` (for a bare reference) |
| `Truthy(p, true)` | `<p> is absent or false` |
| `SomeIn(v, c)` | `for some <v> in <c>` |
| `RuleReference` | `see rule [\`name\`](#anchor)` / negated: `rule [\`name\`](#anchor) does not match` |

Builtin templates (`BuiltinCall`) — implement exactly this set; anything else is
`Unrendered("function-call")`:

| Builtin | Template |
|---|---|
| `startswith(a, b)` | `<a> starts with <b>` |
| `endswith(a, b)` | `<a> ends with <b>` |
| `contains(a, b)` | `<a> contains <b>` |
| `count(a)` (in comparison) | `the number of <a>` (renders as operand, e.g. "the number of X is greater than 0") |
| `lower(a)` / `upper(a)` | `<a> lowercased` / `<a> uppercased` (as operand) |
| `regex.match(p, v)` | `<v> matches pattern <p>` |
| `glob.match(p, _, v)` | `<v> matches glob <p>` |
| `object.get(o, k, d)` | `<o> ▸ <k> (default <d>)` (as operand) |
| `sprintf(f, args)` | only in head values, see §5 |
| `time.now_ns()` | `the current time` (as operand) |

Note two categories: builtins used as **conditions** (boolean position) and
builtins used as **operands** inside a comparison. `count`, `lower`, `upper`,
`object.get`, `time.now_ns` are operand-position; if found in condition position,
fall back to `Unrendered`.

### 6.4 Path humanisation (`PathHumanizer.kt`)

Deterministic breadcrumb style — no natural-language grammar:

1. Drop a leading `input` segment. Keep a leading `data` segment as `data`.
2. Split each field name on `camelCase`, `snake_case`, and `kebab-case`
   boundaries; lowercase the result (`securityContext` → `security context`).
3. `KeyLiteral` segments render quoted verbatim: `▸ "x-fapi-interaction-id"`.
4. Join segments with ` ▸ `.
5. `AnyIndex` (`[_]`): drop the segment from the breadcrumb and prefix the whole
   rendered condition once with `any of:`. Multiple `[_]` in one path still yield
   a single `any of:` prefix (POC simplification — document it).
6. `VarIndex` (`[x]`) where `x` was bound by `some x in <coll>`: render as the
   collection breadcrumb + `[each x]`. Unbound: `[x]`.
7. A **var-rooted path** (`stage.status` where `stage` was bound by
   `some stage in input.pipeline.stages`) renders as the bound collection's
   breadcrumb + `[each stage]` + the remaining fields:
   `` `pipeline ▸ stages ▸ [each stage] ▸ status` ``. A var-rooted path whose
   variable has no `SomeIn` binding in the same body → `Operand.Unrendered`.
8. Wrap the final breadcrumb in backticks.

Examples (these are unit-test cases):

| Rego path | Rendered |
|---|---|
| `input.pipeline.stages[_].checks[_].status` | `any of:` + `` `pipeline ▸ stages ▸ checks ▸ status` `` *(two `[_]`, one prefix — rule 5)* |
| `input.change.ticket.approvedBy` | `` `change ▸ ticket ▸ approved by` `` |
| `input.artifact.labels["signed-off-by"]` | `` `artifact ▸ labels ▸ "signed-off-by"` `` |
| `data.release.exempt_services` | `` `data ▸ release ▸ exempt services` `` |

Literals: strings quoted, numbers/booleans plain, small arrays/sets (≤ 5 elements,
all scalar) as comma-separated quoted list; larger or nested → `Operand.Unrendered`
with source text.

### 6.5 Anchors and cross-references

Each control card gets an HTML anchor derived from `control-id` (lowercased,
non-alphanumerics → `-`) or `package-rulename`. `RuleReference` links to it when the
target is in the same package (same file); cross-package references link to
`<other-package>.md#anchor`. A reference to a rule not found in the policy set
renders as plain `` `name` `` (no link).

### 6.6 Coverage (`Coverage.kt`)

- Numerator: rendered `Condition`s (everything except `Unrendered`).
- Denominator: all `Condition`s.
- Report per rule (on the card footer: `*Rendering coverage: 5 of 6 conditions*`),
  per package (page footer), and overall (in `index.md`).
- Operand-level fallbacks (`Operand.Unrendered`) do **not** count against coverage
  but are listed on the card footer as `contains 1 unrendered value`.

### 6.7 Worked examples (`render/Examples.kt`)

If an examples directory is supplied, each control card gains a
**Worked examples** section showing evaluated verdicts. The verdicts come from
running `opa eval` — the tool never predicts what a rule would do.

**Fixture format.** `--examples <dir>` contains `*.json` files, one fixture each:

```json
{
  "name": "hotfix without change ticket",
  "description": "Emergency deployment attempted straight from a feature branch.",
  "input": { "deployment": { "environment": "production", "id": "rel-4412" },
             "change": { "author": "asmith" } }
}
```

Fixtures are processed in filename order; `name` must be unique across the set
(duplicate → exit code 4).

**Evaluation.** One invocation per fixture per package:
`opa eval --format json --input <fixture-input> [--data <dataDir>]… "data.<package>"`.
Write the fixture's `input` object to a temp file for `--input`. Read every
`RuleGroup`'s value from the single result:

- Set rules (`deny contains msg`): **matched** if the set is non-empty; capture
  the produced messages. Empty/undefined → **not matched**.
- Boolean/complete rules: `true` → matched, `false`/undefined → not matched.
- Any other value type: render the JSON value verbatim as the outcome.

If `opa eval` fails for a fixture, skip that fixture with a warning on stderr
naming the fixture and passing through opa's error — never silently.

**Body attribution (best effort, never guessed).** If every body of a rule group
has a distinct `producesValue` (literal or sprintf template), match each produced
message back to its body by template and label the example *(Situation N)*. If
templates are missing, duplicated, or any body has `producesValue = null`,
attribute at rule level only, with no situation label.

**Card section format**, after the situations:

```markdown
**Worked examples**

- **hotfix without change ticket** — ❌ denied *(Situation 1)*
  *"release [rel-4412] has no approved change ticket"*
  - `deployment ▸ environment`: `"production"`
  - `change ▸ ticket ▸ approved`: absent
- **approved standard release** — ✅ allowed
  - `deployment ▸ environment`: `"production"`
  - `change ▸ ticket ▸ approved`: `true`
```

- Show up to **3 matching and 2 non-matching** fixtures per rule group, selected
  in filename order (fixed cap, deterministic, not configurable).
- Under each example, list the fixture's values at the paths the rule references:
  dedupe `Path` operands across all bodies, order by first appearance, resolve
  each against the fixture input by walking segments. `AnyIndex`: collect values
  across elements, comma-separated, capped at 3 with `…`. Missing path → `absent`.
- The word for a matched outcome depends on rule name: `deny`/`violation` →
  `❌ denied`; `allow` → `✅ allowed`; anything else → `matched` / `not matched`.
  (Simple name-based mapping, documented in the README.)

**Example-coverage report.** `index.md` gains a column: for each control, whether
the fixture set contains ≥ 1 matching and ≥ 1 non-matching example
(`✓ / ✓`, `✓ / –`, `– / –`). A closing line lists controls with no matching
example: *"N controls have no fixture demonstrating them — the corpus has gaps."*
This makes corpus thinness visible instead of implying completeness.

---

## 7. Diff (`diff/`)

### 7.1 Identity and canonical hash

- **Identity** of a control: `custom.control-id` if present, else
  `<package>.<rule name>`. If a control-id appears on both sides, it wins over
  name matching (i.e. a renamed rule keeping its control-id is *the same control*).
  Duplicate control-ids within one side → exit code 4 with an error listing them.
- **Canonical hash** of a rule group's logic (`Canonicalizer.kt`):
  1. Take the mapped `RuleGroup` (domain model, not raw AST — locations are
     already absent from everything except `SourceRef`).
  2. Strip all `SourceRef`s and `RuleMetadata`.
  3. Rename local variables positionally per body (`v1`, `v2`, … in order of first
     occurrence) — applied during mapping via the symbol table, affects
     `Variable`/`VarIndex`/`SomeIn` names.
  4. Serialise to JSON with sorted keys, SHA-256.
- **Metadata hash:** SHA-256 of the serialised `RuleMetadata` (nulls included).

### 7.2 Categories

For each identity across `oldDir` → `newDir`:

| Category | Condition |
|---|---|
| `ADDED` | identity only in new |
| `REMOVED` | identity only in old |
| `LOGIC_CHANGED` | canonical hash differs (metadata may also differ) |
| `DOCS_CHANGED` | canonical hash equal, metadata hash differs |
| `UNCHANGED` | both equal |

### 7.3 Change report (`DiffRenderer.kt`)

Single Markdown file:

- Summary table: counts per category.
- One section per non-`UNCHANGED` control, ordered REMOVED, ADDED, LOGIC_CHANGED,
  DOCS_CHANGED, then by control id:
  - `REMOVED`: the old control card, prefixed with a removal notice.
  - `ADDED`: the new control card.
  - `LOGIC_CHANGED`: the **new** control card, plus a `rego` fenced unified text
    diff of the two rule sources (extract via `SourceRef` spans; use a simple
    line-based diff — implement Myers or plain LCS in ~60 lines, no dependency).
  - `DOCS_CHANGED`: old vs new title/description/frameworks as a two-column table.
- A closing warning if any changed control has coverage < 100%:
  `⚠ N changed controls contain conditions that could not be rendered; review source diffs directly.`

The diff report must state explicitly at the top:
*"This report shows structural changes only. It does not evaluate whether the
policy became stricter or more permissive."*

---

## 8. Public API and CLI

### 8.1 Library facade (`Explico.kt`)

```kotlin
object Explico {
    fun load(policyDir: Path): PolicySet
    fun loadExamples(dir: Path): ExampleSet
    fun render(policySet: PolicySet, examples: ExampleSet? = null, dataDir: Path? = null): RenderedDocs
    fun diff(old: PolicySet, new: PolicySet): DiffReport  // model + markdown
}
data class RenderedDocs(val files: Map<String, String>, val coverage: CoverageSummary)
data class DiffReport(val entries: List<DiffEntry>, val markdown: String)
```

Everything else is `internal`. KDoc on every public declaration.

### 8.2 CLI (`cli/Main.kt`, Clikt)

```
explico render <policyDir> --out <outDir> [--examples <dir>] [--data <dir>]
explico diff <oldDir> <newDir> --out <reportFile>
explico version
```

- `render`: writes the files from `RenderedDocs`, prints coverage summary to stdout.
- `diff`: writes the report, prints the summary table to stdout.
- Exit codes: `0` success · `1` usage error · `2` Rego parse error (opa stderr
  passed through) · `3` opa binary missing/incompatible · `4` duplicate control-ids.
- `--out` contents are fully replaced (delete stale files from previous runs).

---

## 9. Testing strategy

TDD throughout, anchored by the **acceptance pack** shipped alongside this spec
(`explico-acceptance-pack/`): five validated SDLC release-governance policies,
six fixtures, a data document, decided-ahead assertions (its README), and an
ahead-of-time expected render of the simplest package. The pack is the corpus for
tests, the `samples/` content, and the reference documentation — one artefact,
three duties. Copy it into the repo at `src/test/resources/acceptance/` and
symlink or copy `policies/` + `examples/` + `data/` into `samples/`.

Test layers:

1. **Unit tests** (no `opa` needed): `PathHumanizer`, `ExpressionRenderer`,
   `Canonicalizer` (hash stability: variable rename → same hash; operand change →
   different hash), the line differ, and `AstMapper` against small checked-in
   `opa parse` JSON captures (`src/test/resources/ast/*.json`) so mapper tests
   don't require the binary.
2. **Tier-1 acceptance tests** (require `opa`; guard with
   `Assumptions.assumeTrue(OpaRunner.isAvailable())`): JUnit tests implementing
   the "Tier 1 assertions" tables in the acceptance pack README, verbatim —
   contains/count assertions on the generated markdown, insensitive to whitespace
   and ordering. **These are authored before the renderer exists and must never
   be weakened to make the implementation pass.** The pack's fixture verdict
   matrix (produced by `opa eval`, not by hand) is the oracle for the
   worked-examples assertions.
3. **Tier-2 golden tests** (approval-style, byte-exact):
   `expected/release-approvals.md` in the pack is the ahead-of-time proposal;
   cosmetic details may be reconciled ONCE at first successful render, then the
   file is frozen and byte-exact goldens are generated for the remaining
   packages and the diff report. System property `-Dexplico.updateGolden=true`
   regenerates goldens; regeneration is a deliberate, reviewed act.

Additional golden scenarios beyond the acceptance pack:

- `diff-all-categories` — old/new variants of the pack policies exercising every
  diff category, including a control-id-preserving rename (must be `UNCHANGED`
  or `LOGIC_CHANGED`, never REMOVED+ADDED).

Run the whole suite via `./gradlew check`. CI note in README: the pipeline must
install `opa` (pin the exact version; the pack was validated against 1.19)
before `check`.

---

## 10. Documentation deliverables

- **README.md** — what it is, the true-by-construction principle, install
  (including opa), CLI usage with a worked example (input rego → output markdown
  excerpt), library usage snippet, exit codes, limitations table (the §1.2 list,
  verbatim, so users know what falls back to source).
- **ARCHITECTURE.md** — one page: the pipeline
  (`opa` → DTOs → domain model → renderer/differ), where the extension points are
  (builtin template table, humaniser), and *why* there is no Kotlin Rego parser.
- **KDoc** on all public API; `CONTRIBUTING` section in README describing how to
  add a builtin template (add table row + unit test + golden update).
- Sample policy set under `samples/` mirroring the `multi-body` golden scenario,
  referenced from the README.

---

## 11. Implementation order (suggested milestones)

1. Gradle skeleton, `OpaRunner` + version check, DTOs; smoke test parsing one file.
2. `AstMapper` + domain model, driven by checked-in AST JSON fixtures (TDD).
3. `PathHumanizer` + `ExpressionRenderer` (pure unit-tested functions).
4. `MarkdownRenderer` + coverage; first golden test (`single-rule`) passing.
5. Metadata attachment (`opa inspect`), anchors/cross-references; remaining
   render goldens.
6. Worked examples: fixture loading, `opa eval` integration, body attribution,
   card section, example-coverage report; `worked-examples` golden.
7. `Canonicalizer` + `PolicyDiff` + `DiffRenderer`; diff golden.
8. CLI, README, ARCHITECTURE, samples; `maven-publish` config.

Each milestone ends green (`./gradlew check`) and committed.

---

## 12. Acceptance criteria (POC done means)

- [ ] `explico render` on the sample policy set produces deterministic Markdown
      matching the golden output, with per-package and overall coverage reported.
- [ ] Every unrenderable construct in the samples appears as a marked source
      block; no construct is silently dropped or paraphrased.
- [ ] `explico diff` on the diff golden classifies all five categories correctly,
      including the control-id-preserving rename.
- [ ] Formatting-only changes to a `.rego` file (`opa fmt`, comment edits,
      variable renames) yield `UNCHANGED`.
- [ ] With `--examples` supplied, control cards show evaluated verdicts with
      relevant input values; body attribution appears only where message templates
      are unambiguous; a fixture that fails to evaluate produces a stderr warning,
      never a silent omission; `index.md` reports fixture-coverage gaps.
- [ ] Missing `opa` binary produces exit code 3 with an actionable message.
- [ ] Published artifact usable from another Gradle project via `Explico.load/render/diff`.
- [ ] README example can be followed start-to-finish by someone who has never
      seen the project.
