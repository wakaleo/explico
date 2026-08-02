# Writing policies explico renders well

This is for whoever writes the Rego *behind* an explico-rendered control
card — how to get metadata, messages, and structure that render clearly,
and why a lower coverage number isn't a bug report.

## METADATA: the standard OPA format, and what explico does with it

### Standard format vs. explico's `custom:` conventions

The `# METADATA` YAML-block annotation is
[OPA's own standard mechanism](https://www.openpolicyagent.org/docs/latest/annotations/),
not something explico invented or extends with special syntax. OPA's schema
defines a fixed set of top-level fields — `scope`, `title`, `description`,
`organizations`, `related_resources`, `authors`, `schemas`, and `custom`
— and `custom` is itself OPA's own designated escape hatch for arbitrary,
tool-defined data with no schema of its own. `control-id` and `frameworks`
are **explico's own convention layered inside that escape hatch**, not part
of OPA's standard vocabulary — any other tool reading the same annotation
would see an ordinary `custom` map and wouldn't know to treat `control-id`
specially. Nothing about writing `custom.control-id`/`custom.frameworks`
requires explico-specific tooling to parse; it's plain METADATA any
OPA-compliant reader can see.

### Which fields explico actually reads

| METADATA field | Read by explico? | What it does |
|---|---|---|
| `scope` | Yes, but only to distinguish `package` from everything else | See "scope: placement and semantics" below |
| `title` | Yes | The card heading, after the control-id |
| `description` | Yes | The paragraph under the heading |
| `custom.control-id` | Yes | The control-id in the heading, the anchor, and the diff identity |
| `custom.frameworks` | Yes | The `*Frameworks: ...*` line |
| `organizations`, `authors`, `related_resources`, `schemas` | **No** | Valid, standard OPA fields — `opa parse` itself parses and preserves them — explico's own JSON decoding just doesn't model them, so they're silently unused, never an error |
| Any other `custom.*` key besides `control-id`/`frameworks` | **No** | Same as above: OPA preserves it (`custom` is unstructured to OPA), explico's decoder ignores it |

### REL-001: source to card, field by field

```rego
# METADATA
# scope: document
# title: Production change approval
# description: |
#   Production deployments must reference an approved change ticket,
#   and the change author must not approve their own change.
# custom:
#   control-id: REL-001
#   frameworks:
#     - SOC 2 CC8.1
#     - ISO 27001 A.8.32
deny contains msg if {
	...
}
```

renders as:

```markdown
## REL-001 — Production change approval

*Rule `deny` in package `release.approvals` — defined in `change_approval.rego`*
*Frameworks: SOC 2 CC8.1, ISO 27001 A.8.32*

Production deployments must reference an approved change ticket,
and the change author must not approve their own change.
```

| Card text | Comes from |
|---|---|
| `REL-001` in the heading | `custom.control-id` |
| `Production change approval` in the heading | `title` |
| The paragraph below the metadata line | `description`, verbatim (a YAML block literal's own trailing newline is trimmed so it doesn't double up with the blank line after it) |
| `*Frameworks: SOC 2 CC8.1, ISO 27001 A.8.32*` | `custom.frameworks`, comma-joined |
| The `Rule ... in package ... — defined in ...` line | **Not METADATA at all** — the rule name, package path, and source filename are read directly from the parsed AST, structurally derived the same way the condition bullets are (see "METADATA is a human-attested layer" below for why this distinction matters) |

### `scope`: placement and semantics

Only one thing about `scope` actually changes explico's behavior: whether
it's `package` or not.

- `scope: document`, `scope: rule`, or **omitting `scope` entirely** (which
  defaults to `rule` when the annotation sits directly above a rule — OPA's
  own default, not explico's) are all treated **identically**: the
  annotation attaches to the rule as a whole, covering every body
  ("situation") it has, not just the one nearest the comment.
- `scope: package` is filtered out entirely — a package-scoped annotation
  has no attachment point in explico's domain model (a deliberate,
  disclosed scoping decision, not a bug — see `CLAUDE.md`).
- **Placement above the rule is all that matters, not proximity within the
  file.** explico matches an annotation to the rule it belongs to via `opa
  inspect`'s own resolved `path` field, not by how close the comment block
  sits to the rule textually. This is also what correctly deduplicates a
  single `scope: document` annotation across a multi-body rule (two
  `deny contains msg if { ... }` clauses with one shared METADATA block
  above the first) into one attached `RuleMetadata`, not two.

### Degradation when METADATA is absent

A rule with **no** METADATA at all still renders — just with a plain
`<package>.<rule name>` heading (no control-id), the placeholder
*"No description provided in policy metadata."*, no `*Frameworks:*` line,
and it's identified for diffing purposes by `<package>.<rule name>` (see
below) rather than a control-id. Not every helper rule needs annotating —
only annotate what should actually show up as a control in its own right.

