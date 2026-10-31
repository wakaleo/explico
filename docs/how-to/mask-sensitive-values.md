# Keep sensitive values out of examples

**Goal:** use realistic fixtures (real-looking names, e-mail addresses, ids) while keeping those values out of the published documents.

Declare the input paths to hide in the rule's METADATA, under `custom.mask`:

```rego
# METADATA
# scope: document
# title: Production databases are backed up
# custom:
#   control-id: DB-001
#   mask:
#     - database.name
deny contains msg if {
	...
}
```

Paths are keys *inside the fixture's `input`* (no `input.` prefix), separated by dots. `[]` after a key means every element of an array
(`people[].email`).

Rendered with the same fixtures as before, the produced messages hide the name, wherever it occurs:

```markdown
- **production database keeping backups for a week** — ❌ denied *(Situation 2)*
  *"database \*\*\* keeps backups for only 7 days"*
```

How it behaves:

- **Verdicts stay true.** The real input is evaluated; only the display is masked. Masking applies to the `path: value` bullets, the produced messages, a
  declared verdict's reason, the fixture's name and description, the raw value, `explico.json` and generated examples.
- **It is per rule.** Another rule that does not declare the path shows its values.
- **It fails closed.** A malformed `mask` is an error (exit `1`, nothing written), never a warning: ignoring it would publish what it was written to hide.
- **Only values present in the fixture can be hidden.** A value derived by the policy, or read from `--data`, is not in the fixture and is not masked.
- **Matching is deliberately generous.** A masked string is hidden in any letter case, in its JSON-escaped form, and even inside a longer word. That errs
  towards hiding too much, so masking `al` also turns `also` into `***so`.

Treat masking as a convenience for documents, not a data-protection control. Prefer fixtures that never contained real personal data.
The full rules are in [Writing policies: keeping values out of the documentation](../policy-authoring.md).
