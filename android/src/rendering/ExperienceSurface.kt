package dev.deal.embedding

import android.os.Handler
import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import org.json.JSONObject
import java.util.concurrent.Executors

/** Explicitly owned session/render adapter. Activate/close off the UI thread.
 * Leaving composition only detaches rendering; it neither closes nor deletes source.
 */
class ExperienceSurface(private val protocol:ApplicationProtocol, val experience:Experience) : AutoCloseable {
    private val main=Handler(Looper.getMainLooper())
    private val actions=Executors.newSingleThreadExecutor()
    private var session:Session?=null
    @Volatile private var ended=false
    var snapshot:UiSnapshot? by mutableStateOf(null)
        private set
    private var failure:((Throwable)->Unit)?=null
    private var acceptedVersion=-1
    private var acceptedFault=""
    /** Host explicitly chooses activation. Repeated activation retains this live session. */
    @Synchronized fun activate(onFailure:(Throwable)->Unit) {
        check(!ended);failure=onFailure
        if(session!=null)return
        val live=protocol.run(experience)
        try {
            val first=protocol.next(live,"")
            session=live
            receive(first.snapshot)
            protocol.observe(live,::receive,::report)
        }catch(error:Throwable){protocol.close(live);throw error}
    }
    private fun receive(value:UiSnapshot){main.post{if(!ended)snapshot=value}}
    private fun report(error:Throwable){main.post{if(!ended)failure?.invoke(error)}}
    internal fun event(slot:Int,payload:String?,before:()->Unit={}) {
        if(ended)return
        before()
        actions.execute{try{session?.let{protocol.dispatch(it,slot,payload)}}catch(error:Throwable){report(error)}}
    }
    internal fun accepted(value:UiSnapshot){
        if(ended||value.version==acceptedVersion&&value.fault==acceptedFault)return
        acceptedVersion=value.version;acceptedFault=value.fault
        actions.execute{try{session?.let{protocol.published(it,value.version)}}catch(error:Throwable){report(error)}}
    }
    /** Explicit host closure releases the live session, never its saved source. */
    @Synchronized override fun close(){if(ended)return;ended=true;actions.shutdown();session?.let{protocol.close(it)};session=null}
}

/** Standard integration: tree rendering, widget forwarding and renderer acceptance.
 * Hooks are optional app presentation/domain notifications, not protocol wiring.
 */
@Composable fun ExperienceSurface(
    surface:ExperienceSurface,
    transformTree:(JSONObject)->JSONObject={it},
    onAction:()->Unit={},
    onSnapshot:(UiSnapshot)->Unit={},
    loading:@Composable ()->Unit={Text("Opening your experience…")}
) {
    val snapshot=surface.snapshot
    if(snapshot==null){loading();return}
    DealRenderer(transformTree(snapshot.tree)){slot,payload->surface.event(slot,payload,onAction)}
    SideEffect {surface.accepted(snapshot)}
    LaunchedEffect(surface,snapshot.version,snapshot.fault){onSnapshot(snapshot)}
}
