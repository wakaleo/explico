/**
 * CHALLENGE cases for the data-aware diff (spec §14.73) that need the real `opa` binary and a small purpose-built policy each, written to a temp directory:
 * data given as a directory, Rego v0 policies, `--group-by-control`, a control-id-preserving rename, and data opa cannot read. The main contract is
 * RealisticDataAwareDiffAcceptanceIT; these pin the edges around it.
 */
package io.explico.diff

import io.explico.Explico
import io.explico.DataVersions
import io.explico.DiffOptions
import io.explico.model.RegoVersion
import io.explico.opa.OpaInvocationException
import io.explico.opa.OpaRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DataAwareDiffChallengeTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun requireOpa() {
            assumeTrue(OpaRunner.isAvailable(), "opa binary not on PATH")
        }

        private fun policy(id: String, pkg: String, rule: String = "deny", path: String = "data.limits.max") = """
            package $pkg

            import rego.v1

            # METADATA
            # scope: document
            # title: Title $id
            # description: Description $id
            # custom:
            #   control-id: $id
            #   frameworks:
            #     - F 1
            $rule contains msg if {
            	input.amount > $path
            	msg := "too much"
            }
        """.trimIndent() + "\n"

        private fun write(dir: Path, name: String, text: String): Path = dir.resolve(name).also { Files.createDirectories(it.parent); Files.writeString(it, text) }
    }

    @Test
    @DisplayName("data given as a DIRECTORY (every json file in it) is read like a single file")
    fun dataDirectory(@TempDir dir: Path) {
        write(dir, "p/a.rego", policy("C-1", "a"))
        write(dir, "old/limits.json", """{"limits": {"max": 10}}""")
        write(dir, "new/limits.json", """{"limits": {"max": 20}}""")
        val set = Explico.load(dir.resolve("p"))

        val report = Explico.diff(set, set, DiffOptions(data = DataVersions(dir.resolve("old"), dir.resolve("new"))))

        assertThat(report.entries.single().category).isEqualTo(DiffCategory.LOGIC_CHANGED)
        assertThat(report.markdown).contains("reference data changed: `data ▸ limits ▸ max`: `10` -> `20`")
    }

    @Test
    @DisplayName("a YAML data file is read like a JSON one")
    fun yamlData(@TempDir dir: Path) {
        write(dir, "p/a.rego", policy("C-1", "a"))
        write(dir, "old.yaml", "limits:\n  max: 10\n")
        write(dir, "new.yaml", "limits:\n  max: 12\n")
        val set = Explico.load(dir.resolve("p"))

        val report = Explico.diff(set, set, DiffOptions(data = DataVersions(dir.resolve("old.yaml"), dir.resolve("new.yaml"))))

        assertThat(report.markdown).contains("reference data changed: `data ▸ limits ▸ max`: `10` -> `12`")
    }

    @Test
    @DisplayName("Rego v0 policies: the data is read with the set's own syntax version")
    fun regoV0(@TempDir dir: Path) {
        write(dir, "p/a.rego", """
            package a

            # METADATA
            # scope: document
            # title: T
            # description: D
            # custom:
            #   control-id: C-1
            #   frameworks:
            #     - F 1
            deny[msg] {
            	input.amount > data.limits.max
            	msg := "too much"
            }
        """.trimIndent() + "\n")
        write(dir, "old.json", """{"limits": {"max": 10}}""")
        write(dir, "new.json", """{"limits": {"max": 11}}""")
        val set = Explico.load(dir.resolve("p"), RegoVersion.V0)

        val report = Explico.diff(set, set, DiffOptions(data = DataVersions(dir.resolve("old.json"), dir.resolve("new.json"))))

        assertThat(report.entries.single().category).isEqualTo(DiffCategory.LOGIC_CHANGED)
    }

    @Test
    @DisplayName("--group-by-control with data: two rules of one shared control id each report the document they read; a rule that reads another document is unaffected")
    fun groupedControl(@TempDir dir: Path) {
        write(dir, "p/a.rego", policy("SHARED", "a", path = "data.limits.max"))
        write(dir, "p/b.rego", policy("SHARED", "b", path = "data.limits.max"))
        write(dir, "p/c.rego", policy("OTHER", "c", path = "data.other.max"))
        write(dir, "old.json", """{"limits": {"max": 1}, "other": {"max": 5}}""")
        write(dir, "new.json", """{"limits": {"max": 2}, "other": {"max": 5}}""")
        val set = Explico.load(dir.resolve("p"))

        val report = Explico.diff(set, set, DiffOptions(groupByControl = true, data = DataVersions(dir.resolve("old.json"), dir.resolve("new.json"))))

        assertThat(report.entries.associate { "${it.newPackage!!.path}" to it.category }).containsExactlyInAnyOrderEntriesOf(
            mapOf("a" to DiffCategory.LOGIC_CHANGED, "b" to DiffCategory.LOGIC_CHANGED, "c" to DiffCategory.UNCHANGED),
        )
        assertThat(report.markdown.lines().count { it.startsWith("  - reference data changed:") }).isEqualTo(2)
    }

    @Test
    @DisplayName("a control-id-preserving rename is one entry, and a data change under it is reported once")
    fun renameKeepsOneEntry(@TempDir dir: Path) {
        write(dir, "old/a.rego", policy("C-1", "a", rule = "deny"))
        write(dir, "new/a.rego", policy("C-1", "a", rule = "deny_stage"))
        write(dir, "old.json", """{"limits": {"max": 1}}""")
        write(dir, "new.json", """{"limits": {"max": 2}}""")

        val report = Explico.diff(Explico.load(dir.resolve("old")), Explico.load(dir.resolve("new")), DiffOptions(data = DataVersions(dir.resolve("old.json"), dir.resolve("new.json"))))

        assertThat(report.entries).hasSize(1)
        assertThat(report.entries.single().category).isEqualTo(DiffCategory.LOGIC_CHANGED)
        assertThat(report.markdown.lines().count { it.startsWith("  - reference data changed:") }).isEqualTo(1)
    }

    @Test
    @DisplayName("data opa cannot read stops the diff with opa's own message (the CLI maps it to exit 2), and writes nothing partial")
    fun unreadableData(@TempDir dir: Path) {
        write(dir, "p/a.rego", policy("C-1", "a"))
        write(dir, "bad.json", """{"limits": """)
        write(dir, "good.json", """{"limits": {"max": 1}}""")
        val set = Explico.load(dir.resolve("p"))

        assertThatThrownBy { Explico.diff(set, set, DiffOptions(data = DataVersions(dir.resolve("good.json"), dir.resolve("bad.json")))) }
            .isInstanceOf(OpaInvocationException::class.java)
    }
}
