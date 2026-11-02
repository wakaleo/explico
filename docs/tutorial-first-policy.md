# Tutorial: document your first policy

The [other tutorial](tutorial.md) walks through a finished sample. This one starts from an empty directory and builds up documentation for a
single control, one step at a time. Every command and every piece of output below comes from a real run.

You will: render a rule, see a value explico cannot render and fix it, describe the control in METADATA, add worked examples, set a quality
gate, and review a change with `diff`. About 30 minutes.

Before you start, finish the [Quickstart](quickstart.md); it creates `policies/backups.rego` with two rules. The commands below use a shorthand
you can paste into your shell:

```sh
alias explico='java -jar explico.jar'
```

## 1. Add a third rule that uses a shared list

Add a list of retired engines and a rule that uses it. The whole file now reads:

```rego
package databases

retired_engines := ["mongodb-3", "mysql-5"]

deny contains msg if {
	input.database.environment == "production"
	not input.database.backup.enabled
	msg := "production databases must have backups enabled"
}

deny contains msg if {
	input.database.environment == "production"
	input.database.backup.retention_days < 30
	msg := sprintf("database %v keeps backups for only %v days", [input.database.name, input.database.backup.retention_days])
}

deny contains msg if {
	input.database.engine == retired_engines[_]
	msg := sprintf("database %v runs a retired engine", [input.database.name])
}
```

```sh
explico render policies --out policy-docs
```

(`--out` is emptied before each render, so keep it dedicated to generated documents.)

```text
Rendering coverage: 5 of 5 conditions (100%)
Documentation quality (see index.md):
  - 1 rule has no control id and is not used by another rule
  - 1 value is shown as source inside a rendered condition
```

Coverage is 100% (all five conditions were understood), but the second advice line is new. In `policy-docs/databases.md`, Situation 3 reads:

```markdown
- `database ▸ engine` is `retired_engines[_]`
```

`retired_engines[_]` is Rego for "any element of that list". explico does not paraphrase what it cannot prove it understood, so it shows
the value as source. That statement is true, but it reads like code.

## 2. Rewrite it so it renders

For a list, `x in list` is true in exactly the same cases as `x == list[_]`. Change the third rule:

```rego
deny contains msg if {
	input.database.engine in retired_engines
	msg := sprintf("database %v runs a retired engine", [input.database.name])
}
```

Render again, and the line becomes a statement and a link:

```markdown
- `database ▸ engine` is one of rule [`retired_engines`](#databases-retired-engines)
```

The same question now has a readable answer, and the list stays defined in one place. Not every construct has an equivalent rewrite, and
you should not distort a policy to please a renderer; [Policies that document well](guides/policies-that-document-well.md) lists the rewrites
that are provably safe and says when to leave a fallback alone.

## 3. Describe the control in METADATA

Right above the first `deny`, add an OPA METADATA block. It is standard OPA syntax, so `opa` itself understands it; explico just reads it.

```rego
# METADATA
# scope: document
# title: Production databases are backed up
# description: |
#   Every production database must have backups switched on and keep them
#   for at least 30 days, and must not run a retired engine.
# custom:
#   control-id: DB-001
#   frameworks:
#     - SOC 2 A1.2
deny contains msg if {
```

`scope: document` makes the block apply to every `deny` rule in the file (they are one control). `control-id` gives the control a stable
identity, which `diff` relies on later. Render, and the card heading becomes `## DB-001 — Production databases are backed up`, with the
description and `*Frameworks: SOC 2 A1.2*` beneath it. METADATA is text *you* wrote and explico copies; it is not checked against the rule.
See [Writing policies](policy-authoring.md) for every field.

## 4. Add worked examples

A card says what a rule tests. Examples show what it does. Create `examples/` with one JSON file per scenario, each with a `name` and the
`input` to evaluate:

```json
{
  "name": "production database without backups",
  "input": { "database": { "name": "orders", "environment": "production", "engine": "postgres-16", "backup": { "enabled": false, "retention_days": 0 } } }
}
```

That is `examples/01-no-backups.json`. Add three more in the same shape, so the examples below match what you see:

```json
{
  "name": "production database keeping backups for a week",
  "input": { "database": { "name": "billing", "environment": "production", "engine": "postgres-16", "backup": { "enabled": true, "retention_days": 7 } } }
}
```

