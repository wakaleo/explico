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
| `Comparison` (`==`/`!=`/`>`/`>=`/`<`/`<=`), positive or negated (`not x == y`) | `<l> is/is not/is greater than/... <r>` (negated: literal negation of each verb) | Spec §6.3's frozen template; exercised by the pack and probes 08/13/14/16/22/23/25/26/31/34/35/36/41. Doesn't attempt to describe undefined-propagation (if either side is absent the comparison is undefined, not false) — an already-accepted simplification for `==` (REL-001) extended consistently to the other operators, not a new deviation. Negation support **fixed, further follow-up session** — see below. |
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
| `Operand.BuiltinCall`: `plus`/`minus`/`mul`/`div`/`rem` (opa's operator names for `+`/`-`/`*`/`/`/`%`) in operand position, at any nesting depth | `<a> plus/minus/times/divided by/modulo <b>` | **Promoted, further follow-up session** — see below. Probe 25. Unlike `=`/`object.get`, no restriction on nested/`Unrendered` args — arithmetic never introduces a binding, and opa's own parse tree already resolves precedence, so recursive rendering is faithful at any depth. |
| `Operand.BuiltinCall`: `concat(sep, collection)` — a literal array/set explodes into one operand per element; a path/var reference maps as a single whole-collection operand | `<elem>, <elem>, ... joined with <sep>` (one element needs no comma) | **Promoted, further follow-up session** — see below. Probes 26 (exploded array literal), 42 (whole-collection reference). |
| `null` literal operand | `null` | **Promoted, further follow-up session** — see below. Probe 01. |
| Small flat object literal (≤5 pairs, string keys, scalar values) as an operand | `{"key": value, ...}`, opa's own already-sorted key order | **Promoted, further follow-up session** — see below. Probe 02. |
| Ref-head rule reference, EXACT full-path match only (`fruit.apple.seeds`, where `fruit.apple.seeds := 12` — opa gives this rule `head.name == null`, only `head.ref` populated) | Humanized breadcrumb, same as any other path | **Promoted, further follow-up session, deliberately scoped** — see below. Probe 13. A dynamic-keyed ref-head rule reference (`users_by_role.admin.u1.name`) is NOT promoted — see "Falls back by design" below. |
| `default` declarations (rule and function), e.g. `default allow := false` | A distinct `**Default outcome:** <value>` line on the card; never a Situation | **Promoted, review pass (items 1-4).** `RuleGroup.default` is populated from the declaration's own value, rendered as that value's verbatim source; the declaration is excluded from `bodies`. Before, opa's whole-rule `location.text` (just the word `default`) became a bogus "Situation" containing a bare-`default` fallback block. A name that is *only* a `default` (no other body in the file) still gets a body-less card. Limitation: a default in a different file from its rule's bodies is not linked (per-file mapping). Probes 27/28. |
| Bracket-form partial rule (`violation[{"msg": m}] if {...}`, Gatekeeper style) | Ordinary card named `violation` | **Fixed, review pass.** opa leaves `head.name` null and only populates `head.ref == [var, object]`; the rule used to be silently dropped (no card, no index row). `effectiveName` reads `ref[0]` only when `head.key != null`, so ref-headed *document* rules (`fruit.apple.seeds := 12`) stay excluded. A message built from an object key is not extracted as "Produces". |
| Several `scope: rule`-annotated rules sharing one name (e.g. three `deny contains msg if` bodies, K8S-001/002/003) | One card per distinct annotation; unannotated bodies group into their own card | **Fixed, review pass.** The old (package, name) metadata index collapsed them via `.toMap()` last-wins, so two controls vanished and one card carried all bodies. Metadata now resolves per rule by (file, row); `scope: document` still applies to every same-named rule in the file. |
| Package-scope METADATA | Title (bold) and description under the package heading | **Added, review pass.** Read from `opa parse`'s `module.annotations` (`scope: package`) into `PolicyPackage.metadata`. |

### Falls back by design (confirmed correct, not a gap)

| Construct | Reason category | Rationale |
|---|---|---|
| Comprehensions (array/set/object), bare or wrapped in `count(...)` | `comprehension` | Explicit non-goal (spec §1.2). Probes 09 (nested `every`), 29, 30; pack REL-004. |
| `every` (incl. nested `every` inside `every`) | `every` | Explicit non-goal. Probe 09 confirms nesting doesn't get partial credit — the whole outer block falls back as one unit, never guessing at the inner structure. |
| Operand-position builtins outside `count`/`lower`/`upper`/`object.get`/`time.now_ns`/arithmetic/`concat` (any user-defined function used as an operand) | n/a (`Operand.Unrendered`, condition itself still classifies) | No `Operand` shape decided yet for these (differing arity, no template precedent). Probe 15 confirms this is safe: the verbatim call text is shown inline, never silently dropped or guessed. |
| `object.get(o, k, d)` where `k` is not a plain string literal (a var, a number, a computed expression) | n/a (`Operand.Unrendered`) | No breadcrumb-extension rule exists for a non-string key — guessing one would be exactly the "widen a template to swallow a construct approximately" failure mode this audit exists to catch. New `AstMapperTest` case (`objectGetWithANonStringKeyIsNotPromoted`); not exercised by any probe file. |
| `count(...)` bare in condition position | `function-call` | Spec's explicit rule: operand-position builtins never render as conditions. Probe 37. |
| `=` unification used as **binding/destructuring** (`[x, y] = [...]`, a fresh var on either side) | `function-call` | **Partially promoted, further follow-up session** — the pure-comparison case (both sides already bound) is now `Comparison` (see "Rendered faithfully" above); a genuine bind still falls back, since `Condition.Comparison` can't represent "this also assigns x and y" — the mapper positively confirms neither side maps to `Operand.Unrendered` (its signal that a fresh binding may be in play) before promoting. Probe 04. A **negated** `=` (`not x = input.y`) also still falls back regardless of whether it's a pure comparison — see the disclosed gap below. |
| Object literal — more than 5 pairs, a non-string key, or a nested (non-scalar) value | n/a (`Operand.Unrendered`, condition itself still classifies) | Same conservative scope as the small array/set literal's own ≤5-element/scalar-only restriction, extended to objects (see "Rendered faithfully" above and the promotion write-up below). New `AstMapperTest` cases; not exercised by any probe file. |
| Ref-head rule reference with a DYNAMIC key (`users_by_role.admin.u1.name`, where `users_by_role[role][id] := user` is keyed by the rule's own local variables) | n/a (`Operand.Unrendered`) | **Deliberately not promoted** — see the promotion write-up below. An exact-full-path ref-head reference (`fruit.apple.seeds`) IS promoted now, see "Rendered faithfully" above. Probe 14. |
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

### Fixed, follow-up session: negated comparisons were silently dropping the negation

**Discovered while implementing the `=` promotion above** (flagged to the
operator rather than fixed unilaterally at the time, since it's a materially
larger change than "promote the next backlog item"), **fixed in a dedicated
follow-up pass on the operator's explicit instruction.**

- **The bug**: `not input.a == input.b` is real, valid Rego -- confirmed via
  `opa parse`: `negated: true` on the expr, terms name still `equal`. But
  `Condition.Comparison` had no `negated` field, and `buildComparisonLike`
  (the code path shared by `==`, `!=`, `>`, `>=`, `<`, `<=`, and the promoted
  pure-`=` case) never read `expr.negated` at all -- so this rendered as
  `` `input.a` is `input.b` `` (the POSITIVE statement), when the real logic
  tests that they're **not** equal. A genuine MISLEADING finding by this
  audit's own definition (spec §14's cardinal sin), discovered a session
  after the original audit, via unrelated work.
- **The fix**: `Condition.Comparison` gains `negated: Boolean = false` (spec
  §14 amendment). `buildComparisonLike`'s comparison-operator branch and the
  promoted `=` path (`eqAsPureComparisonOrNull`) both now thread
  `expr.negated` straight through -- the `=`-promotion's earlier
  `!expr.negated` guard is removed entirely (it's no longer needed; negation
  is orthogonal to the binding-vs-comparison question that guard was
  actually protecting against). `ExpressionRenderer` gains a
  `NEGATED_COMPARISON_VERBS` map, mirroring `COMPARISON_VERBS` with a
  literal negation of each verb ("is not greater than", never a different
  operator's positive form like "is at most" -- the same
  undefined-propagation simplification already accepted for the positive
  wording). `Canonicalizer` adds `negated` to `Comparison`'s canonical JSON
  (alphabetically ordered: `left`, `negated`, `op`, `right`, `type`).
- **Real diff-quality fix too, not just a rendering one**: before this fix,
  `input.a == input.b` and `not input.a == input.b` -- genuinely opposite
  logic -- hashed IDENTICALLY, since `negated` didn't exist in the canonical
  shape at all. Confirmed empirically with a new `CanonicalizerTest` fixture
  pair (`negated-comparison-base`/`negated-comparison-negated`), not
  assumed.
- **All six operators confirmed via a real `opa parse` run**, not just
  `equal`: `not input.a != input.b`, `not input.a > input.b`, `>=`, `<`,
  `<=` all parse with `negated: true` too -- this isn't an `equal`-specific
  quirk. New probe (`41-negated-comparison.rego`) exercises `equal`'s case
  through the real pipeline (`RegoCoverageAuditTest`); the other five
  operators are covered by hand-built `Condition.Comparison` cases in
  `ExpressionRendererTest`'s phrasing table (all six operators × negated),
  since no real Rego syntax difference exists between them at the AST level
  that would need separate probe coverage -- `buildComparisonLike` treats
  `expr.negated` identically regardless of which comparison operator it's
  attached to.
- **Not exercised by the acceptance pack** -- none of the 5 real policies
  negate a comparison operator, so no golden or `docs/sample-output/`
  content changed.

### Arithmetic operands (`plus`/`minus`/`mul`/`div`/`rem`)

Backlog rank #1 of the ranked list remaining after the negated-comparisons
fix, selected on its own:

- **`plus`/`minus`/`mul`/`div`/`rem`** (opa's own operator names for
  `+`/`-`/`*`/`/`/`%`, confirmed via real `opa parse` runs) now promote to
  `Operand.BuiltinCall`, rendered via a new infix-prose template map
  (`ExpressionRenderer`'s `ARITHMETIC_TEMPLATES`) that interpolates the
  already-rendered LEFT and RIGHT argument text -- "X plus Y", "X minus Y",
  "X times Y", "X divided by Y", "X modulo Y".
- **No restriction on nested or `Unrendered` arguments, unlike `=`/
  `object.get`.** Arithmetic operators can never introduce a binding (unlike
  `=`), so there's no "fresh variable" ambiguity to guard against; and opa's
  own parse tree already resolves operator precedence correctly (confirmed
  empirically: `input.a + input.b + input.c` desugars to opa's own
  left-associative `plus(plus(a, b), c)`), so recursive rendering -- each
  nested call rendered via the exact same mechanism, with no re-derivation
  of grouping -- is faithful by construction at any depth. This is a
  deliberately different design decision from the earlier "Medium-high risk
  ... precedence" framing in the original backlog entry: the risk was about
  a NAIVE flat-token implementation re-deriving precedence, which this
  implementation never does (it only ever walks opa's already-correct tree).
- **Composes cleanly with prior promotions with zero extra logic**: a
  chained sum renders as "`a` plus `b` plus `c`" (confirmed via
  `ExpressionRendererTest.chainedArithmeticRendersLeftAssociatively`); an
  arithmetic expression nested inside another promoted operand builtin
  (`count(x) + 1`) renders as "the number of `x` plus `1`" -- valid prose,
  never malformed nested backticks, since each level's rendered text is
  interpolated as plain text rather than concatenated/re-wrapped at the
  character level (`ExpressionRendererTest.arithmeticNestedWithAnotherOperandBuiltinComposesAsProse`).
- **Unary minus is not a special case**: `-input.a` desugars to opa's own
  `minus(0, input.a)` (confirmed via a real `opa parse` run) -- a genuine
  binary `minus` call like any other, needing no dedicated handling. New
  `AstMapperTest` case.
- **`Coverage.unrenderedOperandCount`'s existing recursion into
  `BuiltinCall.args` needed no change** -- an unbound variable nested inside
  an arithmetic expression still counts against the coverage footer, the
  same guarantee already proven for `count`/`lower`/`upper` (spec §14.4).
  New `ExpressionRendererTest` case
  (`anUnrenderedOperandNestedInsideArithmeticStillCountsAgainstCoverage`).
- **`Canonicalizer` needed no change either** -- `Operand.BuiltinCall`'s
  canonicalization is already fully generic over `name`/`args`, with no
  arithmetic-specific logic to add.
- Verified via `check`/`acceptanceTest` staying green and
  `docs/sample-output/` staying drift-free -- no acceptance-pack policy uses
  arithmetic.

### `concat(sep, collection)`

Backlog rank #1 of the ranked list remaining after arithmetic, selected on
its own:

- **Two genuinely common real-world shapes for the collection argument,
  both promoted, neither guessed.** `concat("/", [input.a, input.b])` (a
  literal array/set) explodes into one operand PER ELEMENT at mapping time
  -- each element goes through the same general `mapOperand` every other
  operand position uses, then all elements are comma-joined at render time.
  `concat("/", input.parts)` (a path or bound var referencing an EXISTING
  collection -- arguably the more common real-world idiom, since most
  policies join an already-computed list rather than construct one inline)
  maps as a SINGLE whole-collection operand instead. Neither requires
  guessing semantics; both were judged in-scope for "how do I render
  `concat`", not scope creep into a different construct. The original
  backlog entry's proposed template ("X joined with Y") didn't specify
  which argument was X vs. Y or address the array-vs-reference question at
  all -- both were this session's own design decision, following the
  project's established "never guess, only promote the unambiguous common
  shape" convention (same posture as `object.get`'s string-literal-key
  restriction).
- **Model representation reuses `Operand.BuiltinCall` with no new type**:
  `args[0]` is always the separator; `args.drop(1)` is either N element
  operands or a single whole-collection operand, by convention (documented
  in `mapConcatOperand`'s own KDoc, not a new sealed variant). The renderer
  doesn't need to know which shape produced its input -- it just
  comma-joins whatever operand text it's given, and `joinToString` naturally
  needs no comma for a one-element list.
- **A set literal (`concat(",", {a, b})`) is treated identically to an
  array literal** -- `mapConcatOperand` checks for either term type. Not
  exercised by any probe (no acceptance-pack-adjacent idiom uses a set
  here); confirmed via a new synthetic-JSON `AstMapperTest` case following
  a real `opa parse` run confirming the "set" term type.
- **A comprehension as the collection argument correctly falls back the
  WHOLE `concat` call**, not a partial rendering -- it takes the
  whole-collection-reference path (its term type isn't "array"/"set"), and
  `mapOperand`'s existing comprehension handling returns `Unsupported`,
  propagating up exactly like every other operand-position promotion's
  `Unsupported` handling already does. New synthetic-JSON `AstMapperTest`
  case.
- **`Coverage`/`Canonicalizer` needed no change** -- both are already fully
  generic over `Operand.BuiltinCall`'s `name`/variable-length `args`.
- Verified via `check`/`acceptanceTest` staying green and
  `docs/sample-output/` staying drift-free -- no acceptance-pack policy uses
  `concat`.

### `null` literal operand

Backlog rank #1 of the ranked list remaining after `concat`, selected on
its own -- the lowest-risk item left, exactly as the backlog's own "Low,
trivial, just never added" note predicted:

- `mapOperand` gains a `"null"` term-type case, mapping to
  `Operand.Literal("null")` -- a one-line addition, no design surface.
- **A direct, mechanical follow-on was needed too, not a separate
  promotion**: `mapCollectionLiteral`'s own scalar-type allow-list (the
  gate deciding whether a small array/set literal renders inline, e.g.
  `["production", "staging"]`) only accepted `string`/`number`/`boolean`
  element types -- an array containing a `null` element (`["x", null, 1]`,
  confirmed via a real `opa parse` run to have term type `"null"`, the SAME
  type `mapOperand`'s new case now recognises) would still have fallen back
  entirely even after `null` itself was promoted, since the array-literal
  gate hadn't been told about the new type. Adding `"null"` to that same
  allow-list is not a new construct -- the set is literally "term types
  `mapOperand` renders as an `Operand.Literal`," and `null` now qualifies.
  New synthetic-JSON `AstMapperTest` case.
- `ExpressionRenderer`/`Coverage`/`Canonicalizer` needed no change --
  `Operand.Literal` was already a fully generic leaf shape everywhere.
- Verified via `check`/`acceptanceTest` staying green and
  `docs/sample-output/` staying drift-free -- no acceptance-pack policy
  compares against `null`.

### Small flat object literal operand

Backlog rank #1 of the ranked list remaining after `null`, selected on its
own -- the last item at "Low"/"Medium" risk before the remaining backlog
steps up to "Medium-high":

- **Same mechanism as the small array/set literal** (`mapCollectionLiteral`)
  -- a ≤5-pair size cap, every VALUE restricted to the same
  `SCALAR_TERM_TYPES` set (now shared between both functions, extracted as
  a single file-level constant rather than duplicated). Renders as
  `{"key": value, ...}` -- real Rego object-literal syntax, not the array
  literal's own bracket-free convention (which only reads naturally after
  "is one of"; a compared whole-object VALUE reads better with its own
  real braces/colons).
- **Every KEY is also restricted to a plain `"string"` term** -- the
  overwhelmingly common `{"k": v}` shape. Rego does allow a non-string key
  (`{1: "a"}`), but promoting that too would need a second, different
  rendering convention for the key position, and the backlog's own risk
  note didn't ask for it -- deliberately left unpromoted rather than
  guessed. New `AstMapperTest` case.
- **The "deterministic key-ordering" question the backlog flagged as a
  design risk turned out to already be solved, not something to design**:
  confirmed via two real `opa parse` runs that opa's own AST already lists
  an object literal's pairs in alphabetically-sorted-by-key order,
  regardless of source order (`{"author": ..., "approved": ...}` parses
  with `"approved"` listed before `"author"`; a `{"zebra":1, "apple":2,
  "mango":3}` probe confirms genuine alphabetical sorting, not just an
  artifact of the first example's particular keys). This mapper never
  sorts anything itself -- it only renders opa's own already-deterministic
  order, consistent with the project's "never guess, only mechanically
  derive" posture.
- **A non-scalar (nested array/object) value stays unpromoted**, exactly
  like the array/set literal's own restriction -- "flat" is the operative
  word in both the backlog's proposed template and this implementation.
  New `AstMapperTest` case.
- **Falling short of the cap or scalar-value restriction demotes only the
  OBJECT OPERAND to `Operand.Unrendered`, never the whole enclosing
  condition** -- exactly like an unbound bare variable doesn't demote its
  whole `Condition.Comparison` either. `input.x == {6 pairs...}` still
  renders as a real comparison, just with the object shown as verbatim
  source on the right. New `AstMapperTest` cases for the size cap and the
  non-scalar-value case both confirm this (a first draft of these tests
  wrongly expected the whole condition to fall back, caught by actually
  running them against the real implementation rather than assumed).
- `ExpressionRenderer`/`Coverage`/`Canonicalizer` needed no change --
  `Operand.Literal` was already a fully generic leaf shape everywhere.
- Verified via `check`/`acceptanceTest` staying green and
  `docs/sample-output/` staying drift-free -- no acceptance-pack policy
  compares against an object literal.

### Ref-head rule reference, exact full-path match only

The last ranked backlog item -- Medium-high risk, the highest of any
promotion this project has done. **Deliberately scoped down from its
original two-probe framing on the operator's explicit instruction**, after
investigation surfaced that the full scope was materially bigger and
riskier than the backlog entry's own description implied:

- **The backlog undersold the real scope.** `fruit.apple.seeds := 12`
  (probe 13) -- confirmed via a real `opa parse` run -- has `head.name ==
  null`; opa only populates `head.ref`, the rule's full dotted path.
  `mapRuleGroups`/`buildRuleRegistry`/`buildPartialRuleRegistry` all
  already filtered these rules out ENTIRELY (no card, not in any
  registry) before this promotion -- not just "unresolved as an operand
  reference" as the backlog's phrasing suggested. Making ref-headed rules
  first-class (their own cards, index.md entries, anchors) was judged a
  separate, materially larger concern and stays out of scope; this
  promotion only makes a REFERENCE to a ref-headed rule's own exact path
  resolve, not the rule's own visibility.
- **Two probes needed genuinely different treatment, confirmed via real
  `opa parse` runs, not assumed**: probe 13's `head.ref` is
  `[var(fruit), string(apple), string(seeds))]` -- every segment after the
  root is a literal. Probe 14's rule, `users_by_role[role][id] := user`,
  has `head.ref == [var(users_by_role), var(role), var(id)]` -- the 2nd/3rd
  segments are the rule's OWN local parameter variables (dynamic keys),
  not literal path segments. **Chose to promote only probe 13's shape**:
  a new `buildRefHeadRuleRegistry` registers a ref-headed rule (per
  package) ONLY when every `head.ref` segment after the root is a
  `"string"` term -- this type filter naturally excludes probe 14's rule
  with no separate check needed, since `role`/`id` have no single static
  path to register in the first place.
- **A reference resolves only on an EXACT full-chain match** against the
  registry, checked in `mapRefChain` before falling through to the
  existing unresolved-root handling -- `fruit.apple.seeds.extra` (one
  segment beyond the rule's own declaration) correctly does NOT match and
  stays `Operand.Unrendered`, the same conservative "only the unambiguous
  shape, never guess" posture every other promotion in this file follows.
  New `AstMapperTest` case.
- **A local variable binding with the same root name always takes
  priority** over the ref-head registry (`symbolTable[rootName] == null`
  is checked before attempting the ref-head match) -- avoids any possible
  ambiguity between a same-package rule name and a local `some...in`/`:=`
  binding. New `AstMapperTest` case
  (`some fruit in input.baskets; fruit.apple.seeds > 10` resolves through
  the LOCAL binding, not the rule).
- **Required threading a shared registry through the entire
  operand-mapping call chain** (`mapOperand` and everything it calls --
  roughly a dozen functions) rather than a single localized change, since
  a ref-head reference can appear as any operand, not just a comparison's
  top-level side. This is architecturally the largest promotion in the
  series so far -- flagged to the operator before implementation, with
  three explicit scoping options offered (probe 13 only / both probes /
  skip entirely), rather than unilaterally deciding the scope.
- `ExpressionRenderer`/`Coverage`/`Canonicalizer` needed no change -- the
  new registry only affects how `Operand.Path` gets BUILT in `AstMapper`,
  never a new `Operand`/`Condition` shape.
- Verified via `check`/`acceptanceTest` staying green and
  `docs/sample-output/` staying drift-free -- no acceptance-pack policy
  uses a ref-headed rule.

## Review pass: realistic-policy fixes (items 1-4)

Found by rendering Gatekeeper-, RBAC- and Terraform-style policies
(`src/test/resources/realistic/policies/`, pinned by `RealisticPoliciesAcceptanceIT`
plus new `AstMapperTest`/`MarkdownRendererTest` cases). See the table rows above.
Disclosed, not fixed: `default` is shown on every card when one rule name is split
across several cards (true, but repeated); `diff` does not hash `default` values or
the new per-rule split; `resolveAnchor` picks the first card when several share a
name; cross-file `default` is not linked.

## Review pass: path/iteration fixes (items 5-7)

Same realistic-policy corpus, pinned by `RealisticPathsAcceptanceIT` plus new
`AstMapperTest.PathHumanizationReviewFixes` and `CanonicalizerTest` cases.

| Finding | Was | Now |
|---|---|---|
| A wildcard `[_]` anywhere in a path (item 6) | Fell back to raw `Operand.Unrendered` source, **always** -- the old `name == "_"` check compared against opa's own GENSYM var name (`$0`, `$1`, ...), which is never literally `"_"`, confirmed via a real `opa parse` run. The pre-existing `PathHumanizer`/`ExpressionRenderer` handling for `AnyIndex` was correct all along; it simply never received one. | Detected by the term's own `sourceText(location) == "_"` instead -- opa's gensym renaming doesn't touch the source text. Humanizes with the pre-existing "any of:" convention, e.g. `input.request.object.spec.containers[_].resources.limits` -> `` any of: `request ▸ object ▸ spec ▸ containers ▸ resources ▸ limits` ``. |
| `x := <collection>[_]` (item 5) | The whole assignment fell back as raw source (compounding the bug above); later uses of `x` showed raw `x.field` text. | Promotes to a real `Condition.SomeIn(x, collection)` + `VarBinding.Iteration` -- the exact same mechanism `some x in collection` uses, since the two are semantically identical. Only a TRAILING wildcard promotes; a middle one (`x := input.a[_].b`) stays a plain `VarBinding.Substitution`, which still renders honestly (the AnyIndex survives mid-path) just without an explicit "for some" bullet. |
| A dynamic bracket key that is itself an `input`/`data` path, e.g. `data.grants[input.user.id]` (item 7) | The nested `ref` term hit `mapPathSegments`'s catch-all `else` branch, dumping its raw source text (`"input.user.id"`) as an ordinary `PathSegment.Field` -- rendered inline, unhumanized, inside an otherwise-clean breadcrumb (`` `data ▸ grants ▸ input.user.id` ``). | A new `PathSegment.KeyPath(segments)` wraps the key's own resolved path, humanized recursively and wrapped in brackets: `` `data ▸ grants ▸ [user ▸ id]` ``. Resolved through the SAME `mapRefChain` every other reference goes through -- a key that doesn't resolve cleanly (unbound var, comprehension) falls back for the WHOLE containing path, never guessed. |

Disclosed, not fixed: `Examples.resolveSegments` treats a `KeyPath` segment as
unresolvable (`emptyList()`) for worked-examples display-value lookup, since
resolving the key's own nested path needs a different root (`input`) than the
one being walked (`current`) -- moot today since `referencedPaths` only ever
passes an `input.`-rooted path, and no fixture in this corpus is evaluated with
examples anyway.

## Review pass: function calls and produced messages (items 8-9)

Same realistic-policy corpus, plus a new `src/test/resources/realistic/functions/`
fixture for item 8's fallback cases, pinned by `RealisticCallsAndMessagesAcceptanceIT`,
`AstMapperTest.FunctionCallsAndMessagePlaceholders`, and `ExpressionRendererTest`'s
`FunctionCallRendering` cases.

| Finding | Was | Now |
|---|---|---|
| A call to a user-defined function in condition position, e.g. `not is_exempt(input.request.namespace)` (item 8) | Fell back to raw `Condition.Unrendered` source, always -- same bucket as any genuinely unsupported builtin, no distinction made. | A new `Condition.FunctionCall(packagePath, functionName, args, negated)`: links to the function's own card (same anchor convention as `Condition.RuleReference`) and names its already-humanized arguments, e.g. `` function [`is_exempt`](#k8s-admission-is-exempt) is not satisfied for `request ▸ namespace` ``. Same-package calls only -- a cross-package call (`helper.is_prod(...)`) is a disclosed, not-yet-handled limitation and still falls back. An argument that doesn't resolve at all (a comprehension) falls the WHOLE call back, never a partial rendering. |
| A declared function parameter used bare inside its own function's body, e.g. `ns` in `is_exempt(ns) if { ns in {...} }` (item 8) | Never recognised as bound at all -- silently rendered as a bare `Operand.Unrendered` echo of its own source text, visually IDENTICAL to a real humanized path. A real, previously-undiscovered instance of the exact "operand fallback is indistinguishable from a real breadcrumb" risk §14's audit flagged but left unquantified. | A new `VarBinding.Parameter`/`Operand.Parameter(name)`: pre-populated into the symbol table before a function's own body is walked, rendered as `` parameter `ns` `` -- explicitly labelled, never confused with an assigned-but-opaque `Operand.Variable` or a genuinely unbound `Operand.Unrendered`. The function's own card also gains a `*Parameters: ...*` line. |
| A produced message whose `sprintf` placeholder is a var-rooted field chain, e.g. `sprintf("container %v is privileged", [container.name])` where `container` is bound by `some container in ...` (item 9) | `producesValue` stayed `null` (a disclosed gap since session 2/3) -- the placeholder helper only ever humanized a plain `input.`-rooted chain. | Extended to any chain whose root is a KNOWN binding (`Iteration`/`Substitution`/`Parameter`, never `NonPath` or genuinely unbound) -- the variable's own name leads the bracket phrase, since unlike `input` it's real content: `[container name]`, `[rc address]`, `[rc change before region]`. Mixing one resolvable and one unresolvable placeholder in the same message still yields `null` for the whole thing (spec §6.7's existing all-or-nothing rule, untouched). |
| An object-valued rule head's own message variable, e.g. `deny contains {"msg": msg, "severity": "high"}` with `msg := "public acl"`, or the bracket-form sugar `violation[{"msg": m}]` with `m := "not prod"` (item 9) | `head.key` is a whole object term, not a bare var, so the existing `messageVar` detection (which only recognised a bare var) never matched either shape -- the message assignment fell through to the generic local-variable path and showed as a fenced `Condition.Unrendered` bullet instead of a `Produces` line. | `messageVarOf(head)` additionally recognises the pair inside an object `head.key` whose KEY is the literal string `"msg"` -- the de facto standard field name for a structured deny message across the Rego ecosystem (OPA's own style guide, Gatekeeper's ConstraintTemplate convention), a deliberate, disclosed convention choice keyed on that one literal name, not a guess about an arbitrary key. Any other object shape (no `"msg"` key, or its value isn't a bare var) stays unrecognised, exactly as before. |

Two Tier-2 goldens (`release-evidence.md`, `release-governance.md`) and the
matching `docs/sample-output/` files were regenerated deliberately, reviewed
diff-by-diff first: each gained exactly one new `*Produces:*` line (REL-002's
`stage.name`, REL-004's `window.name`), nothing else changed. Two pre-existing
`RegoCoverageAuditTest` assertions (probes 15 and 16) pinned the superseded
behaviour -- probe 15's bare "falls back" assertion, and probe 16's `` `level`
is `"critical"` `` (a function parameter silently echoed as if it were a
genuine value) -- and were updated in place to the promoted shape, per this
project's own "repin, don't leave a stale assertion" convention.

Disclosed, not fixed: a cross-package function call stays unresolved;
`Canonicalizer`'s hash is insensitive to a function's own parameter COUNT (an
unused added/removed parameter with an otherwise-identical body hashes the
same) -- `RuleGroup.parameters` is not yet part of `canonicalLogic`; a bare
`NonPath`-bound var used as a sprintf placeholder stays unhumanized (no
real-world evidence yet that it should be, unlike the Iteration/Substitution/
Parameter cases).

## Review pass: more builtins and grouped helper assignments (items 11-12)

New fixtures under `src/test/resources/realistic/builtins/` (`builtins.rego`,
`helpers.rego`), pinned by `RealisticBuiltinsAndHelpersAcceptanceIT`, plus new
`AstMapperTest.MoreBuiltinsReviewItem11`, `ExpressionRendererTest` phrasing-table
rows and operand-template cases, and `MarkdownRendererTest.GroupedHelperAssignments`.

| Finding | Was | Now |
|---|---|---|
| `net.cidr_contains(cidr, ip)` and the seven `is_*` type checks (`is_string`/`is_number`/`is_boolean`/`is_array`/`is_object`/`is_null`/`is_set`), positive and negated, in condition position (item 11) | Fell back to raw fenced source -- not in `CONDITION_BUILTINS` at all. | Added to `CONDITION_BUILTINS`; the existing generic "map every arg, fall back whole on an unsupported one" handling already covers both the 2-arg cidr check and the 1-arg type checks with no further `AstMapper` change. The 1-arg type checks needed their own `UNARY_BUILTIN_TEMPLATES` phrasing in `ExpressionRenderer` (`` `x` is a string `` / `` `x` is not a string ``, etc.) -- a genuinely different shape from every existing two-operand `BuiltinTemplate`. |
| `sum`/`max`/`min`/`to_number`/`trim_space`/`object.keys`/`time.parse_rfc3339_ns` in operand position, including nested inside `count(...)` (item 11) | Fell back to raw fenced source -- not in `OPERAND_BUILTINS`. | Added to `OPERAND_BUILTINS`; all seven are single-argument, confirmed via a real `opa parse` run, and fit the exact same promotion mechanism `count`/`lower`/`upper` already use -- no new code needed beyond the name list and a template per name. |
| Consecutive local-variable-assignment fallbacks in one body, e.g. `required := {...}; provided := {...}; missing := required - provided` (item 12) | Three separate `⚠` bullets and fenced blocks, one per assignment -- correct, but visually noisy for the extremely common "a few helper assignments feeding a later check" idiom. | `AstMapper` now gives an unsubstitutable assignment its own reason, `"helper-assignment"`, distinct from a genuinely-unresolved bare call's `"function-call"`. `MarkdownRenderer.appendConditions` groups CONSECUTIVE `helper-assignment`-reasoned conditions into one block headed "not rendered — grouped helper definition(s) shown as source" -- presentation only: the underlying `conditions` list is untouched, so `Coverage.of` keeps counting each one individually and the footer never changes. A differently-reasoned `Unrendered` immediately adjacent (e.g. `json.is_valid(...)`, still `"function-call"`) is never absorbed into the run. |

Three pre-existing `RegoCoverageAuditTest`/`AstMapperTest` assertions pinned
the pre-rename `"function-call"` reason for what are actually
assignment-fallbacks (probes 31 and 39, and a hand-built `x := count(input.y)`
case) -- repinned to `"helper-assignment"` in place; every OTHER
`"function-call"`-reasoned assertion (a bare unresolved call used directly as
a condition, never an assignment) was individually checked and left
untouched. Three existing acceptance-IT guards whose premise item 11
supersedes (`funcs.rego`'s `is_array` fallback example, and authz.md's
`is_string`/`net.cidr_contains` fallback guards) were repointed to a builtin
that stays unsupported (`json.is_valid`) or to the one construct in that card
still genuinely unsupported (`every`) -- noted at the time, not silently left
stale.

Disclosed, not fixed (explicitly out of scope by the operator's own
instruction, not an oversight): the set-difference operand (`a - b` where
both are sets, e.g. `missing := required - provided`) still has no dedicated
rendering -- `missing` stays an untraced `Operand.Variable` wherever used
bare; and a comprehension argument still forces the WHOLE enclosing
condition/operand to fall back, with no partial credit for "the rest of the
expression around the comprehension is otherwise understood."

## Review pass: presentation polish and worked-example verdicts (items 13-17)

Fixtures: `realistic/builtins/idioms.rego`, `realistic/messages/{policies,examples}/`; pinned by
`RealisticPresentationAcceptanceIT` plus unit cases in `ExpressionRendererTest`,
`MarkdownRendererTest` and `ExamplesTest`.

| Finding | Was | Now |
|---|---|---|
| `else` chains (13) | One generic "not rendered" block. | Same verbatim source, but the header states the construct's generic semantics: branches are tried in order and the first that applies wins. Nothing is claimed about the rule's own logic. |
| Coverage with helper assignments (14) | `1 of 4 conditions` with no hint why. | Unchanged figures plus a breakdown: `(including 3 helper assignments shown as source)`. The assignments are NOT removed from the denominator -- an un-understood right-hand side is still logic the card doesn't explain. |
| `count(x) == 0` / `!= 0` / `> 0` (15) | "the number of `x` is `0`". | "`x` is empty" / "`x` is not empty". Exact for everything `count` accepts. Deliberately narrow: non-negated, bare `count`, literal exactly `0`, those three operators only -- `>= 1`, `> 1`, negations, `0.0` and a `count` on the right keep the number-of wording. |
| Worked examples for object-valued messages (16) | **Crash**: `verdictFor` called `jsonPrimitive` on every set element, throwing on a Gatekeeper-style object. Also a **wrong verdict**: an empty partial-object rule (`violation[{"msg": m}]`, evaluates to `{}`) was reported as matched. | A set element is shown as its string `msg` field (else verbatim JSON, never dropped). An object whose values are all `true` is a partial-object rule: matched iff non-empty, each stringified key decoded the same way. Any other object stays an ordinary complete-rule value, verbatim. |
| Index (17) | One table, id-less rules sorted last. | Controls table, then `## Rules without a control id` with a second table of the same columns (including example coverage). No heading when there are none. |

Tier-2 `index.md` golden and `docs/sample-output/index.md` regenerated deliberately after a
diff review (heading plus table header only). Disclosed, not fixed: an empty `{}` that is a
*complete* rule's value is now treated as not matched (indistinguishable from an empty partial
object at this layer); a severity/other field on an object element is not displayed;
comprehension-argument context remains deferred (conflicts with REL-004's frozen Tier-1).

## Follow-up: a split rule's default becomes its own entry (item 14 continued)

Found re-rendering the realistic corpus after items 1-17: a `default` declaration whose rule name
has MULTIPLE sibling cards (review item 1's own promotion -- e.g. AZ-001/AZ-002, two separately
`scope: rule`-annotated `allow if {...}` bodies, plus `default allow := false`) was repeated on
EVERY sibling, and -- worse -- diffing a changed default reported N `LOGIC_CHANGED` entries (one
per sibling) each with an EMPTY source diff, since the default isn't part of any sibling's own
body source text. Fixtures: `realistic/diff-default/{old,changed,removed}`, pinned by
`RealisticDefaultsAcceptanceIT` plus unit cases in `AstMapperTest`, `MarkdownRendererTest` and
`DiffRendererTest`.

Fixed (operator's explicit choice, asked directly rather than decided unilaterally): the default
becomes ONE entry of its own -- `RuleGroup.isSplitDefaultEntry`, heading "Default for
`<pkg>.<name>`", excluded from `default` on every sibling. A name with exactly one card keeps the
default attached to that card, unchanged. `DefaultValue` gained its own `sourceLocation` so this
new entry's "defined in" line shows a real file instead of the pre-existing `?` placeholder.
`DiffRenderer`'s source diff now always prepends a synthesized `default <name> := <value>` line
(for ANY rule carrying one, split or not) ahead of its body lines, so a changed default shows a
real `-`/`+` line even when the default is still attached to a body-having card -- and the
default line sits as unchanged context when only the body changes.

## Disclosed, unchanged conventions (reviewed this session, deliberately not changed)

**Operand-level fallback (`Operand.Unrendered`) renders as plain backticked
verbatim source — visually identical to a real humanized path.** A
dynamic-keyed ref-head rule reference (`` `users_by_role.admin.u1.name` ``,
deliberately not promoted — see the ref-head promotion write-up), an
untraced non-plain-path-assigned variable, or an operand-position builtin
call the promotion above doesn't
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
round after that (`=` as pure comparison) plus former rank 1 of the round
after that (arithmetic operands) plus former rank 1 of the round after that
(`concat`) plus former rank 1 of the round after that (`null` literal
operand) plus former rank 1 of the round after that (small flat object
literal operand) plus former rank 1 of the round after that (ref-head rule
reference, exact-match only) were selected and implemented in follow-up
passes — see "Promoted this session" and "Promoted in further follow-up
sessions" above. **The frequency-ranked list is now empty** -- every ranked
item has been promoted; only the two explicitly-deferred, not-ranked items
below remain. (The negated-comparisons correctness bug that used to appear
here as an unranked, un-prioritized entry is now fixed -- see "Fixed,
follow-up session" above; it was never really part of this frequency-ranked
list to begin with, since it's a bug fix rather than a coverage promotion.)

| Rank | Construct | Proposed template | Risk |
|---|---|---|---|
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
