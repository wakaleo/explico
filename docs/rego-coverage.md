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
| `SomeIn`, two-variable form (`some k, v in obj`) | `for some k, v in <obj>` | **Promoted, further follow-up session** — see below. Probe 06. |
| `RuleReference`, complete/boolean rule (negated or not, same- or cross-package) | `see rule X` / `rule X does not match` | Exercised by the pack (REL-002/REL-004) and probe 21 (with an explicit `default`) — "does not match" is accurate because a complete rule's undefined and explicit-`false` states are both real "doesn't match" outcomes. |
| `BuiltinCall`: `startswith` | `<a> starts with <b>` | Exercised by the pack (REL-003). |
| `BuiltinCall`: `regex.match` | `<v> matches pattern <p>` | Probe 33 — closes a previously-disclosed gap (CLAUDE.md session 4: "untested against real opa output"); now confirmed correct against real parse output. |
| `BuiltinCall`: `glob.match` | `<v> matches glob <p>` | Probe 32 — same gap, same confirmation. |
| Function overloading (multiple bodies, same name, discriminated by arg pattern) | Each body as its own Situation | Probe 16 — needs **no** special-case handling at all: it's structurally identical to the pre-existing incremental-definitions mechanism (`RuleGroup.bodies`), confirmed by actually running it. |
| Partial object rule with a plain string value (`deny_severity[msg] := "high" if {...}`) | Normal card + `producesValue` | Probe 18 — conditions and the message both render exactly as a `deny contains msg` rule would. |
| `Operand.BuiltinCall`: `count(x)` in operand position | `the number of <x>` | **Promoted this session** — see below. Probe 23. |
| `Operand.BuiltinCall`: `lower(x)` / `upper(x)` in operand position | `<x> lowercased` / `<x> uppercased` | **Promoted this session** — see below. Probe 36. |
| Local variable bound to a plain path, reused later (`env := input.x; env == "y"`) | The bound path substituted inline; the assignment itself disappears entirely | **Promoted this session** — see below. Probes 03/07/39; new `AstMapperTest.LocalVariableSubstitution` cases cover var-rooted continuation, transitive chaining, and the non-plain-path fallback. |
| `Operand.BuiltinCall`: `object.get(o, k, d)` — `o` a real path, `k` a plain string literal | `<o> ▸ <k> (default <d>)`, `k` extending `o`'s own breadcrumb as a `PathSegment.KeyLiteral` | **Promoted this session** — see below. Probe 35; new `AstMapperTest` cases cover both the promoted shape and the non-string-key fallback. |
| `Operand.BuiltinCall`: `time.now_ns()` in operand position | `the current time` | **Promoted, follow-up session** — see below. Zero arguments, so the template is a fixed phrase rather than one built around a rendered argument. Probe 34. |
| `=` unification (opa's `eq`), used as a **pure comparison** — both sides already resolve to a real, non-`Unrendered` operand | Identical to `==` | **Promoted, further follow-up session** — see below. Probe 05. Destructuring/binding uses of `=` (probe 04) are unaffected — still `function-call`, see below. |

### Falls back by design (confirmed correct, not a gap)

| Construct | Reason category | Rationale |
|---|---|---|
| Comprehensions (array/set/object), bare or wrapped in `count(...)` | `comprehension` | Explicit non-goal (spec §1.2). Probes 09 (nested `every`), 29, 30; pack REL-004. |
| `every` (incl. nested `every` inside `every`) | `every` | Explicit non-goal. Probe 09 confirms nesting doesn't get partial credit — the whole outer block falls back as one unit, never guessing at the inner structure. |
| Operand-position builtins outside `count`/`lower`/`upper`/`object.get`/`time.now_ns` (`concat`, and any user-defined function used as an operand) | n/a (`Operand.Unrendered`, condition itself still classifies) | No `Operand` shape decided yet for these (differing arity, no template precedent for `concat`). Probes 15/25/26/31 confirm this is safe: the verbatim call text is shown inline, never silently dropped or guessed. |
| `object.get(o, k, d)` where `k` is not a plain string literal (a var, a number, a computed expression) | n/a (`Operand.Unrendered`) | No breadcrumb-extension rule exists for a non-string key — guessing one would be exactly the "widen a template to swallow a construct approximately" failure mode this audit exists to catch. New `AstMapperTest` case (`objectGetWithANonStringKeyIsNotPromoted`); not exercised by any probe file. |
| `count(...)` bare in condition position | `function-call` | Spec's explicit rule: operand-position builtins never render as conditions. Probe 37. |
| `=` unification used as **binding/destructuring** (`[x, y] = [...]`, a fresh var on either side) | `function-call` | **Partially promoted, further follow-up session** — the pure-comparison case (both sides already bound) is now `Comparison` (see "Rendered faithfully" above); a genuine bind still falls back, since `Condition.Comparison` can't represent "this also assigns x and y" — the mapper positively confirms neither side maps to `Operand.Unrendered` (its signal that a fresh binding may be in play) before promoting. Probe 04. A **negated** `=` (`not x = input.y`) also still falls back regardless of whether it's a pure comparison — see the disclosed gap below. |
| `null` / object literal as a comparison operand | `unclassified` | No `Operand` variant for either shape yet. Probes 01/02. |
| Ref-head rule reference used as an operand (`fruit.apple.seeds`, `users_by_role.admin.u1.name`) | n/a (`Operand.Unrendered`) | Neither `input`/`data`-rooted nor a known local-variable binding, so `mapRefChain` correctly falls back to verbatim rather than guessing a breadcrumb for a rule it doesn't recognise as such. Probes 13/14. |
| `default` declarations (rule and function) | `unclassified` | No recognised body shape; RuleGroup's own `.default` field stays null (pre-existing, disclosed gap). **Quirk found this session**: opa's own AST gives a `default` rule's whole-rule `location.text` as just the literal word `default` — not the full `default allow := false` statement — so the fallback block, while honestly verbatim, is unhelpfully short. This is opa's own location-span behaviour, not an AstMapper defect. Probes 27/28. |
| Declare-only `some` (`some key`, `some i, j` — no `in`) | `unclassified` | Correctly falls back once the confirmed crash (below) was fixed; `i`/`j` never become iteration bindings, so a later `arr[i]` still falls back too even though `arr` itself may now be a promoted substitution binding. Probes 07/12. |
| `walk(input, [path, value])` | `unclassified` (binding) + `function-call` (the call) | Probe 11 — the binding and the call both fall back independently; a later comparison using the bound `value` still renders. |
| Composite-value membership (`[1, 2] in pairs`) | `function-call` (the `pairs :=` binding) | `{[1, 2], [3, 4]}` is a set literal, not a plain path, so the promoted substitution rule doesn't apply -- the binding still falls back, with `pairs` shown as untraced bare text in the (still-rendered) `Membership` check. Probe 31. |
| Assignment to something other than a plain path (`x := count(input.y)`), later bare use | `function-call` (assignment) + `Operand.Variable` on reuse | Spec §5's own explicit wording for the non-promoted case: the assignment stays a visible fallback bullet, and later bare uses of the variable render as `Operand.Variable(x)` -- known to be assigned, distinguishable from a genuinely unbound/unknown name (which stays `Operand.Unrendered`). New `AstMapperTest` case (`assignmentToANonPlainPathStillFallsBackAndLaterBareUseRendersAsVariable`); not exercised by any probe file. |
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

## Promoted this session (selected from the backlog below)

Four backlog items were selected and implemented in a follow-up pass, not
part of the original audit:

- **`count(x)` in operand position** (`x` a plain path) now renders "the
  number of X" via a new `Operand.BuiltinCall(name, args)` variant. A
  comprehension-wrapped `count({a | ...})` (the acceptance pack's own
  REL-004 shape) is unaffected — the comprehension argument still fails to
  resolve, so the whole call still falls back exactly as before.
- **`lower(x)` / `upper(x)` in operand position** now render "X lowercased"
  / "X uppercased" via the same mechanism.
- `Coverage.unrenderedOperandCount` was extended to recurse into
  `BuiltinCall.args`, since this is the first *nested* `Operand` shape the
  model has had — an unrenderable argument buried inside an otherwise-
  faithful `count(...)` (e.g. `count(x)` where `x` is itself an unbound
  local variable) still counts against the coverage footer instead of
  silently vanishing from it.
- `time.now_ns` was deliberately left for a later pass at the time — no
  acceptance-pack policy or probe forced a decision on it yet, and it has no
  arguments to extend a breadcrumb with in the first place. Promoted in a
  further follow-up session — see below.
- **`object.get(o, k, d)` in operand position** — promoted in the SAME
  follow-up pass, once `o` is a real path and `k` a plain string literal:
  `k` extends `o`'s own breadcrumb as a `PathSegment.KeyLiteral`, exactly
  like a real bracket-string path (`labels["signed-off-by"]`) already
  renders — not a second, separately-backtick-wrapped operand. A non-string
  key (a var, a number, a computed expression) has no such extension rule
  and is deliberately NOT promoted — falls back to `Operand.Unrendered`
  rather than guessing a phrase for a shape spec never demonstrated.
- **Disclosed, not fixed**: `Examples.referencedPaths` (spec §6.7's worked-
  examples display) only recognises a top-level `Operand.Path`, so a
  `count(x) == 0` condition's underlying `x` won't appear in a worked
  example's referenced-value listing even though the condition itself now
  renders faithfully. Left as-is — out of scope for this promotion, which
  was about the *condition's* rendering, not worked-examples completeness;
  worth a small follow-up (recurse into `BuiltinCall.args`) whenever
  worked-examples coverage for promoted operand builtins is worth doing.
- **Local variable bound to a plain path, reused later** (`env := input.x;
  env == "y"`) — spec §5's own long-documented rule, finally implemented.
  A per-body symbol table now distinguishes three binding kinds
  (`VarBinding.Iteration` for `some x in y`, `VarBinding.Substitution` for
  a plain-path assignment, `VarBinding.NonPath` for anything else) so an
  iteration variable's "[each x]" marker is never wrongly applied to a
  plain substitution, and vice versa. Resolution is transitive
  (`a := input.x; b := a.y` substitutes fully through both), and works both
  as a bare reference and as a ref-chain root (`env.field`). The
  non-promoted case (assignment to something other than a plain path) now
  matches spec §5's own wording precisely: later bare uses render as
  `Operand.Variable(x)`, not the generic unbound `Operand.Unrendered` --
  distinguishing "assigned, but not a path" from "genuinely never bound".
- Verified via `check`/`acceptanceTest` staying green and
  `docs/sample-output/` staying drift-free for all three — no existing
  golden or shipped behavior was affected, since none of the acceptance
  pack's 5 policies uses any of these idioms.

## Promoted in further follow-up sessions

Each selected on its own (not bundled with the earlier follow-up pass
above), one backlog item per pass:

### `time.now_ns()`

- **`time.now_ns()` in operand position** now renders as the fixed phrase
  "the current time" via the same `Operand.BuiltinCall(name, args)` shape --
  `AstMapper.mapCallOperand` promotes it unconditionally when it appears
  with zero arguments (there's nothing to resolve, unlike `count`/`lower`/
  `upper`/`object.get`, which all promote conditionally on their argument(s)
  resolving cleanly). `ExpressionRenderer.renderOperandBuiltinCall` handles
  it as a special case before the single-argument template map, since a
  fixed phrase has no argument to substitute into a template.
  `Coverage.unrenderedOperandCount`'s existing recursion into
  `BuiltinCall.args` needed no change -- an empty argument list sums to
  zero unrendered operands, correctly.
- **`ExpressionRendererTest`'s "genuinely unrecognised operand builtin"
  defensive-check example was repointed from `time.now_ns` to `concat`** --
  the same repointing pattern session 11's `object.get` promotion already
  established (an example proving the defensive check fires stops proving
  anything once the name it uses gets promoted out from under it).
- Verified via `check`/`acceptanceTest` staying green and
  `docs/sample-output/` staying drift-free -- no existing golden or shipped
  behavior was affected, since no acceptance-pack policy uses `time.now_ns`.

### `some k, v in obj` (two-variable form)

- **`Condition.SomeIn` gained a third field, `key: String? = null`** (spec
  §14 promotion) -- `null` for the pre-existing single-variable form, set
  for the two-variable one. Confirmed via a real `opa parse` run that
  `some k, v in c` desugars to `internal.member_3(k, v, c)`, the natural
  three-argument sibling of the single-variable form's already-handled
  `internal.member_2(v, c)`. `AstMapper.mapSomeIn` now dispatches on args
  count/shape between the two, and -- the actual point of the promotion --
  binds **both** `k` and `v` as `VarBinding.Iteration` against the same
  collection. This needed no new substitution mechanism: the existing
  per-body symbol table (spec §14.4) is already keyed by variable name and
  fully general per name, so a later bare or ref-chain-root use of either
  variable extends the collection's own breadcrumb with `[each k]`/
  `[each v]` exactly like the single-variable form's `[each x]` already
  does -- `PathHumanizer` needed no change at all.
- `ExpressionRenderer`'s `SomeIn` phrase gains "k, v" instead of a single
  name when `key` is non-null: `"for some ${key}, ${variable} in ..."`.
- **`Canonicalizer`'s `SomeIn` canonicalization aliases `key` before
  `variable`, matching their left-to-right introduction order in the real
  source** (`some k, v in ...` introduces `k` before `v`, both before the
  collection is even read) -- consistent with the existing single-variable
  comment this extends. This closes a genuine, real diff-hash gap, not just
  a rendering one: **before** this promotion, both `k` and `v` fell back to
  the generic `Operand.Variable` fallback wherever later used bare, aliased
  purely by first-appearance order -- so `some k, v in obj; k == "x"` and
  `some k, v in obj; v == "x"` (testing the *key* vs. the *value* -- a
  genuinely different check) hashed **identically**, since each variant
  introduces exactly one new name at the same structural position. Proven
  empirically with two new `CanonicalizerTest` fixtures
  (`some-kv-base`/`some-kv-key-vs-value`) run through the real pipeline --
  confirmed `isNotEqualTo` now, not assumed from reading the code alone.
  Rename invariance (`some k, v` -> `some key, val`, matching the existing
  single-variable rename test) is confirmed unaffected by a third fixture
  (`some-kv-renamed-vars`).
- Verified via `check`/`acceptanceTest` staying green and
  `docs/sample-output/` staying drift-free -- no acceptance-pack policy uses
  the two-variable form.

### `=` used as a pure comparison

- **`=` (opa's own operator name `eq`) now promotes to `Condition.Comparison`
  when -- and only when -- both sides are positively confirmed to already
  resolve to a real value.** Unlike `==` ("equal"), which the Rego language
  guarantees never introduces a binding, `=` is genuinely ambiguous at the
  syntax level: it can ALSO destructure/bind a fresh variable (`[x, y] =
  [...]`, probe 04). The mapper runs both sides through the exact same
  `mapOperand` path `==` already uses, then requires NEITHER side to be
  `Operand.Unrendered` before promoting -- an unbound bare var, or a
  composite literal containing one, is exactly the shape that maps to
  `Operand.Unrendered` today (never `Unsupported`), so its presence is
  treated as a positive signal a fresh binding may be in play, not a
  comparison. This is deliberately conservative: `x = input.y` with `x`
  fresh stays unpromoted (probe 05 uses two already-real paths and DOES
  promote; a new `AstMapperTest` case proves the fresh-var sibling stays
  unclassified).
- **A variable already bound by an earlier plain-path `:=` also qualifies**
  -- `x := input.a; x = "production"` promotes the second line, since `x`
  resolves through the pre-existing `VarBinding.Substitution` mechanism
  (spec §14.4) to a real path, not `Operand.Unrendered`. New `AstMapperTest`
  case.
- **Real diff-quality improvement, not just a rendering one**: before this
  promotion, `=` unconditionally fell back to a verbatim
  `Condition.Unrendered`, so switching an existing, equally-pure comparison
  from `==` to `=` (a purely stylistic change with identical real semantics)
  would have spuriously changed the canonical hash. Now both hash
  identically -- confirmed with a new `CanonicalizerTest` fixture pair
  (`eq-unification-base`/`eq-unification-equivalent`), not assumed.
- Verified via `check`/`acceptanceTest` staying green and
  `docs/sample-output/` staying drift-free -- no acceptance-pack policy uses
  `=`.

### Disclosed, not fixed: negated comparisons silently drop the negation

**Discovered while implementing the `=` promotion above, confirmed via a
real `opa parse` run, and deliberately NOT fixed in this pass** -- it's a
pre-existing gap in `buildComparisonLike` (the code path shared by `==`,
`!=`, `>`, `>=`, `<`, `<=`, and now the promoted pure-`=` case), not
something this promotion introduced, and fixing it is a materially larger
change than "promote the next backlog item": it needs a new `negated` field
on the public `Condition.Comparison` (a spec-frozen model type), a spec
amendment, new `ExpressionRenderer` templates for six negated operators, and
a `Canonicalizer` update.

- **The gap**: `not input.a == input.b` is real, valid Rego -- confirmed via
  `opa parse`: `negated: true` on the expr, terms name still `equal`. But
  `Condition.Comparison` has no `negated` field, and `buildComparisonLike`
  never reads `expr.negated` at all -- so this renders as `` `input.a` is
  `input.b` `` (the POSITIVE statement), when the real logic is testing that
  they're **not** equal. This is a genuine MISLEADING finding by this
  audit's own definition (spec §14's cardinal sin: a rendered template whose
  English doesn't match the construct's exact semantics), just discovered a
  session later than the original audit, via unrelated work.
- **This promotion's own new code does NOT add a new instance of it**: the
  `=`-promotion path explicitly requires `!expr.negated` before even
  attempting the pure-comparison check (`mapCallShapedCondition`) -- a
  negated `=` (`not x = input.y`) stays in the pre-existing `function-call`
  fallback, exactly as it already did before this promotion existed. New
  `AstMapperTest` case
  (`negatedUnificationStaysUnclassifiedRatherThanSilentlyDroppingTheNegation`)
  proves this directly.
- **Not exercised by the acceptance pack** -- none of the 5 real policies
  negate a comparison operator (`not input.x == "y"` doesn't appear
  anywhere; the pack's own negations are all over `Truthy`, `Membership`,
  `BuiltinCall`, or `RuleReference`, which already carry `negated`
  correctly). Flagged to the operator as a discovered issue for a
  deliberate future session, not silently left undocumented.

## Disclosed, unchanged conventions (reviewed this session, deliberately not changed)

**Operand-level fallback (`Operand.Unrendered`) renders as plain backticked
verbatim source — visually identical to a real humanized path.** A ref-head
rule reference (`` `fruit.apple.seeds` ``), an untraced non-plain-path-assigned
variable, or an operand-position builtin call the promotion above doesn't
cover (`` `object.get(input.x, "y", "z")` ``) all look exactly like a real
breadcrumb (`` `deployment ▸ environment` ``) in the rendered bullet — there
is no inline marker, only the aggregate "contains N unrendered value(s)"
coverage-footer line. This session's probe corpus showed the pattern is
considerably more pervasive than the single narrow case (operand-position
builtins) session 3/4 originally reviewed it for — though the plain-path
local-variable case, the single largest contributor to that pervasiveness,
was itself promoted away this session (see above), narrowing the remaining
surface. Reaffirmed as **not** a MISLEADING finding for what's left: the
underlying assertion in every affected bullet stays true regardless (the
comparison really does hold), only legibility suffers, and widening the
convention now — mid-audit, without new evidence the *wording itself* is
wrong — risks exactly the scope creep this session was meant to avoid. Left
unchanged; noted here for a future increment to weigh deliberately, with its
own review, not as a byproduct of this one.

## Promotion backlog

Constructs currently in the fallback bucket that a future increment could
render faithfully, ranked by estimated real-world policy-authoring
frequency. The top four ranks (`count`/`lower`/`upper`/`object.get`, and
local-variable substitution) plus former rank 1 (`time.now_ns`) plus former
rank 1 of the next round (`some k, v in obj`) plus former rank 1 of the
round after that (`=` as pure comparison) were selected and implemented in
follow-up passes — see "Promoted this session" and "Promoted in further
follow-up sessions" above; the rest remain a ranked list for selection, not
a plan.

| Rank | Construct | Proposed template | Risk |
|---|---|---|---|
| 1 | Arithmetic operands (`x + 1`, etc.) | A small infix expression renderer | **Medium-high.** More design surface than the others (multiple operators, precedence, humanizing the operand sub-tree). |
| 2 | `concat(sep, [...])` string-join operand | "X joined with Y" (no spec precedent) | **Medium.** New design, not just new wiring. |
| 3 | `null` literal operand | `Operand.Literal("null")` | **Low.** Trivial, just never added. |
| 4 | Small flat object literal operand | Same mechanism as the existing small-array-literal rendering | **Medium.** Needs a deterministic key-ordering and size-cap decision (mirroring the existing ≤5-element array rule). |
| 5 | Ref-head / partial-object rule references used as operands (`fruit.apple.seeds`) | Humanize as a breadcrumb once resolved against the rule registry | **Medium-high.** Needs to distinguish "known local rule reference" from a real input/data path, and to handle a partial-object's dynamic keys. |
| — | **Correctness bug, not a coverage gap**: negated comparisons (`not x == y`) silently render the positive form | Add `negated: Boolean` to `Condition.Comparison`, thread `expr.negated` through `buildComparisonLike` and the promoted `=` path, add negated `ExpressionRenderer` templates for all six operators, update `Canonicalizer` | **High priority despite not being ranked with the others above** — this is a MISLEADING finding (spec §14's cardinal sin), not a missing-coverage nicety; see "Disclosed, not fixed" above for the full write-up. Not bumped ahead of the frequency-ranked list above only because it's a different KIND of work (a model/renderer/diff fix touching six operators at once, not a single new construct), and the operator hasn't yet chosen to prioritize it over the ranked list. |
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
