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
3. **Tier-2 golden tests** — byte-exact, approval-style.
   `expected/release-approvals.md` is the ahead-of-time proposal; cosmetic
   details may be reconciled **once**, at first successful render, then it is
   frozen. Remaining packages and the diff report get goldens generated from
   the approved renderer. `-Dexplico.updateGolden=true` regenerates goldens —
   regeneration is a deliberate, reviewed act, never a side effect of getting
   a build green.

`./gradlew check` runs unit tests only (`*Test`, excludes `*IT`) and must stay
green throughout — it is the fast, opa-independent build gate.
`./gradlew acceptanceTest` runs the Tier-1 `*IT` classes; they are allowed to
be red while the renderer is being built, but must never be excluded from CI
long-term. Wire `acceptanceTest` back into the default build once the
renderer they pin exists and passes.

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

- **`RuleGroup.metadata` and `.default` are always null.** Metadata attachment
  needs `opa inspect` + file/row-proximity matching (§5), and no policy in the
  acceptance pack declares a `default` rule — both are deferred to their own
  later `/tdd` pass rather than being built untested.
- **No `Operand` variant exists for an operand-position builtin call**
  (`count`, `lower`, `upper`, `object.get`, `time.now_ns`). Spec's `Operand`
  sealed interface only has `Path`/`Literal`/`Variable`/`Unrendered` — there's
  nowhere to put "the number of `X`". AstMapper maps these to
  `Operand.Unrendered` unconditionally for now. Revisit when
  `ExpressionRenderer` is built: either add a variant, or confirm the
  spec intends these to only ever appear as fallback anyway.
- **`producesValue`'s placeholder humanisation is minimal, not `PathHumanizer`.**
  It only handles a plain `input.`-rooted field chain (drop `input`, split
  camelCase/snake_case/kebab-case, lowercase, join with spaces, wrap in
  brackets) — approved for this session specifically to fulfil §5's own
  mapping notes without building §6.4's breadcrumb renderer early. A
  var-rooted, key-literal, or any-index sprintf argument (e.g. REL-002's
  `stage.name`, REL-004's `window.name`) makes `producesValue` null rather
  than guessing a format spec never gave an example for.
- **The general "assign a local var to a plain path, substitute inline later"
  rule (§5) isn't implemented.** No rule in the acceptance pack exercises it
  (the only assignments present are the message-producing `msg := ...`, which
  IS handled). Don't assume it works until it has a driving test.
- **`PathSegment.AnyIndex` (`[_]`) has minimal, untested-by-fixture handling.**
  No pack policy uses `[_]`; the code path exists per spec but has no real
  captured JSON exercising it.
