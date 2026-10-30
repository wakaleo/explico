package io.explico;

import static org.assertj.core.api.Assertions.assertThat;

import io.explico.model.PolicySet;
import io.explico.render.ExampleSet;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Spec §14.55 added two trailing parameters (`controlIdFrom`, `controlIdTemplate`) to render(). The overload that ended at `sourceUrl` (the 0.2.0 full
 * signature, which no earlier interop test pinned) and the one ending at `controlIdFrom` must still link for a Java caller, next to the new full one.
 */
class ControlIdApiJavaInteropTest {

    private static final Class<?>[] THROUGH_REQUIREMENTS =
            {PolicySet.class, Path.class, ExampleSet.class, Path.class, boolean.class, boolean.class, List.class};

    private static Class<?>[] with(Class<?>... extra) {
        Class<?>[] all = java.util.Arrays.copyOf(THROUGH_REQUIREMENTS, THROUGH_REQUIREMENTS.length + extra.length);
        System.arraycopy(extra, 0, all, THROUGH_REQUIREMENTS.length, extra.length);
        return all;
    }

    @Test
    void theSignatureEndingAtSourceUrlStillExists() throws Exception {
        assertThat(Explico.class.getMethod("render", with(String.class))).isNotNull();
    }

    @Test
    void theSignatureEndingAtControlIdFromExistsAndTheFullOneTakesTheTemplate() throws Exception {
        assertThat(Explico.class.getMethod("render", with(String.class, String.class))).isNotNull();
        assertThat(Explico.class.getMethod("render", with(String.class, String.class, String.class))).isNotNull();
    }

    @Test
    void groupByControlIsTheNewTrailingParameterAndTheSignatureBeforeItStillExists() throws Exception {
        // Spec §14.56: `groupByControl` was appended last, so the full signature of the previous release (ending at controlIdTemplate) must still link.
        assertThat(Explico.class.getMethod("render", with(String.class, String.class, String.class))).isNotNull();
        assertThat(Explico.class.getMethod("render", with(String.class, String.class, String.class, boolean.class))).isNotNull();
    }

    @Test
    void companionParametersAreAppendedLast() throws Exception {
        // Spec §14.58: companionUrl and companionLabel come after groupByControl, so the previous full signature (ending at it) must still link.
        assertThat(Explico.class.getMethod("render", with(String.class, String.class, String.class, boolean.class))).isNotNull();
        assertThat(Explico.class.getMethod("render", with(String.class, String.class, String.class, boolean.class, String.class))).isNotNull();
        assertThat(Explico.class.getMethod("render", with(String.class, String.class, String.class, boolean.class, String.class, String.class))).isNotNull();
    }
}
