package dev.deal.embedding

import android.content.Context
import androidx.javascriptengine.JavaScriptSandbox
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.function.BiFunction

/** Request-local staging and isolated execution. Policy runs in the DEAL route. */
internal class PortableRequestBackend(
    private val context: Context,
    private val config: RuntimeConfig,
    private val engine: JavaScriptSandbox,
    private val log: EmbeddingLog = EmbeddingLog(context),
    private val transport: (String, JSONObject) -> String
) : AutoCloseable {
    private val compiler = ExperienceCompiler(context, config, log)
    private val outputs = linkedMapOf<String, File>()
    private val machines = linkedMapOf<String, SandboxProgram>()
    private val primitives = SandboxProgram(engine, 256 * 1024)
    private var sequence = 0
    internal var compileCount = 0; private set
    internal var smokeCount = 0; private set
    internal var executionCount = 0; private set
    internal var closedMachines = 0; private set
    init {
        primitives.evaluate("""
            globalThis.portablePrimitive = (operation, input) => {
              const v=input.value;
              switch(operation) {
                case 'numberText': return String(v);
                case 'now': return JSON.stringify({value:Date.now()});
                case 'lowercase': return v.toLowerCase();
                case 'upperFirst': return v.charAt(0).toUpperCase()+v.slice(1);
                case 'slice': return v.substring(input.start,input.end);
                case 'length': return JSON.stringify({value:v.length});
                case 'normalize': return v.normalize('NFKC').toLowerCase();
                case 'units': return JSON.stringify({values:Array.from({length:v.length},(_,i)=>v.charCodeAt(i))});
                case 'test': return JSON.stringify({value:new RegExp(input.pattern,input.flags).test(v)});
                case 'matches': {const m=v.match(new RegExp(input.pattern,input.flags)); return JSON.stringify({values:m||[]});}
                case 'replace': return v.replace(new RegExp(input.pattern,input.flags),input.replacement);
                case 'fixed': return v.toFixed(input.places);
                case 'numberOf': return JSON.stringify({value:Number(v)});
                case 'power': return JSON.stringify({value:Math.pow(input.a,input.b)});
                case 'remainder': return JSON.stringify({value:input.a%input.b});
                case 'groups': return JSON.stringify({values:[...v.matchAll(new RegExp(input.pattern,'g'))].map(m=>Array.from(m,x=>x===undefined?'':x))});
                case 'sentenceEnd': {const m=/[.!?](?:\s|$)/.exec(v);return JSON.stringify({value:m?m.index:-1});}
                default: throw Error('Unknown primitive '+operation);
              }
            }; ''
        """.trimIndent())
    }
    fun build(input: String): JSONObject = JSONObject(PortableRequestBridge.build(input, BiFunction { operation, encoded -> call(operation, JSONObject(encoded)) }))
    fun output(identity: String): File = checkNotNull(outputs[identity]) { "Unknown compiled identity" }
    private fun machine(input: JSONObject): SandboxProgram = checkNotNull(machines[input.getString("machine")]) { "Unknown validation machine" }
    private fun call(operation: String, input: JSONObject): String = when (operation) {
        "compile" -> {
            compileCount++
            try {
                val source = input.getString("source")
                val unread = JSONObject(PortableBackendBridge.unread(source)).getJSONArray("fields")
                val output = compiler.compilePortableValidation(source)
                val identity = "program-${++sequence}"; outputs[identity] = output
                JSONObject().put("identity", identity).put("diagnostics", "").put("unreadFields", unread).toString()
            } catch (error: CandidateRejected) {
                if(error.suppressed.isNotEmpty())throw error
                JSONObject().put("identity", "").put("diagnostics", error.diagnostics).put("unreadFields", JSONArray()).toString()
            }
        }
        "open" -> {
            smokeCount++
            val program = SandboxProgram(engine, 256 * 1024)
            try {
                val handlers = JSONArray(program.evaluateString("eval(" + JSONObject.quote(SandboxBundle.build(context, listOf("portable-machine.js"), "configureDealCapabilities(${JSONArray(config.contracts.map{it.json()})});",
                    "(() => { globalThis.portableMachine=openPortableMachine(factories); return JSON.stringify(portableMachine.handlers); })()", output = output(input.getString("identity")))) + ")"))
                val identity = "machine-${++sequence}"; machines[identity] = program
                JSONObject().put("machine", identity).put("handlers", handlers).toString()
            } catch (error: Throwable) { runCatching { program.close() }.onFailure { error.addSuppressed(it) }; throw error }
        }
        "execute" -> {
            executionCount++
            machine(input).evaluateString("JSON.stringify(portableMachine.execute(${JSONObject.quote(input.getString("name"))},${input.getJSONArray("args")}))")
        }
        "decodeUi" -> machine(input).evaluateString("JSON.stringify(portableMachine.decodeUi(${JSONObject.quote(input.getString("value"))}))")
        "value" -> machine(input).evaluateString("portableMachine.value(($input).value)")
        "effects", "clearEffects" -> { machine(input).evaluate("portableMachine.clear()"); "" }
        "close" -> {
            val identity=input.getString("machine")
            val program = checkNotNull(machines[identity])
            teardown({program.evaluate("portableMachine.close()");Unit},{program.close()})
            machines.remove(identity);closedMachines++; ""
        }
        "holeCompile" -> {
            // Compile the complete provisional source; shared DEAL maps only
            // structured source diagnostics into catalogue hole spans.
            try {
                val source=input.getString("source")
                val syntax=JSONObject(PortableBackendBridge.syntax(source))
                if(syntax.getString("diagnostics").isNotEmpty()) {
                    JSONObject().put("values",syntax.getJSONArray("locations")).toString()
                } else {
                val output=compiler.compilePortableValidation(source)
                val identity="catalogue-${++sequence}";outputs[identity]=output
                StagingReclamation.remove(context,output.parentFile);outputs.remove(identity)
                "{\"values\":[]}"
                }
            } catch(error:CandidateRejected) {
                if(error.suppressed.isNotEmpty())throw error
                val values=JSONArray();val diagnostics=JSONArray(error.diagnostics)
                for(i in 0 until diagnostics.length()) {
                    val diagnostic=diagnostics.getJSONObject(i)
                    // Lowered compiler locations are not reference-source spans.
                    // Until mapped, report outside holes rather than repair a neighbor.
                    values.put(JSONObject().put("line",0).put("message",diagnostic.getString("message")))
                }
                JSONObject().put("values",values).toString()
            }
        }
        // Unsupported execution mechanisms are operational, never successful
        // dummy graphics/play/unread-state answers or model repair authority.
        "pointer", "decodeGfx", "play" -> throw UnsupportedOperationException("PORTABLE_UNSUPPORTED: $operation")
        "numberText", "now", "lowercase", "upperFirst", "slice", "length", "normalize", "units", "test", "matches", "replace", "fixed", "numberOf", "power", "remainder", "groups", "sentenceEnd" ->
            primitives.evaluateString("portablePrimitive(${JSONObject.quote(operation)},$input)")
        else -> transport(operation, input)
    }
    override fun close() {
        var failure: Throwable? = null
        fun attempt(body: () -> Unit) { try { body() } catch (error: Throwable) { if (failure == null) failure = error else failure!!.addSuppressed(error) } }
        machines.entries.toList().forEach { (identity,program) -> attempt { program.close();machines.remove(identity) } }
        outputs.entries.toList().forEach { (identity,output) -> attempt { StagingReclamation.remove(context, output.parentFile);outputs.remove(identity) } }
        attempt { primitives.close() }; failure?.let { throw it }
    }
}
