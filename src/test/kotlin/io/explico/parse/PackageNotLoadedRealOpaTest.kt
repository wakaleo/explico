/**
 * Spec §14.79 against real `opa parse` output: a CALL into a package that is imported but not loaded has the reason `package-not-loaded`; a rule reference or an iteration over an
 * imported name keeps `unclassified`, because an import of REFERENCE DATA looks exactly like an import of a package (today both fall back, neither is rendered as a data path), so
 * "this is a package" would not be a fact. Needs `opa`.
 */
package io.explico.parse

import io.explico.Explico
import io.explico.model.Condition
import io.explico.opa.OpaRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PackageNotLoadedRealOpaTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
        }
    }

    private fun reasons(dir: Path, body: String): List<String> {
        Files.writeString(
            dir.resolve("p.rego"),
            "package app\n\nimport rego.v1\n\nimport data.limits\nimport data.lib.nothere\nimport data.lib.nothere.member\n\ndeny contains msg if {\n$body\n\tmsg := \"denied\"\n}\n",
        )
        return Explico.load(dir).packages.single().rules.single { it.name == "deny" }.bodies.single().conditions.filterIsInstance<Condition.Unrendered>().map { it.reason }
    }

    @Test
    @DisplayName("a call into an unloaded imported package, qualified or by member name, negated or not: package-not-loaded")
    fun calls(@TempDir dir: Path) {
        assertThat(reasons(dir, "\tnothere.fn(input.x)\n\tnot nothere.fn(input.y)\n\tmember(input.z)")).containsExactly("package-not-loaded", "package-not-loaded", "package-not-loaded")
    }

    @Test
    @DisplayName("an iteration over an imported name stays unclassified: it cannot be told from an import of reference data, so 'package' is not claimed")
    fun iterationsStayUnclassified(@TempDir dir: Path) {
        assertThat(reasons(dir, "\tsome c in limits.countries\n\tsome n in nothere.names")).containsExactly("unclassified", "unclassified")
    }

    @Test
    @DisplayName("a built-in call is never package-not-loaded, even beside imports")
    fun builtinsNever(@TempDir dir: Path) {
        assertThat(reasons(dir, "\tregex.match(\"a\", input.s)\n\tcount(input.xs) > 0")).isEmpty()
    }
}
