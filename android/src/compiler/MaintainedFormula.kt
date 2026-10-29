package dev.deal.embedding

import org.json.JSONObject

/** Data adapter only. Grammar and validation live in core/composition/formula.deal. */
internal object MaintainedFormula {
    sealed interface Expression {
        data class Literal(val value: Any) : Expression
        data class Reference(val path: String) : Expression
        data class Operation(val name: String, val arguments: List<Expression>) : Expression
    }
    fun parse(source: String): Expression {
        val data = try { JSONObject(CompositionBridge.parseFormula(source)) }
        catch (error: deal.codegen.jvm.JvmRuntime.DealError) {
            if (error.code !in setOf("INVALID_FORMULA", "E8001", "E8009")) throw error
            throw BehaviorCandidate.Rejected("Formula: ${error.msg}")
        }
        fun decode(value: JSONObject): Expression = when (value.getString("kind")) {
            "literal" -> Expression.Literal(value.get("value").let { if (it is Number) it.toDouble() else it })
            "reference" -> Expression.Reference(value.getString("path"))
            "operation" -> Expression.Operation(value.getString("name"),value.getJSONArray("arguments").let { a ->
                (0 until a.length()).map { decode(a.getJSONObject(it)) }
            })
            else -> error("Invalid portable formula result")
        }
        return decode(data)
    }
    fun references(expression: Expression): Set<String> = when (expression) {
        is Expression.Literal -> emptySet()
        is Expression.Reference -> setOf(expression.path)
        is Expression.Operation -> expression.arguments.flatMap { references(it) }.toSet()
    }
    /** Preflight bindings before mutating the structural builder. */
    fun lower(expression: Expression, reference: (String) -> String,
              literal: (Any) -> String, operation: (String, List<String>) -> String): String {
        val resolved = references(expression).associateWith(reference)
        fun emit(value: Expression): String = when (value) {
            is Expression.Literal -> literal(value.value)
            is Expression.Reference -> resolved.getValue(value.path)
            is Expression.Operation -> operation(value.name,value.arguments.map(::emit))
        }
        return emit(expression)
    }
}
