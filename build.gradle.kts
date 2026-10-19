plugins {
    kotlin("jvm") version "2.4.21"
    // `api` configuration: Fixture.input (public) is a kotlinx.serialization JsonObject, so consumers need that
    // library at COMPILE time, not just runtime (0.1.0 published it runtime-only).
    `java-library`
    kotlin("plugin.serialization") version "2.4.21"
    application
    id("com.vanniktech.maven.publish") version "0.37.0"
    // Build-only tooling (spec §2/§13.1), not a library runtime dependency. The actively
    // maintained fork -- com.github.johnrengelman.shadow is unmaintained -- verified against the
    // plugin's own current docs (gradleup.com/shadow), not assumed from memory.
    id("com.gradleup.shadow") version "9.6.0"
}

// Maven coordinates (io.github.wakaleo -- auto-verified via GitHub OAuth on the Central Portal,
// no domain ownership needed) are independent of the `io.explico` Kotlin package namespace: only
// the published artifact's groupId changes here, no source package is renamed.
group = "io.github.wakaleo"
version = "0.1.1"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Compile against the JDK 17 API surface, so a call to a newer JDK API fails here, not on a consumer's machine.
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}
tasks.withType<JavaCompile> { options.release.set(17) }

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.github.ajalt.clikt:clikt:5.0.3")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.27.7")
}

