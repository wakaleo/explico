/**
 * Spec §14.64: which documentation properties of a CONTROL count as gaps (what `--fail-on-gaps` fails on). Pure: names in, a validated set out.
 */
package io.explico.render

internal class RequiredSet private constructor(
    /** The required property names, in canonical order ([ALL]). */
    val names: List<String>,
) {

    operator fun contains(name: String): Boolean = name in names

    companion object {
        /** Every property a control can be required to have, in the order their gaps are reported. */
        val ALL = listOf("title", "description", "frameworks", "fixture", "verdict")

        /** The properties that can only be judged against worked examples. */
        private val NEEDS_EXAMPLES = listOf("fixture", "verdict")

        /** What every earlier version required: a description and a framework. */
        val DEFAULT = RequiredSet(listOf("description", "frameworks"))

        /**
         * [names] null is [DEFAULT]; an empty list requires nothing; order and duplicates do not matter. An unknown name is refused (names are exact and
         * case-sensitive), and so are `fixture` and `verdict` when no example fixtures were supplied ([examplesSupplied] false): they would pass vacuously.
         */
        fun of(names: List<String>?, examplesSupplied: Boolean): RequiredSet {
            if (names == null) return DEFAULT
            for (name in names) {
                require(name in ALL) { "'$name' is not a documentation property; expected one or more of: ${ALL.joinToString(", ")}" }
            }
            if (!examplesSupplied) {
                for (name in NEEDS_EXAMPLES) {
                    require(name !in names) { "required '$name' needs example fixtures (pass an examples directory with at least one fixture)" }
                }
            }
            return RequiredSet(ALL.filter { it in names })
        }
    }
}
