package io.explico;

import static org.assertj.core.api.Assertions.assertThat;

import io.explico.diff.DiffEntry;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 0.2.0 added a `json` parameter to render() and a `json` property to DiffReport. A Java caller (or anything compiled against 0.1.x)
 * must still link to the old shapes: render without the new argument, and the two-argument DiffReport constructor.
 */
class JsonApiJavaInteropTest {

    @Test
    void theOldTwoArgumentDiffReportConstructorStillExistsAndDefaultsJsonToEmpty() {
        DiffReport report = new DiffReport(List.<DiffEntry>of(), "# Policy diff report");
        assertThat(report.getMarkdown()).isEqualTo("# Policy diff report");
        assertThat(report.getJson()).isEmpty();
    }

    @Test
    void renderStillHasTheOlderOverloadsWithoutTheJsonArgument() throws Exception {
        assertThat(Explico.class.getMethod("render", io.explico.model.PolicySet.class, Path.class)).isNotNull();
        assertThat(Explico.class.getMethod("render", io.explico.model.PolicySet.class, Path.class, io.explico.render.ExampleSet.class, Path.class)).isNotNull();
        assertThat(Explico.class.getMethod("render", io.explico.model.PolicySet.class, Path.class, io.explico.render.ExampleSet.class, Path.class, boolean.class)).isNotNull();
        assertThat(Explico.class.getMethod("render", io.explico.model.PolicySet.class, Path.class, io.explico.render.ExampleSet.class, Path.class, boolean.class, boolean.class)).isNotNull();
    }
}
