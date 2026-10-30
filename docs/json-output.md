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
| `quality` | object | Documentation-quality findings over controls: `missingDescription`, `noFramework`, `noMatchingExample` and `rawDecisionObject` (arrays of control ids) and `duplicateTitles` (`[{title, controls}]`). `noMatchingExample` (a control no fixture makes match) and `rawDecisionObject` (a control with no declared verdict whose value is a JSON object) are filled only when example fixtures were given, and are findings, not gaps. `valuesShownAsSource` lists the unrendered values inside rendered conditions, by exact source text, most frequent first (`[{source, count, places}]`, the full list: the index shows only the first 25; each place carries a `url` when `--source-url` was given; advisory). `noControlId` lists the rules with no control id that no other rule uses, as `<package>.<rule>` labels in index order (advisory). `shownAsSource` groups the conditions shown as source by reason (`[{reason, count, rules, places}]`, `rules` the card labels in index order, `places` the `{rule, file, row}` where each statement starts, rows ascending within a card, each with a `url` when `--source-url` was given; advisory). `unpublishedComments` lists plain comment blocks that never reach the documentation (`[{file, row, lines}]`, each with a `url` when `--source-url` was given; advisory, needs no examples). `required` lists the properties the caller made gaps (default `["description","frameworks"]`; see `--require`). Always present; empty arrays when clean |
| `requirements` | object or null | Null unless a requirements list was supplied. Otherwise `total`, `covered`, `items` (`[{requirement, controls}]` in the list's order; `controls` are control ids, or the rule name for a rule with no control id) and `unlisted` (framework strings used by the policy that are not on the list) |
| `controls` | array | **Only with `--group-by-control`** (spec §14.56): one entry per control that has its own page, in control-id order: `controlId`, `page` (`controls/<slug>.md`), `anchor` (the page's own anchor), `title` (the first titled rule's, or null) and `rules` (in page order, each `{package, rule, sourceFile, anchor}`). Absent without the option |
| `packages` | array | One entry per package document, sorted by `path` |

**Package:** `path`, `file` (its Markdown document), `title`, `description` (package METADATA, or `null`), `authors`, `relatedResources`,
`custom` (package METADATA, same shapes as a rule's), `coverage`, `rules` (in card order).

**Rule:**

| Key | Type | Meaning |
|---|---|---|
| `name` | string | The Rego rule name |
| `controlId`, `title`, `description` | string or null | From METADATA |
| `controlIdDerived` | boolean | **Only when `true`**: the id was derived from the file name by `--control-id-from` rather than declared in METADATA (spec §14.55). Absent otherwise |
| `anchor` | string or null | The card's anchor id in the package document (`#rel-001`); null for a duplicate that lost the claim. For a rule moved to a control page it is the id of its section THERE |
| `controlPage` | string | **Only for a rule moved to a control page by `--group-by-control`**: that page (`controls/sec-28.md`). Absent otherwise |
| `sourceFile` | string or null | The `.rego` file (relative to the policy directory) |
| `sourceLine` | integer or null | The rule's first line in that file (its first body, else its `default`, else its constant value) |
| `companionUrl` | string or null | **Only when `--companion-url` was given** (spec §14.58): the second link's URL for this rule (null for a rule with no location), the same URL its card shows. Absent otherwise |
| `sourceUrl` | string or null | The link to that line when a `--source-url` template was given, else null |
| `frameworks` | string[] | METADATA `custom.frameworks` |
| `authors` | `{name, email}`[] | METADATA `authors`; either field may be null |
| `relatedResources` | `{ref, description}`[] | METADATA `related_resources` |
| `custom` | object | Other scalar `custom:` keys, sorted; a list is comma-joined; structured values are omitted |
| `mask` | string[] | **Only when the rule declares `custom.mask`** (spec §14.61): the declared input paths, in declared order. Their values are shown as `***` in every example's messages, verdict, remediation, status and timing (here and in the Markdown). Absent otherwise |
| `parameters` | string[] | A function's parameter names |
| `effectOfMatch` | `"denied"`, `"allowed"` or null | By the `deny`/`violation`/`allow` naming convention only; null for any other rule |
| `default`, `constantValue` | string or null | The rule's `default` outcome / constant value, as the card shows it |
| `isSplitDefaultEntry` | boolean | True for the separate "Default for ..." card |
| `coverage` | object | `{rendered, total, percent}` for this rule |
| `bodies` | array | Each body: `conditions`, `produces` (the message, or null), `yields` (a conditional rule's value, or null). A rule written as an `else` chain whose every branch could be rendered also has `elseBranches`: the branches after the first, in order, each `{conditions, yields, sourceLine}` (the body's own `conditions`/`yields` are the first branch; an empty `conditions` is the closing "Otherwise"). Absent for every other rule |
| `referenceData` | array | `data.*` documents the rule reads: `path` (`data.release.exempt_services`), `breadcrumb`, `defined`, `value` (OPA's resolved value, or null when undefined). **Empty unless a data directory was supplied** |
| `verdict` | object | **Only when the rule declares `custom.verdict`** (see the authoring guide): `{outcome, reason}`, the declared field names (`reason` null when not declared). Absent otherwise |
| `actions`, `annotations`, `timing` | object | **Only when declared** (see the authoring guide): `actions: {at, label}`, `annotations: {at}`, `timing: {dueBy, startBefore}` -- the declared field paths, `null` for a timing field not declared. Absent otherwise |
| `workedExamples` | array | **Absent for a function** (a rule with `parameters`: an input does not "match" a function; read it as an empty list). Otherwise every author fixture, in fixture order: `name`, `matched`, `outcome` (`"❌ denied"`), `messages`, `situations` (body numbers; null when attribution is impossible). An example the declared verdict applied to also has `verdict`: `{outcome, text, reason}` -- the boolean, the words after the glyph on the card (`compliant` / `not compliant`) and the reason string (null if none declared); absent for an example that stayed raw JSON. Likewise, only when the declaration applied to that example: `remediation` (the action labels, in order; `[]` when the value lists none), `annotations` (the status strings; may be `[]`) and `timing` (`{dueBy, startBefore}`, each `null` where the value was unusable) |
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

**Change:** `category`, `controlId`, `label` (e.g. `"REL-003 — Artifact provenance"`; for a control id shared by several rules under `diff --group-by-control`, with the rule added: `"SEC-28 — Sign-off (grp_signoff.signed_off)"`), `package`, `rule`, `anchor` (null for
`DOCS_CHANGED`, which has no card), and either `details` (a `LOGIC_CHANGED` control: the exact sub-bullets of the Markdown *What
changed* section) or `fields` (a `DOCS_CHANGED` control: which of `title`, `description`, `frameworks`, `authors`,
`related resources`, `custom fields` differ).

## Notes

- The JSON describes policy documentation, not policy behaviour: the same disclaimer as the Markdown applies.
- `referenceData` values and `workedExamples` messages are copied from your data and fixtures verbatim — treat the file as you
  treat the Markdown (see [Security considerations](user-guide.md#security-considerations)).
