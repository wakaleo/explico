/**
 * Maps `opa parse` DTOs ([io.explico.opa.OpaModule]) to the domain model (spec §5).
 *
 * `count`/`lower`/`upper` in operand position map to [io.explico.model.Operand.BuiltinCall]
 * (spec §14 promotion) when their single argument itself resolves cleanly; `object.get(o, k, d)`
 * promotes too, but only when `o` is a real path and `k` a plain string literal (see
 * [mapCallOperand]'s own KDoc for why a non-string key isn't promoted); `time.now_ns()` promotes
 * unconditionally, since it takes no arguments to resolve; `plus`/`minus`/`mul`/`div`/`rem`
 * (arithmetic) promote unconditionally on arity 2, recursively at any nesting depth;
 * `concat(sep, collection)` promotes too, via its own dedicated expansion of the collection
 * argument (see [mapConcatOperand]'s own KDoc). Anything else (still a documented gap) maps to
 * `Operand.Unrendered`, honestly reflecting that we can't render it rather than guessing a
 * phrase. Note that in the acceptance pack, `count({a | ...})
 * == 0` wraps a comprehension, which forces the whole condition to fall back regardless of the
 * builtin promotion above -- see [ConstructResult.Unsupported].
 *
 * [mapSomeIn] additionally recognises the two-variable form `some k, v in c` (spec §14 promotion,
 * `internal.member_3`), not just the single-variable `some v in c` (`internal.member_2`) -- both
 * `k` and `v` bind as iteration variables against the same collection.
 *
 * Metadata attachment (`opa inspect` -> `RuleMetadata`, see [buildMetadataIndex]) resolves each
 * annotation entry against the SPECIFIC [io.explico.opa.OpaRule] it decorates (keyed by file+row),
 * not just the (package, ruleName) pair -- see [buildMetadataIndex]'s own KDoc: a review finding
 * (not in the original spec §5 text) found several independently-`scope: rule`-annotated rules
 * sharing one name silently collapsed into one card under the old (package, name) index.
 * `default` declarations are handled too (same review pass): [mapRuleGroups] excludes a rule whose
 * `OpaRule.default` is true from `bodies` entirely and populates
 * [io.explico.model.RuleGroup.default] instead, via [defaultValueOf].
 *
 * A rule's own name may come from `head.name` (the `contains` keyword, and every complete-rule
 * head) or, when that's null, from `head.ref`'s own first segment -- see [effectiveName]'s KDoc.
 * This recognises the older bracket-key partial-rule sugar (`violation[{"msg": m}] if {...}`,
 * common in Gatekeeper-style policies predating `rego.v1`'s `contains` keyword) as a real, named
 * rule instead of silently dropping it, while deliberately leaving the genuinely different
 * ref-headed DOCUMENT-rule case (`fruit.apple.seeds := 12`, spec §14) excluded, exactly as before.
 *
 * Three further review findings (items 5-7), all in [mapPathSegments]/[mapBody]'s own KDoc:
 * `x := <collection>[_]` promotes to an iteration binding (a real `Condition.SomeIn`), not a
 * silent substitution; a wildcard `[_]` ANYWHERE in a path is now actually detected (it was
 * silently never matching before -- opa renames bare `_` to a gensym at parse time); and a
 * dynamic bracket key that is itself an `input`/`data` path humanizes as its own bracketed
 * sub-breadcrumb ([io.explico.model.PathSegment.KeyPath]) instead of raw dotted source text.
 */
package io.explico.parse

import io.explico.model.Condition
import io.explico.model.ConstantValue
import io.explico.model.DefaultValue
import io.explico.model.LiteralCollection
import io.explico.model.Operand
import io.explico.model.Operator
import io.explico.model.PackageMetadata
import io.explico.model.PathSegment
import io.explico.model.PolicyPackage
import io.explico.model.PolicySet
import io.explico.model.RuleBody
import io.explico.model.RuleGroup
import io.explico.model.RuleMetadata
import io.explico.model.SourceRef
import io.explico.opa.OpaExpr
import io.explico.opa.OpaInspectResult
import io.explico.opa.OpaModule
import io.explico.opa.OpaRule
import io.explico.opa.OpaRuleHead
import io.explico.opa.OpaTerm
import io.explico.opa.opaJson
import io.explico.render.PathHumanizer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/** One parsed `.rego` file: its module AST plus the repo-relative path it came from. */
internal data class ParsedFile(val sourceFile: String, val module: OpaModule)

/**
 * A condition-position builtin call recognised per spec §6.3's table, extended by review item 11:
 * `net.cidr_contains` (2 args, confirmed via a real `opa parse` run) and the seven `is_*` type
 * checks (1 arg each) -- the existing generic "map every arg, fall back whole on an unsupported
 * one" handling in [mapCallShapedCondition] already covers both arities with no further change.
 * `json.is_valid` (spec §14 promotion, coverage-audit backlog) joins the 1-arg unary family: it
 * returns `false` (not undefined) both for syntactically invalid JSON and for a non-string
 * argument (confirmed via a real `opa eval` run), so a unary-predicate wording is faithful in
 * either polarity -- unlike `json.marshal`, which is deliberately NOT promoted (it produces a
 * value, not a boolean, and has no faithful single-phrase template).
 */
private val CONDITION_BUILTINS = setOf(
    "startswith", "endswith", "contains", "regex.match", "glob.match",
    "net.cidr_contains",
    "is_string", "is_number", "is_boolean", "is_array", "is_object", "is_null", "is_set",
    "json.is_valid",
)

/**
 * An operand-position builtin call recognised per spec §6.3/§14 -- each takes exactly one
 * argument. Extended by review item 11: `sum`/`max`/`min`/`to_number`/`trim_space`/`object.keys`/
 * `time.parse_rfc3339_ns`, all confirmed single-argument via a real `opa parse` run, fit the exact
 * same promotion mechanism `count`/`lower`/`upper` already use.
 */
private val OPERAND_BUILTINS = setOf(
    "count", "lower", "upper",
    "sum", "max", "min", "to_number", "trim_space", "object.keys", "time.parse_rfc3339_ns",
)

/** Arithmetic infix operators (spec §14 promotion, backlog rank #1 after §14.8) -- opa's own operator names for `+`/`-`/`*`/`/`/`%`, confirmed via a real `opa parse` run; always binary. */
private val ARITHMETIC_BUILTINS = setOf("plus", "minus", "mul", "div", "rem")

/** Term types [mapOperand] renders directly as an `Operand.Literal` -- shared by [AstMapper.mapCollectionLiteral] (array/set element types) and [AstMapper.mapObjectLiteral] (object literal value types, spec §14 promotion). */
private val SCALAR_TERM_TYPES = setOf("string", "number", "boolean", "null")

/** Result of mapping a term into an operand: either a usable [Operand], or a marker that a fundamentally unsupported Rego construct (comprehension/every) was found underneath it and must promote the whole condition to [Condition.Unrendered]. */
private sealed interface ConstructResult {
    data class Ok(val operand: Operand) : ConstructResult
    data class Unsupported(val reason: String) : ConstructResult
}

/**
 * What a local variable name is bound to, within one rule body (spec §5/§6.4/§14). Two Rego
 * constructs populate this, with different substitution semantics -- conflating them would
 * misrender one as the other:
 * - [Iteration]: `some x in collection` -- `x` stands for one element, so later uses append a
 *   `PathSegment.VarIndex` ("[each x]") on top of the collection's own path.
 * - [Substitution]: `x := <plain input/data path>` (spec §5's promotion) -- `x` stands for that
 *   exact value, so later uses substitute the path directly, with no extra segment.
 * - [NonPath]: `x := <anything else>` -- spec §5 is explicit that later bare uses of `x` still
 *   render, as `Operand.Variable(x)`, never a guessed path and never conflated with a genuinely
 *   unbound/unknown variable (which stays `Operand.Unrendered`).
 * - [Expression]: `x := <anything that maps to a fully-rendered operand tree>` that is not a plain
 *   path (`diff := input.a - input.b`, `n := count(input.xs)`): Rego variables are
 *   single-assignment, so substituting the operand at each bare use is semantically faithful.
 *   Never created when any part of the operand is [Operand.Unrendered] -- that stays [NonPath].
 * - [Parameter]: a declared function parameter (review item 8) -- pre-populated into the symbol
 *   table before a function's own body is walked, so a bare use renders as `Operand.Parameter(x)`,
 *   a known named input rather than an opaque `Operand.Variable` or an unbound `Operand.Unrendered`.
 */
private sealed interface VarBinding {
    data class Iteration(val collection: Operand.Path) : VarBinding
    data class Substitution(val path: Operand.Path) : VarBinding
    object NonPath : VarBinding
    object Parameter : VarBinding

    /** `x := <operand expression>` that maps cleanly (arithmetic, set difference, `count(...)`, a literal, a rule-value reference...) -- later bare uses substitute the whole operand inline. */
    data class Expression(val operand: Operand) : VarBinding
}

internal object AstMapper {

    /**
     * Maps every parsed file into a [PolicySet], grouping same-named rules and sorting per spec
     * §2's determinism rule. [inspectResult] (from `opa inspect --annotations`) attaches
     * [io.explico.model.RuleMetadata] by matching its own `path` field (packagePath + ruleName) --
     * not row-proximity (spec §5's literal wording) -- since opa has already resolved which rule
     * each annotation belongs to, including correctly deduplicating a document-scoped annotation
     * across a rule's multiple bodies. `package`-scoped annotations are skipped: the domain model
     * has nowhere to attach package-level metadata, and no acceptance-pack policy uses that scope.
     */
    fun mapPolicySet(files: List<ParsedFile>, inspectResult: OpaInspectResult? = null): PolicySet {
        val registry = buildRuleRegistry(files)
        val partialRegistry = buildPartialRuleRegistry(files)
        val refHeadRegistry = buildRefHeadRuleRegistry(files)
        val functionRegistry = buildFunctionRegistry(files)
        val metadataIndex = buildMetadataIndex(inspectResult, files)
        val packages = files
            .groupBy { packagePath(it.module) }
            .map { (path, filesInPackage) ->
                val orderedFiles = filesInPackage.sortedBy { it.sourceFile }
                val rules = orderedFiles
                    .flatMap { mapRuleGroups(it, registry, partialRegistry, refHeadRegistry, functionRegistry, metadataIndex) }
                    .sortedBy { it.name }
                PolicyPackage(
                    path = path,
                    rules = rules,
                    sourceFiles = orderedFiles.map { it.sourceFile },
                    metadata = packageMetadataOf(orderedFiles),
                )
            }
            .sortedBy { it.path }
        return PolicySet(packages)
    }

