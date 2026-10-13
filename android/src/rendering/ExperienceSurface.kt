package dev.deal.embedding

import android.os.Handler
import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import org.json.JSONObject
import java.util.concurrent.Executors

/** Explicit session owner. Composition attaches observation, never execution ownership. */
class ExperienceSurface(private val protocol:ApplicationProtocol, val experience:Experience) : AutoCloseable {
    private val main=Handler(Looper.getMainLooper())
    private val actions=Executors.newSingleThreadExecutor()
    @Volatile private var session:Session?=null
    @Volatile private var epoch=0L
    @Volatile private var ownership=0L
    @Volatile private var terminal:Session?=null
    @Volatile private var ended=false
    private var attachments=0
    private var observation:AutoCloseable?=null
    var snapshot:UiSnapshot? by mutableStateOf(null)
        private set
    @Volatile private var snapshotSession:Session?=null
    private var failure:((Throwable)->Unit)?=null
    private var acceptedVersion=-1
    private var acceptedFault=""
    /** Explicit reactivation recovers terminal ownership; healthy sessions retain their state. */
    @Synchronized fun activate(onFailure:(Throwable)->Unit) {
        check(!ended);failure=onFailure
        session?.let{live->
            if(terminal!=live){
                try{
                    val current=protocol.next(live,"").snapshot
                    if(current.fault.isEmpty()){receive(live,epoch,current);return}
                }catch(error:ProtocolFailure){if(error.code !in setOf("INVALID_HANDLE","CLOSED","SESSION_FAILED"))throw error}
                terminal=live
            }
        }
        observation?.close();observation=null
        session?.let{live->try{protocol.close(live)}catch(error:ProtocolFailure){if(error.code!="INVALID_HANDLE")throw error}};session=null;terminal=null;epoch++;ownership++
        val live=protocol.run(experience)
        try {
            val first=protocol.next(live,"")
            session=live;acceptedVersion=-1;acceptedFault=""
            receive(live,epoch,first.snapshot)
            if(attachments>0)observe(live)
        }catch(error:Throwable){runCatching{protocol.close(live)}.onFailure{error.addSuppressed(it)};throw error}
    }
    private fun observe(live:Session){
        val generation=++epoch
        observation=protocol.observe(live,{receive(live,generation,it)},{report(live,generation,it,true)})
    }
    /** Detach receives no later callbacks; execution continues in embedding independently. */
    @Synchronized internal fun attach():AutoCloseable {
        check(!ended);attachments++
        if(attachments==1 && observation==null)session?.let(::observe)
        var detached=false
        return AutoCloseable{synchronized(this){if(!detached){detached=true;attachments--;if(attachments==0){observation?.close();observation=null;epoch++}}}}
    }
    private fun receive(live:Session,generation:Long,value:UiSnapshot){
        if(ended||generation!=epoch||session!=live)return
        if(value.fault.isNotEmpty())terminal=live
        main.post{if(!ended&&generation==epoch&&session==live){snapshotSession=live;snapshot=value}}
    }
    private fun report(live:Session,generation:Long,error:Throwable,terminalFailure:Boolean=false){
        if(ended||generation!=epoch||session!=live)return
        if(terminalFailure || error is ProtocolFailure && error.code in setOf("CLOSED","SESSION_FAILED","INVALID_HANDLE"))terminal=live
        main.post{if(!ended&&generation==epoch&&session==live)failure?.invoke(error)}
    }
    internal fun event(value:UiSnapshot,slot:Int,payload:String?,before:()->Unit={}) {
        synchronized(this){
            if(ended||value!==snapshot||snapshotSession!=session)return
            val live=session?:return;val generation=epoch;val owner=ownership
            before()
            actions.execute{if(!ended&&owner==ownership&&session==live)try{protocol.dispatch(live,slot,payload)}catch(error:Throwable){report(live,generation,error)}}
        }
    }
    internal fun accepted(value:UiSnapshot){
        synchronized(this){
            if(ended||value!==snapshot||snapshotSession!=session||value.version==acceptedVersion&&value.fault==acceptedFault)return
            val live=session?:return;val generation=epoch;val owner=ownership
            acceptedVersion=value.version;acceptedFault=value.fault
            actions.execute{if(!ended&&owner==ownership&&session==live)try{protocol.published(live,value.version)}catch(error:Throwable){report(live,generation,error)}}
        }
    }
    /** Explicit closure retains saved source. A failed closure remains retryable. */
    @Synchronized override fun close(){
        if(ended&&session==null&&observation==null)return
        ended=true;epoch++;ownership++
        teardown(
            {observation?.close();observation=null},
            {session?.let{protocol.close(it)};session=null;terminal=null},
            {actions.shutdownNow()}
        )
    }
}

/** Standard rendering, detachable reception, widget forwarding and renderer acceptance. */
@Composable fun ExperienceSurface(
    surface:ExperienceSurface,
    transformTree:(JSONObject)->JSONObject={it},
    onAction:()->Unit={},
    onSnapshot:(UiSnapshot)->Unit={},
    loading:@Composable ()->Unit={Text("Opening your experience…")}
) {
    DisposableEffect(surface){val attachment=surface.attach();onDispose{attachment.close()}}
    val snapshot=surface.snapshot
    if(snapshot==null){loading();return}
    DealRenderer(transformTree(snapshot.tree)){slot,payload->surface.event(snapshot,slot,payload,onAction)}
    SideEffect {surface.accepted(snapshot)}
    LaunchedEffect(surface,snapshot.version,snapshot.fault){
        try{onSnapshot(snapshot)}catch(error:Exception){android.util.Log.w("DealDiagnostics","Snapshot hook failed",error)}
    }
}
