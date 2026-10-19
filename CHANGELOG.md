# Changelog

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
- **Added:** the Apache `LICENSE` is included in the jars (`META-INF/LICENSE`).
- **Added:** a build-time test (`LibraryJarShapeTest`) that fails if the published library jar ever bundles third-party
  classes, contains a `Main-Class`, or grows past 2 MB.
- **Fixed (since 0.1.0 source, no separate release):** METADATA now attaches on Windows (opa reports backslash paths);
  CI runs on Linux, macOS and Windows.

## 0.1.0

First release: renders Rego policies into true-by-construction Markdown, with worked examples, semantic diff, CLI and
library API. Superseded by 0.1.1 (see above).
