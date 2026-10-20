/**
 * Derives a candidate `input` for a rule body (spec §14.39, `--generate-examples`): a pure function from the body's
 * already-classified conditions to a JSON document that SHOULD make the body match -- or, with [synthesize]'s
 * `violate`, one that satisfies every condition except one (a "near-miss").
 *
 * Nothing here is trusted. The result is only ever a CANDIDATE: the caller runs it through the real `opa eval` and keeps
 * it only if OPA agrees (matches / does not match). So the solver may be incomplete (a body it cannot model is simply
 * [Synthesis.NotSolvable] and gets no generated example) but a wrong guess can never reach the output.
 *
 * It models exactly the constructs the renderer already classifies as plain comparisons, membership, truthiness, a few
 * string/type builtins and `some x in <path>` / `[_]` iteration. Everything else -- rule and function references,
 * fallbacks, `count(...)` comparisons, `data.*` paths, key variables -- is NotSolvable, never a guess.
 *
 * Deterministic: objects are insertion-ordered ([LinkedHashMap]) and nothing iterates a hash-ordered collection.
 */
package io.explico.generate

import io.explico.model.Condition
import io.explico.model.Operand
import io.explico.model.Operator
import io.explico.model.PathSegment
import io.explico.model.RuleBody
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** The outcome of [InputSynthesizer.synthesize]. */
internal sealed interface Synthesis {
    /** A candidate input; NOT yet checked against OPA. */
    data class Solved(val input: JsonObject) : Synthesis

    /** The body uses something the solver does not model (or its constraints contradict); [reason] says what. */
    data class NotSolvable(val reason: String) : Synthesis
}

internal object InputSynthesizer {

    /** The value given to a message argument no condition defines, so the body still produces its message. */
    const val PLACEHOLDER = "sample"

    /**
     * @param violate index into [RuleBody.conditions] of the ONE condition to make false (a near-miss), or null to
     * satisfy them all.
     */
    fun synthesize(body: RuleBody, violate: Int? = null): Synthesis = try {
        val tree = Tree()
        body.conditions.forEachIndexed { index, condition -> tree.apply(condition, violate == index) }
        body.messageArguments.forEach { tree.fillIfUndefined(it) }
        Synthesis.Solved(tree.toJson())
    } catch (e: NotSolvableException) {
        Synthesis.NotSolvable(e.message ?: "not solvable")
    }

    private class NotSolvableException(reason: String) : RuntimeException(reason, null, false, false)

    private fun unsolvable(reason: String): Nothing = throw NotSolvableException(reason)

    // ---- literals ------------------------------------------------------------------------------------------------

    private fun scalar(operand: Operand): JsonElement {
        val literal = operand as? Operand.Literal ?: unsolvable("operand is not a plain path or literal (${operand::class.simpleName})")
        if (literal.collection != null) unsolvable("a collection literal where a scalar is needed")
        return parseScalar(literal.rendered)
    }

    private fun parseScalar(rendered: String): JsonElement {
        val text = rendered.trim()
        return when {
            text.length >= 2 && text.startsWith('"') && text.endsWith('"') -> JsonPrimitive(text.substring(1, text.length - 1))
            text == "true" -> JsonPrimitive(true)
            text == "false" -> JsonPrimitive(false)
            text == "null" -> JsonNull
            text.toLongOrNull() != null -> JsonPrimitive(text.toLong())
            text.toDoubleOrNull() != null -> JsonPrimitive(text.toDouble())
            else -> unsolvable("unparsable literal $text")
        }
    }

    /** The elements of a small literal collection (`"a", "b"`), split on commas outside quotes. */
    private fun collectionElements(operand: Operand): List<JsonElement> {
        val literal = operand as? Operand.Literal
        if (literal == null || literal.collection == null) unsolvable("collection is not a small literal collection")
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var inString = false
        for (i in literal.rendered.indices) {
            val c = literal.rendered[i]
            if (c == '"' && (i == 0 || literal.rendered[i - 1] != '\\')) inString = !inString
            if (c == ',' && !inString) { parts += current.toString(); current.clear() } else current.append(c)
        }
        if (current.isNotBlank()) parts += current.toString()
        return parts.map { parseScalar(it) }
    }

    private fun number(value: Double): JsonElement =
        if (value == Math.rint(value) && Math.abs(value) < 1e15) JsonPrimitive(value.toLong()) else JsonPrimitive(value)

