# Package `release.provenance`

*Source files: `provenance/artifact_provenance.rego`*

## REL-003 — Artifact provenance

*Rule `deny` in package `release.provenance` — defined in `artifact_provenance.rego`*
*Frameworks: ISO 27001 A.8.32, SLSA v1.0 L2*

Production artifacts must be built from a release branch and carry
a sign-off label identifying who approved the build.

**The rule matches when ANY of the following situations applies:**

### Situation 1 — all of the following are true

- `deployment ▸ environment` is `"production"`
- `artifact ▸ source branch` does not start with `"release/"`

*Produces:* "artifact was built from branch [artifact source branch], not a release branch"

### Situation 2 — all of the following are true

- `deployment ▸ environment` is `"production"`
- `artifact ▸ labels ▸ "signed-off-by"` is absent or false

*Produces:* "artifact carries no signed-off-by label"

**Worked examples**

- **hotfix without change ticket** — ❌ denied *(Situation 1)*
  *"artifact was built from branch hotfix/urgent-fix, not a release branch"*
  - `deployment ▸ environment`: `"production"`
  - `artifact ▸ source branch`: `"hotfix/urgent-fix"`
  - `artifact ▸ labels ▸ "signed-off-by"`: `"bjones"`
- **unsigned artifact in freeze window** — ❌ denied *(Situation 2)*
  *"artifact carries no signed-off-by label"*
  - `deployment ▸ environment`: `"production"`
  - `artifact ▸ source branch`: `"release/2026.08"`
  - `artifact ▸ labels ▸ "signed-off-by"`: absent
- **approved standard release** — ✅ allowed
  - `deployment ▸ environment`: `"production"`
  - `artifact ▸ source branch`: `"release/2026.08"`
  - `artifact ▸ labels ▸ "signed-off-by"`: `"bjones"`
- **self-approved change** — ✅ allowed
  - `deployment ▸ environment`: `"production"`
  - `artifact ▸ source branch`: `"release/2026.08"`
  - `artifact ▸ labels ▸ "signed-off-by"`: `"cdavis"`

*Examples are evaluated against this policy version by OPA at generation time.*

*Rendering coverage: 4 of 4 conditions*

---

*Package rendering coverage: 4 of 4 conditions (100%)*
