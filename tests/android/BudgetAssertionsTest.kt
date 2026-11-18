package dev.deal.cutover.tests

import org.json.JSONArray
import org.json.JSONObject

internal fun checkBudgetAssertions() {
    fun text(name: String, value: String) = JSONObject().put("name",name).put("kind","string").put("stringValue",value)
    fun tree(amount: Double, status: String, currency: String = "EUR"): JSONObject {
        val money = JSONObject().put("component","ui.Money").put("props",JSONArray(listOf(
            text("label","Difference (committed - approved)"),text("currency",currency),
            JSONObject().put("name","minorUnits").put("kind","number").put("numberValue",amount)
        ))).put("children",JSONArray())
        val label = JSONObject().put("component","ui.Text").put("props",JSONArray(listOf(text("value",status)))).put("children",JSONArray())
        return JSONObject().put("component","ui.Column").put("props",JSONArray()).put("children",JSONArray(listOf(money,label)))
    }
    fun rejects(block: () -> Unit) { check(runCatching(block).isFailure) { "Budget observer accepted incorrect result" } }
    for ((value,status) in listOf(1750L to "over",-1000L to "Under",0L to "Equal",-250L to "under")) {
        BudgetAssertions.result(tree(value.toDouble(),status),value,status)
    }
    rejects { BudgetAssertions.result(tree(17.5,"over"),1750,"Over") }
    rejects { BudgetAssertions.result(tree(-1750.0,"over"),1750,"Over") }
    rejects { BudgetAssertions.result(tree(1750.0,"under"),1750,"Over") }
    rejects { BudgetAssertions.result(tree(1750.0,"over","USD"),1750,"Over") }
    rejects { BudgetAssertions.withdrawn(tree(1750.0,"over")) }
    rejects { BudgetAssertions.result(tree(0.0,"Over Under"),0,"Equal") }
    val empty = JSONObject().put("component","ui.Column").put("props",JSONArray()).put("children",JSONArray())
    BudgetAssertions.withdrawn(empty)
    rejects { BudgetAssertions.result(empty,0,"Equal") }
    val legacy = tree(1750.0,"Over")
    val difference = legacy.getJSONArray("children").getJSONObject(0)
    difference.put("component","ui.Text").put("props",JSONArray(listOf(text("value","Difference: 1750 EUR cents"))))
    BudgetAssertions.result(legacy,1750,"Over")
    rejects { BudgetAssertions.withdrawn(legacy) }
}
