package dev.deal.embedding

import org.json.JSONArray
import org.json.JSONObject

/** Canonical compiler JSON v2 only. Classification is evidence, not edit authority. */
internal fun classifyCompilerOutcome(status: Int, raw: String?, appPath: String, projectPath: String) {
    val report = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
    val diagnostics = report?.optJSONArray("diagnostics")
    val errors = diagnostics?.let { list -> (0 until list.length()).mapNotNull { list.optJSONObject(it) }.filter { it.optString("severity") == "error" } } ?: emptyList()
    val operations = report?.optJSONArray("operations")
    val compilation = report?.optJSONObject("compilation")
    val valid = report?.opt("version") == 2 && diagnostics != null && operations != null && compilation != null &&
        compilation.opt("published") is Boolean && (0 until diagnostics.length()).all { index ->
            diagnostics.optJSONObject(index)?.optString("severity") in listOf("error", "warning", "info")
        }
    if (status == 0 && valid && errors.isEmpty() && operations.length() == 0 && compilation.optBoolean("published")) return
    val repairable = status == 1 && valid && operations.length() == 0 && !compilation.optBoolean("published") && errors.isNotEmpty() && errors.all { diagnostic ->
        val classification = diagnostic.optJSONObject("classification")
        val range = diagnostic.optJSONObject("range")
        range?.optString("file") == appPath && range.optString("origin") == "SOURCE" &&
            classification?.optString("responsibility") == "SOURCE_CONTRACT"
    }
    if (!repairable) throw CompilerOperationFailed(status, errors.map { it.optString("code") })
    // Keep valid bounded JSON, never a mid-document substring. Preserve producer
    // facts, notes, classification, exact ranges and revision/occurrence evidence.
    val selected = JSONArray()
    var units = 0
    for (diagnostic in errors) {
        val encoded = diagnostic.toString().replace(projectPath + "/", "")
        if (selected.length() > 0 && units + encoded.length > 48000) break
        selected.put(JSONObject(encoded))
        units += encoded.length
    }
    throw CandidateRejected(JSONObject().put("version", 2).put("diagnostics", selected)
        .put("operations", JSONArray()).put("compilation", compilation)
        .put("omittedDiagnostics", errors.size - selected.length()).toString())
}

internal class CompilerOperationFailed(val status: Int, val codes: List<String>) : IllegalStateException("Compiler operation failed (status=$status, codes=${codes.joinToString(",")})")
