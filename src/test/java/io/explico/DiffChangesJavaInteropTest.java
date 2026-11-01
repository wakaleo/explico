/*
 * DiffReport.changes and DiffChange from Java (spec §14.80 step 4): plain getters, no Kotlin types to unwrap, and the changed controls readable without the structural model. The first test needs no
 * opa; the second runs a real diff (guarded).
 */
package io.explico;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.explico.diff.DiffCategory;
import io.explico.diff.DiffChange;
import io.explico.opa.OpaRunner;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DiffChangesJavaInteropTest {

    @Test
    @DisplayName("DiffChange has plain getters and the two-argument DiffReport still has an empty changes list")
    void gettersAndDefault() {
        DiffChange change = new DiffChange("A-1", DiffCategory.ADDED, "A-1 — T", "pkg", "deny", null);

        assertThat(change.getControlId()).isEqualTo("A-1");
        assertThat(change.getCategory()).isEqualTo(DiffCategory.ADDED);
        assertThat(change.getLabel()).isEqualTo("A-1 — T");
        assertThat(change.getPackagePath()).isEqualTo("pkg");
        assertThat(change.getRuleName()).isEqualTo("deny");
        assertThat(change.getAnchor()).isNull();
        assertThat(new DiffReport(List.of(), "# report").getChanges()).isEmpty();
    }

    @Test
    @DisplayName("a real diff's changes are readable from Java without importing the model")
    void realDiff() {
        assumeTrue(OpaRunner.INSTANCE.isAvailable(), "opa binary not on PATH");
        Path base = Path.of("src/test/resources/diff/diff-all-categories");

        DiffReport report = Explico.diff(Explico.load(base.resolve("old")), Explico.load(base.resolve("new")));

        assertThat(report.getChanges()).extracting(DiffChange::getControlId).containsExactly("REL-001", "REL-005", "REL-003", "REL-004");
        assertThat(report.getChanges()).extracting(DiffChange::getCategory)
            .containsExactly(DiffCategory.REMOVED, DiffCategory.ADDED, DiffCategory.LOGIC_CHANGED, DiffCategory.DOCS_CHANGED);
    }
}