    /**
     * Package-scope METADATA (review item 4) -- `opa parse`'s own [io.explico.opa.OpaModule.annotations]
     * already carries a `scope: package` annotation directly (confirmed via a real `opa parse` run:
     * identical title/description to the matching `opa inspect` entry), so this needs no `opa inspect`
     * matching at all. [filesInPackage] must already be sorted by source file (spec §2 determinism):
     * the first one (in that order) carrying a package-scope annotation wins, in the unlikely case more
     * than one file in the same package declares one.
     */
    private fun packageMetadataOf(filesInPackage: List<ParsedFile>): PackageMetadata? =
        filesInPackage
            .flatMap { it.module.annotations }
            .firstOrNull { it.scope == "package" }
            ?.let { PackageMetadata(title = it.title, description = it.description) }

    /**
     * Per-rule metadata (review item 1): an `opa inspect` annotation entry's own `path` field names
     * only the package and rule name, not which specific [OpaRule] statement it decorates -- fine
     * when every rule sharing that name shares ONE `scope: document` annotation (the acceptance
     * pack's own `release.approvals.deny`, 2 bodies, 1 inspect entry), but wrong when several
     * DIFFERENT `scope: rule` annotations (opa's own default when no scope is declared) decorate
     * SEPARATE same-named rule statements -- e.g. three independently-annotated
     * `deny contains msg if {...}` bodies. The old (package, name) -> metadata map collapsed all
     * three into one, silently discarding two (last-write-wins via `.toMap()`).
     *
     * Resolved here using facts opa itself already exposes, never a guess of our own. An inspect
     * entry's own `location` is NOT the METADATA comment's position -- it's the SAME row as the
     * decorated rule's own `OpaRule.location` (confirmed via real `opa parse`/`opa inspect` runs
     * on the same file: a `scope: rule` annotation's entry.row is an EXACT match for the one rule
     * it decorates, and a `scope: document` annotation covering several bodies gets exactly one
     * entry, whose row is an exact match for the FIRST decorated body -- never the comment's own
     * row, several lines earlier):
     * - `scope: document`/`subpackages` -- applies to EVERY rule in the same file sharing that
     *   name, exactly as the old index already did.
     * - anything else (opa's own default is `rule`) -- applies to exactly the ONE rule whose own
     *   row exactly matches the entry's row.
     *
     * Keyed by ([ParsedFile.sourceFile], row): a rule's own [io.explico.opa.OpaLocation.row] is
     * unique within a file, and [mapRuleGroups] looks this up once per individual [OpaRule]
     * rather than once per (package, name) group.
     *
     * `opa inspect --annotations <dir>`'s own `location.file` is `<dir>` plus the file's path
     * underneath it (confirmed via a real run: `src/.../policies/k8s.rego` for
     * `opa inspect ... src/.../policies`) -- NOT the same string as [ParsedFile.sourceFile], which
     * is relative to `dir` (spec §8.1, `Explico.load`'s own `policyDir.relativize`). Matching by
     * package first, then by [String.endsWith] against each candidate file's own `sourceFile`,
     * sidesteps needing to know or reconstruct that directory prefix at all.
     */
    private fun buildMetadataIndex(inspectResult: OpaInspectResult?, files: List<ParsedFile>): Map<Pair<String, Int>, RuleMetadata> {
        val filesByPackage: Map<String, List<ParsedFile>> = files.groupBy { packagePath(it.module) }
        val index = mutableMapOf<Pair<String, Int>, RuleMetadata>()
        for (entry in (inspectResult?.annotations ?: emptyList())) {
            if (entry.annotations.scope == "package") continue
            // A path element that is not a plain name (a ref-headed rule can carry other terms)
            // cannot identify a rule by name -- the entry is skipped (no metadata attaches), never fatal.
            if (entry.path.drop(1).any { it.type != "var" && it.type != "string" }) continue
            val segments = entry.path.drop(1).map { stringValueOf(it) }
            if (segments.size < 2) continue
            val entryPackage = segments.dropLast(1).joinToString(".")
            val ruleName = segments.last()
            val entryFile = entry.location.file
            val entryRow = entry.location.row
            val metadata = RuleMetadata(
                title = entry.annotations.title,
                description = entry.annotations.description,
                controlId = entry.annotations.custom?.controlId,
                frameworks = entry.annotations.custom?.frameworks ?: emptyList(),
            )
            val candidateFiles = (filesByPackage[entryPackage] ?: emptyList())
                .filter { entryFile == null || entryFile.endsWith(it.sourceFile) }
            val sameNameRules = candidateFiles.flatMap { pf -> pf.module.rules.filter { effectiveName(it.head) == ruleName }.map { pf.sourceFile to it } }
            val targets = if (entry.annotations.scope == "document" || entry.annotations.scope == "subpackages") {
                sameNameRules
            } else {
                sameNameRules.filter { (it.second.location?.row ?: 0) == entryRow }
            }
            for ((sourceFile, rule) in targets) {
                index[sourceFile to (rule.location?.row ?: 0)] = metadata
            }
        }
        return index
    }

    /**
     * The rule's name however opa's parser happened to populate it (review item 2). The `contains`
     * keyword populates `head.name` directly; the older bracket-key sugar
     * (`violation[{"msg": m}] if {...}`, a common Gatekeeper-style partial rule predating
     * `rego.v1`'s `contains` keyword) does NOT -- opa leaves `head.name` null and only populates
     * `head.ref` (confirmed via a real `opa parse` run: `ref == [var(violation), object(...)]`).
     * `ref.first()` is the rule's real name in that case, exactly like `head.name` already is for
     * the `contains` form -- reading it is not a guess, just a second place the SAME fact lives.
     * Requires `head.key != null` to stay clear of the genuinely different, deliberately
     * out-of-scope ref-HEADED document-rule case (`fruit.apple.seeds := 12`, spec §14 promotion) --
     * that shape has `head.key == null` and a multi-segment, ALL-literal path, not a single var
     * root plus one key term.
     */
    private fun effectiveName(head: OpaRuleHead): String? =
        head.name ?: head.ref.firstOrNull()?.takeIf { it.type == "var" && head.key != null }?.let { stringValueOf(it) }

    private fun packagePath(module: OpaModule): String =
        module.pkg.path.drop(1).joinToString(".") { stringValueOf(it) }

    /** Import alias -> fully-qualified package path, e.g. "exemptions" -> "release.exemptions" (the `as` name when given, else the last path segment). Non-`data.*` imports (e.g. `rego.v1`) are ignored. */
    private fun resolveImportAliases(module: OpaModule): Map<String, String> =
        module.imports.mapNotNull { import ->
            val chain = decodeTermList(import.path.value)
            if (chain.isEmpty() || stringValueOf(chain.first()) != "data") return@mapNotNull null
            val packageSegments = chain.drop(1)
            val alias = import.alias ?: stringValueOf(packageSegments.last())
            alias to packageSegments.joinToString(".") { stringValueOf(it) }
        }.toMap()

    private fun buildRuleRegistry(files: List<ParsedFile>): Map<String, Set<String>> =
        files.groupBy({ packagePath(it.module) }, { it.module })
            .mapValues { (_, modules) ->
                modules.flatMap { it.rules }.mapNotNull { effectiveName(it.head) }.toSet()
            }

    /**
     * Rule names that are partial (a `contains`/object rule, i.e. the head has a `key`) rather than
     * complete/single-value (spec §14 finding): a partial rule is ALWAYS defined -- even as an empty
     * set/object -- so a bare reference to one (negated or not) never behaves like a boolean flag.
     * `not partialRule` can NEVER succeed (confirmed via real `opa eval`: the enclosing rule stayed
     * undefined whether the partial rule was empty or non-empty), which "does not match" flatly
     * contradicts; a non-negated bare reference is unconditionally, tautologically true for the same
     * reason. [ruleReferenceIfKnown] refuses to classify either direction as a [Condition.RuleReference].
     */
    private fun buildPartialRuleRegistry(files: List<ParsedFile>): Map<String, Set<String>> =
        files.groupBy({ packagePath(it.module) }, { it.module })
            .mapValues { (_, modules) ->
                modules.flatMap { it.rules }.filter { it.head.key != null }.mapNotNull { effectiveName(it.head) }.toSet()
            }

    /**
     * Ref-headed rules (spec §14 promotion, backlog rank #1 after §14.12) -- e.g.
     * `fruit.apple.seeds := 12` -- have `head.name == null` (confirmed via a real `opa parse` run):
     * opa only populates `head.ref`, the rule's full dotted-path "name". `mapRuleGroups` still
     * filters these out entirely (a materially larger, separate concern -- surfacing them as their
     * own cards -- deliberately out of scope here, per the operator's explicit choice to scope this
     * promotion to "exact full-path reference only"), but a bare reference elsewhere in the policy
     * that spells out the SAME full path (`fruit.apple.seeds > 10`) can still resolve against this
     * registry and render as a proper breadcrumb instead of raw verbatim source.
     *
     * Only rules whose `head.ref` is ENTIRELY literal -- every segment after the root is a `"string"`
     * term, never `"var"` -- are registered. This is the exact, load-bearing distinction between
     * probe 13's shape (`fruit.apple.seeds`, all-literal) and probe 14's
     * (`users_by_role[role][id]`, whose `head.ref` is `[var(users_by_role), var(role), var(id)]` --
     * confirmed via a real `opa parse` run) -- a partial rule keyed by its OWN local variables has no
     * single static "full path" to match a reference against; `role`/`id` are placeholders, not
     * literal segments. Excluding non-literal refs here means probe 14 stays correctly unpromoted
     * with no separate check needed -- the type filter does the work.
     */
    private fun buildRefHeadRuleRegistry(files: List<ParsedFile>): Map<String, Set<String>> =
        files.groupBy({ packagePath(it.module) }, { it.module })
            .mapValues { (_, modules) ->
                modules.flatMap { it.rules }
                    .filter { it.head.name == null && it.head.ref.isNotEmpty() && it.head.ref.drop(1).all { segment -> segment.type == "string" } }
                    .map { it.head.ref.joinToString(".") { segment -> stringValueOf(segment) } }
                    .toSet()
            }

    /**
     * User-defined functions, same-package calls only (review item 8): package -> function name
     * -> declared parameter names, in order. `head.args` is null for an ordinary rule and a
     * (possibly empty) list of `"var"` terms for a function -- confirmed via a real `opa parse`
     * run. A call is resolved against this registry by [resolveFunctionCall] -- same package, an
     * import alias, or a full `data.` path. The first definition found wins when a function has several
     * overloaded bodies -- Rego requires consistent parameter names across them for the same
     * arity, so there is nothing to disambiguate.
     */
    private fun buildFunctionRegistry(files: List<ParsedFile>): Map<String, Map<String, List<String>>> =
        files.groupBy({ packagePath(it.module) }, { it.module })
            .mapValues { (_, modules) ->
                val byName = mutableMapOf<String, List<String>>()
                for (module in modules) {
                    for (rule in module.rules) {
                        val name = rule.head.name ?: continue
                        val args = rule.head.args ?: continue
                        byName.putIfAbsent(name, args.map { parameterNameOf(it) })
                    }
                }
                byName
            }

