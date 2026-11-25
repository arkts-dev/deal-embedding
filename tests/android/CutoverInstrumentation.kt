package dev.deal.cutover.tests

import android.app.Instrumentation
import android.os.Bundle
import dev.deal.embedding.*
import org.json.*

/** Actual packaged construction/compiler/Session. Inference inputs are explicit fixtures. */
class CutoverInstrumentation : Instrumentation() {
    private var scenarioInput:String?=null
    private var replaySource=false
    private var providersOnly=false
    private var targetsOnly=false
    private var imagesOnly=false
    private var tabsOnly=false
    private var timerOnly=false
    private var constructionOnly=false
    private var constructionMode:String?=null
    private var storageOnly=false
    override fun onCreate(arguments: Bundle?) { constructionMode=arguments?.getString("mode");constructionOnly=arguments?.getString("construction")=="true";storageOnly=arguments?.getString("storage")=="true";timerOnly=arguments?.getString("timer")=="true";targetsOnly=arguments?.getString("targets")=="true";providersOnly=arguments?.getString("providers")=="true";tabsOnly=arguments?.getString("tabs")=="true";scenarioInput=arguments?.getString("scenarios");replaySource=arguments?.getString("replay")=="true";imagesOnly=arguments?.getString("images")=="true";super.onCreate(arguments); start() }
    override fun onStart() {
        val result=Bundle()
        try {
            if(timerOnly){result.putString("result",checkTimerPainter(this));finish(-1,result);return}
            if(targetsOnly){result.putString("result",checkTargetTransport(targetContext));finish(-1,result);return}
            if(imagesOnly){result.putString("result",checkImageRendering(this,targetContext));finish(-1,result);return}
            if(tabsOnly){result.putString("result",checkTabsRendering(this));finish(-1,result);return}
            checkBudgetAssertions()
            checkJourneyObservation()
            scenarioInput?.let { runScenarios(it,result);return }
            fun answer(id:String,selected:String,options:List<String>)=JSONObject().put("id",id).put("selected",selected).put("probabilities",JSONArray(options.map { JSONObject().put("id",it).put("p",if(it==selected)1 else 0) }))
            fun questions(input:String)=JSONArray(input.substringAfter("\nISSUED QUESTIONS\n"))
            fun actions(tree:JSONObject):List<Pair<Int,String>> { val out=mutableListOf<Pair<Int,String>>();fun visit(node:JSONObject){val props=node.getJSONArray("props");for(i in 0 until props.length()){val prop=props.getJSONObject(i);if(prop.optString("kind")=="action")out.add(prop.getInt("actionSlot") to node.optString("component"))};val children=node.getJSONArray("children");for(i in 0 until children.length())visit(children.getJSONObject(i))};visit(tree);return out }
            val ordinary="""{"title":"Double calculation","inputs":[{"id":"value","label":"Value","kind":"count","measure":"count","value":3,"min":0,"max":99}],"constants":[],"requirements":[{"id":"retain","text":"Double the edited value","sources":["request"],"needs":["calculate"]}],"needs":[{"id":"calculate","type":"figure","words":"Double the edited value","reads":["value"],"spec":{"label":"Double","format":"count","unit":"","formula":"value * 2","example":{"inputs":{"value":3},"result":6}}}],"questions":[]}"""
            val catalogue="""{"title":"Checklist","inputs":[],"constants":[],"requirements":[{"id":"retain","text":"Toggle items and summarize remaining","sources":["request"],"needs":["items","summary"]}],"needs":[{"id":"items","type":"content","words":"Toggle disclosed items","quote":"","reads":[],"spec":{}},{"id":"summary","type":"content","words":"Completion and remaining counts","reads":["items"],"spec":{}}],"questions":[]}"""
            for(mode in if(providersOnly || storageOnly)emptyList() else (constructionMode?.let { require(it in listOf("ordinary","catalogue","mixed","partial","syntax-recovery","gap-reader","money","time","lazy-formula"));listOf(it) } ?: listOf("ordinary","catalogue","mixed","partial","syntax-recovery","gap-reader","money","time","lazy-formula"))) {
                val stages=mutableListOf<String>()
                val plan=JSONObject(if(mode=="catalogue")catalogue else ordinary)
                if(mode=="lazy-formula") {
                    plan.getJSONArray("needs").getJSONObject(0).getJSONObject("spec").put("formula","choose(value >= 0, round(value * 2), 1 / 0)")
                }
                if(mode=="time") {
                    plan.getJSONArray("needs").getJSONObject(0).getJSONObject("spec").put("format","time_of_day")
                }
                if(mode=="money") {
                    plan.getJSONArray("inputs").getJSONObject(0).put("kind","amount").put("measure","money").put("unit","EUR cents").remove("max")
                    plan.getJSONArray("needs").getJSONObject(0).getJSONObject("spec").put("format","money").put("unit","EUR cents")
                }
                if(mode=="mixed" || mode=="partial" || mode=="syntax-recovery" || mode=="gap-reader")plan.getJSONArray("needs").put(JSONObject().put("id","extra").put("type","other").put("words","Retain extra editable text").put("reads",JSONArray(if(mode=="gap-reader")listOf("calculate") else emptyList<String>())).put("spec",JSONObject()))
                val config=EmbeddingConfig("v08-construction-$mode",GenerationGatewayConfig("http://127.0.0.1:1/generate","fixture"),inference=InferenceProvider { request,cancellation ->
                    cancellation.check();stages.add(request.stage);check(request.operation.isNotBlank())
                    when(request.stage) {
                        "planning"->{check(request.diagnostics.isBlank()){"$mode planning: ${request.diagnostics}"};plan.toString()}
                        "selection-first","selection-second","alignment"->{val issued=questions(request.input);val answers=JSONArray();for(i in 0 until issued.length()){val q=issued.getJSONObject(i);val id=q.getString("id");val opts=q.getJSONArray("options");val options=(0 until opts.length()).map{opts.getJSONObject(it).getString("id")};val selected=when{id.startsWith("ownership_")->"authored";id.startsWith("formula_")->"formula";id.startsWith("units_")->"preserved";id=="calculate"->"part_calculate";id=="items"->"cat_items_checklist";id=="align_summary"->"cat_summary_list_summary";else->"unavailable"};answers.put(answer(id,selected,options))};JSONObject().put("answers",answers).toString()}
                        "content"->"""{"sections":[{"need":"items","parameters":{"title":"Disclosed checklist 😀","items":[{"id":"first","label":"First item"},{"id":"second","label":"Second item","done":true}]}},{"need":"summary","parameters":{}}]}"""
                        "coverage"->{val issued=questions(request.input);JSONObject().put("answers",JSONArray((0 until issued.length()).map {answer(issued.getJSONObject(it).getString("id"),"covered",listOf("covered","missing"))})).toString()}
                        "custom-section"->{
                            check(request.maxTokens==6000)
                            if(mode=="syntax-recovery" && stages.count{it=="custom-section"}==1) {
                                return@InferenceProvider """<classes></classes><fields></fields><functions></functions><view>import * as app from "./experience"; import * as ui from "./platform.dealui-pack"; export view Panel(state: app.AppState): View {ui.Text(text: "broken"}</view>"""
                            }
                            if(mode=="syntax-recovery")check(request.diagnostics.contains("Expected ')'") && request.previous.contains("broken")){"View syntax failure did not reach bounded repair: ${request.diagnostics}"}
                            if(mode=="gap-reader") {
                                check(request.input.contains("Issued read-only ports"))
                                return@InferenceProvider """<classes></classes><fields></fields><functions></functions><view>import * as app from "./experience"; import * as ui from "./platform.dealui-pack"; export view Panel(state: app.AppState): View {ui.Column() {When(state.calculate_ready) {ui.Text(text:"Borrowed result") When(state.calculate_display > 10) {ui.Text(text:"Above ten")}}}}</view>"""
                            }
                            if(mode=="partial")"" else """<classes>export class Change {value: string;}</classes><fields>text: string = "Extra 😀";</fields><functions>export function change(state: AppState, action: Change): AppState { return {text:action.value}; }</functions><view>import * as app from "./experience"; import * as ui from "./platform.dealui-pack"; export view Panel(state: app.AppState): View {ui.TextField(label:"Extra",value:state.text,onChange:action app.Change {value:payload})}</view>"""}
                        else->error("Unexpected stage in $mode: ${request.stage}: ${request.diagnostics}")
                    }
                })
                Embedding.open(targetContext,config).use { api ->
                    val itemType=WireType("record",fields=listOf(WireField("id",WireType("string",maximum=32)),WireField("label",WireType("string",maximum=128)),WireField("done",WireType("boolean"))))
                    val schema=if(mode=="catalogue")WireType("record",fields=listOf(WireField("items",WireType("array",element=itemType,maximum=8)))) else WireType("record")
                    val context=api.snapshot(schema,if(mode=="catalogue")"""{"items":[{"id":"first","label":"First item","done":false},{"id":"second","label":"Second item","done":true}]}""" else "{}")
                    val experience=api.generate(api.request("Construction fixture","Double the edited value; toggle First item and Second item; retain extra text",context))
                    val source=api.source(experience);check(!source.deal.contains("f_whole_"));check(!stages.contains("custom-whole"));check(experience.attempts==1)
                    if(mode=="catalogue"){check(source.deal.contains("f_section_items_"));check(source.dealui.contains("Disclosed checklist 😀"));check(stages.contains("alignment"))}
                    if(mode=="syntax-recovery")check(stages.count{it=="custom-section"}==2)
                    if(mode=="mixed" || mode=="syntax-recovery")check(source.deal.contains("f_gap_Change") && source.deal.contains("f_parts_value"))
                    if(mode=="partial")check(source.dealui.contains("notice_extra"))
                    val session=api.run(experience);val before=api.next(session,"").snapshot;check(before.fault.isEmpty());val slot=actions(before.tree).first().first
                    api.dispatch(session,slot,if(mode=="catalogue")"0" else "9")
                    val after=api.next(session,"").snapshot;check(after.version>before.version && after.fault.isEmpty())
                    if(mode=="catalogue")check(after.tree.toString().contains("remaining: 0")) else {
                        fun hasResult(tree:JSONObject,value:Double):Boolean = ViewTree.nodes(tree).any { node ->
                            val component=if(mode=="time")"ClockText" else "NumberText"
                            val field=if(mode=="time")"minutes" else "value"
                            node.optString("component").substringAfterLast('.')==component && ViewTree.number(node,field)==value
                        }
                        check(hasResult(after.tree,18.0)){"mode=$mode tree=${after.tree}"}
                        if(mode=="gap-reader")check(after.tree.toString().contains("Borrowed result") && after.tree.toString().contains("Above ten"))
                        api.dispatch(session,slot,"invalid");val invalid=api.next(session,"").snapshot;check(invalid.fault.isEmpty());check(invalid.tree.toString().contains("invalid"));check(!hasResult(invalid.tree,18.0));if(mode=="gap-reader")check(!invalid.tree.toString().contains("Borrowed result"))
                        if(mode=="money")for(value in listOf("1.5","9007199254740992","4503599627370496")) {
                            api.dispatch(session,slot,value);val invalidMoney=api.next(session,"").snapshot
                            check(invalidMoney.fault.isEmpty() && ViewTree.nodes(invalidMoney.tree).none { it.optString("component").substringAfterLast('.')=="NumberText" }){"Invalid money/overflow published: $value"}
                        }
                        api.dispatch(session,slot,"4");val restored=api.next(session,"").snapshot;check(restored.fault.isEmpty() && hasResult(restored.tree,8.0))
                    }
                    api.close(session);api.forget(experience)
                }
            }
            // Real compiled custom effect route; only the external provider is mocked.
            if(constructionOnly){result.putString("result","PASS: construction ${constructionMode ?: "all modes"}");finish(-1,result);return}
            for(providerMode in if(storageOnly)emptyList() else listOf("custom","mutation","scalar","scalar-argument")) {
            var providerCalls=0;var failProvider=false
            val provider= nativeCapabilities(CapabilityContract("host/probe","Explicit fixture read",listOf(
                CapabilityFunction("read","Return fixture value",if(providerMode=="scalar-argument")listOf(Parameter("value",WireType("number",minimumNumber=0.0,maximumNumber=100.0)))else emptyList(),WireType("number")),
                CapabilityFunction("write","Stage a fixture value for native review",listOf(Parameter("value",WireType("number",minimumNumber=0.0,maximumNumber=100.0))),WireType("boolean")))),
                mapOf("read" to { args:JSONArray->providerCalls++;check(!failProvider){"Fixture read failure"};if(providerMode=="scalar-argument")args.getDouble(0)*2 else 42 },
                      "write" to { _:JSONArray->providerCalls++;true }), mapOf("read" to "read","write" to "mutating"))
            val providerStages=mutableListOf<String>()
            val providerConfig=EmbeddingConfig("v08-provider-fixture",GenerationGatewayConfig("http://127.0.0.1:1/generate","fixture"),inference=InferenceProvider { request,_->
                providerStages.add(request.stage)
                when(request.stage) {
                    "planning"->if(providerMode=="scalar-argument")"""{"title":"Scalar argument","inputs":[{"id":"value","label":"Value","kind":"count","measure":"count","value":21,"min":0,"max":100}],"constants":[],"requirements":[],"needs":[{"id":"read","type":"live","words":"Read actual result for value","reads":["value"],"spec":{"label":"Scalar","measure":"number","unit":"","query":"host/probe.read"}},{"id":"double","type":"figure","words":"Double result","reads":["read"],"spec":{"label":"Doubled","format":"plain","unit":"","formula":"read*2","example":{"inputs":{"read":42},"result":84}}}],"questions":[]}""" else if(providerMode=="custom" || providerMode=="mutation")"""{"title":"Explicit provider","inputs":[],"constants":[],"requirements":[],"needs":[{"id":"read","type":"other","words":"Explicitly load the provider value","reads":[],"spec":{}}],"questions":[]}""" else """{"title":"Scalar provider","inputs":[],"constants":[],"requirements":[],"needs":[{"id":"read","type":"live","words":"Explicitly read the scalar value","reads":[],"spec":{"label":"Scalar","measure":"number","unit":"","query":"host/probe.read"}},{"id":"double","type":"figure","words":"Double the actual result","reads":["read"],"spec":{"label":"Doubled","format":"plain","unit":"","formula":"read*2","example":{"inputs":{"read":42},"result":84}}}],"questions":[]}"""
                    "selection-first","selection-second","alignment"->{val issued=questions(request.input);val answers=JSONArray();for(i in 0 until issued.length()){val q=issued.getJSONObject(i);val opts=q.getJSONArray("options");val options=(0 until opts.length()).map{opts.getJSONObject(it).getString("id")};answers.put(answer(q.getString("id"),when{(providerMode=="custom" || providerMode=="mutation") && "unavailable" in options->"unavailable";q.getString("id").startsWith("real_")->"read";q.getString("id").startsWith("formula_")->"formula";q.getString("id").startsWith("units_")->"preserved";q.getString("id").startsWith("argument_")->"bound";q.getString("id")=="read"->"scalar_0";q.getString("id")=="double"->"part_double";else->options.last()},options))};JSONObject().put("answers",answers).toString()}
                    "custom-whole"->if(providerMode=="mutation")"""<classes>import * as probe from "host/probe"; export class Load {sequence:number;} export class Loaded { value:string; }</classes><fields>value: string = "Unavailable";</fields><functions>export function start(state:AppState,action:Load):AppState {return {value:state.value};} export async function load(state:AppState,action:Load):Promise<Loaded> {let message:string="ADMITTED"; try { let changed:boolean=await probe.write(1); message=JSON.stringify(changed) as string; } catch(unknownError) { let error:Error = unknownError as Error; message=error.name; } return {value:message};} export function loaded(state:AppState,action:Loaded):AppState {return {value:action.value};}</functions><view>import * as app from "./experience";import * as ui from "./platform.dealui-pack";export view Panel(state:app.AppState):View {ui.Column(){ui.Button(text:"Load",value:0,onClick:action app.Load {sequence:payload}) ui.Text(text:state.value)}}</view>""" else """<classes>import * as probe from "host/probe"; export class Load {sequence:number;} export class Loaded { value:string; }</classes><fields>value: string = "Unavailable";</fields><functions>export function start(state:AppState,action:Load):AppState {return {value:state.value};} export async function load(state:AppState,action:Load):Promise<Loaded> {let value:number=await probe.read();return {value:JSON.stringify(value) as string};} export function loaded(state:AppState,action:Loaded):AppState {return {value:action.value};}</functions><view>import * as app from "./experience";import * as ui from "./platform.dealui-pack";export view Panel(state:app.AppState):View {ui.Column(){ui.Button(text:"Load",value:0,onClick:action app.Load {sequence:payload}) ui.Text(text:state.value)}}</view>"""
                    "coverage"->{val issued=questions(request.input);JSONObject().put("answers",JSONArray((0 until issued.length()).map {answer(issued.getJSONObject(it).getString("id"),"covered",listOf("covered","missing"))})).toString()}
                    else->error("Unexpected provider stage ${request.stage}: ${request.diagnostics}")
                }
            })
            Embedding.open(targetContext,providerConfig,listOf(provider)).use { api->
                api.grant("host/probe")
                val experience=api.generate(api.request("Provider fixture","Explicitly load the provider value",api.snapshot(WireType("record"),"{}")))
                check(providerCalls==0){"Isolated checking or preparation read a provider"}
                val session=api.run(experience);val initial=api.next(session,"").snapshot;check(providerCalls==0)
                fun load(tree:JSONObject) { val button=ViewTree.nodes(tree).first{it.optString("component")=="ui.Button"};api.dispatch(session,ViewTree.actionSlot(button)!!,ViewTree.number(button,"value")?.toString()) }
                fun doubled(tree:JSONObject,value:Double)=ViewTree.nodes(tree).any { it.optString("component")=="ui.NumberText" && ViewTree.number(it,"value")==value }
                if(providerMode=="mutation") {
                    // A model-authored effect names a function the host classifies mutating: the host
                    // dispatch boundary refuses it, so no provider call is issued and the refusal is
                    // what the effect publishes instead of a result.
                    load(initial.tree)
                    val refusal=android.os.SystemClock.elapsedRealtime()+5000
                    var refused=api.next(session,"").snapshot
                    while(!refused.tree.toString().contains("DENIED") && android.os.SystemClock.elapsedRealtime()<refusal){Thread.sleep(20);refused=api.next(session,"").snapshot}
                    check(refused.fault.isEmpty() && refused.tree.toString().contains("DENIED")){"A mutating-classified function called from a model-authored effect was not refused: ${refused.json()}"}
                    Thread.sleep(100);check(providerCalls==0){"A mutating-classified function called from a model-authored effect issued a provider call"}
                } else {
                load(initial.tree)
                val deadline=android.os.SystemClock.elapsedRealtime()+5000
                var updated=api.next(session,"").snapshot
                while(!updated.tree.toString().contains(if(providerMode!="custom")"84" else "42") && android.os.SystemClock.elapsedRealtime()<deadline){Thread.sleep(20);updated=api.next(session,"").snapshot}
                check(updated.version>initial.version && updated.fault.isEmpty() && updated.tree.toString().contains(if(providerMode!="custom")"84" else "42")){"Compiled provider result not committed: ${updated.json()}"}
                check(providerCalls==1)
                if(providerMode!="custom")check(doubled(updated.tree,84.0))
                if(providerMode=="scalar-argument") {
                    api.dispatch(session,actions(updated.tree).first{it.second=="ui.TextField"}.first,"20")
                    val changed=api.next(session,"").snapshot;check(!doubled(changed.tree,84.0))
                    check(providerCalls==1){"Input edit automatically replayed provider read"}
                    load(changed.tree)
                    val until=android.os.SystemClock.elapsedRealtime()+5000
                    var latest=api.next(session,"").snapshot
                    while(!doubled(latest.tree,80.0) && android.os.SystemClock.elapsedRealtime()<until){Thread.sleep(20);latest=api.next(session,"").snapshot}
                    check(latest.fault.isEmpty() && doubled(latest.tree,80.0) && providerCalls==2)
                    api.revoke("host/probe")
                    load(latest.tree)
                    var failed=api.next(session,"").snapshot
                    val deniedUntil=android.os.SystemClock.elapsedRealtime()+5000
                    while(!failed.tree.toString().contains("not granted") && android.os.SystemClock.elapsedRealtime()<deniedUntil){Thread.sleep(20);failed=api.next(session,"").snapshot}
                    check(failed.fault.isEmpty() && !doubled(failed.tree,80.0) && providerCalls==2)
                    api.grant("host/probe");failProvider=true
                    load(failed.tree)
                    val failureUntil=android.os.SystemClock.elapsedRealtime()+5000
                    while(!failed.tree.toString().contains("Fixture read failure") && android.os.SystemClock.elapsedRealtime()<failureUntil){Thread.sleep(20);failed=api.next(session,"").snapshot}
                    check(failed.fault.isEmpty() && failed.tree.toString().contains("Fixture read failure") && !doubled(failed.tree,80.0) && providerCalls==3)
                    Thread.sleep(100);check(providerCalls==3){"Failed provider read automatically retried"}
                    failProvider=false
                    load(failed.tree)
                    val retryUntil=android.os.SystemClock.elapsedRealtime()+5000
                    while(!doubled(failed.tree,80.0) && android.os.SystemClock.elapsedRealtime()<retryUntil){Thread.sleep(20);failed=api.next(session,"").snapshot}
                    check(failed.fault.isEmpty() && doubled(failed.tree,80.0) && providerCalls==4)
                }
                }
                api.close(session);api.forget(experience)
            }
            }
            if(providersOnly){result.putString("result","PASS: compiled custom and scalar provider reads, argument invalidation, permission denial, failure, explicit retry and a refused model-authored mutation");finish(-1,result);return}
            val graphicsSource="""import * as graphics from "embedding/graphics";
export class AppState { shapes: graphics.Shape[]; x: number; }
export class Frame { delta: number; }
export class Touch { pointer: graphics.Pointer; }
export function initialState(): AppState { let shapes: graphics.Shape[] = [{tag:"circle",x:12,y:12,radius:8,color:"#ff0000"}]; return {shapes:shapes,x:12}; }
// @ui-update
export function frame(state: AppState, action: Frame): AppState { let x: number = state.x+action.delta; let shapes: graphics.Shape[] = [{tag:"circle",x:x,y:12,radius:8,color:"#ff0000"}]; return {shapes:shapes,x:x}; }
// @ui-update
export function touch(state: AppState, action: Touch): AppState { let shapes: graphics.Shape[] = [{tag:"circle",x:action.pointer.x,y:action.pointer.y,radius:8,color:"#ff0000"}]; return {shapes:shapes,x:action.pointer.x}; }
// A host-authored source issues its own persistence roles: both fields are authoritative state.
export function bridgePersistedState(state: AppState): Record<string, unknown> { let result: Record<string, unknown> = {}; result["shapes"] = state.shapes; result["x"] = state.x; return result; }
export function bridgeRestoredState(source: Record<string, unknown>): AppState { for (let key of Object.keys(source)) { if (key !== "shapes" && key !== "x") { throw {name:"PERSISTENCE_SCHEMA",message:"Unsupported persisted field: "+key}; } } if (!Object.hasOwn(source,"shapes") || !Object.hasOwn(source,"x")) { throw {name:"PERSISTENCE_SCHEMA",message:"Missing persisted field"}; } return {shapes:(source["shapes"] as graphics.Shape[]),x:(source["x"] as number)}; }"""
            val graphicsView="""import * as app from "./experience"; import * as ui from "./platform.dealui-pack";
// @ui-root
export view Picture(state: app.AppState): View { ui.Surface(width:200,height:200,shapes:state.shapes,onFrame:action app.Frame {delta:payload},onPointer:action app.Touch {pointer:payload}) }"""
            Embedding.open(targetContext,EmbeddingConfig("v08-isolated-checks",GenerationGatewayConfig("http://127.0.0.1:1/generate","fixture"))).use { api ->
                val experience=api.load("Surface",Source(graphicsSource,graphicsView));val session=api.run(experience)
                val initial=api.next(session,"").snapshot;check(initial.fault.isEmpty() && initial.tree.toString().contains("json"))
                val frame=actions(initial.tree).first().first
                for(invalid in listOf("0x10","0b10","+1","01",".5","1.","1e999","NaN","Infinity","","null","true","\"1\"")) {
                    val before=api.next(session,"").snapshot
                    check(runCatching{api.dispatch(session,frame,invalid)}.isFailure){"Non-JSON number accepted: $invalid"}
                    val unchanged=api.next(session,"").snapshot
                    check(unchanged.version==before.version && unchanged.tree.toString()==before.tree.toString())
                }
                api.dispatch(session,frame," 1.25e1 ")
                check(api.next(session,"").snapshot.fault.isEmpty())
                val pointer=actions(initial.tree).last().first
                api.dispatch(session,pointer,"""{"phase":"down","id":0,"x":40,"y":50}""")
                check(api.next(session,"").snapshot.tree.toString().contains("40"))
                api.close(session);api.forget(experience)
                fun rejected(source:String,view:String=graphicsView){check(runCatching{api.load("Rejected",Source(source,view))}.isFailure)}
                rejected(graphicsSource.replace("state.x+action.delta","state.x"))
                rejected(graphicsSource.replace("export function initialState(): AppState {", "export function initialState(): AppState { throw {name:\"STARTUP\",message:\"startup trap\"};"))
                rejected(graphicsSource.replace("let x: number = state.x+action.delta;", "let x: number = state.x+action.delta;").replace("x:x,y:12,radius:8", "x:x,y:12,radius:-8"))
                val synthetic=graphicsSource.replace("let x: number = state.x+action.delta;", "if (action.delta === 1) { throw {name:\"SYNTHETIC\",message:\"input trap\"}; } let x: number = state.x+action.delta;")
                val retained=api.load("Synthetic input trap",Source(synthetic,graphicsView));api.forget(retained)
                rejected(graphicsSource.replace("radius:8","radius:-8"))
                rejected(graphicsSource.replace("radius:8,color:","radius:8,width:3,color:"))
                rejected(graphicsSource.replace("let x: number = state.x+action.delta;","let x: number = state.x+action.delta; while (true) { x = x+1; }"))
            }
            // Cold-start restoration is checked before persistence in a discarded,
            // fixed-clock isolate. The activation creates another fresh Session.
            val restoreConfig=EmbeddingConfig("v08-preparation-restore",GenerationGatewayConfig("http://127.0.0.1:1/generate","fixture"),retainState=true)
            var savedId=""
            Embedding.open(targetContext,restoreConfig).use { api ->
                val experience=api.load("Restoration",Source(graphicsSource,graphicsView));savedId=experience.id
                val session=api.run(experience);val snapshot=api.next(session,"").snapshot
                api.dispatch(session,actions(snapshot.tree).last().first,"""{"phase":"down","id":0,"x":73,"y":50}""")
                api.close(session)
            }
            Embedding.open(targetContext,restoreConfig).use { api ->
                val experience=api.saved().single{it.id==savedId};val source=api.source(experience)
                val session=api.run(experience);check(api.next(session,"").snapshot.tree.toString().contains("73"));api.close(session)
                val preferences=targetContext.getSharedPreferences("v08-preparation-restore",0)
                val retained=preferences.getString("workspace.$savedId.state",null)!!
                // Poison one authoritative value inside the saved record: the typed restore rejects it,
                // and the record's own bytes stay untouched until the host replaces them.
                val poisoned=JSONObject(retained);val entries=poisoned.getJSONArray("value").getJSONArray(2)
                var poisonedField=false
                for(i in 0 until entries.length()){val pair=entries.getJSONArray(i);if(pair.getString(0)=="x"){pair.put(1,JSONArray().put("string").put("invalid"));poisonedField=true}}
                check(poisonedField){"Saved record does not carry the authoritative position"}
                val poisonedText=poisoned.toString()
                check(preferences.edit().putString("workspace.$savedId.state",poisonedText).commit())
                check(runCatching{api.run(experience)}.isFailure)
                check(api.source(experience)==source && api.saved().any{it.id==savedId})
                check(preferences.getString("workspace.$savedId.state",null)==poisonedText)
                check(preferences.edit().putString("workspace.$savedId.state",retained).commit())
                val retried=api.run(experience);check(api.next(retried,"").snapshot.tree.toString().contains("73"));api.close(retried);api.forget(experience)
            }
            var calls=0
            val broken=EmbeddingConfig("v08-construction-failure",GenerationGatewayConfig("http://127.0.0.1:1/generate","fixture"),inference=InferenceProvider { _,_->calls++;throw IllegalStateException("fixture transport failure") })
            Embedding.open(targetContext,broken).use { api ->val context=api.snapshot(WireType("record"),"{}");check(runCatching {api.generate(api.request("Failure","Preserve original failure",context))}.isFailure)}
            check(calls==1)
            result.putString("result","PASS: installed isolated handler checking, typed Surface execution/rejection/runaway, ordinary parts, catalogue, gaps, invalid drafts and operational failure")
            finish(-1,result)
        } catch(error:Throwable) {result.putString("failure",android.util.Log.getStackTraceString(error));finish(0,result)}
    }
    /** Explicit paid-generation probe through production API and installed providers.
     * Inputs/outputs stay in private test files, never packaged fixture policy. */
    private fun runScenarios(input:String,result:Bundle) {
        val file=java.io.File(targetContext.filesDir,input)
        require(file.canonicalFile.parentFile==targetContext.filesDir.canonicalFile)
        val scenarios=JSONArray(file.readText());val reports=JSONArray()
        val directory=java.io.File(targetContext.filesDir,"scenario-results").apply{mkdirs()}
        for(index in 0 until scenarios.length()) {
            val scenario=scenarios.getJSONObject(index);val name=scenario.getString("name");require(name.matches(Regex("[a-z]+")))
            val report=JSONObject().put("scenario",name);val start=android.os.SystemClock.elapsedRealtime()
            val checks=JSONArray();val failures=JSONArray();val limitations=JSONArray()
            try {
                Embedding.open(targetContext,EmbeddingConfig("v08-scenario-"+name+"-"+java.util.UUID.randomUUID(),GenerationGatewayConfig("http://127.0.0.1:8787/generate","cerebras"),retainState=true),trustProvider={uid->listOf("dev.deal.apps.vehicle","dev.deal.apps.pizza","dev.deal.apps.rental").any{packageName->runCatching{targetContext.packageManager.getApplicationInfo(packageName,0).uid==uid}.getOrDefault(false)}}).use { api ->
                    api.capture(true)
                    val discovered=api.refresh();report.put("discoveredModules",JSONArray(discovered.map{it.module}))
                    if(name!="budget" && discovered.isEmpty()) {
                        val diagnostics=JSONArray()
                        val services=targetContext.packageManager.queryIntentServices(android.content.Intent("dev.deal.embedding.CAPABILITIES_V1"),android.content.pm.PackageManager.GET_META_DATA)
                        for(resolved in services) {
                            val info=resolved.serviceInfo;if(info.packageName !in listOf("dev.deal.apps.vehicle","dev.deal.apps.pizza","dev.deal.apps.rental"))continue
                            val receipt=JSONObject().put("package",info.packageName)
                            val latch=java.util.concurrent.CountDownLatch(1);var binder:android.os.IBinder?=null
                            val connection=object:android.content.ServiceConnection {
                                override fun onServiceConnected(component:android.content.ComponentName,service:android.os.IBinder){binder=service;latch.countDown()}
                                override fun onServiceDisconnected(component:android.content.ComponentName){}
                            }
                            try {
                                val bound=targetContext.bindService(android.content.Intent().setComponent(android.content.ComponentName(info.packageName,info.name)),connection,android.content.Context.BIND_AUTO_CREATE);receipt.put("bound",bound)
                                check(latch.await(5,java.util.concurrent.TimeUnit.SECONDS)){"No provider connection"}
                                val raw=dev.deal.embedding.capabilities.ICapabilityService.Stub.asInterface(binder).describe();receipt.put("contract",raw)
                                JSONObject(raw)
                            } catch(error:Throwable){receipt.put("failure",android.util.Log.getStackTraceString(error))}
                            finally {runCatching{targetContext.unbindService(connection)}}
                            diagnostics.put(receipt)
                        }
                        java.io.File(directory,"$name-discovery.json").writeText(diagnostics.toString())
                        error("Installed providers were not discovered")
                    }
                    val fact=WireType("record",fields=listOf(WireField("id",WireType("string",maximum=128)),WireField("text",WireType("string",maximum=4096))))
                    val schema=WireType("record",fields=listOf(WireField("facts",WireType("array",element=fact,maximum=32)),WireField("supportingFactIds",WireType("array",element=WireType("string",maximum=128),maximum=32)),WireField("capabilityIds",WireType("array",element=WireType("string",maximum=128),maximum=32))))
                    val context=api.snapshot(schema,scenario.getString("context"))
                    val request=api.request(name,scenario.getString("intent"),context);report.put("trace",api.requestTrace(request))
                    val experience=if(replaySource)api.load("Exact source replay",Source(java.io.File(directory,"$name.deal").readText(),java.io.File(directory,"$name.dealui").readText()))else api.generate(request);val source=api.source(experience)
                    report.put("inference",!replaySource)
                    java.io.File(directory,"$name.deal").writeText(source.deal);java.io.File(directory,"$name.dealui").writeText(source.dealui)
                    report.put("experience",experience.id).put("attempts",experience.attempts)
                    val session=api.run(experience)
                    val initial=api.next(session,"").snapshot
                    java.io.File(directory,"$name-initial.json").writeText(initial.tree.toString())
                    report.put("status","initialized").put("fault",initial.fault)
                    val journey=Scenario(name,api,experience,directory,report,checks,limitations,mutableListOf(report.getString("trace")),session,declaredCapabilities(scenario.getString("context")))
                    journey.trace(session)
                    try {
                        when(name) {
                            "budget"->budgetJourney(journey,initial)
                            "departure"->departureJourney(journey,initial)
                            "food"->foodJourney(journey,initial)
                            "equipment"->equipmentJourney(journey,initial)
                            else->journey.failed("$name.scenario","the scenario name is not one of the four tracked acceptance journeys")
                        }
                        if(name in SCENARIO_JOURNEYS) {
                            val blocked=journey.blockedReason
                            if(blocked!=null) {
                                // A required expectation the journey could not prove is a located blocker,
                                // never a checked journey.
                                report.put("status","blocked").put("blocked",blocked)
                                journey.limited("the $name acceptance journey","blocked: $blocked")
                            } else {
                                report.put("status","checked")
                                journey.passed("the $name acceptance journey completed through the packaged public APIs")
                            }
                        }
                    } catch(error:Throwable) {
                        recordScenarioFailure(report,failures,error)
                    } finally {
                        runCatching{journey.closeLive()}
                        runCatching{journey.writeEvents()}
                        runCatching{api.forget(experience)}
                    }
                }
            } catch(error:Throwable) {recordScenarioFailure(report,failures,error)}
            report.put("checks",checks).put("failures",failures).put("limitations",limitations)
            report.put("elapsedMs",android.os.SystemClock.elapsedRealtime()-start);reports.put(report)
            java.io.File(directory,"reports.json").writeText(reports.toString())
            sendStatus(0,Bundle().apply{putString("scenario",report.toString())})
        }
        result.putString("scenarios",reports.toString())
        val unproven=(0 until reports.length()).map{reports.getJSONObject(it)}.filter{it.optString("status") in setOf("failed","blocked")}
        if(unproven.isNotEmpty())result.putString("unproven",JSONArray(unproven.map{JSONObject().put("scenario",it.optString("scenario")).put("status",it.optString("status")).put("reason",it.optString("blocked").ifEmpty{it.optString("failure")})}).toString())
        finish(if(unproven.isEmpty()) -1 else 0,result)
    }
}

