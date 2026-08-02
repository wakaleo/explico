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
Rendering coverage: 21 of 23 conditions (91%)
```

This writes one Markdown file per Rego package, plus `index.md`. Open
`index.md` first — it's the map of everything else:

```markdown
# Control index

| Control ID | Title | Package | Rule | Coverage | Example coverage | Source file |
|---|---|---|---|---|---|---|
| REL-001 | Production change approval | `release.approvals` | `deny` | 100% | ✓ / ✓ | `approvals/change_approval.rego` |
| REL-002 | Pipeline evidence | `release.evidence` | `deny` | 100% | ✓ / ✓ | `evidence/pipeline_evidence.rego` |
| REL-003 | Artifact provenance | `release.provenance` | `deny` | 100% | ✓ / ✓ | `provenance/artifact_provenance.rego` |
| REL-004 | Release governance evidence | `release.governance` | `deny` | 87% | ✓ / ✓ | `governance/release_governance.rego` |
| — | Release candidate environments | `release.evidence` | `is_release_candidate` | 100% | ✓ / – | `evidence/pipeline_evidence.rego` |
| — | exempt_service | `release.exemptions` | `exempt_service` | 100% | ✓ / ✓ | `exemptions/exemptions.rego` |
| — | Mandatory check completeness | `release.governance` | `all_checks_passed` | 0% | ✓ / – | `governance/release_governance.rego` |

*Overall rendering coverage: 21 of 23 conditions (91%)*
```

Seven rows for five files: `deny` rules get a `control-id` (REL-001
through REL-004) and their own row; helper rules referenced *by* those
`deny` rules (`is_release_candidate`, `exempt_service`,
`all_checks_passed`) get rendered too, without a control-id, sorted last.
The **Example coverage** column (`✓ / ✓` = at least one matching and one
non-matching fixture demonstrate this control; `✓ / –` = only one
direction is demonstrated) is exactly the kind of gap-visibility spec §6.7
is for — a thin fixture set shows up here, not as a false "100% covered."

## 2. Inspect a rule referencing another rule

Open `release-evidence.md`. REL-002's card doesn't inline the logic of the
rules it depends on — it links to them:

```markdown
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

Open `release-governance.md` — REL-004 is the one control below 100%
coverage, and it's below 100% for an honest, visible reason, not a bug:

```markdown
### Situation 1 — all of the following are true

- `deployment ▸ environment` is `"production"`
- ⚠ **not rendered — shown as source:**

  ```rego
  count({a | some a in input.change.approvals; a.role == "release-manager"}) == 0
  ```

*Produces:* "no release manager approval is recorded"
```

`count({a | ...})` is a `count()` call over a set comprehension —
comprehensions are an explicit, permanent non-goal (spec §1.2), so this
condition renders as clearly marked verbatim source instead of guessed
prose. The card's own footer says exactly how much of it this affected:

```markdown
*Rendering coverage: 7 of 8 conditions*

---

*Package rendering coverage: 7 of 9 conditions (77%)*
```

One condition out of eight, in one control, in a five-policy package. See
[`policy-authoring.md`](policy-authoring.md#fallback-and-coverage-design-not-defect)
for why this is the intended, honest behavior — not something to "fix" by
avoiding comprehensions in your own policies.

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
```

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

And because REL-004's own coverage is below 100% (step 3, above), the
report's closing line reflects it:

```
⚠ 1 changed controls contain conditions that could not be rendered; review source diffs directly.
```

The other six controls are `UNCHANGED` and don't get a section at all —
only non-`UNCHANGED` controls appear in the report body, though every
category (including `UNCHANGED`) is always counted in the summary table.

For `LOGIC_CHANGED`, `ADDED`, `REMOVED`, and the control-id-preserving
rename that must never split into a spurious remove-and-add pair, see this
project's own `src/test/resources/diff/diff-all-categories/` fixture pair
— a real old/new variant of these same five policies exercising every
category in one report, which is exactly what this project's own test
suite verifies against on every build.

## Where to go next

- Every flag and exit code, exhaustively: [`user-guide.md`](user-guide.md).
- Writing your own policies for explico to render well:
  [`policy-authoring.md`](policy-authoring.md).
- The pipeline and design rationale: [`../ARCHITECTURE.md`](../ARCHITECTURE.md).
