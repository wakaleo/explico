# Add worked examples

**Goal:** show, on each card, what the rule actually does with realistic inputs, with verdicts and messages produced by `opa`, not by explico.

## Write a fixture

One JSON file per scenario in a directory of your choice (`examples/` here). Each has a `name` and the `input` document the rule reads:

```json
{
  "name": "production database keeping backups for a week",
  "input": { "database": { "name": "billing", "environment": "production", "engine": "postgres-16", "backup": { "enabled": true, "retention_days": 7 } } }
}
```

An optional `description` documents the fixture for whoever maintains it; the card shows the `name`. Files are read in file-name order, and two
fixtures must not share a `name` (exit `4`).

```sh
java -jar explico.jar render policies --examples examples --out policy-docs
```

Each card gains a **Worked examples** section; a card shows at most three matching and two non-matching examples (a `Showing M of N examples` line says when it leaves some out), and the index's
*Example coverage* column counts them all.

- **Name the scenario, not the data.** "production database keeping backups for a week" tells a reader what is covered; "test-7" does not.
- **Cover both directions**: at least one input where the rule matches and one where it does not. `✓ / ✓` in the index means you did.
- **Rules that read `data`**: pass `--data <file-or-dir>` so `opa eval` has the same data your service does.
- The input must have the shape the rule reads. The `path: value` bullets under each example list the fields the rule refers to, so a field
  missing from your fixture shows as `absent` there.

## Have explico propose examples

For rules you have not written fixtures for, add `--generate-examples`:

```sh
java -jar explico.jar render policies --out policy-docs --generate-examples
```

```text
Rendering coverage: 5 of 5 conditions (100%)
Generated examples for 1 of 1 rules
```

The card gains a separate **Generated examples** section:

```markdown
**Generated examples**

- **satisfies every condition of Situation 2** — ❌ denied
  *"database sample keeps backups for only 29 days"* *(Situation 2)*
  *"production databases must have backups enabled"* *(Situation 1)*
```

What this does and does not mean:

- Candidates come from the rule's conditions, and **each is verified with `opa eval`**; a candidate `opa` does not confirm is never shown.
- Names are mechanical (*satisfies every condition of Situation N*, *breaks only: …*). explico does not invent business meaning.
  Values like `sample` and `29` are chosen to satisfy the conditions. They illustrate the rule; they are not scenarios its author chose.
- It **runs your policy** (that is what `opa eval` does). Do not use it on policies you would not evaluate. See [Security considerations](../user-guide.md#security-considerations).
- Author fixtures always count towards the index's *Example coverage*; generated ones never do.
- A rule with no solvable shape (an `else` chain, say) simply gets none.

## Make object-valued decisions readable

If a rule returns an object (`{"allow": false, "reason": "…"}`), examples show raw JSON unless you declare which fields are the verdict with
`custom.verdict`. See [Declaring a verdict for an object-valued rule](../policy-authoring.md#declaring-a-verdict-for-an-object-valued-rule).

## Related

[Policies that document well](../guides/policies-that-document-well.md) · [What the examples do and do not prove](../guides/living-documentation.md#8-what-the-examples-do-and-do-not-prove)
