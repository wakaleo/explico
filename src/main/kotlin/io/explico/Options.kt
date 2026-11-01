package io.explico

import io.explico.render.ExampleSet
import java.nio.file.Path

/**
 * What `Explico.render` is asked to do besides rendering the policies (spec §14.80 step 2). Every field defaults to "not asked", so `RenderOptions()` is the plain render. Related
 * fields are grouped ([controlId], [companion]) so an invalid combination -- a template with no pattern, a link label with no link -- cannot be written. Kotlin callers use named
 * arguments; Java callers use [builder].
 */
public data class RenderOptions @JvmOverloads constructor(
    /** Worked-example fixtures (`Explico.loadExamples`); cards gain a **Worked examples** section, and the index an example-coverage column. */
    val examples: ExampleSet? = null,
    /** A data file or directory, as `opa` reads it, used to evaluate worked examples and to show the reference data a card reads. */
    val dataDir: Path? = null,
    /** Also derive worked examples from each rule's conditions where [examples] leaves a gap (each verified by `opa eval`; this runs the policy). */
    val generateExamples: Boolean = false,
    /** Also produce `explico.json`, the machine-readable result. */
    val json: Boolean = false,
    /** Requirements to evidence, in order; adds a requirements-coverage table to `index.md`. */
    val requirements: List<String> = emptyList(),
    /** A URL template linking every card to its Rego line, with `{file}` (required) and `{line}`. */
    val sourceUrl: String? = null,
    /** Derive a control id from each rule's file name when its METADATA has none. */
    val controlId: ControlIdOptions? = null,
    /** Give every control id shared by two or more rules ONE page, `controls/<id>.md`. */
    val groupByControl: Boolean = false,
    /** A second link on each card and package page, to the module that owns the policy. */
    val companion: CompanionLink? = null,
    /** The documentation properties a control must have, for [RenderedDocs.gaps] (`title`, `description`, `frameworks`, `fixture`, `verdict`); null = the default (`description`, `frameworks`). */
    val required: List<String>? = null,
) {
    public companion object {
        /** A builder for Java callers: `RenderOptions.builder().json(true).build()`. */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }

    /** Fluent builder for [RenderOptions]; each method sets the field of the same name. */
    public class Builder internal constructor() {
        private var options = RenderOptions()

        /** Sets [RenderOptions.examples]. */
        public fun examples(value: ExampleSet?): Builder = apply { options = options.copy(examples = value) }
        /** Sets [RenderOptions.dataDir]. */
        public fun dataDir(value: Path?): Builder = apply { options = options.copy(dataDir = value) }
        /** Sets [RenderOptions.generateExamples]. */
        public fun generateExamples(value: Boolean): Builder = apply { options = options.copy(generateExamples = value) }
        /** Sets [RenderOptions.json]. */
        public fun json(value: Boolean): Builder = apply { options = options.copy(json = value) }
        /** Sets [RenderOptions.requirements]. */
        public fun requirements(value: List<String>): Builder = apply { options = options.copy(requirements = value) }
        /** Sets [RenderOptions.sourceUrl]. */
        public fun sourceUrl(value: String?): Builder = apply { options = options.copy(sourceUrl = value) }
        /** Sets [RenderOptions.controlId]. */
        public fun controlId(value: ControlIdOptions?): Builder = apply { options = options.copy(controlId = value) }
        /** Sets [RenderOptions.groupByControl]. */
        public fun groupByControl(value: Boolean): Builder = apply { options = options.copy(groupByControl = value) }
        /** Sets [RenderOptions.companion]. */
        public fun companion(value: CompanionLink?): Builder = apply { options = options.copy(companion = value) }
        /** Sets [RenderOptions.required]. */
        public fun required(value: List<String>?): Builder = apply { options = options.copy(required = value) }
        /** The [RenderOptions] these calls described. */
        public fun build(): RenderOptions = options
    }
}

/** Derive a control id from a rule's source FILE NAME: [from] is a regex with the named group `id`, [template] says how the captured text is written (`{id}` stands for it). */
public data class ControlIdOptions @JvmOverloads constructor(val from: String, val template: String = "{id}")

/** A second link on each card: [url] is a template with `{file}`, `{package}` and `{line}`, [label] the plain text of the link. */
public data class CompanionLink @JvmOverloads constructor(val url: String, val label: String = "module")

/** What `Explico.diff` is asked to do besides diffing the rules (spec §14.80 step 2). `DiffOptions()` is the plain diff, byte for byte. */
public data class DiffOptions @JvmOverloads constructor(
    /** Allow a control id shared by several rules instead of failing; its rules are told apart by package and rule. */
    val groupByControl: Boolean = false,
    /** The reference data of the old and the new version; a control whose referenced data differs is reported as `LOGIC_CHANGED`. */
    val data: DataVersions? = null,
    /** Fixtures evaluated against both versions; the report lists, per control, those whose outcome differs. */
    val examples: ExampleSet? = null,
    /** Names for the two versions, shown in the report's **Compared** block (which they also switch on). */
    val labels: VersionLabels? = null,
    /** Show the **Compared** block (the digests of the two policy directories) even without [labels]. */
    val digests: Boolean = false,
) {
    public companion object {
        /** A builder for Java callers: `DiffOptions.builder().digests(true).build()`. */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }

    /** Fluent builder for [DiffOptions]; each method sets the field of the same name. */
    public class Builder internal constructor() {
        private var options = DiffOptions()

        /** Sets [DiffOptions.groupByControl]. */
        public fun groupByControl(value: Boolean): Builder = apply { options = options.copy(groupByControl = value) }
        /** Sets [DiffOptions.data]. */
        public fun data(value: DataVersions?): Builder = apply { options = options.copy(data = value) }
        /** Sets [DiffOptions.examples]. */
        public fun examples(value: ExampleSet?): Builder = apply { options = options.copy(examples = value) }
        /** Sets [DiffOptions.labels]. */
        public fun labels(value: VersionLabels?): Builder = apply { options = options.copy(labels = value) }
        /** Sets [DiffOptions.digests]. */
        public fun digests(value: Boolean): Builder = apply { options = options.copy(digests = value) }
        /** The [DiffOptions] these calls described. */
        public fun build(): DiffOptions = options
    }
}

/** The reference data of the [old] and the [new] version, always together. */
public data class DataVersions(val old: Path, val new: Path)

/**
 * Plain-text names for the [old] and the [new] version (e.g. `v1.2`), shown in the report's **Compared** block (giving labels switches the block on). Either may be left out: the
 * unnamed version is then shown by its digest alone, as `--old-label` without `--new-label` always has.
 */
public data class VersionLabels @JvmOverloads constructor(val old: String? = null, val new: String? = null)
