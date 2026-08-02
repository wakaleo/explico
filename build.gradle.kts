plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    application
    id("com.vanniktech.maven.publish") version "0.37.0"
}

// Maven coordinates (io.github.wakaleo -- auto-verified via GitHub OAuth on the Central Portal,
// no domain ownership needed) are independent of the `io.explico` Kotlin package namespace: only
// the published artifact's groupId changes here, no source package is renamed.
group = "io.github.wakaleo"
version = "0.1.0"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.github.ajalt.clikt:clikt:5.0.3")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.27.3")
}

application {
    mainClass.set("io.explico.cli.MainKt")
    // Clikt's terminal-capability detection (via its Mordant dependency) uses JNA, which trips a
    // JDK "restricted method" warning on every CLI invocation otherwise -- confirmed via a
    // cold-start README walkthrough that flagged it as confusing, undocumented noise before any
    // real output. Silences it at the source rather than just documenting it away.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// Substitutes the project version into the CLI's `explico version` output -- avoids hardcoding
// a duplicate version string in Main.kt that would drift from build.gradle.kts's own `version`.
// inputs.property() is required: expand()'s substitution value isn't otherwise tracked as a task
// input, so a version bump alone wouldn't invalidate an UP-TO-DATE processResources and the CLI
// would keep reporting the stale version (confirmed empirically -- this isn't a hypothetical).
tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("explico-version.properties") {
        expand("version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
    filter {
        excludeTestsMatching("*IT")
        isFailOnNoMatchingTests = false
    }
}

// Tier-1 acceptance tests and Tier-2 golden tests (spec §9): require the `opa`
// binary, guarded by Assumptions.assumeTrue(OpaRunner.isAvailable()). Kept out
// of `check` so the default build stays green while they are red during TDD;
// run explicitly. `-Dexplico.updateGolden=true` regenerates the Tier-2 goldens
// instead of comparing against them -- a deliberate, reviewed act, never a
// side effect of getting a build green.
val acceptanceTest by tasks.registering(Test::class) {
    description = "Runs Tier-1 acceptance tests and Tier-2 golden tests (*IT classes)."
    group = "verification"
    useJUnitPlatform()
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
