package dev.deal.embedding

import dev.deal.embedding.capabilities.NativeCapabilities
import org.json.*

fun nativeCapabilities(contract: CapabilityContract, handlers: Map<String, (JSONArray) -> Any>) = NativeCapabilities(contract, handlers)
fun nativeCapabilities(contract: CapabilityContract, handlers: (InvocationContext) -> Map<String, (JSONArray) -> Any>) =
    NativeCapabilities.contextual(contract, contract.functions.associate { function ->
        function.name to { args: JSONArray, invocation: InvocationContext -> handlers(invocation).getValue(function.name)(args) }
    })

data class GenerationContext(val id: String)
class GenerationRequest internal constructor(internal val id: String)
data class Experience(val id: String, val title: String, val origin: String, val attempts: Int)
data class Session(val id: String)
data class Source(val deal: String, val dealui: String)
data class UiSnapshot(val tree: JSONObject, val version: Int, val fault: String) {
    fun json(): JSONObject = JSONObject().put("tree",tree).put("version",version).put("fault",fault)
    companion object { fun parse(v: JSONObject)=UiSnapshot(v.getJSONObject("tree"),v.getInt("version"),v.optString("fault")) }
}
data class SessionUpdate(val snapshot: UiSnapshot, val cursor: String) {
    fun json() = JSONObject().put("snapshot",snapshot.json()).put("cursor",cursor)
}
data class EventPage(val events: List<JSONObject>, val cursor: String, val incomplete: Boolean)
data class Artifact(val status: String, val text: String)
data class ObservationHealth(val incomplete: Boolean, val active: Boolean, val code: String, val lost: Int)
