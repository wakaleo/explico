# Link cards to source

**Goal:** every card's "defined in" line links to the exact Rego line, pinned to the commit the documents were built from, so a reader can
check the plain-language text against the code.

## Link to your repository

`--source-url` takes a URL template. `{file}` (required) is the policy file's path relative to the policy directory; `{line}` is the rule's line.

```sh
java -jar explico.jar render policies --out policy-docs \
  --source-url 'https://github.com/acme/policies/blob/3f2c1ab/policies/{file}#L{line}'
```

The card line becomes:

```markdown
*Rule `deny` in package `databases` — defined in [`backups.rego:15`](https://github.com/acme/policies/blob/3f2c1ab/policies/backups.rego#L15)*
```

The links also appear on package pages, in the index's list of places where source is shown, and in `explico.json`.

**Pin to a commit, not a branch.** A link to `main` drifts as the file changes and stops matching the text beside it. In CI, build the template from the commit:

```sh
--source-url "https://github.com/${GITHUB_REPOSITORY}/blob/${GITHUB_SHA}/policies/{file}#L{line}"
```

Path segments are percent-encoded, so a file name with spaces or `#` still gives a working link.

A template without `{file}` is a usage error (exit `1`), checked before any work is done:

```text
Error: invalid value for --source-url: the source URL template must contain {file} (the policy file's path), e.g. https://host/org/repo/blob/COMMIT/policies/{file}#L{line}
```

## Add a second link to the owning module

Policies are often vendored from another repository. `--companion-url` adds a second link on each card, with `{file}`, `{package}` and `{line}`
available (it needs `{file}` or `{package}`), and `--companion-label` names it (default `module`):

```sh
java -jar explico.jar render policies --out policy-docs \
  --source-url 'https://github.com/acme/policies/blob/3f2c1ab/policies/{file}#L{line}' \
  --companion-url 'https://github.com/acme/modules/blob/main/{package}/{file}#L{line}' \
  --companion-label module
```

```markdown
*Rule `deny` in package `databases` — defined in [`backups.rego:15`](https://github.com/acme/policies/blob/3f2c1ab/policies/backups.rego#L15) · [module](https://github.com/acme/modules/blob/main/databases/backups.rego#L15)*
```

The label is plain text: no `[ ] | ` * ` and no line break, so it can never break the line it sits on. The companion link is on cards and package
pages, not on the index or in diff reports.

## Related

[CI recipes: publish the commit-pinned documents](../guides/ci-recipes.md#github-actions-render-gate-and-publish-the-documents)