    /** One real-opa-rule statement paired with its resolved name and per-rule metadata (review items 1/2/3). */
    private data class NamedRule(val rule: OpaRule, val name: String, val metadata: RuleMetadata?)

    private fun mapRuleGroups(
        file: ParsedFile,
        registry: Map<String, Set<String>>,
        partialRegistry: Map<String, Set<String>>,
        refHeadRegistry: Map<String, Set<String>>,
        functionRegistry: Map<String, Map<String, List<String>>>,
        metadataIndex: Map<Pair<String, Int>, RuleMetadata>,
    ): List<RuleGroup> {
        val currentPackage = packagePath(file.module)
        val ctx = MappingContext(
            currentPackage = currentPackage,
            importAliases = resolveImportAliases(file.module),
            registry = registry,
            partialRegistry = partialRegistry,
            refHeadRegistry = refHeadRegistry,
            functionRegistry = functionRegistry,
            sourceFile = file.sourceFile,
        )
        val named = file.module.rules.mapNotNull { rule ->
            effectiveName(rule.head)?.let { name -> NamedRule(rule, name, metadataIndex[file.sourceFile to (rule.location?.row ?: 0)]) }
        }

        // A `default` declaration (review item 3) is never a Situation -- it's the one value the
        // rule takes when every real body falls through. Rego disallows more than one default per
        // rule name within a package, so this is a straight name -> value map, never a collision.
        val defaultsByName: Map<String, DefaultValue> = named
            .filter { it.rule.default }
            .associate { it.name to defaultValueOf(it.rule, ctx) }

        // Several independently-annotated rules can share a Rego-level name (review item 1) -- e.g.
        // three separately-`scope: rule`-annotated `deny contains msg if {...}` bodies. Grouping by
        // (name, metadata) rather than name alone keeps those as separate cards while still merging
        // bodies that genuinely share one annotation (document scope, or no annotation at all).
        val groupsWithoutDefault = named.filterNot { it.rule.default }
            .groupBy { it.name to it.metadata }
            .map { (key, items) ->
                val (name, metadata) = key
                val orderedRules = items.map { it.rule }.sortedBy { it.location?.row ?: 0 }
                // Review item 10, finding 4: a bodyless `name := value` constant only ever has ONE
                // rule statement for its name -- several sibling bodies means real Situations are
                // (or might be) in play, so the constant-value shape is deliberately not even
                // checked for that case; see constantValueOrNull's own KDoc.
                val constant = orderedRules.singleOrNull()?.let { constantValueOrNull(it, ctx) }
                RuleGroup(
                    name = name,
                    metadata = metadata,
                    default = null,
                    bodies = if (constant != null) emptyList() else orderedRules.map { mapBodyIsolated(it, name, ctx) },
                    // review item 8: a function's own declared parameters, for its card's own
                    // "Parameters" line. `head.args` is consistent across a function's overloaded
                    // bodies (same arity/names), so the first is as good as any.
                    parameters = orderedRules.first().head.args?.map { parameterNameOf(it) } ?: emptyList(),
                    constantValue = constant,
                )
            }
        val siblingCountByName: Map<String, Int> = groupsWithoutDefault.groupingBy { it.name }.eachCount()

        // Review item 14's follow-up: a `default` whose rule name has MULTIPLE sibling cards
        // becomes one separate entry instead of being repeated on every sibling (operator's
        // explicit choice) -- repeating it was noisy, and diffing a changed default per sibling
        // produced a LOGIC_CHANGED entry with an EMPTY source diff (the default isn't part of any
        // sibling's own body source). A name with exactly one sibling keeps the default attached
        // to that one card, unchanged from before this split existed.
        val groups = groupsWithoutDefault.map { group ->
            val default = defaultsByName[group.name]
            if (default != null && siblingCountByName.getValue(group.name) == 1) group.copy(default = default) else group
        }
        val splitDefaultEntries = defaultsByName.filterKeys { (siblingCountByName[it] ?: 0) > 1 }
            .map { (name, defaultValue) ->
                RuleGroup(
                    name = name,
                    metadata = null,
                    default = defaultValue,
                    bodies = emptyList(),
                    parameters = groupsWithoutDefault.first { it.name == name }.parameters,
                    isSplitDefaultEntry = true,
                )
            }

        // A rule name that's only ever a `default` declaration in this file, with no other body,
        // still deserves a card (the same "never silently drop a rule" principle as review item 2)
        // rather than vanishing because the loop above only ever emits a group for a non-default body.
        val namesAlreadyCovered = groupsWithoutDefault.map { it.name }.toSet()
        val defaultOnlyGroups = defaultsByName.filterKeys { it !in namesAlreadyCovered }
            .map { (name, defaultValue) -> RuleGroup(name = name, metadata = null, default = defaultValue, bodies = emptyList()) }

        return groups + splitDefaultEntries + defaultOnlyGroups
    }

    /**
     * `default <name> := <value>`'s value, rendered as its own verbatim source text (review item
     * 3) -- never a guessed re-formatting, the same "always true by construction" posture as every
     * other fallback/literal rendering in this mapper. A default's value is always a scalar or
     * composite literal (Rego disallows referencing `input`/`data`/a var in one), so this is exact.
     */
    private fun defaultValueOf(rule: OpaRule, ctx: MappingContext): DefaultValue =
        DefaultValue(sourceText(rule.head.value?.location), SourceRef(ctx.sourceFile, rule.location?.row ?: 0))

    /**
     * `name := value` with NO `if` at all (review item 10, finding 4). opa represents this as a
     * single synthetic body expr -- `{type: "boolean", value: true}` -- whose own `location.text`
     * is REUSED from the value's own location, confirmed via a real `opa parse` run: writing
     * `if true` explicitly out gives the exact same term shape (`boolean`/`true`) but a DIFFERENT
     * `location.text` ("true", not the value's own text). Comparing the two source texts is the
     * exact, mechanical, non-guessing signal that tells a genuinely bodyless rule apart from one
     * that happens to say `if true` -- the latter keeps the ordinary body/Situation rendering,
     * unaffected, same as before this promotion.
     *
     * `head.key != null` (a partial rule) is excluded even though no acceptance fixture exercises
     * a bodyless partial rule -- a partial rule's bracket value is one SET/OBJECT entry, not a
     * value standing alone, and guessing that the same mechanical signal applies there too would
     * be exactly the "widen a template to swallow a construct approximately" failure mode this
     * project's own promotions are never allowed to risk.
     */
    private fun constantValueOrNull(rule: OpaRule, ctx: MappingContext): ConstantValue? {
        if (rule.head.key != null) return null
        val value = rule.head.value ?: return null
        val onlyExpr = rule.body.singleOrNull() ?: return null
        val terms = onlyExpr.terms as? JsonObject ?: return null
        if ("type" !in terms) return null
        val term = opaJson.decodeFromJsonElement(OpaTerm.serializer(), terms)
        if (term.type != "boolean") return null
        if (sourceText(onlyExpr.location) != sourceText(value.location)) return null
        return ConstantValue(sourceText(value.location), SourceRef(ctx.sourceFile, rule.location?.row ?: 0))
    }

    /**
     * A function parameter's display name. Usually a plain variable, but Rego allows PATTERN
     * parameters (`f([a, b])`, `f("literal")`) -- found by benchmarking real-world corpora, where
     * `stringValueOf` on one aborted the whole load. A non-variable parameter is shown as its own
     * verbatim source, never guessed; only its position (arity) matters for call resolution.
     */
    private fun parameterNameOf(arg: OpaTerm): String =
        if (arg.type == "var") stringValueOf(arg) else sourceText(arg.location)

    /**
     * Failure isolation boundary for ONE rule body (found by benchmarking against real-world Rego
     * corpora: a single mapper exception on a single rule used to abort the render of every file).
     * The mapper cannot be proven total against all the opa output real policies produce, so a body
     * whose mapping throws degrades to the project's own honest fallback -- the rule's verbatim
     * source, reason `mapping-error`, never a guessed rendering -- while its sibling bodies and every
     * other rule still render. The failure is NEVER swallowed: a one-line warning naming the rule,
     * file, row and exception goes to stderr (the project's only logging channel).
     */
    private fun mapBodyIsolated(rule: OpaRule, ruleName: String, ctx: MappingContext): RuleBody =
        try {
            mapBody(rule, ctx)
        } catch (e: Exception) {
            val row = rule.location?.row ?: 0
            val thrownIn = e.stackTrace.filter { it.className.startsWith("io.explico") }.take(2).joinToString(" <- ") { it.methodName }.ifEmpty { "?" }
            System.err.println("explico: warning: could not map rule '$ruleName' (${ctx.sourceFile}:$row): ${e.javaClass.simpleName} in $thrownIn: ${e.message?.lineSequence()?.firstOrNull()}; shown as source")
            RuleBody(
                conditions = listOf(Condition.Unrendered(sourceText(rule.location), "mapping-error")),
                producesValue = null,
                messageTemplate = null,
                sourceLocation = SourceRef(ctx.sourceFile, row),
                sourceText = sourceText(rule.location),
            )
        }