// ---------------------------------------------------------------------------------------------
// Opt-in acceptance journeys: typed observation, provider-call accounting and located failures.
// ---------------------------------------------------------------------------------------------

private val SCENARIO_JOURNEYS=setOf("budget","departure","food","equipment")
private val MUTATION_WORDS=listOf("order","book","reserv","purchas","propos","submit","pay","cancel","charg","confirm","mutat","delet","writ")
private val PIZZA_ROWS=listOf("30 cm","30cm","pizza")
private val PIZZA_TOTALS=listOf(listOf("including delivery"),listOf("incl. delivery"),listOf("delivered"),listOf("delivery total"))
private val EQUIPMENT_PACKAGE=listOf(listOf("package total"),listOf("package hire"),listOf("package"))
private val EQUIPMENT_SEPARATE=listOf(listOf("separate total"),listOf("separate hire"),listOf("separate"),listOf("individual"))
private val DEPARTURE_INSTANTS=listOf(listOf("departure"))
private val INSPECTION_INSTANTS=listOf(listOf("inspection time"),listOf("inspection"))
/** Arrival instants only: a delivery price or the disclosed deadline itself never proves the outcome.
 * `eta` is a marker because labels are matched as whole words, so it never matches inside a word such
 * as "vegetarian". */
internal val FOOD_ARRIVALS=listOf(listOf("arrival"),listOf("arrive"),listOf("delivery time"),listOf("estimated delivery"),listOf("eta"))
/** The disclosed before-18:30 constraint as minutes of the task day (18 * 60 + 30). */
private const val FOOD_DEADLINE_MINUTES=1110.0

