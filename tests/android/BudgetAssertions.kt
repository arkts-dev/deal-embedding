package dev.deal.cutover.tests

import org.json.JSONObject

/**
 * Observe the Budget outcome through the specification's numeric presentation.
 *
 * The signed committed-minus-approved difference must be a typed number (`kind` "number")
 * on a specification numeric component (`ui.NumberText`, `ui.NumberStat` or
 * `ui.NumberListItem`), identified by its own label/prefix/suffix or by the label directly
 * above it. The over/under/equal classification must be a typed string on that same typed
 * result node.
 *
 * A legacy `ui.Money` tree or an `ui.Text` spelling such as "Difference: 1750 EUR cents" is
 * a located acceptance failure: rendered text never substitutes for the typed value the
 * acceptance contract requires. No arithmetic is inferred here.
 */
internal object BudgetAssertions {
    private val classifications = listOf("Over","Under","Equal")
    private val statusProps = setOf("status","state","supporting","suffix","prefix","label","text")
    private val differenceMarkers = listOf("difference")

    fun result(tree: JSONObject, minorUnits: Long, status: String) {
        val (node,value) = ViewTree.unique("budget.difference",tree,differenceMarkers,ViewTree.NUMERIC,
            "the committed-minus-approved difference is not a typed number on ui.NumberText/ui.NumberStat/ui.NumberListItem")
        check(value == minorUnits.toDouble()) { "Wrong signed Budget difference: $value, expected $minorUnits EUR cents" }
        val own = ViewTree.props(node).filter { it.optString("kind") == "string" && it.optString("name") in statusProps }.map { it.optString("stringValue") }
        check(own.any { classification(it).equals(status,true) }) { "Budget status $status is not a typed string on the typed difference result: $own" }
        val published = ViewTree.nodes(tree).filter { it !== node }.flatMap { other ->
            if(!ViewTree.ownMarkers(other).any{it.contains("difference",true)||it.contains("budget",true)}) emptyList()
            else ViewTree.props(other).filter { it.optString("kind") == "string" && it.optString("name") in setOf("status","state","supporting") }.map { it.optString("stringValue") }
        }
        val conflicting = published.filter { value -> classifications.any { !it.equals(status,true) && classification(value).equals(it,true) } }
        check(conflicting.isEmpty()) { "Conflicting Budget statuses published: $conflicting" }
    }

    /**
     * An invalid draft publishes no derived difference, no spelled difference and no classification: the
     * spec makes an invalid result null and unready, and Budget performs no provider read that could expire,
     * so a retained current value or a retained classification here is a located failure rather than a
     * stale display.
     */
    fun withdrawn(tree: JSONObject) {
        val typed = ViewTree.numbers(tree,differenceMarkers,ViewTree.NUMERIC)
        check(typed.isEmpty()) { "Invalid draft kept a typed Budget difference: ${typed.map { it.second }}" }
        val legacy = ViewTree.nodes(tree).filter { node ->
            val name = ViewTree.component(node)
            val props = ViewTree.props(node)
            val ownDifference = ViewTree.ownMarkers(node).any { it.contains("difference",true) }
            val moneyProps = props.map { it.optString("name") }.toSet()
            val legacyMoney = name.endsWith("Money") || (ownDifference && ("minorUnits" in moneyProps ||
                ("amount" in moneyProps && "currency" in moneyProps)))
            legacyMoney || (name !in ViewTree.NUMERIC && ownDifference && props.any { it.optString("kind") == "number" })
        }
        check(legacy.isEmpty()) { "Invalid draft kept a legacy typed difference on ${legacy.map { ViewTree.component(it) }}" }
        // A spelled difference carries the value in text: a label alone never does.
        val spelled = ViewTree.allStrings(tree).filter { it.contains("difference",true) && it.any { character -> character.isDigit() } }
        check(spelled.isEmpty()) { "Invalid draft published a spelled Budget difference: $spelled" }
        val statuses = ViewTree.nodes(tree).flatMap { node ->
            ViewTree.props(node).filter { it.optString("kind") == "string" && it.optString("name") in statusProps }.map { it.optString("stringValue") }
        }.filter { value -> !value.contains("difference",true) && classifications.any { classification(value).equals(it,true) } }
        check(statuses.isEmpty()) { "Invalid draft published a Budget status: $statuses" }
    }

    /** Whole-word classification so "Over budget" classifies but "equally" never does. */
    private fun classification(value: String): String? {
        val words = value.lowercase().split(Regex("[^a-z]+"))
        return classifications.firstOrNull { words.contains(it.lowercase()) }
    }
}
