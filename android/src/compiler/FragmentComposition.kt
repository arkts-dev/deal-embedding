package dev.deal.embedding

import dev.deal.embedding.capabilities.*
import org.json.*

/** Bundled fragments own storage, actions and views. Configuration names semantic ports, never
 * graph nodes or source. Like OHOS composeFragments, each instance is independently namespaced. */
internal class FragmentComposition(private val contracts: List<CapabilityContract>) {
    private val plan=JSONObject().apply { listOf("records","state","nodes","derived","controls","actions","views").forEach { put(it,JSONArray()) };put("root","root") }
    private var serial=0
    private data class Port(val ref:String,val type:CapabilityType,val present:String)
    private val ports=linkedMapOf<String,Port>()
    private val specifications=linkedMapOf<String,JSONObject>()
    private val dependencies=linkedMapOf<String,Set<String>>()
    private val steps=linkedMapOf<String,List<JSONObject>>()
    private val views=linkedMapOf<String,List<String>>()
    private fun fail(message:String):Nothing=throw BehaviorCandidate.Rejected("Fragments: $message")
    private fun closed(o:JSONObject,vararg names:String) { if(o.keys().asSequence().toSet()!=names.toSet())fail("Expected ${names.joinToString()}, got ${o.keys().asSequence().joinToString()}") }
    private fun objects(o:JSONObject,key:String):List<JSONObject> { val a=o.opt(key) as? JSONArray?:fail("$key must be an array, not encoded JSON");return (0 until a.length()).map { a.getJSONObject(it) } }
    private fun strings(o:JSONObject,key:String):List<String> { val a=o.opt(key) as? JSONArray?:fail("$key must be an array");return (0 until a.length()).map { a.getString(it) } }
    private fun name(n:String):String { if(!n.matches(Regex("[A-Za-z][A-Za-z0-9_]*")))fail("Invalid instance/port name '$n'");return n }
    private fun add(key:String,value:JSONObject) { plan.getJSONArray(key).put(value) }
    private fun node(op:String,vararg args:String,params:JSONObject=JSONObject()):String { val id="expression${serial++}";add("nodes",JSONObject().put("id",id).put("op",op).put("args",JSONArray(args.toList())).put("params",params));return id }
    private fun jsonValue(value:Any):String=when(value) { is String->JSONObject.quote(value);is Number,is Boolean,is JSONObject,is JSONArray->value.toString();else->fail("Unsupported literal data") }
    private fun literal(value:Any):String=node("literal",params=JSONObject().put("type",when(value){is Number->"number";is Boolean->"boolean";is String->"string";else->fail("Scalar literal required")}).put("value",jsonValue(value)))
    private val yes get()=literal(true)
    private val zero get()=literal(0)
    private fun all(values:List<String>):String=values.fold(yes) { a,b->node("and",a,b) }
    private fun state(id:String,type:String,value:Any):String { add("state",JSONObject().put("name",id).put("type",type).put("initial",jsonValue(value)));return "\$state.$id" }
    private fun fields(values:Map<String,String>)=JSONArray(values.map { (f,n)->JSONObject().put("field",f).put("node",n) })
    private fun record(id:String,types:Map<String,String>) { add("records",JSONObject().put("name",id).put("fields",JSONArray(types.map { (f,t)->JSONObject().put("name",f).put("type",t) }))) }
    private fun construct(type:String,values:Map<String,String>)=node("record",params=JSONObject().put("type",type).put("fields",fields(values)))
    private fun derive(id:String,type:String,value:Any,expression:String):String { val r=state(id,type,value);add("derived",JSONObject().put("field",id).put("node",expression));return r }
    private fun set(field:String,expression:String)=JSONObject().put("kind","set").put("bind",field).put("node",expression)
    private fun action(id:String,updates:Map<String,String> = emptyMap(),steps:List<JSONObject> = emptyList(),parameters:Map<String,String> = emptyMap()) { add("actions",JSONObject().put("id",id).put("fields",JSONArray(parameters.map { (f,t)->JSONObject().put("name",f).put("type",t) })).put("updates",fields(updates)).put("steps",JSONArray(steps))) }
    private fun event(event:String,action:String,payload:Map<String,String> = emptyMap())=JSONObject().put("name",event).put("action",action).put("payload",fields(payload))
    private fun view(component:String,props:Map<String,String> = emptyMap(),children:List<String> = emptyList(),events:List<JSONObject> = emptyList(),each:String="",key:String="",whenRef:String="",id:String="view${serial++}"):String { add("views",JSONObject().put("id",id).put("component",component).put("props",JSONArray(props.map { (f,n)->JSONObject().put("name",f).put("node",n) })).put("children",JSONArray(children)).put("events",JSONArray(events)).put("each",each).put("key",key).put("when",whenRef));return id }
    private fun type(t:CapabilityType,id:String):String=when(t.kind) {
        "array"->type(t.element!!,id+"Row")+"[]"
        "record"->{
            val fs=linkedMapOf<String,String>()
            t.fields.forEach { (f,ft)->fs[f]=if(f in t.optionalFields) {
                val option=id+"_"+f+"Option";record(option,mapOf("present" to "boolean","value" to type(ft,id+"_"+f)));option
            }else type(ft,id+"_"+f) }
            record(id,fs);id
        }
        else->t.kind
    }
    private fun empty(t:CapabilityType):Any=when(t.kind) {
        "array"->JSONArray();"record"->JSONObject().also { o->t.fields.forEach { (f,ft)->o.put(f,if(f in t.optionalFields)JSONObject().put("present",false).put("value",empty(ft))else empty(ft)) } }
        "number"->0;"boolean"->false;"string"->"";else->JSONObject.NULL
    }
    private fun binding(config:JSONObject):CapabilityFunction {
        closed(config,"module","function")
        return contracts.singleOrNull { it.module==config.getString("module") }?.functions?.singleOrNull { it.name==config.getString("function") }?:fail("Undisclosed capability ${config}")
    }
    private fun port(path:String):Port {
        val parts=path.split('.');if(parts.size<2)fail("Use instance.port, not '$path'")
        val p=ports[parts.take(2).joinToString(".")]?:fail("Unknown port '$path'")
        var t=p.type;var ref=p.ref;var present=p.present
        parts.drop(2).forEach { f->
            if(t.kind!="record")fail("'$f' is not a record field in '$path'")
            val ft=t.fields[f]?:fail("Unknown field '$f' in '$path'")
            if(f in t.optionalFields) { present=node("and",present,"$ref.$f.present");ref+=".$f.value" }else ref+=".$f"
            t=ft
        }
        return Port(ref,t,present)
    }
    private fun formula(text:String,locals:Set<String> = emptySet()):String = MaintainedFormula.lower(MaintainedFormula.parse(text),{ path->
        if(path.substringBefore('.') in locals || path.substringBefore('.')=="row")"$$path" else port(path).ref
    },::literal,{ op,args->node(op,*args.toTypedArray()) })
    private fun present(text:String):String=all(MaintainedFormula.references(MaintainedFormula.parse(text)).filter { it.substringBefore('.') !in setOf("row","item","reply") }.map { port(it).present })
    private fun publish(id:String,name:String,ref:String,t:CapabilityType,present:String=yes) { if(ports.put("$id.$name",Port(ref,t,present))!=null)fail("Duplicate port $id.$name") }
    private fun call(id:String,b:JSONObject,args:List<String>,field:String,projection:String,whenRef:String="")=JSONObject().put("kind","call").put("id",id).put("module",b.getString("module")).put("function",b.getString("function")).put("args",JSONArray(args)).put("bind",field).put("node",projection).put("when",whenRef)