    private fun mapBody(rule: OpaRule, ctx: MappingContext): RuleBody {
        // An else-chain (spec §14 finding): opa's own top-level `rule.location` already spans the
        // WHOLE chain (every branch), confirmed empirically against real opa parse output -- so the
        // existing whole-rule source slice is already the correct fallback text, no extra decoding
        // needed. The chain's later branches are a priority-ordered alternative, not a simple OR of
        // situations, so this demotes the entire body rather than attempting a per-branch rendering.
        if (rule.elseBranch != null) {
            return RuleBody(
                conditions = listOf(Condition.Unrendered(sourceText(rule.location), "else-chain")),
                producesValue = null,
                messageTemplate = null,
                sourceLocation = SourceRef(ctx.sourceFile, rule.location?.row ?: 0),
                sourceText = sourceText(rule.location),
            )
        }

        val symbolTable = mutableMapOf<String, VarBinding>()
        // A function's own declared parameters (review item 8) are in scope for its WHOLE body,
        // before any expr is walked -- a bare use resolves to Operand.Parameter, not an unbound
        // Operand.Unrendered.
        rule.head.args?.forEach { arg -> if (arg.type == "var") symbolTable[stringValueOf(arg)] = VarBinding.Parameter }
        val messageVar = messageVarOf(rule.head)
        var producesValue: String? = null
        var messageTemplate: String? = null
        val conditions = mutableListOf<Condition>()

        for (expr in rule.body) {
            // A `with` override (spec §14 finding): attaches to ANY expr shape, and silently
            // changes what's actually being evaluated (a modified, hypothetical input/data/function),
            // which no existing Condition template describes -- demoted before shape-dispatch so it
            // can never be misclassified as testing the real input.
            if (expr.with != null) {
                conditions += Condition.Unrendered(sourceText(expr.location), "with-override")
                continue
            }
            val assignedMessage = messageVar?.let { matchMessageAssignment(expr, it) }
            if (assignedMessage != null) {
                producesValue = renderProducesValue(assignedMessage, symbolTable)
                messageTemplate = computeMessageTemplate(assignedMessage)
                continue
            }
            // A local-variable assignment (spec §5 promotion, spec §14 backlog): `x := <plain
            // path>` records the binding and disappears from the output entirely -- no fallback
            // bullet, substituted inline wherever `x` is used later in this body. Anything else on
            // the right-hand side still becomes a visible Unrendered bullet (as before), but `x` is
            // now known to be *assigned*, not merely absent, so later bare uses render as
            // `Operand.Variable(x)` per spec §5's own wording, not the generic unbound fallback.
            val assignment = matchAssignment(expr)
            if (assignment != null) {
                val (targetVar, valueTerm) = assignment
                val plainPath = plainPathOperandOrNull(valueTerm, symbolTable, ctx)
                val expression = if (plainPath == null) expressionOperandOrNull(valueTerm, symbolTable, ctx) else null
                when {
                    // `x := <collection>[_]` (review item 5): Rego's wildcard index means "for some
                    // element of <collection>" -- the exact same existential claim `some x in
                    // <collection>` makes, confirmed by both desugaring through internal.member_2
                    // identically once bound. Rendered through that SAME real Condition.SomeIn +
                    // VarBinding.Iteration mechanism, not silently substituted -- substitution would
                    // hide the fact that x ranges over elements of a collection, not one fixed value.
                    // Only the TRAILING position qualifies: a middle wildcard (`x := input.a[_].b`)
                    // falls through to plain substitution below -- real but rare, and the resulting
                    // path (with the AnyIndex preserved mid-chain) still renders honestly via
                    // PathHumanizer's existing AnyIndex handling, just without an explicit SomeIn bullet.
                    plainPath != null && plainPath.segments.lastOrNull() == PathSegment.AnyIndex -> {
                        val collection = Operand.Path(plainPath.segments.dropLast(1))
                        symbolTable[targetVar] = VarBinding.Iteration(collection)
                        conditions += Condition.SomeIn(targetVar, collection)
                    }
                    plainPath != null -> symbolTable[targetVar] = VarBinding.Substitution(plainPath)
                    // Mapped BEFORE targetVar is bound, so a self-reference (`x := x + 1`) sees x as
                    // unbound and stays a fallback rather than looping or substituting itself.
                    expression != null -> symbolTable[targetVar] = VarBinding.Expression(expression)
                    else -> {
                        symbolTable[targetVar] = VarBinding.NonPath
                        // Review item 12: a distinct reason from a genuinely unresolved CONDITION
                        // call's "function-call" -- this is specifically a local-variable
                        // assignment the mapper can't substitute, which MarkdownRenderer groups
                        // with its own kind only (never with an adjacent, differently-reasoned
                        // fallback) into one labelled "helper definition(s)" block.
                        conditions += Condition.Unrendered(sourceText(expr.location), "helper-assignment")
                    }
                }
                continue
            }
            conditions += mapCondition(expr, symbolTable, ctx)
        }

        return RuleBody(
            conditions = conditions,
            producesValue = producesValue,
            messageTemplate = messageTemplate,
            sourceLocation = SourceRef(ctx.sourceFile, rule.location?.row ?: 0),
            sourceText = sourceText(rule.location),
            // Computed AFTER the loop above, so a `n := input.limit`-style local assignment is
            // already in `symbolTable` when `limit := n` itself is resolved (follow-up to review
            // item 10).
            yieldsValue = yieldsValueOrNull(rule, symbolTable, ctx),
        )
    }

    /**
     * The value a CONDITIONAL complete rule's body yields, e.g. `"high"` for `tier := "high" if
     * input.risk > 80` (follow-up to review item 10). Reuses [mapOperand] directly -- a rule's own
     * value is mechanically the same shape as any other operand, including an unrecognised one
     * ([ConstructResult.Unsupported] becomes the ordinary verbatim [Operand.Unrendered] fallback,
     * never a guess).
     *
     * Two cases return null, deliberately: a partial rule (`head.key != null`, bracket/contains
     * sugar) has no single scalar value to show -- its own message is handled separately via
     * [messageVarOf]/[renderProducesValue]; and a plain boolean rule's IMPLICIT `true`
     * (`flag if input.enabled`, never written explicitly) has no `location` at all on
     * `head.value` -- confirmed via a real `opa parse` run that opa synthesizes this term with no
     * source span, the one mechanical signal that distinguishes it from an EXPLICIT `:= true`
     * (which has real location text, "true"). Showing a value line for the implicit case would
     * imply the author wrote something they didn't.
     */
    private fun yieldsValueOrNull(rule: OpaRule, symbolTable: Map<String, VarBinding>, ctx: MappingContext): Operand? {
        if (rule.head.key != null) return null
        val value = rule.head.value ?: return null
        if (value.location == null) return null
        return when (val result = mapOperand(value, symbolTable, ctx)) {
            is ConstructResult.Ok -> result.operand
            is ConstructResult.Unsupported -> Operand.Unrendered(sourceText(value.location))
        }
    }

    /**
     * The rule head's own message variable (spec §5/§6.2, extended review item 9). A bare var in
     * `head.key` position (`deny contains msg`) names it directly. An OBJECT in `head.key`
     * position (`deny contains {"msg": msg, "severity": "high"}`, or the bracket-form sugar
     * `violation[{"msg": m}]` -- both populate `head.key` identically as the object term,
     * confirmed via a real `opa parse` run) names it via the pair whose KEY is the literal string
     * `"msg"` -- the de facto standard field name for a structured deny message across the Rego
     * ecosystem (OPA's own style guide, Gatekeeper's ConstraintTemplate convention), a deliberate,
     * disclosed convention choice, not a guess about an arbitrary key name. Any other object shape
     * (no `"msg"` key, or its value isn't a bare var) stays null -- no Produces line, exactly as
     * before this promotion.
     */
    private fun messageVarOf(head: OpaRuleHead): String? {
        val key = head.key ?: return null
        if (key.type == "var") return stringValueOf(key)
        if (key.type != "object") return null
        val pairs = opaJson.decodeFromJsonElement(ListSerializer(ListSerializer(OpaTerm.serializer())), key.value ?: JsonArray(emptyList()))
        val msgValue = pairs.firstOrNull { it.size == 2 && it[0].type == "string" && stringValueOf(it[0]) == "msg" }?.get(1) ?: return null
        return msgValue.takeIf { it.type == "var" }?.let { stringValueOf(it) }
    }

    /** If [expr] is `<messageVar> := <value>`, returns the value term; else null. */
    private fun matchMessageAssignment(expr: OpaExpr, messageVar: String): OpaTerm? =
        matchAssignment(expr)?.takeIf { it.first == messageVar }?.second

    /** If [expr] is `<localVar> := <value>` (any local variable, not just the message var), returns (variable name, value term); else null. */
    private fun matchAssignment(expr: OpaExpr): Pair<String, OpaTerm>? {
        val terms = expr.terms as? JsonArray ?: return null
        if (terms.size != 3) return null
        val list = decodeTermList(terms)
        val (name, args) = decodeCallShape(list) ?: return null
        if (name != "assign" || args.size != 2) return null
        val target = args[0]
        if (target.type != "var") return null
        return stringValueOf(target) to args[1]
    }

    /**
     * Spec §5's promotion: the assignment's right-hand side qualifies for substitution only when
     * it's itself a plain `input`/`data`/already-bound-variable path -- resolved through the SAME
     * [mapRefChain] every other path reference goes through, so a chained assignment
     * (`a := input.x; b := a.y`) works via [VarBinding.Substitution] resolution for free. Anything
     * that resolves to `Operand.Unrendered` (unbound, or genuinely not a "ref" term at all) does
     * NOT qualify -- the assignment stays a visible fallback bullet instead of silently vanishing
     * while pointing nowhere.
     */
    /**
     * Generalises [plainPathOperandOrNull]: a right-hand side that maps through the ordinary operand
     * pipeline to a tree with NO [Operand.Unrendered] anywhere inside it qualifies for inline
     * substitution. A comprehension, an unknown builtin, or an unbound variable anywhere in the
     * expression disqualifies the WHOLE assignment -- it stays a visible helper-assignment fallback.
     */
    private fun expressionOperandOrNull(term: OpaTerm, symbolTable: Map<String, VarBinding>, ctx: MappingContext): Operand? {
        val operand = (mapOperand(term, symbolTable, ctx) as? ConstructResult.Ok)?.operand ?: return null
        return operand.takeIf { isFullyRendered(it) }
    }

    private fun isFullyRendered(operand: Operand): Boolean = when (operand) {
        is Operand.Unrendered -> false
        is Operand.BuiltinCall -> operand.args.all { isFullyRendered(it) }
        is Operand.FunctionCall -> operand.args.all { isFullyRendered(it) }
        else -> true
    }

    private fun plainPathOperandOrNull(term: OpaTerm, symbolTable: Map<String, VarBinding>, ctx: MappingContext): Operand.Path? {
        if (term.type != "ref") return null
        val result = mapRefChain(decodeTermList(term.value), symbolTable, ctx, term.location)
        return (result as? ConstructResult.Ok)?.operand as? Operand.Path
    }

    private fun mapCondition(expr: OpaExpr, symbolTable: MutableMap<String, VarBinding>, ctx: MappingContext): Condition {
        return when (val terms = expr.terms) {
            is JsonArray -> mapCallShapedCondition(expr, decodeTermList(terms), symbolTable, ctx)
            is JsonObject -> when {
                "symbols" in terms -> mapSomeIn(expr, terms, symbolTable, ctx)
                "domain" in terms -> Condition.Unrendered(sourceText(expr.location), "every")
                "type" in terms -> mapSingleTermCondition(expr, opaJson.decodeFromJsonElement(OpaTerm.serializer(), terms), symbolTable, ctx)
                else -> Condition.Unrendered(sourceText(expr.location), "unclassified")
            }
            else -> Condition.Unrendered(sourceText(expr.location), "unclassified")
        }
    }

