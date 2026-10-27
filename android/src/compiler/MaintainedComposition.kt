package dev.deal.embedding

import dev.deal.embedding.capabilities.*
import org.json.*

/** Maintained state/handler/view fragments. This construction runs once, before canonical source exists.
 * No capability is invoked here; bindings select only explicitly disclosed declarations. */
internal class MaintainedComposition(private val contracts: List<CapabilityContract>) {
    private val plan=JSONObject().put("records",JSONArray()).put("state",JSONArray()).put("nodes",JSONArray()).put("derived",JSONArray()).put("controls",JSONArray()).put("actions",JSONArray()).put("views",JSONArray()).put("root","root")
    private var serial=0
    private fun reject(s:String):Nothing=throw BehaviorCandidate.Rejected(s)
    private fun closed(o:JSONObject,vararg fields:String) { if(o.keys().asSequence().toSet()!=fields.toSet())reject("Expected ${fields.joinToString()}") }
    private fun list(o:JSONObject,k:String):List<JSONObject> {
        val a=o.opt(k) as? JSONArray ?: reject("$k must be a JSON array, not a JSON-encoded string; submit structured tool arguments")
        return (0 until a.length()).map{a.getJSONObject(it)}
    }
    private fun add(k:String,v:JSONObject){plan.getJSONArray(k).put(v)}
    private fun id()="fragment${serial++}"
    private fun node(op:String,vararg args:String,params:JSONObject=JSONObject()):String {val id=id();add("nodes",JSONObject().put("id",id).put("op",op).put("args",JSONArray(args.toList())).put("params",params));return id}
    private fun jsonValue(v:Any):String=when(v){is String->JSONObject.quote(v);is Number,is Boolean,is JSONObject,is JSONArray->v.toString();else->reject("Unsupported literal data")}
    private fun lit(v:Any):String=node("literal",params=JSONObject().put("type",when(v){is Boolean->"boolean";is Number->"number";else->"string"}).put("value",jsonValue(v)))
    private fun formula(source:String,ports:Map<String,String>):String = MaintainedFormula.lower(
        MaintainedFormula.parse(source),
        { path -> ports[path] ?: reject("Formula reads undeclared port '$path'") },
        ::lit, { op,args -> node(op,*args.toTypedArray()) })
    private fun state(n:String,t:String,v:Any):String {add("state",JSONObject().put("name",n).put("type",t).put("initial",jsonValue(v)));return "\$state.$n"}
    private fun derived(n:String,t:String,v:Any,expr:String):String {val ref=state(n,t,v);add("derived",JSONObject().put("field",n).put("node",expr));return ref}
    private fun record(n:String,fields:Map<String,String>){add("records",JSONObject().put("name",n).put("fields",JSONArray(fields.map{JSONObject().put("name",it.key).put("type",it.value)})))}
    private fun bindings(v:Map<String,String>)=JSONArray(v.map{JSONObject().put("field",it.key).put("node",it.value)})
    private fun construct(t:String,v:Map<String,String>)=node("record",params=JSONObject().put("type",t).put("fields",bindings(v)))
    private fun set(f:String,n:String)=JSONObject().put("kind","set").put("bind",f).put("node",n)
    private fun call(id:String,b:JSONObject,args:List<String>,field:String,projection:String,whenRef:String="")=JSONObject().put("kind","call").put("id",id).put("module",b.getString("module")).put("function",b.getString("function")).put("args",JSONArray(args)).put("bind",field).put("node",projection).put("when",whenRef)
    private fun action(n:String,steps:List<JSONObject> = emptyList(),updates:Map<String,String> = emptyMap(),fields:Map<String,String> = emptyMap()) {add("actions",JSONObject().put("id",n).put("fields",JSONArray(fields.map{JSONObject().put("name",it.key).put("type",it.value)})).put("updates",bindings(updates)).put("steps",JSONArray(steps)))}
    private fun view(component:String,props:Map<String,String> = emptyMap(),children:List<String> = emptyList(),events:List<JSONObject> = emptyList(),each:String="",key:String="",whenRef:String="",name:String=id()):String {add("views",JSONObject().put("id",name).put("component",component).put("props",JSONArray(props.map{JSONObject().put("name",it.key).put("node",it.value)})).put("children",JSONArray(children)).put("events",JSONArray(events)).put("each",each).put("key",key).put("when",whenRef));return name}
    private fun event(n:String,a:String,payload:Map<String,String> = emptyMap())=JSONObject().put("name",n).put("action",a).put("payload",bindings(payload))
    private fun control(n:String,label:String,widget:String,min:Int,max:Int,step:Int=1,invalidate:List<String> = emptyList()) {add("controls",JSONObject().put("field",n).put("label",label).put("widget",widget).put("minimum",min).put("maximum",max).put("step",step).put("invalidate",JSONArray(invalidate)))}
    private fun binding(b:JSONObject):CapabilityFunction {closed(b,"module","function");return contracts.singleOrNull{it.module==b.getString("module")}?.functions?.singleOrNull{it.name==b.getString("function")}?:reject("Undisclosed capability binding")}
    private fun numberInput(o:JSONObject,n:String,label:String,widget:String="integer",min:Int=0,max:Int=Int.MAX_VALUE,invalidate:List<String> = emptyList()):String {val v=(o.get(n) as? Number ?: reject("Numeric input required: $n"));val d=v.toDouble();if(!d.isFinite()||d!=kotlin.math.floor(d)||d<min||d>max)reject("Invalid whole-unit input $n");val r=state(n,"number",v);control(n,label,widget,min,max,invalidate=invalidate);return r}
    fun expand(input:JSONObject):JSONObject {
        val p=if(input.has("kind")) {
            val b=JSONObject(input.toString());val title=b.remove("title") as? String ?: reject("Title required")
            JSONObject().put("title",title).put("behaviors",JSONArray().put(b))
        } else input
        closed(p,"title","behaviors")
        if(p.getString("title").isBlank())reject("Screen title is required")
        val behaviors=list(p,"behaviors");if(behaviors.size!=1)reject("Compose exactly one complete behavior per screen")
        val b=behaviors.single();val children=mutableListOf<String>()
        children.addAll(when(b.getString("kind")){"comparison"->comparison(b);"providerSchedule"->schedule(b);"allocation"->allocation(b);"quotedAlternatives"->quoted(b);else->reject("Unsupported maintained behavior ${b.getString("kind")}")})
        view("Column",children=children,name="root");return plan
    }
    private fun comparison(b:JSONObject):List<String> {
        closed(b,"kind","limit","actual","limitLabel","actualLabel")
        val limit=numberInput(b,"limit",b.getString("limitLabel"),"money");val actual=numberInput(b,"actual",b.getString("actualLabel"),"money")
        val delta=derived("difference","number",0,formula("actual - limit",mapOf("actual" to actual,"limit" to limit)));val zero=lit(0)
        val status=derived("status","string","",node("choose",node("gt",delta,zero),lit("Over budget"),node("choose",node("lt",delta,zero),lit("Under budget"),lit("Equal"))))
        val magnitude=derived("magnitude","number",0,node("abs",delta))
        return listOf(view("Comparison",mapOf("status" to status),listOf(view("Money",mapOf("label" to lit("Difference"),"minorUnits" to magnitude)))))
    }
    private fun schedule(b:JSONObject):List<String> {
        closed(b,"kind","start","input","before","inspection","estimate")
        val cap=b.getJSONObject("estimate");val fn=binding(cap);if(fn.parameters.size!=1||fn.parameters.single().type.kind!="number"||fn.result.kind!="number")reject("Schedule estimate requires number -> number")
        val loaded=state("loaded","boolean",false);val start=numberInput(b,"start","Start","time",max=1439);val input=numberInput(b,"input",fn.parameters.single().name,"integer",1,Int.MAX_VALUE,listOf("loaded"));val before=numberInput(b,"before","Before-start buffer (minutes)",max=1439);val inspection=numberInput(b,"inspection","Inspection minutes",max=1439);val travel=state("travel","number",0)
        val leave=derived("leave","number",0,formula("start - before - travel",mapOf("start" to start,"before" to before,"travel" to travel)));val check=derived("inspectionTime","number",0,formula("leave - inspection",mapOf("leave" to leave,"inspection" to inspection)))
        val leaveText=derived("leaveText","string","",node("clock",leave));val inspectText=derived("inspectText","string","",node("clock",check))
        action("Load",listOf(call("estimate",cap,listOf(input),"travel","\$estimate"),set("loaded",lit(true))))
        return listOf(view("PrimaryAction",mapOf("text" to lit("Get travel estimate")),events=listOf(event("onClick","Load"))),view("Metric",mapOf("label" to lit("Depart by"),"value" to leaveText),whenRef=loaded),view("Metric",mapOf("label" to lit("Inspection"),"value" to inspectText),whenRef=loaded))
    }
    private fun quoted(b:JSONObject):List<String> {
        closed(b,"kind","budget","from","until","catalogue","quote","fields","groups")
        val catalogue=b.getJSONObject("catalogue");val quote=b.getJSONObject("quote");val cf=binding(catalogue);val qf=binding(quote)
        if(cf.parameters.map{it.type.kind}!=listOf("string","string")||cf.result.kind!="array"||qf.parameters.map{it.type.kind}!=listOf("string","string","string")||qf.result.kind!="record")reject("Quoted alternatives require period catalogue and whole-basket quote bindings")
        val f=b.getJSONObject("fields");closed(f,"id","name","cost","stock","components","componentId","componentQuantity","quoteCost","deposit","available","ttl")
        val encodedGroups=b.get("groups") as? String ?: reject("groups must contain JSON-encoded requirement data")
        val parser=JSONTokener(encodedGroups);val groupArray=parser.nextValue() as? JSONArray ?: reject("groups must encode an array")
        if(parser.nextClean()!='\u0000')reject("Trailing requirement group data")
        val groups=(0 until groupArray.length()).map{groupArray.getJSONObject(it)};if(groups.size!=2)reject("Quoted alternatives currently require two explicit requirement groups")
        record("Component",linkedMapOf("id" to "string","quantity" to "number"))
        // Keep disclosed attributes structurally typed, without model-authored nominal projections.
        val item=cf.result.element ?: reject("Catalogue array required")
        if(item.kind!="record"||item.optionalFields.isNotEmpty())reject("Catalogue items require complete structured fields")
        fun localType(t:CapabilityType,n:String):String=when(t.kind){"array"->localType(t.element!!,n+"Row")+"[]";"record"->{record(n,t.fields.mapValues{(key,value)->localType(value,n+key.replaceFirstChar{it.uppercase()})});n};else->t.kind}
        val attributes=item.fields["attributes"] ?: reject("Catalogue needs structured attributes")
        if(attributes.kind!="record")reject("Catalogue attributes must be a record")
        val at=localType(attributes,"Attributes")
        record("Item",linkedMapOf("id" to "string","name" to "string","cents" to "number","stock" to "number","attributes" to at,"components" to "Component[]","quantity" to "number"))
        record("Basket",linkedMapOf("items" to "Item[]","label" to "string"))
        record("Quote",linkedMapOf("cents" to "number","deposit" to "number","available" to "boolean","expiresAt" to "number","label" to "string"))
        fun projectType(t:CapabilityType,n:String,ref:String):String=when(t.kind){"record"->construct(n,t.fields.mapValues{(key,value)->projectType(value,n+key.replaceFirstChar{it.uppercase()},ref+"."+key)});"array"->node("project",ref,projectType(t.element!!,n+"Row","\$row"));else->ref}
        val rows=state("rows","Item[]",JSONArray());val packages=state("packages","Basket[]",JSONArray());val separate=state("separate","Basket[]",JSONArray());val pq=state("packageQuotes","Quote[]",JSONArray());val sq=state("separateQuotes","Quote[]",JSONArray());val loaded=state("loaded","boolean",false);val fresh=state("fresh","boolean",false);val now=state("now","number",0)
        val budget=numberInput(b,"budget","Hire budget","money");val from=state("from","string",b.getString("from"));val until=state("until","string",b.getString("until"));val one=lit(1);val zero=lit(0)
        val components=node("project","\$row.${f.getString("components")}",construct("Component",mapOf("id" to "\$row.${f.getString("componentId")}","quantity" to "\$row.${f.getString("componentQuantity")}")))
        val projected=node("project","\$catalogue",construct("Item",linkedMapOf("id" to "\$row.${f.getString("id")}","name" to "\$row.${f.getString("name")}","cents" to "\$row.${f.getString("cost")}","stock" to "\$row.${f.getString("stock")}","attributes" to projectType(attributes,at,"\$row.attributes"),"components" to components,"quantity" to one)))
        fun predicate(g:JSONObject):String {
            closed(g,"label","constraints")
            if(g.getString("label").isBlank()||list(g,"constraints").isEmpty())reject("Requirement groups need a label and explicit constraints")
            var result=lit(true)
            for(c in list(g,"constraints")){
                closed(c,"field","operator","valueType","text","number","boolean")
                val path=c.getString("field");if(!path.matches(Regex("[A-Za-z][A-Za-z0-9_.]*")))reject("Invalid attribute path")
                val op=c.getString("operator");if(op !in setOf("eq","ge","le"))reject("Unsupported constraint operator")
                val value:Any=when(c.getString("valueType")){"string"->c.getString("text");"number"->c.get("number") as? Number ?: reject("Numeric constraint required");"boolean"->c.getBoolean("boolean");else->reject("Unknown constraint value type")}
                result=node("and",result,node(op,"\$row.attributes.$path",lit(value)))
            }
            return result
        }
        val predicates=groups.map{predicate(it)}
        val availableRows=node("filter",rows,node("ge","\$row.stock",one))
        val left=node("filter",availableRows,predicates[0]);val right=node("filter",availableRows,predicates[1])
        val pair=construct("Basket",mapOf("items" to node("array","\$left","\$right",params=JSONObject().put("element","Item")),"label" to lit("Separate hire")))
        val pairs=node("join",left,right,node("ne","\$left.id","\$right.id"),pair)
        val packageRows=node("filter",availableRows,node("gt",node("length","\$row.components"),zero))
        val covering=node("covers",packageRows,rows,params=JSONObject().put("components","components").put("key","id").put("itemId","id").put("quantity","quantity").put("groups",JSONArray(predicates.map{JSONObject().put("predicate",it).put("minimum",one)})))
        val baskets=node("project",covering,construct("Basket",mapOf("items" to node("array","\$row",params=JSONObject().put("element","Item")),"label" to "\$row.name")))
        fun quoteEach(id:String,source:String,target:String):JSONObject {
            val items=node("basket","\$item.items",params=JSONObject().put("id","id").put("quantity","quantity"))
            val receipt=node("now")
            // nowMillis is second-resolution: adding TTL to the floor is conservative, never extends trust.
            val expires=node("add",receipt,node("multiply","\$$id.${f.getString("ttl")}",lit(60000)))
            val result=construct("Quote",mapOf("cents" to "\$$id.${f.getString("quoteCost")}","deposit" to "\$$id.${f.getString("deposit")}","available" to "\$$id.${f.getString("available")}","expiresAt" to expires,"label" to "\$item.label"))
            return call(id,quote,listOf(items,from,until),target,result).put("kind","eachCall").put("each",source)
        }
        action("Load",listOf(call("catalogue",catalogue,listOf(from,until),"rows",projected),set("packages",baskets),set("separate",pairs),quoteEach("packageReply",packages,"packageQuotes"),quoteEach("separateReply",separate,"separateQuotes"),set("now",node("now")),set("loaded",lit(true)),set("fresh",lit(true))),updates=mapOf("packageQuotes" to node("array",params=JSONObject().put("element","Quote")),"separateQuotes" to node("array",params=JSONObject().put("element","Quote"))))
        val zeroQuote=JSONObject().put("cents",0).put("deposit",0).put("available",false).put("expiresAt",0).put("label","")
        fun best(source:String,n:String):String {
            val eligible=node("filter",source,node("and","\$row.available",node("gt","\$row.expiresAt",now)))
            return derived(n,"Quote",zeroQuote,node("first",node("sort",eligible,params=JSONObject().put("field","cents"))))
        }
        val packageBest=best(pq,"packageBest");val separateBest=best(sq,"separateBest")
        val trusted=derived("trusted","boolean",false,node("and",loaded,fresh))
        val any=node("or","$packageBest.available","$separateBest.available")
        val within=node("or",node("and","$packageBest.available",node("le","$packageBest.cents",budget)),node("and","$separateBest.available",node("le","$separateBest.cents",budget)))
        val status=derived("status","string","",node("choose",trusted,node("choose",any,node("choose",within,lit("Within budget"),lit("Over budget")),lit("No usable quote")),lit("Refresh required")))
        val absolute=node("abs","\$action.epoch");val validClock=node("and",node("ge","\$action.epoch",zero),node("ge",absolute,now))
        action("Tick",updates=mapOf("now" to absolute,"fresh" to node("and",fresh,validClock)),fields=mapOf("epoch" to "number"))
        val clock=view("SessionClock",events=listOf(event("onTick","Tick",mapOf("epoch" to "\$payload"))))
        fun money(label:String,ref:String)=view("Money",mapOf("label" to lit(label),"minorUnits" to ref))
        return listOf(clock,view("PrimaryAction",mapOf("text" to lit("Read equipment and quote alternatives")),events=listOf(event("onClick","Load"))),view("Comparison",mapOf("status" to status),listOf(money("Package hire","$packageBest.cents"),money("Separate hire","$separateBest.cents"),money("Refundable package deposit","$packageBest.deposit"),money("Refundable separate deposit","$separateBest.deposit"))))
    }
    private fun allocation(b:JSONObject):List<String> {
        closed(b,"kind","total","minimum","budget","size","deadline","menu","delivery","fields")
        val menu=b.getJSONObject("menu");val delivery=b.getJSONObject("delivery");val mf=binding(menu);val df=binding(delivery)
        if(mf.parameters.isNotEmpty()||mf.result.kind!="array"||df.parameters.size!=1||df.parameters.single().type.kind!="string"||df.result.kind!="array")reject("Allocation requires a no-argument catalogue and string-deadline delivery binding")
        val f=b.getJSONObject("fields");closed(f,"id","name","cost","category","size","windowId","windowStart","windowEnd","eligible","deliveryCost")
        fun m(n:String)="\$row.${f.getString(n)}"
        record("AllocationRow",linkedMapOf("id" to "string","name" to "string","cents" to "number","category" to "boolean","quantity" to "number"));record("Choice",linkedMapOf("id" to "string","start" to "string","end" to "string","eligible" to "boolean","cents" to "number"))
        val rows=state("rows","AllocationRow[]",JSONArray());val windows=state("windows","Choice[]",JSONArray());val selected=state("selected","Choice",JSONObject().put("id","").put("start","").put("end","").put("eligible",false).put("cents",0));val loaded=state("loaded","boolean",false)
        val total=numberInput(b,"total","Required quantity",max=32);val minimum=numberInput(b,"minimum","Minimum category",max=32);val budget=numberInput(b,"budget","Budget","money");val deadline=numberInput(b,"deadline","Required by","time",max=1439,invalidate=listOf("loaded"))
        val zero=lit(0);val menuRows=node("filter","\$menu",node("eq",m("size"),lit((b.get("size") as? Number ?: reject("Numeric size required")))))
        val projection=node("project",menuRows,construct("AllocationRow",linkedMapOf("id" to m("id"),"name" to m("name"),"cents" to m("cost"),"category" to m("category"),"quantity" to zero)))
        val choices=node("project","\$delivery",construct("Choice",linkedMapOf("id" to m("windowId"),"start" to m("windowStart"),"end" to m("windowEnd"),"eligible" to m("eligible"),"cents" to m("deliveryCost"))))
        val eligible=node("filter",windows,"\$row.eligible");val first=node("first",node("sort",eligible,params=JSONObject().put("field","cents")))
        val allocate=node("allocate",rows,total,node("subtract",budget,"\$state.selected.cents"),params=JSONObject().put("quantity","quantity").put("cost","cents").put("quotas",JSONArray().put(JSONObject().put("field","category").put("minimum",minimum))))
        action("Load",listOf(call("menu",menu,emptyList(),"rows",projection),call("delivery",delivery,listOf(node("clock",deadline)),"windows",choices),set("selected",first),set("rows",allocate),set("loaded",lit(true))))
        val count=derived("count","number",0,node("sum",rows,"\$row.quantity"));val category=derived("categoryCount","number",0,node("sum",rows,node("choose","\$row.category","\$row.quantity",zero)));val cost=derived("cost","number",0,node("add",node("sum",rows,node("multiply","\$row.quantity","\$row.cents")),"\$state.selected.cents"))
        val valid=derived("valid","boolean",false,node("and",loaded,node("and","\$state.selected.eligible",node("and",node("eq",count,total),node("and",node("ge",category,minimum),node("le",cost,budget))))))
        val status=derived("status","string","",node("choose",valid,lit("Ready"),lit("Not feasible")))
        val countText=derived("countText","string","",node("concat",node("integer",count),lit(" of "),node("integer",total)))
        val validQuantity=node("and",node("eq","\$action.value",node("floor","\$action.value")),node("and",node("ge","\$action.value",zero),node("le","\$action.value",lit(32))))
        val updated=node("update",rows,node("eq","\$row.id","\$action.id"),params=JSONObject().put("fields",bindings(mapOf("quantity" to "\$action.value"))))
        action("Quantity",updates=mapOf("rows" to node("choose",validQuantity,updated,rows)),fields=mapOf("id" to "string","value" to "number"));action("Select",updates=mapOf("selected" to node("lookup",windows,"\$action.id",params=JSONObject().put("key","id"))),fields=mapOf("id" to "string"));action("Allocate",updates=mapOf("rows" to allocate))
        val editable=derived("editable","boolean",true,node("not","\$state.loading"))
        val qty=view("IntField",mapOf("label" to "\$row.name","value" to "\$row.quantity","minimum" to zero,"maximum" to lit(32),"enabled" to editable),events=listOf(event("onChange","Quantity",mapOf("id" to "\$row.id","value" to "\$payload"))))
        return listOf(view("PrimaryAction",mapOf("text" to lit("Load menu and delivery")),events=listOf(event("onClick","Load"))),view("SecondaryAction",mapOf("text" to lit("Recalculate allocation")),events=listOf(event("onClick","Allocate"))),view("Item",mapOf("title" to "\$row.name"),listOf(qty),each=rows,key="\$row.id"),view("Option",mapOf("title" to "\$row.end","value" to "\$row.id","enabled" to editable),events=listOf(event("onSelect","Select",mapOf("id" to "\$payload"))),each=windows,key="\$row.id"),view("Comparison",mapOf("status" to status),listOf(view("Money",mapOf("label" to lit("Total"),"minorUnits" to cost)),view("Metric",mapOf("label" to lit("Quantity"),"value" to countText)))))
    }
}