(`02-short-retention.json`)

```json
{
  "name": "production database backed up for 35 days",
  "input": { "database": { "name": "ledger", "environment": "production", "engine": "postgres-16", "backup": { "enabled": true, "retention_days": 35 } } }
}
```

(`03-compliant.json`)

```json
{
  "name": "production database on a retired engine",
  "input": { "database": { "name": "archive", "environment": "production", "engine": "mysql-5", "backup": { "enabled": true, "retention_days": 120 } } }
}
```

(`04-retired-engine.json`)

Name each scenario by what it is, not by its data. Then:

```sh
explico render policies --examples examples --out policy-docs
```

The card gains a **Worked examples** section. The verdicts and messages are not written by explico: `opa eval` produced them.

```markdown
- **production database keeping backups for a week** — ❌ denied *(Situation 2)*
  *"database billing keeps backups for only 7 days"*
  - `database ▸ environment`: `"production"`
  - `database ▸ backup ▸ enabled`: `true`
  - `database ▸ backup ▸ retention days`: `7`
  - `database ▸ engine`: `"postgres-16"`
- **production database backed up for 35 days** — ✅ allowed
```

The *(Situation 2)* label says which body produced the message, matched from the message text. `index.md` now shows `✓ / ✓` in *Example
coverage*: at least one example where the rule matches and one where it does not.

No fixtures yet for a rule? `--generate-examples` derives candidates from its conditions and shows only those `opa` confirms; see
[Add worked examples](how-to/add-worked-examples.md).

## 5. Gate the documentation quality

Make the build fail when documentation slips:

```sh
explico render policies --examples examples --out policy-docs \
  --fail-under 100 --fail-on-gaps --must-have title,description,frameworks,fixture
echo "exit $?"
```

```text
Rendering coverage: 5 of 5 conditions (100%)
exit 0
```

Delete the `description` from the METADATA and the same command fails:

```text
Rendering coverage: 5 of 5 conditions (100%)
Documentation quality (see index.md):
  - 1 control has no description
explico: gate failed (--fail-on-gaps): 1 gap(s)
  - control DB-001 has no description
exit 5
```

The documents are still written; a gate never suppresses output. [CI recipes](guides/ci-recipes.md) wire this into GitHub Actions and GitLab.

## 6. Review a change

Copy the policies, then raise the retention threshold from 30 to 90 days (in the rule and in the description) in the copy:

```sh
cp -r policies policies-next
# edit policies-next/backups.rego
explico diff policies policies-next --out change.md --examples examples
```

stdout shows the summary table and the first two sections of the report:

```markdown
## Summary

| Category | Count |
|---|---|
| REMOVED | 0 |
| ADDED | 0 |
| LOGIC_CHANGED | 1 |
| DOCS_CHANGED | 0 |
| UNCHANGED | 1 |

## What changed

- [DB-001 — Production databases are backed up](#db-001) — logic changed
  - condition removed: `database ▸ backup ▸ retention days` is less than `30`
  - condition added: `database ▸ backup ▸ retention days` is less than `90`
  - documentation changed: description

## Behaviour on your examples

- [DB-001 — Production databases are backed up](#db-001)
  - production database backed up for 35 days: ✅ allowed -> ❌ denied

*Compared 2 of 2 controls present in both versions.*
```

The last section is what makes the review. A one-character change turned your own "compliant" scenario into a denial, and the report says
so in your words. explico reports that the outcome changed; it does not say whether that is what you wanted. `change.md` also holds the full
new card and a source diff of the rule.

To make a pipeline stop on such changes, add `--fail-on LOGIC_CHANGED` or `--fail-on EXAMPLE_CHANGES` (or both: `--fail-on LOGIC_CHANGED,EXAMPLE_CHANGES`).

## What you built

A directory of policies with a control id, a description, a framework, four examples and a coverage gate, a rendered set of documents you
can commit, and a diff that explains a change to a reviewer.

| Next | |
|---|---|
| Publish and keep it current | [From Rego to living documentation](guides/living-documentation.md) |
| Run it in a pipeline | [CI recipes](guides/ci-recipes.md) |
| Single tasks | [How-to guides](how-to/index.md) |
