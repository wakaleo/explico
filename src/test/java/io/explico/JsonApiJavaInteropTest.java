package io.explico;

import static org.assertj.core.api.Assertions.assertThat;

import io.explico.diff.DiffEntry;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 0.2.0 added a `json` property to DiffReport. A Java caller (or anything compiled against 0.1.x) must still link to the two-argument DiffReport constructor.
 * (The render() overload chain this once also pinned is gone: spec 14.80 step 2 replaced it with RenderOptions, which has its own Java test.)
 */
class JsonApiJavaInteropTest {

    @Test
    void theOldTwoArgumentDiffReportConstructorStillExistsAndDefaultsJsonToEmpty() {
        DiffReport report = new DiffReport(List.<DiffEntry>of(), "# Policy diff report");
        assertThat(report.getMarkdown()).isEqualTo("# Policy diff report");
        assertThat(report.getJson()).isEmpty();
    }
}
