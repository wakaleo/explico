# Policies that document well

explico shows only what it can derive from your Rego and its METADATA, so how a policy is written decides how well it documents itself. The *Documentation quality*
section of the index, and the block `explico render` prints after the coverage line, point at what to improve; this guide says what to do about each one.
Nothing here asks you to change what a policy *means*: where a rewrite is suggested it keeps the meaning, and each one is **checked on every build** (see
[the rewrites](#rewrites-that-keep-the-meaning)). Where no such rewrite exists, the advice is to leave the policy alone.

## Say it where explico reads it

explico publishes METADATA and nothing else. An ordinary `#` comment above a rule, above the `package` line or between rules never reaches the documentation, and
the index says so (*Comments not published*, with the `file:row` of each block). Move what a reader needs into METADATA:

```rego
# METADATA
# scope: document
# title: Production changes need approval
# description: |
#   A change to production needs an approved change ticket.
# custom:
#   control-id: REL-001
#   frameworks:
#     - SOC 2 CC8.1
deny contains msg if { ... }
```

`title`, `description`, `custom.control-id` and `custom.frameworks` are what the cards, the index and the framework matrix are built from; the
*Keeping your documentation complete* section of [Writing policies](../policy-authoring.md) lists every field, and which ones the index flags when they are
missing. The key is `custom.frameworks`: a top-level `frameworks:` is dropped by `opa` itself before explico sees it.

## Give each entry point a control id

The control-level checks (description, framework, a matching example, a declared verdict) cover only a rule that has a `control-id`. A policy set without ids gets none
of them, so the index lists the **rules with no control id that no other rule uses**: the entry points, such as the `deny` or `violation` of each file.

- Add `custom.control-id` to the entry point's METADATA, or
- derive ids from file names with `--control-id-from` (and add `--group-by-control` when a file also holds helper rules, so the file is one control rather than every
  helper counting as one).

Rules named `test_*` (OPA's own unit-test convention) are neither entry points nor uses of the rule they exercise, but a test file in the directory you render still
gets cards of its own, so render the policies without their tests if you do not want them in the documentation.

## Declare what a decision object means

A rule that returns an object (a decision) shows its worked examples as raw JSON unless you say which fields are the verdict. The index lists such a rule under
*Raw decision object* when you give example fixtures. Declare it once, in METADATA:

```rego
# custom:
#   verdict:
#     outcome: compliant
#     reason: commentary
```

The examples then read "compliant" or "not compliant" with the reason. explico reads only the field names you declare and never guesses a meaning; the other
projections (`actions`, `annotations`, `timing`, `mask`) work the same way and are described in [Writing policies](../policy-authoring.md).

## Reading "shown as source"

The index groups every statement explico could not render by reason, with the `file:row` of each, and says in one sentence what explico needs. The
[constructs reference](../reference/constructs.md) lists what renders and what does not, with the reason shown for each construct that does not. Two things to know:

- A **condition** shown as source (`every`, `else-chain`, `with-override`, `function-call`, `package-not-loaded`, `helper-assignment`, `partial-rule-reference`, `comprehension`) is
  verbatim and marked, and it lowers the coverage percentage. `package-not-loaded` means a call into a package that is imported but not among the rendered policies: render the
  whole policy tree together (point explico at the parent directory, not one policy directory) so the helper packages are loaded and the call can be resolved.
- A **value** shown as source (*Values shown as source*) sits inside a condition that did render. It does not lower the percentage, and it has no reason code, so
  the index lists the exact text and where it occurs. A value that recurs is the best lead: it is usually one pattern in your policy.

## Rewrites that keep the meaning

Most statements shown as source are limits of explico, not mistakes in your policy, and many have no equivalent form: leave those. Two common patterns do have one.
Each pair below is checked on every build: the first falls back exactly as stated, the second renders completely, and both give the same answer on the sample
inputs listed, evaluated by `opa`. Whether the rewrite is right for *your* policy is your call; the reasoning is given so you can judge it.

The pairs share these definitions:

<!-- guide-prelude -->
```rego
package guide

import rego.v1

kinds := ["Role", "ClusterRole"]

ok(x) if x.ok
```

### A constant list indexed with `[_]`

<!-- guide-check: name=kinds value=kinds[_] inputs=[{"kind":"Role"},{"kind":"ClusterRole"},{"kind":"Pod"},{}] -->

`input.kind == kinds[_]` renders, but the value `kinds[_]` is shown as source. For a list or a set, `x in kinds` is true in exactly the same cases, and it renders
as "is one of rule `kinds`" with a link to the rule. The constant stays defined in one place.

**Shown as source (the value `kinds[_]`):**

```rego
kinds_before if { input.kind == kinds[_] }
```

**Renders completely:**

```rego
kinds_after if { input.kind in kinds }
```

### A count of a comprehension compared with zero

<!-- guide-check: name=count reason=comprehension inputs=[{"xs":[{"ok":true}]},{"xs":[{"ok":false}]},{"xs":[]},{}] -->

A comprehension that calls a function or refers to a rule is shown as source whole (explico never renders half of one). When all you ask is "is there at least
one such element", `count([x | ...]) > 0` and `some x in ...` are the same question: the comprehension has an element exactly when some `x` satisfies the body, and when
`xs` is undefined both are false. The `some` form renders, with a link to the function.

**Shown as source (the condition, reason `comprehension`):**

```rego
count_before if { count([x | some x in input.xs; ok(x)]) > 0 }
```

**Renders completely:**

```rego
count_after if { some x in input.xs; ok(x) }
```

## When to leave it

`with`, an `else` chain with a branch explico cannot render, a destructuring unification, an `object.get` with a computed key and a builtin explico does not model have
no general rewrite that keeps the meaning and renders (a particular policy may have one; this guide only lists what can be checked), and rewriting them just to suit the
documentation would change the policy. They are shown as marked verbatim source on purpose: that is the honest rendering, and it is never wrong. The sentences in the index say what explico needs; the
[Rego coverage audit](../rego-coverage.md) records what has been promoted and what has not.

## Make the examples tell the story

- **Name a constant that has a unit.** A bare `86400000000000` in a condition is faithful and unreadable. With `one_day_ns := 86400000000000` as a rule and the condition
  written against it, the same card reads:

  ```markdown
  - the current time minus the time `screened at` is greater than `86400000000000`
  - the current time minus the time `screened at` is greater than rule [`one_day_ns`](#v-one-day-ns)
  ```

  (the first line is the bare number, the second the named constant: both are real output). The constant has a card of its own that shows its value, and its
  METADATA `description` if you give it one, so put the unit there.
- **Write at least one fixture for each situation.** A control passes the *No matching example* finding as soon as one fixture matches it; the *Situation without a
  matching example* finding names the situations no fixture triggers. A situation with no example is the one part of the card nobody has seen hold.
- **Expect an unstable example for a rule that reads the clock.** A rule that uses `time.now_ns()` is evaluated against the real time when the documentation is
  generated, so its example verdict can change between two renders. Give the fixture a timestamp far enough in the past or future that the verdict cannot flip.

## Make the gate follow

Once the documentation is where you want it, hold it there: `--fail-on-gaps` with `--require title,description,frameworks,fixture,verdict` fails the build when a
control loses a property. See [the CI recipes](ci-recipes.md#ratchet-the-documentation-quality) for how to add one property at a time.
