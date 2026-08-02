# CLAUDE.md — explico

Authoritative spec: `specs/explico-poc-specification.md`. Frozen acceptance
contract: `src/test/resources/acceptance/README.md` (Tier-1 assertions +
fixture verdict matrix, produced by real `opa eval` runs against OPA 1.19).
Read both before making design decisions — this file is a summary of their
non-negotiables, not a replacement for them.

## The one sentence that matters

**Every statement in the output is true by construction.** explico renders
only what it can derive mechanically from the Rego AST and its METADATA
annotations. It never paraphrases, infers intent, or guesses. Anything it
cannot render faithfully is shown as clearly marked, verbatim source — never
prose standing in for logic the tool didn't actually check.

## Hard rules (never violate, never negotiate away to get to green)

1. **No Rego parsing in Kotlin, ever.** All parsing goes through the `opa`
   binary. **The exact invocation is `opa parse --format json --json-include
   locations <file>`** — the `--json-include locations` flag is required and
   is NOT in the original spec text; without it, `opa` silently omits every
   `location` field (confirmed empirically against real `opa` output), which
   breaks `SourceRef`, fallback source recovery, source-order sorting, and
   diff's source-span extraction. `opa inspect --annotations --format json`
   needs no such flag — it includes locations by default. A node's
   `location.text` is base64-encoded verbatim source for that node; the
   mapper decodes it directly rather than re-reading and slicing the file by
   row/col. `opa eval --format json` is the third invocation (worked
   examples, §6.7). Kotlin code only maps `opa`'s JSON output into the domain
   model (`parse/AstMapper.kt`). See spec §4.
2. **The fallback is sacred.** Any construct the mapper cannot classify —
   comprehensions, `every`, user-defined functions with parameters, `with`,
   `else` chains, anything unclassified — becomes `Condition.Unrendered` /
   `Operand.Unrendered` with the verbatim source (sliced from the file via the
   AST's row/col span) and a machine-readable `reason`. NEVER render a guess.
   When in doubt, fall back. This is the project's cardinal sin to violate.
3. **Determinism.** Identical input directories → byte-identical output.
   Sort everything that could vary: file iteration, map keys, rule order
   (by source position), fixture order (filename). No wall-clock, no
   randomness, no locale-dependent formatting, no `HashMap`/`HashSet`
   iteration reaching rendered output.
4. **Closed dependency whitelist.** Exactly: `kotlinx-serialization-json`,
   `clikt`, plus JUnit 5 + AssertJ for tests (and the Kotlin serialization
   Gradle plugin that makes the first of those work). No DI framework, no
   logging framework (`System.err` only), nothing else. Adding any dependency
   not on this list means **stop and ask the operator** — do not improvise.
   This governs the *library's own* runtime/test dependencies (what ships
   inside the published jar or its POM) — it does not cover build-only
   Gradle plugins with no runtime footprint. `com.vanniktech.maven.publish`
   and `org.gradle.toolchains.foojay-resolver-convention` (session 7, both
   explicitly requested/approved) are build tooling, confirmed absent from
   the published POM's own `<dependencies>` (§10.1).
5. **Visibility.** `internal` everywhere except the `Explico` facade
   (`Explico.kt`) and the domain model (`model/Model.kt`) — those are the
   public API surface, KDoc'd on every public declaration.
6. **No speculative design.** No plugin systems, no configuration files, no
   interfaces with a single implementation, no abstractions the spec doesn't
   call for. Data classes + top-level/object functions; the sealed
   `Condition`/`Operand` hierarchy is the one deliberate exception (§5).

## Test tiers (spec §9)

1. **Unit tests** — no `opa` needed. `PathHumanizer`, `ExpressionRenderer`,
   the line differ (`LineDifferTest`, pure LCS algorithm), `AstMapper` driven
   from checked-in `opa parse` JSON captures in `src/test/resources/ast/*.json`.
   **Deviation (session 6):** `Canonicalizer`, `PolicyDiff`, and `DiffRenderer`
   are instead driven by real `.rego` variants parsed through the actual `opa`
   binary at test time (`CanonicalizerTest`/`PolicyDiffTest`/`DiffRendererTest`,
   guarded by `assumeTrue(OpaRunner.isAvailable())`, same pattern as the
   pre-existing `OpaRunnerSmokeTest`) — an explicit operator instruction,
   overriding this list's original "no opa needed" framing for these three,
   because hash-stability and category-classification invariants are only
   trustworthy against real parser output, not a hand-built domain model that
   might not match what `opa` actually produces. Still named `*Test` (not
   `*IT`) since they're not Tier-1/Tier-2 acceptance-pack transcriptions; they
   just happen to need `opa`. `./gradlew check` stays green without `opa`
   installed because `assumeTrue` skips (not fails) them.
2. **Tier-1 acceptance tests** (`*IT`, guarded by
   `Assumptions.assumeTrue(OpaRunner.isAvailable())`) — JUnit transcriptions,
   verbatim, of the "Tier 1 assertions" tables in the acceptance pack README.
   Contains/count assertions on generated markdown, insensitive to whitespace
   and ordering. **These are authored before the corresponding renderer code
   exists and must never be weakened, reinterpreted, or deleted to make an
   implementation pass.** If a Tier-1 assertion looks wrong: stop, raise it,
   do not edit the test. The fixture verdict matrix (produced by `opa eval`,
   not by hand) is the oracle for worked-example assertions.
3. **Tier-2 golden tests** — byte-exact, approval-style, in the same `*IT`
   classes as Tier-1 (`AcceptancePackGoldenIT`). `expected/release-approvals.md`
   was the ahead-of-time proposal; reconciled **once** (session 5) — the only
   difference was a stray blank line from a YAML block-literal's trailing
   newline, fixed in `MarkdownRenderer` (cosmetic, confirmed byte-for-byte
   afterward), now frozen. The other five documents' goldens were generated
   from that approved renderer, not authored ahead of time.
   `-Dexplico.updateGolden=true` (wired into the `acceptanceTest` Gradle task)
   regenerates every golden instead of comparing — a deliberate, reviewed act,
   never a side effect of getting a build green.

