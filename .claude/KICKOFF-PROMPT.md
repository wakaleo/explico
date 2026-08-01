# Claude Code kickoff prompt — explico, session 1

Paste the following as the first message (adjust paths to match your repo):

---

Read `specs/explico-poc-specification.md` IN FULL before doing anything — it is
the authoritative specification for this project, and every design decision in
it is deliberate. Then read `specs/explico-acceptance-pack/README.md`: it
contains the decided-ahead Tier-1 acceptance assertions and a fixture verdict
matrix that was produced by real `opa eval` runs — both are frozen contracts,
not suggestions.

This session's goal is spec §11 milestone 1 plus the acceptance safety net for
the first output document. In order:

1. **Scaffold** the Gradle project exactly per spec §2 and §3: Kotlin, JVM 21
   toolchain, Kotlin DSL, single module, and ONLY the dependencies listed in
   §2 — adding any other dependency requires stopping and asking me.

2. **Write CLAUDE.md** capturing the project's non-negotiables so they survive
   context loss: the true-by-construction principle; never parse Rego in Kotlin
   (all parsing via the opa binary, §4); the fallback rule (unrecognised
   constructs render as marked source, NEVER as guessed prose); determinism
   (byte-identical output, sorted iteration, no wall-clock); the closed
   dependency whitelist; the test tiers from §9; and the rule that Tier-1
   acceptance assertions are frozen and must never be weakened to get to green.
   Also record the /accept → /tdd → /review → /commit-summary cycle and that
   the unit of /accept work is one output document.

3. **Relocate the acceptance pack** to `src/test/resources/acceptance/` and
   wire `samples/` per §9.

4. **Stub the facade**: `Explico.load/loadExamples/render` compiling and
   returning empty results, so acceptance tests can fail on assertions rather
   than compilation.

5. Run **/accept for `release-approvals.md`** — the Tier-1 tests for REL-001
   only, transcribed verbatim from the pack README. Confirm they fail for the
   right reason (assertion failure on empty markdown, not an exception).

6. **Milestone 1 proper**: `OpaRunner` (discovery via OPA_BIN then PATH,
   version check requiring 1.x, 30s timeout, stderr capture per §4) and the
   `opa parse` / `opa inspect` DTOs with `ignoreUnknownKeys`. Before writing
   the DTOs, run `opa parse --format json` on
   `src/test/resources/acceptance/policies/approvals/change_approval.rego`,
   save the output to `src/test/resources/ast/change_approval.json`, and shape
   the DTOs from what is ACTUALLY in that JSON, not from memory. Smoke-test
   parsing and inspecting the whole acceptance pack.

Checkpoints: `./gradlew check` green (unit + smoke passing, acceptance failing
via assumption or expected-failure marking as you prefer — but show me their
failure output once). Commit at the end of each numbered step with a
conventional message. If anything in the spec seems wrong, ambiguous, or
inconsistent with what opa actually produces, STOP and ask — do not improvise
around the spec.

---

## Subsequent sessions (for reference, not part of the prompt)

- Session 2: /tdd §5 AstMapper (from the captured AST JSON), then /tdd §6.3–6.4
  ExpressionRenderer + PathHumanizer — the spec's tables become parameterised
  tests. /review, commit.
- Session 3: /tdd §6.1–6.2 + §6.6 MarkdownRenderer + coverage → REL-001
  acceptance tests green → reconcile-once and freeze the Tier-2 golden.
- Session 4: /accept evidence, provenance, exemptions docs → /tdd metadata,
  anchors, cross-references (§6.5).
- Session 5: /accept governance + index → /tdd fallback rendering + §6.7
  worked examples.
- Session 6+: diff (§7), CLI (§8), README/ARCHITECTURE, publish config.
