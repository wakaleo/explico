# Contributing to explico

explico's non-negotiables, development cycle and the reasoning behind its design are recorded in the repository, and they apply to every change:

- [`CLAUDE.md`](CLAUDE.md): the hard rules (no Rego parsing in Kotlin, the verbatim-source fallback, determinism, the closed dependency list), the test tiers and the development cycle.
- [`ARCHITECTURE.md`](ARCHITECTURE.md): the pipeline and why there is no Kotlin Rego parser.
- [`specs/explico-poc-specification.md`](specs/explico-poc-specification.md): the design specification.
- [`src/test/resources/acceptance/README.md`](src/test/resources/acceptance/README.md): the acceptance pack and its frozen assertions.
- [`docs/compatibility.md`](docs/compatibility.md): what is stable. A change to the public API or the CLI also changes the committed baselines `api/explico.api` and `api/explico.cli`, which is a reviewed act.

Run `./gradlew check` (no `opa` needed) and `./gradlew acceptanceTest` (needs `opa` 1.19.0) before you propose a change. Golden regeneration
(`-Dexplico.updateGolden=true`) is always deliberate and reviewed, never a side effect of getting a build green.

## Adding a new recognised builtin's rendering

The smallest, most self-contained extension point:

1. If the builtin is not already recognised as a condition, add it to `CONDITION_BUILTINS` in `parse/AstMapper.kt` (the mapper must classify it as a
   `Condition.BuiltinCall` before anything downstream can render it).
2. Add a row to `BUILTIN_TEMPLATES` in `render/ExpressionRenderer.kt`: the verb, its negated form, and which argument is the "subject" and which the "other"
   operand (see `startswith`/`contains` for the common case, or `regex.match`/`glob.match` for a pattern-first signature). Verify the phrase against a real `opa eval` first.
3. Add a unit test in `ExpressionRendererTest` exercising both the affirmative and negated rendering.
4. If any acceptance-pack policy uses the builtin, regenerate the affected golden(s) and review the diff before committing.

For anything larger (a new `Condition`/`Operand` shape, a new fallback category), read `CLAUDE.md`'s "Hard rules" first, in particular rule 2: when the
mapper cannot classify a construct with full confidence, it must fall back to verbatim source and never guess.