/** A located acceptance failure: the tree did not expose a typed value the contract requires. */
internal class LocatedFailure(val location:String,val reason:String):RuntimeException("$location: $reason")

internal fun located(location:String,reason:String):Nothing=throw LocatedFailure(location,reason)

/** Explicit opt-in fixture date. Europe/Berlin is UTC+02:00 on 23 October 2026 (DST ends 25 October). */
internal fun fixtureEpochMillis(minuteOfDay:Double):Double {
    val local=java.time.LocalDate.of(2026,10,23).atStartOfDay().plusMinutes(minuteOfDay.toLong())
    return local.toInstant(java.time.ZoneOffset.ofHours(2)).toEpochMilli().toDouble()
}

internal fun fixtureEpochDay():Double=java.time.LocalDate.of(2026,10,23).toEpochDay().toDouble()

/** What a journey does with one time evidence: only a contradiction is a failure; no usable evidence
 * blocks the journey, which can then never report itself checked. */
internal enum class TimeOutcome { Proven, Contradicted, Unproven }

/** Labels that name a duration, buffer, distance, price, deadline or countdown rather than the instant
 * a journey compares with the disclosed window or the 18:30 constraint. */
private val NOT_AN_INSTANT=listOf("before","after","by","buffer","duration","inspection","travel","distance","price","total","budget","fee","deposit","deadline","latest","remaining","countdown","maximum","minimum","left","in")
/** The labels that name the window as a whole, so a value under one of them that disagrees with the
 * disclosed interval contradicts it. A `start`/`end` instant may belong to another quantity, so such a
 * value only ever counts as published evidence. */
private val WINDOW_BOUNDARIES=listOf("window","from","until")

/** Verdict for one required time expectation: proven, contradicted by a published value, or unproven
 * because no typed value the journey can interpret exists. */
internal sealed interface TimeEvidence {
    data object Proven:TimeEvidence
    data class Contradiction(val reason:String):TimeEvidence
    data class Unproven(val reason:String):TimeEvidence
    /** The located reason the journey reports for a contradiction or an unproven expectation. */
    val detail:String get() = when(this) {
        is TimeEvidence.Proven->""
        is TimeEvidence.Contradiction->reason
        is TimeEvidence.Unproven->reason
    }
    val outcome:TimeOutcome get() = when(this) {
        is TimeEvidence.Proven->TimeOutcome.Proven
        is TimeEvidence.Contradiction->TimeOutcome.Contradicted
        is TimeEvidence.Unproven->TimeOutcome.Unproven
    }
}

/**
 * The interpretation rules the opt-in journeys use to read typed time evidence: the disclosed
 * collection or hire window and the before-18:30 delivery arrival. Pure, so the device lane proves the
 * same rules directly instead of only through a generated view.
 */
internal object JourneyTimes {
    /** A label names a word as its own word: `weekend` is never `end` and `inspection` is never `in`. */
    fun names(label:String,word:String):Boolean =
        Regex("(?i)(?<![a-z])"+Regex.escape(word)+"(?![a-z])").containsMatchIn(label)
    /** The published string that names a required item together with every required attribute of that
     * item. The item word must be on the node's own string props, so a section that merely contains two
     * different items never becomes one item, and attributes come from that node's own props or from one
     * branch of it (including a single-child wrapper), never gathered from two branches, so two separately
     * described items never become one. Every marker is a whole word, so `male` is never satisfied by
     * `female`. Null when no single item names all of them. */
    fun namedItem(tree:JSONObject,item:String,attributes:List<String>):String?=namedItemNode(tree,item,attributes)?.let{it.second}
    /** The item node and the published text that names it with every required attribute of it. */
    fun namedItemNode(tree:JSONObject,item:String,attributes:List<String>):Pair<JSONObject,String>? {
        fun children(node:JSONObject)=(0 until (node.optJSONArray("children")?.length()?:0)).map{node.getJSONArray("children").getJSONObject(it)}
        fun branch(node:JSONObject):String {
            val kids=children(node)
            val published=ownText(node)
            return if(kids.size==1)listOf(published,branch(kids.single())).filter{it.isNotBlank()}.joinToString(" ") else published
        }
        for(node in ViewTree.nodes(tree)) {
            if(!names(ownText(node),item))continue
            val branches=listOf(ownText(node))+children(node).map{branch(it).let{detail->listOf(ownText(node),detail).filter{text->text.isNotBlank()}.joinToString(" ")}}
            val published=branches.firstOrNull{text->text.isNotBlank()&&attributes.all{names(text,it)}}
            if(published!=null)return node to published
        }
        return null
    }
    /** The typed values one required item publishes: inside its own row, or a direct sibling of the item in
     * that row, but never a value whose nearest item-naming ancestor is another item, so a spare cable's
     * length never stands in for the required one. An item the view never named publishes nothing. */
    fun itemValues(tree:JSONObject,item:JSONObject?,word:String,markers:List<String>):List<Pair<JSONObject,Double>> {
        if(item==null)return emptyList()
        val owners=java.util.IdentityHashMap<JSONObject,JSONObject?>()
        fun visit(node:JSONObject,inherited:JSONObject?) {
            val effective=if(names(ownText(node),word))node else inherited
            owners[node]=effective
            val children=node.optJSONArray("children")?:return
            for(i in 0 until children.length())visit(children.getJSONObject(i),effective)
        }
        visit(tree,null)
        val row=ViewTree.parent(tree,item)
        return ViewTree.entries(tree)
            .filter{entry->ViewTree.component(entry.node) in ViewTree.NUMERIC}
            .filter{entry->markers.any{marker->entry.markers.any{it.contains(marker,true)}}}
            .mapNotNull{entry->ViewTree.NUMERIC_PROP[ViewTree.component(entry.node)]?.let{prop->ViewTree.number(entry.node,prop)?.let{entry.node to it}}}
            .filter{(node,_)->owners[node]===item||(owners[node]==null&&row!=null&&ViewTree.parent(tree,node)===row)}
    }
    /** The declared spellings of one minute of the task day: the minute count, its day-millisecond magnitude
     * and the task date's epoch instant, which are the only denominations the journeys read as an instant. */
    fun spellings(minute:Double):List<Double> = listOf(minute,minute*60000.0,fixtureEpochMillis(minute))
    /** The string props one node publishes, which is what names the item on it. */
    fun ownText(node:JSONObject):String =
        ViewTree.props(node).filter{it.optString("kind")=="string"}.joinToString(" "){it.optString("stringValue")}
    /** The typed values one declared set of markers identifies on the node's own labels. An inherited
     * sibling label never turns an unrelated quantity into a time, and a label naming a duration, a
     * buffer, a price or a deadline is never read as the instant itself. */
    fun typed(tree:JSONObject,markers:List<String>,excluded:List<String> = emptyList()):List<Pair<JSONObject,Double>> =
        ViewTree.entries(tree)
            .filter{entry->ViewTree.component(entry.node) in ViewTree.INSTANT+ViewTree.EDITABLE}
            .filter{entry->markers.any{marker->ViewTree.ownMarkers(entry.node).any{names(it,marker)}}}
            .filter{entry->excluded.none{word->ViewTree.ownMarkers(entry.node).any{names(it,word)}}}
            .mapNotNull{entry->ViewTree.NUMERIC_PROP[ViewTree.component(entry.node)]?.let{prop->ViewTree.number(entry.node,prop)?.let{value->entry.node to value}}}
    /** Minutes of the task day one typed instant spelling denotes: an integral minute count 0-1439, or
     * the epoch instant of the task date whose offset the fixture date fixes at +02:00. Any other
     * magnitude - a duration, seconds of the day, an epoch day, a value outside the day - is no time of
     * the task day, so it can never pass as an early arrival. */
    fun minuteOfDay(value:Double):Double? {
        val epoch=fixtureEpochMillis(0.0)
        return when {
            value>=epoch->((value-epoch)/60000.0).takeIf{it>=0.0&&it<1440.0}
            value>=0.0&&value<1440.0&&value==kotlin.math.floor(value)->value
            else->null
        }
    }
    /** The disclosed minutes no published spelling denotes: a minute count, day milliseconds or the
     * epoch instant of the task date. */
    fun missingTimes(minutes:List<Double>,found:List<Double>):List<Double> =
        minutes.filter{minute->!found.any{value->spellings(minute).any{it==value}}}
    /** Both disclosed ends of one window: typed window boundaries that agree with every end, or the
     * disclosed spelling of both ends. A value under a label that names the window as a whole states a
     * window end: one other than a disclosed end, or a window that drops one of them, contradicts the
     * disclosed interval. A `start`/`end` value may belong to another quantity, so it only ever
     * satisfies a missing end and never turns a window it does not describe into a contradiction. A
     * value that is no time of the task day at all - an epoch day, a duration in another unit, a price
     * - is never read as a boundary. */
    fun window(tree:JSONObject,markers:List<String>,minutes:List<Double>,human:List<String>):TimeEvidence {
        val accepted=minutes.flatMap{spellings(it)}
        val boundaries=typed(tree,markers,NOT_AN_INSTANT).map{it.second}
            .filter{minuteOfDay(it)!=null||it in accepted}
        val declared=typed(tree,WINDOW_BOUNDARIES,NOT_AN_INSTANT).map{it.second}
            .filter{minuteOfDay(it)!=null||it in accepted}
        val stranger=declared.firstOrNull{it !in accepted}
        if(stranger!=null)return TimeEvidence.Contradiction("the window is published with $stranger, which is none of the disclosed $minutes minutes of the task day")
        if(missingTimes(minutes,boundaries).isEmpty())return TimeEvidence.Proven
        if(human.all{spelling->ViewTree.allStrings(tree).any{text->text.contains(spelling,true)}})return TimeEvidence.Proven
        if(declared.isNotEmpty())return TimeEvidence.Contradiction("the window is published as ${declared.distinct()}, which drops the disclosed ${missingTimes(minutes,declared).joinToString(" ")} of the $minutes minutes of the task day")
        return TimeEvidence.Unproven("the tree publishes no typed window identifying ${markers.joinToString("|")} and no disclosed spelling of ${human.joinToString(" ")}")
    }
    /** A computed instant that belongs to the previous task day, so a view that silently wrapped the
     * same clock reading onto the task day is a contradiction. A departure before midnight is the case:
     * a collection start at 00:10 with the unloading and the returned travel is a negative number of
     * minutes of the task day. The accepted spellings keep the date - the negative minute count, its
     * day-millisecond magnitude or the previous day's epoch instant - while the same clock reading of
     * the task day is the wrap this rule fails. No typed instant at all leaves the expectation unproven. */
    fun rollover(tree:JSONObject,markers:List<String>,minutes:Double):TimeEvidence {
        val accepted=spellings(minutes)
        val wrapped=spellings(minutes+1440.0)
        val found=typed(tree,markers).map{it.second}
        val sameDay=wrapped.firstOrNull{it in found}
        if(sameDay!=null)return TimeEvidence.Contradiction("the instant is published as $sameDay, which wrapped it onto the task day instead of keeping $minutes minutes (${1440.0+minutes} minutes of the previous day)")
        if(found.any{it in accepted})return TimeEvidence.Proven
        return TimeEvidence.Unproven("the tree publishes no typed instant identifying ${markers.joinToString("|")} as the previous day's $minutes minutes (a negative minute count, its day-millisecond magnitude or its epoch instant); found ${found.distinct()}")
    }
    /** One arrival instant before [deadline] minutes of the task day, identified by an arrival or
     * delivery-time label on the node itself so a delivery price or another quantity never proves it. */
    fun arrival(tree:JSONObject,markers:List<List<String>>,deadline:Double):TimeEvidence {
        for(set in markers) {
            val found=typed(tree,set,NOT_AN_INSTANT)
            if(found.isEmpty())continue
            val values=found.map{it.second}
            if(values.any{it>=fixtureEpochMillis(1440.0)})return TimeEvidence.Contradiction("the arrival epoch instant is not on the disclosed task date: $values")
            // A Timer's millis is a declared epoch timestamp, so a smaller value is no time of the day.
            val minutes=found.map{(node,value)->if(ViewTree.component(node)=="Timer"&&value<fixtureEpochMillis(0.0))null else minuteOfDay(value)}
            if(minutes.any{it==null})return TimeEvidence.Unproven("the value(s) $values identifying ${set.joinToString("|")} are no time of the task day: no integral 0-1439 minute count and no epoch instant of the task date")
            val late=minutes.filterNotNull().filter{it>=deadline}
            if(late.isNotEmpty())return TimeEvidence.Contradiction("the arrival is $late minutes of the task day, which is not before the disclosed 18:30 ($deadline minutes of the task day)")
            return TimeEvidence.Proven
        }
        return TimeEvidence.Unproven("the tree publishes no typed arrival or delivery time identifying ${markers.joinToString(" ; "){it.joinToString("|")}}")
    }
}

