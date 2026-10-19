# Package `release.evidence`

*Source files: `evidence/pipeline_evidence.rego`*

<a id="rel-002"></a>

## REL-002 — Pipeline evidence

*Rule `deny` in package `release.evidence` — defined in `pipeline_evidence.rego`*
*Frameworks: SOC 2 CC8.1*

Every pipeline stage must have passed before a release candidate
may be deployed, unless the service is explicitly exempt.

**Effect of a match:** ❌ denied *(by the `deny` naming convention)*

**All of the following are true:**

- see rule [`is_release_candidate`](#release-evidence-is-release-candidate)
- rule [`exempt_service`](release-exemptions.md#release-exemptions-exempt-service) does not match
- for some stage in `pipeline ▸ stages`
- `pipeline ▸ stages ▸ [each stage] ▸ status` is not `"passed"`

*Produces:* "pipeline stage [stage name] did not pass"

**Worked examples**

- **failed security scan** — ❌ denied *(Situation 1)*
  *"pipeline stage security-scan did not pass"*
  - `pipeline ▸ stages ▸ [each stage] ▸ status`: `"passed", "failed"`
- **approved standard release** — ✅ allowed
  - `pipeline ▸ stages ▸ [each stage] ▸ status`: `"passed", "passed"`
- **hotfix without change ticket** — ✅ allowed
  - `pipeline ▸ stages ▸ [each stage] ▸ status`: `"passed"`

*Examples are evaluated against this policy version by OPA at generation time.*

*Rendering coverage: 4 of 4 conditions*

---

<a id="release-evidence-is-release-candidate"></a>

## release.evidence.is_release_candidate

*Rule `is_release_candidate` in package `release.evidence` — defined in `pipeline_evidence.rego`*

Deployments to production or staging count as release candidates.

**All of the following are true:**

- `deployment ▸ environment` is one of `"production", "staging"`

**Worked examples**

- **approved standard release** — matched
  - `deployment ▸ environment`: `"production"`
- **hotfix without change ticket** — matched
  - `deployment ▸ environment`: `"production"`
- **self-approved change** — matched
  - `deployment ▸ environment`: `"production"`

*Examples are evaluated against this policy version by OPA at generation time.*

*Rendering coverage: 1 of 1 conditions*

---

*Package rendering coverage: 5 of 5 conditions (100%)*

*Policy digest: `sha256:5f0bc88b6e15…` (full value in index.md)*
