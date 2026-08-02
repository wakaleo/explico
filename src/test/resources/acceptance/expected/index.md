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