`./gradlew check` runs unit tests only (`*Test`, excludes `*IT`) and must stay
green throughout — it is the fast, opa-independent build gate.
`./gradlew acceptanceTest` runs the Tier-1 `*IT` classes and the Tier-2 golden
`*IT` classes; **as of session 6 they are all green** (25 Tier-1 + 7 golden,
the 7th being `DiffAllCategoriesGoldenIT`). Kept as a separate Gradle task
rather than folded into `check`'s dependency graph, since `check` is meant to
stay usable without `opa` installed at all (the `assumeTrue` guard would just
skip them) — but CI must run both `check` and `acceptanceTest`, not `check`
alone, and must install `opa` first.

## The development cycle

`/accept` → `/tdd` → `/review` → `/commit-summary`, repeated per output
document.

- **`/accept`** — write the failing Tier-1 acceptance tests for the *next*
  output document, transcribed verbatim from the acceptance pack README. Does
  not write production code. **The unit of `/accept` work is one output
  document** (e.g. `release-approvals.md`), not one package, not the whole
  pack. Confirm the new tests fail for the right reason (assertion failure on
  stub/incomplete markdown, not an exception) before stopping.
- **`/tdd`** — drive the spec section behind that document to green with
  judgement-based TDD: test-by-test when uncertain, a batched parameterised
  test when transcribing a spec table, no ceremonial unit test when the code
  is trivial and already covered by acceptance tests. The CHALLENGE step
  (hunting edge cases) is always mandatory, never skipped.
- **`/review`** — read-only architecture/quality review of uncommitted
  changes against this file and the spec: fallback honesty, determinism,
  dependency whitelist, Tier-1 test integrity, visibility rules.
- **`/commit-summary`** — summarize the branch changes and propose a commit
  message before committing.

## Layout pointers

- `src/main/kotlin/io/explico/{opa,model,parse,render,diff}` + `Explico.kt` +
  `cli/Main.kt` — see spec §3 for the exact module layout.
- `src/test/resources/acceptance/` — the acceptance pack (policies, examples,
  data, expected goldens, and the frozen README).
- `src/test/resources/ast/` — checked-in `opa parse`/`opa inspect` JSON
  captures for unit tests that must not require the `opa` binary.
- `samples/` — mirrors the acceptance pack's policies/examples/data, referenced
  from the README as the worked CLI example.

## AstMapper: known gaps (deliberate, disclosed — not bugs)

`AstMapper` (built session 2, driven from `src/test/resources/ast/*.json`) does
NOT yet do the following. Each is a scoping decision, not an oversight:

- ~~`RuleGroup.metadata` and `.default` are always null~~ **Metadata resolved
  (session 4).** `AstMapper.mapPolicySet` now takes an optional
  `OpaInspectResult`; metadata attaches by matching `opa inspect`'s own
  `path` field (packagePath + ruleName), not row-proximity as §5's literal
  text describes — opa has already resolved which rule an annotation belongs
  to, including deduplicating a document-scoped annotation across a
  multi-body rule (confirmed: `release.approvals.deny` has 2 bodies but 1
  inspect entry). `package`-scoped annotations are skipped (no attachment
  point in the domain model; unexercised by the pack anyway). `.default`
  remains unimplemented — still no policy in the pack declares a `default`
  rule.
- **No `Operand` variant exists for an operand-position builtin call**
  (`count`, `lower`, `upper`, `object.get`, `time.now_ns`). Spec's `Operand`
  sealed interface only has `Path`/`Literal`/`Variable`/`Unrendered` — there's
  nowhere to put "the number of `X`". AstMapper maps these to
  `Operand.Unrendered` unconditionally for now. Revisit when
  `ExpressionRenderer` is built: either add a variant, or confirm the
  spec intends these to only ever appear as fallback anyway.
