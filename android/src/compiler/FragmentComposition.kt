package dev.deal.embedding

import dev.deal.embedding.capabilities.*
import org.json.*

/** Transport and parsed-view binding only. Authored DEAL owns fragment construction and policy. */
internal class FragmentComposition(private val contracts: List<CapabilityContract>) {
    private fun fail(message:String):Nothing=throw BehaviorCandidate.Rejected("Fragments: $message")
    private fun objects(o:JSONObject,key:String):List<JSONObject> = o.getJSONArray(key).let { a->(0 until a.length()).map { a.getJSONObject(it) } }
    private fun strings(o:JSONObject,key:String):List<String> = o.getJSONArray(key).let { a->(0 until a.length()).map { a.getString(it) } }
    fun expand(input:JSONObject):JSONObject {
        listOf("fragments","commands","display").forEach { key->
            if(input.opt(key) !is JSONArray)fail("Root '$key' must be an actual JSON array (never a quoted/encoded document); submit all root fields title, fragments, commands, display.")
        }
        val expanded=try { JSONObject(CompositionBridge.expand(input.toString(),JSONObject().put("contracts",JSONArray(contracts.map { it.json() })).toString())) }
        catch(error:deal.codegen.jvm.JvmRuntime.DealError) {
            if(error.code !in setOf("INVALID_CONFIG","INVALID_FORMULA","E8001","E8009"))throw error
            fail(error.msg)
        }
        val plan=expanded.getJSONObject("plan");val views=plan.getJSONArray("views");var serial=0
        val replacements=linkedMapOf<String,List<String>>()
        fun nodes(values:List<deal.ui.UiModel.Node>,bindings:Map<String,String>,actions:Map<String,String>,condition:String="",each:String="",key:String=""):List<String> {
            fun expression(e:deal.ui.UiModel.Expr):String=when(e) {
                is deal.ui.UiModel.PathExpr->when {
                    e.parts()==listOf("payload")->"\$payload"
                    e.parts()[0]=="row"->"\$"+e.parts().joinToString(".")
                    e.parts().size==2 && e.parts()[0]=="state"->bindings[e.parts()[1]]?:fail("Missing authored view port")
                    else->fail("Unbound authored view path")
                }
                else->fail("Unsupported authored view expression")
            }
            return values.flatMap { node->when(node) {
                is deal.ui.UiModel.ForEach->nodes(node.children(),bindings,actions,condition,expression(node.source()),expression(node.key()))
                is deal.ui.UiModel.When->{
                    if(condition.isNotEmpty() || node.elseNodes().isNotEmpty())fail("Unsupported authored view condition")
                    nodes(node.thenNodes(),bindings,actions,expression(node.condition()),each,key)
                }
                is deal.ui.UiModel.Call->{
                    val props=JSONArray();val events=JSONArray()
                    node.arguments().forEach { (name,e)->if(e is deal.ui.UiModel.Action) {
                        val action=actions[e.name().removePrefix("app.")]?:fail("Unbound authored action")
                        events.put(JSONObject().put("name",name).put("action",action).put("payload",JSONArray(e.fields().map { (field,value)->JSONObject().put("field",field).put("node",expression(value)) })))
                    }else props.put(JSONObject().put("name",name).put("node",expression(e))) }
                    val id="boundView${serial++}"
                    views.put(JSONObject().put("id",id).put("component",node.name().removePrefix("ui.")).put("props",props).put("events",events)
                        .put("children",JSONArray(nodes(node.children(),bindings,actions))).put("each",each).put("key",key).put("when",condition))
                    listOf(id)
                }
                else->fail("Unsupported authored view node")
            } }
        }
        objects(expanded,"authoredViews").forEach { authored->
            fun bindings(key:String)=authored.getJSONObject(key).let { o->o.keys().asSequence().associateWith { o.getString(it) } }
            replacements[authored.getString("id")]=nodes(FragmentStructure.viewNodes(authored.getString("kind")),bindings("bindings"),bindings("actions"))
        }
        val allViews=(0 until views.length()).map { views.getJSONObject(it) }.associateBy { it.getString("id") }
        allViews.values.forEach { view->view.put("children",JSONArray(strings(view,"children").flatMap { replacements[it]?:listOf(it) })) }
        val editable=expanded.getJSONObject("editable");val instanceViews=expanded.getJSONObject("instanceViews")
        editable.keys().forEach { instance->
            val enabled=editable.getString(instance)
            val ids=strings(instanceViews,instance).flatMap { replacements[it]?:listOf(it) }
            ids.forEach { id->val view=allViews.getValue(id)
                if(view.getString("component") in setOf("IntField","TimeField","TextField","Option","SecondaryAction")) {
                    val props=objects(view,"props");val existing=props.singleOrNull { it.getString("name")=="enabled" }
                    if(existing!=null)existing.put("node",enabled)else view.getJSONArray("props").put(JSONObject().put("name","enabled").put("node",enabled))
                }
            }
        }
        return plan
    }
}
