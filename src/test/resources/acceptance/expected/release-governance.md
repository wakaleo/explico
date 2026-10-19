# Package `release.governance`

*Source files: `governance/release_governance.rego`*

<a id="release-governance-all-checks-passed"></a>

## release.governance.all_checks_passed

*Rule `all_checks_passed` in package `release.governance` — defined in `release_governance.rego`*

*No description provided in policy metadata.*

**All of the following are true:**

- ⚠ **not rendered — shown as source:**

  ```rego
  every check in input.pipeline.checks {
  		check.status == "passed"
  	}
  ```

**Worked examples**

- **approved standard release** — matched
- **hotfix without change ticket** — matched
- **self-approved change** — matched

*Examples are evaluated against this policy version by OPA at generation time.*

*Rendering coverage: 0 of 1 conditions*

---

<a id="rel-004"></a>

## REL-004 — Release governance evidence

*Rule `deny` in package `release.governance` — defined in `release_governance.rego`*
*Frameworks: SOC 2 CC8.1, DORA Art. 9*

Production releases require a release manager approval, must not fall
inside a freeze window, and all mandatory checks must have passed.

**Effect of a match:** ❌ denied *(by the `deny` naming convention)*

**The rule matches when ANY of the following situations applies:**

### Situation 1 — all of the following are true

- `deployment ▸ environment` is `"production"`
- (the set of `change ▸ approvals ▸ [each a]` such that for some a in `change ▸ approvals`; `change ▸ approvals ▸ [each a] ▸ role` is `"release-manager"`) is empty

*Produces:* "no release manager approval is recorded"

### Situation 2 — all of the following are true

- `deployment ▸ environment` is `"production"`
- for some window in `data ▸ release ▸ freeze windows`
- `deployment ▸ timestamp` is at least `data ▸ release ▸ freeze windows ▸ [each window] ▸ start`
- `deployment ▸ timestamp` is at most `data ▸ release ▸ freeze windows ▸ [each window] ▸ finish`

*Produces:* "deployment falls inside freeze window [window name]"

### Situation 3 — all of the following are true

- `deployment ▸ environment` is `"production"`
- rule [`all_checks_passed`](#release-governance-all-checks-passed) does not match

*Produces:* "not all mandatory checks passed"

**Reference data**

- `data ▸ release ▸ freeze windows`: `[{"finish":1767657600,"name":"year-end change freeze","start":1766966400}]`

*Values are read from the supplied data by OPA at generation time.*

**Worked examples**

- **unsigned artifact in freeze window** — ❌ denied
  *"deployment falls inside freeze window year-end change freeze"* *(Situation 2)*
  *"no release manager approval is recorded"* *(Situation 1)*
  - `deployment ▸ environment`: `"production"`
  - `deployment ▸ timestamp`: `1767000000`
- **approved standard release** — ✅ allowed
  - `deployment ▸ environment`: `"production"`
  - `deployment ▸ timestamp`: `1754060000`
- **hotfix without change ticket** — ✅ allowed
  - `deployment ▸ environment`: `"production"`
  - `deployment ▸ timestamp`: `1754060000`

*Examples are evaluated against this policy version by OPA at generation time.*

*Rendering coverage: 8 of 8 conditions*

---

*Package rendering coverage: 8 of 9 conditions (88%)*

*Policy digest: `sha256:5f0bc88b6e15…` (full value in index.md)*
