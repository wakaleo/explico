/**
 * Worked-example fixtures (spec §6.7): example inputs evaluated against the policy
 * set via `opa eval` and rendered as observed verdicts on each control card.
 */
package io.explico.render

import kotlinx.serialization.json.JsonObject

/** One fixture: a named example input, as loaded from `--examples <dir>`. */
public data class Fixture(
    val name: String,
    val description: String?,
    val input: JsonObject,
)

/** All fixtures loaded from one examples directory, in filename order. */
public data class ExampleSet(val fixtures: List<Fixture>)
