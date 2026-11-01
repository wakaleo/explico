package io.explico

/**
 * Marks the structural model -- the parsed shape of a policy (rules, bodies, conditions, operands, path segments, METADATA, and a diff's per-rule entries). It is usable, but OUTSIDE the
 * 1.x compatibility promise: a new Rego construct adds a variant, and nearly every feature has. Prefer `explico.json` and `DiffReport.changes`, which are the stable ways to read a result.
 * Kotlin callers get a warning (never an error) and can `@OptIn(ExplicoStructuralModel::class)`; Java callers are unaffected.
 *
 * Applied to the model types in `io.explico.model` other than `RegoVersion`, `PolicySet` and `PolicyPackage`, to `io.explico.diff.DiffEntry`, and to the properties through which stable classes
 * expose them (`PolicyPackage.rules`, `.metadata`, `.unpublishedComments` and `DiffReport.entries`).
 */
@RequiresOptIn(level = RequiresOptIn.Level.WARNING, message = "The structural model is outside explico's 1.x compatibility promise: a new Rego construct adds a variant. Prefer explico.json or DiffReport.changes.")
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION, AnnotationTarget.CONSTRUCTOR)
public annotation class ExplicoStructuralModel
