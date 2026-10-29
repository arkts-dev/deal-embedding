package dev.deal.embedding

import dev.deal.embedding.capabilities.*
import org.json.*

/** Experimental semantic composition. Not registered in the shipping generation route.
 * Requirements/bindings only; state, effects, enumeration and views are maintained here. */
internal class SelectionComposition(private val contracts: List<CapabilityContract>) {
    private val p=JSONObject().put("records",JSONArray()).put("state",JSONArray()).put("nodes",JSONArray()).put("derived",JSONArray()).put("controls",JSONArray()).put("actions",JSONArray()).put("views",JSONArray()).put("root","root")
    private var serial=0
    private val children=mutableListOf<String>()
    private val aliases=mutableMapOf<String,String>()
    private val resourceTypes=mutableMapOf<String,WireType>()
    private val invalid=mutableListOf<String>()
    private fun fail(s:String):Nothing=throw BehaviorCandidate.Rejected(s)
    private fun closed(o:JSONObject,vararg k:String){if(o.keys().asSequence().toSet()!=k.toSet())fail("Expected ${k.joinToString()}")}
    private fun list(o:JSONObject,k:String)=o.getJSONArray(k).let{a->(0 until a.length()).map{a.getJSONObject(it)}}
    private fun add(k:String,o:JSONObject){p.getJSONArray(k).put(o)}
    private fun id()="semantic${serial++}"
    private fun node(op:String,vararg args:String,params:JSONObject=JSONObject()):String {val n=id();add("nodes",JSONObject().put("id",n).put("op",op).put("args",JSONArray(args.toList())).put("params",params));return n}
    private fun jsonValue(v:Any):String=when(v){is String->JSONObject.quote(v);is Number,is Boolean,is JSONObject,is JSONArray->v.toString();else->fail("Unsupported literal data")}
    private fun lit(v:Any):String=node("literal",params=JSONObject().put("type",when(v){is Boolean->"boolean";is Number->"number";is String->"string";else->fail("Scalar literal required")}).put("value",jsonValue(v)))
    private fun state(n:String,t:String,v:Any):String {add("state",JSONObject().put("name",n).put("type",t).put("initial",jsonValue(v)));return "\$state.$n"}
    private fun record(n:String,fs:Map<String,String>){add("records",JSONObject().put("name",n).put("fields",JSONArray(fs.map{JSONObject().put("name",it.key).put("type",it.value)})))}
    private fun bindings(fs:Map<String,String>)=JSONArray(fs.map{JSONObject().put("field",it.key).put("node",it.value)})
    private fun obj(t:String,fs:Map<String,String>)=node("record",params=JSONObject().put("type",t).put("fields",bindings(fs)))
    private fun derived(n:String,t:String,v:Any,expr:String):String {val ref=state(n,t,v);add("derived",JSONObject().put("field",n).put("node",expr));return ref}
    private fun view(component:String,props:Map<String,String> = emptyMap(),events:List<JSONObject> = emptyList(),each:String="",key:String="",whenRef:String="",children:List<String> = emptyList()):String {val n=id();add("views",JSONObject().put("id",n).put("component",component).put("props",JSONArray(props.map{JSONObject().put("name",it.key).put("node",it.value)})).put("events",JSONArray(events)).put("each",each).put("key",key).put("when",whenRef).put("children",JSONArray(children)));return n}
    private fun event(name:String,action:String,fields:Map<String,String> = emptyMap())=JSONObject().put("name",name).put("action",action).put("payload",bindings(fields))
    private fun action(n:String,steps:List<JSONObject> = emptyList(),updates:Map<String,String> = emptyMap(),fields:Map<String,String> = emptyMap()){add("actions",JSONObject().put("id",n).put("fields",JSONArray(fields.map{JSONObject().put("name",it.key).put("type",it.value)})).put("updates",bindings(updates)).put("steps",JSONArray(steps)))}
    private fun set(f:String,n:String)=JSONObject().put("kind","set").put("bind",f).put("node",n)
    private fun function(b:JSONObject):CapabilityFunction {closed(b,"module","function");return contracts.singleOrNull{it.module==b.getString("module")}?.functions?.singleOrNull{it.name==b.getString("function")}?:fail("Undisclosed binding")}
    private fun call(n:String,b:JSONObject,args:List<String>,target:String,value:String)=JSONObject().put("kind","call").put("id",n).put("module",b.getString("module")).put("function",b.getString("function")).put("args",JSONArray(args)).put("bind",target).put("node",value).put("when","")
    private fun local(t:WireType,n:String):String=when(t.kind){
        "array"->local(t.element!!,n+"Row")+"[]"
        "record"->{if(t.optionalFields.isNotEmpty())fail("Prototype does not yet support optional resource fields");record(n,t.fieldTypes.mapValues{(k,v)->local(v,n+k.replaceFirstChar{it.uppercase()})});n}
        else->t.kind
    }
    private fun empty(t:WireType):Any=when(t.kind){"array"->JSONArray();"record"->JSONObject(t.fieldTypes.mapValues{empty(it.value)});"string"->"";"number"->0;"boolean"->false;else->JSONObject.NULL}
    private fun project(t:WireType,n:String,ref:String):String=when(t.kind){"array"->node("project",ref,project(t.element!!,n+"Row","\$row"));"record"->obj(n,t.fieldTypes.mapValues{(k,v)->project(v,n+k.replaceFirstChar{it.uppercase()},"$ref.$k")});else->ref}
    private fun resolve(ref:String):String {
        if(!ref.startsWith("$"))fail("Explicit reference required")
        val first=ref.removePrefix("$").substringBefore('.')
        return aliases[first]?.let{it+ref.removePrefix("$$first")}?:ref
    }
    /** Inline pure formulas, not a model-authored execution graph. */
    private fun expression(v:Any):String=when(v){
        is String->resolve(v)
        is JSONObject->{
            if(v.has("value")){closed(v,"value");lit(v.get("value"))}
            else if(v.has("field")){closed(v,"field","of");node("field",expression(v.get("of")),params=JSONObject().put("field",v.getString("field")))}
            else {
                closed(v,"op","args");val args=v.getJSONArray("args");val xs=(0 until args.length()).map{expression(args.get(it))}
                if(v.getString("op") !in setOf("add","subtract","multiply","eq","ne","ge","le","gt","lt","and","or","not","choose","clock","integer","sum","filter","length","first","all","any"))fail("Unsupported pure formula")
                node(v.getString("op"),*xs.toTypedArray())
            }
        }
        else->fail("Formula must be a reference or typed literal/operator")
    }
    private fun arguments(binding:JSONObject,values:JSONArray):List<String> {
        val fn=function(binding);if(values.length()!=fn.parameters.size)fail("Capability arity")
        return fn.parameters.mapIndexed{i,param->
            val v=values.get(i)
            if(v is JSONObject && v.has("basket")) {
                closed(v,"basket","encoding","idField","quantityField")
                val source=resolve(v.getString("basket"))
                when(v.getString("encoding")){
                    "idQuantity"->{if(param.type.kind!="string")fail("Basket text requires string argument");node("basket",source,params=JSONObject().put("id","id").put("quantity","quantity"))}
                    "records"->{val t=param.type.element?:fail("Basket records require array argument");if(t.kind!="record"||t.fieldTypes.keys!=setOf(v.getString("idField"),v.getString("quantityField")))fail("Basket record fields");val n=id();local(t,n);val rows=node("filter",source,node("gt","\$row.quantity",lit(0)));node("project",rows,obj(n,mapOf(v.getString("idField") to "\$row.id",v.getString("quantityField") to "\$row.quantity")))}
                    else->fail("Unknown basket encoding")
                }
            }else expression(v)
        }
    }
    fun expand(input:JSONObject):JSONObject {
        closed(input,"title","inputs","resources","selection","evaluation")
        if(input.getString("title").isBlank())fail("Title required")
        state("current","boolean",false);state("fresh","boolean",false);val now=state("now","number",0)
        invalid.addAll(listOf("current","fresh"))
        val inputSpecs=list(input,"inputs")
        if(inputSpecs.map{it.getString("id")}.distinct().size!=inputSpecs.size)fail("Duplicate input")
        for(i in inputSpecs){
            closed(i,"id","label","type","value","minimum","maximum")
            val n=i.getString("id");if(n in setOf("row","candidate","reply","current","fresh","now","items","candidates","results"))fail("Reserved input")
            val type=i.getString("type");val t=if(type=="text")"string" else "number"
            aliases[n]=state(n,t,i.get("value"))
            if(type=="text"){
                action("Edit$n",updates=mapOf(n to "\$action.value","current" to lit(false),"fresh" to lit(false),"sessionEpoch" to node("add","\$state.sessionEpoch",lit(1)),"loading" to lit(false)),fields=mapOf("value" to "string"))
                children.add(view("TextField",mapOf("label" to lit(i.getString("label")),"value" to "\$state.$n"),events=listOf(event("onChange","Edit$n",mapOf("value" to "\$payload")))))
            }else {
                if(type !in setOf("integer","money","time"))fail("Unknown input type")
                add("controls",JSONObject().put("field",n).put("label",i.getString("label")).put("widget",type).put("minimum",i.get("minimum")).put("maximum",i.get("maximum")).put("step",1).put("invalidate",JSONArray(invalid)))
            }
        }
        val steps=mutableListOf<JSONObject>()
        for(r in list(input,"resources")){
            closed(r,"id","binding","arguments");val n=r.getString("id");if(n in aliases||n in setOf("row","candidate","reply"))fail("Duplicate/reserved resource")
            val binding=r.getJSONObject("binding");val fn=function(binding);val type=local(fn.result,"Resource$n");val args=arguments(binding,r.getJSONArray("arguments"))
            aliases[n]=state(n,type,empty(fn.result));resourceTypes[n]=fn.result
            steps.add(call("Read$n",binding,args,n,project(fn.result,"Resource$n","\$Read$n")))
        }
        val s=input.getJSONObject("selection");closed(s,"source","id","label","cost","capacity","eligible","budget","quotas","components")
        val source=s.getString("source");val rt=resourceTypes[source.removePrefix("$")]?.element?:fail("Selection source must name an array resource")
        if(rt.kind!="record")fail("Selection rows must be records")
        val quotas=list(s,"quotas");quotas.forEach{closed(it,"minimum","maximum","predicate")}
        record("SelectionComponent",mapOf("id" to "string","quantity" to "number"))
        val fields=linkedMapOf("id" to "string","label" to "string","cost" to "number","capacity" to "number","quantity" to "number","components" to "SelectionComponent[]")
        quotas.indices.forEach{fields["weight$it"]="number"};record("SelectionItem",fields);record("SelectionCandidate",mapOf("items" to "SelectionItem[]","cost" to "number"))
        val items=state("items","SelectionItem[]",JSONArray());val candidates=state("candidates","SelectionCandidate[]",JSONArray())
        val component=s.getJSONObject("components");closed(component,"path","id","quantity")
        val components=if(component.getString("path").isEmpty())node("array",params=JSONObject().put("element","SelectionComponent")) else node("project","\$row.${component.getString("path")}",obj("SelectionComponent",mapOf("id" to "\$row.${component.getString("id")}","quantity" to "\$row.${component.getString("quantity")}")))
        val projection=linkedMapOf("id" to resolve("\$row.${s.getString("id")}"),"label" to resolve("\$row.${s.getString("label")}"),"cost" to expression(s.get("cost")),"capacity" to node("choose",expression(s.get("eligible")),expression(s.get("capacity")),lit(0)),"quantity" to lit(0),"components" to components)
        for((index,q) in quotas.withIndex()){
            val direct=node("choose",expression(q.get("predicate")),lit(1),lit(0))
            projection["weight$index"]=if(component.getString("path").isEmpty())direct else {
                // Matching is bound to the declared source, never to a literal attributes schema.
                val original=q.get("predicate")
                fun replace(v:Any):Any=when(v){is String->if(v.startsWith("\$row."))"\$right."+v.removePrefix("\$row.") else v;is JSONObject->JSONObject(v.keys().asSequence().associateWith{replace(v.get(it))});is JSONArray->JSONArray((0 until v.length()).map{replace(v.get(it))});else->v}
                val pred=expression(replace(original))
                // First project each item's components in a lexical left/right join, then aggregate.
                val contribution=node("choose",pred,"\$left.${component.getString("quantity")}",lit(0))
                val weights=node("join","\$row.${component.getString("path")}",resolve(source),node("eq","\$left.${component.getString("id")}","\$right.${s.getString("id")}"),contribution)
                val sum=node("sum",weights,"\$row")
                node("choose",node("gt",node("length","\$row.${component.getString("path")}"),lit(0)),sum,direct)
            }
        }
        val selectedRows=node("project",resolve(source),obj("SelectionItem",projection))
        steps.add(set("items",selectedRows))
        val constraints=JSONArray(quotas.mapIndexed{i,q->JSONObject().put("field","weight$i").put("minimum",expression(q.get("minimum"))).put("maximum",expression(q.get("maximum")))})
        val enumerate=node("selections",items,expression(s.get("budget")),params=JSONObject().put("type","SelectionCandidate").put("quantity","quantity").put("cost","cost").put("capacity","capacity").put("constraints",constraints))
        steps.add(set("candidates",node("stockQualified",enumerate,items)))
        val e=input.getJSONObject("evaluation");closed(e,"binding","arguments","cost","eligible","ttlMillis")
        record("SelectionResult",mapOf("items" to "SelectionItem[]","cost" to "number","eligible" to "boolean","expiresAt" to "number"))
        val results=state("results","SelectionResult[]",JSONArray())
        aliases["candidate"]="\$item";aliases["reply"]="\$Evaluated"
        val expiry=node("add",node("now"),expression(e.get("ttlMillis")))
        val evaluated=obj("SelectionResult",mapOf("items" to "\$item.items","cost" to expression(e.get("cost")),"eligible" to expression(e.get("eligible")),"expiresAt" to expiry))
        if(e.getJSONObject("binding").length()>0){
            val binding=e.getJSONObject("binding");steps.add(call("Evaluated",binding,arguments(binding,e.getJSONArray("arguments")),"results",evaluated).put("kind","eachCall").put("each",candidates))
        }else {
            if(e.getJSONArray("arguments").length()!=0)fail("Local evaluation has no arguments")
            aliases["candidate"]="\$row"
            val localResult=obj("SelectionResult",mapOf("items" to "\$row.items","cost" to expression(e.get("cost")),"eligible" to expression(e.get("eligible")),"expiresAt" to expiry))
            steps.add(set("results",node("project",candidates,localResult)))
        }
        steps.add(set("now",node("now")));steps.add(set("current",lit(true)));steps.add(set("fresh",lit(true)))
        action("Refresh",steps,updates=mapOf("results" to node("array",params=JSONObject().put("element","SelectionResult"))))
        children.add(view("PrimaryAction",mapOf("text" to lit("Refresh")),events=listOf(event("onClick","Refresh"))))
        val validResults=derived("validResults","SelectionResult[]",JSONArray(),node("filter",results,node("and","\$state.current",node("and","\$state.fresh",node("and","\$row.eligible",node("gt","\$row.expiresAt",now))))))
        val count=derived("resultCount","string","",node("integer",node("length",validResults)))
        children.add(view("Metric",mapOf("label" to lit("Current alternatives"),"value" to count)))
        // Results need stable basket identity; cost is not unique. Keep presentation scalar in this slice.
        val cheapest=derived("lowestCost","number",0,node("field",node("first",node("sort",validResults,params=JSONObject().put("field","cost"))),params=JSONObject().put("field","cost")))
        val hasResults=derived("hasResults","boolean",false,node("gt",node("length",validResults),lit(0)))
        children.add(view("Money",mapOf("label" to lit("Lowest current total"),"minorUnits" to cheapest),whenRef=hasResults))
        val clock=node("abs","\$action.epoch")
        action("Tick",updates=mapOf("now" to clock,"fresh" to node("and","\$state.fresh",node("and",node("ge","\$action.epoch",lit(0)),node("ge",clock,now)))),fields=mapOf("epoch" to "number"))
        children.add(view("SessionClock",events=listOf(event("onTick","Tick",mapOf("epoch" to "\$payload")))))
        val root=JSONObject().put("id","root").put("component","Column").put("props",JSONArray()).put("events",JSONArray()).put("each","").put("key","").put("when","").put("children",JSONArray(children));add("views",root)
        return p
    }
}
