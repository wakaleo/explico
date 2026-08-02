plugins {
    // Lets Gradle auto-download a JDK 21 toolchain (via the Foojay Disco API) if the machine
    // building this project doesn't already have one on PATH/JAVA_HOME -- removes an entire class
    // of onboarding friction a cold-start reader hit (session 7's README walkthrough test).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "explico"
