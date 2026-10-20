# True by construction

**Every statement in explico's output is true by construction.** It renders only what it can derive mechanically from the Rego
syntax tree and the policy's METADATA annotations. It never paraphrases, infers intent or guesses.

That is a stronger claim than "usually accurate", so it is worth saying exactly what it does and does not cover.

## What it covers

- **The conditions.** A line such as `` `deployment ▸ environment` is `"production"` `` is produced by a fixed template from a
  recognised piece of Rego (here, an equality between a path and a string). The same Rego always yields the same sentence.
- **The structure.** "All of the following are true" and "The rule matches when ANY of the following situations applies" are the
  literal meaning of a rule body and of several bodies of the same rule.
- **The worked examples.** Each verdict (allowed or denied, which situation, which message) comes from a real `opa eval` of your
  policy on your fixture. explico never predicts a verdict.
- **The numbers.** Coverage is counted, not estimated.

## What it does not cover

- **METADATA is human-attested.** The title, description, control id and frameworks are whatever the policy author wrote. explico
  shows them faithfully; it cannot verify that the description matches the logic.
- **Fixtures are yours.** A worked example shows what the policy does on that input, not that the input is realistic.
- **Intent.** explico says what a rule checks, never whether it is a good control.

## The fallback: what happens to anything it cannot render faithfully

When explico meets a construct it has no faithful template for, it does not describe it. It shows the **verbatim Rego source**, in
a block marked with a warning sign:

````markdown
- ⚠ **not rendered — shown as source:**

  ```rego
  count({r | some r in input.regions; net.cidr_is_valid(r)}) > 3
  ```
````

That block is counted against the rendering coverage, so a card full of them reads as the unfinished picture it is. Each one carries a
machine-readable reason (for example `function-call`, `helper-assignment`, `every`, `comprehension`, `with-override`, `else-chain`),
available in the [JSON output](../json-output.md), so you can rank what is costing you coverage.

Two rules govern the fallback:

- **When in doubt, fall back.** A construct is rendered only when explico can do so without changing its meaning.
- **All or nothing.** A comprehension or an `every` block is rendered only if everything inside it is. One part explico cannot render
  keeps the whole block as source; it never shows half of one.

## Why this matters for compliance

A control document is only as trustworthy as its least reliable sentence. Because explico either states something mechanically
derived or shows the source, a reader can use the card as evidence without wondering whether the prose drifted from the code. And
because the output is deterministic (the same policies give byte-identical documents), a change in the rendered text is always a
change in the policy.

See [Reading a card](reading-a-card.md) for what each part of a card means, and [Coverage](coverage.md) for how the percentage is
counted.
