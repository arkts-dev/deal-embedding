package dev.deal.embedding

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Observation only: never decides admission, retry, source transformation or runtime behavior. */
internal class GenerationTelemetry(private val log: EmbeddingLog, private val clock: () -> Long = { SystemClock.elapsedRealtime() }) {
    private val started = clock()
    private var initial: ExperienceSource? = null
    private var current: ExperienceSource? = null
    private var repair: JSONObject? = null
    private var base: ExperienceSource? = null
    private var modelStarted = 0L
    private var modelActive = false
    private var patchStarted = 0L
    private var checkStarted = 0L
    private var checkActive = false
    private var repairStarted = 0L
    private var calls = 0
    private var repairs = 0
    private var checks = 0
    private var providers = 0
    private var initialMs = 0L
    private var repairMs = 0L
    private var patchMs = 0L
    private var checkingMs = 0L
    private var lastDiagnostic = ""
    private var lastCheck = ""
    private var sourceChecked = false
    private var ended = false
    private var requestEnd: Long? = null
    private val seenCodes = linkedSetOf<String>()
    private var sequence = 0
    private var editCount = 0
    private var addedLines = 0L
    private var removedLines = 0L
    private var changedBytes = 0L
    private var editTotalsComplete = true
    private var failureStage = ""
    private val missing = linkedSetOf<String>()
    private val tokens = JSONObject().put("initial", JSONObject()).put("repair", JSONObject())
    private val settings = JSONArray()
    private var mounted = false
    private var mountMs: Long? = null
    private var publicationMs: Long? = null
    private var mountAt: Long? = null
    private var publicationAt: Long? = null