### Malformed METADATA

Verified against real `opa parse` output, not assumed:

- **Invalid YAML inside the block is a hard parse failure for the whole
  file** — e.g. a stray line like `#   this is: not: valid: yaml: at all`
  produces `yaml: line 3: mapping values are not allowed in this context`
  from `opa` itself (`opa parse`'s real exit code and stderr, reproduced
  directly). This surfaces through explico exactly like any other Rego
  syntax error: exit code `2`, `opa`'s own error message passed through
  verbatim, never paraphrased.
- **A genuinely unrecognised top-level key (not `custom`) is silently
  dropped by `opa` itself**, before explico's own JSON decoding ever sees
  it — confirmed by inspecting `opa parse`'s own annotation output with
  such a key present: no error, and the key simply isn't there. This is
  OPA's own leniency, not an explico behavior.
- **An unrecognised key nested under `custom:` survives in `opa`'s own
  output** (`custom` is unstructured to OPA) but is silently dropped by
  explico's own deserialization, same as the standard fields explico
  doesn't model above. Also never an error.

### `control-id` as diff identity

`custom.control-id` is more than display text — it's the identity
`explico diff` tracks a control by:

- **If present on both sides of a diff, it wins over package/rule-name
  matching.** Renaming a rule, or moving it to a different package, while
  keeping its `control-id` unchanged, is correctly reported as the *same*
  control — `UNCHANGED` if its logic and metadata are both unchanged,
  `LOGIC_CHANGED` if the logic differs — **never** a spurious "one control
  removed, another added" pair. A rule with no `control-id` is identified
  by `<package>.<rule name>` instead; a rename of *that* is genuinely
  indistinguishable from a removal-plus-addition, since explico never
  guesses at rename detection by similarity heuristics.
- **Duplicate `control-id`s within one side of a diff are an error** (exit
  code `4`), not a silent pick-one-arbitrarily — fix the duplicate before
  the report can be produced.
- **`frameworks` has no such identity role** — it's a flat list of strings,
  rendered verbatim, comma-joined, with no registry or validation.

### METADATA is a human-attested layer, not a mechanically verified one

Everything under "All of the following are true" on a card — every
condition bullet — is *true by construction*: mechanically derived from
the parsed AST, never paraphrased, never guessed. **METADATA is different.**
A rule's `title`, `description`, `control-id`, and `frameworks` are exactly
what a human wrote in the annotation, rendered faithfully — but explico
never checks whether a description still accurately describes what the
conditions actually do, or whether a `frameworks` mapping is still correct.
If the Rego changes and the METADATA block isn't updated to match, explico
will render the stale description next to the current, accurate logic,
with no warning that the two have drifted apart. Keeping metadata in sync
with logic is the policy author's job, not something explico's honesty
guarantee extends to — that guarantee is specifically about the *logic*,
never about auditing the prose a human attached to it.

## Writing conditions that render as structured text

Each leaf condition in a rule body renders as one bullet if explico
recognises its shape:

- **Comparisons**: `input.x == "y"`, `input.x >= input.z`, `count(...) == 0`
  — any of `==`/`!=`/`>`/`>=`/`<`/`<=` between two operands.
