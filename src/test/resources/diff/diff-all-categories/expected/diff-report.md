# Policy diff report

*This report shows structural changes only. It does not evaluate whether the policy became stricter or more permissive.*

## Summary

| Category | Count |
|---|---|
| REMOVED | 1 |
| ADDED | 1 |
| LOGIC_CHANGED | 1 |
| DOCS_CHANGED | 1 |
| UNCHANGED | 4 |

## Changes

**REMOVED**

**⚠ This control has been removed.**

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

*Rendering coverage: 4 of 4 conditions*

---

**ADDED**

## REL-005 — New deployment window check

*Rule `deny` in package `release.newcheck` — defined in `new_check.rego`*

Deployments must not be scheduled outside the approved maintenance window.

**All of the following are true:**

- `deployment ▸ environment` is `"production"`
- `deployment ▸ within maintenance window` is absent or false

*Produces:* "deployment scheduled outside the approved maintenance window"

*Rendering coverage: 2 of 2 conditions*

---

**LOGIC_CHANGED**

## REL-003 — Artifact provenance

*Rule `deny` in package `release.provenance` — defined in `artifact_provenance.rego`*
*Frameworks: ISO 27001 A.8.32, SLSA v1.0 L2*

Production artifacts must be built from a release branch and carry
a sign-off label identifying who approved the build.

**The rule matches when ANY of the following situations applies:**

### Situation 1 — all of the following are true

- `deployment ▸ environment` is `"production"`
- `artifact ▸ source branch` does not start with `"releases/"`

*Produces:* "artifact was built from branch [artifact source branch], not a release branch"

### Situation 2 — all of the following are true

- `deployment ▸ environment` is `"production"`
- `artifact ▸ labels ▸ "signed-off-by"` is absent or false

*Produces:* "artifact carries no signed-off-by label"

*Rendering coverage: 4 of 4 conditions*

**Source diff**

```rego
  deny contains msg if {
  	input.deployment.environment == "production"
- 	not startswith(input.artifact.source_branch, "release/")
+ 	not startswith(input.artifact.source_branch, "releases/")
  	msg := sprintf("artifact was built from branch %v, not a release branch", [input.artifact.source_branch])
  }
  
  deny contains msg if {
  	input.deployment.environment == "production"
  	not input.artifact.labels["signed-off-by"]
  	msg := "artifact carries no signed-off-by label"
  }
```

---

**DOCS_CHANGED**

| | Old | New |
|---|---|---|
| Title | Release governance evidence | Release governance evidence (updated) |
| Description | Production releases require a release manager approval, must not fall<br>inside a freeze window, and all mandatory checks must have passed. | Updated description text only -- production releases require a release<br>manager approval, must not fall inside a freeze window, and all mandatory<br>checks must have passed. |
| Frameworks | SOC 2 CC8.1, DORA Art. 9 | SOC 2 CC8.1, DORA Art. 9 |

---

⚠ 1 changed controls contain conditions that could not be rendered; review source diffs directly.
