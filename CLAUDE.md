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
5. **Visibility.** `internal` everywhere except the `Explico` facade
   (`Explico.kt`) and the domain model (`model/Model.kt`) — those are the
   public API surface, KDoc'd on every public declaration.
6. **No speculative design.** No plugin systems, no configuration files, no
   interfaces with a single implementation, no abstractions the spec doesn't
   call for. Data classes + top-level/object functions; the sealed
   `Condition`/`Operand` hierarchy is the one deliberate exception (§5).

## Test tiers (spec §9)

1. **Unit tests** — no `opa` needed. `PathHumanizer`, `ExpressionRenderer`,
   `Canonicalizer` (hash stability), the line differ, `AstMapper` driven from
   checked-in `opa parse` JSON captures in `src/test/resources/ast/*.json`.
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
`*IT` class; **as of session 5 they are all green** (25 Tier-1 + 6 golden).
Kept as a separate Gradle task rather than folded into `check`'s dependency
graph, since `check` is meant to stay usable without `opa` installed at all
(the `assumeTrue` guard would just skip them) — but CI must run both `check`
and `acceptanceTest`, not `check` alone, and must install `opa` first.

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
