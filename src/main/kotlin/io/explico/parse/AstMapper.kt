/**
 * Maps `opa parse` DTOs ([io.explico.opa.OpaModule]) to the domain model (spec §5).
 *
 * `count`/`lower`/`upper` in operand position map to [io.explico.model.Operand.BuiltinCall]
 * (spec §14 promotion) when their single argument itself resolves cleanly; `object.get(o, k, d)`
 * promotes too, but only when `o` is a real path and `k` a plain string literal (see
 * [mapCallOperand]'s own KDoc for why a non-string key isn't promoted); `time.now_ns()` promotes
 * unconditionally, since it takes no arguments to resolve. Anything else (still a documented gap)
 * maps to `Operand.Unrendered`, honestly reflecting that we can't render it rather than guessing a
 * phrase. Note that in the acceptance pack, `count({a | ...})
 * == 0` wraps a comprehension, which forces the whole condition to fall back regardless of the
 * builtin promotion above -- see [ConstructResult.Unsupported].
 *
 * Metadata attachment (`opa inspect` -> `RuleMetadata`, see [buildMetadataIndex])
 * matches by `opa inspect`'s own `path` field rather than the file/row-proximity
 * spec §5 literally describes -- opa has already resolved which rule an
 * annotation belongs to. `default` declarations are still not handled: no
 * policy in the acceptance pack exercises one, so `RuleGroup.default` is
 * always null.
 */
package io.explico.parse

import io.explico.model.Condition
import io.explico.model.Operand
import io.explico.model.Operator
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

/** A condition-position builtin call recognised per spec §6.3's table. */
private val CONDITION_BUILTINS = setOf("startswith", "endswith", "contains", "regex.match", "glob.match")

/** An operand-position builtin call recognised per spec §6.3/§14 -- each takes exactly one argument. */
private val OPERAND_BUILTINS = setOf("count", "lower", "upper")

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
 */
