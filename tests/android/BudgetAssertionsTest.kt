package dev.deal.cutover.tests

import org.json.JSONArray
import org.json.JSONObject

internal fun checkBudgetAssertions() {
    fun text(name: String, value: String) = JSONObject().put("name",name).put("kind","string").put("stringValue",value)
    fun number(name: String, value: Double) = JSONObject().put("name",name).put("kind","number").put("numberValue",value)
    fun node(component: String, props: List<JSONObject>) = JSONObject().put("component",component).put("props",JSONArray(props)).put("children",JSONArray())
    fun column(children: List<JSONObject>) = JSONObject().put("component","ui.Column").put("props",JSONArray()).put("children",JSONArray(children))
    /** The specification numeric presentation: a typed value plus the typed classification on the result. */
    fun spec(component: String, amount: Double, status: String, label: String = "Difference (committed - approved)", suffix: String = " EUR cents") = column(listOf(
        node(component,listOf(text("label",label),number("value",amount),text("supporting",status),text("suffix",suffix),number("decimals",-1.0),number("minimumDigits",1.0)))
    ))
    /** A difference spelled only as text: the legacy spelling the observer must reject. */
    fun spelled(amount: Long, status: String) = column(listOf(
        node("ui.Text",listOf(text("text","Difference (committed - approved)"))),
        node("ui.Text",listOf(text("text","Difference: $amount EUR cents"))),
        node("ui.Text",listOf(text("text",status)))
    ))
    /** The removed legacy component with its own money props: rejected as well. */
    fun legacy(amount: Double, status: String, currency: String = "EUR") = column(listOf(
        node("ui.Money",listOf(text("label","Difference (committed - approved)"),text("currency",currency),number("minorUnits",amount))),
        node("ui.Text",listOf(text("text",status)))
    ))
    fun rejects(name: String, block: () -> Unit) { check(runCatching(block).isFailure) { "Budget observer accepted $name" } }
    for ((value,status) in listOf(1750L to "Over",-1000L to "Under",0L to "Equal",-250L to "Under")) {
        BudgetAssertions.result(spec("ui.NumberStat",value.toDouble(),status),value,status)
        BudgetAssertions.result(spec("ui.NumberText",value.toDouble(),status,label="Difference",suffix=" EUR cents"),value,status)
    }
    // The difference may be labelled by the plain text directly above the typed number.
    BudgetAssertions.result(column(listOf(
        node("ui.Text",listOf(text("text","Difference"))),
        node("ui.NumberStat",listOf(number("value",1750.0),text("supporting","Over"),text("suffix"," EUR cents")))
    )),1750,"Over")
    rejects("a legacy ui.Money difference") { BudgetAssertions.result(legacy(1750.0,"Over"),1750,"Over") }
    rejects("a Difference: text spelling") { BudgetAssertions.result(spelled(1750,"Over"),1750,"Over") }
    rejects("a wrong minor-unit scale") { BudgetAssertions.result(spec("ui.NumberStat",17.5,"Over"),1750,"Over") }
    rejects("a flipped sign") { BudgetAssertions.result(spec("ui.NumberStat",-1750.0,"Over"),1750,"Over") }
    rejects("a wrong classification") { BudgetAssertions.result(spec("ui.NumberStat",1750.0,"Under"),1750,"Over") }
    rejects("a status that is not on the typed result") { BudgetAssertions.result(column(listOf(
        node("ui.NumberStat",listOf(text("label","Difference"),number("value",1750.0))),
        node("ui.Text",listOf(text("text","Over")))
    )),1750,"Over") }
    rejects("conflicting classifications") { BudgetAssertions.result(column(listOf(
        node("ui.NumberStat",listOf(text("label","Difference"),number("value",1750.0),text("supporting","Over budget"))),
        node("ui.NumberStat",listOf(text("label","Remaining"),number("value",250.0),text("supporting","Under budget")))
    )),1750,"Over") }
    rejects("two conflicting spelled classifications") { BudgetAssertions.result(spec("ui.NumberStat",0.0,"Over Under"),0,"Equal") }
    rejects("withdrawing a published legacy difference") { BudgetAssertions.withdrawn(legacy(1750.0,"Over")) }
    rejects("withdrawing a spelled difference") { BudgetAssertions.withdrawn(spelled(1750,"Over")) }
    rejects("withdrawing a typed difference") { BudgetAssertions.withdrawn(spec("ui.NumberStat",1750.0,"Over")) }
    // A withdrawn draft may keep its label but must publish no derived value.
    BudgetAssertions.withdrawn(column(listOf(node("ui.Text",listOf(text("text","Difference (committed - approved)"))))))
    BudgetAssertions.withdrawn(column(emptyList()))
    val empty = column(emptyList())
    rejects("a missing difference") { BudgetAssertions.result(empty,0,"Equal") }
    rejects("a difference that is only a spec label") { BudgetAssertions.result(column(listOf(
        node("ui.NumberText",listOf(text("prefix","Difference"),text("suffix"," EUR cents")))
    )),1750,"Over") }
}
