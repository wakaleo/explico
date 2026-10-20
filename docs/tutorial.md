# Tutorial: render → worked examples → diff

A guided walkthrough of `samples/` — five real release-governance
policies, six worked-example fixtures, and a data document — using the
real CLI. Every command below is copy-pasteable from a checkout of this
repository; every piece of output shown is real, taken from an actual run,
never hand-typed.

## 1. Render the sample policy set

```
explico render samples/policies --out docs \
  --examples samples/examples --data samples/data/release/data.json
```

```
Rendering coverage: 24 of 24 conditions (100%)
```

This writes one Markdown file per Rego package, plus `index.md`. Open
`index.md` first — it's the map of everything else:

```markdown
# Control index

*Policy digest: `sha256:5f0bc88b6e15c3e7855a524b2e86bceeec63c4ab152185fccc9afbf9a1b71983`*

| Control ID | Title | Package | Rule | Rendered | Example coverage | Source file |
|---|---|---|---|---|---|---|
| REL-001 | [Production change approval](release-approvals.md#rel-001) | `release.approvals` | `deny` | 100% | ✓ / ✓ | `approvals/change_approval.rego` |
| REL-002 | [Pipeline evidence](release-evidence.md#rel-002) | `release.evidence` | `deny` | 100% | ✓ / ✓ | `evidence/pipeline_evidence.rego` |
| REL-003 | [Artifact provenance](release-provenance.md#rel-003) | `release.provenance` | `deny` | 100% | ✓ / ✓ | `provenance/artifact_provenance.rego` |
| REL-004 | [Release governance evidence](release-governance.md#rel-004) | `release.governance` | `deny` | 100% | ✓ / ✓ | `governance/release_governance.rego` |

## Rules without a control id

| Control ID | Title | Package | Rule | Rendered | Example coverage | Source file |
|---|---|---|---|---|---|---|
| — | [Release candidate environments](release-evidence.md#release-evidence-is-release-candidate) | `release.evidence` | `is_release_candidate` | 100% | ✓ / – | `evidence/pipeline_evidence.rego` |
| — | [exempt_service](release-exemptions.md#release-exemptions-exempt-service) | `release.exemptions` | `exempt_service` | 100% | ✓ / ✓ | `exemptions/exemptions.rego` |
| — | [Mandatory check completeness](release-governance.md#release-governance-all-checks-passed) | `release.governance` | `all_checks_passed` | 100% | ✓ / – | `governance/release_governance.rego` |

## Controls by framework

| Framework requirement | Controls |
|---|---|
| DORA Art. 9 | [REL-004](release-governance.md#rel-004) |
| ISO 27001 A.8.32 | [REL-001](release-approvals.md#rel-001), [REL-003](release-provenance.md#rel-003) |
| SLSA v1.0 L2 | [REL-003](release-provenance.md#rel-003) |
| SOC 2 CC8.1 | [REL-001](release-approvals.md#rel-001), [REL-002](release-evidence.md#rel-002), [REL-004](release-governance.md#rel-004) |

*Overall rendering coverage: 24 of 24 conditions (100%)*

## How to read this index

- **Rendered** — the share of a rule's conditions explico could render as readable text; the rest is shown as marked verbatim source on the rule's own card. It says nothing about whether a control is effective or complete. A helper rule can show 0% because its only condition is a construct explico does not render (such as a `with` override); that says nothing about the rules that call it.
- **Example coverage** — `✓ / ✓` means at least one worked example where the rule matches and at least one where it does not; `–` means none.
```

Seven rows for five files, in two tables: `deny` rules get a `control-id`
(REL-001 through REL-004) and sit in the first; helper rules referenced *by*
those `deny` rules (`is_release_candidate`, `exempt_service`,
`all_checks_passed`) are rendered too, without a control-id, in a second
table under **Rules without a control id**. Both tables share the same
columns, and every title links straight to the control's own card.
The **Example coverage** column (`✓ / ✓` = at least one matching and one
non-matching fixture demonstrate this control; `✓ / –` = only one
direction is demonstrated) is exactly the kind of gap-visibility spec §6.7
is for — a thin fixture set shows up here, not as a false "100% covered."
When a rule can't be fully rendered, a **Below 100%** line (absent here, because the pack renders in full)
names it and links to its card, where the construct is shown as source. A closing **How to read this index** section spells out what "Rendered" and
"Example coverage" actually mean, so neither is mistaken for a statement about the control
itself.

