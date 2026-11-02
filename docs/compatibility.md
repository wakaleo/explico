# Compatibility

What explico promises to keep working in 1.x, what it does not, and how a change is announced. From 1.0, explico follows [semantic versioning](https://semver.org) for what is listed
under **What is stable**: a release that removes or changes it is a new major version. Everything else may change in any release, and the [CHANGELOG](../CHANGELOG.md) says so when it does.

Two files in the repository state the stable surface mechanically, so you do not have to trust this page alone: [`api/explico.api`](../api/explico.api) (every public type and member of the
library) and [`api/explico.cli`](../api/explico.cli) (every command, option and exit code). The build fails when either drifts from the code, so a change to the surface is always a
visible, reviewed diff.

## What is stable

### The library, from Kotlin and Java

- **The facade, `Explico`:** `load(dir)` and `load(dir, regoVersion)`, `loadExamples(dir)`, `render(policies)` and `render(policies, options)`, `diff(old, new)` and `diff(old, new, options)`. Nothing else is
  an entry point.
- **The options:** `RenderOptions` and `DiffOptions`, with `ControlIdOptions`, `CompanionLink`, `DataVersions` and `VersionLabels`, including their Java builders. A new option is added as a last
  field with a default.
- **The results:** `RenderedDocs` (`files`, `coverage`, `gaps`, `findings`, `generatedExamples`, and `writeTo`) and `DiffReport` (`markdown`, `json`, `changes`, the digests, the example comparison,
  and `writeTo`), `DiffChange` and `DiffCategory`.
- **The loaded set:** `PolicySet` with `packages`, `regoVersion` and `sourceDir`, and `PolicyPackage`'s `path` and `sourceFiles`. `sourceDir` is part of the set's equality: two loads of copies of one
  directory are unequal sets with equal packages.
- **The exceptions:** `ExplicoException` and exactly its six subclasses, each with the properties shown: `OpaFailedException` (`exitCode`, `stderr`), `OpaNotAvailableException`,
  `OutputDirectoryException` (`path`), `DuplicateControlIdException` (`controlIds`), `DuplicateFixtureNameException` (`name`) and `InvalidMaskException`. An invalid argument is an
  `IllegalArgumentException` and a missing directory an `IOException`; neither is an `ExplicoException`.

### The command line

The commands are `explico render`, `explico diff`, `explico demo` and `explico version`, with the options listed in [`api/explico.cli`](../api/explico.cli). A removed option is announced as described under
[Deprecation](#deprecation) before it goes. The exit codes are part of the contract:

| Code | Meaning |
|---|---|
| `0` | success |
| `1` | usage or refused output |
| `2` | opa rejected the input |
| `3` | opa missing or not 1.x |
| `4` | duplicate control id or fixture name |
| `5` | a CI gate failed |

A gate (`--fail-under`, `--fail-on-gaps`, `diff --fail-on`) writes the output first and then exits `5`; it never suppresses output.

### The JSON

`explico.json` has the schema id `explico/render/1` and the diff JSON `explico/diff/1` (see [JSON output](json-output.md)). Additive fields do not bump the schema: a consumer must tolerate keys it does
not know. Removing or renaming a field, or changing what one means, gets a new schema number.

## What is not stable

- **The Markdown wording and layout.** Phrases, headings, the order of sections and the exact text of every card can and do change between releases,
  and the sample output in this repository is regenerated when they do. Read the JSON, not the Markdown, in a tool.
- **The structural model.** The types marked `@ExplicoStructuralModel` (the parsed shape of a policy: rules, bodies, conditions, operands, path segments, METADATA, and `DiffEntry` with `DiffReport.entries`)
  are public but outside the promise, because a new Rego construct adds a variant. Kotlin callers get a warning when they use them. Destructuring a marked property or calling `copy()` on a data class
  that exposes one is not warned about, but is equally unstable. Use `explico.json` or `DiffReport.changes` instead.
- **Text written to stderr.** Messages, warnings and the wording of a gate failure are for people; the exit code is the contract.
- **Generated file names**, except `index.md`, `explico.json` and the `controls/` directory. The name of a package's page, for example, is derived and may change.

## Typed diff facts

A change in the diff JSON whose logic changed carries typed `facts`. They are a **closed set of 15 kinds**, and the rule is strict: **a new fact kind means a new schema version** (`explico/diff/2`),
never an additive change, so a consumer that handles all of these can rely on there being no others within a schema version. The sentences are for people and may be reworded; the kinds are the contract.

| Kind | Meaning |
|---|---|
| `condition-removed` | A condition the old rule had and the new one does not. |
| `condition-added` | A condition the new rule has and the old one did not. |
| `message-removed` | A produced message only the old rule had. |
| `message-added` | A produced message only the new rule has. |
| `bodies-changed` | The number of bodies (situations) changed. |
| `default-outcome-changed` | The `default` value differs, or appears on one side only. |
| `value-changed` | A constant rule's value differs. |
| `source-fallback-changed` | The code shown as source (conditions explico could not render) is a different set. |
| `situations-reordered` | The same situations in a different order. |
| `situation-value-changed` | A situation yields a different value. |
| `situation-removed` | A situation with no counterpart in the new rule. |
| `situation-added` | A situation with no counterpart in the old rule. |
| `structure-changed` | The logic changed but nothing above names it. |
| `reference-data-changed` | A data document the control reads differs. |
| `documentation-changed` | The METADATA changed too. Always the last fact. |

The fields each kind carries are in [JSON output](json-output.md#typed-facts-of-a-logic_changed-change).

## Deprecation

From 1.0, nothing in the stable surface is removed or changed incompatibly without notice. A deprecation ships in a minor release and is announced in the [CHANGELOG](../CHANGELOG.md): in the library the
declaration is marked `@Deprecated`, naming its replacement; a CLI option keeps working and its `--help` text says it is deprecated and what replaces it. The removal happens no earlier than the next major
version, so there is always at least one minor release of warnings (the marker or help note, and the CHANGELOG entry) before a removal. A security fix is the one exception, and the CHANGELOG says when it
applies.

Before 1.0 there was no such promise. The changes made on the way to 1.0 (the long forms of `render` and `diff` replaced by options objects, four CLI options renamed, the structural model made opt-in) are recorded in the CHANGELOG.

## Updating the baselines

Both baselines are checked in and compared on every build, so an accidental change fails `./gradlew check`:

- **The library API:** `api/explico.api` is written by Kotlin's ABI validation. `checkKotlinAbi`, part of `check`, fails when the public surface differs from the file. After an intended change, run
  `./gradlew updateKotlinAbi` and commit the file with the change.
- **The CLI:** `api/explico.cli` is written from the real command tree. The test that compares it fails on any difference; after an intended change, run
  `./gradlew test --tests '*CliBaselineTest*' -Dexplico.updateCli=true` and commit the file.

A baseline diff is a reviewed change: read it in the pull request, because it is exactly what consumers will see. The test that pins the list of public classes (`ApiBaselineTest`) has to be edited in the same
change when a type is added or removed, so a new public class is never an accident.
