# Changelog

## 0.2.0

A new opt-in feature, and fixes for two defects found while building it. Recommended over 0.1.1, especially on Windows.

- **Added:** `--generate-examples` (library: `Explico.render(..., generateExamples = true)`). explico derives worked examples itself from
  each rule's conditions where the author's own fixtures leave a gap: inputs that satisfy a rule body and "near-misses" that break exactly one
  condition, each **verified by `opa eval`** and shown in a separate, labelled *Generated examples* section with mechanical names. Off by
  default (output is byte-identical without it). Bodies it cannot model get none.
- **Added:** `RuleBody.messageArguments` (the plain input paths a message's `sprintf` reads) and `RenderedDocs.generatedExamples`.
- **Fixed (Windows):** worked examples failed for any policy directory given as an absolute path with a drive letter (`D:\policies`):
  `opa eval --data` treats everything before the first `:` as a document prefix, so `D:\a\x` was read as prefix `D` plus path `\a\x`.
  Such paths (and POSIX directories with a colon in their name) are now passed as `file://` URLs. Relative paths were never affected.
- **Fixed:** an `opa` failure reported on stdout (as `opa eval --format json` does) produced an exception with an empty message; it now
  carries opa's output, or at least the exit code.
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
