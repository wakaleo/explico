package io.explico;

import static org.assertj.core.api.Assertions.assertThat;

import io.explico.model.PolicySet;
import io.explico.render.ExampleSet;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 0.2.0 added a trailing `requirements` list to render(); every shorter overload must still link, and the full one must exist. */
class RequirementsApiJavaInteropTest {

    @Test
    void everyShorterRenderOverloadStillExistsAndTheFullOneTakesTheRequirements() throws Exception {
        assertThat(Explico.class.getMethod("render", PolicySet.class, Path.class)).isNotNull();
        assertThat(Explico.class.getMethod("render", PolicySet.class, Path.class, ExampleSet.class, Path.class, boolean.class)).isNotNull();
        assertThat(Explico.class.getMethod("render", PolicySet.class, Path.class, ExampleSet.class, Path.class, boolean.class, boolean.class)).isNotNull();
        assertThat(Explico.class.getMethod("render", PolicySet.class, Path.class, ExampleSet.class, Path.class, boolean.class, boolean.class, List.class)).isNotNull();
    }
}
