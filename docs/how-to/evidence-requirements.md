# Evidence compliance requirements

**Goal:** show which requirements in a framework you answer to have at least one control, and fail the build when one has none.

## 1. Tag controls with the requirements they support

In each control's METADATA, list requirements under `custom.frameworks`:

```rego
# custom:
#   control-id: DB-001
#   frameworks:
#     - SOC 2 A1.2
```

`index.md` gains a **Controls by framework** table, one row per requirement.

## 2. List the requirements you must evidence

A plain text file, one requirement per line, `#` for comments (`requirements.txt`):

```text
# Requirements we must evidence
SOC 2 A1.2
SOC 2 CC6.1
```

```sh
java -jar explico.jar render policies --out policy-docs --requirements requirements.txt
```

The index shows each requirement and the controls that answer it:

```markdown
## Requirements coverage

*1 of 2 requirements have at least one control.*

| Requirement | Controls |
|---|---|
| SOC 2 A1.2 | [DB-001](databases.md#db-001) |
| SOC 2 CC6.1 | **No control** |
```

## 3. Fail when one has no control

```sh
java -jar explico.jar render policies --out policy-docs --requirements requirements.txt --fail-on-gaps
echo "exit $?"
```

```text
Rendering coverage: 5 of 5 conditions (100%)
explico: gate failed (--fail-on-gaps): 1 gap(s)
  - requirement SOC 2 CC6.1 has no control
exit 5
```

The documents are written first, so the failing build still leaves the evidence to read. `--fail-on-gaps` also covers documentation gaps in controls.
By default it asks for a description and a framework on every control; change that with `--require`
(`title`, `description`, `frameworks`, `fixture`, `verdict`; the last two need `--examples`):

```sh
--fail-on-gaps --require title,description,frameworks,fixture
```

## Know what this does and does not show

Matching is **exact text**: `SOC 2 A1.2` and `SOC2 A1.2` are different requirements. That is deliberate, so a typo surfaces as a gap
instead of being quietly corrected. The mapping is what the authors *declared* in METADATA; explico does not verify that a control satisfies the
requirement it names.

## Related

[CI recipes](../guides/ci-recipes.md) · [Ratchet the documentation quality](../guides/ci-recipes.md#ratchet-the-documentation-quality)
