# Document existing policies

**Goal:** render a policy library you did not write: it may use the older Rego syntax, carry no METADATA, and keep one control per file.

## 1. Tell explico the Rego version

explico targets Rego v1 (`if`, `contains`). Run it on older code and `opa` rejects the syntax, so the command exits `2` and prints `opa`'s own message:

```text
"message": "`if` keyword is required before rule body",
```

Add `--rego-version v0`. explico never guesses the version, because a wrong guess would silently mis-parse. A directory is one version or the other.

## 2. Derive control ids from file names

Suppose the files are named for their controls:

```text
legacy/ctrl_101_no_root.rego
legacy/ctrl_102_resource_limits.rego
```

`--control-id-from` takes a regular expression with a named group `id`; `--control-id-template` says how to write the result:

```sh
java -jar explico.jar render legacy --rego-version v0 --out legacy-docs \
  --control-id-from 'ctrl_(?<id>\d+)_' --control-id-template 'CTRL-{id}'
```

```text
Rendering coverage: 3 of 3 conditions (100%)
Documentation quality (see index.md):
  - 2 controls have no description
  - 2 controls name no framework
```

`index.md` lists them as controls:

```markdown
| Control ID | Title | Package | Rule | Rendered | Source file |
|---|---|---|---|---|---|
| CTRL-101 | [deny](k8s-noroot.md#ctrl-101) | `k8s.noroot` | `deny` | 100% | `ctrl_101_no_root.rego` |
| CTRL-102 | [deny](k8s-limits.md#ctrl-102) | `k8s.limits` | `deny` | 100% | `ctrl_102_resource_limits.rego` |
```

Things to know:

- Only the **file's base name** is matched, not its directory.
- **METADATA's `control-id` always wins**, so you can migrate a file at a time.
- A derived id is marked as such on the card and in the index legend: explico never presents it as something an author attested.
- These controls have no description or framework, so the quality section lists them. That is accurate. Use it as your to-do list, or leave it.
  `--fail-on-gaps` would fail until they are filled in.

## 3. One control spread over several rules

If several rules share an id (a file with many rules, or the same id in two files), add `--group-by-control` to give the control **one page**,
`controls/<id>.md`, instead of cards scattered over package pages. `diff` needs the same flag, otherwise a shared id is an error (exit `4`).

## 4. Add METADATA gradually

Nothing requires METADATA. A rule without it renders from its conditions alone, with the heading `deny` and the note
*No description provided in policy metadata.* Add titles and descriptions to the controls that matter first; the index's quality section tells
you which ones are missing. [Writing policies](../policy-authoring.md) lists every field.

## Related

- Unsupported constructs appear as marked source, never as a guess: [Coverage](../concepts/coverage.md).
- Real-world libraries (Gatekeeper, Trivy, Regal, conftest) and what share of them renders: [Rego coverage audit](../rego-coverage.md).