    private fun mapCallShapedCondition(
        expr: OpaExpr,
        terms: List<OpaTerm>,
        symbolTable: Map<String, VarBinding>,
        ctx: MappingContext,
    ): Condition {
        val (name, args) = decodeCallShape(terms) ?: return Condition.Unrendered(sourceText(expr.location), "unclassified")
        val comparisonOp = COMPARISON_OPERATORS[name]
        if (comparisonOp != null && args.size == 2) {
            return buildComparisonLike(expr, args, symbolTable, ctx) { left, right -> Condition.Comparison(left, comparisonOp, right, expr.negated) }
        }
        if (name == "eq" && args.size == 2) {
            eqAsPureComparisonOrNull(args, expr.negated, symbolTable, ctx)?.let { return it }
        }
        if (name == "internal.member_2" && args.size == 2) {
            return buildComparisonLike(expr, args, symbolTable, ctx) { member, collection ->
                Condition.Membership(expr.negated, member, collection)
            }
        }
        if (name in CONDITION_BUILTINS) {
            val mapped = args.map { mapOperand(it, symbolTable, ctx) }
            val unsupported = mapped.filterIsInstance<ConstructResult.Unsupported>().firstOrNull()
            if (unsupported != null) return Condition.Unrendered(sourceText(expr.location), unsupported.reason)
            val operands = mapped.filterIsInstance<ConstructResult.Ok>().map { it.operand }
            return Condition.BuiltinCall(name, operands, expr.negated)
        }
        // A call to a user-defined function (review item 8; cross-package via imports added after the
        // real-world corpus benchmark): see [resolveFunctionCall]. An argument that doesn't resolve at
        // all (a comprehension) falls through to the generic fallback below, never a
        // partially-faithful call.
        val target = resolveFunctionCall(name, args.size, ctx)
        if (target != null) {
            val mapped = args.map { mapOperand(it, symbolTable, ctx) }
            val unsupported = mapped.filterIsInstance<ConstructResult.Unsupported>().firstOrNull()
            if (unsupported == null) {
                val operands = mapped.map { (it as ConstructResult.Ok).operand }
                return Condition.FunctionCall(target.first, target.second, operands, expr.negated)
            }
        }
        return Condition.Unrendered(sourceText(expr.location), "function-call")
    }

    /**
     * Resolves a call's dotted [name] to the (package, function) it names, or null when it names no
     * user-defined function of exactly [argCount] parameters -- the one resolution rule shared by
     * condition- and operand-position calls. Three spellings, all confirmed via real `opa parse`
     * output: a bare name (this package), `<import alias>[.<sub package>].<fn>` (an `import data.x.y`
     * or `import data.x.y as z`), and a full `data.<package>.<fn>` path. A package or function the
     * loaded policy set does not define resolves to null (the caller falls back), never a guess.
     */
    private fun resolveFunctionCall(name: String, argCount: Int, ctx: MappingContext): Pair<String, String>? {
        val segments = name.split('.')
        val functionName = segments.last()
        val packagePath = when {
            segments.size == 1 -> ctx.currentPackage
            segments[0] == "data" && segments.size >= 3 -> segments.subList(1, segments.size - 1).joinToString(".")
            else -> ctx.importAliases[segments[0]]?.let { (listOf(it) + segments.subList(1, segments.size - 1)).joinToString(".") }
        } ?: return null
        return if (ctx.functionRegistry[packagePath]?.get(functionName)?.size == argCount) packagePath to functionName else null
    }

    private inline fun buildComparisonLike(
        expr: OpaExpr,
        args: List<OpaTerm>,
        symbolTable: Map<String, VarBinding>,
        ctx: MappingContext,
        build: (Operand, Operand) -> Condition,
    ): Condition {
        val left = mapOperand(args[0], symbolTable, ctx)
        val right = mapOperand(args[1], symbolTable, ctx)
        val unsupported = listOf(left, right).filterIsInstance<ConstructResult.Unsupported>().firstOrNull()
        if (unsupported != null) return Condition.Unrendered(sourceText(expr.location), unsupported.reason)
        return build((left as ConstructResult.Ok).operand, (right as ConstructResult.Ok).operand)
    }

    /**
     * `=` (opa's own operator name "eq", spec §14 promotion, backlog rank #1 after §14.6) is
     * Rego's general unification operator -- unlike `==` ("equal"), which the language guarantees
     * never introduces a binding, `=` can ALSO destructure/bind a fresh variable (`[x, y] = [...]`
     * binds x and y; probe 04). Promoting it to [Condition.Comparison] is only safe once BOTH sides
     * are confirmed to already resolve to a genuine value through the exact same [mapOperand] path
     * `==` uses -- an unbound bare var (`x` in `x = input.y` when `x` is fresh) or a composite
     * literal containing one (`[x, y]`) maps to `Operand.Unrendered` (never `Unsupported` -- see
     * [mapCollectionLiteral]/[mapVarOperand]), which is this function's positive signal that a new
     * binding may be in play, not a comparison. Returns null (never a guess) for that case, and for
     * a comprehension/every on either side too -- the caller's existing "function-call" fallback
     * handles both exactly as it already did before this promotion existed. [negated] (`not x = y`,
     * also valid Rego) is orthogonal to the binding-vs-comparison question above and threads straight
     * through to the built [Condition.Comparison] -- see spec §14's negated-comparison amendment.
     */
    private fun eqAsPureComparisonOrNull(args: List<OpaTerm>, negated: Boolean, symbolTable: Map<String, VarBinding>, ctx: MappingContext): Condition? {
        val left = mapOperand(args[0], symbolTable, ctx)
        val right = mapOperand(args[1], symbolTable, ctx)
        if (left !is ConstructResult.Ok || right !is ConstructResult.Ok) return null
        if (left.operand is Operand.Unrendered || right.operand is Operand.Unrendered) return null
        return Condition.Comparison(left.operand, Operator.EQ, right.operand, negated)
    }

    private fun mapSomeIn(expr: OpaExpr, terms: JsonObject, symbolTable: MutableMap<String, VarBinding>, ctx: MappingContext): Condition {
        val symbols = opaJson.decodeFromJsonElement(ListSerializer(OpaTerm.serializer()), terms.getValue("symbols"))
        val callTerm = symbols.singleOrNull() ?: return Condition.Unrendered(sourceText(expr.location), "unclassified")
        // A declare-only `some x` (no `in`) has exactly one symbol too, but its value is a bare var
        // term, not a call-shaped `internal.member_2` term -- decodeTermList would otherwise crash
        // trying to deserialize a JsonPrimitive as a JsonArray (spec §14 finding: this took down the
        // whole render, not just this one condition, before the guard existed).
        if (callTerm.type != "call") return Condition.Unrendered(sourceText(expr.location), "unclassified")
        val (name, args) = decodeCallShape(decodeTermList(callTerm.value)) ?: return Condition.Unrendered(sourceText(expr.location), "unclassified")
        // `some v in c` desugars to internal.member_2(v, c); the two-variable form
        // `some k, v in c` (spec §14 backlog rank #1) desugars to internal.member_3(k, v, c) --
        // confirmed via real `opa parse` output. Both k and v bind as VarBinding.Iteration against
        // the same collection: Rego doesn't distinguish how a key vs. value loop variable can later
        // be used, so neither does this mapper.
        val (key, variable, collectionTerm) = when {
            name == "internal.member_2" && args.size == 2 && args[0].type == "var" ->
                Triple(null, stringValueOf(args[0]), args[1])
            name == "internal.member_3" && args.size == 3 && args[0].type == "var" && args[1].type == "var" ->
                Triple(stringValueOf(args[0]), stringValueOf(args[1]), args[2])
            else -> return Condition.Unrendered(sourceText(expr.location), "unclassified")
        }
        // `some x in workloads.containers` / `some x in local_items`: the collection IS a partial rule's
        // value, so the bare rule reference (which an operand position refuses) is exactly right here.
        val ruleCollection = if (collectionTerm.type == "var" || collectionTerm.type == "ref") {
            val chain = if (collectionTerm.type == "var") listOf(collectionTerm) else decodeTermList(collectionTerm.value)
            val root = chain.first()
            if ((root.type == "var" || root.type == "string") && symbolTable[stringValueOf(root)] == null) {
                partialRuleRootedPath(stringValueOf(root), chain.drop(1), symbolTable, ctx, allowBareRule = true)
            } else null
        } else null
        val collection = if (ruleCollection != null) ConstructResult.Ok(ruleCollection) else mapOperand(collectionTerm, symbolTable, ctx)
        val collectionPath = (collection as? ConstructResult.Ok)?.operand as? Operand.Path
            ?: return Condition.Unrendered(sourceText(expr.location), (collection as? ConstructResult.Unsupported)?.reason ?: "unclassified")
        if (key != null) symbolTable[key] = VarBinding.Iteration(collectionPath)
        symbolTable[variable] = VarBinding.Iteration(collectionPath)
        return Condition.SomeIn(variable, collectionPath, key)
    }

    private fun mapSingleTermCondition(
        expr: OpaExpr,
        term: OpaTerm,
        symbolTable: Map<String, VarBinding>,
        ctx: MappingContext,
    ): Condition {
        if (term.type == "var") {
            val name = stringValueOf(term)
            return ruleReferenceIfKnown(ctx.currentPackage, name, ctx, expr.negated)
                ?: Condition.Unrendered(sourceText(expr.location), fallbackReasonFor(ctx.currentPackage, name, ctx))
        }
        if (term.type != "ref") return Condition.Unrendered(sourceText(expr.location), "unclassified")

        val chain = decodeTermList(term.value)
        if (chain.isEmpty()) return Condition.Unrendered(sourceText(expr.location), "unclassified")
        val root = stringValueOf(chain.first())

        val aliasedPackage = ctx.importAliases[root]
        if (aliasedPackage != null && allPlainStringSegments(chain.drop(1))) {
            val ruleName = chain.drop(1).joinToString(".") { stringValueOf(it) }
            return ruleReferenceIfKnown(aliasedPackage, ruleName, ctx, expr.negated)
                ?: Condition.Unrendered(sourceText(expr.location), fallbackReasonFor(aliasedPackage, ruleName, ctx))
        }
        if (root == "data" && chain.size > 1 && allPlainStringSegments(chain.drop(1))) {
            val candidatePackage = chain.drop(1).dropLast(1).joinToString(".") { stringValueOf(it) }
            val candidateRule = stringValueOf(chain.last())
            // A known rule name (any kind) at a data.-prefixed path must never fall through to
            // mapRefChain below: an ordinary Path/Truthy rendering of a PARTIAL rule's data path
            // would resurface the exact same always-defined/tautological-truthiness issue
            // ruleReferenceIfKnown already guards against, just via a different Condition shape.
            if (ctx.registry[candidatePackage]?.contains(candidateRule) == true) {
                return ruleReferenceIfKnown(candidatePackage, candidateRule, ctx, expr.negated)
                    ?: Condition.Unrendered(sourceText(expr.location), "partial-rule-reference")
            }
        }
        val operand = mapRefChain(chain, symbolTable, ctx, term.location)
        return when (operand) {
            is ConstructResult.Ok -> Condition.Truthy(operand.operand, expr.negated)
            is ConstructResult.Unsupported -> Condition.Unrendered(sourceText(expr.location), operand.reason)
        }
    }