- **`producesValue`'s placeholder format is deliberately its own thing, not
  `PathHumanizer`'s breadcrumb style.** It reuses `PathHumanizer.wordsOf` for
  the word-splitting (drop `input`, split camelCase/snake_case/kebab-case,
  lowercase) but assembles a space-joined bracket phrase (`[deployment id]`),
  not a `▸`-joined breadcrumb — spec §5 only gives an example for a plain
  `input.`-rooted field chain, never for a var-rooted, key-literal, or
  any-index one. A var-rooted, key-literal, or any-index sprintf argument
  (e.g. REL-002's `stage.name`, REL-004's `window.name`) makes `producesValue`
  null rather than guessing a bracket format spec never demonstrated (see
  spec §5's session-2 amendment).
- **The general "assign a local var to a plain path, substitute inline later"
  rule (§5) isn't implemented.** No rule in the acceptance pack exercises it
  (the only assignments present are the message-producing `msg := ...`, which
  IS handled). Don't assume it works until it has a driving test.
- **`PathSegment.AnyIndex` (`[_]`) has minimal, untested-by-fixture handling.**
  No pack policy uses `[_]`; the code path exists per spec but has no real
  captured JSON exercising it.

## PathHumanizer / ExpressionRenderer: known gaps (session 3)

- ~~`PathHumanizer` can't distinguish a bound vs. unbound middle-position
  `VarIndex`~~ **Resolved (session 4).** `AstMapper.mapPathSegments` now
  takes the symbol table: a middle-position bracket-index variable
  (`arr[i]`) with no `some i in ...` binding promotes the *whole* operand to
  `Operand.Unrendered`, mirroring rule 7's already-established root-position
  handling — never a raw, meaningless `VarIndex`. `PathHumanizer` therefore
  still never needs to distinguish bound/unbound itself: every `VarIndex` it
  receives is bound by construction. Both directions have synthetic-JSON
  tests (`unboundMiddlePositionVarIndexBecomesOperandUnrendered`,
  `boundMiddlePositionVarIndexRendersLikeTheVarRootedCase`) since no pack
  policy uses bracket-index syntax at all.
- ~~`ExpressionRenderer`'s inline rendering for `Operand.Unrendered` is this
  session's own convention~~ **Settled (session 4), still not spec-sourced.**
  Backtick-wrapped verbatim source (`` `count(input.x)` ``) stays the
  convention: spec only shows the block format for `Condition`-level
  `Unrendered` (§6.2), never an `Operand.Unrendered` embedded inside an
  otherwise-normal phrase, and no pack policy exercises operand-level
  `Unrendered` at all (confirmed again this session — still only reachable
  via synthetic tests) so there's no new evidence to act on. Already tested
  (`unrenderedOperandRendersAsVerbatimSourceInline`). If a real card in a
  later session ever makes this look wrong once `MarkdownRenderer` exists,
  reconsider it then, with that real evidence in hand — not preemptively.
- ~~`regex.match`/`glob.match` rendering is untested against real captured
  JSON`~~ **Partially resolved (session 4).** Neither builtin appears in the
  acceptance pack, so there's still no *real* `opa parse` capture — but
  `AstMapperTest` now has synthetic-JSON tests
  (`regexMatchClassifiesAsBuiltinCallWithPatternAndValueInSourceOrder`,
  `globMatchClassifiesAsBuiltinCallIncludingTheIgnoredDelimiterArgument`)
  proving `AstMapper` correctly classifies both into `Condition.BuiltinCall`
  with the args in source order, grounded in the already-real,
  already-confirmed shape of dotted builtin names (a 2-element
  `[var, string]` ref chain, first verified via `internal.member_2`) rather
  than a guess about a new construct. `ExpressionRenderer`'s own
  `.first()`/`.last()` arg-picking for these two is still only tested against
  a hand-built `Condition.BuiltinCall` in `ExpressionRendererTest`, not one
  produced by `AstMapper` — the two test files weren't wired together, so
  don't overstate this as "tested end-to-end." Still not validated against
  real `opa` output either way — no pack policy uses either builtin.
- ~~`RuleReference`'s anchor is fully injected, not computed~~ **Resolved
  (session 4).** `MarkdownRenderer.resolveAnchor` implements spec §6.5:
  control-id if the target has one, else `package-rulename`, both slugged
  (lowercased, non-alphanumeric runs -> single `-`, trimmed) -- includes
  underscores in rule names, e.g. `is_release_candidate` ->
  `is-release-candidate`, a convention choice spec doesn't pin exactly.
  Same-package references get a bare `#anchor`; cross-package get
  `<package>.md#anchor`. `ExpressionRenderer` still only assembles the
  wrapper text around whatever `anchorFor` callback it's given -- the
  callback is now `MarkdownRenderer`'s real implementation, not a test stub.

## MarkdownRenderer: interpretation choices (session 4, confirmed by golden freeze session 5)

`release-approvals.md`'s golden only has ONE control card, so session 4 couldn't
confirm these against a real multi-card package. Session 5 generated Tier-2
goldens for the other five documents (all multi-rule or multi-body packages)
from this exact behavior and froze them — these choices are now the approved,
checked-in shape, not just "reasonable," though still not literally spec-pinned:

- **No "*(referenced rule)*" suffix after a `RuleReference` bullet.** Spec
  §6.2's worked example shows one (`...does not match *(referenced rule)*`),
  but §6.3's phrasing table and every Tier-1 assertion (including REL-002's
  cross-package reference) omit it. Treated as the worked example's own
  illustrative flourish, not a requirement — `ExpressionRenderer`'s existing,
  already-tested contract (27 tests) doesn't produce it, and adding it would
  mean splitting the responsibility for one bullet's text across two files.
- **`---` separates every card, including the last one before the package
  footer** (not just once at the very end). Confirmed against
  `release-evidence.md` and `release-governance.md`'s frozen goldens, both
  multi-card packages.
- **Combined coverage footer format**: `*Rendering coverage: X of Y
  conditions; contains N unrendered value(s)*` — spec gives the two pieces
  separately (§6.6) but never shows them combined in one footer line. Not
  exercised by any frozen golden either (no pack rule both falls short of
  100% coverage AND has an unrendered operand at the same time) — still
  genuinely unconfirmed.
- **`index.md`'s exact shape** (the `# Control index` heading, the table's
  column order, `—` for a missing control id, plus session 5's
  example-coverage column and corpus-gaps line) was this session's own
  design — spec describes the columns to include, not the literal format.
  Now confirmed by `index.md`'s frozen golden (session 5).

## §6.7 Worked examples (session 5)

- **`RuleBody.messageTemplate` added** (distinct from `producesValue`) —
  carries the raw `sprintf` format string (`%v`/`%s` untouched) or the
  literal string verbatim, used only for worked-examples body attribution.
  `producesValue` stays display-only and null whenever a placeholder can't
  be humanised (session 2/3, unchanged); `messageTemplate` stays populated
  regardless, since attribution only needs the template's literal/wildcard
  shape to match a real evaluated message, not a display rendering of the
  argument. This is what lets REL-004's var-rooted body 2 (`window.name`)
  still get a *(Situation 2)* label despite `producesValue = null` — the
  only way found to satisfy both the display rule and the acceptance
  README's own note that all 3 of REL-004's bodies are distinct enough to
  need labels.
