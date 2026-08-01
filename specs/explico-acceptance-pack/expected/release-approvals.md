# Package `release.approvals`

*Source files: `approvals/change_approval.rego`*

## REL-001 — Production change approval

*Rule `deny` in package `release.approvals` — defined in `change_approval.rego`*
*Frameworks: SOC 2 CC8.1, ISO 27001 A.8.32*

Production deployments must reference an approved change ticket,
and the change author must not approve their own change.

**The rule matches when ANY of the following situations applies:**

### Situation 1 — all of the following are true

- `deployment ▸ environment` is `"production"`
- `change ▸ ticket ▸ approved` is absent or false

*Produces:* "release [deployment id] has no approved change ticket"

### Situation 2 — all of the following are true

- `deployment ▸ environment` is `"production"`
- `change ▸ author` is `change ▸ approver`

*Produces:* "change [change id] was approved by its author"

**Worked examples**

- **hotfix without change ticket** — ❌ denied *(Situation 1)*
  *"release rel-1002 has no approved change ticket"*
  - `deployment ▸ environment`: `"production"`
  - `change ▸ ticket ▸ approved`: absent
  - `change ▸ author`: `"asmith"`
  - `change ▸ approver`: `"bjones"`
- **self-approved change** — ❌ denied *(Situation 2)*
  *"change chg-2003 was approved by its author"*
  - `deployment ▸ environment`: `"production"`
  - `change ▸ ticket ▸ approved`: `true`
  - `change ▸ author`: `"asmith"`
  - `change ▸ approver`: `"asmith"`
- **approved standard release** — ✅ allowed
  - `deployment ▸ environment`: `"production"`
  - `change ▸ ticket ▸ approved`: `true`
  - `change ▸ author`: `"asmith"`
  - `change ▸ approver`: `"bjones"`
- **failed security scan** — ✅ allowed
  - `deployment ▸ environment`: `"staging"`
  - `change ▸ ticket ▸ approved`: `true`
  - `change ▸ author`: `"asmith"`
  - `change ▸ approver`: `"bjones"`

*Rendering coverage: 4 of 4 conditions*

---

*Package rendering coverage: 4 of 4 conditions (100%)*