private fun formatNumber(value:Double):String=if(value==kotlin.math.floor(value)&&kotlin.math.abs(value)<1e15)value.toLong().toString()else value.toString()

/** Capability references disclosed in the scenario context, as "module.function". Not permissions. */
private fun declaredCapabilities(contextJson:String):Set<String> {
    val ids=runCatching{JSONObject(contextJson).optJSONArray("capabilityIds")}.getOrNull()?:return emptySet()
    return (0 until ids.length()).map{ids.optString(it)}.filter{it.isNotEmpty()}.toSet()
}

private fun recordScenarioFailure(report:JSONObject,failures:JSONArray,error:Throwable) {
    report.put("status","failed")
    val located=error as? LocatedFailure
    if(located!=null) {
        report.put("failure","${located.location}: ${located.reason}")
        failures.put(JSONObject().put("location",located.location).put("reason",located.reason))
    } else {
        report.put("failure",android.util.Log.getStackTraceString(error))
        failures.put(JSONObject().put("location","${report.optString("scenario")}.unexpected").put("reason",error.javaClass.simpleName+": "+error.message))
    }
}

/**
 * Typed observation of the checked Deal UI tree. A rendered string never substitutes a typed
 * value: numeric props are read with `kind` "number", strings with `kind` "string".
 */
internal object ViewTree {
    /** Specification numeric presentation components and their typed value prop. */
    val NUMERIC=setOf("NumberText","NumberStat","NumberListItem")
    /** Components whose typed value may carry a computed instant. */
    val INSTANT=setOf("NumberText","NumberStat","NumberListItem","ClockText","Timer")
    val EDITABLE=setOf("NumberField","TextField","TimeField","DateField","Slider","Select","Tabs","Toggle","Checkbox")
    val CONTROLS=setOf("Button","IconButton","Cell")
    val NUMERIC_PROP=mapOf(
        "NumberText" to "value","NumberStat" to "value","NumberListItem" to "value",
        "ClockText" to "minutes","Timer" to "millis","TimeField" to "value","DateField" to "value",
        "NumberField" to "value","Slider" to "value","Progress" to "value","ProgressRing" to "value")
    private val IDENTIFYING=listOf("label","title","text","prefix","suffix","supporting","detail","trailing","message","subtitle","value")
    private val STALE=listOf("status","state","supporting","detail","text","label","message","suffix","prefix","subtitle","title","eyebrow")
    /** Components that publish a text disclosure and no value of their own: a banner or heading in a
     * value's section, never another quantity's stale state. */
    private val BANNERS=setOf("Text","Badge","InsetBanner","EmptyState","Snackbar","SectionHeader","Header","Hero","Spinner","Skeleton","Icon","Image","Divider","Spacer")
    /** The banner components that open a section, so one that follows a value belongs to the next one. */
    private val HEADINGS=setOf("SectionHeader","Header","Hero")
    /** The words that publish a stale or unready state. */
    val STALE_WORDS=listOf("stale","expired","not current","awaiting")
    /** The words a value's own labels use for a withdrawn display: absent provider data is `unavailable`. */
    val WITHDRAWN_WORDS=STALE_WORDS+listOf("unavailable")

    class Entry(val node:JSONObject,val markers:List<String>)

    fun component(node:JSONObject)=node.optString("component").substringAfterLast('.')
    fun props(node:JSONObject):List<JSONObject> {
        val array=node.optJSONArray("props")?:return emptyList()
        return (0 until array.length()).map{array.getJSONObject(it)}
    }
    fun prop(node:JSONObject,name:String)=props(node).firstOrNull{it.optString("name")==name}
    fun string(node:JSONObject,name:String)=prop(node,name)?.takeIf{it.optString("kind")=="string"}?.optString("stringValue")
    fun number(node:JSONObject,name:String):Double? {
        val value=prop(node,name)?:return null
        if(value.optString("kind")!="number"||!value.has("numberValue"))return null
        return value.getDouble("numberValue")
    }
    fun actionSlot(node:JSONObject)=props(node).firstOrNull{it.optString("kind")=="action"&&it.has("actionSlot")}?.getInt("actionSlot")
    fun nodes(tree:JSONObject):List<JSONObject> {
        val result=mutableListOf<JSONObject>()
        fun visit(node:JSONObject){result.add(node);val children=node.optJSONArray("children")?:return;for(i in 0 until children.length())visit(children.getJSONObject(i))}
        visit(tree);return result
    }
    fun ownMarkers(node:JSONObject):List<String> = props(node).filter{it.optString("kind")=="string"&&it.optString("name") in IDENTIFYING}.map{it.optString("stringValue")}.filter{it.isNotBlank()}
    /** The parent of one node: the row or section one published quantity belongs to. */
    fun parent(tree:JSONObject,target:JSONObject):JSONObject? {
        for(node in nodes(tree)) {
            val children=node.optJSONArray("children")?:continue
            for(i in 0 until children.length())if(children.getJSONObject(i)===target)return node
        }
        return null
    }
    /** Own identifying strings plus the parent's and the immediately preceding sibling's. */
    fun entries(tree:JSONObject):List<Entry> {
        val result=mutableListOf<Entry>()
        fun visit(node:JSONObject,inherited:List<String>) {
            val own=ownMarkers(node)
            result.add(Entry(node,own+inherited))
            val children=node.optJSONArray("children")?:return
            var previous=emptyList<String>()
            for(i in 0 until children.length()) {
                val child=children.getJSONObject(i)
                visit(child,own+previous)
                previous=ownMarkers(child)
            }
        }
        visit(tree,emptyList())
        return result
    }
    fun numbers(tree:JSONObject,markers:List<String>,components:Set<String> = NUMERIC):List<Pair<JSONObject,Double>> {
        val result=mutableListOf<Pair<JSONObject,Double>>()
        for(entry in entries(tree)) {
            val name=component(entry.node)
            if(name !in components)continue
            val value=NUMERIC_PROP[name]?.let{number(entry.node,it)}?:continue
            if(markers.isEmpty()||markers.any{marker->entry.markers.any{it.contains(marker,true)}})result.add(entry.node to value)
        }
        // Own labels win over inherited ones: a sibling's label never makes a typed value ambiguous.
        val own=result.filter{(node,_)->markers.isEmpty()||markers.any{marker->ownMarkers(node).any{it.contains(marker,true)}}}
        return if(own.isNotEmpty())own else result
    }
    fun unique(location:String,tree:JSONObject,markers:List<String>,components:Set<String>,reason:String)=uniqueAny(location,tree,listOf(markers),components,reason)    fun uniqueAny(location:String,tree:JSONObject,sets:List<List<String>>,components:Set<String>,reason:String):Pair<JSONObject,Double> {
        for(set in sets) {
            val found=numbers(tree,set,components)
            if(found.isEmpty())continue
            val distinct=found.map{it.second}.distinct()
            if(distinct.size>1)located(location,"$reason: ambiguous typed candidates $distinct for ${set.joinToString("|")}")
            return found.first()
        }
        located(location,"$reason: no ${components.joinToString("/")} node identifying ${sets.joinToString(" ; "){it.joinToString("|")}} carries a typed number")
    }
    /** A control identified by its own label first; inherited labels never make a row ambiguous. */
    fun select(location:String,found:List<Entry>,markers:List<String>,what:String):Entry {
        if(found.isEmpty())located(location,"no $what identifying ${markers.joinToString("|")} is exposed")
        val own=found.filter{entry->markers.any{marker->ownMarkers(entry.node).any{it.contains(marker,true)}}}
        val candidates=if(own.isNotEmpty())own else found
        if(candidates.size>1)located(location,"ambiguous $what for ${markers.joinToString("|")}: ${candidates.map{ownMarkers(it.node)}}")
        return candidates.first()
    }
    /** A typed number whose value is one of the accepted spellings of the same quantity. */
    fun matching(location:String,tree:JSONObject,sets:List<List<String>>,components:Set<String>,accepted:List<Double>,reason:String):Double {
        for(set in sets) {
            val found=numbers(tree,set,components)
            if(found.isEmpty())continue
            val ok=found.filter{entry->accepted.any{it==entry.second}}
            if(ok.isNotEmpty())return ok.first().second
            located(location,"$reason: ${set.joinToString("|")} carries ${found.map{it.second}}, expected one of $accepted")
        }
        located(location,"$reason: no ${components.joinToString("/")} node identifying ${sets.joinToString(" ; "){it.joinToString("|")}} carries a typed number")
    }
    fun allStrings(tree:JSONObject):List<String> = nodes(tree).flatMap{node->props(node).filter{it.optString("kind")=="string"}.map{it.optString("stringValue")}}
    /** Typed numbers keyed by component, prop and the node's own markers, not traversal position. */
    fun numbersSnapshot(tree:JSONObject,components:Set<String>?=null):List<Pair<String,Double>> {
        val result=mutableListOf<Pair<String,Double>>()
        fun visit(node:JSONObject) {
            val name=component(node)
            if(name!="Timer"&&(components==null||name in components)) {
                NUMERIC_PROP[name]?.let{propName->number(node,propName)?.let{
                    val identity=ownMarkers(node).sorted().joinToString("|")
                    result.add("$name.$propName@$identity" to it)
                }}
            }
            val children=node.optJSONArray("children")?:return
            for(i in 0 until children.length())visit(children.getJSONObject(i))
        }
        visit(tree)
        return result.sortedWith(compareBy({it.first},{it.second}))
    }
    /** Explicit stale/unready state published by a non-interactive node, never a button label. */
    fun stale(tree:JSONObject):String? {
        for(node in nodes(tree)) staleText(node)?.let{return it}
        return null
    }
    private fun staleText(node:JSONObject,words:List<String> = STALE_WORDS):String? {
        if(component(node) in CONTROLS)return null
        for(value in props(node)) {
            if(value.optString("kind")!="string"||value.optString("name") !in STALE)continue
            val text=value.optString("stringValue")
            if(words.any{text.contains(it,true)})return text
        }
        return null
    }
    /** The stale/unready state published for one value: on the value itself, on an ancestor, or on a node
     * of the value's own section, which is where a section-level banner sits. A banner is text-only: a
     * sibling typed value's stale label describes that value and never stands in for this one, and a marker
     * published for another section never stales this value. */
    fun staledValue(tree:JSONObject,target:JSONObject,words:List<String> = STALE_WORDS):String? {
        fun contains(node:JSONObject):Boolean {
            if(node===target)return true
            val children=node.optJSONArray("children")?:return false
            for(i in 0 until children.length())if(contains(children.getJSONObject(i)))return true
            return false
        }
        fun scan(node:JSONObject):String? {
            if(!contains(node))return null
            staleText(node,words)?.let{return it}
            val children=node.optJSONArray("children")?:return null
            var path=-1
            for(i in 0 until children.length())if(contains(children.getJSONObject(i))){path=i;break}
            for(i in 0 until children.length()) {
                val child=children.getJSONObject(i)
                val name=component(child)
                if(name !in BANNERS)continue
                // A heading that follows the value heads the section after it, never this value.
                if(name in HEADINGS&&i>path)continue
                staleText(child,words)?.let{return it}
            }
            for(i in 0 until children.length())scan(children.getJSONObject(i))?.let{return it}
            return null
        }
        return scan(tree)
    }
    fun refreshActions(tree:JSONObject):List<Pair<JSONObject,Int>> {
        val result=mutableListOf<Pair<JSONObject,Int>>()
        for(entry in entries(tree)) {
            if(component(entry.node) !in CONTROLS)continue
            val slot=actionSlot(entry.node)?:continue
            if(listOf("refresh","reload","retry","load").any{marker->entry.markers.any{it.contains(marker,true)}})result.add(entry.node to slot)
        }
        return result
    }
    fun difference(before:List<Pair<String,Double>>,after:List<Pair<String,Double>>):String {
        val lines=mutableListOf<String>()
        for(i in 0 until maxOf(before.size,after.size)) {
            val left=before.getOrNull(i);val right=after.getOrNull(i)
            if(left!=right)lines.add("${left?.first ?: "absent"}=${left?.second} -> ${right?.first ?: "absent"}=${right?.second}")
            if(lines.size>=6)break
        }
        return if(lines.isEmpty())"no typed difference" else lines.joinToString("; ")
    }
}

