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
  a Maven-publishing plugin so the module can be consumed as a library
  (`com.vanniktech.maven.publish` as of §10.1 — supersedes this line's original
  plain `maven-publish`, since Sonatype's Central Portal migration needed more
  than the raw plugin provides).
- **Dependencies (keep to exactly these):**
  - `org.jetbrains.kotlinx:kotlinx-serialization-json` — parsing `opa` JSON output.
  - `com.github.ajalt.clikt:clikt` — CLI argument parsing.
  - Test: JUnit 5, AssertJ.
  - Nothing else. No DI framework, no logging framework (use `System.err`).
  - This list governs the *library's own* runtime/test dependencies (what
    ships inside the published jar or its POM). It does not cover build-only
    Gradle plugins with no runtime footprint: `com.vanniktech.maven.publish`
    (§10.1), `org.gradle.toolchains.foojay-resolver-convention` (§10.2), and
    `com.gradleup.shadow` (§13.1) are all build tooling, each confirmed absent
    from the published POM's own dependency list.
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
  1. `opa parse --format json --json-include locations <file.rego>` — one
     invocation per file. Produces the module AST (package, imports, rules,
     expressions, terms, with source locations). **The `--json-include
     locations` flag is required** — without it `opa` silently omits every
     `location` field (confirmed empirically against real `opa` output; not
     an assumption), which would break `SourceRef`, fallback source recovery,
     source-order sorting, and diff's source-span extraction. Each node's
     `location.text` is base64-encoded verbatim source for that node (see §5).
  2. `opa inspect --annotations --format json <dir>` — one invocation per policy
     directory. Produces METADATA annotations with their scopes and locations.
     No extra flag needed: unlike `opa parse`, this command includes locations
     by default.
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
    val messageTemplate: String?,     // raw string literal / sprintf template, unhumanised; see §6.7
    val sourceLocation: SourceRef,
    val sourceText: String,           // whole body's verbatim source (base64-decoded rule-level location.text); see §7.3
)

sealed interface Condition {
    // §14 amendment (§14.8): negated is true for `not x == y` etc -- Rego allows `not` on any comparison.
    data class Comparison(val left: Operand, val op: Operator, val right: Operand, val negated: Boolean = false) : Condition
    data class Membership(val negated: Boolean, val member: Operand, val collection: Operand) : Condition
    data class Truthy(val operand: Operand, val negated: Boolean) : Condition   // bare ref / not ref
    data class BuiltinCall(val name: String, val args: List<Operand>, val negated: Boolean) : Condition
    // §14 promotion (§14.6): key is non-null for the two-variable form (`some k, v in c`).
    data class SomeIn(val variable: String, val collection: Operand, val key: String? = null) : Condition
    data class RuleReference(val packagePath: String, val ruleName: String, val negated: Boolean) : Condition
    /** Fallback: anything the mapper cannot classify. */
    data class Unrendered(val sourceText: String, val reason: String) : Condition
}

enum class Operator { EQ, NEQ, GT, GTE, LT, LTE }

sealed interface Operand {
    data class Path(val segments: List<PathSegment>) : Operand    // input/data references
    data class Literal(val rendered: String) : Operand            // scalars, small arrays/sets
    data class Variable(val name: String) : Operand
    // §14 promotion: count/lower/upper/object.get/time.now_ns -- see §14.4.
    data class BuiltinCall(val name: String, val args: List<Operand>) : Operand
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
  If it is anything else, `producesValue = null` — do not guess. **This also covers
  the case where a `sprintf` argument itself cannot be humanised** — a var-rooted,
  key-literal, or any-index path (e.g. a message built from `stage.name` after
  `some stage in ...`). The whole `producesValue` is null in that case, never a
  phrase with some placeholders humanised and others raw or guessed.
  Alongside `producesValue`, also capture `messageTemplate`: the same string
  literal or `sprintf` format string, but with `%v`/`%s` left untouched — no
  attempt to humanise the arguments. `messageTemplate` stays populated even
  when `producesValue` is null (e.g. the var-rooted-argument case above),
  since §6.7's body attribution only needs the template's literal/wildcard
  shape to match a real evaluated message, not a display rendering of its
  arguments. `producesValue` remains the display-only field for the card's
  own *"Produces: ..."* line and is never affected by this.
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
  `"unclassified"`). `sourceText` is recovered by base64-decoding the AST
  node's own `location.text` — **not** by re-reading the source file and
  slicing by row/col span. `opa parse --json-include locations` already
  returns the exact verbatim source for every node this way, so a second file
  read is unnecessary; it's also guaranteed byte-for-byte consistent with what
  `opa` actually parsed. Do not attempt to pretty-print from the AST.
