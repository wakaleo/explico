# Architecture

One page, per spec §10. For the full rationale behind any specific choice,
see `specs/explico-poc-specification.md` (the authoritative design) and
`CLAUDE.md` (every judgment call the spec doesn't pin, recorded as it was made).

## The pipeline

```
.rego files ──opa parse──▶ OpaModule JSON ──┐
                                             ├──▶ AstMapper ──▶ domain model ──┬──▶ MarkdownRenderer ──▶ render output
.rego dir   ──opa inspect─▶ METADATA JSON ───┘         (parse/)  (model/)     │         (render/)
                                                                              ├──▶ Canonicalizer + PolicyDiff
fixtures    ──opa eval────▶ verdict JSON ───────────────────────────────────▶│    + DiffRenderer ──▶ diff report
                                                       (Explico.kt orchestrates all of it)   (diff/)
```

1. **`opa/`** — `OpaRunner` shells out to the `opa` binary (`parse`, `inspect`,
   `eval`); `OpaJson.kt` holds `@Serializable` DTOs for exactly the fields
   explico consumes, `Json { ignoreUnknownKeys = true }` so `opa` upgrades
   don't break deserialization.
2. **`parse/AstMapper.kt`** — the only place raw `opa` JSON is ever read. Maps
   it into the domain model (`model/Model.kt`): `PolicySet` → `PolicyPackage`
   → `RuleGroup` → `RuleBody` → `Condition`/`Operand`/`PathSegment`. Anything
   it can't confidently classify becomes `Unrendered`, carrying the exact
   verbatim source (base64-decoded from the AST node's own `location.text`)
   and a machine-readable reason — never a guess standing in for logic the
   mapper didn't actually understand.
3. **`render/`** — pure functions over the domain model, no `opa` calls:
   `PathHumanizer` (a field-chain path → a `▸`-joined breadcrumb),
   `ExpressionRenderer` (a `Condition` → one English-ish bullet),
   `MarkdownRenderer` (assembles cards/pages/index), `Coverage` (rendered vs.
   total conditions), `Examples`/`WorkedExamples` (fixture verdicts, obtained
   via `opa eval` and orchestrated from `Explico.kt`, then rendered here).
4. **`diff/`** — also pure functions over the domain model:
   `Canonicalizer` (SHA-256 of a rule's logic/metadata, insensitive to
   formatting/comments/variable names), `PolicyDiff` (identity resolution +
   ADDED/REMOVED/LOGIC_CHANGED/DOCS_CHANGED/UNCHANGED classification),
   `LineDiffer` (dependency-free LCS line diff), `DiffRenderer` (the change
   report, reusing `MarkdownRenderer.renderCard` rather than a second,
   divergent card implementation).
5. **`Explico.kt`** — the public facade (`load`/`loadExamples`/`render`/`diff`)
   orchestrating the above; the only other public surface is the domain model
   itself (`model/Model.kt`) and a handful of return types that live next to
   the module producing them (`CoverageSummary`, `Fixture`/`ExampleSet`,
   `DiffEntry`/`DiffCategory`) because they're part of a public function's
   signature. Everything else is `internal`.
6. **`cli/Main.kt`** — a thin Clikt wrapper: three commands, each catching
   exactly the exception types the library throws and mapping them to the
   spec's exit codes, never letting an exception reach the JVM's default
   handler (which would print a raw stack trace).

## Why there is no Kotlin Rego parser

Rego is a real, non-trivial language — indentation-insensitive but with its
own comprehension/every/`with`/`else` semantics, its own compilation and type
inference (particularly around v1 vs. legacy syntax and `future.keywords`).
Reimplementing a parser in Kotlin would mean explico's understanding of Rego
could silently drift from OPA's actual behavior, which is the one thing the
whole tool exists to prevent: **every statement in the output must be true by
construction**, i.e. traceable to what `opa` itself actually parsed and
evaluated. Shelling out to the real `opa` binary means explico's picture of a
policy's AST and its runtime verdicts are never a second opinion — they're
the same opinion Rego's own reference implementation has. The cost (a process
invocation per file/eval, `--json-include locations` being required but
undocumented in older docs) is a fixed, well-understood cost, paid once per
`load`/`render`/`diff` call — cheaper than the alternative risk.

## Extension points

- **Builtin rendering** (`render/ExpressionRenderer.kt`'s `BUILTIN_TEMPLATES`
  map): the verb/negated-verb/argument-order for a condition-position
  builtin like `startswith`. Adding one is table-row-sized — see the
  README's Contributing section. The builtin must already be recognised by
  `parse/AstMapper.kt`'s `CONDITION_BUILTINS` set first; if it isn't, that's
  a mapper change (a new construct to classify), not just a rendering one.
- **Path humanisation** (`render/PathHumanizer.kt`): turns a `PathSegment`
  chain into the breadcrumb text readers see (`deployment ▸ environment`).
  Word-splitting (camelCase/snake_case/kebab-case) and the `input.`-root
  drop live here; a genuinely new segment *shape* (not just a new field
  name) would be a domain-model change first (`model/Model.kt`'s
  `PathSegment` sealed interface), since `PathHumanizer` only ever sees
  shapes `AstMapper` already produced.
- **The fallback mechanism itself is not an extension point** — it's the
  answer for everything the two extension points above don't cover, and
  deliberately stays that way. A new Rego construct should almost always
  render as `Unrendered` with its real reason, not grow a bespoke renderer,
  unless it's genuinely common enough in the acceptance pack (or a real
  policy corpus) to justify a real `Condition`/`Operand` variant, a
  humaniser rule, and the tests that prove both against real `opa` output —
  the same bar every existing construct had to clear.