    /**
     * A known rule name (spec §14 finding) resolves to a real [Condition.RuleReference] only when
     * it's a COMPLETE rule -- a partial (`contains`/object) rule is always defined, even as an empty
     * set/object, so neither a negated nor a non-negated bare reference to one behaves like a
     * boolean flag (confirmed via real `opa eval`: the enclosing rule stayed undefined regardless of
     * the partial rule's contents). Refusing to classify it at all, rather than rendering a phrase
     * ("does not match" / implicitly "matches") that can never be true, is the safe fallback.
     */
    private fun ruleReferenceIfKnown(packagePath: String, ruleName: String, ctx: MappingContext, negated: Boolean): Condition.RuleReference? {
        if (ctx.registry[packagePath]?.contains(ruleName) != true) return null
        if (ctx.partialRegistry[packagePath]?.contains(ruleName) == true) return null
        return Condition.RuleReference(packagePath, ruleName, negated)
    }

    private fun fallbackReasonFor(packagePath: String, ruleName: String, ctx: MappingContext): String =
        if (ctx.partialRegistry[packagePath]?.contains(ruleName) == true) "partial-rule-reference" else "unclassified"

    /**
     * CHALLENGE edge case / real crash fix: a dotted chain's segment can be a COMPOSITE term, not
     * a plain field name -- e.g. `data.grants[input.user.id]` (review item 7's own KeyPath
     * construct) has `chain.last().type == "ref"`, not `"string"`. Every dotted-path-to-a-rule
     * check in this file joins/stringifies `chain.drop(1)` assuming plain string segments; calling
     * `stringValueOf` on a composite term throws (it reads `.value.jsonPrimitive`, which a
     * `JsonArray` value isn't). This guard must run BEFORE any of those checks, not just before
     * the ones added by this promotion -- the same latent crash already existed in
     * [mapSingleTermCondition]'s own data/alias checks, just never exercised by a real fixture
     * until this promotion's own acceptance corpus did (`some grant in data.grants[input.user.id]`).
     */
    private fun allPlainStringSegments(terms: List<OpaTerm>): Boolean = terms.all { it.type == "string" }

    /**
     * A known COMPLETE rule's VALUE, referenced directly as an operand (review item 10) -- e.g.
     * `threshold` (bare, same package), `config.max_items` (import alias), or
     * `data.rulevalues.config.region_prefix` (full data path): all three call sites resolve
     * through this one function. A PARTIAL (`contains`/object) rule is deliberately excluded --
     * same reasoning as [ruleReferenceIfKnown]'s own KDoc: it has no single scalar value to point
     * a reader at -- so it stays whatever fallback the caller already uses.
     */
    private fun ruleValueReferenceIfKnown(packagePath: String, ruleName: String, ctx: MappingContext): Operand.RuleValueReference? {
        if (ctx.registry[packagePath]?.contains(ruleName) != true) return null
        if (ctx.partialRegistry[packagePath]?.contains(ruleName) == true) return null
        return Operand.RuleValueReference(packagePath, ruleName)
    }

    // --- Operands ---

    private fun mapOperand(term: OpaTerm, symbolTable: Map<String, VarBinding>, ctx: MappingContext): ConstructResult = when (term.type) {
        "string" -> ConstructResult.Ok(Operand.Literal(quoted(stringValueOf(term))))
        "number" -> ConstructResult.Ok(Operand.Literal(term.value?.jsonPrimitive?.content ?: "0"))
        "boolean" -> ConstructResult.Ok(Operand.Literal(term.value?.jsonPrimitive?.content ?: "false"))
        "null" -> ConstructResult.Ok(Operand.Literal("null")) // spec §14 promotion, backlog rank #1 after §14.10 -- trivial, just never added.
        "ref" -> mapRefChain(decodeTermList(term.value), symbolTable, ctx, term.location)
        "var" -> mapVarOperand(term, symbolTable, ctx)
        "array", "set" -> mapCollectionLiteral(term, symbolTable, ctx)
        "object" -> mapObjectLiteral(term, symbolTable, ctx)
        "call" -> mapCallOperand(term, symbolTable, ctx)
        "setcomprehension", "arraycomprehension", "objectcomprehension" -> ConstructResult.Unsupported("comprehension")
        else -> ConstructResult.Unsupported("unclassified")
    }

    /**
     * A bare local-variable reference (spec §6.4 rule 7 / spec §5 promotion): resolved through
     * [VarBinding] -- see its own KDoc for what each kind means. A name absent from [symbolTable]
     * entirely is checked against the CURRENT package's own rules (review item 10) before falling
     * back -- a local binding always takes priority, same precedent as the ref-head rule registry
     * check in [mapRefChain]. Genuinely unknown to both stays the generic `Operand.Unrendered`
     * fallback.
     */
    private fun mapVarOperand(term: OpaTerm, symbolTable: Map<String, VarBinding>, ctx: MappingContext): ConstructResult =
        when (val binding = symbolTable[stringValueOf(term)]) {
            is VarBinding.Iteration -> ConstructResult.Ok(Operand.Path(binding.collection.segments + PathSegment.VarIndex(stringValueOf(term))))
            is VarBinding.Substitution -> ConstructResult.Ok(Operand.Path(binding.path.segments))
            VarBinding.NonPath -> ConstructResult.Ok(Operand.Variable(stringValueOf(term)))
            VarBinding.Parameter -> ConstructResult.Ok(Operand.Parameter(stringValueOf(term)))
            is VarBinding.Expression -> ConstructResult.Ok(binding.operand)
            null -> {
                val name = stringValueOf(term)
                val reference = ruleValueReferenceIfKnown(ctx.currentPackage, name, ctx)
                ConstructResult.Ok(reference ?: Operand.Unrendered(sourceText(term.location)))
            }
        }

    /**
     * Builds a Path from a decoded ref chain: `input`/`data` root, a known ref-headed rule's own
     * exact full path (spec §14 promotion, see [buildRefHeadRuleRegistry]'s own KDoc), or a
     * var-rooted path resolved via [symbolTable] (spec §6.4 rule 7 for [VarBinding.Iteration]; spec
     * §5's promotion for [VarBinding.Substitution], which continues the chain with no extra segment
     * since the variable stands for the exact value, not one element of a collection).
     * [wholeRefLocation] is used only for the unbound-variable fallback, so its source text covers
     * the whole reference, not just the root token. A [VarBinding.NonPath] root (assigned, but not
     * to a plain path) falls back to `Operand.Unrendered` rather than `Operand.Variable` -- spec §5
     * only specifies `Operand.Variable` for a *bare* use of such a variable, not a field chained off
     * it.
     *
     * The ref-head check requires [symbolTable] to have NO binding at all for the root name -- a
     * local variable always takes priority over a same-package rule name, avoiding any possible
     * ambiguity. It also requires the FULL chain (not just a prefix) to match a registered
     * ref-headed rule's own path exactly -- `fruit.apple.seeds.extra` (one segment beyond the
     * rule's own declaration) correctly does NOT match and falls through to the existing unresolved
     * fallback, the same deliberately conservative posture as every other promotion in this file:
     * only the unambiguous common shape is promoted, never guessed.
     */
    private fun mapRefChain(chain: List<OpaTerm>, symbolTable: Map<String, VarBinding>, ctx: MappingContext, wholeRefLocation: io.explico.opa.OpaLocation?): ConstructResult {
        if (chain.isEmpty()) return ConstructResult.Ok(Operand.Unrendered(""))
        val root = chain.first()
        // A chain's HEAD is not always a plain name: `util.f(x).lines[0]` heads on a call, `[a, b][0]`
        // on an array literal, `{...}[k]` on a comprehension (found by benchmarking real-world Rego
        // corpora; stringValueOf threw on each). None has a rendering, so it is the ordinary
        // operand fallback -- the chain's own verbatim source -- never a guess.
        if (root.type != "var" && root.type != "string") return ConstructResult.Ok(Operand.Unrendered(sourceText(wholeRefLocation)))
        val rootName = stringValueOf(root)
        val remaining = chain.drop(1)
        // Review item 10: a full `data.<package>.<rule>` path to a COMPLETE rule's value is a
        // reference, not a data path -- checked before the generic `data`-root path-building
        // below, same precedent as `mapSingleTermCondition`'s own data-prefixed rule check at
        // condition level (a partial rule there is excluded the same way, via ruleValueReferenceIfKnown).
        // When it IS a partial rule, this deliberately does NOT fall back to Unrendered the way the
        // bare/alias cases in mapVarOperand do -- it falls through to the ordinary data-path
        // building below instead, rendering a plain breadcrumb. Unlike a CONDITION-position bare
        // reference (which would tautologically claim "is present and not false" about an
        // always-defined partial rule), a comparison OPERAND makes no such claim: the breadcrumb is
        // a faithful, if unlinked, description of a real data path opa will actually resolve.
        if (rootName == "data" && chain.size > 1 && allPlainStringSegments(remaining)) {
            val candidatePackage = remaining.dropLast(1).joinToString(".") { stringValueOf(it) }
            val candidateRule = stringValueOf(chain.last())
            ruleValueReferenceIfKnown(candidatePackage, candidateRule, ctx)?.let { return ConstructResult.Ok(it) }
        }
        if (rootName == "input" || rootName == "data") {
            val segments = mapPathSegments(remaining, symbolTable, ctx) ?: return ConstructResult.Ok(Operand.Unrendered(sourceText(wholeRefLocation)))
            return ConstructResult.Ok(Operand.Path(listOf(PathSegment.Field(rootName)) + segments))
        }
        // Review item 10: an import-alias-rooted chain (`config.max_items`) is a reference to that
        // package's rule's value, resolved the same way a same-package bare name is in
        // mapVarOperand -- just against the aliased package. A local binding for the alias name
        // always takes priority (same precedent as the ref-head registry check just below).
        val aliasedPackage = ctx.importAliases[rootName]
        if (aliasedPackage != null && symbolTable[rootName] == null && allPlainStringSegments(remaining)) {
            val ruleName = remaining.joinToString(".") { stringValueOf(it) }
            ruleValueReferenceIfKnown(aliasedPackage, ruleName, ctx)?.let { return ConstructResult.Ok(it) }
        }
        // Iterating or indexing into a PARTIAL rule's value: `workloads.containers[_]` (import alias) or
        // `local_items[_]` (this package). The exact reference (nothing after the rule name) keeps its
        // existing treatment above; with a tail it is the rule's real document path, rendered like a
        // full `data.` path to a partial rule (found by the corpus benchmark: the single largest
        // assignment fallback, and it cascaded into every later use of the bound variable).
        if (symbolTable[rootName] == null) {
            partialRuleRootedPath(rootName, remaining, symbolTable, ctx)?.let { return ConstructResult.Ok(it) }
        }
        if (symbolTable[rootName] == null) {
            // Only a chain whose tail is entirely plain names can name a ref-headed rule; a composite
            // segment (`x[input.k]`) cannot, and stringValueOf would throw on it.
            val dottedPath = if (allPlainStringSegments(remaining)) chain.joinToString(".") { stringValueOf(it) } else null
            if (dottedPath != null && ctx.refHeadRegistry[ctx.currentPackage]?.contains(dottedPath) == true) {
                val segments = mapPathSegments(remaining, symbolTable, ctx) ?: return ConstructResult.Ok(Operand.Unrendered(sourceText(wholeRefLocation)))
                return ConstructResult.Ok(Operand.Path(listOf(PathSegment.Field(rootName)) + segments))
            }
        }
        return when (val binding = symbolTable[rootName]) {
            is VarBinding.Iteration -> {
                val segments = mapPathSegments(remaining, symbolTable, ctx) ?: return ConstructResult.Ok(Operand.Unrendered(sourceText(wholeRefLocation)))
                ConstructResult.Ok(Operand.Path(binding.collection.segments + PathSegment.VarIndex(rootName) + segments))
            }
            is VarBinding.Substitution -> {
                val segments = mapPathSegments(remaining, symbolTable, ctx) ?: return ConstructResult.Ok(Operand.Unrendered(sourceText(wholeRefLocation)))
                ConstructResult.Ok(Operand.Path(binding.path.segments + segments))
            }
            // A parameter used as a ref-chain ROOT (`ns.subfield`), spec §14.26 (coverage audit
            // probe 28's remaining half): the tail after the parameter maps through the same
            // mapPathSegments every other reference uses, promoted to Operand.ParameterPath when it
            // resolves cleanly. `remaining` is always non-empty here in practice -- a "ref" term with
            // no fields beyond its root is represented as a bare "var" term instead (mapVarOperand's
            // job) -- but the empty case is handled defensively by falling back to the identical
            // Operand.Parameter a bare use would produce, rather than an empty-tail ParameterPath.
            VarBinding.Parameter -> {
                if (remaining.isEmpty()) return ConstructResult.Ok(Operand.Parameter(rootName))
                val segments = mapPathSegments(remaining, symbolTable, ctx) ?: return ConstructResult.Ok(Operand.Unrendered(sourceText(wholeRefLocation)))
                ConstructResult.Ok(Operand.ParameterPath(rootName, segments))
            }
            VarBinding.NonPath, is VarBinding.Expression, null -> ConstructResult.Ok(Operand.Unrendered(sourceText(wholeRefLocation)))
        }
    }