- **`Explico.render()` signature changed** to
  `render(policySet, policyDir, examples, dataDir)` — added `policyDir`
  (approved by the operator; spec §8.1's literal signature omits it). Needed
  because `opa eval` must re-read the actual `.rego` files for worked
  examples, and the domain model only carries relative `sourceFiles` paths
  with no base directory to resolve them against.
- **A `SomeIn`'s own collection `Path`** (e.g. `pipeline ▸ stages`) is
  excluded from the worked-examples referenced-path listing — it resolves to
  a raw array of objects, not a scalar, and rendering it as a JSON dump
  looked wrong on the first real render. The per-element access via
  `VarIndex` (`pipeline ▸ stages ▸ [each stage] ▸ status`) is what's
  actually useful and stays included. Not spec-pinned; a quality fix made
  after seeing real output.
- **Verified against the full fixture verdict matrix, not just the Tier-1
  assertions.** Generated all 6 documents and manually diffed every
  worked-examples section (verdicts, messages, situation labels, referenced
  path values) against the acceptance README's matrix directly. Zero
  disagreements across all 6 fixtures × 5 packages, including the exemption
  path (fixture 05) and the multi-message/multi-situation case (fixture 06
  → REL-004 S1+S2 in one card entry).
- **Multi-message-per-fixture card format is this session's own choice**:
  the summary line's `*(Situation N)*` label is used only when exactly one
  message is produced; when a fixture matches more than one body at once,
  each message gets its own line with its own label instead. Not
  spec-pinned (no worked example shows this combination), but exercised by
  real data (fixture 06 on REL-004) and matches the fixture verdict matrix.
- **The "any other value type" verdict case (spec §6.7) is unexercised** —
  every pack rule is a set rule (`deny contains msg`) or a boolean/complete
  rule. `WorkedExamples`'s handling of a genuinely different value type
  (rendering it verbatim as a single "message") is a minimal, disclosed
  simplification with no real-data test behind it.

## §7 Diff (`diff/`, session 6)

The final POC feature. `Canonicalizer.kt`, `PolicyDiff.kt`, `LineDiffer.kt`,
`DiffRenderer.kt`; `Explico.diff(old, new): DiffReport` wires them together.
All driven by real `.rego` variants under `src/test/resources/diff/{canonicalizer,
policydiff,diffrenderer,diff-all-categories}/`, parsed through the actual `opa`
binary — see the Test tiers section above for why this deviates from the
spec's "no opa needed" framing for these three files specifically.

- **`RuleGroup.name` is deliberately excluded from the canonical logic hash** —
  caught by the rename test itself: including it made a pure control-id-
  preserving rename hash as `LOGIC_CHANGED` instead of `UNCHANGED`, which
  directly contradicts spec §7.1's own acceptance criterion. Recorded as a
  spec amendment (§7.1 step 2 now says so explicitly) rather than left as a
  silent implementation detail, since the spec's literal step list didn't
  mention it and a future reader could easily reintroduce the bug.
- **The canonical JSON is hand-built** (`buildJsonObject`/`buildJsonArray`,
  not `kotlinx.serialization`'s automatic derivation — the domain model has no
  `@Serializable` annotations). Every object's keys are inserted in a fixed
  alphabetical order per type, with an explicit `"type"` discriminator field
  for each `Condition`/`Operand`/`PathSegment` subtype — this satisfies spec
  §7.1's "serialise to JSON with sorted keys" without needing a runtime sort
  step, since the insertion order IS the sorted order by construction.
- **Body attribution's `messageTemplate` (spec §6.7) is included in the logic
  hash; `producesValue` is not.** A produced message's text is part of a
  rule's logic — changing "artifact was built from..." to different wording
  with identical conditions must be `LOGIC_CHANGED`, never `DOCS_CHANGED`
  (which is specifically about `RuleMetadata`) or `UNCHANGED`. Tested directly
  (`aProducedMessageChangeYieldsADifferentLogicHashEvenWithIdenticalConditions`).
- **Reformatting *inside* a `Condition.Unrendered`/`Operand.Unrendered` fallback
  span changes the hash — deliberate, disclosed, not a bug.** The tool can't
  verify two differently-formatted fallback spans mean the same thing, since
  by definition it didn't understand the construct; only a classified
  construct's formatting is invisible to the hash (because only its *parsed
  structure* is hashed, never its source text). Proven with a real fixture
  pair reformatting whitespace inside `count({a | some a in ...})` (REL-004's
  own comprehension-fallback shape) — the reformatted version's hash legitimately
  differs, confirming the caveat is real, not hypothetical.
  (`reformattingInsideAnUnrenderedFallbackSpanYieldsADifferentLogicHash`).
- **`RuleBody.sourceText` added to the domain model** (spec §5 amendment): the
  whole body's verbatim source, base64-decoded from the rule's own AST
  `location.text` — the same mechanism `Condition.Unrendered.sourceText`
  already used, just applied one level up (the whole rule, not one
  expression). Needed because §7.3's `LOGIC_CHANGED` unified diff needs the
  full old/new rule source, and the architecture never lets anything past
  `AstMapper` touch raw `opa` JSON again — so this has to live in the domain
  model, not be re-derived from a second file read or a raw-AST lookup inside
  `DiffRenderer`.
- **`DiffEntry`/`DiffCategory`/`DuplicateControlIdException` are `public`**,
  living in `diff/PolicyDiff.kt` rather than `model/Model.kt` or `Explico.kt`
  — same precedent as `Fixture`/`ExampleSet` (`render/Examples.kt`) and
  `CoverageSummary` (`render/Coverage.kt`): public because they're part of a
  public facade's return type (`Explico.diff`'s `DiffReport`), not because
  they belong in the two canonically-public files.
- **Identity resolution is a single map** keyed by `controlId ?: "$package.$name"`
  built independently for `old` and `new`; matching old/new by this one key is
  what makes a control-id-preserving rename fall out "for free" as the same
  identity, with no separate rename-detection pass needed. A rule with no
  control-id whose package or name changes is genuinely a different identity
  (correctly `REMOVED`+`ADDED`, not a detected rename) — the spec never asks
  for name-similarity heuristics, and adding one would be exactly the kind of
  guess rule 2 (fallback honesty) forbids in spirit.
