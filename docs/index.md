# explico

**Turn your Rego policies and a few example inputs into living documentation that is true by construction.**

explico reads [OPA](https://www.openpolicyagent.org/) Rego policy files and their standard `# METADATA`, and writes plain-language control
documents that a control owner, an auditor or a new engineer can read. Your example inputs are run through the real `opa`, so each
document shows what the policy *does today*, not what someone remembers it doing. Regenerate it in CI and the documentation can never be
quietly out of date.

## From this

A rule, as the policy author wrote it. (A **card** is the section generated for one rule; a **situation** is one way the rule can match.
Terms like these are in the [glossary](glossary.md).)

<!-- excerpt: samples/policies/approvals/change_approval.rego -->
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

## To this

What the control owner reads (generated; nothing here was written by hand):

<!-- excerpt: docs/sample-output/release-approvals.md -->
```markdown
### Situation 1 — all of the following are true

- `deployment ▸ environment` is `"production"`
- `change ▸ ticket ▸ approved` is absent or false

*Produces:* "release [deployment id] has no approved change ticket"

### Situation 2 — all of the following are true

- `deployment ▸ environment` is `"production"`
- `change ▸ author` is `change ▸ approver`

*Produces:* "change [change id] was approved by its author"

**Worked examples**

- **hotfix without change ticket** — ❌ denied *(Situation 1)*
  *"release rel-1002 has no approved change ticket"*
  - `deployment ▸ environment`: `"production"`
  - `change ▸ ticket ▸ approved`: absent
  - `change ▸ author`: `"asmith"`
  - `change ▸ approver`: `"bjones"`
- **self-approved change** — ❌ denied *(Situation 2)*
  *"change chg-2003 was approved by its author"*
```

Each verdict above came from running `opa eval` on a small JSON file named "hotfix without change ticket". That file is the whole
cost of the example.

## Why you can trust it

- **True by construction.** Every statement is derived mechanically from the Rego and its METADATA. Anything explico cannot render
  faithfully is shown as the **verbatim Rego**, clearly marked, and counted. It never paraphrases or guesses.
  [How that works →](concepts/true-by-construction.md)
- **Deterministic.** The same inputs give byte-identical documents, so a change in the text is always a change in the policy.
- **It drives the real `opa`.** There is no second Rego parser to disagree with the one you enforce with.
- **It tells you how much it understood.** Every card, package and the index report a rendering coverage figure, and CI can enforce a
  threshold. [Coverage →](concepts/coverage.md)

## What you get

- A **control card** for every rule: title, description, control id, frameworks, what a match means, every condition in plain words,
  and the worked examples.
- An **index** of all controls, a framework-to-controls matrix, documentation-quality findings and, for a list of requirements you
  supply, the requirements that have **no control**.
- **`explico diff`**: what changed between two versions of a policy set, in plain words, insensitive to formatting, comments and
  variable names. [Reviewing a change →](guides/living-documentation.md#6-what-a-change-looks-like-in-review)
- **CI gates** for coverage, documentation gaps and policy changes, and **JSON** for dashboards and GRC tools.
- **Links** from every card to the exact Rego line at the commit under review.

## Start here

| If you want to… | Go to |
|---|---|
| See it work in a minute | [Install and first run](install.md) |
| Set up living documentation for your own policies | [From Rego to living documentation](guides/living-documentation.md) |
| See real output for Kubernetes, storage, API and more | [Showcase](showcase/README.md) |
| Follow a guided walkthrough | [Tutorial](tutorial.md) |
| Understand a card | [Reading a card](concepts/reading-a-card.md) |
| Run it in a pipeline | [CI recipes](guides/ci-recipes.md) |

You need a JDK (17 or newer) and the `opa` binary (1.x); explico is one jar and writes Markdown. The worked-example verdicts are evaluated,
not asserted: a changed outcome shows up in the diff and fails a drift check, but explico does not decide whether it is the outcome you
wanted ([what the examples do and do not prove](guides/living-documentation.md#8-what-the-examples-do-and-do-not-prove)).

explico documents and diffs policies; it is not a linter and not a replacement for OPA. It is not affiliated with or endorsed by the Open
Policy Agent project or Styra.