    private fun safe(block: () -> Unit) { try { block() } catch (_: Exception) { missing.add("telemetry_error") } }
    private fun elapsed(start: Long) = (clock() - start).coerceAtLeast(0)
    private fun pair(raw: String): ExperienceSource? = runCatching { val p=JSONObject(raw); if(p.length()!=2) null else ExperienceSource(p.getString("deal"),p.getString("dealui")) }.getOrNull()
    private fun nullable(value: Any?) = value ?: JSONObject.NULL
    private fun diagnostics(raw: String): JSONArray {
        val list = runCatching { JSONArray(raw) }.getOrElse { runCatching { JSONObject(raw).getJSONArray("diagnostics") }.getOrDefault(JSONArray()) }
        return JSONArray((0 until list.length()).mapNotNull { index ->
            val d=list.optJSONObject(index) ?: return@mapNotNull null
            val code=d.optString("code").takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,79}")) } ?: return@mapNotNull null
            val range=d.optJSONObject("range")
            JSONObject().put("code",code).put("stage",d.optString("stage").ifEmpty { if(code.startsWith("UI")) "ui" else "deal" })
                .put("sourceFile",(range?.optString("file") ?: d.optString("file")).substringAfterLast('/').take(80))
        })
    }
    private fun codes(raw: String): Set<String> { val ds=diagnostics(raw);return (0 until ds.length()).map { ds.getJSONObject(it).getString("code") }.toSet() }
    fun modelStart(previous: String, diagnostic: String) = safe {
        finishRepair()
        calls++; modelStarted=clock();modelActive=true;failureStage="model"
        if(previous.isNotEmpty()) {
            repairs++;repairStarted=modelStarted;base=pair(previous);lastDiagnostic=diagnostic
            if(base==null)missing.add("invalid_telemetry_base")
            val ds=diagnostics(diagnostic)
            repair=JSONObject().put("schema",1).put("requestId",log.run).put("attemptId","${log.run}:$calls").put("attemptNumber",calls).put("repairNumber",repairs)
                .put("baseRevision",nullable(base?.let(::revision))).put("initialRevision",nullable(initial?.let(::revision)))
                .put("targetCheckId",lastCheck).put("diagnostics",ds).put("diagnosticCodes",JSONArray(codes(diagnostic).sorted()))
                .put("diagnosticStage",JSONArray((0 until ds.length()).map { ds.getJSONObject(it).getString("stage") }.distinct()))
                .put("sourceFile",JSONArray((0 until ds.length()).map { ds.getJSONObject(it).getString("sourceFile") }.distinct()))
                .put("patchOutcome","not_completed").put("patchRejectionCode",JSONObject.NULL).put("editCount",JSONObject.NULL)
                .put("filesChanged",JSONObject.NULL).put("addedLines",JSONObject.NULL).put("removedLines",JSONObject.NULL).put("changedBytes",JSONObject.NULL)
                .put("unchangedDeal",JSONObject.NULL).put("unchangedUi",JSONObject.NULL)
                .put("modelMs",JSONObject.NULL).put("patchApplyMs",JSONObject.NULL).put("recheckMs",JSONObject.NULL)
                .put("recheckOutcome","not_checked").put("remainingDiagnosticCodes",JSONObject.NULL)
                .put("preservationOutcome","not_tested").put("runtimeTestOutcome","not_tested")
        }
    }
    fun modelEnd(raw: String) = safe {
        val ms=elapsed(modelStarted);if(repair==null)initialMs+=ms else repairMs+=ms
        repair?.put("modelMs",ms);modelActive=false;failureStage="generation"
    }
    fun provider(model: String, metadata: JSONObject) = safe {
        if(ended||!modelActive)return@safe
        providers++
        val operation=if(repair==null)"initial" else "repair"
        val totals=tokens.getJSONObject(operation)
        for(key in listOf("in","cached","out","reasoning")) {
            val n=metadata.opt(key)
            if(n !is Number || n.toLong()<0) { missing.add("$operation.$key");totals.put(key,JSONObject.NULL) }
            else if(!totals.has(key))totals.put(key,n.toLong())
            else if(!totals.isNull(key))totals.put(key,totals.getLong(key)+n.toLong())
        }
        val setting=JSONObject().put("model",model)
        for(key in listOf("reasoning_effort","temperature","max_tokens","prefixSha256"))if(metadata.has(key))setting.put(key,metadata.get(key))
        if(!(0 until settings.length()).any { settings.getJSONObject(it).toString()==setting.toString() })settings.put(setting)
        repair?.put("model",model)?.put("providerMetadata",metadata)
    }
    fun patchStart() = safe { patchStarted=clock();failureStage="patch" }
    fun patchEnd(result: JSONObject) = safe {
        val ms=elapsed(patchStarted);patchMs+=ms
        val record=repair ?: return@safe
        record.put("patchApplyMs",ms)
        if(!result.optBoolean("accepted")) {
            val rejection=result.optString("diagnostics")
            record.put("patchOutcome","rejected").put("patchRejectionCode",rejection.substringBefore(':').take(80))
            // Retain actionable indices/counts, never source or raw failed edits.
            if(rejection.startsWith("PATCH_NO_MATCH:") || rejection.startsWith("PATCH_MULTIPLE_MATCHES:"))record.put("patchRejectionDetail",rejection.take(1024))
            record.put("resultRevision",nullable(base?.let(::revision))).put("immediate",base?.let { changes(it,it) } ?: JSONObject.NULL)
            record.put("cumulative",if(initial!=null&&base!=null)changes(initial!!,base!!) else JSONObject.NULL)
            base?.let { val unchanged=changes(it,it);for(key in listOf("filesChanged","addedLines","removedLines","changedBytes","unchangedDeal","unchangedUi"))record.put(key,unchanged.get(key)) }
            record.put("editCount",if(result.has("editCount"))result.getInt("editCount") else JSONObject.NULL)
            finishRepair();return@safe
        }
        current=pair(result.getString("source"))
        record.put("patchOutcome","applied").put("resultRevision",nullable(current?.let(::revision)))
        record.put("editCount",result.getInt("editCount"))
        val immediate=if(base!=null&&current!=null)changes(base!!,current!!) else JSONObject.NULL
        record.put("immediate",immediate).put("cumulative",if(initial!=null&&current!=null)changes(initial!!,current!!) else JSONObject.NULL)
        if(immediate is JSONObject)for(key in listOf("filesChanged","addedLines","removedLines","changedBytes","unchangedDeal","unchangedUi"))record.put(key,immediate.get(key))
    }
    fun checkStart(source: ExperienceSource) = safe {
        if(initial==null)initial=source
        current=source;checks++;checkStarted=clock();checkActive=true;failureStage="check"
        repair?.put("checkId","${log.run}:check:$checks")
    }
    fun checkEnd(accepted: Boolean, diagnostic: String) = safe {
        val ms=elapsed(checkStarted);checkingMs+=ms;checkActive=false;lastCheck="${log.run}:check:$checks"
        sourceChecked=accepted;failureStage=if(accepted)"" else "check"
        val before=codes(lastDiagnostic);val after=codes(diagnostic)
        repair?.put("recheckMs",ms)?.put("recheckOutcome",if(accepted)"passed" else "rejected")
            ?.put("remainingDiagnosticCodes",JSONArray(after.sorted()))?.put("disappearedDiagnosticCodes",JSONArray((before-after).sorted()))
            ?.put("newDiagnosticCodes",JSONArray((after-seenCodes).sorted()))?.put("persistentDiagnosticCodes",JSONArray((before intersect after).sorted()))
            ?.put("recurredDiagnosticCodes",JSONArray(((after intersect seenCodes)-before).sorted()))
        seenCodes.addAll(after);lastDiagnostic=diagnostic
        finishRepair()
    }
    private fun finishRepair() {
        repair?.let { record ->
            if(!record.has("providerMetadata"))missing.add("repair_usage_unavailable")
            if(!record.has("resultRevision"))record.put("resultRevision",nullable(base?.let(::revision)))
            if(record.optString("patchOutcome")=="applied") {
                editCount+=record.optInt("editCount")
                val change=record.optJSONObject("immediate")
                if(change?.optBoolean("complete")==true){addedLines+=change.getLong("addedLines");removedLines+=change.getLong("removedLines");changedBytes+=change.getLong("changedBytes")}else editTotalsComplete=false
            }
            record.put("totalRepairMs",elapsed(repairStarted)).put("telemetryComplete",missing.isEmpty()).put("missing",JSONArray(missing.toList()))
            log.metric("repair-metrics",record)
        }
        repair=null
    }
    fun end(outcome: String, code: String = "") = safe {
        if(ended)return@safe
        if(modelActive){val ms=elapsed(modelStarted);if(repair==null)initialMs+=ms else repairMs+=ms;repair?.put("modelMs",ms);modelActive=false}
        if(checkActive){val ms=elapsed(checkStarted);checkingMs+=ms;checkActive=false;repair?.put("recheckMs",ms)?.put("recheckOutcome",if(outcome=="cancelled")"cancelled" else "operational_failure")}
        if(code=="REPEATED_RESPONSE")repair?.put("patchOutcome","not_applied_repeated_response")
        if(outcome=="cancelled")repair?.put("interruptedStage",failureStage)
        finishRepair();ended=true;requestEnd=elapsed(started)
        if(providers!=calls)missing.add("provider_usage_calls:$providers/$calls")
        summary(outcome,code)
    }
    fun mounted(duration: Long, success: Boolean) = safe {
        mounted=success;mountMs=duration;mountAt=elapsed(started);summary(if(success)"runtime_mounted" else "runtime_mount_failed",if(success)"" else "MOUNT_FAILED")
    }
    fun runtimeFailure(code: String) = safe { failureStage="runtime";summary("runtime_failed",code) }
    fun published() = safe { if(publicationAt!=null)return@safe;publicationAt=elapsed(started);publicationMs=publicationAt!!-(mountAt ?: publicationAt!!);summary("snapshot_published","") }
    private fun summary(outcome: String, code: String) {
        for(operation in listOf("initial","repair"))for(key in listOf("in","cached","out","reasoning")) {
            val t=tokens.getJSONObject(operation);if(!t.has(key))t.put(key,if((if(operation=="initial")calls-repairs else repairs)==0)0 else JSONObject.NULL)
        }
        val record=JSONObject().put("schema",1).put("requestId",log.run).put("summaryRevision",++sequence).put("outcome",outcome).put("failureStage",if(code.isEmpty())JSONObject.NULL else failureStage.ifEmpty { if(code=="MOUNT_FAILED")"mount" else "generation" }).put("failureCode",code)
            .put("initialCalls",calls-repairs).put("repairCalls",repairs).put("initialModelMs",initialMs).put("repairModelMs",repairMs).put("patchApplyMs",patchMs).put("checkingMs",checkingMs)
            .put("requestEndMs",nullable(requestEnd)).put("timeToCheckedSourceMs",if(sourceChecked)nullable(checkedAt) else JSONObject.NULL)
            .put("mountMs",nullable(mountMs)).put("timeToMountMs",nullable(mountAt)).put("firstPublicationAfterMountMs",nullable(publicationMs)).put("timeToFirstPublicationMs",nullable(publicationAt))
            .put("sourceChecked",sourceChecked).put("runtimeMounted",mounted).put("snapshotPublished",publicationAt!=null)
            .put("totalAdmittedEdits",editCount).put("sumImmediateChanges",JSONObject().put("complete",editTotalsComplete).put("addedLines",if(editTotalsComplete)addedLines else JSONObject.NULL).put("removedLines",if(editTotalsComplete)removedLines else JSONObject.NULL).put("changedBytes",if(editTotalsComplete)changedBytes else JSONObject.NULL))
            .put("remainingDiagnosticCodes",JSONArray(codes(lastDiagnostic).sorted()))
            .put("testedBehavior","not_tested").put("preservationOutcome","not_tested").put("tokens",tokens).put("settings",settings)
            .put("cumulative",if(initial!=null&&current!=null)changes(initial!!,current!!) else JSONObject.NULL)
            .put("missing",JSONArray(missing.toList())).put("telemetryComplete",missing.isEmpty()).put("logDeliveryComplete","unknown_until_observation_health_checked")
        log.metric("request-summary",record)
    }
    private var checkedAt: Long? = null
    fun checkedTime() = safe { checkedAt=elapsed(started) }
    companion object {
        fun revision(source: ExperienceSource): String {
            val input="${source.deal.codePointCount(0,source.deal.length)}:${source.deal}${source.ui.codePointCount(0,source.ui.length)}:${source.ui}"
            return MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
        /** Exact line-LCS edit counts, bounded work; byte metric is UTF-8 bytes of added+removed lines. */
        fun changes(before: ExperienceSource, after: ExperienceSource): JSONObject {
            var added=0;var removed=0;var bytes=0L;var complete=true
            fun diff(a: String,b: String) {
                if(a==b)return
                fun lines(text: String): List<String> { val parts=text.split('\n');return parts.mapIndexed { i,line -> if(i<parts.lastIndex)line+"\n" else line }.filter { it.isNotEmpty() } }
                val x=lines(a);val y=lines(b)
                var prefix=0;while(prefix<minOf(x.size,y.size)&&x[prefix]==y[prefix])prefix++
                var endX=x.size;var endY=y.size;while(endX>prefix&&endY>prefix&&x[endX-1]==y[endY-1]){endX--;endY--}
                val n=endX-prefix;val m=endY-prefix
                if(n.toLong()*m>4_000_000){complete=false;return}
                val dp=Array(n+1){IntArray(m+1)}
                for(i in n-1 downTo 0)for(j in m-1 downTo 0)dp[i][j]=if(x[prefix+i]==y[prefix+j])dp[i+1][j+1]+1 else maxOf(dp[i+1][j],dp[i][j+1])
                var i=0;var j=0
                fun lineBytes(lines: List<String>, k: Int)=lines[k].toByteArray(Charsets.UTF_8).size
                while(i<n||j<m) {
                    if(i<n&&j<m&&x[prefix+i]==y[prefix+j]){i++;j++}
                    else if(j<m&&(i==n||dp[i][j+1]>=dp[i+1][j])){added++;bytes+=lineBytes(y,prefix+j);j++}
                    else{removed++;bytes+=lineBytes(x,prefix+i);i++}
                }
            }
            diff(before.deal,after.deal);diff(before.ui,after.ui)
            return JSONObject().put("algorithm","line-lcs-v1; changedBytes=UTF8 added+removed line bytes").put("complete",complete)
                .put("addedLines",if(complete)added else JSONObject.NULL).put("removedLines",if(complete)removed else JSONObject.NULL).put("changedBytes",if(complete)bytes else JSONObject.NULL)
                .put("filesChanged",JSONArray(listOfNotNull(if(before.deal!=after.deal)"deal" else null,if(before.ui!=after.ui)"dealui" else null)))
                .put("unchangedDeal",before.deal==after.deal).put("unchangedUi",before.ui==after.ui)
        }
    }
}