- Metadata: match `opa inspect` annotations to rules **by `opa inspect`'s own
  `path` field** (packagePath + ruleName, exact match) — **not** file + row
  proximity as originally planned. `opa` has already resolved which rule each
  annotation belongs to, including correctly deduplicating a document-scoped
  annotation across a rule's multiple bodies (confirmed empirically:
  `release.approvals.deny` has 2 bodies but exactly 1 `opa inspect` entry, at
  the row of the first). Re-deriving that resolution ourselves via row
  proximity would risk disagreeing with `opa`'s own answer for no benefit.
  Scope still matters for what a `path`-matched entry means (`rule` applies to
  the following rule; `document` applies to all bodies of that rule name), but
  not for the matching mechanism itself. `package`-scoped annotations are
  skipped — the domain model has no attachment point for package-level
  metadata, and no acceptance-pack policy uses that scope. `custom.control-id`
  and `custom.frameworks` are read if present.

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
| `Comparison(l, op, r, negated=true)` (§14.8) | literal negation of the positive wording above, e.g. EQ→`is not`, NEQ→`is`, GT→`is not greater than` (never a different operator's positive form, e.g. never "is at most" for negated GT) |
| `Membership(false, m, c)` | `<m> is one of <c>` |
| `Membership(true, m, c)` | `<m> is not one of <c>` |
| `Truthy(p, false)` | `<p> is true` (for a bare reference) |
| `Truthy(p, true)` | `<p> is absent or false` |
| `SomeIn(v, c)` | `for some <v> in <c>` |
| `SomeIn(v, c, k)` (two-variable form, §14.6) | `for some <k>, <v> in <c>` |
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
has a distinct `messageTemplate` (literal or sprintf template, unhumanised —
see §5), match each produced message back to its body by template and label
the example *(Situation N)*. If templates are missing or duplicated, attribute
at rule level only, with no situation label. Note this is keyed on
`messageTemplate`, not the display-only `producesValue`: a body whose message
argument can't be humanised (e.g. a var-rooted `sprintf` argument) still has
a perfectly good template for matching purposes even though its `producesValue`
is null — REL-004's body 2 (`window.name`) is exactly this case, and the
acceptance pack's fixture verdict matrix requires it to still get a situation
label.

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
  2. Strip all `SourceRef`s and `RuleMetadata`, and the `RuleGroup.name` itself —
     the rule's own name is identity, not logic, and a control-id-preserving
     rename (this section, above) must hash identically to its pre-rename
     logic when nothing else changed, which is only possible if the name
     doesn't feed the hash.
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
    diff of the two rule sources. Extraction uses the same mechanism as §5's
    fallback source recovery — the rule's own AST node `location.text`
    (base64-decoded), not a second file read sliced by `SourceRef`'s row —
    since it is already the exact verbatim source for that rule, spanning its
    full body. Use a simple line-based diff — implement Myers or plain LCS in
    ~60 lines, no dependency.
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

### 10.1 Maven Central publishing (session 7, approved deviation)

Not specified in the original spec text; added per explicit operator instruction.
Verified against the plugin's current docs
(https://vanniktech.github.io/gradle-maven-publish-plugin/central/), not from memory,
since Sonatype migrated to the Central Portal after this spec's original research.

- **Plugin:** `com.vanniktech.maven.publish` 0.37.0, replacing the raw `maven-publish`
  plugin's manual `publications {}` block entirely -- `mavenPublishing { }`'s
  `coordinates()`/`pom {}` DSL is the single source of truth for the publication now.
- **Group ID:** `io.github.wakaleo` -- a GitHub-username namespace, auto-verified via
  GitHub OAuth on the Central Portal, chosen specifically because it needs no domain
  ownership proof (unlike `io.explico`, which would require verifying an `explico.io`
  DNS record). Independent of the `io.explico` Kotlin package namespace throughout the
  codebase -- only the publishing coordinate changed, no source was renamed. Confirmed
  with the operator rather than assumed.
- **Version:** `0.1.0` (dropped the `-POC` suffix `build.gradle.kts` carried through
  development -- Maven Central coordinates are permanent once published, so this is the
  point that string had to become a real release version).
- **Signing is conditional on credential presence** (`if
  (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()`),
  not unconditional. This keeps `publishToMavenLocal` usable for local and CI smoke
  testing without a real GPG key, while never being a silent "publish unsigned" path for
  an actual release: `publishToMavenCentral` without a key fails (Central rejects
  unsigned publications), and CI's `release.yml` (§10.2) also verifies all four secrets
  are present *before* invoking Gradle at all.
- **`automaticRelease = false`**, not `true`: publishing to Central is irreversible (a
  coordinate can never be deleted or overwritten), so a tagged release gets one manual
  approval on the Central Portal after CI validates and uploads it, rather than a fully
  unattended publish on every `v*` push. An operator judgment call, not a plugin default
  -- reasonable to revisit once the pipeline has a track record.
- **Credentials/key: environment variables only, mapped from GitHub Actions secrets
  (§10.2), nothing secret in the repo.** The plugin reads Gradle properties
  `mavenCentralUsername`/`mavenCentralPassword`/`signingInMemoryKey`/
  `signingInMemoryKeyPassword`, which Gradle itself populates from any
  `ORG_GRADLE_PROJECT_<name>`-prefixed environment variable -- not plugin-specific
  behaviour, just how Gradle always maps env vars to project properties.
- **Consumer smoke test** (`consumer-smoke-test/`): a deliberately standalone Gradle
  build -- no `include(...)` in the root `settings.gradle.kts` -- so it resolves
  `io.github.wakaleo:explico` from `mavenLocal()`/`mavenCentral()` like a real external
  consumer, never via Gradle's own project-dependency substitution (which would mask a
  broken or missing publication). Calls `Explico.load`/`Explico.render` against the
  repo's own `samples/` and asserts specific rendered content (a real control-id
  appearing in a real card heading), not just "it ran without throwing." Verified
  end-to-end against a real `publishToMavenLocal` output.