- **`DiffRenderer`'s exact section format is this session's own choice**, spec
  §7.3 not being literally prescriptive beyond "one section per non-UNCHANGED
  control" and the four per-category content shapes:
  - A bold `**CATEGORY**` label line opens every section (not spec-pinned;
    needed because `ADDED`'s section would otherwise be visually identical to
    a plain render-page card, with no way to tell it apart from `LOGIC_CHANGED`
    minus its diff block at a glance).
  - `REMOVED`'s removal notice is the literal bold line `**⚠ This control has
    been removed.**` immediately before the reused old card.
  - `DOCS_CHANGED`'s two-column table renders a multi-line description with
    literal `<br>` in place of `\n` (Markdown table cells can't contain raw
    newlines); a missing title/description/frameworks list renders `—`.
  - The coverage warning counts *any* non-`UNCHANGED` entry whose displayed
    side (`newRule ?: oldRule`) has condition-level coverage below 100% —
    including `DOCS_CHANGED` entries, since the exact wording ("N changed
    controls") doesn't restrict itself to `LOGIC_CHANGED` only, and REL-004's
    own real coverage gap (7 of 8, from its `count({a | ...})` comprehension
    fallback) is exactly the case the `diff-all-categories` golden exercises.
  None of this is confirmed by more than one golden yet (only
  `diff-all-categories` exists) — reasonable-but-not-battle-tested, same
  status MarkdownRenderer's own choices had after session 4, before session
  5's multi-package goldens confirmed them.
- **`diff-all-categories` golden** (`src/test/resources/diff/diff-all-categories/`)
  uses real acceptance-pack policies, not synthetic ones: `old/` is an exact
  copy of all 5 pack policies; `new/` removes REL-001 entirely (`REMOVED`),
  adds a brand-new REL-005 control (`ADDED`), changes one condition in REL-003
  (`LOGIC_CHANGED`), changes only REL-004's title/description
  (`DOCS_CHANGED`), renames REL-002's rule `deny` → `deny_stage` with logic
  and package path otherwise untouched (`UNCHANGED` via the control-id-
  preserving rename), and leaves `exempt_service` byte-identical (`UNCHANGED`,
  plain case). All 5 categories plus the rename in one golden, exactly as
  instructed.

## §8.2 CLI (`cli/Main.kt`, session 7)

- **Exit codes are enforced by catching each library exception once, at the
  command level, and re-throwing Clikt's own `ProgramResult(code)`** — never
  letting an exception reach the JVM's default uncaught-handler, which would
  print a raw stack trace and violate "no stack trace" on `opa`
  missing/incompatible. `1` (usage error) needs no explicit code at all:
  Clikt's own `UsageError`/`CliktError` handling inside `.main()` already
  exits `1` for a missing required option or a `path(mustExist = true)`
  argument that doesn't exist — proven by a process-level test, not assumed.
- **`explico version` prints only explico's own version, unconditionally
  `0`.** Deliberately doesn't check or report `opa`'s status — that's a
  diagnostic command's job to report cleanly even when `opa` is broken, not
  fail like `render`/`diff` do. Spec's exit-3 rule ("opa binary
  missing/incompatible") reads as applying to commands that actually need
  `opa` to do their work, not this one.
- **The version string is substituted at build time**
  (`processResources`+`expand()` into `explico-version.properties`, no new
  dependency) rather than hardcoded in `Main.kt`, so it can never drift from
  `build.gradle.kts`'s own `version` — which Step 1 changes to a real
  released version. **Caught a real bug doing this:** `expand()`'s
  substitution value isn't tracked as a Gradle task input on its own, so
  `processResources` stayed UP-TO-DATE across a version bump and `explico
  version` kept reporting the *old* version until a manual `--rerun` —
  confirmed empirically (bump, rebuild, observe stale output; add
  `inputs.property("version", project.version)`; bump again, rebuild,
  observe correct output). Don't remove that `inputs.property` call.
- **`diff`'s stdout is the literal `## Summary` section sliced out of the
  same markdown written to `--out`**, not a second, separately-computed
  table — guarantees they can never disagree, at the cost of a small string
  search instead of recomputing counts from `DiffReport.entries`.
- **Process-level tests, not just unit tests of the command classes.**
  `CliProcessTest` spawns the actual CLI as a child `java` process (reusing
  the test JVM's own classpath via `java.class.path` — Gradle's `Test` task
  launches with `-cp`, so this is already complete) for every documented
  exit code (0/1/2/3/4), `--out` replacement, and both duplicate-detection
  paths (control-ids via `diff`, fixture names via `render --examples`). An
  in-process call to the command's `run()` couldn't prove the exit code
  itself reaches the OS process, nor that stderr is genuinely free of a JVM
  stack trace -- both are the actual spec requirement.

## §10.1 Maven Central publishing (session 7)

