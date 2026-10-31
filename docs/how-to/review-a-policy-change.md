# Review a policy change

**Goal:** give a reviewer a plain-language account of what a change to the Rego does, and optionally stop the merge when it matters.

Check out the base version (for example `main`) into one directory and the proposed version into another, then:

```sh
java -jar explico.jar diff old-policies new-policies --out change.md --examples examples
```

stdout shows the summary table and the sections a reviewer reads first:

```markdown
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

`change.md` holds the rest: the new card for each changed control, a table of documentation changes, and a source diff of the Rego.

## How controls are matched

By **control id** (`custom.control-id`), or, with no id, by package and rule name. A rename that keeps the control id is not a change. A control whose
logic differs is `LOGIC_CHANGED`; one whose title, description or other METADATA differs is `DOCS_CHANGED`; formatting and comment edits are
`UNCHANGED`. Two controls sharing an id make the comparison ambiguous (exit `4`); with a deliberately split control, add `--group-by-control`.

## Make the report say which versions

```sh
java -jar explico.jar diff old-policies new-policies --out change.md --old-label v1.0 --new-label v1.1
```

```markdown
**Compared**

- Old (v1.0): `sha256:b14cde4048dc…`
- New (v1.1): `sha256:3436c9f41ce4…`
```

The digests are of the policy files. They are the same ones `index.md` shows for a render.

## Include reference data

If a rule reads plain `data.*` documents, a change to that data is a change in behaviour with no change in Rego. Pass both versions of the data:

```sh
java -jar explico.jar diff old-policies new-policies --out change.md --old-data old-data --new-data new-data
```

A control whose referenced data differs is reported as `LOGIC_CHANGED`, with the change named.

## Stop the merge

All of these write the report first, then exit `5`:

| Flag | Fails when |
|---|---|
| `--fail-on LOGIC_CHANGED` | any control is in a listed category (`ADDED`, `REMOVED`, `LOGIC_CHANGED`, `DOCS_CHANGED`) |
| `--fail-on-example-changes` | any of your fixtures (`--examples`) changes outcome between the two versions |

```text
explico: gate failed (--fail-on LOGIC_CHANGED): 1 control(s): DB-001
```

A gate is a tripwire for "someone must look at this", not a verdict. explico reports that behaviour moved; it does not decide whether
stricter or looser is what you wanted.

## Related

[From Rego to living documentation: what a change looks like in review](../guides/living-documentation.md#6-what-a-change-looks-like-in-review) ·
[CI recipes: diff a pull request against its base](../guides/ci-recipes.md#review-a-policy-change-diff-the-pull-request-against-its-base)
