// Deliberately NOT a subproject of the root build (no `include(...)` in the root
// settings.gradle.kts): the whole point is to resolve `io.github.wakaleo:explico` the way a real
// external consumer would -- from a Maven repository (mavenLocal() for CI/local testing, ready
// for mavenCentral() once a real release exists) -- never via Gradle project-dependency
// substitution, which would silently mask a broken or missing publication.
plugins {
    kotlin("jvm") version "2.3.0"
    application
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("io.github.wakaleo:explico:0.1.0")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("MainKt")
}
