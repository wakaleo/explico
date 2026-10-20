# JSON output

explico can emit its result as JSON for tools that should not parse Markdown (dashboards, GRC tools, CI scripts). It is **opt-in**,
and everything else about the output is unchanged when it is off.

```
explico render policies --out docs --json            # also writes docs/explico.json
explico diff old new --out report.md --json report.json
```

Library: `Explico.render(policySet, policyDir, examples, dataDir, generateExamples, json = true)` adds `explico.json` to
`RenderedDocs.files`; `Explico.diff(old, new).json` always holds the change report's JSON.

## What it promises

- **The same facts as the Markdown, not a second interpretation.** Every condition's `text` is the exact phrase the card shows
  (including its Markdown inline-code backticks), minus links. A construct explico could not render is `unrendered` with its
  verbatim `source` and a machine-readable `reason`, never prose. Coverage, the policy digest, effect of a match, worked-example
  verdicts and reference data come from the same model the Markdown is built from.
- **More than the card where the card summarises:** `workedExamples` lists *every* fixture (the card shows up to 3 matching + 2
  non-matching), and `referenceData.value` is the *full* resolved value (the card cuts long arrays after 5).
- **Deterministic:** keys are written in the order below, two-space indented, one trailing newline; the same input is byte-identical.
- **Versioned:** `schema` is `explico/render/1` or `explico/diff/1`. A breaking change gets a new number; fields may be *added* to a
  version, so tolerate unknown keys.

## `explico/render/1`

| Key | Type | Meaning |
|---|---|---|
| `schema` | string | `"explico/render/1"` |
| `policyDigest` | string | SHA-256 (64 hex) over the `.rego` files; the value shown on `index.md` |
| `coverage` | object | `{rendered, total, percent}` over every condition |
| `quality` | object | Documentation-quality findings over controls: `missingDescription` and `noFramework` (arrays of control ids) and `duplicateTitles` (`[{title, controls}]`). Always present; empty arrays when clean |
| `requirements` | object or null | Null unless a requirements list was supplied. Otherwise `total`, `covered`, `items` (`[{requirement, controls}]` in the list's order; `controls` are control ids, or the rule name for a rule with no control id) and `unlisted` (framework strings used by the policy that are not on the list) |
| `packages` | array | One entry per package document, sorted by `path` |

**Package:** `path`, `file` (its Markdown document), `title`, `description` (package METADATA, or `null`), `authors`, `relatedResources`,
`custom` (package METADATA, same shapes as a rule's), `coverage`, `rules` (in card order).

**Rule:**

| Key | Type | Meaning |
|---|---|---|
| `name` | string | The Rego rule name |
| `controlId`, `title`, `description` | string or null | From METADATA |
| `anchor` | string or null | The card's anchor id in the package document (`#rel-001`); null for a duplicate that lost the claim |
| `sourceFile` | string or null | The `.rego` file (relative to the policy directory) |
| `sourceLine` | integer or null | The rule's first line in that file (its first body, else its `default`, else its constant value) |
| `sourceUrl` | string or null | The link to that line when a `--source-url` template was given, else null |
| `frameworks` | string[] | METADATA `custom.frameworks` |
| `authors` | `{name, email}`[] | METADATA `authors`; either field may be null |
| `relatedResources` | `{ref, description}`[] | METADATA `related_resources` |
| `custom` | object | Other scalar `custom:` keys, sorted; a list is comma-joined; structured values are omitted |
| `parameters` | string[] | A function's parameter names |
| `effectOfMatch` | `"denied"`, `"allowed"` or null | By the `deny`/`violation`/`allow` naming convention only; null for any other rule |
| `default`, `constantValue` | string or null | The rule's `default` outcome / constant value, as the card shows it |
| `isSplitDefaultEntry` | boolean | True for the separate "Default for ..." card |
| `coverage` | object | `{rendered, total, percent}` for this rule |
| `bodies` | array | Each body: `conditions`, `produces` (the message, or null), `yields` (a conditional rule's value, or null) |
| `referenceData` | array | `data.*` documents the rule reads: `path` (`data.release.exempt_services`), `breadcrumb`, `defined`, `value` (OPA's resolved value, or null when undefined). **Empty unless a data directory was supplied** |
| `workedExamples` | array | Every author fixture, in fixture order: `name`, `matched`, `outcome` (`"❌ denied"`), `messages`, `situations` (body numbers; null when attribution is impossible) |
| `generatedExamples` | array | Only with `generateExamples`: the same shape plus `generated: true`, named as on the card |

**Condition:** `kind` (`comparison`, `membership`, `truthy`, `builtinCall`, `someIn`, `every`, `ruleReference`, `functionCall`,
`unrendered`), `rendered` (boolean), `text` (the card phrase, or null when unrendered), `source` and `reason` (set only when
unrendered), `link` (`{package, rule}` for a rule reference or function call, else null), and for an `every` its nested `conditions` (same shape; `text` is the head phrase).

## `explico/diff/1`

| Key | Type | Meaning |
|---|---|---|
| `schema` | string | `"explico/diff/1"` |
| `summary` | object | Counts for `REMOVED`, `ADDED`, `LOGIC_CHANGED`, `DOCS_CHANGED`, `UNCHANGED` |
| `changes` | array | Every non-`UNCHANGED` control, in report order (removed, added, logic changed, docs changed; then control id) |

**Change:** `category`, `controlId`, `label` (e.g. `"REL-003 — Artifact provenance"`), `package`, `rule`, `anchor` (null for
`DOCS_CHANGED`, which has no card), and either `details` (a `LOGIC_CHANGED` control: the exact sub-bullets of the Markdown *What
changed* section) or `fields` (a `DOCS_CHANGED` control: which of `title`, `description`, `frameworks`, `authors`,
`related resources`, `custom fields` differ).

## Notes

- The JSON describes policy documentation, not policy behaviour: the same disclaimer as the Markdown applies.
- `referenceData` values and `workedExamples` messages are copied from your data and fixtures verbatim — treat the file as you
  treat the Markdown (see [Security considerations](user-guide.md#security-considerations)).
