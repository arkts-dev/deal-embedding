package dev.deal.cutover.tests

import android.content.Context
import dev.deal.embedding.*
import org.json.JSONArray
import org.json.JSONObject

internal fun targetTransportSource() = Source("""
export class AppState { value: number; }
export class Change { value: number; }
export function initialState(): AppState { return {value:3}; }
// @ui-update
export function change(state: AppState, action: Change): AppState { return {value:action.value}; }
""".trimIndent(), """
import * as app from "./experience";
import * as ui from "./platform.dealui-pack";
// @ui-root
export view Panel(state: app.AppState): View { ui.Column() { ui.NumberField(label:"Value",value:state.value,minimum:0,maximum:99,onChange:action app.Change {value:payload}) ui.Text(text:"Target transport ready") } }
""".trimIndent())

/** Public packaged path, no cloud or provider calls; each namespace belongs to this test. */
fun checkTargetTransport(context: Context): String {
    val gateway = GenerationGatewayConfig("http://127.0.0.1:1/generate", "fixture")
    check(runCatching { EmbeddingConfig("invalid-target", gateway, target="print") }.isFailure)
    val source = targetTransportSource()
    for (target in listOf("screen", "chat", "card")) {
        val namespace = "target-transport-$target-${java.util.UUID.randomUUID()}"
        val config = EmbeddingConfig(namespace,gateway,retainState=true,target=target)
        var savedId = ""
        Embedding.open(context,config).use { api ->
            val experience = api.load("Target transport",source)
            check(api.source(experience) == source)
            val surface = ExperienceSurface(api,experience)
            check(surface.target == target)
            check(runCatching { ExperienceSurface(api,experience,if(target=="screen")"card" else "screen") }.isFailure)
            surface.close()
            val session = api.run(experience)
            val initial = api.next(session,"").snapshot
            check(initial.fault.isEmpty() && initial.tree.toString().contains("Target transport ready"))
            fun control(node: JSONObject): JSONObject? {
                if(node.optString("component").endsWith("NumberField"))return node
                val children=node.getJSONArray("children")
                for(i in 0 until children.length())control(children.getJSONObject(i))?.let{return it}
                return null
            }
            val props=checkNotNull(control(initial.tree)).getJSONArray("props")
            val slot=(0 until props.length()).map{props.getJSONObject(it)}.single{it.optString("name")=="onChange"}.getInt("actionSlot")
            api.dispatch(session,slot,"9")
            val changed=api.next(session,"").snapshot
            check(changed.fault.isEmpty() && changed.version > initial.version)
            val values=checkNotNull(control(changed.tree)).getJSONArray("props")
            check((0 until values.length()).map{values.getJSONObject(it)}.single{it.optString("name")=="value"}.getDouble("numberValue")==9.0)
            savedId=experience.id
            api.close(session)
        }
        // Neither reinterpret nor delete saved data when a host opens it with another target.
        Embedding.open(context,config.copy(target=if(target=="screen")"card" else "screen")).use { api ->
            val saved=api.saved().single{it.id==savedId}
            check(runCatching{api.run(saved)}.isFailure)
            check(api.source(saved)==source && api.saved().any{it.id==savedId})
        }
        Embedding.open(context,config).use { api ->
            val saved=api.saved().single{it.id==savedId}
            val session=api.run(saved)
            val restored=api.next(session,"").snapshot
            check(restored.fault.isEmpty() && restored.tree.toString().contains("\"numberValue\":9"))
            api.close(session)
            // Legacy records without target stay inspectable; no silent target migration.
            val prefs=context.getSharedPreferences(namespace,0)
            check(prefs.edit().remove("workspace.$savedId.target").commit())
            Embedding.open(context,config).use { legacy ->
                val retained=legacy.saved().single{it.id==savedId}
                check(runCatching{legacy.run(retained)}.isFailure)
                check(legacy.source(retained)==source)
            }
            check(prefs.edit().putString("workspace.$savedId.target",target).commit())
            api.forget(saved)
        }
        // Generation must carry the configured target into the actual planning prompt.
        var planned=false
        val generatedConfig=config.copy(inference=InferenceProvider { request,_ ->
            when(request.stage) {
                "planning" -> { check(request.input.contains("\nFROZEN TARGET\n$target\n"));planned=true;"""{"title":"Target probe","needs":[{"id":"probe","type":"other","words":"Show target probe","spec":{}}]}""" }
                "selection-first", "selection-second", "coverage" -> {
                    val questions=JSONArray(request.input.substringAfter("\nISSUED QUESTIONS\n"))
                    JSONObject().put("answers",JSONArray((0 until questions.length()).map { index ->
                        val q=questions.getJSONObject(index);val options=q.getJSONArray("options")
                        val ids=(0 until options.length()).map{options.getJSONObject(it).getString("id")}
                        val selected=if("covered" in ids)"covered" else "unavailable"
                        JSONObject().put("id",q.getString("id")).put("selected",selected).put("probabilities",JSONArray(ids.map{JSONObject().put("id",it).put("p",if(it==selected)1 else 0)}))
                    })).toString()
                }
                "custom-whole" -> """<classes>export class Change {value:number;}</classes><fields>value:number=3;</fields><functions>export function change(state:AppState,action:Change):AppState {return {value:action.value};}</functions><view>import * as app from "./experience";import * as ui from "./platform.dealui-pack";export view Panel(state:app.AppState):View {ui.Column(){ui.NumberField(label:"Value",value:state.value,minimum:0,maximum:99,onChange:action app.Change {value:payload}) ui.Text(text:"Target transport ready")}}</view>"""
                else -> error("Unexpected generation stage ${request.stage}: ${request.diagnostics}")
            }
        })
        Embedding.open(context,generatedConfig).use { api ->
            val experience=api.generate(api.request("Target probe","Show target probe",api.snapshot(WireType("record"),"{}")))
            check(planned)
            val session=api.run(experience)
            check(api.next(session,"").snapshot.fault.isEmpty())
            api.close(session);api.forget(experience)
        }
    }
    return "PASS: host screen/chat/card through packaged generate/load/check/mount/edit/save/reopen; mismatches rejected with data retained"
}