The **Policy digest** under the title is a SHA-256 over every `.rego` file
in `samples/policies` — change one byte of policy and it changes; render
the same policies twice (with or without examples) and it's identical.
Every other document carries the same digest's first 12 characters, so a
reader holding one page can confirm it came from the policy version the
index describes. It depends only on the policy source, never on the
examples, the data document, or explico's own version.

Because REL-001 through REL-004 declare `frameworks:` in their METADATA,
the index also builds a **Controls by framework** matrix — which controls
satisfy SOC 2 CC8.1, which satisfy ISO 27001 A.8.32, and so on — straight
from that METADATA, with no extra authoring. A policy set where no rule
names a framework gets no such section at all.

## 2. Inspect a rule referencing another rule

Open `release-evidence.md`. REL-002's card doesn't inline the logic of the
rules it depends on — it links to them:

```markdown
<a id="rel-002"></a>

## REL-002 — Pipeline evidence

...

**All of the following are true:**

- see rule [`is_release_candidate`](#release-evidence-is-release-candidate)
- rule [`exempt_service`](release-exemptions.md#release-exemptions-exempt-service) does not match
- for some stage in `pipeline ▸ stages`
- `pipeline ▸ stages ▸ [each stage] ▸ status` is not `"passed"`
```

Two different link shapes: `is_release_candidate` is in the *same* package
as REL-002, so it's a bare `#anchor`; `exempt_service` lives in
`release.exemptions`, a different package, so it's
`release-exemptions.md#anchor`. Neither rule's logic is ever inlined or
flattened into REL-002's card — spec §1.2's own explicit non-goal. Below
that, the worked examples show real evaluated outcomes:

```markdown
**Worked examples**

- **failed security scan** — ❌ denied *(Situation 1)*
  *"pipeline stage security-scan did not pass"*
  - `pipeline ▸ stages ▸ [each stage] ▸ status`: `"passed", "failed"`
- **approved standard release** — ✅ allowed
  - `pipeline ▸ stages ▸ [each stage] ▸ status`: `"passed", "passed"`
```

`pipeline ▸ stages ▸ [each stage] ▸ status` collects the status of *every*
stage in the fixture's input, comma-separated — not just the one that
failed. Every value here came from a real `opa eval` run against
`samples/examples/04-failed-security-scan.json` and
`01-approved-standard-release.json`; explico never predicts what a rule
would do.

## 3. Inspect the fallback mechanism and coverage

Open `release-governance.md`. REL-004's first situation counts approvals with a
comprehension, and explico renders it, because everything inside it is
something explico can render:

```markdown
- (the set of `change ▸ approvals ▸ [each a]` such that for some a in `change ▸ approvals`; `change ▸ approvals ▸ [each a] ▸ role` is `"release-manager"`) is empty
```

The helper rule `all_checks_passed` uses an `every` block, which explico renders as a
nested list. It says outright that an empty collection satisfies `every`, since that is
the one thing a reader could miss:

```markdown
## release.governance.all_checks_passed

- for every check in `pipeline ▸ checks`, all of the following are true (vacuously true when there are none)
  - `pipeline ▸ checks ▸ [each check] ▸ status` is `"passed"`

*Rendering coverage: 2 of 2 conditions*
```

and the package footer says how much of the whole package rendered:

```markdown
*Package rendering coverage: 10 of 10 conditions (100%)*
```

An `every` is rendered all-or-nothing: if its collection is not a plain path, or any
condition inside it is something explico has no phrase for, the whole block is shown as
source instead (`docs/showcase/fallbacks.md` shows one). It never shows half of one. See
[`policy-authoring.md`](policy-authoring.md#fallback-and-coverage-design-not-defect)
for why a block shown as source is the intended, honest behavior.

## 4. Diff two versions

Make one small change — a metadata edit, not a logic change — to see how
`diff` classifies it. Copy `samples/policies` somewhere and edit
`governance/release_governance.rego`'s title and description only (leave
every condition untouched), then:

```
explico diff samples/policies <your-copy> --out report.md
```