- **Membership**: `input.x in {"a", "b"}` or `input.x in data.y.set`.
- **Truthy/negated references**: `input.x`, `not input.x`.
- **Recognised builtin calls**: `startswith`/`endswith`/`contains`/
  `regex.match`/`glob.match`. (Adding a new one is a small, well-defined
  extension — see the README's Contributing section.)
- **`some x in collection`**, with later `x.field` accesses in the same
  body rendering as a breadcrumb through the collection.
- **References to another rule** in the same or an imported package —
  rendered as a link to that rule's own card.

Anything else — comprehensions, `every`, user-defined functions with
parameters, `with` statements, `else` chains, an operand-position builtin
call like `count(some_comprehension)` — becomes a clearly marked, verbatim
source block instead of a guess. This is deliberate (see "Fallback and
coverage" below), not a gap to work around by restructuring your policy
around explico's limitations. Write Rego the way it should be written;
let the coverage percentage tell you honestly how much of it explico could
turn into prose.

## The distinct-message convention and `*(Situation N)*` labels

When a rule has multiple bodies (multiple `deny contains msg if { ... }`
clauses, i.e. an OR of "situations"), explico labels which body a worked
example's message came from — *if and only if* every body's message is
distinct enough to tell apart:

```rego
deny contains msg if {
	input.deployment.environment == "production"
	not input.change.ticket.approved
	msg := sprintf("release %v has no approved change ticket", [input.deployment.id])
}

deny contains msg if {
	input.deployment.environment == "production"
	input.change.author == input.change.approver
	msg := sprintf("change %v was approved by its author", [input.change.id])
}
```

Both bodies' message templates are literally different strings
(`"release %v has no approved change ticket"` vs. `"change %v was approved
by its author"`), so a worked example matching either one gets a
`*(Situation 1)*` / `*(Situation 2)*` label. If two bodies produced the
*same* message text, or a body's message can't be identified as either a
string literal or an `sprintf` call, explico attributes at the rule level
only — no label — rather than guessing which situation actually fired.
This is "best effort, never guessed" (spec §6.7): distinct messages are a
convention you opt into by writing them, not something explico infers from
body structure.

The message argument itself (`input.deployment.id` above) gets humanised
into the card's `*Produces:*` line as `[deployment id]` — but only when
it's a plain `input.`-rooted field chain. A message built from a
loop-bound variable (`stage.name` after `some stage in ...`) or anything
else non-trivial makes the whole `*Produces:*` value `null` (shown as
absent) rather than a phrase with some parts humanised and others raw —
again, never a partial guess.

## Fallback and coverage: design, not defect

A card's `*Rendering coverage: X of Y conditions*` line, and the package-
and corpus-wide summaries, tell you what fraction of a rule's logic became
readable prose. **A number under 100% is not a bug in explico, and not
necessarily a problem with the policy either** — it means "here is a
condition you should read as source, because explico won't paraphrase
something it can't verify it understood correctly." A `count()` over a
comprehension, an `every` block, a `with`-mocked test helper: these are all
completely legitimate Rego, and they render as a fenced `rego` block with a
short machine-readable reason (e.g. `comprehension`, `every`,
`function-call`) instead of invented English. Treat a fallback block as
"read this bit yourself," and treat the coverage percentage as an honest
signal of how much of the policy you can trust the prose above it to
represent faithfully — not a completeness score to chase to 100% by
rewriting policies around the tool.

## Authoring a worked-example fixture

`--examples <dir>` reads every `*.json` file in the directory, in filename
order (prefix them `01-`, `02-`, ... to control display order — the pack in
`samples/examples/` does this). Each file is one fixture:

```json
{
  "name": "hotfix without change ticket",
  "description": "Emergency deployment attempted straight from a feature branch.",
  "input": {
    "deployment": { "environment": "production", "id": "rel-4412" },
    "change": { "author": "asmith" }
  }
}
```

- **`name`** (required) — must be unique across the whole set; a duplicate
  is an error (exit code `4`), not a silent overwrite. Shown verbatim as
  the worked example's own label on the card.
- **`description`** (optional) — not currently rendered on the card, but
  useful documentation for whoever maintains the fixture set.
- **`input`** (required) — the exact JSON object evaluated as `input` via
  `opa eval`. Whatever paths a rule's conditions reference get shown
  underneath the fixture's outcome, resolved from this object — write
  fixtures that plausibly exercise the paths you actually reference.

Up to 3 matching and 2 non-matching fixtures are shown per control (a fixed
cap, filename order, not configurable) — a fixture set can have more than 5
entries; extras beyond the cap still count toward `index.md`'s
example-coverage column, just aren't all shown individually on every card.
A fixture whose evaluation genuinely fails prints a stderr warning naming it
and is excluded from that render — never a silent omission.

### Naming principle: describe the scenario, not the data

`name` is the only thing a reader sees before deciding whether a fixture is
relevant to what they're checking — it should read as a business scenario,
not a description of which fields are set:

- **Name a denied fixture after the violation, not the mechanism.** `"hotfix
  without change ticket"` tells a reader what went wrong; `"change.ticket is
  null"` makes them go read the rule to find out why that matters. The
  fixture above is named the first way for exactly this reason.
- **Name an allowed fixture after the scenario it represents in order, or
  the near-miss it demonstrates** — `"approved standard release"` for the
  straightforward in-order case, `"failed security scan"` (allowed once
  remediated, or allowed because a *different* control's own examples show
  the denial) for a case that's interesting precisely because it's close to
  the line. Avoid generic names like `"valid input"` or `"test case 2"` —
  they carry no scenario information at all, and stop being distinguishable
  once a control accumulates several fixtures.

The test is: could someone deciding "is my situation covered by an existing
example?" answer just by reading the list of `name`s, without opening a
single fixture file? If not, the name is describing data, not a scenario.

## Where examples come from

### The invariants

- **Examples are committed fixture files, never generated at render time.**
  explico contains no fixture generator of any kind — every `*.json` file
  under `--examples <dir>` is something a person wrote and checked in
  before `render` ever ran. There is no "auto-generate a few examples for
  me" mode, and none is planned; the sourcing ladder below is a manual
  workflow, not a preview of upcoming tooling.
- **Every verdict shown is `opa`'s own, evaluated against the current
  policy version at render time.** A fixture's outcome, its produced
  messages, and every referenced path value are computed fresh by a real
  `opa eval` call each time `render` runs — never cached from a previous
  run, never precomputed and stored alongside the fixture. Change the
  policy and re-render, and the same fixture can show a different verdict,
  correctly.
- **The fixture's `name` is the only human-authored text that appears
  in the Worked examples section.** Everything else displayed — the
  outcome word (`❌ denied`/`✅ allowed`/`matched`/`not matched`), the
  produced message, the `*(Situation N)*` label, every referenced path's
  value — is either a fixed vocabulary word or something `opa`
  computed. `description` is real, optional prose you can write in the
  fixture file, but it is never rendered on the card (see the format
  above) — it doesn't count as authored text *in the section*, because it
  never reaches the section at all.

### The mechanics: fixture file vs. computed each render

| Lives in the fixture file | Computed fresh every render |
|---|---|
| `name` (required, unique) | The matched/not-matched verdict |
| `description` (optional, never rendered) | Every produced message |
| `input` (required, the exact JSON evaluated) | Which `*(Situation N)*` label applies, if any |
| | Every referenced path's resolved value, read out of the fixture's own `input` |

The fixture file is deliberately thin — just enough to name a scenario and
supply its input. Everything a reader actually sees about *what happened*
is derived, not stored.

### The sourcing ladder (manual today — there is no harvesting tool)

Nothing below is automated. This is a practical order to work through by
hand when building or growing a fixture set, roughly cheapest-and-most-
available first:

1. **Harvest scenario inputs out of existing `*_test.rego` files.** If the
   policy already has OPA unit tests, each `with input as {...}` block in a
   `test_...` rule is a candidate fixture input someone already thought
   through — copy the input object out by hand. The test rule's own name
   (e.g. `test_denies_change_without_ticket`) is usually a good starting
   point for the fixture's `name`, reworded into the plain-language style
   the pack's own fixtures use (e.g. "hotfix without change ticket").
2. **Hand-author flagship scenarios for your most critical controls.**
   Don't rely solely on whatever a test file happened to exercise — for
   the controls that matter most, deliberately write the one or two inputs
   that most clearly demonstrate what the control is actually for, in the
   clearest possible form (see "small inputs" below).
3. **Capture real evaluation inputs from the pipeline.** A real input a
   policy was actually evaluated against in CI/CD is often more
   representative than anything hand-imagined. **Sanitise it first** —
   strip real ticket IDs, usernames, hostnames, tokens, or anything else
   that shouldn't end up permanently committed to the repository — before
   turning it into a fixture. Treat this as building two things at once:
   real example material for today's cards, and the seed of a **regression
   corpus** for tomorrow (see below).

### Choosing which examples to add

- **Aim for at least one example per `Situation`** (each body of a
  multi-body rule) **plus one "near-miss" allowed case** — an input that
  comes close to violating the control but doesn't. Showing only "this
  triggers it" examples leaves the actual boundary of the control
  implicit; a near-miss makes it concrete.
- **Prefer small, minimal inputs over large, realistic-looking ones.**
  Only the paths a rule's conditions actually reference get shown under
  each example (see the format above) — a fixture with just those fields
  populated is more readable than a sprawling real-world payload with
  dozens of irrelevant ones. Trim captured pipeline inputs down before
  committing them, rather than pasting them in whole.

### This corpus appreciates in value

The same fixture set that powers today's Worked examples section is also
the natural seed for **cross-version change-impact analysis** — evaluating
whether a policy change flips any real scenario's verdict — which spec
§1.2 explicitly names as future-phase scope, not something this POC does
yet. A well-sourced, sanitised, real-world-grounded fixture corpus built
now isn't just documentation for today's cards; it's the regression suite
a future differential-evaluation feature would run against. Time spent
growing it deliberately is not wasted on a feature that doesn't exist yet.
