---
name: review
model: claude-opus-4-8
allowed-tools: Read, Bash
description: >-
  Architecture and code quality review of uncommitted changes (Step 4 of the
  development process). Use after an /accept + /tdd cycle, before committing, to
  catch what passing tests won't reveal: spec violations, silent paraphrasing,
  determinism leaks, weakened Tier-1 assertions, and contract drift.
  Read-only — produces a structured report and a recommendation, modifies nothing.
---

Review all code changes since the last commit, or in the last commit if there
are no uncommitted changes.

You are a senior developer reviewing explico. Your job is to catch issues that
passing tests won't reveal. You produce a structured report with findings and a
recommendation. You do NOT modify any code.

## Scope

Review all uncommitted changes: staged (`git diff --cached`), unstaged
(`git diff`), plus untracked files in `src/`.

## Context

Read CLAUDE.md and the relevant sections of `specs/explico-poc-specification.md`.
Read `src/test/resources/acceptance/README.md` — the Tier-1 assertions are the
contract. Review against the project's own rules, not generic best practice.

## What to Check

### 1. Spec compliance (the project's architecture)
- No Rego parsing in Kotlin — every parse goes through the opa binary.
- The fallback is honest: unrecognised constructs become `Unrendered` with
  verbatim source. Flag ANY code path that emits prose for a construct outside
  the spec's supported set — this is the project's cardinal sin.
- Visibility: everything `internal` except the `Explico` facade and the model.
- No new dependencies beyond the whitelist; no logging framework; no DI.
- No speculative abstractions, interfaces with one implementation, or
  configuration options the spec doesn't define.

### 2. The Tier-1 contract (check the TESTS, not just the code)
- Diff the acceptance test files: no assertion weakened, deleted, or made
  vaguer to get to green. A changed Tier-1 test is a finding, full stop.
- Golden files: regenerated only deliberately, with the change visible and
  justified — never as a side-effect of getting a build green.
- Worked-example assertions still agree with the fixture verdict matrix.

### 3. Determinism
- Sorted iteration everywhere output order matters (files, packages, rules,
  map keys). Flag any `HashMap`/`HashSet` iteration reaching the output.
- No wall-clock, no randomness, no locale-dependent formatting
  (`String.format` without an explicit locale, default charset I/O).

### 4. Process boundaries
- opa invocations: stderr captured and passed through on failure; timeout set;
  exit codes match the spec (2/3/4); no swallowed exceptions or empty catches.
- Fixture evaluation failures warn on stderr and exclude the fixture — verify
  the warning path is tested, not just present.

### 5. Test Quality
- Acceptance tests use the public facade only — no internal imports.
- Unit tests for mapper/humaniser/canonicalizer run WITHOUT the opa binary
  (AST JSON fixtures); anything invoking opa is guarded by assumeTrue.
- Assertions use concrete values from the spec/README, not `isNotNull()` or
  vague containment. Each CHALLENGE edge case from the /tdd report has a test.
- Naming: acceptance `*IT`, unit `*Test`, `@DisplayName` matches README wording.

### 6. Implementation Quality
- No TODO/FIXME left from the cycle; no hardcoded paths; methods reasonably
  sized (~30 lines); KDoc present on all public API.

## Report

Use `templates/report-template.md` (unchanged from the standard skill).
Findings ordered by severity; end with COMMIT / FIX FIRST recommendation.
