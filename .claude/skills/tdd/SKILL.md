---
name: tdd
model: claude-sonnet-5
allowed-tools: Read, Write, Edit, Bash
description: >-
  Drive an explico spec section to green with judgement-based TDD (Step 3 of the
  development process). Test-first is the PREFERRED approach, but you decide the
  granularity: one test at a time when the design is uncertain, a batch of
  related tests to frame a fine-grained requirement, or no low-level unit test
  when the code is trivial and already covered by the acceptance tests. Never
  ship untested code. The CHALLENGE step — hunting and testing edge cases — is
  always mandatory.
argument-hint: "<the spec section / behaviour to drive to green, e.g. §6.4 path humanisation>"
---

Drive to green: $ARGUMENTS

Read CLAUDE.md for architecture and testing conventions before writing any code.
The behaviour is pinned by failing Tier-1 acceptance tests (from /accept) and by
the spec (`specs/explico-poc-specification.md`) — re-read the relevant spec
section before starting. Your job is to make the tests pass with well-tested
production code, test-driving the underlying code with judgement.

## APPROACH — test-drive with judgement (test-first is the default)

TDD is the preferred way to build. Choose the granularity that fits the
uncertainty — with one hard rule: **never write untested code.**

- **Uncertain about the solution?** (AstMapper classification, body attribution)
  Work test-by-test — one small failing unit test, minimum code to pass, repeat.
- **Pinning down a fine-grained requirement?** (the §6.3 phrasing table, the
  §6.4 humanisation rules) Write the RELATED unit tests as a single batch FIRST —
  the spec's tables convert almost row-for-row into a parameterised test — then
  write the code that makes the batch pass together.
- **Trivial code already covered by the acceptance tests?** (markdown string
  concatenation, file writing) Skip the ceremonial unit test.

Unit tests must not require the opa binary: drive AstMapper and friends from
checked-in `opa parse` JSON captures in `src/test/resources/ast/`. Only
acceptance and smoke tests may invoke opa (guarded by assumeTrue).

## GREEN — minimum code, right boundaries

Write the minimum production code to satisfy the tests you are driving — no
speculative methods, no anticipating later milestones, no abstractions until
refactoring demands them. Respect the architecture:

- NEVER parse Rego in Kotlin — all parsing goes through the opa binary.
- The fallback is sacred: an unrecognised construct becomes `Unrendered` with
  verbatim source, NEVER a guessed prose rendering. When in doubt, fall back.
- Everything is `internal` except the `Explico` facade and the domain model.
- Data classes and functions; the sealed `Condition`/`Operand` hierarchy is the
  only inheritance in the codebase.
- Dependency whitelist is closed: kotlinx-serialization, clikt, JUnit, AssertJ.
  Adding a dependency requires stopping and asking the operator.

## REFACTOR — clean up with confidence

Tests green → remove duplication, extract clear names, simplify conditionals,
make the code read like the spec (§ numbers in KDoc where helpful). Run ALL
tests after refactoring; if anything breaks, fix the PRODUCTION code (never the
test) before moving on.

## CHALLENGE — ALWAYS mandatory: hunt the edge cases

Never skipped, whatever granularity you chose. For the behaviour you just built,
ask: "What input could break it? Where are the boundaries?" The recurring edge
cases in THIS codebase:

- **Humaniser boundaries** — single-letter fields, digits in names (`sha256`),
  consecutive capitals (`URLPath`), mixed `camelCase_snake-kebab`, quoted keys
  containing spaces or unicode, multiple `[_]` in one path (one prefix only),
  a var-rooted path whose variable has NO `some` binding (→ Unrendered).
- **Structural emptiness** — empty policy directory, package with zero rules,
  rule with only a `default`, body with a single condition (no Situation
  heading), fixture input missing every referenced path (`absent` everywhere).
- **Determinism** — byte-identical output across runs: sorted file iteration,
  sorted map keys, no iteration over HashMap, no wall-clock anywhere.
- **Canonical hash stability** — variable rename → SAME hash; `opa fmt` /
  comment edits → SAME hash; operand or operator change → DIFFERENT hash;
  metadata-only edit → same logic hash, different metadata hash.
- **Process boundaries** — opa syntax error passes stderr through (exit 2),
  missing binary (exit 3), duplicate control-ids (exit 4), a fixture that fails
  eval produces a stderr warning and is EXCLUDED, never silently included.

Drive each genuine edge case as its own failing test first, then make it pass.

## STOP

When the behaviour is fully covered — its acceptance tests green AND its
boundaries tested — report: which spec section now passes, what production code
you wrote or changed, what you refactored, and which edge cases you drove.
Never modify an existing test to make it pass; Tier-1 assertions are frozen.
Continue to the next section (or wait for the operator at a milestone boundary).
