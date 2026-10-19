/**
 * `custom:` METADATA is free-form, so a field explico READS (`control-id`, `frameworks`) can arrive
 * in a shape explico does not model. Found by benchmarking real-world Rego: Trivy's checks declare
 * `custom.frameworks` as an OBJECT (framework -> control ids), and strict list decoding aborted the
 * whole load for 47 files. A wrongly-shaped read field is treated exactly like an unrecognised key:
 * ignored, never fatal. No opa binary needed.
 */
package io.explico.opa

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class OpaCustomAnnotationTest {

    private fun custom(json: String): OpaCustomAnnotation = opaJson.decodeFromString(OpaCustomAnnotation.serializer(), json)

    @Test
    fun theDocumentedShapeStillDecodes() {
        val c = custom("""{"control-id":"REL-001","frameworks":["SOC 2 CC8.1","ISO 27001 A.8.32"]}""")
        assertThat(c.controlId).isEqualTo("REL-001")
        assertThat(c.frameworks).containsExactly("SOC 2 CC8.1", "ISO 27001 A.8.32")
    }

    @Test
    @DisplayName("an OBJECT-shaped frameworks (Trivy's convention) is ignored, not fatal; control-id still reads")
    fun objectShapedFrameworksIsIgnored() {
        val c = custom("""{"control-id":"AWS-0175","frameworks":{"cis-aws-1.4":["1.20"]}}""")
        assertThat(c.frameworks).isEmpty()
        assertThat(c.controlId).isEqualTo("AWS-0175")
    }

    @Test
    @DisplayName("a string-shaped frameworks is ignored")
    fun stringShapedFrameworksIsIgnored() {
        assertThat(custom("""{"frameworks":"soc2"}""").frameworks).isEmpty()
    }

    @Test
    @DisplayName("CHALLENGE edge case: a mixed list keeps only the string entries it actually declares")
    fun mixedListKeepsOnlyStrings() {
        assertThat(custom("""{"frameworks":["soc2",1,null,{"a":1},"iso"]}""").frameworks).containsExactly("soc2", "iso")
    }

    @Test
    @DisplayName("CHALLENGE edge case: a non-string control-id is ignored (null), not fatal")
    fun nonStringControlIdIsIgnored() {
        assertThat(custom("""{"control-id":42}""").controlId).isNull()
        assertThat(custom("""{"control-id":["a"]}""").controlId).isNull()
        assertThat(custom("""{"control-id":null}""").controlId).isNull()
    }

    @Test
    fun absentFieldsAreNullAndEmpty() {
        val c = custom("{}")
        assertThat(c.controlId).isNull()
        assertThat(c.frameworks).isEmpty()
    }
}
