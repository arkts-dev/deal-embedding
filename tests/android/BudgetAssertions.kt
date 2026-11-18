package dev.deal.cutover.tests

import org.json.JSONObject

/** Observe typed Money as money, not as a text spelling. No arithmetic is inferred here. */
internal object BudgetAssertions {
    private fun nodes(tree: JSONObject): List<JSONObject> {
        val result = mutableListOf(tree)
        val children = tree.getJSONArray("children")
        for (i in 0 until children.length()) result.addAll(nodes(children.getJSONObject(i)))
        return result
    }
    private fun prop(node: JSONObject, name: String): JSONObject? {
        val props = node.getJSONArray("props")
        return (0 until props.length()).map { props.getJSONObject(it) }.singleOrNull { it.optString("name") == name }
    }
    private fun texts(tree: JSONObject): List<String> = nodes(tree).flatMap { node ->
        listOf("value", "text", "status").mapNotNull { name -> prop(node,name)?.takeIf { it.optString("kind") == "string" }?.getString("stringValue") }
    }
    private fun differences(tree: JSONObject): List<JSONObject> = nodes(tree).filter {
        it.getString("component").substringAfterLast('.') == "Money" &&
            prop(it,"label")?.optString("stringValue")?.let { label -> label.equals("Difference",true) || label.startsWith("Difference (",true) } == true
    }
    private fun hasStatus(text: String, status: String) = text.equals(status,true) || text.startsWith("$status ",true)
    fun result(tree: JSONObject, minorUnits: Long, status: String) {
        val money = differences(tree)
        val text = texts(tree)
        check(money.isNotEmpty() || text.contains("Difference: $minorUnits EUR cents")) { "Budget difference absent: $tree" }
        for (node in money) {
            check(prop(node,"currency")?.optString("stringValue") == "EUR") { "Budget currency changed: $node" }
            val amount = prop(node,"minorUnits") ?: error("Missing minor units: $node")
            check(amount.getString("kind") == "number" && amount.getDouble("numberValue") == minorUnits.toDouble()) { "Wrong signed minor units: $node" }
        }
        check(text.any { hasStatus(it,status) }) { "Budget status $status absent: $tree" }
        check(text.none { value -> listOf("Over","Under","Equal").any { !it.equals(status,true) && hasStatus(value,it) } }) { "Conflicting Budget statuses: $tree" }
    }
    fun withdrawn(tree: JSONObject) {
        check(differences(tree).isEmpty() && texts(tree).none { text ->
            text.startsWith("Difference:",true) || listOf("Over","Under","Equal").any { hasStatus(text,it) }
        }) { "Invalid draft published derived Budget result: $tree" }
    }
}
