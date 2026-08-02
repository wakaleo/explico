# Package `release.exemptions`

*Source files: `exemptions/exemptions.rego`*

## release.exemptions.exempt_service

*Rule `exempt_service` in package `release.exemptions` — defined in `exemptions.rego`*

*No description provided in policy metadata.*

**All of the following are true:**

- `deployment ▸ service` is one of `data ▸ release ▸ exempt services`

**Worked examples**

- **exempt legacy service** — matched
  - `deployment ▸ service`: `"legacy-batch"`
- **approved standard release** — not matched
  - `deployment ▸ service`: `"payments-api"`
- **hotfix without change ticket** — not matched
  - `deployment ▸ service`: `"payments-api"`

*Examples are evaluated against this policy version by OPA at generation time.*

*Rendering coverage: 1 of 1 conditions*

---

*Package rendering coverage: 1 of 1 conditions (100%)*
