package dev.deal.embedding

import android.content.Context
import dev.deal.embedding.capabilities.NativeCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Executes an app-linked, locally compiled policy with an explicit, private mechanism inventory.
 * Never shares these bindings with generated experience isolates. Driving and async transport are
 * framework-owned; hosts supply facts/transport, not an evaluation loop.
 */
internal class TrustedProgram(context: Context, assets: String, entry: String, mechanisms: List<NativeCapabilities>, private val model: ModelClient) : AutoCloseable {
    private val cancellation = GenerationCancellation()
    private val log = EmbeddingLog(context)
    /** Observation scope only; never an execution or authority handle. */
    val trace: String get() = log.run
    private val engine = SandboxEngine.acquire(context)
    private val program = SandboxProgram(engine, 256 * 1024)
    private val handlers = mechanisms.associateBy { it.contract.module }
    private val workers = Executors.newCachedThreadPool()
    private val driver = Executors.newSingleThreadScheduledExecutor()
    private val calls = mutableMapOf<String, CompletableFuture<String>>()
    private val replies = java.util.concurrent.ConcurrentLinkedQueue<JSONObject>()
    private var sequence = 0
    private var closed = false
    init {
        require(handlers.size == mechanisms.size)
        try {
            val configure = "configureDealCapabilities([...Object.values(dealHostContracts),...${JSONArray(mechanisms.map { it.contract.json() })}]);"
            program.evaluate(SandboxBundle.build(context, listOf("generation-contracts.js", "protocol-contracts.js", "protocol-host.js"), configure,
                "(() => {globalThis.trustedEntry=dealLoad(factories,${JSONObject.quote(entry)}).entry;globalThis.trustedResults=new Map();return '';})()", assetRoot = assets))
            driver.scheduleWithFixedDelay({ advance() }, 0, 5, TimeUnit.MILLISECONDS)
        } catch (error: Throwable) {
            runCatching { teardown({ program.close() }, { SandboxEngine.release(engine) }, { workers.shutdownNow() }, { driver.shutdownNow() }) }.onFailure { error.addSuppressed(it) }
            throw error
        }
    }
    /** String ABI keeps domain schemas in the compiled policy, not a duplicate native DTO. */
    @Synchronized fun call(name: String, vararg arguments: String): CompletableFuture<String> {
        check(!closed && calls.size < 8) { "Trusted program closed or invocation limit reached" }
        require(arguments.size <= 8 && arguments.sumOf { it.toByteArray(Charsets.UTF_8).size } <= 16384)
        require(name.matches(Regex("[A-Za-z][A-Za-z0-9_]*")))
        val key = (++sequence).toString()
        val future = CompletableFuture<String>()
        calls[key] = future
        try {
            program.evaluate("(() => {const fn=trustedEntry[${JSONObject.quote(name)}];if(!fn||typeof fn.\$f!=='function')throw Error('Unknown trusted export');Promise.resolve(fn.\$f(...${JSONArray(arguments.toList())})).then(value=>{if(typeof value!=='string')throw Error('Trusted result must be string');trustedResults.set('$key',{value});}).catch(e=>trustedResults.set('$key',{error:{code:e.code||'POLICY_FAILED',message:e.message||'Policy failed'}}));return '';})()")
        } catch (error: Throwable) { calls.remove(key); future.completeExceptionally(error) }
        return future
    }
    @Synchronized private fun advance() {
        if (closed) return
        try {
            val outgoing = JSONArray(program.evaluate("dealCapabilities.take()"))
            for (i in 0 until outgoing.length()) {
                val request = outgoing.getJSONObject(i)
                workers.execute {
                    val reply = JSONObject().put("id", request.getInt("id"))
                    try {
                        val module = request.getString("module")
                        val name = request.getString("function")
                        val args = request.getJSONArray("args")
                        val value: Any = when (module) {
                            "embedding/policy-data" -> when (name) {
                                "sha256" -> java.security.MessageDigest.getInstance("SHA-256").digest(args.getString(0).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                                "parse" -> try { JSONObject(args.getString(0)) } catch (error: org.json.JSONException) { throw ProtocolFailure("INVALID_JSON", "Expected JSON object") }
                                else -> error("Unlinked data mechanism")
                            }
                            "embedding/protocol" -> when (name) {
                                "questions" -> ChoiceProtocol.questions(args.getJSONArray(0)).toString()
                                "answers" -> ChoiceProtocol.answers(args.getString(0), args.getJSONArray(1))
                                else -> error("Unlinked choice mechanism")
                            }
                            "embedding/chooser" -> {
                                require(name == "choose")
                                val bounded = GenerationCancellation()
                                val registration = cancellation.onCancel { bounded.cancel() }
                                val deadline = driver.schedule({ bounded.cancel() }, 60, TimeUnit.SECONDS)
                                try {
                                    log.operation("choice", detail = args.getString(0)) { child -> model.complete(args.getString(0), "", "", bounded, child).also { bounded.check(); child.event("choice", "response", detail = it) } }
                                } finally { deadline.cancel(false); registration.close() }
                            }
                            else -> (handlers[module] ?: error("Unlinked trusted mechanism")).invoke(name, args)
                        }
                        reply.put("ok", true).put("value", value)
                    } catch (error: Throwable) { reply.put("ok", false).put("error", JSONObject().put("code", if (error is ProtocolFailure) error.code else "HOST_FAILED").put("message", error.message?.take(200) ?: "Trusted mechanism failed")) }
                    replies.add(reply)
                }
            }
            val completed = JSONArray()
            while (true) completed.put(replies.poll() ?: break)
            if (completed.length() > 0) program.evaluate("dealCapabilities.deliver($completed)")
            for ((key, future) in calls.toMap()) {
                val raw = program.evaluate("JSON.stringify(trustedResults.get('$key')||null)")
                if (raw == "null") continue
                val result = JSONObject(raw)
                calls.remove(key)
                program.evaluate("trustedResults.delete('$key');''")
                if (result.has("error")) { val e = result.getJSONObject("error"); future.completeExceptionally(ProtocolFailure(e.getString("code"), e.getString("message"))) }
                else future.complete(result.getString("value"))
            }
        } catch (error: Throwable) { calls.values.toList().forEach { it.completeExceptionally(error) }; calls.clear() }
    }
    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            cancellation.cancel()
            calls.values.toList().forEach { it.completeExceptionally(ProtocolFailure("CLOSED", "Trusted policy closed")) }; calls.clear()
        }
        teardown({ driver.shutdownNow() }, { workers.shutdownNow() }, { program.close() }, { SandboxEngine.release(engine) },
            { check(driver.awaitTermination(10, TimeUnit.SECONDS)) }, { check(workers.awaitTermination(10, TimeUnit.SECONDS)) })
    }
}
