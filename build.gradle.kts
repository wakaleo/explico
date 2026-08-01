plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    application
    `maven-publish`
}

group = "io.explico"
version = "0.1.0-POC"

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
}

tasks.test {
    useJUnitPlatform()
    filter {
        excludeTestsMatching("*IT")
        isFailOnNoMatchingTests = false
    }
}

// Tier-1 acceptance tests (spec §9): require the `opa` binary, guarded by
// Assumptions.assumeTrue(OpaRunner.isAvailable()). Kept out of `check` so the
// default build stays green while they are red during TDD; run explicitly.
val acceptanceTest by tasks.registering(Test::class) {
    description = "Runs Tier-1 acceptance tests (*IT classes)."
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("*IT")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
