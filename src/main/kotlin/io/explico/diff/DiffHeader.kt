/**
 * Spec §14.76: which two versions a diff report compares -- the digest of each policy directory (the value `render` prints in `index.md`) and an optional human label
 * for each. Absent (null) when `diff` was not given the policy directories: nothing is guessed.
 */
package io.explico.diff

internal data class DiffHeader(val oldDigest: String, val newDigest: String, val oldLabel: String?, val newLabel: String?) {
    /** True when both versions have the same `.rego` files and content. */
    val samePolicies: Boolean get() = oldDigest == newDigest
}