Full reasoning recorded in spec §10.1 (a deliberate spec amendment, not just
here, since it changes `group`/`version` and adds a new plugin -- the kind of
decision this project's convention always records in the spec itself). Key
points for day-to-day work in this repo:

- `group` is now `io.github.wakaleo`, `version` is `0.1.0` (no more `-POC`
  suffix). The Kotlin package namespace (`io.explico.*`) is untouched --
  these are independent things, don't conflate them.
- `com.vanniktech.maven.publish` (0.37.0) replaced the raw `maven-publish`
  plugin entirely; there is no more manual `publishing { publications {} }`
  block in `build.gradle.kts`, only `mavenPublishing { }`.
- `./gradlew publishToMavenLocal` works with **no credentials at all** --
  signing only activates if `signingInMemoryKey` is present as a Gradle
  property (i.e. `ORG_GRADLE_PROJECT_signingInMemoryKey` env var set). Don't
  "fix" this by making signing unconditional; that would break local/CI
  smoke testing for no safety benefit, since a real release path
  (`publishToMavenCentral` + CI's own secret-presence check) still can't
  silently skip signing.
- `consumer-smoke-test/` is a **standalone** Gradle build, intentionally not
  included in the root `settings.gradle.kts`. Run it via `./gradlew -p
  consumer-smoke-test run` from the repo root (reuses the root's wrapper to
  drive a different project directory) after a fresh `publishToMavenLocal`.
  It resolves the real published artifact from `mavenLocal()`, never a
  project dependency -- don't add an `include(...)` for it, that would
  defeat the entire point of the test.

## §10.2 CI/release workflows (`.github/workflows/`, session 7)

- **`ci.yml`** (push/PR to `main`): pins `opa` to exactly `1.19.0` via
  `open-policy-agent/setup-opa@v2` (verified as the current major-version tag
  against the action's real repo, not assumed), runs `check acceptanceTest`,
  then `publishToMavenLocal` + the consumer smoke test.
- **`release.yml`** (push of a `v*` tag): same verification, then a secret-
  presence check that fails the job immediately (before spending any CI time
  on tests) if `MAVEN_CENTRAL_USERNAME`/`MAVEN_CENTRAL_TOKEN`/`SIGNING_KEY`/
  `SIGNING_PASSWORD` aren't all set, then `publishToMavenCentral` with those
  four secrets mapped to the `ORG_GRADLE_PROJECT_mavenCentralUsername`/
  `mavenCentralPassword`/`signingInMemoryKey`/`signingInMemoryKeyPassword`
  env vars the plugin actually reads (§10.1). No `signingInMemoryKeyId` --
  confirmed via Gradle's own signing-plugin docs that it's only required for
  a GPG *subkey*, not a regular key, and the operator's instruction named
  exactly four secrets.
- **Every action reference was verified against the real repository's tags
  via `gh api`** (`actions/checkout@v7`, `actions/setup-java@v5`,
  `gradle/actions/setup-gradle@v6`, `open-policy-agent/setup-opa@v2`), not
  assumed from training-data memory of older conventions (e.g. `checkout@v4`
  is what most existing tutorials still show, but v7 is current).
- **Disclosed limitation: neither workflow has actually run in real GitHub
  Actions.** This repo has no configured git remote (confirmed via `git
  remote -v`), so there is nothing to push a tag or PR to yet. Verified
  instead: YAML syntax (`python3 -c "import yaml; yaml.safe_load(...)"` on
  both files) and that every action reference is a real, current tag on its
  real repository. The actual job logic (`check acceptanceTest`,
  `publishToMavenLocal`, the consumer smoke test, the secret-presence check)
  is the same sequence already proven locally in this session, command by
  command -- but "proven to parse and reference real actions" is not the
  same claim as "proven to run green in Actions." Confirm this once the repo
  has a real remote and a first real PR/tag push.

## §12 acceptance criteria audit (session 7)

All 8 checkboxes verified with real command+output evidence (not just
"tests pass") -- see the session transcript for the exact commands. Two
real bugs were found and fixed in the process, both by actually running
things rather than trusting existing test coverage:

- **`processResources`'s version substitution didn't invalidate on a
  version bump** (already covered in §8.2's CLI section above) -- caught
  by literally bumping the version and rebuilding, not by reasoning about
  Gradle's caching model in the abstract.
- **The README's Kotlin library-usage snippet was top-level script
  statements, not valid inside a `fun main()` in a real `.kt` file** --
  caught by extracting the exact snippet into a scratch consumer project
  and running it verbatim, which failed to compile until wrapped in
  `fun main() { }`. Also caught during the same pass: the snippet's
  `diff()` call used placeholder paths (`old-policies`/`new-policies`)
  that don't exist anywhere in the repo -- replaced with a real, runnable
  call (`Explico.diff(policySet, policySet)`, diffing `samples/` against
  itself).
- **A cold-start subagent walkthrough** (a fresh agent, no context from
  this session, told to follow only `README.md` in a clean copy of the
  repo) surfaced further real friction, all fixed:
  - `git clone <this repo>` was an unfillable placeholder (no remote
    configured, no URL given) -- replaced with acquisition-method-agnostic
    wording ("however you obtained the source...").
  - JDK 21 wasn't on the default `PATH`/`JAVA_HOME` and the README gave no
    install guidance -- fixed at the root instead of documenting around
    it: added the `org.gradle.toolchains.foojay-resolver-convention`
    plugin (`settings.gradle.kts`) so Gradle auto-downloads a JDK 21
    toolchain when none is found, verified by actually running a build
    under JDK 23 with no JDK 21 on the system `java_home` registry.
  - Every CLI invocation printed 4 lines of JNA/`--enable-native-access`
    JVM warnings before any real output (from Clikt's Mordant terminal
    detection) -- fixed by adding
    `applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")`
    to the `application { }` block, confirmed silent afterward via both
    `installDist` and `./gradlew run`.
  - The published artifact requires **Kotlin 2.3.0+** on the consumer
    side (an older Kotlin Gradle plugin hits an opaque "incompatible
    metadata version" error) -- undocumented; now called out explicitly
    in the README's library-install section.
  - The Maven Central dependency snippet read as immediately usable, with
    the "not actually published yet" caveat buried in a parenthetical
    below it -- reordered so the caveat leads.
- **Disclosed, not fixed: `opa fmt`, comment-edit, and variable-rename
  invariants (spec §12) now have a genuine `opa fmt`-produced fixture**
  (`src/test/resources/diff/canonicalizer/opa-fmt-applied/`, generated
  once via a real `opa fmt -w` run, not hand-edited) backing both
  `CanonicalizerTest` and `PolicyDiffTest` -- closing a gap where the
  existing "reformatted" fixture was hand-edited, not `opa`'s own output.
- **Disclosed, not fixed: the CI/release workflows themselves still
  haven't run in real GitHub Actions** (§10.2) -- this repo has no git
  remote yet. Everything checkable without one was checked.

## §13.1 Shadow-jar distribution (session 8)

- **`com.gradleup.shadow` 9.6.0**, not the unmaintained
  `com.github.johnrengelman.shadow` -- verified current via the plugin's
  own docs, same standard as every other plugin choice this project has
  made. Build-only tooling (§2's note on this), no runtime footprint on
  the published library jar.
- **`tasks.test { dependsOn(tasks.shadowJar) }`** -- deliberate: the shadow
  jar is a first-class deliverable now, and `ShadowJarProcessTest` runs it
  as a real subprocess, so it must exist before `test` runs. This means
  `./gradlew check` (and therefore CI, §10.2) always builds and exercises
  the shadow jar too, with no separate wiring needed in `ci.yml`.
- **Two real build issues found and fixed by actually running
  `./gradlew shadowJar` and reading its warnings, not by pre-emptively
  configuring things "to be safe":**
  - Several `META-INF/*.kotlin_module` files (one per Kotlin dependency
    jar) needed `duplicatesStrategy = DuplicatesStrategy.INCLUDE` for
    shadow's own `KotlinModuleMetadataTransformer` to see and merge all of
    them, per the plugin's own warning text.
  - Mordant (Clikt's terminal backend) ships 3 separate
    `META-INF/services/...TerminalInterfaceProvider` entries; needed
    `mergeServiceFiles()` so ServiceLoader-based platform-detection
    fallback keeps all 3 instead of shadow picking one arbitrarily.
- **`java -jar <shadow jar>` needed its own fix for the JNA/native-access
  warning** (§8.2 already silenced this for the installed-distribution
  path via `applicationDefaultJvmArgs`) -- that JVM arg only affects the
  generated start script, which a standalone `java -jar` launch never
  goes through. Fixed via the JAR manifest's `Enable-Native-Access:
  ALL-UNNAMED` attribute (a real, JDK-supported manifest key for exactly
  this "no command-line flag available" scenario, confirmed via JDK docs,
  not assumed).
- **A real, unrelated deprecation warning was found and fixed in the same
  pass**: `expand("version" to project.version)` inside `processResources`'s
  execution-time `filesMatching {}` closure triggered a "Task.project at
  execution time" deprecation (configuration-cache-incompatible, would
  break in a future Gradle). Fixed by capturing `project.version` into a
  `val` at configuration time instead. Unrelated to shadow itself --
  found only because `--warning-mode all` was run to double-check the
  shadow-jar build was clean, and it surfaced this too.

## §13.2 `explico demo` (session 8)

- **Embedded resources, driven by a build-generated manifest, not a
  classpath directory listing.** `generateDemoResources` (build.gradle.kts)
  copies `src/test/resources/acceptance/{policies,examples,data}` into
  `build/generated/demoResources/` and writes `demo-manifest.txt` next to
  them (a flat, sorted, newline-separated relative-path list), added as an
  extra `main` resources `srcDir`. `getResource("policies")` (a directory
  name) is unreliable inside a jar depending on whether its zip index has
  explicit directory entries; `getResourceAsStream` on each manifest-listed
  file path always works, jar or exploded classpath alike. Verified by
  actually inspecting the built jar's contents (`unzip -l`), not assumed.
- **`OpaRunner.binaryOverride`**: a mutable `var`, checked before `OPA_BIN`
  in `resolveBinary()`. Exists only because `--fetch-opa` needs a way to
  make a freshly-downloaded binary path take effect for the rest of the
  process -- an already-running JVM can't mutate its own `OPA_BIN`
  environment variable. Set exactly once, by `DemoCommand`, never by
  `render`/`diff`.
- **`OpaFetcher`** (`opa/OpaFetcher.kt`): `java.net.http.HttpClient` only,
  no new dependency. Platform → asset name mapping
  (`opa_<darwin|linux|windows>_<amd64|arm64>[.exe]`) and the checksum-file
  URL convention were both confirmed against the real `v1.19.0` GitHub
  release assets (`gh api repos/open-policy-agent/opa/releases/tags/v1.19.0`),
  not guessed. **Caught a real bug this way too**: `HttpClient.newHttpClient()`'s
  default redirect policy is `NEVER`, and GitHub's release-asset URLs 302 to
  a CDN -- the first real fetch attempt failed with a raw HTTP 302 until
  `.followRedirects(HttpClient.Redirect.NORMAL)` was added. windows/arm64 has
  no release asset at all; `assetNameForCurrentPlatform` throws
  `UnsupportedPlatformException` for it rather than guessing a substitute.
- **Verified for real, end-to-end, not just unit-tested**: a real
  `--fetch-opa` run this session downloaded the actual opa 1.19.0 binary
  from GitHub, verified its SHA-256 against the release's own checksum
  file, cached it, and used it to run a real demo (confirmed byte-identical
  output against the frozen `release-approvals.md` golden). This path is
  deliberately **not** in the automated test suite (`check`/`acceptanceTest`
  stay network-independent and fast) -- `OpaFetcherTest` covers the pure
  platform-mapping logic; `DemoCommandProcessTest` covers everything else
  (extraction, refuse-if-exists, the exit-3 message) without touching the
  network. This is a disclosed, deliberate gap, not an oversight.
- **Precondition order**: `explico-demo` already existing (exit 1) is
  checked before opa availability (exit 3) -- cheaper, unrelated to opa,
  and the more relevant error when both would otherwise apply. Consistent
  with `render`/`diff`'s existing "no partial side effects on failure"
  pattern: a failed `demo` run (either exit code) leaves nothing behind.

## §13.3 Documentation (session 8)

- **Mid-session addition, not in the original spec §13.3 text**: the
  operator asked for the README to open with the name-origin paragraph
  (now sourced verbatim in spirit from spec §1's own opening sentence) and
  a new "Relationship to OPA and Rego" section (drives the real `opa`
  binary rather than reimplementing Rego, standard METADATA only, OPA 1.x
  targeting, positioning vs. Regal and Konstraint, an independence
  disclaimer). Both Regal's and Konstraint's actual descriptions were
  verified via web search before writing the comparison -- Regal is a
  linter/language server (a different problem entirely from rendering),
  Konstraint's `doc` subcommand is the closest existing analogue but is a
  byproduct of its real job (Kubernetes Gatekeeper ConstraintTemplate
  generation, scoped to the `violation[]` convention) -- not assumed from
  memory, since misdescribing another real project in a public README
  would be a real (if minor) harm, not just an internal inaccuracy.
- **Every command and every piece of output in `docs/tutorial.md` is real**,
  generated by actually running `explico render`/`explico diff` against
  `samples/` (including a genuine `diff` walkthrough: copied `samples/policies`,
  edited one rule's title/description only, diffed the copy against the
  original, confirmed `DOCS_CHANGED` with the exact real table content) --
  the same standard the README's own worked example already held itself to
  (session 7's audit finding). Nothing in any of the three `docs/` files is
  hypothetical or hand-typed output.
- **`docs/user-guide.md` explicitly documents which exceptions a library
  consumer can actually catch by type**: only
  `io.explico.render.DuplicateFixtureNameException` and
  `io.explico.diff.DuplicateControlIdException` are public; everything else
  `load`/`render`/`diff` might throw is an `internal` exception type, not
  importable by name outside the module (spec §8.1's "everything else is
  internal" has this real, previously-undocumented consequence for
  consumers who want to catch specific failure types).
- **`docs/policy-authoring.md`'s METADATA section was substantially
  expanded post-hoc (later in session 8), on explicit operator request, to
  cover the standard-vs-`custom:` distinction, exactly which fields are
  read vs. ignored, `scope` semantics, degradation when absent, malformed-
  METADATA behavior, `control-id`'s diff-identity role, and METADATA's
  "human-attested, not mechanically verified" framing — all anchored to a
  field-by-field walkthrough of REL-001's real source and card.** Every
  factual claim about OPA's own parsing behavior was verified empirically
  against real `opa parse` output before being written, not assumed --
  including two genuinely surprising, previously-undocumented findings:
  (1) a truly unrecognised top-level METADATA key is silently dropped by
  `opa` itself, before explico's own JSON decoding ever sees it, while (2)
  an unrecognised key nested under `custom:` survives in `opa`'s own
  output (`custom` is unstructured to OPA) and is only silently dropped by
  explico's own deserialization -- two different tools doing the dropping,
  for two syntactically similar-looking cases. Also confirmed: `opa`
  itself defaults an unscoped rule-level annotation to `scope: rule`
  (matches the pre-existing, already-working `is_release_candidate`
  sample policy, which has never declared `scope:` explicitly). The old
  standalone "Control-id and frameworks conventions" section was removed
  as fully redundant once its content was folded into the new METADATA
  section -- not left as a second, potentially-drifting copy.

## §13.4 Cold-start demo test (session 8)

Same method as session 7's README cold-start test: a fresh subagent given
*only* the built shadow jar (renamed to `explico.jar`, matching the
README's literal command) and the exact "Quick demo" section text, with
explicit instructions not to look at anything else in this repository.
`opa` was already on the test machine's `PATH`, so `--fetch-opa` itself
wasn't exercised by the agent (already covered by manual verification in
§13.2's notes above). Everything the section *claimed* — the command, the
exact two-line output, the file existing with real content, the 91%/6-doc
figures — matched exactly on a real run. Three real omissions found and
fixed, all in the Quick demo section itself, none in the tool's behavior:

- No hint that `demo` writes `./explico-demo/` relative to the *current*
  directory — a first-time reader following the snippet from an arbitrary
  directory gets a surprise new folder wherever they happened to be. Now
  says "from an empty/scratch directory" up front.
- Re-running the exact same command in the same directory doesn't
  re-render — it refuses (exit `1`, spec §13.2's own deliberate
  behavior) with no README guidance on what that means or how to retry.
  Now documented (`rm -rf explico-demo` first).
- The two-line pointer names `release-approvals.md` specifically but never
  mentions `index.md` — arguably the better first stop (a table across all
  6 documents). Now mentioned alongside it.

## §13.5 Java-interop ergonomics (session 9)

- **`@JvmStatic` on every `Explico` member, `@JvmOverloads` on `render`
  only** (the one function with optional trailing parameters). Nothing
  else in the codebase gets these -- `OpaRunner` and every other
  `internal` type stay exactly as they were, since Java-ergonomics scope
  (spec §13.5) is specifically about the public facade a Java library
  consumer actually calls, not internal implementation types no external
  Java caller ever touches.
- **`src/test/java/io/explico/ExplicoJavaInteropTest.java`** is a real
  Java file, compiled by Gradle's already-present Java source set wiring
  (the `kotlin("jvm")` plugin sets this up automatically -- no build
  config change was needed for `compileTestJava` to pick it up). It
  caught the interop gotcha immediately: the test's own `OpaRunner`
  reference needed `.INSTANCE` (confirming `OpaRunner` correctly has
  *no* `@JvmStatic`, since it's internal and out of scope), while every
  `Explico.*` call needed no such qualifier and `render` needed no
  explicit `null`s -- proving the annotations actually work from Java,
  not just that they're present in the source.

## §13.6 Worked-examples provenance footer (session 9)

- **One line, exact wording**: `*Examples are evaluated against this
  policy version by OPA at generation time.*`, with a blank line on each
  side, placed after the fixture list and before the coverage footer in
  `appendWorkedExamples` (`MarkdownRenderer.kt`) -- absent entirely when
  there's no worked-examples section at all (no fixtures supplied), same
  as the section itself.
- **Golden regeneration was genuinely deliberate, not incidental**: ran
  `acceptanceTest` first to see exactly which 5 of the 6 documents broke
  (every one with a worked-examples section; `index.md` and the diff
  golden don't render worked examples at all -- confirmed `DiffRenderer`
  never passes `workedExamples` to `renderCard`, always the empty-list
  default), regenerated with `-Dexplico.updateGolden=true`, then reviewed
  the full diff before accepting it: exactly the 2-line footer inserted
  once per worked-examples section (7 total insertion points across the 5
  files), nothing else changed anywhere.