/** External provider-call accounting from the captured observation events. */
internal object JourneyEvents {
    private val TERMINAL=setOf("completed","failed","cancelled","discarded")
    fun collect(api:ApplicationProtocol,traces:List<String>):List<JSONObject> {
        val result=mutableListOf<JSONObject>()
        for(trace in traces.distinct()) {
            if(trace.isEmpty())continue
            var cursor=""
            do{val page=api.events(trace,cursor,200);result.addAll(page.events);cursor=page.cursor}while(cursor.isNotEmpty())
        }
        return result
    }
    fun calls(api:ApplicationProtocol,traces:List<String>):List<JSONObject> = collect(api,traces).filter{it.optString("stage")=="transport"&&it.optString("outcome")=="received"}
    fun pending(api:ApplicationProtocol,traces:List<String>):List<JSONObject> {
        val events=collect(api,traces)
        val terminal=events.filter{it.optString("stage")=="transport"&&it.optString("outcome") in TERMINAL}.map{it.optString("span")}.toSet()
        return events.filter{it.optString("stage")=="transport"&&it.optString("outcome")=="received"&&it.optString("span") !in terminal}
    }
    /** Pending calls admitted after a marker, so residue from an earlier close cannot stand in. */
    fun pendingSince(api:ApplicationProtocol,traces:List<String>,since:Long):List<JSONObject> =
        pending(api,traces).filter{it.optLong("sequence",-1)>since}
    fun latestSequence(api:ApplicationProtocol,traces:List<String>):Long =
        collect(api,traces).maxOfOrNull{it.optLong("sequence",-1)}?:-1L
    fun target(call:JSONObject)=call.optString("target")
}

