# Coverage

Every card, every package page and the index report a **rendering coverage** figure. It answers one narrow question: *of the
conditions in this policy, how many did explico render as readable text, rather than showing as source?*

It says **nothing** about whether a control is effective, complete or well written.

## What counts as a condition

A condition is one test in a rule body: a comparison, a membership test, a presence check, a builtin call, a `some … in`, a reference
to another rule or function. An `every` block counts its head plus each condition nested inside it. A statement that tests nothing
does not count: a declare-only `some i, j`, a bare `true`, and an assignment that is substituted into later conditions.

A condition is **rendered** if explico produced a phrase for it, and **unrendered** if it is shown as a ⚠ source block.

## Where you see it

| Place | Form |
|---|---|
| Each card footer | `*Rendering coverage: 4 of 4 conditions*` |
| Each package page | `*Package rendering coverage: 10 of 10 conditions (100%)*` |
| `index.md` | a *Rendered* column per rule, an overall line, and a *Below 100%* note naming every rule that is not fully rendered, linked to its card |
| The command line | `Rendering coverage: 24 of 24 conditions (100%)` on stdout |
| `explico.json` | per rule, per package and overall |

Two refinements appear in a footer when they apply:

- `contains N unrendered value(s)` — a condition was rendered, but one *operand* inside it (say a variable explico could not
  resolve) is shown as raw source. These do not lower the percentage; they are reported next to it.
- `including N helper assignment(s) shown as source` — how many of the unrendered conditions are local assignments whose right-hand
  side explico could not substitute. They still count as unrendered; the note only tells you what kind they are.

## What a low number means

A helper rule can legitimately show 0%: if its only condition is a construct explico does not render (a `with` override, say), that
says nothing about the rules that call it. A number below 100% is not a bug, and not necessarily a problem with the policy. It means:
*read this part as source*.

## Using it as a gate

`render --fail-under <percent>` exits with code `5` when overall coverage is below the threshold. It is compared exactly (18 of 20
conditions is exactly 90%: it passes 90 and fails 91). The output is still written.

**The figure can change between explico versions with no change to your policies.** A release may render a construct that used to be
source, or stop counting a statement that tests nothing. Pin the explico version in CI and re-baseline the threshold when you upgrade;
the [changelog](../../CHANGELOG.md) says when what counts as a condition changes. See [CI recipes](../guides/ci-recipes.md).

## How good is it on real policies?

On policy sets written by other people, measured when this page was written (it will move as constructs are added):

| Policy set | Rendered |
|---|---|
| Gatekeeper library (Rego v0) | 88% |
| conftest examples | 83% |
| Trivy Kubernetes checks, with their library loaded as one unit | 83% |
| Trivy checks, one file at a time | 66% |
| Regal's own rules | 57% |

The one-file-at-a-time figure understates Trivy because most of what remains is a call into a library file that a one-file load cannot
see. Regal's rules are written against its own helper libraries, which explico does not resolve call by call. See [Limits and FAQ](../limits-and-faq.md) and the
[Rego coverage reference](../rego-coverage.md) for exactly what falls back and why.