    /** A value that is not [value]. */
    private fun different(value: JsonElement): JsonElement {
        val primitive = value as? JsonPrimitive ?: unsolvable("cannot pick a different value for a composite")
        return when {
            primitive is JsonNull -> JsonPrimitive("not-null")
            primitive.isString -> JsonPrimitive(primitive.content + "-other")
            primitive.booleanOrNull != null -> JsonPrimitive(!primitive.booleanOrNull!!)
            primitive.doubleOrNull != null -> number(primitive.doubleOrNull!! + 1)
            else -> unsolvable("cannot pick a different value")
        }
    }

    private fun numeric(value: JsonElement): Double {
        val primitive = value as? JsonPrimitive
        return (if (primitive != null && !primitive.isString) primitive.doubleOrNull else null)
            ?: unsolvable("ordering comparison against a non-number")
    }

    private fun invert(op: Operator) = when (op) {
        Operator.EQ -> Operator.NEQ
        Operator.NEQ -> Operator.EQ
        Operator.GT -> Operator.LTE
        Operator.GTE -> Operator.LT
        Operator.LT -> Operator.GTE
        Operator.LTE -> Operator.GT
    }

    private fun mirror(op: Operator) = when (op) {
        Operator.EQ -> Operator.EQ
        Operator.NEQ -> Operator.NEQ
        Operator.GT -> Operator.LT
        Operator.GTE -> Operator.LTE
        Operator.LT -> Operator.GT
        Operator.LTE -> Operator.GTE
    }

    // ---- the input being built -------------------------------------------------------------------------------------

    /** A place a scalar can be written: an object field or the single element of a list. */
    private sealed interface Slot {
        fun get(): Any?
        fun write(value: Any)

        fun set(value: JsonElement) {
            val existing = get()
            if (existing != null && existing != value) unsolvable("conflicting constraints on one path")
            write(value)
        }
    }

    private class FieldSlot(val holder: MutableMap<String, Any>, val key: String) : Slot {
        override fun get(): Any? = holder[key]
        override fun write(value: Any) { holder[key] = value }
    }

    private class ElementSlot(val list: MutableList<Any>) : Slot {
        override fun get(): Any? = list.firstOrNull()
        override fun write(value: Any) { if (list.isEmpty()) list.add(value) else list[0] = value }
    }

    private class Tree {
        val root = LinkedHashMap<String, Any>()

        /** Lookup only (never iterated): the element object created for each `some x in ...` variable. */
        val elements = HashMap<String, MutableMap<String, Any>>()
        var fresh = 0

        fun toJson(): JsonObject = convert(root) as JsonObject

        private fun convert(value: Any): JsonElement = when (value) {
            is JsonElement -> value
            is Map<*, *> -> JsonObject(value.entries.associateTo(LinkedHashMap()) { (k, v) -> k as String to convert(v!!) })
            is List<*> -> JsonArray(value.map { convert(it!!) })
            else -> error("unexpected value in the synthesized input: $value")
        }

        fun freshValue(): JsonElement = JsonPrimitive("v${++fresh}")

        // ---- paths ----

        @Suppress("UNCHECKED_CAST")
        private fun mapAt(holder: MutableMap<String, Any>, key: String): MutableMap<String, Any> =
            when (val existing = holder[key]) {
                null -> LinkedHashMap<String, Any>().also { holder[key] = it }
                is MutableMap<*, *> -> existing as MutableMap<String, Any>
                else -> unsolvable("conflicting constraints on one path")
            }

        @Suppress("UNCHECKED_CAST")
        private fun listAt(holder: MutableMap<String, Any>, key: String): MutableList<Any> =
            when (val existing = holder[key]) {
                null -> ArrayList<Any>().also { holder[key] = it }
                is MutableList<*> -> existing as MutableList<Any>
                else -> unsolvable("conflicting constraints on one path")
            }

        @Suppress("UNCHECKED_CAST")
        private fun firstElementMap(list: MutableList<Any>): MutableMap<String, Any> {
            if (list.isEmpty()) list.add(LinkedHashMap<String, Any>())
            return list[0] as? MutableMap<String, Any> ?: unsolvable("conflicting constraints on one path")
        }

        private fun keyOf(segment: PathSegment): String = when (segment) {
            is PathSegment.Field -> segment.name
            is PathSegment.KeyLiteral -> segment.key
            else -> unsolvable("path segment ${segment::class.simpleName} is not modelled")
        }

