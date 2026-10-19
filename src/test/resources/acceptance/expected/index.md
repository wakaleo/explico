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
| — | [Mandatory check completeness](release-governance.md#release-governance-all-checks-passed) | `release.governance` | `all_checks_passed` | 0% | ✓ / – | `governance/release_governance.rego` |

## Controls by framework

| Framework requirement | Controls |
|---|---|
| DORA Art. 9 | [REL-004](release-governance.md#rel-004) |
| ISO 27001 A.8.32 | [REL-001](release-approvals.md#rel-001), [REL-003](release-provenance.md#rel-003) |
| SLSA v1.0 L2 | [REL-003](release-provenance.md#rel-003) |
| SOC 2 CC8.1 | [REL-001](release-approvals.md#rel-001), [REL-002](release-evidence.md#rel-002), [REL-004](release-governance.md#rel-004) |

*Overall rendering coverage: 22 of 23 conditions (95%)*

*Below 100%: [Mandatory check completeness](release-governance.md#release-governance-all-checks-passed) -- each shows the constructs it could not render as source on its own card.*

## How to read this index

- **Rendered** — the share of a rule's conditions explico could render as readable text; the rest is shown as marked verbatim source on the rule's own card. It says nothing about whether a control is effective or complete. A helper rule can show 0% because its only condition is a construct explico does not render (such as every); that says nothing about the rules that call it.
- **Example coverage** — `✓ / ✓` means at least one worked example where the rule matches and at least one where it does not; `–` means none.
