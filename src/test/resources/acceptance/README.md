# explico acceptance pack

A curated set of SDLC release-governance Rego policies that serves three purposes
at once:

1. **Acceptance tests** — the tables below are the authoritative, decided-ahead
   assertions that the generated markdown must satisfy.
2. **Examples** — the `samples/` content referenced by the explico README.
3. **Documentation** — running `explico render policies --examples examples
   --data data` over this pack produces the reference output shown in the docs.

Everything in this pack has been validated against OPA 1.19: all policies parse,
all annotations are read by `opa inspect`, and every verdict in the tables below
was produced by `opa eval`, not asserted by hand.

## Layout

```
policies/            5 packages, one control area each
examples/            6 input fixtures (explico worked-example format)
data/release/        data document (exempt services, freeze windows)
expected/            ahead-of-time expected markdown (see "Two-tier testing")
```

## The controls

| File | Control | Constructs deliberately exercised |
|---|---|---|
| `approvals/change_approval.rego` | REL-001 Production change approval | eq vs literal, negated truthy, **path-vs-path** eq, two bodies, sprintf messages, full metadata → **100% coverage showcase** |
| `evidence/pipeline_evidence.rego` | REL-002 Pipeline evidence | `some … in`, var-rooted path (`stage.status`), `!=`, set membership, **helper rule** (`is_release_candidate`), **cross-package negated reference** (`exemptions.exempt_service`) |
| `provenance/artifact_provenance.rego` | REL-003 Artifact provenance | negated builtin (`startswith`), **quoted key-literal path** (`labels["signed-off-by"]`), string-literal message (no sprintf) |
| `governance/release_governance.rego` | REL-004 Release governance evidence | **comprehension → fallback block**, `data.*` paths, numeric `>=`/`<=`, helper containing **`every` → fallback**, three bodies → **coverage < 100%** |
| `exemptions/exemptions.rego` | (helper, **no METADATA**) | name-based identity, visible "no description" gap marker, `data.*` membership |

## Two-tier testing

**Tier 1 — key-aspect assertions (authored ahead, stable).** JUnit tests derived
from the tables in this README. They assert that the generated markdown for each
control **contains** the listed phrases/structures, without pinning whitespace or
ordering. These are the acceptance tests; they must never be weakened to make the
implementation pass.

**Tier 2 — byte-exact goldens (approval-style).** `expected/release-approvals.md`
is the ahead-of-time proposal for the full render of the simplest package. It is
normative for structure and phrasing. At first successful render, cosmetic
details (exact blank lines, horizontal rules) may be reconciled ONCE, the file is
then frozen, and all further packages get byte-exact goldens generated from the
approved renderer.

## Tier 1 assertions

### REL-001 (`release-approvals.md`)
- Heading `## REL-001 — Production change approval`; frameworks line lists
  `SOC 2 CC8.1` and `ISO 27001 A.8.32`.
- Exactly two `### Situation` headings.
- Contains: `` `deployment ▸ environment` is `"production"` ``
- Contains: `` `change ▸ ticket ▸ approved` is absent or false``
- Contains: `` `change ▸ author` is `change ▸ approver` `` (path on BOTH sides)
- Produces lines: `release [deployment id] has no approved change ticket` and
  `change [change id] was approved by its author`.
- Coverage footer reports 4 of 4; no ⚠ fallback blocks anywhere in the file.

### REL-002 (`release-evidence.md`)
- Contains a rendered `for some stage in` line referencing
  `` `pipeline ▸ stages` `` and a var-rooted condition rendering as
  `` `pipeline ▸ stages ▸ [each stage] ▸ status` is not `"passed"` ``.
- Contains: `` `deployment ▸ environment` is one of `"production"`, `"staging"` ``
  (inside the `is_release_candidate` card).
- Contains a cross-package link: `rule [`exempt_service`](release-exemptions.md#`
  … `) does not match`.
- No ⚠ fallback blocks.

### REL-003 (`release-provenance.md`)
- Contains: `` `artifact ▸ source branch` `` and `does not start with `"release/"``
  (negated builtin phrasing).
- Contains quoted key-literal breadcrumb: `` `artifact ▸ labels ▸ "signed-off-by"` ``.
- Situation 2 *Produces:* the literal string `artifact carries no signed-off-by label`.

### REL-004 (`release-governance.md`)
- Exactly three `### Situation` headings.
- Situation 1 contains a ⚠ **not rendered — shown as source** block whose fenced
  content includes `count({a |`.
- Situation 2 fully rendered: `` `deployment ▸ timestamp` is at least `` …
  `` `data ▸ release ▸ freeze windows` `` breadcrumbs present.
- Situation 3 references rule `all_checks_passed`; the `all_checks_passed` card
  contains a ⚠ block whose fenced content includes `every check in`.
- Coverage footer reports **less than 100%** and the card lists the fallback count.

### Helper with no metadata (`release-exemptions.md`)
- Heading falls back to `## release.exemptions.exempt_service`.
- Contains the marker `*No description provided in policy metadata.*`
- Contains: `` `deployment ▸ service` is one of `` … `` `data ▸ release ▸ exempt services` ``.

### index.md
- Lists all five controls; REL-001..004 sorted before the id-less helper.
- Example-coverage column: REL-001 `✓ / ✓`; REL-002 `✓ / ✓`; REL-003 `✓ / ✓`;
  REL-004 `✓ / ✓`; `exempt_service` `✓ / ✓`.
- Overall coverage line present and below 100%.

## Fixture verdict matrix (produced by `opa eval`, OPA 1.19)

Matched rule bodies per fixture — the worked-examples section of each card must
agree with this table exactly:

| Fixture | REL-001 | REL-002 | REL-003 | REL-004 | exempt_service |
|---|---|---|---|---|---|
| 01 approved standard release | — | — | — | — | — |
| 02 hotfix without change ticket | **S1** | — | **S1** | — | — |
| 03 self-approved change | **S2** | — | — | — | — |
| 04 failed security scan | — | **S1** | — | — | — |
| 05 exempt legacy service | — | — (exempt) | — | — | **true** |
| 06 unsigned artifact in freeze window | — | — | **S2** | **S1, S2** | — |

Messages produced (verbatim from `opa eval`):

- 02 → `release rel-1002 has no approved change ticket`;
  `artifact was built from branch hotfix/urgent-fix, not a release branch`
- 03 → `change chg-2003 was approved by its author`
- 04 → `pipeline stage security-scan did not pass`
- 06 → `artifact carries no signed-off-by label`;
  `deployment falls inside freeze window year-end change freeze`;
  `no release manager approval is recorded`

Notes:
- Fixture 05 demonstrates the exemption path: the pipeline stage failed, yet
  REL-002 does not fire because `exempt_service` is true. The REL-002 card's
  worked examples must show 05 as **allowed** with
  `` `deployment ▸ service`: `"legacy-batch"` `` visible.
- REL-004 body attribution: bodies 1 and 3 produce string literals, body 2 a
  sprintf template — all distinct, so *(Situation N)* labels are required.

## Regenerating the verdict matrix

```
for fx in examples/*.json; do
  jq .input "$fx" > /tmp/input.json
  opa eval --format json --input /tmp/input.json \
    --data policies --data data/release/data.json "data.release"
done
```

Any change to a policy or fixture requires re-running this and updating the
matrix in the same commit.
