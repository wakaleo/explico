# Changelog

## 0.2.0

A new opt-in feature, and fixes for two defects found while building it. Recommended over 0.1.1, especially on Windows.

- **Added:** `every` blocks are rendered: `every x in coll { ... }` becomes a head bullet ("for every x in `coll`, all of the following are true (vacuously true when there are none)") with its conditions nested beneath it; the two-variable form and nested `every`s work too. All-or-nothing, like comprehensions: a collection that is not a plain path, or anything unrenderable inside, keeps the whole block as source. `kind: "every"` with nested `conditions` in `explico.json`. This changes the acceptance pack's own figures (the `every` helper is now 2 of 2; overall 24 of 24, 100%, was 22 of 23) -- a deliberate, approved amendment of the frozen acceptance README (spec §14.49). Note for library consumers: the public sealed `Condition` gains a variant (`Condition.Every`), so an exhaustive Kotlin `when` over it needs a new branch.
- **Improved:** real-world coverage (measured on Gatekeeper, Trivy, Regal and conftest): a declare-only `some i, j` and a bare `true` statement are no longer conditions; `import data.lib.pkg.fn` / `.rule` resolves a bare call or reference to that function or rule; a function's `msg := sprintf(...)` return is shown as its produced text. Gatekeeper 67% -> 81%, conftest 72% -> 79%. Spec §14.50.
- **Improved:** a free index variable (`pods[i]`, declared or not) is now understood: used once it is the wildcard `_` ("any of: ..."); used more than once it is introduced by a "for some index or key `i` of `pods`" bullet and every later use reads `[each i]`. `input[_]` no longer renders an empty breadcrumb. The public sealed `Condition` gains `SomeKey` (see the note on `Every`). Gatekeeper 81% -> 88%, conftest 79% -> 83%. Spec §14.51.
- **Note for `--fail-under` users:** rendering coverage is measured against what explico understands, so it can move on upgrade without any policy change (more constructs render; statements that test nothing stop counting). Pin the explico version in CI and re-baseline the threshold when you upgrade.
- **Added:** `--generate-examples` (library: `Explico.render(..., generateExamples = true)`). explico derives worked examples itself from
  each rule's conditions where the author's own fixtures leave a gap: inputs that satisfy a rule body and "near-misses" that break exactly one
  condition, each **verified by `opa eval`** and shown in a separate, labelled *Generated examples* section with mechanical names. Off by
  default (output is byte-identical without it). Bodies it cannot model get none.
- **Added:** `render --source-url <template>` links every card's *defined in*, every package page's source files and every index row to the Rego line in your repository
  (`{file}` required, `{line}` optional; path segments percent-encoded). `sourceLine`/`sourceUrl` in `explico.json`. Pin a commit, not a branch.
- **Added:** CI gates, new exit code `5`: `render --fail-under <percent>` (rendering coverage), `render --fail-on-gaps` (documentation gaps: missing description or
  framework, shared titles, and with `--requirements` uncovered requirements or unlisted framework strings) and `diff --fail-on <categories>` (e.g. `LOGIC_CHANGED,REMOVED`).
  Output is still written; the reason is on stderr. Library: `RenderedDocs.gaps`, one worded line per gap.
- **Added:** a *Documentation quality* section in `index.md` (only when there is something to report) listing controls with no description, no framework or a
  title shared with another control; `render --requirements <file>` adds a *Requirements coverage* table -- which of your listed requirements have a control,
  which have **no control**, and which framework strings your controls use that are not on the list (a typo catcher). Both are also in `explico.json`.
- **Added:** machine-readable output. `render --json` writes `explico.json` (packages, rules, conditions with the exact card phrase or their verbatim
  source, coverage, metadata, effect of a match, every worked-example verdict, reference data); `diff --json <file>` writes the change report as JSON.
  Library: `render(..., json = true)` and `DiffReport.json`. Opt-in; the Markdown is byte-identical either way. Schema in `docs/json-output.md`.
- **Added:** every card for a rule named `deny`/`violation`/`allow` gains `**Effect of a match:** ❌ denied`/`✅ allowed`, stating the naming convention it
  rests on; no line for any other name. The index names every rule below 100% rendered with a link to its card, and the legend explains why a helper
  can show 0%. `explico --help` now describes each command.
- **Added:** a card now shows a rule's METADATA `authors`, `related_resources` and any other `custom:` key whose value is a scalar or a list of
  scalars (previously dropped), as `*Authors:*`, `*Related resources:*` and `*Custom:*` lines -- on a card, and on the package page for `scope: package` METADATA. Edits to them are `DOCS_CHANGED` in `explico diff`.
- **Added:** a `## What changed` section at the top of the diff report: one line per changed control --
  `removed`/`added`/`documentation changed: <fields>`/`logic changed`, the last with indented sub-bullets naming
  exactly which conditions, messages, body count, default outcome or fallback source changed -- so a reviewer
  doesn't have to read every full card to see what moved.
