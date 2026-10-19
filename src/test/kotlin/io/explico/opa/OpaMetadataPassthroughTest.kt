/**
 * Spec §14.43: `authors`, `related_resources` and the extra `custom:` keys explico now carries. Decoding only -- no opa
 * needed; the end-to-end card is pinned by RealisticMetadataPassthroughAcceptanceIT.
 */
package io.explico.opa

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class OpaMetadataPassthroughTest {

    private fun custom(json: String): OpaCustomAnnotation = opaJson.decodeFromString(OpaCustomAnnotation.serializer(), json)
    private fun body(json: String): OpaAnnotationBody = opaJson.decodeFromString(OpaAnnotationBody.serializer(), json)

    @Test
    fun scalarsAndScalarListsAreCarriedAsDisplayTextSortedByKey() {
        val c = custom("""{"tags":["a","b"],"owner":"platform","tier":2,"audited":true}""")
        assertThat(c.customFields.entries.map { it.key to it.value })
            .containsExactly("audited" to "true", "owner" to "platform", "tags" to "a, b", "tier" to "2")
    }

    @Test
    fun controlIdAndFrameworksAreNeverRepeatedAsCustomFields() {
        assertThat(custom("""{"control-id":"X","frameworks":["a"],"owner":"p"}""").customFields).containsOnlyKeys("owner")
    }

    @Test
    @DisplayName("CHALLENGE: an object, a list holding an object, null, and an empty list are all skipped, never guessed at")
    fun structuredNullAndEmptyValuesAreSkipped() {
        val c = custom("""{"obj":{"a":1},"mixed":["a",{"b":1}],"nil":null,"empty":[],"nested":[["a"]],"ok":"yes"}""")
        assertThat(c.customFields).containsOnlyKeys("ok")
    }

    @Test
    @DisplayName("CHALLENGE: a custom that is not an object at all reads as empty, not fatal")
    fun nonObjectCustomIsEmpty() {
        val b = body("""{"custom":"oops","title":"T"}""")
        assertThat(b.custom!!.customFields).isEmpty()
        assertThat(b.custom!!.controlId).isNull()
        assertThat(b.title).isEqualTo("T")
    }

    @Test
    fun authorsAndRelatedResourcesDecodeFromOpasOwnNormalisedShape() {
        val b = body(
            """{"authors":[{"name":"Ann","email":"a@x.io"},{"name":"Bob"}],
               "related_resources":[{"ref":"https://x.io/a","description":"Runbook"},{"ref":"https://x.io/b"}]}""",
        )
        assertThat(b.authors).containsExactly(OpaAuthor("Ann", "a@x.io"), OpaAuthor("Bob", null))
        assertThat(b.relatedResources).containsExactly(OpaRelatedResource("https://x.io/a", "Runbook"), OpaRelatedResource("https://x.io/b", null))
    }

    @Test
    fun absentFieldsDefaultToEmpty() {
        val b = body("{}")
        assertThat(b.authors).isEmpty()
        assertThat(b.relatedResources).isEmpty()
        assertThat(b.custom).isNull()
    }
}
