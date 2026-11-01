# Evaluating explico

For engineering leaders, security and compliance leads deciding whether to pilot explico. It answers the questions a decision needs, with
pointers to the evidence, and says plainly where explico does not fit. It is about a page long on purpose.

## What problem it solves

Policy-as-code is precise but unreadable to the people who own the controls. Hand-written control documents are readable but drift from the
code, and nobody finds out until an audit. explico generates the document *from* the Rego, so it cannot drift: regenerate it in CI and a
change in the text is always a change in the policy.

| Who | What they get |
|---|---|
| Control owner, auditor | Plain-language control cards: what a rule checks, a worked example per scenario, mapped to the frameworks you name |
| Reviewer of a policy change | A diff in words, including what happens to your own example scenarios |
| Engineering lead | A pipeline gate on documentation quality and on changes; a coverage figure instead of a promise |

It documents and diffs policies. It is not a linter, not a policy engine, and not a replacement for OPA.

## What you can rely on, and what you must still own

This is the question that matters in an audit. The boundary is sharp, and the tool states it rather than blurring it.

| Part of the output | Basis | Who is accountable |
|---|---|---|
| Conditions in plain words | Derived mechanically from the Rego syntax tree; a construct it cannot render faithfully is shown as marked, verbatim source and counted | explico |
| Worked-example verdicts and messages | Produced by running `opa eval` on your fixture; never predicted | `opa` and your fixture |
| Coverage and counts | Counted, not estimated | explico |
| Title, description, control id, frameworks | Copied from the policy's METADATA | **The policy author**: explico cannot check that a description matches the logic |
| Requirement-to-control mapping | What the METADATA declares, matched as exact text | **The policy author** |
| Whether a control is *good* | Not assessed | **You** |

So the output can be cited as evidence of **what the policy does today**. It is not evidence that the description is right or the control is
adequate; reviewers still own that. [True by construction](concepts/true-by-construction.md) is the full statement.

## What to expect on your own policies

Be sceptical of the demo: the sample policies render at 100% because they were written with explico in mind. The honest numbers come from
the project's audit of four public libraries it was not written around (Gatekeeper, Trivy, Regal, conftest; 964 files):

- **97% of files render at all** (934 of 964). The 30 that do not are rejected by OPA itself: older Rego syntax, or a file that calls a function defined in a sibling file.
- **Rendered conditions on that third-party code were 47–61%**, against about 90% on the project's own fixtures. The rest are shown as marked source, never
  paraphrased. That figure predates later improvements and is not a current measurement, so treat it as an order of magnitude and run your own.

The practical reading: expect useful cards for policies of ordinary shape (comparisons, membership, negation, iteration, user-defined functions),
and expect heavily abstract or builtin-dense policies to show a lot of source. Each such block says why. Measure your own set in the pilot below.
Details: [Rego coverage audit](rego-coverage.md), [What explico renders](reference/constructs.md).

## What adopting it costs

- **Runtime:** a JDK (17 or newer) and the `opa` binary (1.x). One jar; no service, no database, no account. OPA 1.19.0 is the tested version.
- **Authoring effort:** optional but where the value is: a `# METADATA` block per control (title, description, control id, frameworks), and one small JSON file per example
  scenario. Nothing is required to render; missing documentation is reported by the tool as a finding.
- **CI:** one render step and, if you want them, gates (`--fail-under`, `--fail-on-gaps`, `diff --fail-on`). [CI recipes](guides/ci-recipes.md) has GitHub Actions and GitLab.
- **Packaging:** you download the jar and install `opa` yourself; no container image or marketplace action is documented. Pin both versions in CI.
- **Performance:** no published figures. Time a render of your own policy set during the pilot.

## Risks to weigh

- **Examples execute policy code.** Rendering without examples only parses. With `--examples` or `--generate-examples`, `opa eval` runs the policy, and Rego has
  builtins that reach outside the process. Use it only on policies you trust, in an environment without credentials you cannot expose.
  [Security policy](security.md).
- **`--out` is emptied before each render.** Give it a dedicated directory.
- **Masking is a documentation aid, not a security boundary.** [Mask sensitive values](how-to/mask-sensitive-values.md) lists what it does not hide.
- **Pre-1.0.** The API and output can still change. Read the changelog's "Changed" entries before upgrading; a test pins the public model classes' constructors from earlier releases, and the pinned `opa` version is part of reproducibility.
- **Tied to OPA.** It reads standard OPA METADATA and drives the real `opa` binary, so there is no second dialect to migrate away from, but it is of no use outside Rego.
- **Not affiliated with OPA or Styra.**

## Project facts

| | |
|---|---|
| Licence | [Apache 2.0](https://github.com/wakaleo/explico/blob/main/LICENSE) |
| Current version | 0.4.0 ([changelog](changelog.md)) |
| Releases | Signed; fixes go to the latest release only ([security policy](security.md)) |
| Source and issues | [github.com/wakaleo/explico](https://github.com/wakaleo/explico) |
| Vulnerabilities | Private report via GitHub's *Security → Report a vulnerability* |
| Network use | None, except the optional `explico demo --fetch-opa` |

## A pilot you can run in two weeks

A suggestion, not a requirement. Choose one team and the three to five policies it cares most about.

1. **Day 1.** Install, run the [Quickstart](quickstart.md), then render the team's real policies unchanged. Record the coverage figure and what the index says is missing.
2. **Week 1.** Add METADATA (title, description, control id, frameworks) to those controls and a handful of example scenarios written by someone who owns the control, not just the author of the Rego. Follow [Tutorial: your first policy](tutorial-first-policy.md).
3. **Week 2.** Wire it into CI with a drift check, and run `diff` on two real recent policy changes. Show the control owner the cards and the diff.

**Judge it on:** did the control owner understand a card without help? Did the diff tell a reviewer something the Rego diff did not? What coverage did you reach,
and is the rest acceptable shown as source? Did anyone find a sentence that was wrong? (That would be a defect worth reporting.)

**It is probably not for you if** your policies are not Rego, your documentation need is a narrative no tool could derive from code, or you cannot
run example evaluation anywhere safe and need rendered verdicts.

## Next

[Quickstart](quickstart.md) · [Showcase](showcase/README.md) (real output for Kubernetes, storage and API policies) · [Limits and FAQ](limits-and-faq.md)
