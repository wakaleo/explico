# Quickstart: your first rendered rule in five minutes

You will write one small Rego rule, render it, and read the result. Nothing here needs a project, a build tool or a configuration file.

You need a JDK (17 or newer), the `opa` binary (1.x) on your `PATH`, and the explico jar. [Install and first run](install.md) has the details; this checks them:

```sh
java -jar explico.jar version      # explico 0.4.0
opa version                        # Version: 1.x.y
```

(No `opa`? `java -jar explico.jar demo --fetch-opa` downloads a pinned, checksum-verified one for the demo. For your own policies, install `opa` itself.)

## 1. Write a rule

Make a directory `policies/` and put this in `policies/backups.rego`:

```rego
package databases

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
```

## 2. Render it

```sh
java -jar explico.jar render policies --out policy-docs
```

!!! warning "`--out` is replaced"
    `render --out <dir>` deletes the directory and writes it again, so output never goes stale. explico only does that to a directory that is missing, empty, or
    marked `.explico-output` (every directory it writes carries that file). Any other non-empty directory is refused with an error, and nothing is deleted; `--force`
    overrides that. Nothing overrides the safety refusals: `--out` may not be, or contain, your policies, `--examples` or `--data`, the working directory, your
    home directory or a filesystem root.

```text
Rendering coverage: 4 of 4 conditions (100%)
Documentation quality (see index.md):
  - 1 rule has no control id and is not used by another rule
```

`policy-docs/` now holds `index.md` and `databases.md`. The second line is advice, not an error: the rule has no control id yet, which the
[tutorial](tutorial-first-policy.md) adds.

## 3. Read the card

Open `policy-docs/databases.md`:

```markdown
**Effect of a match:** ❌ denied *(by the `deny` naming convention)*

**The rule matches when ANY of the following situations applies:**

### Situation 1 — all of the following are true

- `database ▸ environment` is `"production"`
- `database ▸ backup ▸ enabled` is absent or false

*Produces:* "production databases must have backups enabled"

### Situation 2 — all of the following are true

- `database ▸ environment` is `"production"`
- `database ▸ backup ▸ retention days` is less than `30`

*Produces:* "database [database name] keeps backups for only [database backup retention days] days"

*Rendering coverage: 4 of 4 conditions*
```

Each `deny` body became one *situation*; the conditions inside it are all required; the rule matches when any situation does. Every sentence is
derived from the Rego syntax tree, so it is true by construction. Anything explico cannot say faithfully is shown as marked source instead, and
the coverage line counts it. [Reading a card](concepts/reading-a-card.md) explains every part.

## Where next

| You want to… | Go to |
|---|---|
| Turn this into a documented control, with examples and a CI check | [Tutorial: document your first policy](tutorial-first-policy.md) |
| Do one specific thing (link to source, hide values, gate a pipeline…) | [How-to guides](how-to/index.md) |
| Understand what the output means | [Concepts](concepts/true-by-construction.md) |
| Something failed | [Troubleshooting](troubleshooting.md) |