private sealed interface VarBinding {
    data class Iteration(val collection: Operand.Path) : VarBinding
    data class Substitution(val path: Operand.Path) : VarBinding
    object NonPath : VarBinding
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
        val metadataIndex = buildMetadataIndex(inspectResult)
        val packages = files
            .groupBy { packagePath(it.module) }
            .map { (path, filesInPackage) ->
                val rules = filesInPackage
                    .flatMap { mapRuleGroups(it, registry, partialRegistry, metadataIndex) }
                    .sortedBy { it.name }
                PolicyPackage(
                    path = path,
                    rules = rules,
                    sourceFiles = filesInPackage.map { it.sourceFile }.sorted(),
                )
            }
            .sortedBy { it.path }
        return PolicySet(packages)
    }

    private fun buildMetadataIndex(inspectResult: OpaInspectResult?): Map<Pair<String, String>, RuleMetadata> =
        (inspectResult?.annotations ?: emptyList())
            .filter { it.annotations.scope != "package" }
            .mapNotNull { entry ->
                val segments = entry.path.drop(1).map { stringValueOf(it) }
                if (segments.size < 2) return@mapNotNull null
                val key = segments.dropLast(1).joinToString(".") to segments.last()
                key to RuleMetadata(
                    title = entry.annotations.title,
                    description = entry.annotations.description,
                    controlId = entry.annotations.custom?.controlId,
                    frameworks = entry.annotations.custom?.frameworks ?: emptyList(),
                )
            }.toMap()

    private fun packagePath(module: OpaModule): String =
        module.pkg.path.drop(1).joinToString(".") { stringValueOf(it) }

    /** Import alias -> fully-qualified package path, e.g. "exemptions" -> "release.exemptions". Non-`data.*` imports (e.g. `rego.v1`) are ignored. */
    private fun resolveImportAliases(module: OpaModule): Map<String, String> =
        module.imports.mapNotNull { import ->
            val chain = decodeTermList(import.path.value)
            if (chain.isEmpty() || stringValueOf(chain.first()) != "data") return@mapNotNull null
            val packageSegments = chain.drop(1)
            val alias = stringValueOf(packageSegments.last())
            alias to packageSegments.joinToString(".") { stringValueOf(it) }
        }.toMap()

    private fun buildRuleRegistry(files: List<ParsedFile>): Map<String, Set<String>> =
        files.groupBy({ packagePath(it.module) }, { it.module })
            .mapValues { (_, modules) ->
                modules.flatMap { it.rules }.mapNotNull { it.head.name }.toSet()
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
                modules.flatMap { it.rules }.filter { it.head.key != null }.mapNotNull { it.head.name }.toSet()
            }

    private fun mapRuleGroups(
        file: ParsedFile,
        registry: Map<String, Set<String>>,
        partialRegistry: Map<String, Set<String>>,
        metadataIndex: Map<Pair<String, String>, RuleMetadata>,
    ): List<RuleGroup> {
        val currentPackage = packagePath(file.module)
        val ctx = MappingContext(
            currentPackage = currentPackage,
            importAliases = resolveImportAliases(file.module),
            registry = registry,
            partialRegistry = partialRegistry,
            sourceFile = file.sourceFile,
        )
        return file.module.rules
            .filter { it.head.name != null }
            .groupBy { it.head.name!! }
            .map { (name, rules) ->
                val orderedRules = rules.sortedBy { it.location?.row ?: 0 }
                RuleGroup(
                    name = name,
                    metadata = metadataIndex[currentPackage to name],
                    default = null,
                    bodies = orderedRules.map { mapBody(it, ctx) },
                )
            }
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
        val messageVar = rule.head.key?.takeIf { it.type == "var" }?.let { stringValueOf(it) }
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
                val plainPath = plainPathOperandOrNull(valueTerm, symbolTable)
                symbolTable[targetVar] = if (plainPath != null) VarBinding.Substitution(plainPath) else VarBinding.NonPath
                if (plainPath == null) conditions += Condition.Unrendered(sourceText(expr.location), "function-call")
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
        )
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
    private fun plainPathOperandOrNull(term: OpaTerm, symbolTable: Map<String, VarBinding>): Operand.Path? {
        if (term.type != "ref") return null
        val result = mapRefChain(decodeTermList(term.value), symbolTable, term.location)
        return (result as? ConstructResult.Ok)?.operand as? Operand.Path
    }

    private fun mapCondition(expr: OpaExpr, symbolTable: MutableMap<String, VarBinding>, ctx: MappingContext): Condition {
        return when (val terms = expr.terms) {
            is JsonArray -> mapCallShapedCondition(expr, decodeTermList(terms), symbolTable, ctx)
            is JsonObject -> when {
                "symbols" in terms -> mapSomeIn(expr, terms, symbolTable)
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
            return buildComparisonLike(expr, args, symbolTable) { left, right -> Condition.Comparison(left, comparisonOp, right) }
        }
        if (name == "internal.member_2" && args.size == 2) {
            return buildComparisonLike(expr, args, symbolTable) { member, collection ->
                Condition.Membership(expr.negated, member, collection)
            }
        }
        if (name in CONDITION_BUILTINS) {
            val mapped = args.map { mapOperand(it, symbolTable) }
            val unsupported = mapped.filterIsInstance<ConstructResult.Unsupported>().firstOrNull()
            if (unsupported != null) return Condition.Unrendered(sourceText(expr.location), unsupported.reason)
            val operands = mapped.filterIsInstance<ConstructResult.Ok>().map { it.operand }
            return Condition.BuiltinCall(name, operands, expr.negated)
        }
        return Condition.Unrendered(sourceText(expr.location), "function-call")
    }

    private inline fun buildComparisonLike(
        expr: OpaExpr,
        args: List<OpaTerm>,
        symbolTable: Map<String, VarBinding>,
        build: (Operand, Operand) -> Condition,
    ): Condition {
        val left = mapOperand(args[0], symbolTable)
        val right = mapOperand(args[1], symbolTable)
        val unsupported = listOf(left, right).filterIsInstance<ConstructResult.Unsupported>().firstOrNull()
        if (unsupported != null) return Condition.Unrendered(sourceText(expr.location), unsupported.reason)
        return build((left as ConstructResult.Ok).operand, (right as ConstructResult.Ok).operand)
    }

    private fun mapSomeIn(expr: OpaExpr, terms: JsonObject, symbolTable: MutableMap<String, VarBinding>): Condition {
        val symbols = opaJson.decodeFromJsonElement(ListSerializer(OpaTerm.serializer()), terms.getValue("symbols"))
        val callTerm = symbols.singleOrNull() ?: return Condition.Unrendered(sourceText(expr.location), "unclassified")
        // A declare-only `some x` (no `in`) has exactly one symbol too, but its value is a bare var
        // term, not a call-shaped `internal.member_2` term -- decodeTermList would otherwise crash
        // trying to deserialize a JsonPrimitive as a JsonArray (spec §14 finding: this took down the
        // whole render, not just this one condition, before the guard existed).
        if (callTerm.type != "call") return Condition.Unrendered(sourceText(expr.location), "unclassified")
        val (name, args) = decodeCallShape(decodeTermList(callTerm.value)) ?: return Condition.Unrendered(sourceText(expr.location), "unclassified")
        if (name != "internal.member_2" || args.size != 2 || args[0].type != "var") {
            return Condition.Unrendered(sourceText(expr.location), "unclassified")
        }
        val variable = stringValueOf(args[0])
        val collection = mapOperand(args[1], symbolTable)
        val collectionPath = (collection as? ConstructResult.Ok)?.operand as? Operand.Path
            ?: return Condition.Unrendered(sourceText(expr.location), (collection as? ConstructResult.Unsupported)?.reason ?: "unclassified")
        symbolTable[variable] = VarBinding.Iteration(collectionPath)
        return Condition.SomeIn(variable, collectionPath)
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
        if (aliasedPackage != null) {
            val ruleName = chain.drop(1).joinToString(".") { stringValueOf(it) }
            return ruleReferenceIfKnown(aliasedPackage, ruleName, ctx, expr.negated)
                ?: Condition.Unrendered(sourceText(expr.location), fallbackReasonFor(aliasedPackage, ruleName, ctx))
        }
        if (root == "data" && chain.size > 1) {
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
        val operand = mapRefChain(chain, symbolTable, term.location)
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

    // --- Operands ---

    private fun mapOperand(term: OpaTerm, symbolTable: Map<String, VarBinding>): ConstructResult = when (term.type) {
        "string" -> ConstructResult.Ok(Operand.Literal(quoted(stringValueOf(term))))
        "number" -> ConstructResult.Ok(Operand.Literal(term.value?.jsonPrimitive?.content ?: "0"))
        "boolean" -> ConstructResult.Ok(Operand.Literal(term.value?.jsonPrimitive?.content ?: "false"))
        "ref" -> mapRefChain(decodeTermList(term.value), symbolTable, term.location)
        "var" -> mapVarOperand(term, symbolTable)
        "array", "set" -> mapCollectionLiteral(term, symbolTable)
        "call" -> mapCallOperand(term, symbolTable)
        "setcomprehension", "arraycomprehension", "objectcomprehension" -> ConstructResult.Unsupported("comprehension")
        else -> ConstructResult.Unsupported("unclassified")
    }

    /** A bare local-variable reference (spec §6.4 rule 7 / spec §5 promotion): resolved through [VarBinding] -- see its own KDoc for what each kind means. A name absent from [symbolTable] entirely is genuinely unknown/unbound, never seen by any recognised construct, so it stays the generic `Operand.Unrendered` fallback. */
    private fun mapVarOperand(term: OpaTerm, symbolTable: Map<String, VarBinding>): ConstructResult =
        when (val binding = symbolTable[stringValueOf(term)]) {
            is VarBinding.Iteration -> ConstructResult.Ok(Operand.Path(binding.collection.segments + PathSegment.VarIndex(stringValueOf(term))))
            is VarBinding.Substitution -> ConstructResult.Ok(Operand.Path(binding.path.segments))
            VarBinding.NonPath -> ConstructResult.Ok(Operand.Variable(stringValueOf(term)))
            null -> ConstructResult.Ok(Operand.Unrendered(sourceText(term.location)))
        }

    /**
     * Builds a Path from a decoded ref chain: `input`/`data` root, or a var-rooted path resolved via
     * [symbolTable] (spec §6.4 rule 7 for [VarBinding.Iteration]; spec §5's promotion for
     * [VarBinding.Substitution], which continues the chain with no extra segment since the
     * variable stands for the exact value, not one element of a collection). [wholeRefLocation] is
     * used only for the unbound-variable fallback, so its source text covers the whole reference,
     * not just the root token. A [VarBinding.NonPath] root (assigned, but not to a plain path)
     * falls back to `Operand.Unrendered` rather than `Operand.Variable` -- spec §5 only specifies
     * `Operand.Variable` for a *bare* use of such a variable, not a field chained off it.
     */
    private fun mapRefChain(chain: List<OpaTerm>, symbolTable: Map<String, VarBinding>, wholeRefLocation: io.explico.opa.OpaLocation?): ConstructResult {
        if (chain.isEmpty()) return ConstructResult.Ok(Operand.Unrendered(""))
        val root = chain.first()
        val rootName = stringValueOf(root)
        val remaining = chain.drop(1)
        if (rootName == "input" || rootName == "data") {
            val segments = mapPathSegments(remaining, symbolTable) ?: return ConstructResult.Ok(Operand.Unrendered(sourceText(wholeRefLocation)))
            return ConstructResult.Ok(Operand.Path(listOf(PathSegment.Field(rootName)) + segments))
        }
        return when (val binding = symbolTable[rootName]) {
            is VarBinding.Iteration -> {
                val segments = mapPathSegments(remaining, symbolTable) ?: return ConstructResult.Ok(Operand.Unrendered(sourceText(wholeRefLocation)))
                ConstructResult.Ok(Operand.Path(binding.collection.segments + PathSegment.VarIndex(rootName) + segments))
            }
            is VarBinding.Substitution -> {
                val segments = mapPathSegments(remaining, symbolTable) ?: return ConstructResult.Ok(Operand.Unrendered(sourceText(wholeRefLocation)))
                ConstructResult.Ok(Operand.Path(binding.path.segments + segments))
            }
            VarBinding.NonPath, null -> ConstructResult.Ok(Operand.Unrendered(sourceText(wholeRefLocation)))
        }
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
     */
    private fun mapPathSegments(terms: List<OpaTerm>, symbolTable: Map<String, VarBinding>): List<PathSegment>? {
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
                        name == "_" -> PathSegment.AnyIndex
                        symbolTable[name] is VarBinding.Iteration -> PathSegment.VarIndex(name)
                        else -> return null
                    }
                }
                else -> segments += PathSegment.Field(sourceText(term.location))
            }
        }
        return segments
    }

    private fun mapCollectionLiteral(term: OpaTerm, symbolTable: Map<String, VarBinding>): ConstructResult {
        val elements = decodeTermList(term.value)
        val allScalar = elements.all { it.type in setOf("string", "number", "boolean") }
        if (elements.size > 5 || !allScalar) return ConstructResult.Ok(Operand.Unrendered(sourceText(term.location)))
        val rendered = elements.joinToString(", ") { el ->
            (mapOperand(el, symbolTable) as ConstructResult.Ok).let { (it.operand as Operand.Literal).rendered }
        }
        return ConstructResult.Ok(Operand.Literal(rendered))
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
     * renderer's template is a fixed phrase. Anything else (still a documented gap, file header)
     * falls back, as does any promotable builtin above if an argument doesn't resolve -- never a
     * guessed rendering.
     */
    private fun mapCallOperand(term: OpaTerm, symbolTable: Map<String, VarBinding>): ConstructResult {
        val (name, args) = decodeCallShape(decodeTermList(term.value)) ?: return ConstructResult.Ok(Operand.Unrendered(sourceText(term.location)))
        val mapped = args.map { mapOperand(it, symbolTable) }
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
        return ConstructResult.Ok(Operand.Unrendered(sourceText(term.location)))
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
        var index = 0
        val result = StringBuilder()
        var i = 0
        while (i < format.length) {
            val c = format[i]
            if (c == '%' && i + 1 < format.length && (format[i + 1] == 'v' || format[i + 1] == 's')) {
                result.append(placeholders[index++])
                i += 2
            } else {
                result.append(c)
                i += 1
            }
        }
        return result.toString()
    }

    /**
     * Only a plain `input.`-rooted field chain gets humanised into a bracket phrase. A var-rooted,
     * key-literal, or any-index chain returns null rather than a guessed bracket format -- spec §5
     * (amended session 2) only gives an example for the plain-chain case, and this placeholder
     * convention (space-joined words in brackets) is deliberately different from PathHumanizer's
     * breadcrumb style (§6.4), so there's no format to translate a VarIndex/KeyLiteral segment into
     * without inventing one.
     */
    private fun renderPlaceholder(term: OpaTerm, symbolTable: Map<String, VarBinding>): String? {
        if (term.type != "ref") return null
        val chain = decodeTermList(term.value)
        if (chain.isEmpty() || stringValueOf(chain.first()) != "input") return null
        val fieldNames = chain.drop(1)
        if (fieldNames.any { it.type != "string" || sourceText(it.location).startsWith("\"") }) return null
        val words = fieldNames.flatMap { PathHumanizer.wordsOf(stringValueOf(it)) }
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
    val sourceFile: String,
)
