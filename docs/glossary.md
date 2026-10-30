# Glossary

**Absent.** In a worked example, the input has no value at that path.

**Card.** The section of a package page that documents one rule: heading, description, conditions and, if you supplied inputs, worked
examples. See [Reading a card](concepts/reading-a-card.md).

**Condition.** One test in a rule body: a comparison, a membership test, a presence check, a call, a `some … in`. Conditions inside one
body must all hold.

**Companion link.** A second link next to a card's source link, to the module that owns the policy (`--companion-url`, with `--companion-label`).

**Control id.** The identifier you give a rule in `custom.control-id` METADATA (for example `REL-001`). It is the card's heading and how
`explico diff` recognises the same control across versions, so renaming a rule does not look like a removal.

**Control page.** With `--group-by-control`, the one page (`controls/<id>.md`) a control id shared by several rules gets, with a section per rule.
See [Reading a card](concepts/reading-a-card.md).

**Coverage (rendering coverage).** How many of the conditions explico rendered as text rather than showing as source. It says nothing
about whether a control is good. See [Coverage](concepts/coverage.md).

**Declared projection.** What a rule's author says, in `custom:` METADATA, about a rule that returns an object: which fields are the verdict
(`custom.verdict`), the remediation (`custom.actions`), the status (`custom.annotations`) and the timing (`custom.timing`). explico reads only
the field names you declare and shows what the evaluated value holds; it never infers a meaning.

**Derived control id.** A control id read from a rule's file name by `--control-id-from` rather than declared in METADATA. The card says so.

**Digest (policy digest).** A SHA-256 over every `.rego` file in the policy set, shown under the index title and (shortened) on every page.
It changes when any policy byte changes, and never because of your examples, data, or the explico version.

**Example (fixture, worked example).** A small JSON file with a `name` and an `input`. explico evaluates it against every rule with the real
`opa eval` and shows the verdict on each card.

**Fallback.** What explico does with a construct it cannot render faithfully: it shows the verbatim Rego in a marked block (⚠) and counts
it. See [True by construction](concepts/true-by-construction.md).

**Framework.** A standard a control helps satisfy (for example `SOC 2 CC8.1`), listed in `custom.frameworks`. The index builds a
controls-by-framework matrix from them.

**Gate.** A CI check that fails the build with exit code `5`: `--fail-under`, `--fail-on-gaps`, `diff --fail-on`. See
[CI recipes](guides/ci-recipes.md).

**Index.** `index.md`, generated alongside the package pages: every control with its coverage and example coverage, a framework matrix,
and documentation-quality findings.

**Mask.** `custom.mask: [paths]` on a rule: input paths whose values every worked example of that rule shows as `***`. Only values that are in the
fixture input can be hidden. See [Writing policies](policy-authoring.md).

**METADATA.** The standard OPA `# METADATA` comment block above a rule: title, description and `custom:` keys. explico reads it; it does not
verify it.

**Package page.** One generated document per Rego package, containing the cards for its rules.

**Situation.** One body of a rule with several bodies. A rule matches when **any** of its situations applies; within one situation, every
condition must hold. In a rule written as an `else` chain the situations are tried **in order** and the first that applies wins.

**Verdict.** What a rule did with an example: *denied* or *allowed* for rules named `deny`/`violation`/`allow`, otherwise *matched* or
*not matched*, plus the message and which situation produced it. Always from `opa`.
