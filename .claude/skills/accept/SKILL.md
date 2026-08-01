---
name: accept
model: claude-sonnet-5
allowed-tools: Read, Write, Edit, Bash
description: >-
  Write the failing Tier-1 acceptance tests for the NEXT explico output document
  (Step 2 of the development process). The assertions are already decided —
  they live in the acceptance pack README — so this skill transcribes them into
  JUnit, it does not invent them. One output document at a time. The tests must
  fail for the right reason.
argument-hint: "<output document, e.g. release-approvals.md> @src/test/resources/acceptance/README.md"
---

Write the failing acceptance tests for: $ARGUMENTS

Read CLAUDE.md for project conventions before writing anything.
Re-read the acceptance pack README (`src/test/resources/acceptance/README.md`),
specifically the "Tier 1 assertions" section for this document and the
"Fixture verdict matrix". These are the contract. They were verified against a
real `opa eval` run before this project existed.

## Structure

One outer class per rendered output document, named `<Document>AcceptanceIT`
(e.g. `ReleaseApprovalsAcceptanceIT`).
One `@Nested` inner class per control on that page — name it after the control.
One `@Test` per bullet in the README's assertion list for that control.

Use `@DisplayName` with the README's exact wording:
- Class: the control heading (e.g. "REL-001 — Production change approval")
- Method: the assertion text, abbreviated only where necessary

## How to test

Test through the public facade ONLY: `Explico.load(...)`,
`Explico.loadExamples(...)`, `Explico.render(...)`. Read the generated markdown
from `RenderedDocs.files`. NEVER touch internal classes — this is an acceptance
test of the library's contract, not a unit test of its parts.

Guard every test class with `@BeforeAll` →
`Assumptions.assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")`.

Assertions are CONTAINS and COUNT assertions, insensitive to whitespace and
ordering, exactly as the README specifies:
- phrase present: `assertThat(markdown).contains("`deployment ▸ environment` is `\"production\"`")`
- structure count: count occurrences of `### Situation` and assert the number
- absence: `assertThat(markdown).doesNotContain("⚠")` where the README says
  "no fallback blocks"
Worked-example assertions must agree with the fixture verdict matrix — verdicts,
messages, and *(Situation N)* labels come from that table verbatim.

## What NOT to do

Do NOT write production code. The tests MUST FAIL against the current facade.
Do NOT weaken, reinterpret, or "improve" a Tier-1 assertion to make it easier to
pass — the README is frozen; if an assertion seems wrong, STOP and raise it.
Do NOT invent assertions beyond the README for this document.
Do NOT write tests for more than one output document.
Do NOT pin whitespace, blank lines, or ordering — that is Tier 2's job.

## When you're done

Run the tests. Confirm they fail for the RIGHT reason:
- Assertion failure on empty/stub markdown → good
- Compilation error against a missing facade method → acceptable on the first
  document only; add the stub to the facade (returning empty RenderedDocs) so
  subsequent runs fail on assertions
- Error/exception (NPE, opa invocation crash) → wrong reason, investigate
- Test passes → something is wrong, investigate

Report: which document, how many controls and assertions, and the failure reason.

STOP. Do not proceed to implementation.
