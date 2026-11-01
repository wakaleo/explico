/**
 * Spec §14.66: ONE sentence per fallback reason saying what explico needs for the construct to be rendered. It explains, it never prescribes a rewrite (a rewrite can
 * change what a policy means). The reason is the machine-readable string a [io.explico.model.Condition.Unrendered] carries.
 */
package io.explico.render

internal object UnrenderedReasons {

    private const val GENERIC = "explico does not render this construct"

    private val sentences = mapOf(
        "every" to "an every block is shown only when its domain is a plain path and every condition in its body can be rendered",
        "else-chain" to "an else chain is shown only when every branch can be rendered, and it is not one of several definitions of the same rule",
        "with-override" to "with replaces a value for one call, which explico does not model",
        "function-call" to "a call or unification explico cannot render: a built-in it does not model, a function it cannot find or whose arguments it cannot render, " +
            "or a pattern on the left of =",
        "package-not-loaded" to "needs a package that is imported but not among the rendered policies; render it together with these policies to resolve it",
        "helper-assignment" to "a local assignment whose value explico cannot substitute into the conditions that use it",
        "partial-rule-reference" to "a bare reference to a set or object rule is always defined, even when empty, so it is not shown as a yes/no test",
        "comprehension" to "a comprehension is shown only when its head and every condition inside it can be rendered",
        "mapping-error" to "explico could not map this expression (see the warning on stderr)",
        "unclassified" to GENERIC,
    )

    /** The reasons with a sentence of their own. */
    val known: Set<String> get() = sentences.keys

    /** The sentence for [reason]; any reason not in the table (a newer one) gets the generic sentence rather than none. */
    fun why(reason: String): String = sentences[reason] ?: GENERIC
}
