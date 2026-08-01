/**
 * Maps `opa parse` DTOs ([io.explico.opa.OpaModule]) to the domain model (spec §5).
 *
 * Known gap (flagged, not resolved here): spec's `Operand` sealed interface has no
 * variant for an operand-position builtin call (`count`, `lower`, `upper`,
 * `object.get`, `time.now_ns`) — only `Path`/`Literal`/`Variable`/`Unrendered`. Until
 * the model grows one, such calls map to `Operand.Unrendered`, honestly reflecting
 * that we can't render them yet rather than guessing a phrase. In this pack every
 * such call (`count({a | ...}) == 0`) wraps a comprehension anyway, which forces the
 * whole condition to fall back regardless — see [ConstructResult.Unsupported].
 *
 * Metadata attachment (`opa inspect`, RuleMetadata) and `default` declarations are
 * deliberately not handled yet: no policy in the acceptance pack exercises a
 * `default` rule, and metadata attachment is a separate, later milestone (§11
 * suggests it alongside anchors/cross-references). `RuleGroup.metadata` and
 * `.default` are always null from this mapper for now.
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
import io.explico.model.SourceRef
import io.explico.opa.OpaExpr
import io.explico.opa.OpaModule
import io.explico.opa.OpaRule
import io.explico.opa.OpaTerm
import io.explico.opa.opaJson
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

/** Result of mapping a term into an operand: either a usable [Operand], or a marker that a fundamentally unsupported Rego construct (comprehension/every) was found underneath it and must promote the whole condition to [Condition.Unrendered]. */
private sealed interface ConstructResult {
    data class Ok(val operand: Operand) : ConstructResult
    data class Unsupported(val reason: String) : ConstructResult
}

internal object AstMapper {

