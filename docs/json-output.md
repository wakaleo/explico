# JSON output

explico can emit its result as JSON for tools that should not parse Markdown (dashboards, GRC tools, CI scripts). It is **opt-in**,
and everything else about the output is unchanged when it is off.

```
explico render policies --out docs --json            # also writes docs/explico.json
explico diff old new --out report.md --json         # also writes report.md.json
```

Library: `Explico.render(policySet, RenderOptions(json = true))` adds `explico.json` to
`RenderedDocs.files`; `Explico.diff(old, new).json` always holds the change report's JSON.

## What it promises

- **The same facts as the Markdown, not a second interpretation.** Every condition's `text` is the exact phrase the card shows
  (including its Markdown inline-code backticks), minus links. A construct explico could not render is `unrendered` with its
  verbatim `source` and a machine-readable `reason`, never prose. Coverage, the policy digest, effect of a match, worked-example
  verdicts and reference data come from the same model the Markdown is built from.
- **More than the card where the card summarises:** `workedExamples` lists *every* fixture (the card shows up to 3 matching + 2
  non-matching and says `Showing M of N examples` when it omits some, where N is the length of this array), and `referenceData.value` is the *full* resolved value (the card cuts long arrays after 5).
- **Deterministic:** keys are written in the order below, two-space indented, one trailing newline; the same input is byte-identical.
- **Versioned:** `schema` is `explico/render/1` or `explico/diff/1`. A breaking change gets a new number; fields may be *added* to a
  version, so tolerate unknown keys. What is promised stable, and how a change is announced, is in [Compatibility](compatibility.md).

## `explico/render/1`

| Key | Type | Meaning |
|---|---|---|
| `schema` | string | `"explico/render/1"` |
| `policyDigest` | string | SHA-256 (64 hex) over the `.rego` files; the value shown on `index.md` |
| `coverage` | object | `{rendered, total, percent}` over every condition |
| `quality` | object | Documentation-quality findings over controls: `missingDescription`, `noFramework`, `noMatchingExample` and `rawDecisionObject` (arrays of control ids) and `duplicateTitles` (`[{title, controls}]`). `situationsWithoutExample` (`[{control, situations}]`: a control with several situations, some of which no fixture demonstrates) is always present, an empty array without example fixtures. `noMatchingExample` (a control no fixture makes match) and `rawDecisionObject` (a control with no declared verdict whose value is a JSON object) are filled only when example fixtures were given, and are findings, not gaps. `valuesShownAsSource` lists the unrendered values inside rendered conditions, by exact source text, most frequent first (`[{source, count, places}]`, the full list: the index shows only the first 25; each place carries a `url` when `--source-url` was given; advisory). `noControlId` lists the rules with no control id that no other rule uses, as `<package>.<rule>` labels in index order (advisory). `shownAsSource` groups the conditions shown as source by reason (`[{reason, count, rules, places}]`, `rules` the card labels in index order, `places` the `{rule, file, row}` where each statement starts, rows ascending within a card, each with a `url` when `--source-url` was given; advisory). `unpublishedComments` lists plain comment blocks that never reach the documentation (`[{file, row, lines}]`, each with a `url` when `--source-url` was given; advisory, needs no examples). `required` lists the properties the caller made gaps (default `["description","frameworks"]`; see `--must-have`). Always present; empty arrays when clean |
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
| `compared` | object | Which versions were compared: `old` and `new`, each `{digest, label?}` — the `sha256:` digest of the policy directory (the value `render` prints in `index.md`) and the label given with `--old-label`/`--new-label`. Present only when `diff` was given the policy directories (always, from the CLI) |
| `summary` | object | Counts for `REMOVED`, `ADDED`, `LOGIC_CHANGED`, `DOCS_CHANGED`, `UNCHANGED` |
| `changes` | array | Every non-`UNCHANGED` control, in report order (removed, added, logic changed, docs changed; then control id). The library's `DiffReport.changes` is this same list (`category`, `controlId`, `label`, `package` as `packagePath`, `rule` as `ruleName`, `anchor`) |

