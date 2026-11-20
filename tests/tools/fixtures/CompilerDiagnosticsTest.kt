package dev.deal.embedding

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal class CandidateRejected(val diagnostics: String): IllegalArgumentException()

fun main(args: Array<String>) {
    val report = JSONObject(File(args[0]).readText())
    val app = args[1]
    val project = File(app).parentFile.parent
    check(report.getInt("version") == 2)
    val original = report.getJSONArray("diagnostics").getJSONObject(0)
    check(original.getJSONObject("classification").getString("responsibility") == "SOURCE_CONTRACT")
    check(original.getJSONObject("facts").length() > 0)
    check(original.getJSONObject("range").getInt("endScalarOffset") > 0)
    check(original.getJSONObject("occurrence").getString("sourceRevision").isNotEmpty())
    try { classifyCompilerOutcome(1,report.toString(),app,project); error("source rejection required") }
    catch (error: CandidateRejected) {
        val transported = JSONObject(error.diagnostics)
        val diagnostic = transported.getJSONArray("diagnostics").getJSONObject(0)
        check(diagnostic.getJSONObject("facts").toString() == original.getJSONObject("facts").toString())
        check(diagnostic.getJSONObject("occurrence").toString() == original.getJSONObject("occurrence").toString())
        check(!error.diagnostics.contains(project))
        check(transported.getJSONObject("compilation").toString() == report.getJSONObject("compilation").toString())
    }
    fun operational(value: JSONObject?, status: Int = 1) {
        try { classifyCompilerOutcome(status,value?.toString(),app,project); error("must not authorize repair") }
        catch (_: CompilerOperationFailed) {}
    }
    operational(null)
    operational(JSONObject(report.toString()).also { it.getJSONObject("compilation").remove("published") })
    operational(JSONObject(report.toString()).put("diagnostics",JSONArray().put("not a diagnostic")),0)
    operational(JSONObject(report.toString()).put("version",1)) // old wire format is rejected, never supported
    operational(JSONObject(report.toString()).put("operations",JSONArray().put(JSONObject().put("reason","io"))))
    operational(JSONObject(report.toString()).also { it.getJSONArray("diagnostics").getJSONObject(0).getJSONObject("classification").put("responsibility","COMPILER_CONTRACT") })
    operational(JSONObject(report.toString()).also { it.getJSONArray("diagnostics").getJSONObject(0).getJSONObject("range").put("file","framework.deal") })
    operational(JSONObject(report.toString()).also { it.getJSONArray("diagnostics").getJSONObject(0).getJSONObject("range").put("origin","SYNTHETIC") })
    operational(JSONObject(report.toString()).also { it.getJSONObject("compilation").put("published",true) })
    val success = JSONObject(File(args[2]).readText())
    check(success.getJSONObject("compilation").getBoolean("published"))
    check(success.getJSONObject("compilation").getJSONArray("phases").length() > 0)
    classifyCompilerOutcome(0,success.toString(),app,project)
    val configuration = JSONObject(File(args[3]).readText())
    check(configuration.getJSONArray("diagnostics").getJSONObject(0).getJSONObject("classification").getString("responsibility") == "CONFIGURATION_CONTRACT")
    operational(configuration)
    operational(JSONObject(success.toString()).put("version",1),0)
    val many = JSONObject(report.toString()).put("diagnostics",JSONArray().also { list -> repeat(100) { list.put(JSONObject(original.toString())) } })
    try { classifyCompilerOutcome(1,many.toString(),app,project); error("source rejection required") }
    catch (error: CandidateRejected) {
        val bounded = JSONObject(error.diagnostics)
        val retained = bounded.getJSONArray("diagnostics").length()
        check(retained in 1..99)
        check(bounded.getInt("omittedDiagnostics") == 100 - retained)
    }
    println("compiler diagnostics: real JSON v2 facts/ranges/revisions survive; source-only repair; operations/foreign/old/missing reports reject")
}