    /** Maps every parsed file into a [PolicySet], grouping same-named rules and sorting per spec §2's determinism rule. */
    fun mapPolicySet(files: List<ParsedFile>): PolicySet {
        val registry = buildRuleRegistry(files)
        val packages = files
            .groupBy { packagePath(it.module) }
            .map { (path, filesInPackage) ->
                val rules = filesInPackage
                    .flatMap { mapRuleGroups(it, registry) }
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

    private fun mapRuleGroups(file: ParsedFile, registry: Map<String, Set<String>>): List<RuleGroup> {
        val ctx = MappingContext(
            currentPackage = packagePath(file.module),
            importAliases = resolveImportAliases(file.module),
            registry = registry,
            sourceFile = file.sourceFile,
        )
        return file.module.rules
            .filter { it.head.name != null }
            .groupBy { it.head.name!! }
            .map { (name, rules) ->
                val orderedRules = rules.sortedBy { it.location?.row ?: 0 }
                RuleGroup(
                    name = name,
                    metadata = null,
                    default = null,
                    bodies = orderedRules.map { mapBody(it, ctx) },
                )
            }
    }

    private fun mapBody(rule: OpaRule, ctx: MappingContext): RuleBody {
        val symbolTable = mutableMapOf<String, Operand.Path>()
        val messageVar = rule.head.key?.takeIf { it.type == "var" }?.let { stringValueOf(it) }
        var producesValue: String? = null
        val conditions = mutableListOf<Condition>()

        for (expr in rule.body) {
            val assignedMessage = messageVar?.let { matchMessageAssignment(expr, it) }
            if (assignedMessage != null) {
                producesValue = renderProducesValue(assignedMessage, symbolTable)
                continue
            }
            conditions += mapCondition(expr, symbolTable, ctx)
        }

        return RuleBody(
            conditions = conditions,
            producesValue = producesValue,
            sourceLocation = SourceRef(ctx.sourceFile, rule.location?.row ?: 0),
        )
    }

    /** If [expr] is `<messageVar> := <value>`, returns the value term; else null. */
    private fun matchMessageAssignment(expr: OpaExpr, messageVar: String): OpaTerm? {
        val terms = expr.terms as? JsonArray ?: return null
        if (terms.size != 3) return null
        val list = decodeTermList(terms)
        val (name, args) = decodeCallShape(list) ?: return null
        if (name != "assign" || args.size != 2) return null
        val target = args[0]
        if (target.type != "var" || stringValueOf(target) != messageVar) return null
        return args[1]
    }

    private fun mapCondition(expr: OpaExpr, symbolTable: MutableMap<String, Operand.Path>, ctx: MappingContext): Condition {
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
        symbolTable: Map<String, Operand.Path>,
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
        symbolTable: Map<String, Operand.Path>,
        build: (Operand, Operand) -> Condition,
    ): Condition {
        val left = mapOperand(args[0], symbolTable)
        val right = mapOperand(args[1], symbolTable)
        val unsupported = listOf(left, right).filterIsInstance<ConstructResult.Unsupported>().firstOrNull()
        if (unsupported != null) return Condition.Unrendered(sourceText(expr.location), unsupported.reason)
        return build((left as ConstructResult.Ok).operand, (right as ConstructResult.Ok).operand)
    }

    private fun mapSomeIn(expr: OpaExpr, terms: JsonObject, symbolTable: MutableMap<String, Operand.Path>): Condition {
        val symbols = opaJson.decodeFromJsonElement(ListSerializer(OpaTerm.serializer()), terms.getValue("symbols"))
        val callTerm = symbols.singleOrNull() ?: return Condition.Unrendered(sourceText(expr.location), "unclassified")
        val (name, args) = decodeCallShape(decodeTermList(callTerm.value)) ?: return Condition.Unrendered(sourceText(expr.location), "unclassified")
        if (name != "internal.member_2" || args.size != 2 || args[0].type != "var") {
            return Condition.Unrendered(sourceText(expr.location), "unclassified")
        }
        val variable = stringValueOf(args[0])
        val collection = mapOperand(args[1], symbolTable)
        val collectionPath = (collection as? ConstructResult.Ok)?.operand as? Operand.Path
            ?: return Condition.Unrendered(sourceText(expr.location), (collection as? ConstructResult.Unsupported)?.reason ?: "unclassified")
        symbolTable[variable] = collectionPath
        return Condition.SomeIn(variable, collectionPath)
    }

    private fun mapSingleTermCondition(
        expr: OpaExpr,
        term: OpaTerm,
        symbolTable: Map<String, Operand.Path>,
        ctx: MappingContext,
    ): Condition {
        if (term.type == "var") {
            val name = stringValueOf(term)
            return ruleReferenceIfKnown(ctx.currentPackage, name, ctx, expr.negated)
                ?: Condition.Unrendered(sourceText(expr.location), "unclassified")
        }
        if (term.type != "ref") return Condition.Unrendered(sourceText(expr.location), "unclassified")

        val chain = decodeTermList(term.value)
        if (chain.isEmpty()) return Condition.Unrendered(sourceText(expr.location), "unclassified")
        val root = stringValueOf(chain.first())

        val aliasedPackage = ctx.importAliases[root]
        if (aliasedPackage != null) {
            val ruleName = chain.drop(1).joinToString(".") { stringValueOf(it) }
            return ruleReferenceIfKnown(aliasedPackage, ruleName, ctx, expr.negated)
                ?: Condition.Unrendered(sourceText(expr.location), "unclassified")
        }
        if (root == "data" && chain.size > 1) {
            val candidatePackage = chain.drop(1).dropLast(1).joinToString(".") { stringValueOf(it) }
            val candidateRule = stringValueOf(chain.last())
            val reference = ruleReferenceIfKnown(candidatePackage, candidateRule, ctx, expr.negated)
            if (reference != null) return reference
        }
        val operand = mapRefChain(chain, symbolTable, term.location)
        return when (operand) {
            is ConstructResult.Ok -> Condition.Truthy(operand.operand, expr.negated)
            is ConstructResult.Unsupported -> Condition.Unrendered(sourceText(expr.location), operand.reason)
        }
    }

    private fun ruleReferenceIfKnown(packagePath: String, ruleName: String, ctx: MappingContext, negated: Boolean): Condition.RuleReference? =
        if (ctx.registry[packagePath]?.contains(ruleName) == true) Condition.RuleReference(packagePath, ruleName, negated) else null

    // --- Operands ---

    private fun mapOperand(term: OpaTerm, symbolTable: Map<String, Operand.Path>): ConstructResult = when (term.type) {
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

    private fun mapVarOperand(term: OpaTerm, symbolTable: Map<String, Operand.Path>): ConstructResult {
        val name = stringValueOf(term)
        val bound = symbolTable[name]
        return if (bound != null) {
            ConstructResult.Ok(Operand.Path(bound.segments + PathSegment.VarIndex(name)))
        } else {
            ConstructResult.Ok(Operand.Unrendered(sourceText(term.location)))
        }
    }

    /** Builds a Path from a decoded ref chain: `input`/`data` root, or a var-rooted path resolved via [symbolTable] (spec §6.4 rule 7). [wholeRefLocation] is used only for the unbound-variable fallback, so its source text covers the whole reference, not just the root token. */
    private fun mapRefChain(chain: List<OpaTerm>, symbolTable: Map<String, Operand.Path>, wholeRefLocation: io.explico.opa.OpaLocation?): ConstructResult {
        if (chain.isEmpty()) return ConstructResult.Ok(Operand.Unrendered(""))
        val root = chain.first()
        val rootName = stringValueOf(root)
        val remaining = chain.drop(1)
        return when {
            rootName == "input" || rootName == "data" ->
                ConstructResult.Ok(Operand.Path(listOf(PathSegment.Field(rootName)) + mapPathSegments(remaining)))
            symbolTable.containsKey(rootName) ->
                ConstructResult.Ok(Operand.Path(symbolTable.getValue(rootName).segments + PathSegment.VarIndex(rootName) + mapPathSegments(remaining)))
            else -> ConstructResult.Ok(Operand.Unrendered(sourceText(wholeRefLocation)))
        }
    }

    private fun mapPathSegments(terms: List<OpaTerm>): List<PathSegment> = terms.map { term ->
        when (term.type) {
            "string" -> {
                val raw = sourceText(term.location)
                if (raw.startsWith("\"")) PathSegment.KeyLiteral(stringValueOf(term)) else PathSegment.Field(stringValueOf(term))
            }
            "var" -> if (stringValueOf(term) == "_") PathSegment.AnyIndex else PathSegment.VarIndex(stringValueOf(term))
            else -> PathSegment.Field(sourceText(term.location))
        }
    }

    private fun mapCollectionLiteral(term: OpaTerm, symbolTable: Map<String, Operand.Path>): ConstructResult {
        val elements = decodeTermList(term.value)
        val allScalar = elements.all { it.type in setOf("string", "number", "boolean") }
        if (elements.size > 5 || !allScalar) return ConstructResult.Ok(Operand.Unrendered(sourceText(term.location)))
        val rendered = elements.joinToString(", ") { el ->
            (mapOperand(el, symbolTable) as ConstructResult.Ok).let { (it.operand as Operand.Literal).rendered }
        }
        return ConstructResult.Ok(Operand.Literal(rendered))
    }

    /** Operand-position builtins (count/lower/upper/object.get/time.now_ns) have no [Operand] variant yet (see file header) -- always [Operand.Unrendered], unless an argument contains a fundamentally unsupported construct, which propagates instead. */
    private fun mapCallOperand(term: OpaTerm, symbolTable: Map<String, Operand.Path>): ConstructResult {
        val (_, args) = decodeCallShape(decodeTermList(term.value)) ?: return ConstructResult.Ok(Operand.Unrendered(sourceText(term.location)))
        val unsupported = args.map { mapOperand(it, symbolTable) }.filterIsInstance<ConstructResult.Unsupported>().firstOrNull()
        return unsupported ?: ConstructResult.Ok(Operand.Unrendered(sourceText(term.location)))
    }

    // --- producesValue (spec §5's minimal placeholder formatting, approved for this session) ---

    private fun renderProducesValue(valueTerm: OpaTerm, symbolTable: Map<String, Operand.Path>): String? {
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

    /** Only a plain `input.`-rooted field chain gets humanised into a bracket phrase; anything else (var-rooted, key-literal, any-index) is deferred to when PathHumanizer exists. */
    private fun renderPlaceholder(term: OpaTerm, symbolTable: Map<String, Operand.Path>): String? {
        if (term.type != "ref") return null
        val chain = decodeTermList(term.value)
        if (chain.isEmpty() || stringValueOf(chain.first()) != "input") return null
        val fieldNames = chain.drop(1)
        if (fieldNames.any { it.type != "string" || sourceText(it.location).startsWith("\"") }) return null
        val words = fieldNames.flatMap { splitWords(stringValueOf(it)) }
        return "[${words.joinToString(" ")}]"
    }

    /** Splits a field name on camelCase/snake_case/kebab-case boundaries and lowercases it, per spec §6.4 rule 2 -- the one piece of that rule needed to fulfil §5's producesValue mapping this session. */
    private fun splitWords(name: String): List<String> {
        val withBoundaries = name
            .replace(Regex("[_-]"), " ")
            .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        return withBoundaries.split(" ").filter { it.isNotEmpty() }.map { it.lowercase() }
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
    val sourceFile: String,
)
