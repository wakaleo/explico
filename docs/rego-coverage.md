# Rego language coverage audit

A systematic audit (spec §14) of every rendering decision `AstMapper` /
`ExpressionRenderer` / `PathHumanizer` make, checked against the real Rego
language (not just the acceptance pack's own 5 policies) via a 40-file probe
corpus under [`src/test/resources/probes/`](../src/test/resources/probes/),
each run through the real `opa parse` → `AstMapper` → `ExpressionRenderer`
pipeline, and pinned permanently in
[`RegoCoverageAuditTest`](../src/test/kotlin/io/explico/audit/RegoCoverageAuditTest.kt).
Ground truth for the construct/builtin inventory came from `opa capabilities
--current` (opa 1.19.0, 206 builtins) and the official Rego language-reference
docs, cross-referenced against spec §6.3/§6.4 and the mapper's actual source.

## The design principle behind the fallback bucket

explico renders a construct only as deep as it can classify it *exactly*:
**leaf conditions are rendered, a reference to another rule is followed one
level deep (as a link, never inlined), and anything requiring deeper
structural understanding — comprehensions, `every`, arbitrary function
bodies, `else`-chains, `with`-overrides — is deliberately shown as clearly
marked, verbatim source instead.** This is not a shortcoming to work around;
it is the mechanism that makes the one-sentence guarantee ("every statement
in the output is true by construction") possible at all. A construct landing
in the fallback bucket is a *success* of that design, not a gap — the
audit's job was to find any construct that landed in the wrong bucket, most
importantly the one genuinely unacceptable case: **rendered into a template
whose English doesn't match the construct's exact semantics.**

Three such misclassifications, plus one outright crash, were found and fixed
this session (§"Fixed this session" below) — all four confirmed by actually
running the construct through the real pipeline, not by reading the code and
guessing.

## Coverage table

### Rendered faithfully

| Construct | Rendered as | Rationale |
|---|---|---|
| `Comparison` (`==`/`!=`/`>`/`>=`/`<`/`<=`) | `<l> is/is not/is greater than/... <r>` | Spec §6.3's frozen template; exercised by the pack and probes 08/13/14/16/22/23/25/26/31/34/35/36. Doesn't attempt to describe undefined-propagation (if either side is absent the comparison is undefined, not false) — an already-accepted simplification for `==` (REL-001) extended consistently to the other operators, not a new deviation. |
| `Membership` (`in` / `not in`) | `<m> is/is not one of <c>` | Identical wording for set and array collections (probe 24) — correct, since "is one of" doesn't claim anything about ordering or uniqueness that would differ between the two. |
| `Truthy`, negated (`not input.x`) | `<p> is absent or false` | Type-agnostic and exactly matches Rego's real `not` semantics (succeeds iff undefined or exactly `false`), regardless of the field's actual type. |
| `Truthy`, non-negated (bare `input.x`) | `<p> is present and not false` | **Fixed this session** — see below. |
| `SomeIn` (`some x in y`) | `for some x in <y>` | Exercised by the pack and probe 08 (multiple independent `some...in` in one body both render correctly). |
| `RuleReference`, complete/boolean rule (negated or not, same- or cross-package) | `see rule X` / `rule X does not match` | Exercised by the pack (REL-002/REL-004) and probe 21 (with an explicit `default`) — "does not match" is accurate because a complete rule's undefined and explicit-`false` states are both real "doesn't match" outcomes. |
| `BuiltinCall`: `startswith` | `<a> starts with <b>` | Exercised by the pack (REL-003). |
| `BuiltinCall`: `regex.match` | `<v> matches pattern <p>` | Probe 33 — closes a previously-disclosed gap (CLAUDE.md session 4: "untested against real opa output"); now confirmed correct against real parse output. |
| `BuiltinCall`: `glob.match` | `<v> matches glob <p>` | Probe 32 — same gap, same confirmation. |
| Function overloading (multiple bodies, same name, discriminated by arg pattern) | Each body as its own Situation | Probe 16 — needs **no** special-case handling at all: it's structurally identical to the pre-existing incremental-definitions mechanism (`RuleGroup.bodies`), confirmed by actually running it. |
| Partial object rule with a plain string value (`deny_severity[msg] := "high" if {...}`) | Normal card + `producesValue` | Probe 18 — conditions and the message both render exactly as a `deny contains msg` rule would. |

### Falls back by design (confirmed correct, not a gap)

| Construct | Reason category | Rationale |
|---|---|---|
| Comprehensions (array/set/object), bare or wrapped in `count(...)` | `comprehension` | Explicit non-goal (spec §1.2). Probes 09 (nested `every`), 29, 30; pack REL-004. |
| `every` (incl. nested `every` inside `every`) | `every` | Explicit non-goal. Probe 09 confirms nesting doesn't get partial credit — the whole outer block falls back as one unit, never guessing at the inner structure. |
| Operand-position builtins (`count`, `lower`, `upper`, `object.get`, `time.now_ns`, `concat`, and any user-defined function used as an operand) | n/a (`Operand.Unrendered`, condition itself still classifies) | No `Operand` variant exists yet for "the result of calling X" (documented gap, AstMapper file header). Probes 15/23/25/26/31/34/35/36 confirm this is safe: the verbatim call text is shown inline, never silently dropped or guessed. |
| `count(...)` bare in condition position | `function-call` | Spec's explicit rule: operand-position builtins never render as conditions. Probe 37. |
| `=` unification, both as binding (destructuring) and as pure comparison between two already-bound values | `function-call` | `=` desugars to operator name `eq`, not `equal` (which `==` produces) — confirmed via real `opa parse` output. `COMPARISON_OPERATORS` only maps `equal`, so `=` is uniformly unclassified today; never misrendered as a binding when it's actually a comparison or vice versa. Probes 04/05/31. |
| `null` / object literal as a comparison operand | `unclassified` | No `Operand` variant for either shape yet. Probes 01/02. |
| Local variable bound to a plain path, then reused (`env := input.x; env == "production"`) | `function-call` (assignment) + faithful-looking `Comparison` with a bare-var operand | The pre-existing, already-disclosed gap (CLAUDE.md, spec §5): the assignment renders as its own (slightly confusing) fallback bullet; the later comparison renders correctly as a `Comparison`, but with `` `env` `` shown as bare backticked text indistinguishable from a real path breadcrumb. Reviewed this session (see "Disclosed, unchanged" below) — not reclassified, but now backed by a permanent regression test (probes 03/06/07/11) instead of only a prose note. |
| Ref-head rule reference used as an operand (`fruit.apple.seeds`, `users_by_role.admin.u1.name`) | n/a (`Operand.Unrendered`) | Neither `input`/`data`-rooted nor a known local-variable binding, so `mapRefChain` correctly falls back to verbatim rather than guessing a breadcrumb for a rule it doesn't recognise as such. Probes 13/14. |
| `default` declarations (rule and function) | `unclassified` | No recognised body shape; RuleGroup's own `.default` field stays null (pre-existing, disclosed gap). **Quirk found this session**: opa's own AST gives a `default` rule's whole-rule `location.text` as just the literal word `default` — not the full `default allow := false` statement — so the fallback block, while honestly verbatim, is unhelpfully short. This is opa's own location-span behaviour, not an AstMapper defect. Probes 27/28. |
| Declare-only `some` (`some key`, `some i, j` — no `in`) | `unclassified` | Correctly falls back once the confirmed crash (below) was fixed. Probes 07/12. |
| `walk(input, [path, value])` | `unclassified` (binding) + `function-call` (the call) | Probe 11 — the binding and the call both fall back independently; a later comparison using the bound `value` still renders. |
| Composite-value membership (`[1, 2] in pairs`) | `function-call` (the `pairs :=` binding) | The membership check itself renders (`Membership`), with the untraced `pairs` variable shown as bare text — same pattern as the local-variable case above. Probe 31. |
| String interpolation (`$"Deployment {x} was rejected"`) | `function-call` | A genuinely new-to-this-audit Rego construct (desugars to a `templatestring` term type opa's parser produces, not documented in spec §6.3 at all). Safely, silently falls back for both the binding and the message — same graceful-omission behaviour as any other unsupported message shape, not a new regression. Probe 39. |
| Dynamic root-position ref (`input[key]`) where `key` is unbound | n/a (`Operand.Unrendered`) | Not a new rule — this is the *existing* "unbound middle/root-position `VarIndex` → whole operand falls back" rule (session 4) doing exactly its job against a genuinely new real-`opa`-output shape. Probe 12. |

### Fixed this session (previously ERROR or MISLEADING)

| Finding | Was | Now | Probe |
|---|---|---|---|
| **Crash** on declare-only single-variable `some key` (no `in`) | Uncaught `JsonDecodingException`, took down the *entire* render, not just the one condition | Falls back safely (`Condition.Unrendered`, reason `unclassified`) | 12 |
| **Silent data loss**: `else`-chains | The `else` branch is a sibling field on opa's own rule AST that `OpaRule` didn't model; `ignoreUnknownKeys` silently dropped it — the card showed only the `if` branch as if it were the rule's entire logic | `OpaRule.elseBranch` (presence-only) detected; the whole rule body demoted to one `Condition.Unrendered` (reason `else-chain`) whose source spans the *entire* chain — opa's own `rule.location.text` already covers every branch, confirmed empirically | 17 |
| **Silent, false-by-omission**: `with` overrides | Same root cause on the expr level (`OpaExpr` didn't model `with`) — a `some_rule with input.x as "y"` rendered as a plain, unqualified rule reference, actively implying the real input was being tested | `OpaExpr.with` (presence-only) detected; demoted to `Condition.Unrendered` (reason `with-override`) before any shape-dispatch, so it can never be misclassified | 10 |
| **Misleading**: negated *or* non-negated reference to a **partial** (`contains`/object) rule | Rendered as `Condition.RuleReference` ("rule X does not match" / "see rule X") exactly like a complete-rule reference — but a partial rule is *always* defined (even empty), so `not partialRule` can never succeed and a bare reference is unconditionally, tautologically true. Confirmed via real `opa eval`: the enclosing rule stayed undefined in both the empty and non-empty cases. | The rule registry now tracks which names are partial; `ruleReferenceIfKnown` refuses to classify either direction, falling back to `Condition.Unrendered` (reason `partial-rule-reference`) | 20, 40 |
| **Misleading**: `Truthy`, non-negated, "is true" wording | Spec §6.3's literal frozen template — accurate only when the field happens to be boolean; for any other type (the acceptance pack never exercised this bare-non-negated path — only ever via negation, over a boolean flag), "is true" is simply wrong (e.g. a non-empty string "author" field "being true" describes nothing real) | Changed to "is present and not false" — the exact type-agnostic mirror of the already-correct negated wording, recorded as a spec §14 amendment | 38 |

None of the five fixed constructs (`some key` declare-only, `else`, `with`,
partial-rule bare reference, non-negated `Truthy`) appear anywhere in the
existing acceptance pack, so nothing previously shipped was affected — but
any future policy using any of them would have been silently misrendered
before this session.

## Disclosed, unchanged conventions (reviewed this session, deliberately not changed)

**Operand-level fallback (`Operand.Unrendered`) renders as plain backticked
verbatim source — visually identical to a real humanized path.** An untraced
local variable (`` `env` ``), a ref-head rule reference (`` `fruit.apple.seeds` ``),
or an operand-position builtin call (`` `count(input.x)` ``) all look exactly
like a real breadcrumb (`` `deployment ▸ environment` ``) in the rendered
bullet — there is no inline marker, only the aggregate "contains N unrendered
value(s)" coverage-footer line. This session's probe corpus showed the
pattern is considerably more pervasive than the single narrow case (operand-
position builtins) session 3/4 originally reviewed it for. Reaffirmed as
**not** a MISLEADING finding: the underlying assertion in every affected
bullet stays true regardless (the comparison really does hold), only
legibility suffers, and widening the convention now — mid-audit, without new
evidence the *wording itself* is wrong — risks exactly the scope creep this
session was meant to avoid. Left unchanged; noted here for a future
increment to weigh deliberately, with its own review, not as a byproduct of
this one.

## Promotion backlog

Constructs currently in the fallback bucket that a future increment could
render faithfully, ranked by estimated real-world policy-authoring
frequency. **No promotion was implemented this session** — this is a ranked
list for selection, not a plan.

| Rank | Construct | Proposed template | Risk |
|---|---|---|---|
| 1 | `count(x)` in operand position, `x` a plain path (not a comprehension) | "the number of X" — spec §6.3 already specifies this exact wording | **Low.** Only needs a new `Operand` variant + `AstMapper` wiring; the design already exists and is approved. |
| 2 | `lower(x)` / `upper(x)` in operand position | "X lowercased" / "X uppercased" — also already spec'd | **Low.** Same reasoning as #1. |
| 3 | Local variable bound to a plain path, reused later (`env := input.x; env == "y"`) | Substitute the bound path inline wherever the variable is used; suppress the separate assignment bullet | **Medium.** The documented, long-standing gap (spec §5). Needs to apply *only* when the RHS is a plain path (not a builtin call, comprehension, or arithmetic expression) — mirrors the `SomeIn`-binding mechanism already in place, but getting the scope boundary wrong would reintroduce exactly the kind of untraced-variable confusion this audit flagged. |
| 4 | `object.get(o, k, d)` in operand position | "X ▸ K (default D)" — already spec'd | **Low-medium.** Needs a decision on rendering the key/default when *they* aren't simple literals. |
| 5 | `time.now_ns()` in operand position | "the current time" — already spec'd | **Low.** |
| 6 | `some k, v in obj` (two-variable form) | "for some k, v in X", with both `k` and `v` in scope for later var-rooted paths | **Medium.** Touches `PathHumanizer`'s `[each x]` convention, which has no two-variable form yet. |
| 7 | `=` used as a pure comparison (both sides already bound, no new binding introduced) | Treat identically to `==` | **Medium.** Must positively confirm *neither* side introduces an unbound variable before promoting — a destructuring `=` is assignment, not comparison, and conflating the two would be exactly the "widen a template to swallow a construct approximately" failure mode this audit exists to catch. |
| 8 | Arithmetic operands (`x + 1`, etc.) | A small infix expression renderer | **Medium-high.** More design surface than the others (multiple operators, precedence, humanizing the operand sub-tree). |
| 9 | `concat(sep, [...])` string-join operand | "X joined with Y" (no spec precedent) | **Medium.** New design, not just new wiring. |
| 10 | `null` literal operand | `Operand.Literal("null")` | **Low.** Trivial, just never added. |
| 11 | Small flat object literal operand | Same mechanism as the existing small-array-literal rendering | **Medium.** Needs a deterministic key-ordering and size-cap decision (mirroring the existing ≤5-element array rule). |
| 12 | Ref-head / partial-object rule references used as operands (`fruit.apple.seeds`) | Humanize as a breadcrumb once resolved against the rule registry | **Medium-high.** Needs to distinguish "known local rule reference" from a real input/data path, and to handle a partial-object's dynamic keys. |
| — | `else`-chains rendered as multiple "Situation N" entries | *Not recommended near-term* | **High — explicitly deferred.** An else-chain's branches are priority-ordered and mutually exclusive (first match wins), not a simple OR of situations the way multiple `deny` bodies are. Modeling this incorrectly would reintroduce a MISLEADING finding of exactly the kind this session just fixed; needs its own design pass, not an incremental template tweak. |
| — | `walk()` builtin | *Not recommended* | Niche in this project's target domain (compliance/authz policies rarely need generic tree traversal); low estimated frequency doesn't justify the design cost. |

## Methodology

- **Ground truth**: `opa capabilities --current` (opa 1.19.0) for the full
  206-builtin list; the official Rego language-reference docs for the
  construct inventory; both cross-referenced against spec §6.3/§6.4 and
  `AstMapper.kt`'s actual source, read in full.
- **Probe corpus**: 40 files under `src/test/resources/probes/`, one
  construct/idiom per file, each independently valid Rego confirmed via
  `opa parse` before any classification work began.
- **Classification**: every probe run through the *real* pipeline
  (`opa parse` → `AstMapper` → `ExpressionRenderer`), never a hand-built
  synthetic `Condition` — including two probes (10, 20) where empirical
  `opa eval` runs (not just reading the code) were needed to settle the
  actual runtime semantics before a bucket could be assigned.
- **Two findings were deliberately escalated as ambiguous** rather than
  decided unilaterally (per this session's own instruction to stop on
  anything ambiguous between MISLEADING and FAITHFUL): the `Truthy` wording
  question (resolved: fix it) and the operand-fallback-visibility question
  (resolved: leave it, already a reviewed convention). Both are recorded
  above with the reasoning that settled them.
- **Permanence**: `RegoCoverageAuditTest.kt` pins one or more assertions per
  probe to its confirmed bucket. Verified genuinely load-bearing (not just
  present) by deliberately reverting the `some key` crash guard and
  confirming the exact real exception resurfaces as a test failure, then
  restoring it and confirming green again — the same standard this
  project's own build-verification passes are always held to.
