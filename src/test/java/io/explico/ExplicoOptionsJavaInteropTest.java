/*
 * The options-object API from Java (spec §14.80 step 2): builders for RenderOptions and DiffOptions, short constructors for the small classes, and the two entry points with and without
 * options. Pins that a Java caller never has to pass a long list of nulls. Needs opa (guarded).
 */
package io.explico;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.explico.model.PolicySet;
import io.explico.opa.OpaRunner;
import io.explico.render.ExampleSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExplicoOptionsJavaInteropTest {

    private static final Path PACK = Path.of("src/test/resources/acceptance");

    @BeforeAll
    static void requireOpa() {
        assumeTrue(OpaRunner.INSTANCE.isAvailable(), "opa binary not on PATH");
    }

    @Test
    @DisplayName("render(policies) and render(policies, builder.build()) reproduce the frozen goldens")
    void renderWithAndWithoutOptions() throws Exception {
        PolicySet set = Explico.load(PACK.resolve("policies"));
        ExampleSet examples = Explico.loadExamples(PACK.resolve("examples"));

        RenderedDocs withOptions = Explico.render(
            set, RenderOptions.builder().examples(examples).dataDir(PACK.resolve("data/release/data.json")).build());
        RenderedDocs plain = Explico.render(set);

        assertThat(withOptions.getFiles()).containsKey("release-approvals.md");
        assertThat(withOptions.getFiles().get("release-approvals.md"))
            .isEqualTo(Files.readString(PACK.resolve("expected/release-approvals.md")));
        assertThat(plain.getFiles()).containsKey("index.md");
        assertThat(plain.getFiles().get("release-approvals.md")).doesNotContain("**Worked examples**");
    }

    @Test
    @DisplayName("the small option classes need no explicit defaults, and json(true) adds explico.json")
    void smallClassesAndJson() {
        Path policies = Path.of("src/test/resources/realistic/control-id-from-filename/policies");
        PolicySet set = Explico.load(policies);

        RenderOptions options = RenderOptions.builder()
            .json(true)
            .controlId(new ControlIdOptions("ctrl_(?<id>\\d+)", "CTRL-{id}"))
            .companion(new CompanionLink("https://modules.example.com/{package}/src/{file}#L{line}"))
            .requirements(List.of("SOC 2 CC8.1"))
            .build();
        RenderedDocs docs = Explico.render(set, options);

        assertThat(docs.getFiles()).containsKey("explico.json");
        assertThat(docs.getFiles().get("fname_nineteen.md")).contains("## CTRL-19 — signed_off");
        assertThat(docs.getFiles().get("fname_nineteen.md")).contains("[module](https://modules.example.com/");
    }

    @Test
    @DisplayName("diff(old, new) is plain; diff(old, new, builder.digests(true).build()) shows the digests")
    void diffWithAndWithoutOptions() {
        Path base = Path.of("src/test/resources/realistic/metadata-with-logic");
        PolicySet old = Explico.load(base.resolve("old"));
        PolicySet updated = Explico.load(base.resolve("new"));

        DiffReport plain = Explico.diff(old, updated);
        DiffReport shown = Explico.diff(old, updated, DiffOptions.builder().digests(true).labels(new VersionLabels("v1", "v2")).build());

        assertThat(plain.getMarkdown()).doesNotContain("**Compared**");
        assertThat(shown.getMarkdown()).contains("**Compared**").contains("- Old (v1): `sha256:");
        assertThat(shown.getOldDigest()).startsWith("sha256:");
    }
}