### 10.2 CI/release workflows (`.github/workflows/`, session 7)

- **`ci.yml`**, on push to `main` and on pull requests: installs `opa` pinned to
  exactly `1.19.0` (the version the acceptance pack's goldens were validated
  against), runs `./gradlew check acceptanceTest`, then `publishToMavenLocal`
  followed by the consumer smoke test (§10.1) -- proving the publication and the
  external-consumer resolution path on every change, not only at release time.
- **`release.yml`**, on push of a tag matching `v*`: the same verification
  sequence, then `publishToMavenCentral`. Required secrets: `MAVEN_CENTRAL_USERNAME`,
  `MAVEN_CENTRAL_TOKEN`, `SIGNING_KEY`, `SIGNING_PASSWORD` -- mapped to the
  `ORG_GRADLE_PROJECT_mavenCentralUsername`/`mavenCentralPassword`/
  `signingInMemoryKey`/`signingInMemoryKeyPassword` environment variables the
  plugin actually reads (§10.1). A dedicated step checks all four are non-empty
  and fails the job immediately, before running any tests, if any are missing --
  `automaticRelease = false` (§10.1) already means a human approves the actual
  release on the Central Portal, but this is a second, independent guard against
  ever reaching the publish step unsigned or unauthenticated.
- Every action reference (`actions/checkout`, `actions/setup-java`,
  `gradle/actions/setup-gradle`, `open-policy-agent/setup-opa`) was pinned to its
  current major-version tag, confirmed against the real repository at the time of
  writing, not assumed from memory.

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

---

## 13. Post-POC increment 1: distribution, documentation, API ergonomics