- **Added:** a *Reference data* section on a card that reads a plain `data.*` document (e.g. an exemption list): shows what the
  supplied data actually holds, resolved by `opa eval` and never a loaded rule's own value. Works with `--data` alone, with no
  `--examples` needed.
- **Added:** a policy digest -- a SHA-256 over the policy's `.rego` files (line endings normalised, examples/data/tool version excluded) --
  shown under the title of `index.md` and, shortened, at the end of every package document, so a reader can tell which policy version a
  document describes.
- **Added:** `index.md` titles link to their cards, and a *Controls by framework* table (built from METADATA `frameworks`) appears when any rule
  names one.
- **Changed:** the index column `Coverage` is now `Rendered` (it never measured control coverage), and a closing *How to read this index* section
  explains it. Every rendered document gains the digest footer line.
- **Added:** `RuleBody.messageArguments` (the plain input paths a message's `sprintf` reads) and `RenderedDocs.generatedExamples`.
- **Fixed (Windows):** worked examples failed for any policy directory given as an absolute path with a drive letter (`D:\policies`):
  `opa eval --data` treats everything before the first `:` as a document prefix, so `D:\a\x` was read as prefix `D` plus path `\a\x`.
  Such paths (and POSIX directories with a colon in their name) are now passed as `file://` URLs. Relative paths were never affected.
- **Fixed:** an `opa` failure reported on stdout (as `opa eval --format json` does) produced an exception with an empty message; it now
  carries opa's output, or at least the exit code.
- **Compatibility:** the public model grew (`RuleMetadata`, `PackageMetadata`, `RuleBody`, `RenderedDocs` gained fields). Their 0.1.x JVM constructors are kept
  (`@JvmOverloads`), so existing Java and already-compiled callers still link; `ModelBinaryCompatibilityTest` pins this against the 0.1.1 signatures.
  What does change is the generated data-class `copy(...)` signature (extra parameters), which affects only code that calls `copy()` on explico's model
  and was compiled against 0.1.x -- recompile it. The model is not binary-stable for `copy()`/destructuring before 1.0.
- **Changed:** failed tests now print their full assertion message in the Gradle/CI log (no effect on users).
- **Changed:** a different `sprintf` argument path now changes a rule's logic hash for `explico diff` (it changes the produced message).

## 0.1.1

Packaging corrections to 0.1.0. No rendering behaviour changes. **Use 0.1.1 instead of 0.1.0.**

- **Fixed:** 0.1.0's library jar (`explico-0.1.0.jar` on Maven Central) was the 6.6 MB runnable "fat" jar: it bundled
  Kotlin, kotlinx.serialization, Clikt and Mordant classes while the POM also declared them as dependencies, giving
  consumers duplicate classes on their classpath. The library jar is now ~390 KB with only `io.explico` classes; the
  runnable jar is a separate `explico-<version>-all.jar`, attached to the GitHub Release only.
- **Fixed:** `kotlinx-serialization-json` is now an `api` (compile-scope) dependency. `Fixture.input` exposes its
  `JsonObject`, but 0.1.0 declared it runtime-only, so a Gradle consumer could not compile against `Fixture`.
- **Changed:** bytecode now targets **Java 17** (was 21), compiled against the JDK 17 API surface; CI runs the runnable jar
  on a real JDK 17. Building from source still uses a JDK 21 toolchain.
- **Note:** because the library is now built with Kotlin 2.4, its POM declares `kotlin-stdlib:2.4.21`; a Gradle consumer on
  Kotlin 2.3 therefore resolves that stdlib version transitively (verified to build and run with the 2.3.0 compiler).
- **Added:** `SECURITY.md`, and a documented warning that rendering *with* worked examples executes the policy via
  `opa eval` (rendering without examples only parses).
- **Added:** the Apache `LICENSE` is included in the jars (`META-INF/LICENSE`).
- **Added:** a build-time test (`LibraryJarShapeTest`) that fails if the published library jar ever bundles third-party
  classes, contains a `Main-Class`, or grows past 2 MB.
- **Security (build and test tooling only; nothing that ships to users was affected):** AssertJ 3.27.3 -> 3.27.7
  (CVE-2026-24400, XXE in `isXmlEqualTo`, which no test uses) and the Kotlin Gradle plugin 2.3.0 -> 2.4.21
  (CVE-2026-53914, unsafe deserialization in the Kotlin build-cache metadata). All shipped runtime dependencies were scanned
  against OSV and are clean. The library is now built with Kotlin 2.4; consumers need Kotlin 2.3.0 or newer (unchanged).
- **Fixed (since 0.1.0 source, no separate release):** METADATA now attaches on Windows (opa reports backslash paths);
  CI runs on Linux, macOS and Windows.

## 0.1.0

First release: renders Rego policies into true-by-construction Markdown, with worked examples, semantic diff, CLI and
library API. Superseded by 0.1.1 (see above).