/** One journey's report surface: typed checks, located failures, limitations and captured evidence. */
private class Scenario(val name:String,val api:ApplicationProtocol,val experience:Experience,val directory:java.io.File,
    val report:JSONObject,val checks:JSONArray,val limitations:JSONArray,val traces:MutableList<String>,var session:Session?,val declared:Set<String>) {
    val modules:List<String> = declared.map{it.substringBeforeLast('.')}.distinct()

    fun live():Session=session?:located("$name.session","the journey requires a live Session")
    fun snapshot():UiSnapshot=api.next(live(),"").snapshot
    fun passed(description:String){checks.put(description)}
    fun limited(obligation:String,reason:String){limitations.put(JSONObject().put("obligation",obligation).put("reason",reason))}
    /** The journey state that makes a required expectation unproven; it is reported, not hidden. */
    var blockedReason:String?=null
    fun blocked(location:String,reason:String) {
        if(blockedReason==null)blockedReason="$location: $reason"
        limitations.put(JSONObject().put("obligation",location).put("reason",reason).put("blocking",true))
    }
    fun failed(location:String,reason:String):Nothing=located(location,reason)
    fun trace(session:Session?) {
        if(session==null)return
        val value=runCatching{api.sessionTrace(session)}.getOrNull()?:return
        if(value.isNotEmpty()&&value !in traces)traces.add(value)
    }
    fun evidence(suffix:String,tree:JSONObject){java.io.File(directory,"$name-$suffix.json").writeText(tree.toString())}
    fun closeLive() {
        val live=session?:return
        session=null
        try{api.close(live)}catch(_:Throwable){}
    }
    fun writeEvents() {
        val events=JourneyEvents.collect(api,traces)
        java.io.File(directory,"$name-events.json").writeText(JSONArray(events).toString())
        report.put("providerCalls",JSONArray(events.filter{it.optString("stage")=="transport"&&it.optString("outcome")=="received"}.map{JourneyEvents.target(it)}))
    }
    fun calls():List<JSONObject> {
        val health=api.health()
        if(health.incomplete)failed("$name.observation","observation is incomplete (code='${health.code}', lost=${health.lost}); provider-call evidence cannot prove the journey")
        return JourneyEvents.calls(api,traces)
    }
    fun grantReads(){for(module in modules)api.grant(module)}
    fun revokeReads(){for(module in modules)api.revoke(module)}
    fun waitForIncrease(location:String,previous:Int,timeoutMs:Long=30000) {
        val deadline=android.os.SystemClock.elapsedRealtime()+timeoutMs
        while(true) {
            if(calls().size>previous)return
            if(android.os.SystemClock.elapsedRealtime()>deadline)failed(location,"the explicit action admitted no external provider call (still $previous)")
            Thread.sleep(20)
        }
    }
    /** The observed call count must stay identical for the whole settle window, not just one sample. */
    fun expectCalls(location:String,expected:Int,windowMs:Long=1500) {
        val deadline=android.os.SystemClock.elapsedRealtime()+windowMs
        while(true) {
            val observed=calls()
            if(observed.size!=expected)failed(location,"observed ${observed.size} external provider calls ${observed.map{JourneyEvents.target(it)}}, expected $expected")
            if(android.os.SystemClock.elapsedRealtime()>=deadline)return
            Thread.sleep(50)
        }
    }
    fun assertReadOnly(location:String) {
        val observed=calls()
        val targets=observed.map{JourneyEvents.target(it)}
        val unexpected=targets.filter{it !in declared}
        if(unexpected.isNotEmpty())failed(location,"calls outside the disclosed read set were made: $unexpected")
        val mutating=targets.filter{target->MUTATION_WORDS.any{target.substringAfterLast('.').contains(it,true)}}
        if(mutating.isNotEmpty())failed(location,"a mutating provider call was made: $mutating")
        passed("no mutating provider call: ${observed.size} read(s) ${targets.distinct()}")
    }
    fun dispatchSlot(slot:Int,payload:String?=null){api.dispatch(live(),slot,payload)}
    /** All explicit load/refresh controls, dispatched once each, in tree order. */
    fun dispatchAllLoads(location:String) {
        val markers=listOf("load","menu","delivery","catalogue","quote","equipment","refresh","estimate")
        val slots=ViewTree.entries(snapshot().tree).filter{ViewTree.component(it.node) in ViewTree.CONTROLS&&ViewTree.actionSlot(it.node)!=null&&markers.any{m->it.markers.any{s->s.contains(m,true)}}}.map{ViewTree.actionSlot(it.node)!!}.distinct()
        if(slots.isEmpty())failed("$location.action","no explicit authorized load action is exposed (no Button/IconButton/Cell labelled load/menu/delivery/catalogue/quote/refresh)")
        for(slot in slots.take(4)) {
            val before=calls().size
            val version=snapshot().version
            dispatchSlot(slot)
            val deadline=android.os.SystemClock.elapsedRealtime()+10000
            while(android.os.SystemClock.elapsedRealtime()<deadline) {
                Thread.sleep(100)
                if(calls().size>before||snapshot().version!=version)break
            }
        }
    }
    fun actionPreferred(location:String,sets:List<List<String>>,tree:JSONObject=snapshot().tree):Pair<JSONObject,Int> {
        for(set in sets) {
            val found=ViewTree.entries(tree).filter{ViewTree.component(it.node) in ViewTree.CONTROLS&&ViewTree.actionSlot(it.node)!=null&&set.any{m->it.markers.any{s->s.contains(m,true)}}}
            if(found.isEmpty())continue
            val entry=ViewTree.select("$location.action",found,set,"action")
            return entry.node to ViewTree.actionSlot(entry.node)!!
        }
        failed("$location.action","no action exposes ${sets.joinToString(" ; "){it.joinToString("|")}}")
    }
    fun editable(location:String,markers:List<String>,tree:JSONObject=snapshot().tree):Pair<JSONObject,Int> {
        val found=ViewTree.entries(tree).filter{ViewTree.component(it.node) in ViewTree.EDITABLE&&ViewTree.actionSlot(it.node)!=null&&markers.any{m->it.markers.any{s->s.contains(m,true)}}}
        val entry=ViewTree.select(location,found,markers,"editable control")
        return entry.node to ViewTree.actionSlot(entry.node)!!
    }
    fun editablePreferred(location:String,sets:List<List<String>>,tree:JSONObject=snapshot().tree):Pair<JSONObject,Int> {
        for(set in sets) {
            val found=ViewTree.entries(tree).filter{ViewTree.component(it.node) in ViewTree.EDITABLE&&ViewTree.actionSlot(it.node)!=null&&set.any{m->it.markers.any{s->s.contains(m,true)}}}
            if(found.isEmpty())continue
            val entry=ViewTree.select(location,found,set,"editable control")
            return entry.node to ViewTree.actionSlot(entry.node)!!
        }
        failed(location,"no editable control exposes ${sets.joinToString(" ; "){it.joinToString("|")}}")
    }
    fun edit(location:String,markers:List<String>,value:String):UiSnapshot {
        val before=snapshot()
        val (_,slot)=editable(location,markers,before.tree)
        api.dispatch(live(),slot,value)
        val after=api.next(live(),"").snapshot
        if(after.fault.isNotEmpty())failed(location,"the edit faulted the Session: ${after.fault}")
        if(after.version<=before.version)failed(location,"the edit of ${markers.joinToString("|")} committed no revision (still ${after.version})")
        evidence("edit-${after.version}",after.tree)
        return after
    }
    /** An edit whose draft is expected to be invalid: it must reach the view so that the dependent results
     * can withdraw. A payload the transport or the view refuses before commit records no Session fault and
     * commits no revision, so the draft never became an invalid draft: that returns null so the caller can
     * try another spelling. A faulted Session, or a dispatch that changed state while throwing, is a real
     * fault and is never downgraded. */
    fun invalidEdit(location:String,markers:List<String>,value:String):UiSnapshot? {
        val before=snapshot()
        val (_,slot)=editable(location,markers,before.tree)
        try{api.dispatch(live(),slot,value)}catch(error:Throwable){
            val after=runCatching{snapshot()}.getOrNull()
                ?:failed(location,"the dispatch of the draft '$value' failed and the Session no longer publishes a snapshot: ${error.message ?: error.javaClass.simpleName}")
            if(after.fault.isEmpty()&&after.version<=before.version)return null
            failed(location,"the dispatch of the draft '$value' failed and left the Session faulted or changed (fault='${after.fault}', version ${before.version}->${after.version}): ${error.message ?: error.javaClass.simpleName}")
        }
        val after=api.next(live(),"").snapshot
        if(after.fault.isNotEmpty())failed(location,"the edit faulted the Session: ${after.fault}")
        if(after.version<=before.version)failed(location,"the draft '$value' committed no revision (still ${after.version}), so the view neither refused it at ingress nor committed it")
        evidence("edit-${after.version}",after.tree)
        return after
    }
    /** An edit ingress may legitimately refuse a draft, which records no fault and commits no revision: the
     * draft never reached the view. A real fault is never downgraded. A refusal leaves the edit unproven, so
     * the journey reports a located blocker carrying the reason the caller names for the observation it
     * could not make. */
    fun tryEdit(location:String,markers:List<String>,value:String,rejected:String="no invalid draft existed to withdraw dependent results"):UiSnapshot? {
        val before=snapshot()
        val (_,slot)=editable(location,markers,before.tree)
        try{api.dispatch(live(),slot,value)}catch(error:Throwable){
            val after=runCatching{snapshot()}.getOrNull()
                ?:failed(location,"the dispatch of '$value' failed and the Session no longer publishes a snapshot: ${error.message ?: error.javaClass.simpleName}")
            if(after.fault.isEmpty()&&after.version<=before.version) {
                blocked(location,"the dispatch of '$value' was refused before it reached the view (${error.message ?: error.javaClass.simpleName}), so $rejected")
                return null
            }
            failed(location,"the dispatch of '$value' failed and left the Session faulted or changed (fault='${after.fault}'): ${error.message ?: error.javaClass.simpleName}")
        }
        val after=api.next(live(),"").snapshot
        if(after.fault.isNotEmpty())failed(location,"the edit faulted the Session: ${after.fault}")
        evidence("edit-${after.version}",after.tree)
        return after
    }
    /** An invalid draft of one editable control must withdraw the dependent evidence, or the framework
     * may legitimately reject the draft at ingress. Candidate spellings are tried in order because the
     * generated control decides which draft is invalid, and a draft the control accepts as valid cannot
     * prove a withdrawal. Every candidate rejected at ingress leaves the required expectation unproven:
     * that is a located blocker, never a product failure. Returns the spelling and the snapshot that
     * withdrew the evidence, or null after reporting the blocker or the located failure. */
    fun invalidDraft(location:String,markers:List<String>,candidates:List<String>,withdrawn:(JSONObject)->Boolean):Pair<String,UiSnapshot>? {
        val rejected=mutableListOf<String>()
        val accepted=mutableListOf<String>()
        for(candidate in candidates) {
            val draft=invalidEdit(location,markers,candidate)
            if(draft==null){rejected.add(candidate);continue}
            if(withdrawn(draft.tree))return candidate to draft
            accepted.add(candidate)
        }
        if(rejected.size==candidates.size)
            blocked(location,"the generated control refused every invalid draft (${rejected.joinToString(", ")}) before it reached the view, so no invalid draft existed to withdraw dependent results")
        else failed(location,"the draft(s) ${accepted.joinToString(", ")} the control accepted as valid left the dependent evidence current, so no tested spelling was an invalid draft"+
            if(rejected.isEmpty()) "" else " (refused at ingress: ${rejected.joinToString(", ")})")
        return null
    }
    fun await(location:String,timeoutMs:Long=30000,accept:(UiSnapshot)->Boolean):UiSnapshot {
        val deadline=android.os.SystemClock.elapsedRealtime()+timeoutMs
        var latest=snapshot()
        while(!accept(latest)) {
            if(android.os.SystemClock.elapsedRealtime()>deadline)failed(location,"timed out; fault='${latest.fault}' tree=${latest.tree.toString().take(1200)}")
            Thread.sleep(20)
            latest=snapshot()
        }
        evidence("ready-${latest.version}",latest.tree)
        return latest
    }
    fun reopen(location:String):UiSnapshot {
        val before=calls().size
        api.close(live());session=null
        val opened=api.run(experience);session=opened;trace(opened)
        val restored=api.next(opened,"").snapshot
        if(restored.fault.isNotEmpty())failed(location,"the cold reopen faulted: ${restored.fault}")
        expectCalls("$location.replay",before)
        evidence("reopened",restored.tree)
        return restored
    }
    fun preservedString(location:String,tree:JSONObject,markers:List<String>,what:String) {
        val found=ViewTree.allStrings(tree).filter{text->markers.any{text.contains(it,true)}}
        if(found.isEmpty())failed(location,"$what is not preserved as a typed string (no string prop contains ${markers.joinToString("|")})")
    }
    /** A required item and each required attribute of that item must be named by one published item node
     * (see `JourneyTimes.namedItem`). A view that publishes no string naming the item leaves the requirement
     * unproven, which is a located blocker; one that publishes the item without one of its required
     * attributes states the requirement is unmet, which is a located failure. Returns the item node. */
    fun preservedItem(location:String,tree:JSONObject,item:String,attributes:List<String>,what:String):JSONObject? {
        JourneyTimes.namedItemNode(tree,item,attributes)?.let{return it.first}
        val named=JourneyTimes.namedItemNode(tree,item,emptyList())
        if(named==null)blocked(location,"the view publishes no string naming $item, so $what is unproven")
        else failed(location,"the published item \"${named.second.take(160)}\" does not name ${attributes.joinToString(" and ")} as whole words, so $what is unmet")
        return null
    }
    fun preservedNumber(location:String,tree:JSONObject,markers:List<String>,accepted:List<Double>,what:String) {
        ViewTree.matching(location,tree,listOf(markers),ViewTree.EDITABLE+ViewTree.NUMERIC,accepted,"$what is not preserved as a typed number")
    }
    fun preservedDate(location:String,tree:JSONObject,iso:String,human:String) {
        val text=ViewTree.allStrings(tree).any{it.contains(iso)||it.contains(human,true)}
        val day=fixtureEpochDay()
        val field=ViewTree.entries(tree).any{ViewTree.component(it.node)=="DateField"&&ViewTree.number(it.node,"value")==day}
        if(!text&&!field)failed(location,"the disclosed date is not preserved as a typed string ($iso/$human) or a DateField epoch day $day")
    }
    /** Minor units per labelled unit on a typed money value, disclosed by its own label or affixes. */
    fun moneyScale(location:String,node:JSONObject):Double {
        val text=ViewTree.ownMarkers(node).joinToString(" ").lowercase()
        val cents=Regex("(?i)\\bcents?\\b").containsMatchIn(text)
        val euros=Regex("(?i)\\beuros?\\b|€").containsMatchIn(text)
        return when {
            cents->1.0
            euros->100.0
            else->failed(location,"the typed value's unit is not disclosed in its label or affixes (no EUR/cent), so the EUR constraint cannot be checked")
        }
    }
    /** Minor units per field unit of the disclosed budget control, from its affix or value. */
    fun budgetScale(location:String,node:JSONObject,major:Double):Double {
        val text=ViewTree.ownMarkers(node).joinToString(" ").lowercase()
        if(Regex("(?i)\\bcents?\\b").containsMatchIn(text))return 1.0
        if(Regex("(?i)\\beuros?\\b|€").containsMatchIn(text))return 100.0
        return when(ViewTree.number(node,"value")) {
            major->100.0
            major*100->1.0
            else->failed(location,"the budget control's unit is not disclosed (no EUR/cent) and its value is not the disclosed $major EUR, so the budget constraint cannot be checked")
        }
    }
    /** A computed instant that must keep the previous task day rather than wrap onto the task day. No
     * usable evidence leaves the expectation unproven, which is a located blocker; a same-day wrap is a
     * located failure. Returns true only when proven. */
    fun preservedRollover(location:String,tree:JSONObject,markers:List<String>,minutes:Double):Boolean {
        val evidence=JourneyTimes.rollover(tree,markers,minutes)
        return when(evidence.outcome) {
            TimeOutcome.Proven->true
            TimeOutcome.Contradicted->failed(location,evidence.detail)
            TimeOutcome.Unproven->{
                blocked(location,"${evidence.detail}, so the preserved previous-day instant is unproven")
                false
            }
        }
    }
    /** A disclosed window the journey must prove: a typed window that agrees with every end, or the
     * disclosed spelling of both ends. No usable evidence leaves the expectation unproven, which is a
     * located blocker; a contradictory window is a located failure. Returns true only when proven. */
    fun preservedWindow(location:String,tree:JSONObject,markers:List<String>,minutes:List<Double>,human:List<String>):Boolean {
        val evidence=JourneyTimes.window(tree,markers,minutes,human)
        return when(evidence.outcome) {
            TimeOutcome.Proven->true
            TimeOutcome.Contradicted->failed(location,evidence.detail)
            TimeOutcome.Unproven->{
                blocked(location,"${evidence.detail}, so the disclosed ${minutes.joinToString(" and ")} minutes of the task day are unproven")
                false
            }
        }
    }
    /** A typed arrival instant that satisfies the disclosed before-18:30 constraint. No arrival the
     * journey can interpret leaves the expectation unproven, which is a located blocker; a published
     * arrival at or after the deadline is a located failure. Returns true only when it is proven. */
    fun arrivalBefore(location:String,tree:JSONObject,markers:List<List<String>>,deadline:Double):Boolean {
        val evidence=JourneyTimes.arrival(tree,markers,deadline)
        return when(evidence.outcome) {
            TimeOutcome.Proven->true
            TimeOutcome.Contradicted->failed(location,evidence.detail)
            TimeOutcome.Unproven->{
                blocked(location,"${evidence.detail}, so the disclosed before-18:30 constraint is unproven")
                false
            }
        }
    }
    fun totalMinor(location:String,tree:JSONObject,sets:List<List<String>>):Double {
        val (node,value)=ViewTree.uniqueAny(location,tree,sets,ViewTree.NUMERIC,"the provider-derived total is not a typed number")
        return value*moneyScale(location,node)
    }
    /** Every feasibility classification the tree publishes, with the node that carries it: the typed and
     * status props when any is published, otherwise the explanatory text. Two distinct states are a
     * contradiction the callers refuse to resolve rather than a classification. */
    fun feasibilityStates(tree:JSONObject):List<Pair<JSONObject,String>> {
        val primary=mutableListOf<Pair<JSONObject,String>>()
        val fallback=mutableListOf<Pair<JSONObject,String>>()
        fun classify(lower:String,into:MutableList<Pair<JSONObject,String>>,node:JSONObject) {
            if(listOf("infeasible","exceeds","over budget","not feasible","does not fit").any{lower.contains(it)})into.add(node to "infeasible")
            else if(listOf("feasible","within budget","under budget","fits").any{lower.contains(it)})into.add(node to "feasible")
        }
        for(node in ViewTree.nodes(tree)) {
            for(value in ViewTree.props(node)) {
                if(value.optString("kind")=="boolean"&&value.optString("name") in setOf("feasible","withinBudget","available","fits")) {
                    primary.add(node to (if(value.optBoolean("booleanValue"))"feasible" else "infeasible"))
                }
                if(value.optString("kind")!="string")continue
                val lower=value.optString("stringValue").lowercase()
                if(value.optString("name") in setOf("status","state","supporting"))classify(lower,primary,node)
                else if(value.optString("name") in setOf("text","label","detail","message","suffix","prefix"))classify(lower,fallback,node)
            }
        }
        val states=if(primary.isNotEmpty())primary else fallback
        return states.map{it.second}.distinct().map{state->states.first{it.second==state}}
    }
    /** Typed feasibility classification published by the recomputed constraints, when it is unambiguous. */
    fun feasibility(tree:JSONObject):String?=feasibilityStates(tree).singleOrNull()?.second
    fun requireFeasible(location:String,tree:JSONObject,expected:Boolean) {
        val state=feasibility(tree)?:failed(location,"the tree publishes no unambiguous feasibility/status result for the recomputed constraints")
        if((state=="feasible")!=expected)failed(location,"feasibility is '$state', expected ${if(expected)"feasible" else "infeasible"}")
    }
    fun explanationOrNull(tree:JSONObject):String?=ViewTree.allStrings(tree).firstOrNull{text->listOf("unavailable","not granted","denied","missing","no data","cannot be").any{text.contains(it,true)}}
    fun explanation(location:String,tree:JSONObject):String=explanationOrNull(tree)?:failed(location,"missing or unavailable provider data is not explained in the tree")
    fun budgetExplanation(location:String,tree:JSONObject):String=ViewTree.nodes(tree)
        .filter{ViewTree.actionSlot(it)==null&&ViewTree.component(it) !in ViewTree.EDITABLE}
        .flatMap{node->ViewTree.props(node).filter{it.optString("kind")=="string"}.map{it.optString("stringValue")}}
        .firstOrNull{text->!text.trim().equals("infeasible",true)&&!text.trim().equals("feasible",true)&&
            Regex("(?i)over budget|insufficient (budget|funds)|cannot afford|exceed(s|ing)?\\s+(the\\s+)?(budget|funds|EUR\\s*\\d|\\d)").containsMatchIn(text)}
        ?:failed(location,"the withdrawn feasibility is not explained with the budget constraint that failed")
    fun quantities(tree:JSONObject):List<Pair<JSONObject,Double>> {
        fun rows(components:Set<String>) = ViewTree.numbers(tree,PIZZA_ROWS,components)
            .filter{entry->ViewTree.ownMarkers(entry.first).none{it.contains("total",true)}}
        val editable=rows(setOf("NumberField","Slider"))
        if(editable.isNotEmpty())return editable
        return rows(setOf("NumberListItem","NumberStat","NumberText"))
    }
    fun assertAllocation(location:String,tree:JSONObject,total:Double=6.0) {
        val rows=quantities(tree)
        if(rows.isEmpty())failed(location,"the allocation is not exposed as typed quantities (no NumberField/NumberListItem/NumberStat value identifying a 30 cm pizza row)")
        for((node,value) in rows) if(value<0||value!=kotlin.math.floor(value))failed(location,"a pizza quantity is not a nonnegative integer: $value at ${ViewTree.ownMarkers(node)}")
        val sum=rows.sumOf{it.second}
        if(sum!=total)failed(location,"the typed allocation totals $sum pizzas, expected $total")
        val vegan=rows.filter{entry->ViewTree.ownMarkers(entry.first).any{it.contains("vegan",true)}}.sumOf{it.second}
        if(vegan<2.0)failed(location,"the typed allocation includes $vegan vegan pizzas, expected at least two")
    }
    fun quantityRow(location:String,tree:JSONObject):Triple<JSONObject,Int,Double> {
        val rows=quantities(tree).filter{ViewTree.component(it.first) in ViewTree.EDITABLE&&ViewTree.actionSlot(it.first)!=null}
        if(rows.isEmpty())failed(location,"pizza quantities are not exposed as editable typed numbers")
        val row=rows.first()
        val label=ViewTree.ownMarkers(row.first).maxByOrNull{it.length}?:failed(location,"the pizza quantity control has no identifying label")
        return Triple(row.first,ViewTree.actionSlot(row.first)!!,row.second)
    }
    fun packageAndSeparate(location:String,tree:JSONObject):Triple<Double,Double,Double> {
        val packageNode=ViewTree.uniqueAny("$location.package",tree,EQUIPMENT_PACKAGE,ViewTree.NUMERIC,"the package whole-basket total is not a typed number")
        val separateNode=ViewTree.uniqueAny("$location.separate",tree,EQUIPMENT_SEPARATE,ViewTree.NUMERIC,"the separate whole-basket total is not a typed number")
        val depositNode=ViewTree.uniqueAny("$location.deposit",tree,listOf(listOf("deposit"),listOf("refundable")),ViewTree.NUMERIC,"the refundable deposit is not shown as its own typed value")
        if(depositNode.first===packageNode.first||depositNode.first===separateNode.first)failed("$location.deposit","the refundable deposit is not its own typed value")
        val packageTotal=packageNode.second*moneyScale("$location.package",packageNode.first)
        val separateTotal=separateNode.second*moneyScale("$location.separate",separateNode.first)
        val deposit=depositNode.second*moneyScale("$location.deposit",depositNode.first)
        if(deposit<0)failed("$location.deposit","the refundable deposit is negative: $deposit")
        return Triple(packageTotal,separateTotal,deposit)
    }
    /** The required XLR cable length, read only from the values one required item publishes (see
     * `JourneyTimes.itemValues`) rather than from a spare cable elsewhere in the tree. A published length
     * with a readable unit that meets the minimum proves it, so a shorter cable the catalogue also offers
     * never vetoes the required one; a published length that no candidate meets is the requirement
     * contradicted, which is a located failure; an item the view never named, or a length the harness cannot
     * read, leaves it unproven, which is a located blocker. */
    fun cableLength(location:String,tree:JSONObject,item:JSONObject?):String? {
        val found=JourneyTimes.itemValues(tree,item,"xlr",listOf("xlr","cable"))
        val unreadable=mutableListOf<Double>()
        val tooShort=mutableListOf<String>()
        for((node,value) in found) {
            val unit=ViewTree.props(node).filter{it.optString("name") in setOf("prefix","suffix")}.joinToString(" ").lowercase()
            val millimetres=Regex("(?i)\\bmm\\b|millimet").containsMatchIn(unit)
            val metres=Regex("(?i)\\bm\\b|metre|meter").containsMatchIn(unit)
            if(millimetres&&value>=10000)return "$value mm"
            if(metres&&value>=10)return "$value m"
            if(millimetres)tooShort.add("$value mm") else if(metres)tooShort.add("$value m") else unreadable.add(value)
        }
        if(tooShort.isNotEmpty())failed(location,"every published XLR cable length in the required cable's row is below the required minimum 10 m: ${tooShort.distinct()}")
        blocked(location,"the required XLR cable length is not published in the required cable's row as a typed number with a readable m/mm unit affix"+
            if(unreadable.isEmpty()) "" else " (published ${unreadable.distinct()} carry no m/mm unit)")
        return null
    }
    fun instantValue(location:String,tree:JSONObject,sets:List<List<String>>,minuteOfDay:Double):Double {
        val accepted=JourneyTimes.spellings(minuteOfDay)
        return ViewTree.matching(location,tree,sets,ViewTree.INSTANT,accepted,"the computed instant is not a typed number (ClockText minutes, NumberText value or Timer millis)")
    }
    fun assertInstants(location:String,tree:JSONObject,departure:Double,inspection:Double) {
        instantValue("$location.departure",tree,DEPARTURE_INSTANTS,departure)
        instantValue("$location.inspection",tree,INSPECTION_INSTANTS,inspection)
    }
    fun hasInstants(tree:JSONObject,departure:Double,inspection:Double):Boolean=runCatching{assertInstants("probe",tree,departure,inspection)}.isSuccess
    fun hasAnyInstant(tree:JSONObject):Boolean = ViewTree.numbers(tree,DEPARTURE_INSTANTS.flatten()+INSPECTION_INSTANTS.flatten(),ViewTree.INSTANT).isNotEmpty()
    /** Typed returned travel duration in minutes, when the generated view publishes it. */
    fun travelMinutes(tree:JSONObject):Double? {
        for((node,value) in ViewTree.numbers(tree,listOf("travel","duration"),ViewTree.NUMERIC)) {
            val unit=ViewTree.props(node).filter{it.optString("name") in setOf("prefix","suffix")}.joinToString(" ").lowercase()
            if(Regex("(?i)millis|ms").containsMatchIn(unit)||value>600.0)return value/60000.0
            return value
        }
        return null
    }
    /** Changed arguments must withdraw the dependent evidence, or display it explicitly staled. A value's
     * own labels always carry that disclosure, and an invalidated reply may also be staled by its own
     * section, which is where a section-level banner sits. A marker published for another section never
     * stales this value, and an invalid draft accepts no such disclosure at all: the spec makes an invalid
     * display result null and unready. */
    fun assertWithdrawn(location:String,tree:JSONObject,sets:List<List<String>>,components:Set<String> = ViewTree.NUMERIC,staleAcceptable:Boolean=false) {
        for(set in sets) {
            val found=ViewTree.numbers(tree,set,components)
            if(found.isEmpty())continue
            val current=found.filter{entry->!staled(tree,entry.first,staleAcceptable)}
            if(current.isNotEmpty())failed(location,"changed arguments left a current typed value ${current.map{it.second}} for ${set.joinToString("|")}")
        }
    }
    /** Whether one published value is displayed staled by its own labels, which an invalid draft accepts. */
    fun ownStale(node:JSONObject):Boolean =
        ViewTree.ownMarkers(node).any{marker->ViewTree.WITHDRAWN_WORDS.any{marker.contains(it,true)}}
    /** Whether one value is displayed staled: by its own labels, or, when the caller accepts it, by the
     * stale state its own section publishes. */
    private fun staled(tree:JSONObject,node:JSONObject,section:Boolean):Boolean =
        ownStale(node)||(section&&ViewTree.staledValue(tree,node,ViewTree.WITHDRAWN_WORDS)!=null)
    /** Dispatch an admitted provider read and close the Session while it is still pending. A pending
     * transition that cannot be observed in flight is a required expectation left unproven, so it is a
     * located blocker, never a limitation on a checked journey. */
    fun pendingStale(location:String) {
        calls()
        val marker=JourneyEvents.latestSequence(api,traces)
        val load=actionPreferred("$location.action",listOf(listOf("refresh"),listOf("reload"),listOf("load"),listOf("menu"),listOf("catalogue"),listOf("quote"),listOf("estimate")))
        var pending=false
        var attempts=0
        while(attempts<4&&!pending) {
            attempts++
            dispatchSlot(load.second)
            val deadline=android.os.SystemClock.elapsedRealtime()+8000
            while(android.os.SystemClock.elapsedRealtime()<deadline) {
                if(JourneyEvents.pendingSince(api,traces,marker).isNotEmpty()){pending=true;break}
                Thread.sleep(2)
            }
        }
        if(!pending) {
            blocked("$location.pending","the provider read completed before the Session could be closed while it was pending, after $attempts bounded attempts, so the required restore-as-stale transition was never observed")
            return
        }
        val admitted=calls().size
        api.close(live());session=null
        val opened=api.run(experience);session=opened;trace(opened)
        val restored=api.next(opened,"").snapshot
        if(restored.fault.isNotEmpty())failed(location,"the cold reopen after a pending read faulted: ${restored.fault}")
        expectCalls("$location.replay",admitted)
        evidence("pending-reopened",restored.tree)
        val stale=ViewTree.stale(restored.tree)?:failed(location,"the snapshot exposes no stale/unready marker for the pending provider read; the public tree cannot prove the pending work was restored as stale")
        val refresh=ViewTree.refreshActions(restored.tree).firstOrNull()?:failed(location,"the restored tree offers no explicit refresh action for the stale provider work")
        val beforeRefresh=calls().size
        dispatchSlot(refresh.second)
        waitForIncrease("$location.refresh",beforeRefresh,40000)
        passed("a pending provider read was closed in flight, restored as stale with an explicit refresh that dispatched a new read and no call replayed: ${stale.take(120)}")
    }
}

