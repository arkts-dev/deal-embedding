package dev.deal.embedding

import org.json.JSONObject

/** Copy-only presentation transform. Excludes inputs, payloads, keys and executable props. */
fun displayText(tree:JSONObject,transform:(String)->String):JSONObject {
    val copy=JSONObject(tree.toString())
    fun visit(node:JSONObject){
        val names=when(node.getString("component").substringAfterLast('.')){
            "Text","Hero","Heading","Caption"->setOf("value")
            "Money"->setOf("label")
            "Metric"->setOf("label","value","unit")
            "Comparison"->setOf("status")
            "Option","Item"->setOf("title","detail","meta","trailing")
            "Section"->setOf("title","summary")
            "Notice","Failure","Toggle","Button","PrimaryAction","SecondaryAction"->setOf("text")
            "Progress"->setOf("label")
            else->emptySet()
        }
        val props=node.getJSONArray("props")
        for(i in 0 until props.length()){val prop=props.getJSONObject(i);if(prop.optString("name") in names&&prop.has("stringValue"))prop.put("stringValue",transform(prop.getString("stringValue")))}
        val children=node.getJSONArray("children");for(i in 0 until children.length())visit(children.getJSONObject(i))
    }
    visit(copy);return copy
}
