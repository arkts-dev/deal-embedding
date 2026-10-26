package dev.deal.embedding

import dev.deal.embedding.capabilities.*
import org.json.*

// Native representations of api/embedding.d.deal. IDs are validated against instance registries.
data class WireField(val name: String, val type: WireType, val optional: Boolean = false)
data class WireType(val kind: String, val fields: List<WireField> = emptyList(), val element: WireType? = null, val maximum: Int = 128, val minimumNumber: Double = -Double.MAX_VALUE, val maximumNumber: Double = Double.MAX_VALUE) {
    fun json(): JSONObject = JSONObject().put("kind",kind).put("fields",JSONArray(fields.map { JSONObject().put("name",it.name).put("type",it.type.json()).put("optional",it.optional) })).put("element",element?.json() ?: JSONObject.NULL).put("maximum",maximum).put("minimumNumber",minimumNumber).put("maximumNumber",maximumNumber)
    fun validateSchema(depth:Int=0, ancestors:MutableSet<WireType> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())) {
        require(depth<=8 && ancestors.add(this)) { "Schema depth/cycle" }
        try {
            require(kind in setOf("number","string","boolean","null","record","array") && maximum in 0..8192 && minimumNumber.isFinite() && maximumNumber.isFinite() && minimumNumber<=maximumNumber)
            require((kind=="array")== (element!=null) && (kind=="record" || fields.isEmpty()))
            require(fields.size<=32 && fields.map{it.name}.distinct().size==fields.size && fields.all{CapabilityType.identifier(it.name)})
            fields.forEach{it.type.validateSchema(depth+1,ancestors)};element?.validateSchema(depth+1,ancestors)
        }finally{ancestors.remove(this)}
    }
    fun validate(value: Any?, depth: Int = 0) {
        require(depth <= 8 && maximum in 0..8192 && minimumNumber.isFinite() && maximumNumber.isFinite() && minimumNumber <= maximumNumber && kind in setOf("number","string","boolean","null","record","array"))
        require((kind == "array") == (element != null) && (kind == "record" || fields.isEmpty()))
        require(fields.size <= 32 && fields.map { it.name }.distinct().size == fields.size && fields.all { CapabilityType.identifier(it.name) })
        when(kind) {
            "number" -> require(value is Number && value.toDouble().isFinite() && value.toDouble() in minimumNumber..maximumNumber)
            "string" -> require(value is String && value.length <= maximum)
            "boolean" -> require(value is Boolean)
            "null" -> require(value == null || value === JSONObject.NULL)
            "array" -> { require(value is JSONArray && value.length() <= maximum); for(i in 0 until value.length()) element!!.validate(value.get(i),depth+1) }
            "record" -> { require(value is JSONObject && value.keys().asSequence().all { key -> fields.any { it.name == key } }); fields.forEach { if(value.has(it.name))it.type.validate(value.get(it.name),depth+1) else require(it.optional) } }
        }
    }
    companion object { fun parse(v: JSONObject,depth:Int=0): WireType { require(depth<=8);return WireType(v.getString("kind"),v.getJSONArray("fields").let { a -> (0 until a.length()).map { val f=a.getJSONObject(it);WireField(f.getString("name"),parse(f.getJSONObject("type"),depth+1),f.getBoolean("optional")) } },v.optJSONObject("element")?.let { parse(it,depth+1) },v.requiredInteger("maximum"),v.getDouble("minimumNumber"),v.getDouble("maximumNumber")) } }
}
data class Parameter(val name:String,val type:WireType)
data class SharedCapabilityFunction(val name:String,val description:String,val parameters:List<Parameter> = emptyList(),val result:WireType) {
    fun json()=JSONObject().put("name",name).put("description",description).put("parameters",JSONArray(parameters.map{JSONObject().put("name",it.name).put("type",it.type.json())})).put("result",result.json())
}
data class SharedCapabilityContract(val module:String,val description:String,val version:Int=1,val functions:List<SharedCapabilityFunction>) {
    fun json()=JSONObject().put("module",module).put("description",description).put("version",version).put("functions",JSONArray(functions.map{it.json()}))
    fun native():CapabilityContract {functions.forEach{f->f.result.validateSchema();f.parameters.forEach{it.type.validateSchema()}};return CapabilityContract.parse(legacyContract(json()).toString())}
}
fun nativeCapabilities(contract:SharedCapabilityContract,handlers:Map<String,(JSONArray)->Any>)=NativeCapabilities(contract.native(),handlers)
data class GenerationContext(val id: String)
data class GenerationRequest(val id: String)
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

internal fun CapabilityContract.protocolJson(): JSONObject = JSONObject().put("module",module).put("description",description).put("version",version).put("functions",JSONArray(functions.map { fn -> JSONObject().put("name",fn.name).put("description",fn.description).put("parameters",JSONArray(fn.parameters.map { JSONObject().put("name",it.name).put("type",it.type.protocolJson()) })).put("result",fn.result.protocolJson()) }))
internal fun CapabilityType.protocolJson(): JSONObject = JSONObject().put("kind",kind).put("fields",JSONArray(fields.map { (name,type)->JSONObject().put("name",name).put("type",type.protocolJson()).put("optional",name in optionalFields) })).put("element",element?.protocolJson() ?: JSONObject.NULL).put("maximum",maximum).put("minimumNumber",minimumNumber).put("maximumNumber",maximumNumber)
internal fun protocolCatalog(v: JSONArray): JSONArray = JSONArray((0 until v.length()).map { val c=v.getJSONObject(it); val fs=c.getJSONArray("functions"); for(i in 0 until fs.length()) { val fn=fs.getJSONObject(i); val ps=fn.getJSONArray("parameters");for(j in 0 until ps.length()){val p=ps.getJSONObject(j);p.put("type",legacyType(p.getJSONObject("type")))};fn.put("result",legacyType(fn.getJSONObject("result"))) };c })
internal fun legacyContract(v:JSONObject):JSONObject=protocolCatalog(JSONArray().put(JSONObject(v.toString()))).getJSONObject(0)
internal fun legacyType(v: JSONObject): JSONObject { val fields=v.getJSONArray("fields");val converted=JSONObject();for(i in 0 until fields.length()){val f=fields.getJSONObject(i);converted.put(f.getString("name"),legacyType(f.getJSONObject("type")))};v.put("fields",converted);v.put("optionalFields",JSONArray((0 until fields.length()).map{fields.getJSONObject(it)}.filter{it.optBoolean("optional")}.map{it.getString("name")}));v.optJSONObject("element")?.let { v.put("element",legacyType(it)) };return v }