    fun expand(input:JSONObject):JSONObject {
        listOf("fragments","commands","display").forEach { key->
            if(input.opt(key) !is JSONArray)fail("Root '$key' must be an actual JSON array (never a quoted/encoded document); submit all root fields title, fragments, commands, display.")
        }
        closed(input,"title","fragments","commands","display")
        if(input.getString("title").isBlank())fail("Title required")
        objects(input,"fragments").forEach { f->closed(f,"id","kind","config");val id=name(f.getString("id"));if(id in setOf("row","item","reply")||specifications.put(id,f)!=null)fail("Duplicate/reserved instance '$id'") }
        if(specifications.isEmpty())fail("At least one fragment required")
        specifications.forEach { (id,f)->
            val references=mutableSetOf<String>()
            fun scan(v:Any?) { when(v) {
                is JSONObject->v.keys().forEach { key->if(key=="expr")references.addAll(MaintainedFormula.references(MaintainedFormula.parse(v.getString(key)))) else scan(v.get(key)) }
                is JSONArray->(0 until v.length()).forEach { scan(v.get(it)) }
            } }
            scan(f.getJSONObject("config"));dependencies[id]=references.filter { it.substringBefore('.') !in setOf("row","item","reply") }.map { it.substringBefore('.') }.toSet()
            dependencies.getValue(id).forEach { if(it !in specifications)fail("$id references missing instance '$it'") }
        }
        val ordered=mutableListOf<String>();val visiting=mutableSetOf<String>()
        fun visit(id:String) { if(id in ordered)return;if(!visiting.add(id))fail("Dependency cycle at '$id'");dependencies.getValue(id).forEach(::visit);visiting.remove(id);ordered.add(id) }
        specifications.keys.forEach(::visit)
        ordered.forEach { id->val f=specifications.getValue(id);val c=f.getJSONObject("config")
            views[id]=when(f.getString("kind")) {
                "input"->input(id,c);"read"->read(id,c);"result"->result(id,c);"comparison"->comparison(id,c)
                "choice"->choice(id,c);"allocation"->allocation(id,c);"alternatives"->alternatives(id,c);"quotes"->quotes(id,c)
                else->fail("Unknown fragment kind '${f.getString("kind")}'")
            }
        }
        val owned=mutableSetOf<String>();val commandViews=mutableListOf<String>();val commandIds=mutableSetOf<String>()
        val conflicts=mutableMapOf<String,MutableList<String>>()
        objects(input,"commands").forEach { c->
            closed(c,"id","label","targets");val id=name(c.getString("id"));if(id in specifications||!commandIds.add(id))fail("Duplicate command '$id'")
            val closure=linkedSetOf<String>()
            fun include(target:String) { if(target !in specifications)fail("Unknown command target '$target'");dependencies.getValue(target).forEach(::include);closure.add(target) }
            val targets=strings(c,"targets");if(targets.isEmpty()||targets.distinct().size!=targets.size)fail("Command requires distinct targets")
            targets.forEach(::include)
            // Local fragment initialization follows its prerequisite reads automatically; callers
            // name outcomes/resources, not hidden state-initialization steps.
            fun resources(target:String):Set<String> = dependencies.getValue(target).flatMap { resources(it) }.toSet() + if(steps[target].orEmpty().isNotEmpty())setOf(target)else emptySet()
            do {
                val size=closure.size
                ordered.filter { it !in closure && specifications.getValue(it).getString("kind") !in setOf("read","quotes") }.forEach { candidate->
                    val deps=dependencies.getValue(candidate)
                    if(deps.any { it in closure } && deps.flatMap { resources(it) }.all { it in closure })include(candidate)
                }
            }while(closure.size!=size)
            val writes=closure.filter { steps[it].orEmpty().isNotEmpty() }
            writes.forEach { if(!owned.add(it))fail("Resource '$it' belongs to multiple commands; use one shared command") }
            val sequence=ordered.filter { it in closure }.flatMap { steps[it].orEmpty() }
            if(sequence.none { it.getString("kind") in setOf("call","eachCall") })fail("Command '$id' has no external read")
            action(id,steps=sequence)
            closure.forEach { conflicts.getOrPut(it){mutableListOf()}.add("\$state.workflow${id}Pending") }
            val enabled=derive(id+"Enabled","boolean",true,node("not","\$state.workflow${id}Pending"))
            commandViews.add(view("PrimaryAction",mapOf("text" to literal(c.getString("label")),"enabled" to enabled),events=listOf(event("onClick",id))))
        }
        steps.filterValues { it.isNotEmpty() }.keys.forEach { if(it !in owned)fail("Resource '$it' is not attached to a command") }
        // Only controls in this command's dependency closure are conflicting. Independent
        // resources remain usable; captured/stale events are still fenced by generated updates.
        conflicts.forEach { (instance,pending)->
            val enabled=derive(instance+"Editable","boolean",true,node("not",pending.fold(literal(false)){a,b->node("or",a,b)}))
            views.getValue(instance).forEach { viewId->
                val v=(0 until plan.getJSONArray("views").length()).map { plan.getJSONArray("views").getJSONObject(it) }.single { it.getString("id")==viewId }
                if(v.getString("component") in setOf("IntField","TimeField","TextField","Option","SecondaryAction")) {
                    val props=v.getJSONArray("props")
                    val index=(0 until props.length()).firstOrNull { props.getJSONObject(it).getString("name")=="enabled" }
                    if(index!=null)props.getJSONObject(index).put("node",enabled)else props.put(JSONObject().put("name","enabled").put("node",enabled))
                }
            }
        }
        val display=strings(input,"display");if(display.distinct().size!=display.size)fail("Duplicate display instance")
        val children=display.flatMap { views[it]?:fail("Unknown display instance '$it'") }
        views.filterValues { it.isNotEmpty() }.keys.forEach { if(it !in display)fail("Visible fragment '$it' omitted from display") }
        view("Column",children=children+commandViews,id="root")
        return plan
    }
    private fun expr(o:JSONObject,key:String):String { val e=o.getJSONObject(key);closed(e,"expr");return e.getString("expr") }
    private fun input(id:String,c:JSONObject):List<String> {
        closed(c,"label","format","value","minimum","maximum")
        val format=c.getString("format");val v=c.get("value");val t=when(format){"text"->CapabilityType("string");"boolean"->CapabilityType("boolean");"integer","money","time"->CapabilityType("number");else->fail("Unknown input format $format")}
        if(t.kind=="number") {
            if(v !is Number||!v.toDouble().isFinite())fail("Finite numeric input required")
            val lo=c.getDouble("minimum");val hi=c.getDouble("maximum")
            if(!lo.isFinite()||!hi.isFinite()||lo>hi||v.toDouble() !in lo..hi)fail("Invalid input bounds")
            if(format!="number" && (lo!=kotlin.math.floor(lo)||hi!=kotlin.math.floor(hi)||lo<Int.MIN_VALUE||hi>Int.MAX_VALUE||v.toDouble()!=kotlin.math.floor(v.toDouble())))fail("Whole-unit input bounds required")
            if(format=="time" && (lo<0||hi>1439))fail("Time bounds must be minute-of-day")
        } else if(t.kind=="string" && v !is String || t.kind=="boolean" && v !is Boolean)fail("Input value type")
        val ref=state(id+"Value",t.kind,v);publish(id,"value",ref,t)
        var change="\$action.value"
        if(t.kind=="number") {
            var valid=all(listOf(node("ge",change,literal(c.getDouble("minimum"))),node("le",change,literal(c.getDouble("maximum")))))
            if(format!="number")valid=node("and",valid,node("eq",change,node("floor",change)))
            change=node("choose",valid,change,ref)
        }
        if(format=="boolean")change=node("not",ref)
        action(id+"Edit",mapOf(id+"Value" to change),parameters=mapOf("value" to if(format=="boolean")"string" else t.kind))
        val component=when(format){"text"->"TextField";"boolean"->"Toggle";"time"->"TimeField";else->"IntField"}
        val props=linkedMapOf((if(format=="boolean")"text" else "label") to literal(c.getString("label")),(if(format=="boolean")"checked" else "value") to ref)
        if(format!="boolean")props["enabled"]=yes
        if(t.kind=="number"&&format!="time") { props["minimum"]=literal(c.getDouble("minimum"));props["maximum"]=literal(c.getDouble("maximum"));props["format"]=literal(if(format=="money")"euro-cents" else "integer") }
        return listOf(view(component,props,events=listOf(event("onChange",id+"Edit",mapOf("value" to "\$payload")))))
    }
    private fun read(id:String,c:JSONObject):List<String> {
        closed(c,"binding","arguments")
        val b=c.getJSONObject("binding");val fn=binding(b);val args=objects(c,"arguments").map { closed(it,"expr");formula(it.getString("expr")) }
        if(args.size!=fn.parameters.size)fail("$id capability arity: expected ${fn.parameters.size}")
        val local=type(fn.result,id+"Result");val value=state(id+"Result",local,empty(fn.result));val loaded=state(id+"Present","boolean",false)
        val available=all(objects(c,"arguments").map { present(it.getString("expr")) })
        steps[id]=listOf(call(id+"Reply",b,args,id+"Result","\$${id}Reply",available),set(id+"Present","\$${id}ReplyPresent"))
        publish(id,"result",value,fn.result,loaded);publish(id,"present",loaded,CapabilityType("boolean"))
        return emptyList()
    }
    private fun result(id:String,c:JSONObject):List<String> {
        closed(c,"label","format","value")
        val text=expr(c,"value");val format=c.getString("format");val raw=formula(text)
        val type=if(format=="text")"string" else "number"
        val value=derive(id+"Value",type,if(type=="number")0 else "",raw);val valid=derive(id+"Present","boolean",false,present(text))
        publish(id,"value",value,CapabilityType(type),valid);publish(id,"present",valid,CapabilityType("boolean"))
        val formatted=when(format){"money"->return listOf(view("Money",mapOf("label" to literal(c.getString("label")),"minorUnits" to value),whenRef=valid));"time"->node("clock",value);"number"->node("numberText",value);"integer"->node("integer",value);"text"->value;else->fail("Unknown result format")}
        val display=if(format=="text")value else derive(id+"Text","string","",formatted)
        return listOf(view("Metric",mapOf("label" to literal(c.getString("label")),"value" to display),whenRef=valid))
    }
    private fun choice(id:String,c:JSONObject):List<String> {
        closed(c,"source","identity","label","eligible","cost")
        val source=expr(c,"source");val local=id+"Option"
        val ct=CapabilityType("record",fields=linkedMapOf("id" to CapabilityType("string"),"label" to CapabilityType("string"),"eligible" to CapabilityType("boolean"),"cost" to CapabilityType("number")))
        type(ct,local)
        val rows=state(id+"Rows",local+"[]",JSONArray());val selected=state(id+"Selected",local,empty(ct))
        val projected=node("project",formula(source),construct(local,mapOf("id" to formula(expr(c,"identity"),setOf("row")),"label" to formula(expr(c,"label"),setOf("row")),"eligible" to formula(expr(c,"eligible"),setOf("row")),"cost" to formula(expr(c,"cost"),setOf("row")))))
        val eligible=node("filter",rows,"\$row.eligible")
        steps[id]=listOf(set(id+"Rows",projected),set(id+"Selected",node("first",node("sort",eligible,params=JSONObject().put("field","cost")))))
        val valid=derive(id+"Present","boolean",false,all(listOf(present(source),"$selected.eligible",node("any",rows,node("eq","\$row.id","$selected.id")))))
        publish(id,"selected",selected,ct,valid);publish(id,"present",valid,CapabilityType("boolean"))
        action(id+"Select",mapOf(id+"Selected" to node("choose",node("any",rows,node("eq","\$row.id","\$action.id")),node("lookup",rows,"\$action.id",params=JSONObject().put("key","id")),selected)),parameters=mapOf("id" to "string"))
        return listOf(view("Option",mapOf("title" to "\$row.label","value" to "\$row.id","enabled" to yes),events=listOf(event("onSelect",id+"Select",mapOf("id" to "\$payload"))),each=rows,key="\$row.id"))
    }
    private fun allocation(id:String,c:JSONObject):List<String> {
        closed(c,"source","identity","label","cost","eligible","total","budget","surcharge","quotas")
        val source=expr(c,"source");val totalText=expr(c,"total");val budgetText=expr(c,"budget");val surchargeText=expr(c,"surcharge")
        val quotas=objects(c,"quotas");quotas.forEach { closed(it,"label","minimum","predicate") }
        val local=id+"Row";val fs=linkedMapOf("id" to "string","name" to "string","cost" to "number","quantity" to "number")
        quotas.indices.forEach { fs["quota$it"]="boolean" };record(local,fs)
        val rows=state(id+"Rows",local+"[]",JSONArray());val sourceReady=present(source)
        val fields=linkedMapOf("id" to formula(expr(c,"identity"),setOf("row")),"name" to formula(expr(c,"label"),setOf("row")),"cost" to formula(expr(c,"cost"),setOf("row")),"quantity" to zero)
        quotas.forEachIndexed { i,q->fields["quota$i"]=formula(expr(q,"predicate"),setOf("row")) }
        val filtered=node("filter",formula(source),formula(expr(c,"eligible"),setOf("row")))
        val projected=node("project",filtered,construct(local,fields))
        val requested=formula(totalText);val budget=formula(budgetText);val fee=formula(surchargeText)
        val quotaParams=JSONArray(quotas.mapIndexed { i,q->JSONObject().put("field","quota$i").put("minimum",formula(expr(q,"minimum"))) })
        val allocate=node("allocate",rows,requested,node("subtract",budget,fee),params=JSONObject().put("quantity","quantity").put("cost","cost").put("quotas",quotaParams))
        steps[id]=listOf(set(id+"Rows",projected),set(id+"Rows",allocate))
        val count=derive(id+"Count","number",0,node("sum",rows,"\$row.quantity"))
        val cost=derive(id+"Total","number",0,node("add",node("sum",rows,node("multiply","\$row.quantity","\$row.cost")),fee))
        val ready=derive(id+"Present","boolean",false,all(listOf(sourceReady,present(totalText),present(budgetText),present(surchargeText))+quotas.map { present(expr(it,"minimum")) }))
        val conditions=mutableListOf(ready,node("eq",count,requested),node("le",cost,budget))
        quotas.forEachIndexed { i,q->conditions.add(node("ge",node("sum",rows,node("choose","\$row.quota$i","\$row.quantity",zero)),formula(expr(q,"minimum")))) }
        val valid=derive(id+"Valid","boolean",false,all(conditions))
        val status=derive(id+"Status","string","",node("choose",sourceReady,node("choose",ready,node("choose",valid,literal("Ready"),literal("Not feasible")),literal("Not feasible: no eligible option")),literal("Refresh required")))
        val countText=derive(id+"CountText","string","",node("concat",node("integer",count),literal(" of "),node("integer",requested)))
        val quantityValid=all(listOf(node("eq","\$action.value",node("floor","\$action.value")),node("ge","\$action.value",zero),node("le","\$action.value",requested)))
        val updated=node("update",rows,node("eq","\$row.id","\$action.id"),params=JSONObject().put("fields",fields(mapOf("quantity" to "\$action.value"))))
        action(id+"Quantity",mapOf(id+"Rows" to node("choose",quantityValid,updated,rows)),parameters=mapOf("id" to "string","value" to "number"))
        action(id+"Allocate",mapOf(id+"Rows" to allocate))
        val rowType=CapabilityType("record",fields=fs.filterKeys { !it.startsWith("quota") }.mapValues { CapabilityType(it.value) })
        publish(id,"rows",rows,CapabilityType("array",element=rowType),sourceReady);publish(id,"total",cost,CapabilityType("number"),ready);publish(id,"valid",valid,CapabilityType("boolean"));publish(id,"present",ready,CapabilityType("boolean"))
        return listOf(view("SecondaryAction",mapOf("text" to literal("Recalculate allocation"),"enabled" to yes),events=listOf(event("onClick",id+"Allocate"))),
            view("IntField",mapOf("label" to "\$row.name","value" to "\$row.quantity","minimum" to zero,"maximum" to requested,"enabled" to yes),events=listOf(event("onChange",id+"Quantity",mapOf("id" to "\$row.id","value" to "\$payload"))),each=rows,key="\$row.id"),
            view("Comparison",mapOf("status" to status),listOf(view("Money",mapOf("label" to literal("Total"),"minorUnits" to cost)),view("Metric",mapOf("label" to literal("Quantity"),"value" to countText)))))
    }
    private fun alternatives(id:String,c:JSONObject):List<String> {
        closed(c,"source","identity","label","stock","components","roles")
        val source=expr(c,"source");val roles=objects(c,"roles");roles.forEach { closed(it,"label","quantity","predicate") }
        val component=c.getJSONObject("components");closed(component,"source","identity","quantity")
        val local=id+"Item";val componentType=id+"Component";record(componentType,mapOf("id" to "string","quantity" to "number"))
        val fs=linkedMapOf("id" to "string","label" to "string","capacity" to "number","quantity" to "number","components" to componentType+"[]")
        roles.indices.forEach { fs["role$it"]="boolean" };record(local,fs)
        val basketType=id+"Basket";record(basketType,linkedMapOf("items" to local+"[]","key" to "string","label" to "string"))
        val rows=state(id+"Rows",local+"[]",JSONArray());val baskets=state(id+"Baskets",basketType+"[]",JSONArray())
        val componentSource=expr(component,"source")
        val components=if(componentSource.isEmpty())node("array",params=JSONObject().put("element",componentType))else node("project",formula(componentSource,setOf("row")),construct(componentType,mapOf("id" to formula(expr(component,"identity"),setOf("row")),"quantity" to formula(expr(component,"quantity"),setOf("row")))))
        val values=linkedMapOf("id" to formula(expr(c,"identity"),setOf("row")),"label" to formula(expr(c,"label"),setOf("row")),"capacity" to formula(expr(c,"stock"),setOf("row")),"quantity" to zero,"components" to components)
        roles.forEachIndexed { i,r->values["role$i"]=formula(expr(r,"predicate"),setOf("row")) }
        val params=JSONArray(roles.mapIndexed { i,r->JSONObject().put("field","role$i").put("quantity",formula(expr(r,"quantity"))) })
        steps[id]=listOf(set(id+"Rows",node("project",formula(source),construct(local,values))),set(id+"Baskets",node("alternatives",rows,params=JSONObject().put("type",basketType).put("roles",params))))
        val ct=CapabilityType("record",fields=mapOf("id" to CapabilityType("string"),"quantity" to CapabilityType("number")))
        val item=CapabilityType("record",fields=fs.filterKeys { !it.startsWith("role") }.mapValues { (f,t)->if(f=="components")CapabilityType("array",element=ct)else CapabilityType(t) })
        val bt=CapabilityType("record",fields=linkedMapOf("items" to CapabilityType("array",element=item),"key" to CapabilityType("string"),"label" to CapabilityType("string")))
        publish(id,"baskets",baskets,CapabilityType("array",element=bt),present(source))
        return emptyList()
    }
    private fun quotes(id:String,c:JSONObject):List<String> {
        closed(c,"source","binding","arguments","cost","deposit","available","ttlMillis","budget")
        val source=expr(c,"source");val b=c.getJSONObject("binding");val fn=binding(b)
        val local=id+"Quote";val qt=CapabilityType("record",fields=linkedMapOf("key" to CapabilityType("string"),"label" to CapabilityType("string"),"cost" to CapabilityType("number"),"deposit" to CapabilityType("number"),"available" to CapabilityType("boolean"),"expiresAt" to CapabilityType("number")))
        type(qt,local)
        val results=state(id+"Results",local+"[]",JSONArray());val loaded=state(id+"Present","boolean",false);val fresh=state(id+"Fresh","boolean",false);val now=state(id+"Now","number",0)
        val args=objects(c,"arguments").mapIndexed { i,a->
            when(a.getString("kind")) {
                "value"->{closed(a,"kind","value");formula(expr(a,"value"),setOf("item"))}
                "basket"->{closed(a,"kind","encoding","idField","quantityField")
                    when(a.getString("encoding")) {
                        "idQuantity"->{if(fn.parameters.getOrNull(i)?.type?.kind!="string")fail("$id basket text requires string parameter");node("basket","\$item.items",params=JSONObject().put("id","id").put("quantity","quantity"))}
                        "records"->{val t=fn.parameters.getOrNull(i)?.type?:fail("$id argument arity");val row=t.element?:fail("Basket records require array parameter")
                            if(row.fields.keys!=setOf(a.getString("idField"),a.getString("quantityField")))fail("Basket record parameter fields")
                            val n=id+"Argument$i";type(row,n)
                            node("project","\$item.items",construct(n,mapOf(a.getString("idField") to "\$row.id",a.getString("quantityField") to "\$row.quantity")))
                        }
                        else->fail("Unknown basket encoding")
                    }
                }
                else->fail("Unknown quote argument kind")
            }
        }
        if(args.size!=fn.parameters.size)fail("$id quote arity")
        val reply=id+"Reply"
        // Formula reply scope is bound to the actual typed provider response, not a schema guessed by the model.
        fun replaceReply(ref:String):String = if(ref.startsWith("\$reply"))"\$$reply"+ref.removePrefix("\$reply") else ref
        fun quoteFormula(key:String)=MaintainedFormula.lower(MaintainedFormula.parse(expr(c,key)),{ p->if(p.startsWith("reply."))replaceReply("$$p")else port(p).ref },::literal,{ op,x->node(op,*x.toTypedArray()) })
        val ttl=quoteFormula("ttlMillis");val expiry=node("add",node("now"),ttl)
        val projection=construct(local,mapOf("key" to "\$item.key","label" to "\$item.label","cost" to quoteFormula("cost"),"deposit" to quoteFormula("deposit"),"available" to all(listOf(quoteFormula("available"),node("gt",ttl,zero),node("eq",node("subtract",ttl,ttl),zero),node("eq",node("subtract",expiry,expiry),zero))),"expiresAt" to expiry))
        val each=call(reply,b,args,id+"Results",projection,present(source)).put("kind","eachCall").put("each",formula(source))
        steps[id]=listOf(each,set(id+"Now",node("now")),set(id+"Present",present(source)),set(id+"Fresh",yes))
        val budget=formula(expr(c,"budget"))
        val valid=all(listOf(loaded,fresh,"\$row.available",node("gt","\$row.expiresAt",now)))
        val current=derive(id+"Current",local+"[]",JSONArray(),node("filter",results,valid))
        val status=derive(id+"Status","string","",node("choose",all(listOf(loaded,fresh)),node("choose",node("gt",node("length",current),zero),node("choose",node("any",current,node("le","\$row.cost",budget)),literal("Within budget"),literal("Over budget")),literal("No usable quote")),literal("Refresh required")))
        val absolute=node("abs","\$action.epoch");action(id+"Tick",mapOf(id+"Now" to absolute,id+"Fresh" to all(listOf(fresh,node("ge","\$action.epoch",zero),node("ge",absolute,now)))),parameters=mapOf("epoch" to "number"))
        publish(id,"results",results,CapabilityType("array",element=qt),loaded)
        val children=listOf(view("Money",mapOf("label" to literal("Hire"),"minorUnits" to "\$row.cost")),view("Money",mapOf("label" to literal("Refundable deposit"),"minorUnits" to "\$row.deposit")))
        val within=derive(id+"Within",local+"[]",JSONArray(),node("filter",current,node("le","\$row.cost",budget)))
        return listOf(view("SessionClock",events=listOf(event("onTick",id+"Tick",mapOf("epoch" to "\$payload")))),view("Notice",mapOf("text" to status)),view("Item",mapOf("title" to "\$row.label"),children,each=current,key="\$row.key"))
    }
    private fun comparison(id:String,c:JSONObject):List<String> {
        closed(c,"limit","actual","label")
        val limit=expr(c,"limit");val actual=expr(c,"actual")
        val delta=derive(id+"Difference","number",0,node("subtract",formula(actual),formula(limit)))
        val magnitude=derive(id+"Magnitude","number",0,node("abs",delta))
        val status=derive(id+"Status","string","",node("choose",node("gt",delta,zero),literal("Over budget"),node("choose",node("lt",delta,zero),literal("Under budget"),literal("Equal"))))
        val valid=derive(id+"Present","boolean",false,all(listOf(present(limit),present(actual))))
        publish(id,"difference",delta,CapabilityType("number"),valid);publish(id,"present",valid,CapabilityType("boolean"))
        return listOf(view("Comparison",mapOf("status" to status),listOf(view("Money",mapOf("label" to literal(c.getString("label")),"minorUnits" to magnitude))),whenRef=valid))
    }
}