Scope decided before implementation, recorded here per this project's own
convention (design decisions get written down before, or as, they're made —
see `CLAUDE.md`'s session-by-session log for the reasoning behind each one).
Everything below is *new* scope beyond §12's POC acceptance criteria, not a
correction to them — the POC is done; this is what comes after it.

### 13.1 Shadow-jar distribution

A single runnable jar bundling explico and all its runtime dependencies
(`kotlinx-serialization-json`, `clikt`/its `clikt-mordant` dependency), so a
user only needs a JDK and the jar — no Gradle, no dependency resolution — to
run the CLI. `java -jar <jar> version` must work as a plain subprocess
invocation, proven by a real test (the same process-level-test convention
§8.2's exit codes already use, not a library-level shortcut).

- **Plugin: `com.gradleup.shadow`** — the actively maintained fork; the
  original `com.github.johnrengelman.shadow` is unmaintained. Exact version
  confirmed against the plugin's own current docs at implementation time,
  not assumed from training-data memory (the same standard §10.1/§10.2's
  plugin choices were held to).
- Build-only tooling (§2): does not appear in the published library jar's own
  dependencies, so it doesn't expand the closed runtime/test whitelist.
- `release.yml` (§10.2) attaches the built shadow jar to the GitHub Release
  created for the pushed `v*` tag — the Quick demo section (§13.3)'s
  "download the jar" step needs something real to point at.

### 13.2 `explico demo`

A fourth CLI command, alongside `render`/`diff`/`version` (§8.2): zero-argument,
self-contained walkthrough for someone who has the jar and `opa` but no
checkout of this repository.

- Embeds the acceptance pack's `policies/`, `examples/`, and
  `data/release/data.json` as jar resources, packaged at build time from the
  same `src/test/resources/acceptance/` directory the acceptance pack itself
  uses — one source of truth, never a second hand-copied set that can drift
  (the same principle behind `samples/` in §9/§10).
- On run: extracts those resources to `./explico-demo/` in the current
  working directory. **Refuses and exits 1 if that directory already
  exists** — never silently overwrites or merges into a directory the user
  might have other content in.
- Then runs the equivalent of `render ./explico-demo/policies --out
  ./explico-demo/docs --examples ./explico-demo/examples --data
  ./explico-demo/data/release/data.json`, and prints a short, exact pointer
  to the file to open (e.g. "Open ./explico-demo/docs/release-approvals.md
  to see a rendered control card.") — two lines, not a wall of text.
- **Missing/incompatible `opa`**: exit 3 (same code as `render`/`diff`),
  with an actionable message naming the *exact pinned version* (matching
  CI's pin, §10.2) rather than just "version 1.x" — a first-time user
  following the Quick demo section needs the precise version to install,
  not a range.
- **`--fetch-opa`** (optional flag): if it fits cleanly — a single pinned
  version, a per-platform download URL, and a checksum verification, all
  addable without materially growing `OpaRunner`'s scope or introducing a
  new dependency (the JDK's own `java.net.http` should suffice) — downloads
  that exact pinned `opa` build to a cache directory and uses it for the
  demo run. If achieving checksum-verified, correctly-platformed download
  turns out to need meaningfully more machinery than that (its own
  retry/proxy handling, a new HTTP client dependency), this flag is
  explicitly **deferred to a later increment** instead of half-built — a
  decision to resolve during implementation, not pre-committed either way.

### 13.3 Documentation

Three new files under `docs/`, each with a single clear job, no overlap:

- **`docs/user-guide.md`** — the CLI and library reference: every flag,
  every exit code, every public facade function, in one place. What the
  README's CLI-usage/Library-usage sections summarize, this covers
  exhaustively.
- **`docs/policy-authoring.md`** — for someone writing Rego policies *for*
  explico to render well: which METADATA fields map to which part of a
  control card, the distinct-`producesValue`-per-body convention that drives
  `*(Situation N)*` attribution (§6.7) and why it's "distinct or no label,
  never guessed," control-id/`frameworks` conventions, the fallback mechanism
  and coverage percentage explained explicitly as *intentional, honest
  design* (a lower coverage number means "here's what to read as source,"
  not "the tool is unfinished"), and how to author a worked-example fixture
  (§6.7's format).
- **`docs/tutorial.md`** — walks all five `samples/` policies through
  `render` → inspecting the worked-examples section → `diff`, with real
  command output inlined throughout — never hypothetical/hand-typed output,
  the same standard the README's own worked example already holds itself to
  (session 7's audit).

README gains a **Quick demo** section, ahead of the existing, more involved
Install/CLI-usage sections: download the release jar (§13.1), run `explico
demo`, see a real rendered card, in as few steps as possible. Links out to
the three `docs/` files instead of duplicating their content.

### 13.4 Cold-start demo test (jar-only path)

Same method as session 7's README cold-start test, narrower scope: a fresh
subagent with *only* the built jar, `opa` on `PATH`, and the README's Quick
demo section — explicitly no repository checkout, no other file in this
project visible to it. Must reach a rendered control card. Exactly like
session 7: fix what it stumbles on, fix the docs (or the demo command's own
behavior) — never the tester, never chalk a finding up to "well, a real user
would know better." (§13.9 extends this to a second, programmatic-path test.)

### 13.5 Java-interop ergonomics

`Explico` (the public facade, §8.1) is meant to be usable from Java, not
just Kotlin (§1.1 G4: "usable both as a CLI and as a JVM library") — but a
Kotlin `object`'s members are, by default, only reachable from Java via
`Explico.INSTANCE.load(...)`, and a function with default parameter values
(`render`'s trailing `examples`/`dataDir`) requires a Java caller to supply
every argument explicitly, since Java has no concept of Kotlin default
parameters. Neither is how the facade is documented or intended to be
called from Java.

- **`@JvmStatic`** on every `Explico` member function makes them plain
  static methods from Java's perspective: `Explico.load(dir)`, not
  `Explico.INSTANCE.load(dir)`.
- **`@JvmOverloads`** on `render` generates the overloads a Java caller
  needs to write `Explico.render(policySet, policyDir)` without passing
  explicit `null`s for `examples`/`dataDir`.
- Proven by a real Java test file, compiled and run against the facade —
  not inferred from the annotations' mere presence, the same standard
  every other cross-boundary claim in this project is held to.

### 13.6 Worked-examples provenance footer

One additional line on every rendered **Worked examples** section: *"Examples
are evaluated against this policy version by OPA at generation time."* — a
muted, explicit restatement of §6.7's own invariant (worked examples are
never predicted, always freshly evaluated) directly in the artefact a
reader is looking at, not only in this spec and `CLAUDE.md`. A wording and
placement decision, not a behavior change — regenerating the Tier-2 goldens
for it is its own deliberate, reviewed act (`-Dexplico.updateGolden=true`),
never a side effect of an unrelated change.

### 13.7 Dogfooding: `generateSampleDocs`

A Gradle task rendering `samples/` (with its examples and data) into
`docs/sample-output/`, through the exact same `Explico.render` path every
other consumer uses — no injected banners, no divergence from what
`Explico.render`'s own output actually is. A `README.md` inside
`docs/sample-output/` states plainly that the directory is generated and
should not be hand-edited. The generated output is committed, so a
browsing reader on GitHub sees real rendered cards without running
anything, and `ci.yml` runs the task and fails the build on `git diff
--exit-code` over `docs/sample-output/` if the committed output has
drifted from what a fresh render actually produces — the identical
drift-check recipe `docs/user-guide.md` documents for a consumer's own CI
(§13.8), applied to this repository's own generated docs, so the two can
never silently diverge from each other. The README's own worked-example
section links directly to a real card inside this directory, rather than
only showing an inline excerpt.

### 13.8 Documentation, expanded scope

Beyond §13.3's original three files:

- **`docs/user-guide.md`** gains: Java *and* Kotlin snippets for the
  load→render→write-files path (not Kotlin only); the two resource-loading
  patterns a real consumer needs — build-time generation from a project's
  own policy files (the primary, recommended pattern; §13.7 is this
  project's own working example of it) versus extracting embedded jar
  resources to a temp directory at runtime (needed when the policies
  themselves are shipped inside a jar) — stated plainly that jar resources
  aren't filesystem paths and can't be passed directly to `Explico.load`,
  which needs a real directory; guidance on choosing an output directory
  (a dedicated directory is recommended; writing alongside source is
  possible, but the output is organised per-*package*, not per source
  file, so it doesn't map onto a `.rego` tree 1:1); a Gradle `JavaExec`
  task recipe for wiring `explico render` into another project's own
  build; and the CI drift-check recipe §13.7 itself uses (render to a
  temp directory, diff against committed output, fail the build on
  divergence).
- **`docs/policy-authoring.md`**'s METADATA section (§10.1's own
  cross-reference notwithstanding, this was written directly against this
  section) is joined by an expanded fixtures section: the fixture `name`
  as the displayed title, uniqueness and filename ordering, a naming
  *principle* (name the business scenario, not the data — violation-
  phrased names for a fixture expected to be denied, in-order-or-near-miss
  phrasing for one expected to be allowed), `description` as
  captured-but-never-rendered, and a full "Where examples come from"
  treatment: the committed-vs-computed invariants, the manual sourcing
  ladder (harvest from existing `*_test.rego` `with input as` blocks,
  hand-author flagship scenarios, capture sanitised real traffic),
  selection guidance (one example per Situation plus a near-miss), and
  the corpus's dual role as a future change-impact seed. Explicitly never
  documents fixture-generation tooling, since none exists.
- **`docs/tutorial.md`** gains a fourth stage: after render → worked
  examples → diff, a walkthrough of the same scenario invoked
  programmatically (not just via the CLI), with real output.
- **README.md** opens with the name-origin paragraph (sourced from §1's
  own opening sentence) and gains a "Relationship to OPA and Rego" section
  (drives the real `opa` binary rather than reimplementing Rego, reads
  standard METADATA only, targets OPA 1.x, positioning vs. Regal and
  Konstraint, an explicit independence disclaimer) and a Quick demo
  section (§13.2), linking out to `docs/` rather than duplicating it.
- **Every code snippet shown in any of the above lives in a compiled test
  source set** (`docsSnippets`), not just an inline fenced code block
  copied by hand into the Markdown — a snippet that doesn't actually
  compile fails the build, the same "verified, not assumed" standard
  every empirical claim in this project is held to, extended to cover
  documentation's own code examples.

### 13.9 Cold-start tests, extended

Two, both per §13.4/session 7's established method (a fresh subagent, no
context from the authoring session, told exactly what a real newcomer
would have and nothing else):

1. **The jar-only path** (§13.4, already run once): only the built shadow
   jar, `opa` on `PATH`, and the README's Quick demo section. Must reach a
   rendered control card.
2. **The programmatic path**: only `docs/user-guide.md`'s Kotlin
   load→render→write-files snippet, followed in a throwaway Gradle
   project with no other access to this repository. Must produce real
   rendered output.

Both: fix what the agent stumbles on in the docs or the tool's own
behavior — never dismiss a finding as "a real reader would know better."

## 14. Rego language coverage audit

A dedicated audit session (not a feature expansion) verifying every
rendering decision `AstMapper`/`ExpressionRenderer`/`PathHumanizer` make
against the real Rego language, not just the 5 policies the acceptance pack
happens to exercise. The design principle already implicit in §6.2/§6.3
("leaf conditions rendered, helper rules referenced one level deep, anything
deeper shown as source") is confirmed as the correct rationale for the
fallback bucket, not a limitation to design around. Full findings, the
construct/builtin-by-builtin classification table, and a ranked promotion
backlog for future increments live in
[`docs/rego-coverage.md`](../docs/rego-coverage.md) — this section records
only the spec-relevant outcomes: the amendment below, and the audit's
existence as a permanent, executable regression suite
(`src/test/resources/probes/` + `RegoCoverageAuditTest.kt`), not prose that
can silently drift from what the code actually does.

### 14.1 Amendment: `Truthy` non-negated wording

Spec §6.3's original table specified `Truthy(p, false)` → `<p> is true`.
This session found and confirmed (via a real probe evaluated through the
actual pipeline, `src/test/resources/probes/38-truthy-non-boolean-field.rego`)
that this wording is only accurate when the referenced field happens to be
boolean — the acceptance pack only ever exercises the *negated* bare-truthy
form (`not input.change.ticket.approved`), never the non-negated one, so
this was untested against a real non-boolean field until now. Rego's actual
semantics for a bare condition-position reference is "defined and not equal
to boolean `false`" — type-agnostic, not "is exactly `true`". For a non-empty
string field, "is true" is simply wrong.

**§6.3's table is amended**: `Truthy(p, false)` → `<p> is present and not
false` — the exact type-agnostic mirror of the already-correct negated
wording (`<p> is absent or false`). No acceptance-pack golden changes (the
non-negated form isn't exercised by any of the 5 real policies).

### 14.2 Fixed this session (previously ERROR or MISLEADING)

Four other findings, all confirmed by running the real pipeline (two of
them requiring an actual `opa eval` run to settle the runtime semantics, not
just reading the code) rather than reasoned about in the abstract:

- **A crash**, not a fallback: a declare-only single-variable `some key`
  (no `in`) threw an uncaught `JsonDecodingException` and took down the
  entire render, violating "the fallback is sacred" more severely than any
  ordinary unclassified construct — a crash isn't even a fallback.
- **Silent data loss**: an `else`-chain's branch is a sibling field on
  opa's own rule AST (`OpaRule` didn't model it); `ignoreUnknownKeys`
  silently dropped it entirely, so the rendered card showed only the `if`
  branch as if it were the rule's whole logic, with no fallback marker at
  all — not "shown as source", just gone.
- **Silent, false-by-omission**: a `with` override has the identical root
  cause at the expr level (`OpaExpr` didn't model `with`) — a rule
  reference evaluated against a deliberately modified input rendered as an
  ordinary, unqualified reference to the real one.
- **Misleading**: a bare reference (negated or not) to a **partial**
  (`contains`/object) rule was classified identically to a reference to a
  complete/boolean rule. A partial rule is always defined, even as an
  empty set — confirmed via real `opa eval` that the enclosing condition
  stays undefined regardless of the partial rule's contents — so "does not
  match" (negated) or an implied real conditional (non-negated) both
  describe something that can never actually happen.

All four are fixed (§14's own `OpaRule`/`OpaExpr` field additions and the
rule registry's new partial/complete distinction), covered by a permanent
regression test per probe, and confirmed absent from every existing
acceptance-pack policy — nothing previously shipped was affected, but any
future policy using any of these four idioms would have been silently
misrendered before this session.

### 14.3 Deliberately not changed

Reviewed and reaffirmed, not altered: `Operand.Unrendered`'s rendering as
plain backticked verbatim source, indistinguishable inline from a real
humanized path (only disclosed in aggregate via the coverage footer's
"contains N unrendered value(s)"). This session's probe corpus showed the
pattern is more pervasive than session 3/4 originally scoped it for, but the
underlying assertion in every affected bullet remains true — this is a
legibility question, not a semantic one, and is left to a future increment's
own deliberate review rather than folded into this audit as a side effect.

### 14.4 Promotions (follow-up passes, same session)

Four items from §14's own promotion backlog (`docs/rego-coverage.md`) were
selected and implemented immediately after the audit landed, rather than
deferred wholesale:

- `count(x)`/`lower(x)`/`upper(x)` in operand position now render via a new
  `Operand.BuiltinCall(name, args)` model variant, using the exact templates
  §6.3's table already specified ("the number of X", "X lowercased", "X
  uppercased"). ~~`time.now_ns` remains unpromoted — no arguments to extend a
  breadcrumb with, and not yet forced by any real case.~~ **Promoted in a
  further follow-up session — see §14.5.**
- The long-documented §5 rule — "assign a local var to a plain path,
  substitute inline later" — is implemented. A per-body binding now
  distinguishes *why* a variable is bound (`some x in y` iteration vs. a
  plain-path assignment vs. anything else), since the two constructs need
  different substitution behavior (an iteration variable's "[each x]"
  marker must never apply to a plain substitution, or vice versa).
  Resolution is transitive and works both as a bare reference and as a
  ref-chain root. The wording for the *non*-promoted case (assignment to
  something other than a plain path) is also now implemented exactly as
  originally specified: a later bare use of such a variable renders as
  `Operand.Variable`, not the generic unbound fallback.
- `object.get(o, k, d)` in operand position now also renders via
  `Operand.BuiltinCall`, but only in the unambiguous shape §6.3's own
  template implies: `o` a real path, `k` a plain string literal. `k`
  extends `o`'s own breadcrumb as an ordinary `PathSegment.KeyLiteral` --
  the same mechanism a real bracket-string path (`labels["signed-off-by"]`)
  already uses -- rather than inventing a second rendering convention. A
  non-string key has no such extension rule and stays `Operand.Unrendered`
  rather than guessing one.

None of the four promoted constructs appear in the existing acceptance
pack, so no golden or `docs/sample-output/` content changed — confirmed by
running both after each promotion, not assumed. Full rationale, verification
steps, and updated coverage/backlog tables are in `docs/rego-coverage.md`,
not duplicated here.

### 14.5 Further promotion: `time.now_ns()` (follow-up session)

Backlog rank #1 (of the ranked list remaining after §14.4), selected on its
own rather than bundled into that earlier pass:

- `time.now_ns()` in operand position now renders as the fixed phrase "the
  current time", via the same `Operand.BuiltinCall(name, args)` model
  variant, promoted unconditionally on zero arguments rather than on an
  argument resolving cleanly (it has none to resolve). `Operand`'s KDoc
  example in §5 above is updated accordingly.

Not exercised by the acceptance pack, so no golden or `docs/sample-output/`
content changed. Full rationale is in `docs/rego-coverage.md`, not
duplicated here.

### 14.6 Further promotion: `some k, v in obj` two-variable form (follow-up session)

Backlog rank #1 of the ranked list remaining after §14.5, selected on its
own:

- `Condition.SomeIn` gains a third field, `key: String? = null` -- `null`
  for the existing single-variable form, set for the two-variable one.
  `some k, v in c` desugars to `internal.member_3(k, v, c)` (confirmed via a
  real `opa parse` run), the natural three-argument sibling of the
  single-variable form's `internal.member_2(v, c)`. Both `k` and `v` bind
  as iteration variables against the same collection -- the pre-existing
  per-body symbol table (§14.4) is already fully general per variable name,
  so no new substitution mechanism was needed: a later bare or ref-chain-
  root use of either variable extends the collection's own breadcrumb with
  `[each k]`/`[each v]`, exactly like the single-variable form's `[each x]`
  already does.
- The diff canonicalizer aliases `key` before `variable`, matching their
  left-to-right introduction order in the real source. This closes a real
  gap, not just a rendering one: before this promotion, a bare later use of
  either `k` or `v` fell back to the generic `Operand.Variable` case,
  aliased purely by first-appearance order -- so testing the *key*
  (`k == "x"`) vs. the *value* (`v == "x"`) of the same collection hashed
  identically, silently reporting a genuine logic change as `UNCHANGED`.

Not exercised by the acceptance pack, so no golden or `docs/sample-output/`
content changed. Full rationale, including the empirical fixture proving
the hash-collision gap above, is in `docs/rego-coverage.md`, not duplicated
here.

### 14.7 Further promotion: `=` used as a pure comparison (follow-up session)

Backlog rank #1 of the ranked list remaining after §14.6, selected on its
own:

- `=` (opa's own operator name `eq`, distinct from `==`'s `equal`) promotes
  to `Condition.Comparison` when -- and only when -- both sides are
  positively confirmed to already resolve to a real, non-`Unrendered`
  operand through the same `mapOperand` path `==` already uses. Unlike
  `==`, which the language guarantees never introduces a binding, `=` can
  also destructure/bind a fresh variable (`[x, y] = [...]`); a fresh bare
  var or a composite literal containing one maps to `Operand.Unrendered`
  (never `Unsupported`), which this promotion treats as a positive signal a
  binding may be in play, and declines to promote rather than guess.
- ~~**Disclosed, not fixed, in the same pass**: implementing this promotion
  surfaced a pre-existing gap shared by `==`/`!=`/`>`/`>=`/`<`/`<=`:
  `Condition.Comparison` has no `negated` field, so `not x == y` (real,
  valid Rego -- confirmed via `opa parse`) silently renders the POSITIVE
  form.~~ **Fixed in a dedicated follow-up pass -- see §14.8.**

Not exercised by the acceptance pack, so no golden or `docs/sample-output/`
content changed. Full rationale, including the empirical fixture proving
`=` and `==` now hash identically for equivalent logic, is in
`docs/rego-coverage.md`, not duplicated here.

### 14.8 Fix: negated comparisons (follow-up session)

The correctness bug disclosed in §14.7 above, fixed on the operator's
explicit instruction:

- `Condition.Comparison` gains `negated: Boolean = false`. Both
  `buildComparisonLike`'s comparison-operator branch and the promoted `=`
  path (`eqAsPureComparisonOrNull`) now thread `expr.negated` through --
  the `=` path's earlier `!expr.negated` exclusion is removed, since
  negation is orthogonal to the binding-vs-comparison question that guard
  actually protects.
- `ExpressionRenderer` gains `NEGATED_COMPARISON_VERBS`, the literal
  negation of each positive verb (e.g. "is not greater than" for negated
  GT -- never a different operator's positive wording like "is at most"),
  consistent with the same undefined-propagation simplification already
  accepted for the positive form.
- `Canonicalizer` adds `negated` to `Comparison`'s canonical JSON. This
  closes a real diff-hash gap, not just a rendering one: before this fix,
  `x == y` and `not x == y` -- genuinely opposite logic -- hashed
  identically, since `negated` didn't exist in the canonical shape at all.
  Confirmed empirically with a new `CanonicalizerTest` fixture pair.
- Confirmed via a real `opa parse` run that all six comparison operators
  (not just `equal`) can carry `negated: true` -- this isn't an
  `equal`-specific quirk.

Not exercised by the acceptance pack, so no golden or `docs/sample-output/`
content changed. Full rationale is in `docs/rego-coverage.md`, not
duplicated here.