        /** Creates whatever containers the path needs and returns where its last segment lives. */
        fun slotOf(path: Operand.Path): Slot {
            val segments = path.segments
            val lastVariable = segments.indexOfLast { it is PathSegment.VarIndex }
            var current: MutableMap<String, Any>
            var i: Int
            if (lastVariable >= 0) {
                val name = (segments[lastVariable] as PathSegment.VarIndex).name
                current = elements[name] ?: unsolvable("variable '$name' has no generated element")
                i = lastVariable + 1
            } else {
                when ((segments.firstOrNull() as? PathSegment.Field)?.name) {
                    "input" -> Unit
                    "data" -> unsolvable("data-rooted path (needs a data document)")
                    else -> unsolvable("path is not rooted at input")
                }
                current = root
                i = 1
            }
            if (i >= segments.size) unsolvable("path ends at an element, not a field")
            while (true) {
                val key = keyOf(segments[i])
                if (i == segments.lastIndex) return FieldSlot(current, key)
                if (segments[i + 1] is PathSegment.AnyIndex) {
                    val list = listAt(current, key)
                    if (i + 1 == segments.lastIndex) return ElementSlot(list)
                    current = firstElementMap(list)
                    i += 2
                } else {
                    current = mapAt(current, key)
                    i += 1
                }
            }
        }

        private fun pathOf(operand: Operand): Operand.Path =
            operand as? Operand.Path ?: unsolvable("operand is not a plain path or literal (${operand::class.simpleName})")

        /** The list a collection path denotes, created empty if absent. */
        @Suppress("UNCHECKED_CAST")
        fun listOf(path: Operand.Path): MutableList<Any> = when (val slot = slotOf(path)) {
            is FieldSlot -> listAt(slot.holder, slot.key)
            is ElementSlot -> unsolvable("a collection reached through a wildcard element")
        }

        // ---- conditions ----

        fun apply(condition: Condition, violate: Boolean) {
            when (condition) {
                is Condition.Comparison -> comparison(condition, violate)
                is Condition.Truthy -> slotOf(pathOf(condition.operand)).set(JsonPrimitive(!(condition.negated xor violate)))
                is Condition.Membership -> membership(condition, violate)
                is Condition.BuiltinCall -> builtin(condition, violate)
                is Condition.SomeIn -> iteration(condition, violate)
                is Condition.RuleReference -> unsolvable("RuleReference is not modelled")
                is Condition.FunctionCall -> unsolvable("FunctionCall is not modelled")
                is Condition.Unrendered -> unsolvable("Unrendered (${condition.reason}) is not modelled")
                is Condition.Every -> unsolvable("every is not modelled")
                is Condition.SomeKey -> unsolvable("a free index variable is not modelled")
            }
        }

        private fun comparison(condition: Condition.Comparison, violate: Boolean) {
            var op = if (condition.negated xor violate) invert(condition.op) else condition.op
            var left = condition.left
            var right = condition.right
            if (left is Operand.Literal && right is Operand.Path) {
                left = right.also { right = left }
                op = mirror(op)
            }
            when {
                left is Operand.Path && right is Operand.Literal -> {
                    val literal = scalar(right)
                    val slot = slotOf(left)
                    slot.set(
                        when (op) {
                            Operator.EQ -> literal
                            Operator.NEQ -> different(literal)
                            Operator.GT -> number(numeric(literal) + 1)
                            Operator.GTE, Operator.LTE -> number(numeric(literal))
                            Operator.LT -> number(numeric(literal) - 1)
                        },
                    )
                }
                left is Operand.Path && right is Operand.Path -> {
                    val a = slotOf(left)
                    val b = slotOf(right as Operand.Path)
                    when (op) {
                        Operator.EQ -> {
                            val value = (a.get() ?: b.get() ?: freshValue()) as? JsonElement ?: unsolvable("conflicting constraints on one path")
                            a.set(value); b.set(value)
                        }
                        Operator.NEQ -> {
                            val first = (a.get() ?: freshValue()) as? JsonElement ?: unsolvable("conflicting constraints on one path")
                            a.set(first)
                            val pinned = b.get()
                            when {
                                pinned == null -> b.set(different(first))
                                pinned == first || pinned !is JsonElement -> unsolvable("conflicting constraints on one path")
                            }
                        }
                        Operator.GT, Operator.GTE -> { a.set(JsonPrimitive(5L)); b.set(if (op == Operator.GT) JsonPrimitive(1L) else JsonPrimitive(5L)) }
                        Operator.LT, Operator.LTE -> { b.set(JsonPrimitive(5L)); a.set(if (op == Operator.LT) JsonPrimitive(1L) else JsonPrimitive(5L)) }
                    }
                }
                left is Operand.Literal && right is Operand.Literal -> unsolvable("comparison has no path operand")
                else -> unsolvable("operand is not a plain path or literal (${(if (left is Operand.Path || left is Operand.Literal) right else left)::class.simpleName})")
            }
        }

