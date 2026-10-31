# Troubleshooting

Find your symptom, read the cause, apply the fix. Every failure has its own exit code, and the message says what to do.

## Exit codes

| Exit | What happened | What to do |
|---|---|---|
| `1` | Usage error: a bad flag, a missing required option (`--out`), a path that does not exist, an invalid URL template or `custom.mask` | Re-read the message; `java -jar explico.jar render --help` lists the options. |
| `2` | `opa` could not parse your Rego; its own message is shown unchanged, with the file and line | Fix the Rego. If the file uses the older `deny[msg] { ... }` syntax, add `--rego-version v0`. |
| `3` | `opa` is missing or is not version 1.x: *"explico requires opa version 1.x, but it could not be run …"* | Install `opa` 1.x and put it on `PATH`, or set `OPA_BIN`. |
| `4` | Two controls share a control id (`diff`), or two example files share a `name` (`render --examples`) | Make the ids or names unique; for one control deliberately split over several rules, use `--group-by-control`. |
| `5` | A CI gate failed (`--fail-under`, `--fail-on-gaps`, `diff --fail-on`, `--fail-on-example-changes`); the output **was still written** | Read the stderr line, which names the gate and why. See [CI recipes](guides/ci-recipes.md). |

## The render does not start

**`"message": "`if` keyword is required before rule body"`, or another `rego_parse_error`, exit `2`.** Your policies use Rego v0 syntax. Add
`--rego-version v0`, or migrate the policies to v1. The version is never guessed. See [Document existing policies](how-to/document-existing-policies.md).

**Exit `3`, "explico requires opa version 1.x".** Check `opa version`. Either `opa` is not on the `PATH` of the process that runs explico (CI runners and
IDEs often have a different `PATH` from your shell), or it is a 0.x release. Point `OPA_BIN` at the binary to be explicit. For the demo only,
`--fetch-opa` downloads one.

**Exit `1` about a path.** A path argument is wrong. Paths are relative to where you ran the command, not to the policy
directory.

**An old Kotlin error: "Module was compiled with an incompatible version of Kotlin".** A Kotlin consumer with a compiler older than 2.3. See
[CLI and library](user-guide.md#adding-the-dependency).

## The output is not what I expected

**My example is missing from a card.** A card shows at most three matching and two non-matching examples, in file-name order. The index's
*Example coverage* column counts them all. An example whose evaluation actually fails prints a warning on stderr naming it and is left out.

**Example coverage shows `–`.** The rule has no fixture where it matches (or none where it does not). Add one for each direction:
[Add worked examples](how-to/add-worked-examples.md).

**My rule says "matched", not "denied".** Only rules named exactly `deny`, `violation` or `allow` get the denied/allowed wording; anything else is
neutral ("matched").

**The coverage figure is below 100%, or I see "shown as source".** explico met a construct it will not paraphrase and showed the verbatim Rego instead.
That is the design, not a failure, and the card and the index say where. Some have a provably equivalent rewrite
([Policies that document well](guides/policies-that-document-well.md)); many do not. The full list is [What explico renders](reference/constructs.md).

**A condition mentions "a value shown as source" but the coverage is 100%.** Coverage counts conditions. A condition that renders can still contain one
value (for example `kinds[_]`) that is shown as source. The card footer and the index count those separately.

**Worked examples are missing for a rule.** Functions are not evaluated, and neither is a name shared by several cards whose messages cannot be
attributed to one card without guessing. explico shows no example rather than the wrong one.

**The heading is `deny`, not a title.** The rule has no METADATA `title`. Add one, with a `control-id`: [Writing policies](policy-authoring.md).

**A control I renamed shows as removed and added.** Without a `control-id`, identity is package plus rule name, so a rename is a different control.
Give it a `control-id` before renaming.

**The same policy renders differently on two machines.** The output is deterministic for identical inputs, but `opa` versions can differ in what they
emit. Pin `opa` (this project's CI pins 1.19.0); `index.md` shows a digest of your policy files so you can tell whether the inputs matched.

## A gate failed (exit 5)

The documents are written before the gate is checked, so open the output. The stderr line names the gate:

```text
explico: gate failed (--fail-on-gaps): 1 gap(s)
  - control DB-001 has no description
```

| Gate | Fix |
|---|---|
| `--fail-under N` | Coverage is below N%. The index lists what is shown as source; rewrite or lower N. Zero conditions counts as 100%. |
| `--fail-on-gaps` | Add the missing description, framework, title, fixture or verdict it names, or change `--require`. A requirement with no control needs a control that lists it under `frameworks`. |
| `diff --fail-on …` | A control is in a listed category. This is a tripwire for review, not an error in the policy. |
| `--fail-on-example-changes` | One of your fixtures changes outcome between versions. The report's *Behaviour on your examples* names it. |

## Still stuck

[Limits and FAQ](limits-and-faq.md) · [Glossary](glossary.md) · [open an issue](https://github.com/wakaleo/explico/issues) with the command, its exit code
and stderr, and `opa version`.
