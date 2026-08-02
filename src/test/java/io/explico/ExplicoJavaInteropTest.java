// Proves spec §13.5's Java-interop ergonomics against the real facade, not just the presence of
// @JvmStatic/@JvmOverloads annotations: every call below is exactly what a Java caller would
// write, with no Explico.INSTANCE and no explicit nulls for render()'s optional parameters.
package io.explico;

import io.explico.model.PolicySet;
import io.explico.opa.OpaRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ExplicoJavaInteropTest {

    @BeforeAll
    static void requireOpa() {
        // OpaRunner is internal, not part of spec §13.5's Java-ergonomics scope (only the public
        // Explico facade gets @JvmStatic) -- .INSTANCE is expected and correct here.
        assumeTrue(OpaRunner.INSTANCE.isAvailable(), "opa binary not on PATH");
    }

    private final Path policiesDir = Path.of("src/test/resources/acceptance/policies");

    @Test
    void loadIsStaticNotInstanceQualified() {
        // Explico.load(...), not Explico.INSTANCE.load(...) -- would not compile without @JvmStatic.
        PolicySet policySet = Explico.load(policiesDir);
        assertThat(policySet.getPackages()).isNotEmpty();
    }

    @Test
    void renderCallableWithNoOptionalArgumentsAtAll() {
        // Explico.render(policySet, policyDir) -- two args, not four -- would not compile without
        // @JvmOverloads, since Java has no concept of Kotlin's default parameter values.
        PolicySet policySet = Explico.load(policiesDir);
        RenderedDocs rendered = Explico.render(policySet, policiesDir);

        Map<String, String> files = rendered.getFiles();
        assertThat(files).containsKey("index.md");
        assertThat(files.get("index.md")).contains("REL-001");
        assertThat(rendered.getCoverage().getTotal()).isGreaterThan(0);
    }

    @Test
    void diffIsAlsoStaticNotInstanceQualified() {
        PolicySet policySet = Explico.load(policiesDir);
        DiffReport report = Explico.diff(policySet, policySet);

        assertThat(report.getEntries()).isNotEmpty();
        assertThat(report.getMarkdown()).contains("This report shows structural changes only");
    }
}
