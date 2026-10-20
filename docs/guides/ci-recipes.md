# CI recipes

explico is built to run in a pipeline: it is deterministic, it writes its output **before** it decides whether to fail, and its exit
codes say why it stopped.

!!! note "Versions"
    The gate flags (`--fail-under`, `--fail-on-gaps`, `--requirements`, `diff --fail-on`) and exit code `5` are in **0.2.0**. If that
    release is not yet published when you read this, build the jar yourself (`./gradlew shadowJar`, then use
    `build/libs/explico-0.2.0-all.jar`); 0.1.1 renders and diffs but has no gates. Set `EXPLICO_VERSION` below to the version you use.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Success |
| 1 | Usage error (bad flags, missing required option, nonexistent path) |
| 2 | Rego parse error: `opa`'s own message is passed through verbatim |
| 3 | `opa` missing or not version 1.x |
| 4 | Duplicate control ids (`diff`) or duplicate fixture names (`render --examples`) |
| 5 | A CI gate failed: the output is still written and stderr says which gate and why |

## What you need on the runner

- A JDK, version 17 or newer.
- `opa` 1.x. CI for explico pins **1.19.0**.
- The explico jar. Releases attach `explico-<version>-all.jar`.

## One workflow: gates and drift together

If you commit the rendered documents (see [From Rego to living documentation](living-documentation.md)), use **one** `render` command for
both the gates and the drift check. They must use the same flags, and must not include `--source-url` (a commit-pinned link changes on
every commit):

```yaml
      - name: Render, enforce the gates, and fail if the committed documents are stale
        run: |
          java -jar explico.jar render policies --out docs/policy-docs \
            --examples examples --data data \
            --requirements requirements.txt \
            --fail-under 90 --fail-on-gaps
          git diff --exit-code -- docs/policy-docs
```

Publish the commit-pinned version separately (next section), where nothing is compared against a committed file.

## GitHub Actions: render, gate and publish the documents

```yaml
name: Policy docs
on:
  pull_request:
  push:
    branches: [main]

env:
  EXPLICO_VERSION: "0.2.0"

jobs:
  docs:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v6
        with: { distribution: temurin, java-version: "21" }
      - uses: open-policy-agent/setup-opa@v2
        with: { version: "1.19.0" }
      - name: Download explico
        run: >
          curl -fsSL -o explico.jar
          "https://github.com/wakaleo/explico/releases/download/v${EXPLICO_VERSION}/explico-${EXPLICO_VERSION}-all.jar"
      - name: Render the control documents
        run: >
          java -jar explico.jar render policies --out policy-docs
          --examples policy-examples
          --requirements requirements.txt
          --source-url "https://github.com/${{ github.repository }}/blob/${{ github.sha }}/policies/{file}#L{line}"
          --fail-under 90 --fail-on-gaps
      - uses: actions/upload-artifact@v7
        if: always()
        with: { name: policy-docs, path: policy-docs }
```

Notes:

- `if: always()` uploads the documents even when a gate fails, because explico writes them first. That is what you want when you are
  trying to see *which* control is below the bar.
- Pinning the commit in `--source-url` (`${{ github.sha }}`, not a branch) is what makes the published document trustworthy: its links
  keep pointing at the code it describes.
- `--fail-under` is compared exactly. **Pin `EXPLICO_VERSION`** and re-baseline the threshold when you upgrade: the figure can move
  between explico versions with no change to your policies. See [Coverage](../concepts/coverage.md).

## What each gate checks

| Flag | Fails (exit 5) when |
|---|---|
| `render --fail-under <percent>` | overall rendering coverage is below the threshold |
| `render --fail-on-gaps` | a control has no description or no framework, two controls share a title, and (with `--requirements`) a requirement has no control or a control names a framework that is not on your list |
| `diff --fail-on <categories>` | any control is in one of the comma-separated categories `ADDED`, `REMOVED`, `LOGIC_CHANGED`, `DOCS_CHANGED` |

`--requirements <file>` is a text file with one requirement per line (blank lines and `#` comments ignored). Matching against a
control's `frameworks` is by exact string, deliberately: a typo such as `SOC2 CC8.1` for `SOC 2 CC8.1` shows up as an unlisted framework
and as a requirement with no control, instead of being silently accepted.

## Review a policy change: diff the pull request against its base

```yaml
name: Policy change report
on: pull_request

env:
  EXPLICO_VERSION: "0.2.0"

jobs:
  diff:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
        with: { path: head }
      - uses: actions/checkout@v7
        with: { ref: "${{ github.base_ref }}", path: base }
      - uses: actions/setup-java@v6
        with: { distribution: temurin, java-version: "21" }
      - uses: open-policy-agent/setup-opa@v2
        with: { version: "1.19.0" }
      - name: Download explico
        run: >
          curl -fsSL -o explico.jar
          "https://github.com/wakaleo/explico/releases/download/v${EXPLICO_VERSION}/explico-${EXPLICO_VERSION}-all.jar"
      - name: Diff the policies
        run: >
          java -jar explico.jar diff base/policies head/policies
          --out policy-diff.md --json policy-diff.json
          --fail-on REMOVED,LOGIC_CHANGED
      - name: Show the report on the run page
        if: always()
        run: cat policy-diff.md >> "$GITHUB_STEP_SUMMARY"
      - uses: actions/upload-artifact@v7
        if: always()
        with: { name: policy-diff, path: "policy-diff.*" }
```

`diff` classifies every control as `ADDED`, `REMOVED`, `LOGIC_CHANGED`, `DOCS_CHANGED` or `UNCHANGED`, matching by control id, so a
renamed rule that keeps its id is not reported as a removal and an addition. The report starts with a **What changed** section naming,
for each changed control, exactly which conditions, messages or documentation fields moved. With `--fail-on REMOVED,LOGIC_CHANGED` a
pull request that changes what a control *does* fails until someone looks, while a wording-only change passes.

## Keep committed documents from drifting

If you commit the rendered documents (as this project does for its own samples), regenerate them in CI and fail on any difference:

```yaml
      - name: Regenerate and check for drift
        run: |
          java -jar explico.jar render policies --out docs/policy-docs --examples policy-examples
          git diff --exit-code -- docs/policy-docs
```

Because output is byte-identical for identical inputs, any difference means the policy changed without its document being refreshed. Use
the same flags as the gate step, and no `--source-url`; see [One workflow](#one-workflow-gates-and-drift-together).

## GitLab CI

```yaml
policy-docs:
  image: eclipse-temurin:21
  variables:
    EXPLICO_VERSION: "0.2.0"
    OPA_VERSION: "1.19.0"
  before_script:
    - curl -fsSL -o /usr/local/bin/opa "https://github.com/open-policy-agent/opa/releases/download/v${OPA_VERSION}/opa_linux_amd64_static"
    - chmod +x /usr/local/bin/opa
    - curl -fsSL -o explico.jar "https://github.com/wakaleo/explico/releases/download/v${EXPLICO_VERSION}/explico-${EXPLICO_VERSION}-all.jar"
  script:
    - java -jar explico.jar render policies --out policy-docs --fail-under 90 --fail-on-gaps
  artifacts:
    when: always
    paths: [policy-docs]
```

## Machine-readable results

Add `--json` to `render` (or `--json <file>` to `diff`) for a structured result that dashboards and GRC tools can consume: every
rule, condition, verdict and coverage figure. The schema and what each field promises is in [JSON output](../json-output.md).
