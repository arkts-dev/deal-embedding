package dev.deal.embedding

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal class CandidateRejected(val diagnostics: String): IllegalArgumentException()

fun main(args: Array<String>) {
    val report = JSONObject(File(args[0]).readText())
    val app = args[1]
    val project = File(app).parentFile.parent
    check(report.getInt("version") == 3 && report.getString("status") == "rejected")
    val original = report.getJSONArray("diagnostics").getJSONObject(0)
    check(original.getString("responsibility") == "SOURCE_CONTRACT")
    check(original.getJSONObject("evidence").length() > 0)
    check(original.getJSONObject("range").getJSONArray("offset").getInt(1) > 0)
    check(report.getJSONArray("files").getJSONObject(original.getJSONObject("range").getInt("file")).getString("revision").isNotEmpty())
    fun rejected(value: JSONObject): JSONObject {
        try { classifyCompilerOutcome(1,value.toString(),app,project); error("source rejection required") }
        catch (error: CandidateRejected) { return JSONObject(error.diagnostics) }
    }
    val transported = rejected(report)
    check(transported.toString() == report.toString()) // canonical producer is already root-relative
    fun operational(value: JSONObject?, status: Int = 1) {
        try { classifyCompilerOutcome(status,value?.toString(),app,project); error("must not authorize repair") }
        catch (_: CompilerOperationFailed) {}
    }
    fun mutated(change: (JSONObject) -> Unit) = JSONObject(report.toString()).also(change)
    operational(null)
    operational(mutated { it.remove("status") })
    operational(mutated { it.put("diagnostics",JSONArray().put("not a diagnostic")) },0)
    for (version in listOf(1,2,4)) operational(mutated { it.put("version",version) })
    operational(mutated { it.put("operations",JSONArray()) })
    operational(mutated { it.put("compilation",JSONObject()) })
    operational(mutated { it.getJSONArray("diagnostics").getJSONObject(0).put("responsibility","COMPILER_CONTRACT") })
    operational(mutated { it.getJSONArray("files").getJSONObject(0).put("file","framework.deal") })
    operational(mutated { it.getJSONArray("diagnostics").getJSONObject(0).getJSONObject("range").put("origin","synthetic") })
    operational(mutated { it.getJSONArray("diagnostics").getJSONObject(0).getJSONObject("range").put("file",99) })
    operational(mutated { it.getJSONArray("diagnostics").getJSONObject(0).put("context",99) })
    operational(mutated { it.getJSONArray("diagnostics").getJSONObject(0).put("cause",99) })
    operational(mutated { it.getJSONArray("diagnostics").getJSONObject(0).getJSONObject("range").put("offset",JSONArray(listOf(10,1))) })
    operational(mutated { it.put("context","invalid") })
    operational(mutated { it.getJSONArray("diagnostics").getJSONObject(0).put("range",JSONObject().put("file",0)) })
    operational(mutated { it.getJSONArray("diagnostics").getJSONObject(0).getJSONObject("range").put("at",JSONArray(listOf(2,1,1,1))) })
    operational(mutated { it.getJSONArray("diagnostics").getJSONObject(0).getJSONObject("range").put("origin","source") })
    operational(mutated { it.put("status","accepted") })
    val success = JSONObject(File(args[2]).readText())
    check(success.getString("status") == "accepted")
    classifyCompilerOutcome(0,success.toString(),app,project)
    val configuration = JSONObject(File(args[3]).readText())
    check(configuration.getJSONArray("diagnostics").getJSONObject(0).getString("responsibility") == "CONFIGURATION_CONTRACT")
    operational(configuration)
    operational(JSONObject(success.toString()).put("version",2),0)
    operational(JSONObject(success.toString()).put("status","failed"),0)
    val many = mutated { value -> value.put("diagnostics",JSONArray().also { list -> repeat(100) { index ->
        list.put(JSONObject(original.toString()).also { diagnostic -> if(index > 0) diagnostic.put("cause",index-1) })
    } }) }
    check(rejected(many).toString() == many.toString()) // never truncate indexed occurrences
    val excerpts = mutated { value ->
        value.put("context",JSONArray().put(JSONObject().put("file",0).put("start",0).put("text",project + "/literal")))
        value.getJSONArray("diagnostics").getJSONObject(0).put("context",0)
        value.getJSONArray("files").getJSONObject(0).put("file",app)
    }
    val projected = rejected(excerpts)
    check(projected.getJSONArray("files").getJSONObject(0).getString("file") == "src/candidate.deal")
    check(projected.getJSONArray("context").getJSONObject(0).getString("text") == project + "/literal")
    println("compiler diagnostics: v3 status/evidence/snapshots/indexes preserved; legacy/malformed/foreign/operational reports reject; source bytes untouched")
}