    /**
     * `<alias>.<partialRule>[...]` or `<partialRule>[...]` (a rule of the current package) with at least
     * one segment after the rule name (none needed when [allowBareRule]: the collection of a `some x in`), as the rule's own document path: `data`, its package, the rule
     * name, then the tail. Null when the root names no such partial rule, the tail is empty, or the tail
     * cannot be mapped (the caller then keeps the whole-chain fallback).
     */
    private fun partialRuleRootedPath(
        rootName: String,
        remaining: List<OpaTerm>,
        symbolTable: Map<String, VarBinding>,
        ctx: MappingContext,
        allowBareRule: Boolean = false,
    ): Operand.Path? {
        val minTail = if (allowBareRule) 0 else 1
        val aliasedPackage = ctx.importAliases[rootName]
        val (packagePath, ruleName, tail) = when {
            aliasedPackage != null && remaining.size > minTail && remaining.first().type == "string" ->
                Triple(aliasedPackage, stringValueOf(remaining.first()), remaining.drop(1))
            aliasedPackage == null && remaining.size >= minTail -> Triple(ctx.currentPackage, rootName, remaining)
            else -> return null
        }
        if (ctx.partialRegistry[packagePath]?.contains(ruleName) != true) return null
        val tailSegments = mapPathSegments(tail, symbolTable, ctx) ?: return null
        val rulePath = (packagePath.split('.') + ruleName).map { PathSegment.Field(it) }
        return Operand.Path(listOf<PathSegment>(PathSegment.Field("data")) + rulePath + tailSegments)
    }

    /**
     * Maps the segments after a ref chain's root. Returns null if a middle-position bracket-index
     * variable (`arr[i]`, spec §6.4 rule 6) has no `some i in ...` binding ([VarBinding.Iteration])
     * in [symbolTable] -- the caller promotes that to `Operand.Unrendered` for the whole path,
     * mirroring how an unbound var-ROOTED path (rule 7) is already handled, rather than emitting a
     * raw, meaningless `VarIndex`. A [VarBinding.Substitution]/[VarBinding.NonPath] name in
     * bracket-index position is deliberately treated the same as unbound -- spec §5's promotion
     * only covers substitution as a bare reference or a chain ROOT, not as an index. A bound
     * middle-position var renders identically to the var-rooted case: PathHumanizer treats every
     * `VarIndex` it sees as "[each x]" unconditionally, because by the time it gets one, this
     * function has already guaranteed it's an iteration variable.
     *
     * A wildcard (`arr[_]`, spec §6.4 rule 5 / review item 6) is detected by its own SOURCE TEXT
     * (`sourceText(term.location) == "_"`), never by `stringValueOf(term)` -- confirmed via a real
     * `opa parse` run that opa renames every bare `_` to a fresh internal variable (`$0`, `$1`, ...)
     * at parse time, so `term.value` is never literally `"_"`. The old `name == "_"` check was
     * never exercised by any real policy (CLAUDE.md's own disclosed "no pack policy uses `[_]`"
     * note) and silently never matched, sending every real wildcard to the `else -> return null`
     * branch -- the whole operand fell back to raw verbatim source instead of humanizing.
     *
     * A nested `ref` term (review item 7) -- a dynamic bracket key that is itself an `input`/`data`
     * path, e.g. `input.user.id` in `data.grants[input.user.id]` -- is resolved through the exact
     * same [mapRefChain] every other reference goes through, never guessed, and promoted to
     * [PathSegment.KeyPath] only when it comes back a clean [Operand.Path]; anything else (unbound,
     * a comprehension) falls back for the WHOLE containing path, same conservative posture as every
     * other segment kind here. Needs [ctx] (not required by any other branch) to make that
     * recursive call.
     */
    private fun mapPathSegments(terms: List<OpaTerm>, symbolTable: Map<String, VarBinding>, ctx: MappingContext): List<PathSegment>? {
        val segments = mutableListOf<PathSegment>()
        for (term in terms) {
            when (term.type) {
                "string" -> {
                    val raw = sourceText(term.location)
                    segments += if (raw.startsWith("\"")) PathSegment.KeyLiteral(stringValueOf(term)) else PathSegment.Field(stringValueOf(term))
                }
                "var" -> {
                    val name = stringValueOf(term)
                    segments += when {
                        sourceText(term.location) == "_" -> PathSegment.AnyIndex
                        symbolTable[name] is VarBinding.Iteration -> PathSegment.VarIndex(name)
                        else -> return null
                    }
                }
                "ref" -> {
                    val nested = mapRefChain(decodeTermList(term.value), symbolTable, ctx, term.location)
                    val nestedPath = (nested as? ConstructResult.Ok)?.operand as? Operand.Path ?: return null
                    segments += PathSegment.KeyPath(nestedPath.segments)
                }
                else -> segments += PathSegment.Field(sourceText(term.location))
            }
        }
        return segments
    }

    private fun mapCollectionLiteral(term: OpaTerm, symbolTable: Map<String, VarBinding>, ctx: MappingContext): ConstructResult {
        val elements = decodeTermList(term.value)
        val allScalar = elements.all { it.type in SCALAR_TERM_TYPES }
        if (elements.size > 5 || !allScalar) return ConstructResult.Ok(Operand.Unrendered(sourceText(term.location)))
        val rendered = elements.joinToString(", ") { el ->
            (mapOperand(el, symbolTable, ctx) as ConstructResult.Ok).let { (it.operand as Operand.Literal).rendered }
        }
        // Spec §14 promotion: records which Rego syntax produced this literal, so the renderer can
        // add faithful delimiters (`[...]`/`{...}`) outside the one frozen bare-list position (a
        // membership test's own collection side) -- see Operand.Literal's own KDoc.
        val collection = if (term.type == "set") LiteralCollection.SET else LiteralCollection.ARRAY
        return ConstructResult.Ok(Operand.Literal(rendered, collection))
    }

    /**
     * A small flat object literal (spec §14 promotion, backlog rank #1 after §14.11) -- the exact
     * same mechanism [mapCollectionLiteral] already uses for a small array/set: a ≤5-pair size cap,
     * every VALUE restricted to [SCALAR_TERM_TYPES] (a nested object/array value stays a documented
     * gap, never guessed), rendered as `{"key": value, ...}` -- valid Rego object-literal syntax,
     * not the array literal's bracket-free convention (which only fits after a "is one of" phrase;
     * a compared whole-object VALUE reads better with its own real braces/colons). Every KEY is also
     * restricted to a plain "string" term -- the overwhelmingly common case (`{"k": v}` syntax); a
     * non-string key (Rego does allow e.g. a numeric key) is unusual enough, and adds enough
     * rendering-format ambiguity, that it's deliberately left unpromoted rather than guessed.
     * Key order is opa's own: confirmed via a real `opa parse` run that opa's AST already lists an
     * object literal's pairs in sorted-by-key order regardless of source order (`{"zebra":1,
     * "apple":2,"mango":3}` parses with pairs in `apple, mango, zebra` order) -- this mapper never
     * re-sorts anything itself, it only renders opa's own already-deterministic order.
     */
    private fun mapObjectLiteral(term: OpaTerm, symbolTable: Map<String, VarBinding>, ctx: MappingContext): ConstructResult {
        val pairs = opaJson.decodeFromJsonElement(ListSerializer(ListSerializer(OpaTerm.serializer())), term.value ?: JsonArray(emptyList()))
            .map { it[0] to it[1] }
        val allFlat = pairs.all { (key, value) -> key.type == "string" && value.type in SCALAR_TERM_TYPES }
        if (pairs.size > 5 || !allFlat) return ConstructResult.Ok(Operand.Unrendered(sourceText(term.location)))
        val rendered = pairs.joinToString(", ") { (key, value) ->
            val valueText = (mapOperand(value, symbolTable, ctx) as ConstructResult.Ok).let { (it.operand as Operand.Literal).rendered }
            "${quoted(stringValueOf(key))}: $valueText"
        }
        return ConstructResult.Ok(Operand.Literal("{$rendered}"))
    }