// docsSnippets (spec §13.8): every code snippet shown in README.md/docs/*.md lives here verbatim,
// as a real compiled Kotlin/Java file -- not just an inline fenced code block nobody checks. A
// snippet that doesn't compile fails the build. Conventional source dirs
// (src/docsSnippets/{kotlin,java,resources}), no explicit srcDir wiring needed.
sourceSets {
    create("docsSnippets") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

val docsSnippetsImplementation = configurations.getByName("docsSnippetsImplementation") {
    extendsFrom(configurations.implementation.get())
}

dependencies {
    docsSnippetsImplementation(sourceSets.main.get().output)
}

// Compiling docsSnippets is enough to prove every shown snippet is valid, current API -- wired
// into `check` (not a separate opt-in task) so a broken snippet is caught on every build, the
// same "verified, not assumed" standard every other empirical claim in this project is held to.
tasks.check {
    dependsOn("compileDocsSnippetsKotlin", "compileDocsSnippetsJava")
}

application {
    mainClass.set("io.explico.cli.MainKt")
    // Clikt's terminal-capability detection (via its Mordant dependency) uses JNA, which trips a
    // JDK "restricted method" warning on every CLI invocation otherwise -- confirmed via a
    // cold-start README walkthrough that flagged it as confusing, undocumented noise before any
    // real output. Silences it at the source rather than just documenting it away.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// Single runnable jar (spec §13.1): shadow auto-configures Main-Class from application.mainClass
// above, no extra manifest wiring needed. It KEEPS the default "all" classifier
// (explico-<version>-all.jar): an empty classifier writes the very same file name as the library jar,
// so the PUBLISHED Maven artifact silently became the fat jar (found after tagging 0.1.0) --
// bundling Kotlin, kotlinx.serialization and Clikt while the POM also declares them.
// Enable-Native-Access: ALL-UNNAMED silences the same JNA warning applicationDefaultJvmArgs
// silences for the installed-distribution path (above) -- but a plain `java -jar` launch of this
// jar doesn't go through that generated start script, so it needs the equivalent manifest
// attribute instead (the only JDK-supported way to do this from inside the jar itself).
// The runnable fat jar is a GitHub Release asset only. The shadow plugin would otherwise publish it to Maven
// Central as an extra "-all" artifact (and a shadowRuntimeElements variant in the Gradle module metadata).
(components["java"] as org.gradle.api.component.AdhocComponentWithVariants)
    .withVariantsFromConfiguration(configurations["shadowRuntimeElements"]) { skip() }

// The licence travels inside both jars (Central does not require it, consumers' compliance tooling expects it).
tasks.jar { from("LICENSE") { into("META-INF") } }

tasks.shadowJar {
    from("LICENSE") { into("META-INF") }
    // Each Kotlin dependency jar carries its own same-named META-INF/*.kotlin_module file; shadow's
    // KotlinModuleMetadataTransformer needs to see all of them (not just the first, which EXCLUDE
    // would leave it) to merge correctly -- confirmed via the exact build warning this produced
    // and the plugin's own guidance for it, not applied speculatively.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    // Mordant (Clikt's terminal backend) ships 3 separate META-INF/services/...TerminalInterfaceProvider
    // entries, one per platform-detection implementation -- merging them (not picking one arbitrarily)
    // is what ServiceLoader-based platform fallback actually needs. Also confirmed via the real
    // build warning this fixes, not applied preemptively.
    mergeServiceFiles()
    manifest {
        attributes("Enable-Native-Access" to "ALL-UNNAMED")
    }
}

// Embeds the acceptance pack's policies/examples/data as jar resources for `explico demo` (spec
// §13.2) -- copied from the same src/test/resources/acceptance/ the acceptance pack itself uses,
// never a second hand-copied set that can drift (the samples/ precedent, spec §9/§10). A generated
// manifest lists every embedded file by relative path: classpath *directory* listing (getResource on
// a directory name) is unreliable inside a jar depending on whether the zip has explicit directory
// entries, but getResourceAsStream on a known file path always works, jar or exploded classes alike.
val demoResourceFiles = fileTree("src/test/resources/acceptance") {
    include("policies/**", "examples/**", "data/**")
}
val generateDemoResources = tasks.register<Copy>("generateDemoResources") {
    from(demoResourceFiles)
    into(layout.buildDirectory.dir("generated/demoResources"))
    doLast {
        val sourceRoot = file("src/test/resources/acceptance")
        val manifestLines = demoResourceFiles.files
            .map { it.relativeTo(sourceRoot).path.replace(File.separatorChar, '/') }
            .sorted()
        layout.buildDirectory.file("generated/demoResources/demo-manifest.txt").get().asFile
            .writeText(manifestLines.joinToString("\n"))
    }
}
sourceSets {
    main {
        resources {
            srcDir(generateDemoResources.map { layout.buildDirectory.dir("generated/demoResources").get() })
        }
    }
}

// Substitutes the project version into the CLI's `explico version` output -- avoids hardcoding
// a duplicate version string in Main.kt that would drift from build.gradle.kts's own `version`.
// inputs.property() is required: expand()'s substitution value isn't otherwise tracked as a task
// input, so a version bump alone wouldn't invalidate an UP-TO-DATE processResources and the CLI
// would keep reporting the stale version (confirmed empirically -- this isn't a hypothetical).
// The version is captured into a local val at configuration time, not read via `project.version`
// inside the execution-time filesMatching {} closure -- the latter is a deprecated
// configuration-cache-incompatible pattern (confirmed via a real build warning naming this exact
// line), not a style preference.
val explicoVersion = project.version.toString()
tasks.processResources {
    inputs.property("version", explicoVersion)
    filesMatching("explico-version.properties") {
        expand("version" to explicoVersion)
    }
}

// Dogfooding (spec §13.7): renders samples/ into docs/sample-output/ through the exact same CLI
// path any consumer would use -- no injected banners, no divergence from Explico.render's own
// output. A README.md marking the directory as generated is added afterward (doLast), not baked
// into the render itself, since `render`'s own --out semantics fully replaces the directory first
// -- writing the marker before the render would just have it deleted again.
val generateSampleDocs = tasks.register<JavaExec>("generateSampleDocs") {
    group = "documentation"
    description = "Renders samples/ into docs/sample-output/ (dogfooding, spec §13.7)."
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.explico.cli.MainKt")
    args = listOf(
        "render", "samples/policies",
        "--out", "docs/sample-output",
        "--examples", "samples/examples",
        "--data", "samples/data/release/data.json",
    )
    doLast {
        file("docs/sample-output/README.md").writeText(
            """
            # Generated sample output

            **Do not edit.** Regenerated by `./gradlew generateSampleDocs`, rendering
            `samples/` through the exact same `Explico.render` path any other consumer
            uses -- this is real output, not hand-edited or annotated with anything
            this task didn't put here itself.

            CI fails the build if this directory's committed content doesn't match a
            fresh render (see `ci.yml` and `docs/user-guide.md`'s drift-check recipe).
            """.trimIndent() + "\n",
        )
    }
}

// Showcase (docs/showcase/): side-by-side Rego source and rendered output for authored examples under
// samples/showcase/. Each example is rendered through the real CLI (one JavaExec per example, into
// build/showcase/), then generateShowcase stitches source + output into one page per example. Output is
// committed and drift-checked in CI exactly like docs/sample-output.
val showcaseExamples = mapOf(
    "kubernetes-admission" to ("Kubernetes admission control" to
        "Three Gatekeeper-style `deny` rules over a Pod admission request: iteration with `some ... in`, a negated builtin, a missing-field check, and `sprintf` messages that become the card's *Produces* line."),
    "storage-encryption" to ("Storage bucket controls" to
        "Infrastructure-plan rules with framework tags in METADATA, a membership test against a set literal, and a numeric comparison."),
    "api-authorization" to ("API authorization" to
        "Three small rules (role membership, an ownership comparison, a lookup in external `data`) and an `allow` decision that combines them; references between rules become links."),
    "helpers-and-functions" to ("Helpers, constants and functions" to
        "A constant rule, a user-defined function with a parameter, and rules that call both. References become links to the target's card."),
    "comprehensions" to ("Comprehensions" to
        "Set, list and variable-bound comprehensions rendered as readable phrases: counting matching items, an emptiness check, and a set substituted into a membership test."),
    "fallbacks" to ("When explico will not paraphrase" to
        "A comprehension with one condition explico cannot render (all-or-nothing, so the whole thing stays source) and an `every` block. explico shows the verbatim Rego in a marked block and reports it in the coverage footer, rather than describing logic it did not check."),
)

val showcaseRenderTasks = showcaseExamples.keys.sorted().map { name ->
    tasks.register<JavaExec>("renderShowcase_" + name.replace('-', '_')) {
        dependsOn(tasks.classes)
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("io.explico.cli.MainKt")
        val dataFile = file("samples/showcase/$name/data/data.json")
        args = listOf("render", "samples/showcase/$name/policies",
            "--out", "build/showcase/$name",
            "--examples", "samples/showcase/$name/examples") +
            (if (dataFile.exists()) listOf("--data", dataFile.path) else emptyList())
    }
}

val generateShowcase = tasks.register("generateShowcase") {
    group = "documentation"
    description = "Builds docs/showcase/ from samples/showcase/ (Rego source beside real rendered output)."
    dependsOn(showcaseRenderTasks)
    doLast {
        val out = file("docs/showcase")
        out.deleteRecursively()
        out.mkdirs()
        val names = showcaseExamples.keys.sorted()
        for (name in names) {
            val (title, blurb) = showcaseExamples.getValue(name)
            val sb = StringBuilder()
            sb.append("# $title\n\n$blurb\n\n[All examples](README.md)\n\n")
            sb.append("## Rego policy\n\n")
            file("samples/showcase/$name/policies").walkTopDown().filter { it.isFile && it.extension == "rego" }
                .sortedBy { it.name }.forEach { f ->
                    sb.append("`${f.name}`\n\n```rego\n${f.readText().trimEnd()}\n```\n\n")
                }
            sb.append("## Rendered output\n\n")
            sb.append("Generated by explico, shown unedited (inside a quote block so its headings stay separate from this page's).\n\n")
            file("build/showcase/$name").listFiles().orEmpty().filter { it.name.endsWith(".md") && it.name != "index.md" }
                .sortedBy { it.name }.forEach { f ->
                    f.readText().trimEnd().lines().forEach { sb.append(if (it.isEmpty()) ">\n" else "> $it\n") }
                    sb.append("\n")
                }
            file("docs/showcase/$name.md").writeText(sb.toString().trimEnd() + "\n")
        }
        val idx = StringBuilder("# Showcase\n\n")
        idx.append("Real Rego policies beside the documents explico renders from them. **Do not edit**: regenerated by `./gradlew generateShowcase` from `samples/showcase/`, and CI fails if it drifts.\n\n")
        idx.append("| Example | What it shows |\n|---|---|\n")
        for (name in names) {
            val (title, blurb) = showcaseExamples.getValue(name)
            idx.append("| [$title]($name.md) | $blurb |\n")
        }
        idx.append("\nThe five-package release-governance policy set, with worked examples, is in [`../sample-output/`](../sample-output/index.md) (source: [`samples/policies`](../../samples/policies)).\n")
        file("docs/showcase/README.md").writeText(idx.toString())
    }
}

tasks.test {
    useJUnitPlatform()
    // Show WHY a test failed (the full assertion message) in the console/CI log, not just the exception class.
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    filter {
        excludeTestsMatching("*IT")
        isFailOnNoMatchingTests = false
    }
    // ShadowJarProcessTest (spec §13.1) runs the real built jar as a subprocess, so it must
    // already exist. Only real cost is a fast (~1-3s) shadowJar build before every `test` run.
    dependsOn(tasks.shadowJar)
    // LibraryJarShapeTest inspects the thin library jar that is actually published.
    dependsOn(tasks.jar)
}

// Tier-1 acceptance tests and Tier-2 golden tests (spec §9): require the `opa`
// binary, guarded by Assumptions.assumeTrue(OpaRunner.isAvailable()). Kept out
// of `check` so the default build stays green while they are red during TDD;
// run explicitly. `-Dexplico.updateGolden=true` regenerates the Tier-2 goldens
// instead of comparing against them -- a deliberate, reviewed act, never a
// side effect of getting a build green.
val acceptanceTest = tasks.register<Test>("acceptanceTest") {
    description = "Runs Tier-1 acceptance tests and Tier-2 golden tests (*IT classes)."
    group = "verification"
    useJUnitPlatform()
    // Show WHY a test failed (the full assertion message) in the console/CI log, not just the exception class.
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("*IT")
    }
    systemProperty("explico.updateGolden", System.getProperty("explico.updateGolden", "false"))
}

// Maven Central publishing (spec §10, session 7), via com.vanniktech.maven.publish -- verified
// against its current docs (https://vanniktech.github.io/gradle-maven-publish-plugin/central/),
// not from memory. `automaticRelease = false` is deliberate: publishing to Central is
// irreversible (a version can never be deleted or overwritten), so a real release gets one final
// manual approval on the Central Portal after CI validates and uploads it, rather than a fully
// unattended publish on every `v*` tag push. Flip to `true` once the pipeline has a track record.
mavenPublishing {
    publishToMavenCentral(automaticRelease = false)

    // Only sign when credentials are actually present (mapped from ORG_GRADLE_PROJECT_* env vars
    // by Gradle itself, spec §10) -- keeps `publishToMavenLocal` usable for local/CI smoke testing
    // without a real GPG key. `publishToMavenCentral` itself still fails loudly without them: an
    // unsigned publication is rejected by the Central Portal, and release.yml (spec §10) also
    // verifies all four secrets are present before ever invoking Gradle, so this is never a silent
    // "publish unsigned" path in the one place (a tagged release) where that would matter.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    coordinates(group.toString(), "explico", version.toString())

    pom {
        name.set("explico")
        description.set("Renders OPA Rego policies into true-by-construction Markdown control-card documentation, and diffs policy versions.")
        inceptionYear.set("2026")
        url.set("https://github.com/wakaleo/explico")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("wakaleo")
                name.set("John Ferguson Smart")
                url.set("https://github.com/wakaleo/")
            }
        }
        scm {
            url.set("https://github.com/wakaleo/explico/")
            connection.set("scm:git:git://github.com/wakaleo/explico.git")
            developerConnection.set("scm:git:ssh://git@github.com/wakaleo/explico.git")
        }
    }
}