// ---------------------------------------------------------------------------------------------
// Journeys. Every expectation is a typed value, a typed classification or a located failure.
// ---------------------------------------------------------------------------------------------

private fun budgetJourney(s:Scenario,initial:UiSnapshot) {
    BudgetAssertions.result(initial.tree,1750,"Over");s.passed("initial committed-minus-approved difference is 1750 EUR cents and over budget")
    for((value,expected,status) in listOf(Triple("17000",-1000L,"Under"),Triple("18000",0L,"Equal"),Triple("19750",1750L,"Over"))) {
        BudgetAssertions.result(s.edit("budget.committed",listOf("Committed"),value).tree,expected,status)
        s.passed("committed $value with approved 18000 gives $expected EUR cents, $status")
    }
    BudgetAssertions.result(s.edit("budget.approved",listOf("Approved"),"20000").tree,-250,"Under")
    s.passed("approved 20000 with committed 19750 gives -250 EUR cents, Under")
    s.edit("budget.approved.restore",listOf("Approved"),"18000")
    // An invalid draft withdraws the typed difference and its classification; a valid draft recovers
    // both. A malformed payload never reaches the view, so the candidates are well-formed numbers the
    // control has to refuse as invalid drafts: a fractional minor unit and an unsafe integer.
    val invalid=s.invalidDraft("budget.invalid",listOf("Committed"),listOf("1.5","9007199254740992")){tree->runCatching{BudgetAssertions.withdrawn(tree)}.isSuccess}
    if(invalid!=null)s.passed("the invalid committed draft '${invalid.first}' withdraws the typed difference and its classification")
    val recovered=s.edit("budget.recovery",listOf("Committed"),"19750")
    BudgetAssertions.result(recovered.tree,1750,"Over");s.passed("a valid draft recovers 1750 EUR cents, Over")
    s.expectCalls("budget.reads",0);s.passed("the budget journey performed no external provider read or module call")
    val typed=ViewTree.numbersSnapshot(recovered.tree)
    val difference=ViewTree.unique("budget.difference",recovered.tree,listOf("difference"),ViewTree.NUMERIC,"typed difference").second
    val committed=ViewTree.unique("budget.draft.committed",recovered.tree,listOf("committed"),ViewTree.EDITABLE,"typed committed draft").second
    val approved=ViewTree.unique("budget.draft.approved",recovered.tree,listOf("approved"),ViewTree.EDITABLE,"typed approved draft").second
    if(difference!=1750.0||committed!=19750.0||approved!=18000.0)s.failed("budget.drafts","typed values published as difference=$difference committed=$committed approved=$approved")
    val restored=s.reopen("budget.reopen")
    val reopened=ViewTree.numbersSnapshot(restored.tree)
    if(reopened!=typed)s.failed("budget.reopen","cold reopen changed typed values: ${ViewTree.difference(typed,reopened)}")
    val reopenedDifference=ViewTree.unique("budget.reopen.difference",restored.tree,listOf("difference"),ViewTree.NUMERIC,"typed difference").second
    val reopenedCommitted=ViewTree.unique("budget.reopen.committed",restored.tree,listOf("committed"),ViewTree.EDITABLE,"typed committed draft").second
    val reopenedApproved=ViewTree.unique("budget.reopen.approved",restored.tree,listOf("approved"),ViewTree.EDITABLE,"typed approved draft").second
    if(reopenedDifference!=1750.0||reopenedCommitted!=19750.0||reopenedApproved!=18000.0)s.failed("budget.reopen","cold reopen published difference=$reopenedDifference committed=$reopenedCommitted approved=$reopenedApproved")
    BudgetAssertions.result(restored.tree,1750,"Over")
    s.passed("cold reopen restored the typed difference 1750 and both typed drafts with no provider call between close and reopen")
}

private fun departureJourney(s:Scenario,initial:UiSnapshot) {
    s.grantReads();s.passed("authorized the disclosed read ${s.modules.joinToString(", ")}")
    s.expectCalls("departure.initial",0)
    val inputs=s.snapshot().tree
    s.preservedNumber("departure.input.distance",inputs,listOf("distance"),listOf(12.0),"the disclosed 12 km distance as an editable number")
    s.preservedNumber("departure.input.unloading",inputs,listOf("unloading"),listOf(15.0),"the disclosed 15-minute unloading buffer as an editable number")
    s.preservedNumber("departure.input.inspection",inputs,listOf("inspection"),listOf(10.0),"the disclosed 10-minute pre-departure inspection buffer as an editable number")
    s.preservedString("departure.input.venue",inputs,listOf("The Foundry"),"the disclosed collection venue")
    s.preservedDate("departure.input.date",inputs,"2026-10-23","23 October 2026")
    if(s.preservedWindow("departure.input.window",inputs,listOf("window","from","until","start","end"),listOf(960.0,1020.0),listOf("16:00","17:00")))
        s.passed("the disclosed 16:00-17:00 Europe/Berlin collection window is preserved, both ends as disclosed spelling or as the typed times 960 and 1020 minutes of the task day")
    s.passed("venue, date, 12 km distance and the 15/10 minute buffers are preserved as typed inputs")
    val load=s.actionPreferred("departure.load",listOf(listOf("travel estimate"),listOf("estimate"),listOf("load"),listOf("refresh")),inputs)
    s.dispatchSlot(load.second)
    s.waitForIncrease("departure.load",0)
    val loaded=s.await("departure.load",40000){s.hasAnyInstant(it.tree)}
    val travel=s.travelMinutes(loaded.tree)
    if(travel==null)s.limited("departure travel duration provenance","the typed returned travel duration is not published, so the expected instants are pinned to the fixture simulation's 29 and 55 minutes")
    val travel12=travel?:29.0
    val departure12=960.0-15.0-travel12
    s.assertInstants("departure.loaded",loaded.tree,departure12,departure12-10.0)
    s.passed("the explicit authorized load computed departure ${departure12} and inspection ${departure12-10.0} minutes as typed instants")
    s.expectCalls("departure.singleRead",1)
    val moved=s.edit("departure.start",listOf("start"),"970")
    s.assertInstants("departure.moved",moved.tree,departure12+10.0,departure12)
    s.expectCalls("departure.bufferEdit",1);s.passed("a start edit recomputed departure/inspection without a second provider read")
    val reset=s.edit("departure.start.restore",listOf("start"),"960")
    s.assertInstants("departure.reset",reset.tree,departure12,departure12-10.0)
    // The disclosed buffers are editable too: a buffer edit recomputes both instants with no new read.
    val unloading=s.edit("departure.unloading",listOf("unloading"),"20")
    s.assertInstants("departure.unloading",unloading.tree,departure12-5.0,departure12-5.0-10.0)
    s.expectCalls("departure.unloadingCalls",1)
    val inspection=s.edit("departure.inspection",listOf("inspection"),"12")
    s.assertInstants("departure.inspection",inspection.tree,departure12-5.0,departure12-5.0-12.0)
    s.expectCalls("departure.inspectionCalls",1)
    s.edit("departure.unloading.restore",listOf("unloading"),"15")
    val buffers=s.edit("departure.inspection.restore",listOf("inspection"),"10")
    s.assertInstants("departure.buffers.restored",buffers.tree,departure12,departure12-10.0)
    s.passed("editing the unloading and inspection buffers recomputed departure ${departure12} and inspection ${departure12-10.0} without another provider read")
    // Instants and rollover are preserved: a collection start at 00:10 with the 15 minute unloading and
    // the returned travel puts departure and inspection on the previous day. A view that silently wraps
    // them onto the task day is a located failure here; one that refuses the draft or publishes no such
    // instant blocks the journey instead.
    val rolled=s.tryEdit("departure.rollover",listOf("start"),"10","the previous-day instants cannot be observed")
    val editedStart=rolled!=null&&ViewTree.numbers(rolled.tree,listOf("start"),ViewTree.EDITABLE).any{(_,value)->value in JourneyTimes.spellings(10.0)}
    if(rolled==null) {
        // tryEdit located the blocker: the framework refused a 00:10 collection start at dispatch ingress.
    } else if(!editedStart) {
        s.blocked("departure.rollover.start","the edited 00:10 collection start is not published as the typed draft 10 minutes, so the previous-day instants cannot be observed")
    } else {
        val rolloverDeparture=10.0-15.0-travel12
        val rolloverInspection=rolloverDeparture-10.0
        val departureRolled=s.preservedRollover("departure.rollover.departure",rolled.tree,DEPARTURE_INSTANTS.flatten(),rolloverDeparture)
        val inspectionRolled=s.preservedRollover("departure.rollover.inspection",rolled.tree,INSPECTION_INSTANTS.flatten(),rolloverInspection)
        if(departureRolled&&inspectionRolled)
            s.passed("a 00:10 collection start keeps departure $rolloverDeparture and inspection $rolloverInspection minutes on the previous day, never a same-day wrap")
    }
    // The start is restored whenever the view accepted the draft, so the distance expectations below
    // keep their 16:00 collection start.
    if(rolled!=null)s.edit("departure.rollover.restore",listOf("start"),"960")
    val changed=s.edit("departure.distance",listOf("distance"),"20")
    s.assertWithdrawn("departure.distance",changed.tree,DEPARTURE_INSTANTS+INSPECTION_INSTANTS,ViewTree.INSTANT,staleAcceptable=true)
    s.expectCalls("departure.invalidate",1);s.passed("a changed distance invalidated the estimate without another provider read")
    val reload=s.actionPreferred("departure.reload",listOf(listOf("refresh"),listOf("reload"),listOf("load"),listOf("estimate")),changed.tree)
    s.dispatchSlot(reload.second)
    s.waitForIncrease("departure.reload",1)
    val reloaded=s.await("departure.reload",40000){s.hasAnyInstant(it.tree)}
    val travel20=s.travelMinutes(reloaded.tree)
    if(travel20==null)s.limited("departure 20 km travel duration provenance","the typed returned travel duration is not published, so the 20 km expectation is pinned to the fixture simulation's 55 minutes")
    val departure20=960.0-15.0-(travel20?:55.0)
    s.assertInstants("departure.reloaded",reloaded.tree,departure20,departure20-10.0)
    s.expectCalls("departure.reloadCalls",2);s.passed("20 km after the explicit reload gives departure ${departure20} and inspection ${departure20-10.0}")
    val invalid=s.invalidDraft("departure.invalid",listOf("distance"),listOf("-1")){tree->runCatching{s.assertWithdrawn("probe",tree,DEPARTURE_INSTANTS+INSPECTION_INSTANTS,ViewTree.INSTANT)}.isSuccess}
    if(invalid!=null)s.passed("the invalid distance draft '${invalid.first}' withdrew the dependent instants")
    s.expectCalls("departure.invalidCalls",2)
    s.edit("departure.distance.restore",listOf("distance"),"12")
    val reload2=s.actionPreferred("departure.reload2",listOf(listOf("refresh"),listOf("reload"),listOf("load"),listOf("estimate")))
    s.dispatchSlot(reload2.second)
    s.waitForIncrease("departure.reload2",2)
    val recovered=s.await("departure.reload2",40000){s.hasAnyInstant(it.tree)}
    s.assertInstants("departure.recovered",recovered.tree,departure12,departure12-10.0)
    s.expectCalls("departure.recoveredCalls",3)
    val notices=ViewTree.allStrings(recovered.tree).filter{it.contains("unchecked")||it.contains("missing implementing needs")||it.contains("missing original behavior")}
    if(notices.isNotEmpty())s.limited("departure semantic completeness","the generated view reports ${notices.take(3)}")
    s.assertReadOnly("departure.readOnly")
    val drafts=ViewTree.numbersSnapshot(recovered.tree,ViewTree.EDITABLE)
    val typed=ViewTree.numbersSnapshot(recovered.tree)
    val restored=s.reopen("departure.reopen")
    val reopenedDrafts=ViewTree.numbersSnapshot(restored.tree,ViewTree.EDITABLE)
    if(reopenedDrafts!=drafts)s.failed("departure.reopen","cold reopen changed the typed inputs: ${ViewTree.difference(drafts,reopenedDrafts)}")
    if(s.hasInstants(restored.tree,departure12,departure12-10.0)) {
        if(ViewTree.numbersSnapshot(restored.tree)!=typed)s.failed("departure.reopen","cold reopen changed typed values")
        s.passed("cold reopen restored the same typed instants and inputs with no provider call")
    } else {
        val stale=ViewTree.stale(restored.tree)?:s.failed("departure.reopen.stale","cold reopen neither restored the typed instants nor published an explicit stale state")
        if(ViewTree.refreshActions(restored.tree).isEmpty())s.failed("departure.reopen.stale","the stale travel estimate offers no explicit refresh action")
        val before=s.calls().size
        val refresh=s.actionPreferred("departure.reopen.refresh",listOf(listOf("refresh"),listOf("reload"),listOf("load"),listOf("estimate")),restored.tree)
        s.dispatchSlot(refresh.second)
        s.waitForIncrease("departure.reopen.refresh",before)
        val refreshed=s.await("departure.reopen.refresh",40000){s.hasAnyInstant(it.tree)}
        s.assertInstants("departure.reopen.refreshed",refreshed.tree,departure12,departure12-10.0)
        s.passed("cold reopen restored the typed inputs, published a stale state for the restored session (${stale.take(80)}) and an explicit refresh restored the same instants")
    }
    s.pendingStale("departure.pendingStale")
}

