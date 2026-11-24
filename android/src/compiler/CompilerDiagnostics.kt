package dev.deal.embedding

import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Path

/** Compiler diagnostic protocol v3 only. Preserve shared indexes and every producer occurrence. */
internal fun classifyCompilerOutcome(status: Int, raw: String?, appPath: String, projectPath: String) {
    val report = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
    val diagnostics = report?.optJSONArray("diagnostics")
    val files = report?.optJSONArray("files")
    val context = report?.optJSONArray("context")
    val states = setOf("accepted", "rejected", "failed")
    val responsibilities = setOf("SOURCE_CONTRACT", "CONFIGURATION_CONTRACT", "HOST_CONTRACT", "COMPILER_CONTRACT", "RUNTIME_CONTRACT", "PROTOCOL_CONTRACT")
    fun nonblank(value: JSONObject, key: String) = value.opt(key) is String && value.getString(key).isNotBlank()
    fun integer(value: Any?, minimum: Int = 0): Boolean = value is Number && value.toDouble().let { it.isFinite() && it == kotlin.math.floor(it) && it >= minimum }
    fun reference(value: Any?, size: Int) = integer(value) && (value as Number).toLong() < size
    fun coordinates(value: Any?, size: Int, minimum: Int): Boolean = value is JSONArray && value.length() == size &&
        (0 until size).all { integer(value.opt(it), minimum) }
    fun location(value: Any?): Boolean = value is JSONObject &&
        value.keys().asSequence().all { it in setOf("file", "at", "offset", "origin") } &&
        reference(value.opt("file"), files?.length() ?: 0) && coordinates(value.opt("at"), 4, 1) &&
        value.getJSONArray("at").let { it.getLong(0) < it.getLong(2) || it.getLong(0) == it.getLong(2) && it.getLong(1) <= it.getLong(3) } &&
        (!value.has("origin") || value.opt("origin") == "synthetic")
    fun references(value: Any?): Boolean = when (value) {
        is JSONArray -> (0 until value.length()).all { references(value.opt(it)) }
        is JSONObject -> value.keys().asSequence().all { key ->
            when (key) {
                "range", "declarationRange", "parameterRange" -> location(value.opt(key)) && references(value.opt(key))
                "file" -> reference(value.opt(key), files?.length() ?: 0)
                "at" -> coordinates(value.opt(key), 4, 1)
                "offset" -> coordinates(value.opt(key), 2, 0) && value.getJSONArray(key).let { it.getLong(0) <= it.getLong(1) }
                else -> references(value.opt(key))
            }
        }
        else -> true
    }
    val valid = runCatching { report != null && report.opt("version") == 3 && report.optString("status") in states && files != null && diagnostics != null &&
        report.keys().asSequence().all { it in setOf("version", "status", "files", "diagnostics", "context", "failures") } &&
        (0 until files.length()).all { i -> files.optJSONObject(i)?.let { file ->
            nonblank(file, "file") && nonblank(file, "revision") && file.optString("snapshot") in setOf("current", "candidate")
        } == true } &&
        (!report.has("context") || context != null) &&
        (context == null || (context.length() > 0 && (0 until context.length()).all { i -> context.optJSONObject(i)?.let { excerpt ->
            reference(excerpt.opt("file"), files.length()) && integer(excerpt.opt("start")) && excerpt.opt("text") is String
        } == true })) &&
        (0 until diagnostics.length()).all { i -> diagnostics.optJSONObject(i)?.let { diagnostic ->
            nonblank(diagnostic, "code") && nonblank(diagnostic, "message") && diagnostic.optString("severity") in setOf("error", "warning") &&
                diagnostic.optString("responsibility") in responsibilities && references(diagnostic) &&
                (!diagnostic.has("context") || reference(diagnostic.opt("context"), context?.length() ?: 0)) &&
                (!diagnostic.has("cause") || reference(diagnostic.opt("cause"), diagnostics.length()))
        } == true } &&
        (report.optString("status") == "failed").let { failed ->
            if (!failed) !report.has("failures") else report.optJSONArray("failures")?.let { failures ->
                failures.length() > 0 && (0 until failures.length()).all { i -> failures.optJSONObject(i)?.let { failure ->
                    nonblank(failure, "reason") && nonblank(failure, "message") && failure.optString("responsibility") in responsibilities
                } == true }
            } == true
        }
    }.getOrDefault(false)
    val errors = diagnostics?.let { list -> (0 until list.length()).mapNotNull { list.optJSONObject(it) }.filter { it.optString("severity") == "error" } } ?: emptyList()
    if (!valid) throw CompilerOperationFailed(status, errors.map { it.optString("code") })
    checkNotNull(report)
    checkNotNull(files)
    if (status == 0 && report.getString("status") == "accepted" && errors.isEmpty()) return
    val root = Path.of(projectPath).toAbsolutePath().normalize()
    val app = Path.of(appPath).toAbsolutePath().normalize()
    val repairable = status == 1 && report.getString("status") == "rejected" && errors.isNotEmpty() && errors.all { diagnostic ->
        val range = diagnostic.optJSONObject("range")
        val file = range?.let { files.getJSONObject(it.getInt("file")) }
        val source = file?.getString("file")?.let { runCatching { root.resolve(it).normalize() }.getOrNull() }
        range != null && source == app && !range.has("origin") && diagnostic.getString("responsibility") == "SOURCE_CONTRACT"
    }
    if (!repairable) throw CompilerOperationFailed(status, errors.map { it.optString("code") })
    // Never slice/filter diagnostics: cause/context/file indexes refer to the original arrays.
    // Only path fields are projected; replacing arbitrary strings corrupts source excerpts and edits.
    for (index in 0 until files.length()) {
        val file = files.getJSONObject(index)
        val path = Path.of(file.getString("file"))
        if (path.isAbsolute && path.normalize().startsWith(root)) file.put("file", root.relativize(path.normalize()).toString().replace('\\', '/'))
    }
    throw CandidateRejected(report.toString())
}

internal class CompilerOperationFailed(val status: Int, val codes: List<String>) : IllegalStateException("Compiler operation failed (status=$status, codes=${codes.joinToString(",")})")
