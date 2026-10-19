/**
 * Guards the shape of the library jar that is PUBLISHED to Maven Central. 0.1.0 shipped the 6.6 MB runnable
 * "shadow" jar under the library's own file name (an empty shadow classifier), bundling Kotlin, kotlinx.serialization
 * and Clikt while the POM also declared them -- duplicate classes on every consumer's classpath. Nothing noticed,
 * because every functional test still passed. This test fails the build instead.
 */
package io.explico

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

class LibraryJarShapeTest {

    private val version = Files.readAllLines(Path.of("build.gradle.kts")).first { it.startsWith("version = ") }
        .substringAfter('"').substringBefore('"')
    private val jar = Path.of("build/libs/explico-$version.jar")

    private fun entries(): List<String> = ZipFile(jar.toFile()).use { zip -> zip.entries().asSequence().map { it.name }.toList() }

    @Test
    fun theLibraryJarContainsOnlyExplicoClassesNeverBundledDependencies() {
        val classes = entries().filter { it.endsWith(".class") }
        assertThat(classes).isNotEmpty().allSatisfy { assertThat(it).startsWith("io/explico/") }
    }

    @Test
    fun theLibraryJarIsNotTheRunnableFatJar() {
        assertThat(Files.size(jar)).describedAs("library jar size in bytes").isLessThan(2_000_000)
        ZipFile(jar.toFile()).use { zip ->
            val manifest = zip.getInputStream(zip.getEntry("META-INF/MANIFEST.MF")).bufferedReader().readText()
            assertThat(manifest).doesNotContain("Main-Class")
        }
    }

    @Test
    fun theApacheLicenceTravelsInsideTheJar() {
        assertThat(entries()).contains("META-INF/LICENSE")
    }

    @Test
    fun theRunnableJarIsAClearlySeparateFile() {
        val runnable = Path.of("build/libs/explico-$version-all.jar")
        assertThat(runnable).exists()
        assertThat(Files.size(runnable)).isGreaterThan(Files.size(jar))
    }
}