private fun foodJourney(s:Scenario,initial:UiSnapshot) {
    s.grantReads();s.passed("authorized the disclosed reads ${s.modules.joinToString(", ")}")
    s.expectCalls("food.initial",0)
    val inputs=s.snapshot().tree
    s.preservedString("food.input.entrance",inputs,listOf("entrance","The Foundry"),"the disclosed stage entrance")
    s.preservedString("food.input.deadline",inputs,listOf("18:30"),"the disclosed 18:30 deadline")
    s.passed("the stage entrance and the 18:30 deadline are preserved as typed strings")
    s.dispatchAllLoads("food.load")
    s.waitForIncrease("food.load",0)
    val ready=s.await("food.allocation",60000){snapshot->runCatching{s.assertAllocation("probe",snapshot.tree)}.isSuccess}
    s.assertAllocation("food.allocation",ready.tree)
    s.passed("the allocation is complete: six 30 cm pizzas with at least two vegan as typed quantities")
    val total=s.totalMinor("food.total",ready.tree,PIZZA_TOTALS)
    if(total>8000.0)s.failed("food.budget","the delivered total is ${total/100} EUR, above the disclosed EUR 80")
    if(total<100.0)s.failed("food.budget","the delivered total ${total/100} EUR is implausibly small for six pizzas; the money scale is probably inverted")
    s.passed("the delivered total ${total/100} EUR including delivery is a typed minor-unit value within EUR 80")
    s.requireFeasible("food.feasible",ready.tree,true)
    s.passed("the loaded allocation publishes a feasible classification")
    if(s.arrivalBefore("food.arrival",ready.tree,FOOD_ARRIVALS,FOOD_DEADLINE_MINUTES))
        s.passed("the typed delivery arrival is before 18:30 on the task date")
    val row=s.quantityRow("food.quantity",ready.tree)
    val label=ViewTree.ownMarkers(row.first).maxByOrNull{it.length}?:s.failed("food.quantity","the pizza quantity control has no identifying label")
    val beforeQuantity=s.quantities(ready.tree).sumOf{it.second}
    val increased=s.edit("food.quantity.increase",listOf(label),formatNumber(row.third+1))
    val afterQuantity=s.quantities(increased.tree).sumOf{it.second}
    if(afterQuantity==beforeQuantity)s.failed("food.quantity.recompute","an increased quantity did not change the typed allocation (still $afterQuantity)")
    for(entry in s.quantities(increased.tree)) if(entry.second<0||entry.second!=kotlin.math.floor(entry.second))s.failed("food.quantity.recompute","a recomputed pizza quantity is not a nonnegative integer: ${entry.second}")
    val increasedTotal=s.totalMinor("food.quantity.total",increased.tree,PIZZA_TOTALS)
    if(increasedTotal<=total)s.failed("food.quantity.recompute","an increased quantity did not increase the typed total ($total -> $increasedTotal EUR cents)")
    s.passed("a quantity edit recomputes the typed allocation and the delivered total")
    s.edit("food.quantity.restore",listOf(label),formatNumber(row.third))
    // An invalid draft withdraws the dependent total and the feasibility classification; a valid draft
    // recovers both without another provider read. Negative and fractional quantities are well-formed
    // numbers the control must refuse as invalid drafts; one it accepts as a valid quantity cannot
    // prove a withdrawal, so the candidates are tried in order.
    val readCalls=s.calls().size
    val invalid=s.invalidDraft("food.quantity.invalid",listOf(label),listOf("-2","1.5")){tree->runCatching{s.assertWithdrawn("probe",tree,PIZZA_TOTALS)}.isSuccess}
    if(invalid!=null) {
        val withdrawn=invalid.second
        s.assertWithdrawn("food.quantity.invalid",withdrawn.tree,PIZZA_TOTALS)
        val states=s.feasibilityStates(withdrawn.tree).filter{(node,_)->!s.ownStale(node)}
        if(states.isNotEmpty())s.failed("food.quantity.invalid","an invalid quantity draft still publishes the allocation classification ${states.map{it.second}}")
        s.expectCalls("food.quantity.invalidCalls",readCalls)
        val recoveredQuantity=s.edit("food.quantity.recover",listOf(label),formatNumber(row.third))
        s.requireFeasible("food.quantity.recover",recoveredQuantity.tree,true)
        s.totalMinor("food.quantity.recover",recoveredQuantity.tree,PIZZA_TOTALS)
        s.expectCalls("food.quantity.recoverCalls",readCalls)
        s.passed("the invalid quantity draft '${invalid.first}' withdrew the delivered total and the feasible classification, and a valid draft recovered both with no provider read")
    }
    val budget=s.editable("food.budget.field",listOf("budget"))
    val scale=s.budgetScale("food.budget.field",budget.first,80.0)
    val budgetValue=ViewTree.number(budget.first,"value")?:s.failed("food.budget.field","the budget control carries no typed value")
    val reduced=(total-1000.0)/scale
    if(reduced<0)s.failed("food.budget","the delivered total ${total/100} EUR cannot be pushed below a nonnegative budget")
    s.edit("food.budget.reduce",listOf("budget"),formatNumber(reduced))
    val infeasible=s.await("food.budget.infeasible",20000){snapshot->s.feasibility(snapshot.tree)=="infeasible"}
    s.requireFeasible("food.budget.infeasible",infeasible.tree,false)
    s.budgetExplanation("food.budget.explanation",infeasible.tree)
    s.passed("lowering the editable budget withdraws feasibility and explains why")
    s.edit("food.budget.restore",listOf("budget"),formatNumber(budgetValue))
    s.await("food.budget.recover",20000){snapshot->s.feasibility(snapshot.tree)=="feasible"}
    s.passed("restoring the budget recovers the feasible classification")
    s.revokeReads()
    s.dispatchAllLoads("food.unavailable.load")
    val denied=s.await("food.unavailable",30000){snapshot->s.explanationOrNull(snapshot.tree)!=null}
    val explanation=s.explanation("food.unavailable",denied.tree)
    if(s.feasibility(denied.tree)=="feasible")s.failed("food.unavailable","missing provider data still claims a feasible allocation")
    s.passed("missing or unavailable provider data is explained, never fabricated: ${explanation.take(160)}")
    s.grantReads()
    val before=s.calls().size
    s.dispatchAllLoads("food.recover.load")
    s.waitForIncrease("food.recover.load",before)
    val recovered=s.await("food.recover",60000){snapshot->runCatching{s.totalMinor("probe",snapshot.tree,PIZZA_TOTALS)}.isSuccess}
    s.assertAllocation("food.recover",recovered.tree)
    val expected=s.totalMinor("food.total.recovered",recovered.tree,PIZZA_TOTALS)
    s.passed("a granted reload restores the typed allocation and delivered total")
    s.assertReadOnly("food.readOnly")
    val drafts=ViewTree.numbersSnapshot(recovered.tree,ViewTree.EDITABLE)
    val restored=s.reopen("food.reopen")
    val reopenedDrafts=ViewTree.numbersSnapshot(restored.tree,ViewTree.EDITABLE)
    if(reopenedDrafts!=drafts)s.failed("food.reopen","cold reopen changed the typed authoritative drafts: ${ViewTree.difference(drafts,reopenedDrafts)}")
    val reopenedTotal=runCatching{s.totalMinor("food.reopen.total",restored.tree,PIZZA_TOTALS)}.getOrNull()
    if(reopenedTotal!=null) {
        if(reopenedTotal!=expected)s.failed("food.reopen.total","cold reopen published $reopenedTotal EUR cents, expected $expected")
        s.passed("cold reopen restored the typed quantities and the delivered total with no provider call")
    } else {
        val stale=ViewTree.stale(restored.tree)?:s.failed("food.reopen.stale","cold reopen neither restored the provider-derived total nor published an explicit stale state")
        if(ViewTree.refreshActions(restored.tree).isEmpty())s.failed("food.reopen.stale","the stale provider data offers no explicit refresh action")
        val callsBefore=s.calls().size
        s.dispatchAllLoads("food.reopen.refresh")
        s.waitForIncrease("food.reopen.refresh",callsBefore)
        val refreshed=s.await("food.reopen.refresh",60000){snapshot->runCatching{s.totalMinor("probe",snapshot.tree,PIZZA_TOTALS)}.getOrNull()==expected}
        s.passed("cold reopen restored the draft quantities, published a stale state for the restored session (${stale.take(80)}) and an explicit refresh recomputed $expected EUR cents")
    }
    s.pendingStale("food.pendingStale")
}

private fun equipmentJourney(s:Scenario,initial:UiSnapshot) {
    s.grantReads();s.passed("authorized the disclosed reads ${s.modules.joinToString(", ")}")
    s.expectCalls("equipment.initial",0)
    val inputs=s.snapshot().tree
    s.preservedString("equipment.input.place",inputs,listOf("The Foundry","stage centre"),"the disclosed collection place")
    if(s.preservedWindow("equipment.input.period",inputs,listOf("window","from","until","start","end"),listOf(975.0,1350.0),listOf("16:15","22:30")))
        s.passed("the disclosed 16:15-22:30 hire period is preserved, both ends as disclosed spelling or as the typed times 975 and 1350 minutes of the task day")
    s.preservedNumber("equipment.input.budget",inputs,listOf("budget"),listOf(20.0,2000.0),"the editable EUR 20 hire budget")
    s.passed("collection place and the editable EUR 20 hire budget are preserved as typed values")
    s.dispatchAllLoads("equipment.load")
    s.waitForIncrease("equipment.load",0)
    val ready=s.await("equipment.quotes",60000){snapshot->runCatching{s.packageAndSeparate("probe",snapshot.tree)}.isSuccess}
    val totals=s.packageAndSeparate("equipment.quotes",ready.tree)
    s.passed("package ${totals.first/100} EUR against separate ${totals.second/100} EUR as typed whole-basket totals, refundable deposit ${totals.third/100} EUR shown separately")
    if(totals.first==totals.second)s.failed("equipment.accounting","package and separate hire publish the same typed total ${totals.first/100} EUR, so the comparison observes no package difference")
    val required=listOf(
        s.preservedItem("equipment.required.boom",ready.tree,"boom",listOf("adjustable"),"the required adjustable boom stand"),
        s.preservedItem("equipment.required.clip",ready.tree,"clip",listOf("adapter"),"the required clip adapter"),
        s.preservedItem("equipment.required.xlr",ready.tree,"xlr",listOf("balanced","female","male"),"the required balanced XLR female-to-male cable"))
    val cable=s.cableLength("equipment.required.length",ready.tree,required.last())
    if(required.all{it!=null}&&cable!=null)
        s.passed("the required adjustable boom stand, the clip adapter and one balanced XLR female-to-male cable of $cable are named as published whole-word string evidence")
    // Package contents and shared stock are a required constraint. A typed availability or stock value
    // whose own label names the availability is what proves shared stock; a heading above the baskets
    // and the whole-basket totals alone do not. A view that publishes none leaves the constraint
    // unproven and the journey blocked.
    val availability=listOf("available","availability","stock")
    val stock=ViewTree.numbers(ready.tree,availability,ViewTree.NUMERIC)
        .filter{(node,_)->ViewTree.ownMarkers(node).any{marker->availability.any{word->JourneyTimes.names(marker,word)}}}
    if(stock.isEmpty())s.blocked("equipment.stock","the view publishes no typed shared-stock or availability value on its own availability/stock label, so shared stock is unproven")
    else {
        for((node,value) in stock) if(value<0||value!=kotlin.math.floor(value))s.failed("equipment.stock","a published shared-stock or availability value is not a nonnegative integer: $value at ${ViewTree.ownMarkers(node)}")
        s.passed("shared stock is published as typed availability ${stock.map{it.second}.distinct()} on ${stock.map{ViewTree.ownMarkers(it.first)}}")
    }
    val budget=s.editable("equipment.budget",listOf("budget"),ready.tree)
    val scale=s.budgetScale("equipment.budget",budget.first,20.0)
    val budgetValue=ViewTree.number(budget.first,"value")?:s.failed("equipment.budget","the budget control carries no typed value")
    val reduced=(totals.first-500.0)/scale
    if(reduced<0)s.failed("equipment.budget","the package total cannot be pushed below a nonnegative budget")
    s.edit("equipment.budget.reduce",listOf("budget"),formatNumber(reduced))
    s.await("equipment.budget.applied",20000){snapshot->s.feasibility(snapshot.tree)=="infeasible"}
    s.passed("the edited hire budget is applied: a budget below the package total withdraws feasibility")
    s.edit("equipment.budget.restore",listOf("budget"),formatNumber(budgetValue))
    s.await("equipment.budget.recover",20000){snapshot->s.feasibility(snapshot.tree)=="feasible"}
    val period=s.editablePreferred("equipment.period",listOf(listOf("until"),listOf("from"),listOf("end"),listOf("date"),listOf("time")))
    val marker=ViewTree.ownMarkers(period.first).maxByOrNull{it.length}?:s.failed("equipment.period","the hire period control has no identifying label")
    val original=ViewTree.number(period.first,"value")?:s.failed("equipment.period","the hire period control carries no typed value")
    val callsBefore=s.calls().size
    val changed=s.edit("equipment.period.change",listOf(marker),formatNumber(original+30))
    s.assertWithdrawn("equipment.period.invalidate",changed.tree,EQUIPMENT_PACKAGE+EQUIPMENT_SEPARATE,staleAcceptable=true)
    s.expectCalls("equipment.period.noReplay",callsBefore)
    s.passed("a changed quote argument invalidated the typed totals without replaying the quote")
    val reload=s.actionPreferred("equipment.reload",listOf(listOf("refresh"),listOf("reload"),listOf("quote"),listOf("load")),changed.tree)
    s.dispatchSlot(reload.second)
    s.waitForIncrease("equipment.reload",callsBefore)
    val requoted=s.await("equipment.requote",60000){snapshot->runCatching{s.packageAndSeparate("probe",snapshot.tree)}.isSuccess}
    s.packageAndSeparate("equipment.requoted",requoted.tree)
    s.passed("an explicit reload re-quoted the changed arguments")
    // An invalid draft withdraws the dependent comparison and a valid draft recovers it. A malformed
    // payload never reaches the view, so the candidate is a well-formed number the control has to refuse:
    // an invalid hire budget publishes no current classification at all. A withdrawal is a transition, so
    // the requoted tree must publish an unambiguous feasible result first; otherwise the observation is
    // unproven and blocks.
    val invalidCalls=s.calls().size
    val before=s.feasibility(requoted.tree)
    val invalid=if(before!="feasible") {
        s.blocked("equipment.budget.invalid","the requoted tree publishes no unambiguous feasible classification (${before}), so the invalid-draft withdrawal cannot be observed")
        null
    } else s.invalidDraft("equipment.budget.invalid",listOf("budget"),listOf("-1")){tree->
        s.feasibilityStates(tree).none{(node,_)->!s.ownStale(node)}
    }
    if(invalid!=null) {
        s.expectCalls("equipment.budget.invalidCalls",invalidCalls)
        s.edit("equipment.budget.invalid.restore",listOf("budget"),formatNumber(budgetValue))
        s.await("equipment.budget.invalid.recover",20000){snapshot->s.feasibility(snapshot.tree)=="feasible"}
        s.expectCalls("equipment.budget.invalidRecoverCalls",invalidCalls)
        s.passed("the invalid hire budget draft '${invalid.first}' withdrew the computed feasibility and a valid draft recovered it without replaying the quotes")
    }
    s.assertReadOnly("equipment.readOnly")
    val current=s.snapshot()
    val drafts=ViewTree.numbersSnapshot(current.tree,ViewTree.EDITABLE)
    val expected=s.packageAndSeparate("equipment.total.recovered",requoted.tree)
    val restored=s.reopen("equipment.reopen")
    val reopenedDrafts=ViewTree.numbersSnapshot(restored.tree,ViewTree.EDITABLE)
    if(reopenedDrafts!=drafts)s.failed("equipment.reopen","cold reopen changed the typed drafts: ${ViewTree.difference(drafts,reopenedDrafts)}")
    val reopenedTotals=runCatching{s.packageAndSeparate("equipment.reopen.totals",restored.tree)}.getOrNull()
    if(reopenedTotals!=null) {
        if(reopenedTotals!=expected)s.failed("equipment.reopen.totals","cold reopen published $reopenedTotals, expected $expected")
        s.passed("cold reopen restored the typed package/separate totals and deposit with no provider call")
    } else {
        val stale=ViewTree.stale(restored.tree)?:s.failed("equipment.reopen.stale","cold reopen neither restored the provider-derived totals nor published an explicit stale state")
        if(ViewTree.refreshActions(restored.tree).isEmpty())s.failed("equipment.reopen.stale","the stale quotes offer no explicit refresh action")
        val callsBeforeReopen=s.calls().size
        s.dispatchAllLoads("equipment.reopen.refresh")
        s.waitForIncrease("equipment.reopen.refresh",callsBeforeReopen)
        val refreshed=s.await("equipment.reopen.refresh",60000){snapshot->runCatching{s.packageAndSeparate("probe",snapshot.tree)}.getOrNull()==expected}
        s.passed("cold reopen restored the drafts, published a stale state for the restored session (${stale.take(80)}) and an explicit refresh restored the same typed totals")
    }
    s.pendingStale("equipment.pendingStale")
}