        private fun membership(condition: Condition.Membership, violate: Boolean) {
            val inside = !(condition.negated xor violate)
            val member = condition.member
            val collection = condition.collection
            when {
                member is Operand.Path && collection is Operand.Literal -> {
                    val elements = collectionElements(collection)
                    slotOf(member).set(if (inside) elements.first() else outsideOf(elements))
                }
                member is Operand.Literal && collection is Operand.Path -> {
                    val value = scalar(member)
                    val list = listOf(collection)
                    if (inside) { if (value !in list) list.add(value) } else if (value in list) unsolvable("conflicting constraints on one path")
                }
                member is Operand.Path && collection is Operand.Path -> {
                    val list = listOf(collection)
                    val slot = slotOf(member)
                    if (inside) {
                        val value = (slot.get() ?: list.firstOrNull() ?: freshValue()) as? JsonElement ?: unsolvable("conflicting constraints on one path")
                        slot.set(value)
                        if (value !in list) list.add(value)
                    } else {
                        val value = (slot.get() ?: freshValue()) as? JsonElement ?: unsolvable("conflicting constraints on one path")
                        if (value in list) unsolvable("conflicting constraints on one path")
                        slot.set(value)
                    }
                }
                else -> unsolvable("membership operands are not (path in literal collection), (literal in path) or (path in path)")
            }
        }

        private fun outsideOf(elements: List<JsonElement>): JsonElement {
            var candidate = "not-a-member"
            while (JsonPrimitive(candidate) in elements) candidate += "-x"
            return JsonPrimitive(candidate)
        }

        private fun builtin(condition: Condition.BuiltinCall, violate: Boolean) {
            val holds = !(condition.negated xor violate)
            val name = condition.name
            val args = condition.args
            if (name in STRING_PREDICATES && args.size == 2) {
                val literal = (scalar(args[1]) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: unsolvable("builtin $name needs a string literal")
                val test: (String) -> Boolean = when (name) {
                    "startswith" -> { s -> s.startsWith(literal) }
                    "endswith" -> { s -> s.endsWith(literal) }
                    else -> { s -> s.contains(literal) }
                }
                val value = if (holds) {
                    when (name) { "startswith" -> literal + "x"; "endswith" -> "x$literal"; else -> "x${literal}x" }
                } else {
                    listOf("no-match", "zzz", "q", "0").firstOrNull { !test(it) } ?: unsolvable("cannot build a string for which $name is false")
                }
                slotOf(pathOf(args[0])).set(JsonPrimitive(value))
                return
            }
            if (name in TYPE_CHECKS && args.size == 1) {
                val slot = slotOf(pathOf(args[0]))
                slot.set(
                    when (name) {
                        "is_string" -> if (holds) JsonPrimitive("s") else JsonPrimitive(1L)
                        "is_number" -> if (holds) JsonPrimitive(1L) else JsonPrimitive("s")
                        else -> if (holds) JsonPrimitive(true) else JsonPrimitive("s")
                    },
                )
                return
            }
            unsolvable("builtin $name is not modelled")
        }

        private fun iteration(condition: Condition.SomeIn, violate: Boolean) {
            if (violate) unsolvable("an iteration cannot be violated")
            if (condition.key != null) unsolvable("key variable (some k, v in ...) is not modelled")
            val collection = pathOf(condition.collection)
            val list = listOf(collection)
            elements[condition.variable] = firstElementMap(list)
        }

        /** A message argument no condition defined still has to exist, or the body would produce no message at all. */
        fun fillIfUndefined(path: Operand.Path) {
            val slot = slotOf(path)
            if (slot.get() == null) slot.write(JsonPrimitive(PLACEHOLDER))
        }
    }

    private val STRING_PREDICATES = setOf("startswith", "endswith", "contains")
    private val TYPE_CHECKS = setOf("is_string", "is_number", "is_boolean")
}