```
## Summary

| Category | Count |
|---|---|
| REMOVED | 0 |
| ADDED | 0 |
| LOGIC_CHANGED | 0 |
| DOCS_CHANGED | 1 |
| UNCHANGED | 6 |

## What changed

- REL-004 — Release governance evidence (rev. 2) — documentation changed: title, description
```

Right after the summary table, a short **What changed** section lists every
non-`UNCHANGED` control in one line each — here, just REL-004, naming
exactly the two metadata fields that actually differ. A `LOGIC_CHANGED`
control gets its own indented sub-bullets instead (`condition removed:
...`/`condition added: ...`, a changed message, a changed body count or
default outcome — whatever the model can mechanically name; see
`src/test/resources/diff/change-summary/` for a worked example of each
shape), and its label links straight to the full card below. `REMOVED` and
`ADDED` controls link too; `DOCS_CHANGED` has no card in this report, so its
label stays plain text.

The report's one changed section is REL-004, correctly classified
`DOCS_CHANGED` — its logic hash is identical (no condition, operator, or
operand changed), only its metadata hash differs:

```markdown
**DOCS_CHANGED**

| | Old | New |
|---|---|---|
| Title | Release governance evidence | Release governance evidence (rev. 2) |
| Description | Production releases require a release manager approval, must not fall<br>inside a freeze window, and all mandatory checks must have passed. | Updated wording only: production releases require a release manager<br>approval, must not fall inside a freeze window, and all mandatory<br>checks must have passed. |
| Frameworks | SOC 2 CC8.1, DORA Art. 9 | SOC 2 CC8.1, DORA Art. 9 |
```

Because REL-004 renders in full, the report has no closing warning. When a changed control
contains something shown as source, the report ends with `⚠ N changed controls contain
conditions that could not be rendered; review source diffs directly.`

The other six controls are `UNCHANGED` and don't get a section at all —
only non-`UNCHANGED` controls appear in the report body, though every
category (including `UNCHANGED`) is always counted in the summary table.

For `LOGIC_CHANGED`, `ADDED`, `REMOVED`, and the control-id-preserving
rename that must never split into a spurious remove-and-add pair, see this
project's own `src/test/resources/diff/diff-all-categories/` fixture pair
— a real old/new variant of these same five policies exercising every
category in one report, which is exactly what this project's own test
suite verifies against on every build.

## 5. The same walkthrough, programmatically

Everything above is also available as a library, without shelling out to the
CLI at all. This is the exact code in
[`src/docsSnippets/kotlin/QuickstartKotlin.kt`](../src/docsSnippets/kotlin/QuickstartKotlin.kt)
— compiled as part of this project's own build, so it can never drift out of
date with the real `Explico` API:

```kotlin
import io.explico.Explico
import java.nio.file.Path

fun main() {
    val policySet = Explico.load(Path.of("samples/policies"))
    val examples = Explico.loadExamples(Path.of("samples/examples"))
    val rendered = Explico.render(
        policySet,
        Path.of("samples/policies"),
        examples,
        Path.of("samples/data/release/data.json"),
    )
    println(rendered.files["release-approvals.md"])
    println("Coverage: ${rendered.coverage.percent}%")

    // diff() takes two loaded PolicySets -- substitute your own old/new checkouts in practice.
    // Diffing samples/ against itself is a real, runnable no-op: every control comes back UNCHANGED.
    val report = Explico.diff(policySet, policySet)
    println(report.markdown)
}
```

Same `load` → `render` → `diff` sequence as steps 1 and 4 above, same
`policyDir` re-passed to `render` for the same reason the CLI needs it
(worked examples re-invoke `opa eval` against the real files). See
[`user-guide.md`](user-guide.md#library) for the full facade reference,
including the Java-callable form (`@JvmStatic`/`@JvmOverloads` — no
`INSTANCE`, no explicit nulls) and the write-files-to-disk pattern for both
languages.

## Where to go next

- Every flag and exit code, exhaustively: [`user-guide.md`](user-guide.md).
- The same result as data for dashboards and scripts (`--json`): [`json-output.md`](json-output.md).
- Extra, machine-derived worked examples for rules your fixtures leave a gap for (`--generate-examples`): the
  *Generated examples* section of [`user-guide.md`](user-guide.md).
- Writing your own policies for explico to render well:
  [`policy-authoring.md`](policy-authoring.md).
- The pipeline and design rationale: [`../ARCHITECTURE.md`](../ARCHITECTURE.md).
