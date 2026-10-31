package io.explico.diff

/**
 * One author fixture whose outcome differs between the old and the new version of a control (the per-fixture verdict diff, spec §14.74): [control] is the control id (else `package.rule`),
 * [old] and [new] the outcome words the card itself uses (`✅ allowed`, `❌ denied`, `matched`, ...). When only the produced message(s) changed, [old] and [new] are the same word.
 */
public data class ExampleChange(val control: String, val fixture: String, val old: String, val new: String)

/**
 * What the per-fixture verdict diff could actually compare (spec §14.74), so "no example changed" is never read as more than it says. Of the [total] controls present in
 * both versions, [compared] had examples on both sides; [functions] were skipped because a function is not matched against an input, [unattributable] because their
 * examples cannot be credited to the rule (several same-named rules whose messages cannot be told apart, or no fixture could be evaluated). [fixturesOnOneVersion] counts the
 * distinct fixtures that only one version could evaluate (the other skipped them, with a warning on stderr), which are not compared.
 */
public data class ExampleComparison(val compared: Int, val total: Int, val functions: Int, val unattributable: Int, val fixturesOnOneVersion: Int)
