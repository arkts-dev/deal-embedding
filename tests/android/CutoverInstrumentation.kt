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
    override fun onCreate(arguments: Bundle?) { targetsOnly=arguments?.getString("targets")=="true";providersOnly=arguments?.getString("providers")=="true";tabsOnly=arguments?.getString("tabs")=="true";scenarioInput=arguments?.getString("scenarios");replaySource=arguments?.getString("replay")=="true";imagesOnly=arguments?.getString("images")=="true";super.onCreate(arguments); start() }
    override fun onStart() {
        val result=Bundle()
        try {
            if(targetsOnly){result.putString("result",checkTargetTransport(targetContext));finish(-1,result);return}
            if(imagesOnly){result.putString("result",checkImageRendering(this,targetContext));finish(-1,result);return}
            if(tabsOnly){result.putString("result",checkTabsRendering(this));finish(-1,result);return}
            checkBudgetAssertions()
            scenarioInput?.let { runScenarios(it,result);return }
            fun answer(id:String,selected:String,options:List<String>)=JSONObject().put("id",id).put("selected",selected).put("probabilities",JSONArray(options.map { JSONObject().put("id",it).put("p",if(it==selected)1 else 0) }))
            fun questions(input:String)=JSONArray(input.substringAfter("\nISSUED QUESTIONS\n"))
            fun actions(tree:JSONObject):List<Pair<Int,String>> { val out=mutableListOf<Pair<Int,String>>();fun visit(node:JSONObject){val props=node.getJSONArray("props");for(i in 0 until props.length()){val prop=props.getJSONObject(i);if(prop.optString("kind")=="action")out.add(prop.getInt("actionSlot") to node.optString("component"))};val children=node.getJSONArray("children");for(i in 0 until children.length())visit(children.getJSONObject(i))};visit(tree);return out }
            val ordinary="""{"title":"Double calculation","inputs":[{"id":"value","label":"Value","kind":"count","measure":"count","value":3,"min":0,"max":99}],"requirements":[{"id":"retain","text":"Double the edited value","sources":["request"],"needs":["calculate"]}],"needs":[{"id":"calculate","type":"figure","words":"Double the edited value","reads":["value"],"spec":{"label":"Double","format":"count","unit":"","formula":"value * 2","example":{"inputs":{"value":3},"result":6}}}]}"""
            val catalogue="""{"title":"Checklist","requirements":[{"id":"retain","text":"Toggle items and summarize remaining","sources":["request"],"needs":["items","summary"]}],"needs":[{"id":"items","type":"content","words":"Toggle disclosed items","spec":{}},{"id":"summary","type":"content","words":"Completion and remaining counts","reads":["items"],"spec":{}}]}"""
            for(mode in if(providersOnly)emptyList() else listOf("ordinary","catalogue","mixed","partial","syntax-recovery","gap-reader","money","time","lazy-formula")) {
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
                        "planning"->plan.toString()
                        "selection-first","selection-second","alignment"->{val issued=questions(request.input);val answers=JSONArray();for(i in 0 until issued.length()){val q=issued.getJSONObject(i);val id=q.getString("id");val opts=q.getJSONArray("options");val options=(0 until opts.length()).map{opts.getJSONObject(it).getString("id")};val selected=when{ id.startsWith("ownership_")->"authored";id.startsWith("formula_")->"formula";id.startsWith("units_")->"preserved";id=="calculate"->"part_calculate";id=="items"->"cat_items_checklist";id.startsWith("align_")->"summary";else->"unavailable"};answers.put(answer(id,selected,options))};JSONObject().put("answers",answers).toString()}
                        "content"->"""{"sections":[{"need":"items","parameters":{"title":"Disclosed checklist 😀","items":[{"id":"first","label":"First item"},{"id":"second","label":"Second item","done":true}]}},{"need":"summary","parameters":{}}]}"""
                        "coverage"->{val issued=questions(request.input);JSONObject().put("answers",JSONArray((0 until issued.length()).map {answer(issued.getJSONObject(it).getString("id"),"covered",listOf("covered","missing"))})).toString()}
                        "custom-section"->{
                            check(request.maxTokens==6000)
                            if(mode=="syntax-recovery" && stages.count{it=="custom-section"}==1) {
                                return@InferenceProvider """<classes></classes><fields></fields><functions></functions><view>import * as app from "./experience"; import * as ui from "./platform.dealui-pack"; export view Panel(state: app.AppState): View {ui.Text(value: "broken"}</view>"""
                            }
                            if(mode=="syntax-recovery")check(request.diagnostics.contains("Expected ')'") && request.previous.contains("broken")){"View syntax failure did not reach bounded repair: ${request.diagnostics}"}
                            if(mode=="gap-reader") {
                                check(request.input.contains("Issued read-only ports"))
                                return@InferenceProvider """<classes></classes><fields></fields><functions></functions><view>import * as app from "./experience"; import * as ui from "./platform.dealui-pack"; export view Panel(state: app.AppState): View {ui.Column() {When(state.calculate_ready) {ui.Text(value:"Borrowed result") When(state.calculate_display > 10) {ui.Text(value:"Above ten")}}}}</view>"""
                            }
                            if(mode=="partial")"" else """<classes>export class Change {value: string;}</classes><fields>text: string = "Extra 😀";</fields><functions>export function change(state: AppState, action: Change): AppState { return {text:action.value}; }</functions><view>import * as app from "./experience"; import * as ui from "./platform.dealui-pack"; export view Panel(state: app.AppState): View {ui.TextField(label:"Extra",value:state.text,onChange:action app.Change {value:payload})}</view>"""}
                        else->error("Unexpected stage in $mode: ${request.stage}: ${request.diagnostics}")
                    }
                })
                Embedding.open(targetContext,config).use { api ->
                    val context=api.snapshot(WireType("record"),"{}")
                    val experience=api.generate(api.request("Construction fixture","Double the edited value; toggle First item and Second item; retain extra text",context))
                    val source=api.source(experience);check(!source.deal.contains("f_whole_"));check(!stages.contains("custom-whole"));check(experience.attempts==1)
                    if(mode=="catalogue"){check(source.deal.contains("f_section_items_"));check(source.dealui.contains("Disclosed checklist 😀"));check(stages.contains("alignment"))}
                    if(mode=="syntax-recovery")check(stages.count{it=="custom-section"}==2)
                    if(mode=="mixed" || mode=="syntax-recovery")check(source.deal.contains("f_gap_Change") && source.deal.contains("f_parts_value"))
                    if(mode=="partial")check(source.dealui.contains("notice_extra"))
                    val session=api.run(experience);val before=api.next(session,"").snapshot;check(before.fault.isEmpty());val slot=actions(before.tree).first().first
                    api.dispatch(session,slot,if(mode=="catalogue")null else "9")
                    val after=api.next(session,"").snapshot;check(after.version>before.version && after.fault.isEmpty())
                    if(mode=="catalogue")check(after.tree.toString().contains("remaining: 0")) else {
                        check(after.tree.toString().contains(if(mode=="time")"Double: 00:18" else "Double: 18"))
                        if(mode=="gap-reader")check(after.tree.toString().contains("Borrowed result") && after.tree.toString().contains("Above ten"))
                        api.dispatch(session,slot,"invalid");val invalid=api.next(session,"").snapshot;check(invalid.fault.isEmpty());check(invalid.tree.toString().contains("invalid"));check(!invalid.tree.toString().contains("Double: 18"));if(mode=="gap-reader")check(!invalid.tree.toString().contains("Borrowed result"))
                        if(mode=="money")for(value in listOf("1.5","9007199254740992","4503599627370496")) {
                            api.dispatch(session,slot,value);val invalidMoney=api.next(session,"").snapshot
                            check(invalidMoney.fault.isEmpty() && !invalidMoney.tree.toString().contains("Double:")){"Invalid money/overflow published: $value"}
                        }
                        api.dispatch(session,slot,"4");val restored=api.next(session,"").snapshot;check(restored.fault.isEmpty() && restored.tree.toString().contains(if(mode=="time")"Double: 00:08" else "Double: 8"))
                    }
                    api.close(session);api.forget(experience)
                }
            }
            // Real compiled custom effect route; only the external provider is mocked.
            for(providerMode in listOf("custom","scalar","scalar-argument")) {
            var providerCalls=0;var failProvider=false
            val provider= nativeCapabilities(CapabilityContract("host/probe","Explicit fixture read",listOf(CapabilityFunction("read","Return fixture value",if(providerMode=="scalar-argument")listOf(Parameter("value",WireType("number",minimumNumber=0.0,maximumNumber=100.0)))else emptyList(),WireType("number")))) , mapOf("read" to { args:JSONArray->providerCalls++;check(!failProvider){"Fixture read failure"};if(providerMode=="scalar-argument")args.getDouble(0)*2 else 42 }))
            val providerStages=mutableListOf<String>()
            val providerConfig=EmbeddingConfig("v08-provider-fixture",GenerationGatewayConfig("http://127.0.0.1:1/generate","fixture"),inference=InferenceProvider { request,_->
                providerStages.add(request.stage)
                when(request.stage) {
                    "planning"->if(providerMode=="scalar-argument")"""{"title":"Scalar argument","inputs":[{"id":"value","label":"Value","kind":"count","measure":"count","value":21,"min":0,"max":100}],"needs":[{"id":"read","type":"live","words":"Read actual result for value","reads":["value"],"spec":{"label":"Scalar","measure":"number","unit":"","query":"host/probe.read"}},{"id":"double","type":"figure","words":"Double result","reads":["read"],"spec":{"label":"Doubled","format":"plain","unit":"","formula":"read*2","example":{"inputs":{"read":42},"result":84}}}]}""" else if(providerMode=="custom")"""{"title":"Explicit provider","needs":[{"id":"read","type":"other","words":"Explicitly load the provider value","spec":{}}]}""" else """{"title":"Scalar provider","needs":[{"id":"read","type":"live","words":"Explicitly read the scalar value","spec":{"label":"Scalar","measure":"number","unit":"","query":"host/probe.read"}},{"id":"double","type":"figure","words":"Double the actual result","reads":["read"],"spec":{"label":"Doubled","format":"plain","unit":"","formula":"read*2","example":{"inputs":{"read":42},"result":84}}}]}"""
                    "selection-first","selection-second"->{val issued=questions(request.input);val answers=JSONArray();for(i in 0 until issued.length()){val q=issued.getJSONObject(i);val opts=q.getJSONArray("options");val options=(0 until opts.length()).map{opts.getJSONObject(it).getString("id")};answers.put(answer(q.getString("id"),when{providerMode=="custom" && "unavailable" in options->"unavailable";q.getString("id").startsWith("real_")->"read";q.getString("id").startsWith("formula_")->"formula";q.getString("id").startsWith("units_")->"preserved";q.getString("id").startsWith("argument_")->"bound";q.getString("id")=="read"->"scalar_0";q.getString("id")=="double"->"part_double";else->options.last()},options))};JSONObject().put("answers",answers).toString()}
                    "custom-whole"->"""<classes>import * as probe from "host/probe"; export class Load {} export class Loaded { value:string; }</classes><fields>value: string = "Unavailable";</fields><functions>export function start(state:AppState,action:Load):AppState {return {value:state.value};} export async function load(state:AppState,action:Load):Promise<Loaded> {let value:number=await probe.read();return {value:JSON.stringify(value) as string};} export function loaded(state:AppState,action:Loaded):AppState {return {value:action.value};}</functions><view>import * as app from "./experience";import * as ui from "./platform.dealui-pack";export view Panel(state:app.AppState):View {ui.Column(){ui.Button(text:"Load",onClick:action app.Load {}) ui.Text(value:state.value)}}</view>"""
                    "coverage"->{val issued=questions(request.input);JSONObject().put("answers",JSONArray((0 until issued.length()).map {answer(issued.getJSONObject(it).getString("id"),"covered",listOf("covered","missing"))})).toString()}
                    else->error("Unexpected provider stage ${request.stage}: ${request.diagnostics}")
                }
            })
            Embedding.open(targetContext,providerConfig,listOf(provider)).use { api->
                api.grant("host/probe")
                val experience=api.generate(api.request("Provider fixture","Explicitly load the provider value",api.snapshot(WireType("record"),"{}")))
                check(providerCalls==0){"Isolated checking or preparation read a provider"}
                val session=api.run(experience);val initial=api.next(session,"").snapshot;check(providerCalls==0)
                api.dispatch(session,actions(initial.tree).first{it.second=="ui.Button"}.first,null)
                val deadline=android.os.SystemClock.elapsedRealtime()+5000
                var updated=api.next(session,"").snapshot
                while(!updated.tree.toString().contains(if(providerMode!="custom")"84" else "42") && android.os.SystemClock.elapsedRealtime()<deadline){Thread.sleep(20);updated=api.next(session,"").snapshot}
                check(updated.version>initial.version && updated.fault.isEmpty() && updated.tree.toString().contains(if(providerMode!="custom")"84" else "42")){"Compiled provider result not committed: ${updated.json()}"}
                check(providerCalls==1)
                if(providerMode!="custom")check(updated.tree.toString().contains("Doubled: 84"))
                if(providerMode=="scalar-argument") {
                    api.dispatch(session,actions(updated.tree).first{it.second=="ui.TextField"}.first,"20")
                    val changed=api.next(session,"").snapshot;check(!changed.tree.toString().contains("Doubled:"))
                    check(providerCalls==1){"Input edit automatically replayed provider read"}
                    api.dispatch(session,actions(changed.tree).first{it.second=="ui.Button"}.first,null)
                    val until=android.os.SystemClock.elapsedRealtime()+5000
                    var latest=api.next(session,"").snapshot
                    while(!latest.tree.toString().contains("Doubled: 80") && android.os.SystemClock.elapsedRealtime()<until){Thread.sleep(20);latest=api.next(session,"").snapshot}
                    check(latest.fault.isEmpty() && latest.tree.toString().contains("Doubled: 80") && providerCalls==2)
                    api.revoke("host/probe")
                    api.dispatch(session,actions(latest.tree).first{it.second=="ui.Button"}.first,null)
                    var failed=api.next(session,"").snapshot
                    val deniedUntil=android.os.SystemClock.elapsedRealtime()+5000
                    while(!failed.tree.toString().contains("not granted") && android.os.SystemClock.elapsedRealtime()<deniedUntil){Thread.sleep(20);failed=api.next(session,"").snapshot}
                    check(failed.fault.isEmpty() && !failed.tree.toString().contains("Doubled:") && providerCalls==2)
                    api.grant("host/probe");failProvider=true
                    api.dispatch(session,actions(failed.tree).first{it.second=="ui.Button"}.first,null)
                    val failureUntil=android.os.SystemClock.elapsedRealtime()+5000
                    while(!failed.tree.toString().contains("Fixture read failure") && android.os.SystemClock.elapsedRealtime()<failureUntil){Thread.sleep(20);failed=api.next(session,"").snapshot}
                    check(failed.fault.isEmpty() && failed.tree.toString().contains("Fixture read failure") && !failed.tree.toString().contains("Doubled:") && providerCalls==3)
                    Thread.sleep(100);check(providerCalls==3){"Failed provider read automatically retried"}
                    failProvider=false
                    api.dispatch(session,actions(failed.tree).first{it.second=="ui.Button"}.first,null)
                    val retryUntil=android.os.SystemClock.elapsedRealtime()+5000
                    while(!failed.tree.toString().contains("Doubled: 80") && android.os.SystemClock.elapsedRealtime()<retryUntil){Thread.sleep(20);failed=api.next(session,"").snapshot}
                    check(failed.fault.isEmpty() && failed.tree.toString().contains("Doubled: 80") && providerCalls==4)
                }
                api.close(session);api.forget(experience)
            }
            }
            if(providersOnly){result.putString("result","PASS: compiled custom and scalar provider reads, argument invalidation, permission denial, failure and explicit retry");finish(-1,result);return}
            val graphicsSource="""import * as graphics from "embedding/graphics";
export class AppState { shapes: graphics.Shape[]; x: number; }
export class Frame { delta: number; }
export class Touch { pointer: graphics.Pointer; }
export function initialState(): AppState { let shapes: graphics.Shape[] = [{tag:"circle",x:12,y:12,radius:8,color:"#ff0000"}]; return {shapes:shapes,x:12}; }
// @ui-update
export function frame(state: AppState, action: Frame): AppState { let x: number = state.x+action.delta; let shapes: graphics.Shape[] = [{tag:"circle",x:x,y:12,radius:8,color:"#ff0000"}]; return {shapes:shapes,x:x}; }
// @ui-update
export function touch(state: AppState, action: Touch): AppState { let shapes: graphics.Shape[] = [{tag:"circle",x:action.pointer.x,y:action.pointer.y,radius:8,color:"#ff0000"}]; return {shapes:shapes,x:action.pointer.x}; }"""
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
                check(preferences.edit().putString("workspace.$savedId.state","{\"shapes\":[],\"x\":\"invalid\"}").commit())
                check(runCatching{api.run(experience)}.isFailure)
                check(api.source(experience)==source && api.saved().any{it.id==savedId})
                check(preferences.getString("workspace.$savedId.state",null)=="{\"shapes\":[],\"x\":\"invalid\"}")
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
                    if(name=="budget") {
                        var version=initial.version
                        fun edit(label:String,value:String):JSONObject {
                            val current=api.next(session,"").snapshot
                            fun find(node:JSONObject):Int? {
                                val props=node.getJSONArray("props")
                                val labelled=(0 until props.length()).any { props.getJSONObject(it).optString("name")=="label" && (props.getJSONObject(it).optString("stringValue")==label || props.getJSONObject(it).optString("stringValue").startsWith(label+" (")) }
                                if(labelled)for(i in 0 until props.length()) {val p=props.getJSONObject(i);if(p.optString("kind")=="action")return p.getInt("actionSlot")}
                                val children=node.getJSONArray("children");for(i in 0 until children.length()){val found=find(children.getJSONObject(i));if(found!=null)return found};return null
                            }
                            api.dispatch(session,find(current.tree)?:error("Missing editable $label"),value)
                            val updated=api.next(session,"").snapshot;check(updated.version>version && updated.fault.isEmpty());version=updated.version
                            java.io.File(directory,"$name-edit-${version}.json").writeText(updated.tree.toString());return updated.tree
                        }
                        val checks=JSONArray()
                        BudgetAssertions.result(initial.tree,1750,"Over")
                        for((value,expected,status) in listOf(Triple("17000",-1000L,"Under"),Triple("18000",0L,"Equal"),Triple("19750",1750L,"Over"))) {
                            BudgetAssertions.result(edit("Committed",value),expected,status);checks.put("difference $expected, $status")
                        }
                        BudgetAssertions.result(edit("Approved","20000"),-250,"Under");checks.put("approved editable")
                        edit("Approved","18000")
                        for(value in listOf("invalid","1.5","9007199254740992")) {
                            BudgetAssertions.withdrawn(edit("Committed",value));checks.put(JSONObject().put("invalidDraft",value).put("withdrawn",true))
                        }
                        BudgetAssertions.result(edit("Committed","19750"),1750,"Over");checks.put("valid draft recovery")
                        report.put("budgetChecks",checks)
                    }
                    if(name=="departure") {
                        api.grant("host/trip")
                        fun nodes(tree:JSONObject):List<JSONObject> {val result=mutableListOf(tree);val children=tree.getJSONArray("children");for(i in 0 until children.length())result.addAll(nodes(children.getJSONObject(i)));return result}
                        fun prop(node:JSONObject,name:String):String? {val props=node.getJSONArray("props");for(i in 0 until props.length()){val p=props.getJSONObject(i);if(p.optString("name")==name)return p.optString("stringValue")};return null}
                        fun slot(node:JSONObject):Int {val props=node.getJSONArray("props");for(i in 0 until props.length()){val p=props.getJSONObject(i);if(p.optString("kind")=="action")return p.getInt("actionSlot")};error("Missing action")}
                        fun texts(tree:JSONObject)=nodes(tree).mapNotNull{prop(it,"value")?:prop(it,"text")}
                        fun editContaining(label:String,value:String):UiSnapshot {val current=api.next(session,"").snapshot;val field=nodes(current.tree).single{prop(it,"label")?.contains(label,true)==true};api.dispatch(session,slot(field),value);val updated=api.next(session,"").snapshot;check(updated.version>current.version && updated.fault.isEmpty());return updated}
                        fun load(expectedDeparture:String,expectedInspection:String):UiSnapshot {
                            val current=api.next(session,"").snapshot
                            val button=nodes(current.tree).single{it.getString("component").endsWith(".Button")}
                            api.dispatch(session,slot(button),null)
                            var updated=api.next(session,"").snapshot;val until=android.os.SystemClock.elapsedRealtime()+10000
                            while(texts(updated.tree).none{it.contains(expectedDeparture)} && android.os.SystemClock.elapsedRealtime()<until){Thread.sleep(20);updated=api.next(session,"").snapshot}
                            check(updated.fault.isEmpty() && texts(updated.tree).any{it.contains(expectedDeparture)} && texts(updated.tree).any{it.contains(expectedInspection)}){"Departure calculations missing: ${texts(updated.tree)}"}
                            java.io.File(directory,"departure-loaded-${updated.version}.json").writeText(updated.tree.toString());return updated
                        }
                        val loaded=load("15:16","15:06")
                        // Inputs unrelated to provider arguments recompute without another read.
                        val moved=editContaining("start","970");check(texts(moved.tree).any{it.contains("15:26")} && texts(moved.tree).any{it.contains("15:16")})
                        editContaining("start","960")
                        val changed=editContaining("Distance","20");check(texts(changed.tree).none{it.contains("15:16") || it.contains("15:06")})
                        load("15:00","14:50")
                        val invalid=editContaining("Distance","invalid");check(texts(invalid.tree).none{it.contains("15:00") || it.contains("14:50")})
                        editContaining("Distance","12");load("15:16","15:06")
                        report.put("departureChecks",JSONArray(listOf("authorized actual provider read","12 km: departure 15:16 inspection 15:06","edited start recomputes without provider reload","20 km: departure 15:00 inspection 14:50","invalid distance withdraws results")))
                        val partial=texts(loaded.tree).any{it.contains("unchecked") || it.contains("missing implementing needs") || it.contains("missing original behavior")}
                        report.put("status",if(partial)"semantic-partial" else "semantic-checked")
                    }
                    api.close(session)
                    val reopened=api.run(experience);check(api.next(reopened,"").snapshot.fault.isEmpty());api.close(reopened)
                    api.forget(experience)
                    val events=JSONArray();var cursor=""
                    do {val page=api.events(report.getString("trace"),cursor,200);for(event in page.events)events.put(event);cursor=page.cursor}while(cursor.isNotEmpty())
                    java.io.File(directory,"$name-events.json").writeText(events.toString())
                }
            } catch(error:Throwable) {report.put("status","failed").put("failure",android.util.Log.getStackTraceString(error))}
            report.put("elapsedMs",android.os.SystemClock.elapsedRealtime()-start);reports.put(report)
            java.io.File(directory,"reports.json").writeText(reports.toString())
            sendStatus(0,Bundle().apply{putString("scenario",report.toString())})
        }
        result.putString("scenarios",reports.toString());finish(-1,result)
    }
}
