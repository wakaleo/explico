# Writing policies explico renders well

This is for whoever writes the Rego *behind* an explico-rendered control
card — how to get metadata, messages, and structure that render clearly,
and why a lower coverage number isn't a bug report.

## METADATA fields → card anatomy

explico reads the standard OPA `# METADATA` annotation block
(document-scoped, immediately above a rule) and maps it directly onto the
control card:

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

| METADATA field | Where it appears on the card |
|---|---|
| `custom.control-id` | The card's heading (`## REL-001 — ...`) and its anchor for cross-references. A rule with no control-id gets `## <package>.<rule name>` instead, and sorts last in `index.md`. |
| `title` | The rest of the heading line. Falls back to the bare rule name if absent. |
| `description` | The paragraph directly under the metadata line. If absent: *"No description provided in policy metadata."* — a visible gap, not a silently blank section. |
| `custom.frameworks` | An italic `*Frameworks: ...*` line, comma-joined, only rendered if the list is non-empty. |
| `scope: document` | Attaches the annotation to the rule as a whole, covering every body (situation). Use `scope: rule` for the same effect on a single-body rule; either works the same way to explico, since it matches annotations to rules by `opa inspect`'s own resolved `path`, not by proximity. |

A rule with **no** METADATA at all still renders — just with a plain
`<package>.<rule name>` heading and the "no description" placeholder. Not
every helper rule needs annotating; only annotate what should actually show
up as a control.

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

## Control-id and frameworks conventions

- **`control-id` is the identity a diff report tracks a control by.** If
  present on both sides of a `diff`, it wins over package/rule-name
  matching — so renaming a rule, or moving it to a different package,
  while keeping its `control-id`, is correctly reported as the *same*
  control (`UNCHANGED` or `LOGIC_CHANGED`, depending on whether its actual
  logic changed), never a spurious "one control removed, another added."
  A rule with no `control-id` is identified by `<package>.<rule name>`
  instead — a rename of *that* is genuinely indistinguishable from a
  removal-plus-addition, since explico never guesses at rename detection
  by similarity heuristics.
- **Duplicate `control-id`s within one side of a diff are an error**
  (exit code `4`), not a silent pick-one-arbitrarily — fix the duplicate
  before the report can be produced.
- **`frameworks` is a flat list of strings**, rendered verbatim,
  comma-joined. There's no framework registry or validation; whatever you
  write appears as-is.

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