    /**
     * Operand-position builtins. `count`/`lower`/`upper` (spec §14 promotion) map to
     * [Operand.BuiltinCall] when their single argument itself resolves cleanly -- rendered via
     * [io.explico.render.ExpressionRenderer]'s spec §6.3 templates ("the number of X", "X lowercased",
     * "X uppercased"). `object.get(o, k, d)` (spec §14 backlog) promotes only in the common,
     * unambiguous shape -- `o` a real path and `k` a plain string literal -- so the renderer can
     * extend `o`'s own breadcrumb with `k` as a `PathSegment.KeyLiteral`, exactly like an ordinary
     * bracket-string path segment (`labels["signed-off-by"]`) already renders. A non-string key (a
     * var, a number, a computed expression) has no such extension rule, and guessing one would be
     * exactly the "widen a template to swallow a construct approximately" failure mode spec §14's
     * audit exists to catch -- falls back to `Operand.Unrendered` instead. `time.now_ns()` (spec §14
     * backlog rank #1) promotes too -- it takes no arguments, so there's nothing to resolve; the
     * renderer's template is a fixed phrase. `plus`/`minus`/`mul`/`div`/`rem` (spec §14, next backlog
     * rank #1) -- opa's own arithmetic operator names -- promote unconditionally on arity 2 (they're
     * always binary; opa desugars even unary minus to `minus(0, x)`, confirmed via a real `opa parse`
     * run), with NO restriction on whether an argument is itself nested `Operand.BuiltinCall` or
     * `Operand.Unrendered` -- unlike `=`/`object.get`, arithmetic operators can never introduce a
     * binding, so there's no "fresh variable" ambiguity to guard against, and opa's own parse tree
     * already resolves operator precedence correctly, so rendering it recursively (each nested call
     * rendered via the exact same mechanism) is faithful by construction regardless of depth --
     * [io.explico.render.ExpressionRenderer]'s templates interpolate already-rendered text rather
     * than re-deriving grouping. Anything else (still a documented gap, file header) falls back, as
     * does any promotable builtin above if an argument doesn't resolve -- never a guessed rendering.
     */
    private fun mapCallOperand(term: OpaTerm, symbolTable: Map<String, VarBinding>, ctx: MappingContext): ConstructResult {
        val (name, args) = decodeCallShape(decodeTermList(term.value)) ?: return ConstructResult.Ok(Operand.Unrendered(sourceText(term.location)))
        val mapped = args.map { mapOperand(it, symbolTable, ctx) }
        val unsupported = mapped.filterIsInstance<ConstructResult.Unsupported>().firstOrNull()
        if (unsupported != null) return unsupported
        val operands = mapped.map { (it as ConstructResult.Ok).operand }
        if (name in OPERAND_BUILTINS && args.size == 1) {
            return ConstructResult.Ok(Operand.BuiltinCall(name, operands))
        }
        if (name == "object.get" && operands.size == 3 && operands[0] is Operand.Path && isStringLiteral(operands[1])) {
            return ConstructResult.Ok(Operand.BuiltinCall(name, operands))
        }
        if (name == "time.now_ns" && operands.isEmpty()) {
            return ConstructResult.Ok(Operand.BuiltinCall(name, operands))
        }
        if (name in ARITHMETIC_BUILTINS && operands.size == 2) {
            return ConstructResult.Ok(Operand.BuiltinCall(name, operands))
        }
        if (name == "concat" && args.size == 2) {
            return mapConcatOperand(args[0], args[1], symbolTable, ctx)
        }
        // Same-package user-defined function (coverage audit rank 4). An argument that is itself
        // Operand.Unrendered stays wrapped inside the call (like BuiltinCall), so the footer still
        // counts it; an Unsupported one already returned above.
        resolveFunctionCall(name, args.size, ctx)?.let { (packagePath, functionName) ->
            return ConstructResult.Ok(Operand.FunctionCall(packagePath, functionName, operands))
        }
        return ConstructResult.Ok(Operand.Unrendered(sourceText(term.location)))
    }

    /**
     * `concat(separator, collection)` (spec §14 promotion) -- unlike the uniform checks above, the
     * SECOND argument needs its own expansion rather than the generic single-pass `args.map {
     * mapOperand }` at the top of [mapCallOperand]: a literal array/set (`concat("/", [a, b])`)
     * explodes into one [Operand] per element, joined at render time; anything else (a path or
     * bound var referencing an existing collection, e.g. `concat("/", input.parts)`) maps as a
     * SINGLE whole-collection operand instead -- both are genuinely common real-world shapes, and
     * neither requires guessing: every element (or the whole collection reference) still goes
     * through the same [mapOperand] every other operand position uses. The result is stored as
     * `Operand.BuiltinCall("concat", listOf(separator) + collectionOperands)` -- the renderer knows
     * by convention that args[0] is the separator and the rest are the joined items (one item for
     * the whole-collection-reference case, N items for the exploded-literal case).
     */
    private fun mapConcatOperand(separatorTerm: OpaTerm, collectionTerm: OpaTerm, symbolTable: Map<String, VarBinding>, ctx: MappingContext): ConstructResult {
        val collectionElementTerms = if (collectionTerm.type == "array" || collectionTerm.type == "set") {
            decodeTermList(collectionTerm.value)
        } else {
            listOf(collectionTerm)
        }
        val results = (listOf(separatorTerm) + collectionElementTerms).map { mapOperand(it, symbolTable, ctx) }
        val unsupported = results.filterIsInstance<ConstructResult.Unsupported>().firstOrNull()
        if (unsupported != null) return unsupported
        return ConstructResult.Ok(Operand.BuiltinCall("concat", results.map { (it as ConstructResult.Ok).operand }))
    }

    /** An `Operand.Literal` produced from a "string" term is always quote-wrapped by [quoted] -- distinguishes a string key from a numeric/boolean one, which [mapOperand] renders unquoted. */
    private fun isStringLiteral(operand: Operand): Boolean = operand is Operand.Literal && operand.rendered.startsWith("\"")

    // --- producesValue (spec §5's minimal placeholder formatting, approved for this session) ---

    /**
     * The raw message template for §6.7 body attribution: a string literal verbatim, or an
     * sprintf format string with `%v`/`%s` left untouched. Unlike [renderProducesValue], this
     * never fails on an unhumanisable placeholder -- it doesn't try to render the arguments at
     * all, just identify the template's literal/wildcard shape.
     */
    private fun computeMessageTemplate(valueTerm: OpaTerm): String? {
        if (valueTerm.type == "string") return stringValueOf(valueTerm)
        if (valueTerm.type != "call") return null
        val (name, args) = decodeCallShape(decodeTermList(valueTerm.value)) ?: return null
        if (name != "sprintf" || args.size != 2) return null
        return args[0].takeIf { it.type == "string" }?.let { stringValueOf(it) }
    }

    private fun renderProducesValue(valueTerm: OpaTerm, symbolTable: Map<String, VarBinding>): String? {
        if (valueTerm.type == "string") return stringValueOf(valueTerm)
        if (valueTerm.type != "call") return null
        val (name, args) = decodeCallShape(decodeTermList(valueTerm.value)) ?: return null
        if (name != "sprintf" || args.size != 2) return null
        val format = args[0].takeIf { it.type == "string" }?.let { stringValueOf(it) } ?: return null
        val placeholderArgs = decodeTermList(args[1].value)
        val placeholders = placeholderArgs.map { renderPlaceholder(it, symbolTable) ?: return null }
        // The display message is produced ONLY when every verb is understood and verbs line up
        // with arguments exactly. Found by benchmarking real-world Rego: a verb other than %v/%s
        // (`%d`, `%q`) used to be copied as text WITHOUT consuming an argument, so every later %v
        // took the WRONG argument (the card read "%d of [total]" for a message that really reads
        // "5 of x"), and a format with more verbs than arguments crashed. Anything not understood
        // is null -- no Produces line -- never a misaligned one.
        var index = 0
        val result = StringBuilder()
        var i = 0
        while (i < format.length) {
            val c = format[i]
            if (c != '%') {
                result.append(c)
                i += 1
                continue
            }
            when (format.getOrNull(i + 1)) {
                'v', 's' -> result.append(placeholders.getOrNull(index++) ?: return null)
                '%' -> result.append('%')
                else -> return null
            }
            i += 2
        }
        if (index != placeholders.size) return null
        return result.toString()
    }

    /**
     * A plain `input.`-rooted field chain, or a var-rooted one whose root is a KNOWN binding
     * (review item 9's promotion), humanises into a bracket phrase; anything else returns null
     * rather than a guessed format. `input` itself is dropped (boilerplate, spec §5's original
     * example); a bound variable's own name is NOT dropped -- unlike "input" it's real, meaningful
     * content (`container` in `container.name`) -- so it leads the word list the same way a field
     * name would. Only a plain (non-bracket) string field chain after the root qualifies: a
     * KeyLiteral/VarIndex/AnyIndex segment, or an unbound/`NonPath` root, still has no established
     * bracket-format translation and stays null, exactly as before this promotion. This
     * placeholder convention (space-joined words in brackets) remains deliberately different from
     * PathHumanizer's own `▸`-joined breadcrumb style (§6.4) -- extending ONE established
     * convention to a wider root set, not inventing a second one.
     */
    private fun renderPlaceholder(term: OpaTerm, symbolTable: Map<String, VarBinding>): String? {
        if (term.type != "ref") return null
        val chain = decodeTermList(term.value)
        val root = chain.firstOrNull() ?: return null
        val rootName = stringValueOf(root)
        val fieldNames = chain.drop(1)
        if (fieldNames.any { it.type != "string" || sourceText(it.location).startsWith("\"") }) return null
        val rootWords = when {
            rootName == "input" -> emptyList()
            symbolTable[rootName].let { it != null && it !is VarBinding.NonPath } -> PathHumanizer.wordsOf(rootName)
            else -> return null
        }
        val words = rootWords + fieldNames.flatMap { PathHumanizer.wordsOf(stringValueOf(it)) }
        if (words.isEmpty()) return null
        return "[${words.joinToString(" ")}]"
    }

    // --- shared decoding helpers ---

    private fun quoted(value: String): String = "\"$value\""

    private fun stringValueOf(term: OpaTerm): String = term.value?.jsonPrimitive?.content ?: ""

    private fun decodeTermList(element: JsonElement?): List<OpaTerm> =
        if (element == null) emptyList() else opaJson.decodeFromJsonElement(ListSerializer(OpaTerm.serializer()), element)

    /** A call-shaped term list is `[operatorRef, ...args]`; the operator's own ref chain joins to a dotted name (`equal`, `internal.member_2`). */
    private fun decodeCallShape(terms: List<OpaTerm>): Pair<String, List<OpaTerm>>? {
        val operatorTerm = terms.firstOrNull() ?: return null
        if (operatorTerm.type != "ref") return null
        val name = decodeTermList(operatorTerm.value).joinToString(".") { stringValueOf(it) }
        return name to terms.drop(1)
    }

    private fun sourceText(location: io.explico.opa.OpaLocation?): String {
        val encoded = location?.text ?: return ""
        return String(Base64.getDecoder().decode(encoded))
    }
}

private val COMPARISON_OPERATORS = mapOf(
    "equal" to Operator.EQ,
    "neq" to Operator.NEQ,
    "gt" to Operator.GT,
    "gte" to Operator.GTE,
    "lt" to Operator.LT,
    "lte" to Operator.LTE,
)

private data class MappingContext(
    val currentPackage: String,
    val importAliases: Map<String, String>,
    val registry: Map<String, Set<String>>,
    val partialRegistry: Map<String, Set<String>>,
    val refHeadRegistry: Map<String, Set<String>>,
    val functionRegistry: Map<String, Map<String, List<String>>>,
    val sourceFile: String,
)