**Change:** `category`, `controlId`, `label` (e.g. `"REL-003 — Artifact provenance"`; for a control id shared by several rules under `diff --group-by-control`, with the rule added: `"SEC-28 — Sign-off (grp_signoff.signed_off)"`), `package`, `rule`, `anchor` (null for
`DOCS_CHANGED`, which has no card), and either `details` (a `LOGIC_CHANGED` control: the exact sub-bullets of the Markdown *What
changed* section, including, when `diff` was given `--old-data`/`--new-data`, one ``reference data changed: `<document>`: <old> -> <new>`` string per changed document), and, when `diff` was given `--examples`, `examples`: `[{fixture, old, new}]` — every fixture whose outcome for this control changed (`old` and `new` are the cards' words, equal when only the produced message changed); an empty array when none did, and the key is absent without `--examples`. With `--examples` the document also has a top-level `exampleComparison`: `{compared, total, functions, unattributable, fixturesOnOneVersion}`, what the comparison covered or `fields` (a `DOCS_CHANGED` control, and also a `LOGIC_CHANGED` one whose documentation changed too: which of `title`, `description`, `frameworks`, `authors`,
`related resources`, `custom fields` differ).

## Typed facts of a LOGIC_CHANGED change

`details` are English sentences. A change whose logic changed (`category` `LOGIC_CHANGED`, and only those) also has `facts`: an array **parallel to `details`** — same length, same
order — one object per line, so a workflow can filter on what changed without parsing a sentence. Each fact has `kind` and `sentence`, and `facts[i].sentence` is exactly
`details[i]` (both are produced from the same facts, so they cannot disagree). The kinds are a **closed set** of 15; a new kind would be a new schema version.

| Kind | Extra fields | Meaning |
|---|---|---|
| `condition-removed` | `condition` | A condition the old rule had and the new one does not; `condition` is the phrase alone, as the card words it. |
| `condition-added` | `condition` | A condition the new rule has and the old one did not. |
| `message-removed` | `message` | A produced message only the old rule had (the message alone, without quotes). |
| `message-added` | `message` | A produced message only the new rule has. |
| `bodies-changed` | `old`, `new` | The number of bodies (situations) changed; both are JSON numbers. |
| `default-outcome-changed` | — | The `default` value differs, or appears on one side only. |
| `value-changed` | — | A constant rule's value differs. |
| `source-fallback-changed` | — | The code shown as source (conditions explico could not render) is a different set. |
| `situations-reordered` | — | The same situations in a different order (it matters for an `else` chain). |
| `situation-value-changed` | — | A situation yields a different value (with or without a situation number). |
| `situation-removed` | — | A situation with no counterpart in the new rule. |
| `situation-added` | — | A situation with no counterpart in the old rule. |
| `structure-changed` | — | The logic changed but nothing above names it (for example the same conditions regrouped across bodies); the source diff shows it. |
| `reference-data-changed` | `path`, `old`, `new` | A data document the control reads differs (only with `--old-data`/`--new-data`). `path` is the breadcrumb without backticks; `old` and `new` are real JSON values, and **a key is omitted when that version has the document undefined** (a real JSON `null` is `null`). |
| `documentation-changed` | `fields` | The METADATA changed too; `fields` are the same names as the change's `fields`. Always the last fact. |

The situation kinds carry the sentence only for now (their parameters are free-form phrases). Example:

```json
{
  "category": "LOGIC_CHANGED",
  "controlId": "PAY-LIM-001",
  "details": ["reference data changed: `data ▸ limits ▸ max`: `{\"EUR\":100000}` -> `{\"EUR\":50000}`", "documentation changed: description"],
  "facts": [
    {
      "kind": "reference-data-changed",
      "sentence": "reference data changed: `data ▸ limits ▸ max`: `{\"EUR\":100000}` -> `{\"EUR\":50000}`",
      "path": "data ▸ limits ▸ max",
      "old": {"EUR": 100000},
      "new": {"EUR": 50000}
    },
    {"kind": "documentation-changed", "sentence": "documentation changed: description", "fields": ["description"]}
  ]
}
```

## Notes

- The JSON describes policy documentation, not policy behaviour: the same disclaimer as the Markdown applies.
- `referenceData` values and `workedExamples` messages are copied from your data and fixtures verbatim — treat the file as you
  treat the Markdown (see [Security considerations](user-guide.md#security-considerations)).
