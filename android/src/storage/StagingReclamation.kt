package dev.deal.embedding

import android.content.Context
import java.io.File

/** Failed staging deletion stays in an app-private durable journal until reclaimed.
 * Only framework candidate directories can enter the journal; never saved source.
 */
internal object StagingReclamation {
    internal var delete:(File)->Boolean={it.deleteRecursively()}
    private fun preferences(context:Context)=context.getSharedPreferences("embedding-reclamation",0)
    @Synchronized fun remove(context:Context,directory:File){
        require(directory.canonicalFile.parentFile==context.filesDir.canonicalFile && directory.name.matches(Regex("candidate-[a-f0-9-]{36}")))
        val prefs=preferences(context)
        val pending=prefs.getStringSet("pending",emptySet()).orEmpty().toMutableSet()
        pending.add(directory.name)
        check(prefs.edit().putStringSet("pending",pending).commit()){"Cannot retain staging cleanup ownership"}
        if(!delete(directory))throw ProtocolFailure("CLEANUP_FAILED","Candidate staging cleanup failed; reclamation retained")
        pending.remove(directory.name)
        check(prefs.edit().putStringSet("pending",pending).commit()){"Cannot acknowledge staging cleanup"}
    }
    @Synchronized fun retry(context:Context){
        val names=preferences(context).getStringSet("pending",emptySet()).orEmpty().toList()
        var failure:Throwable?=null
        for(name in names)try{remove(context,File(context.filesDir,name))}catch(error:Throwable){if(failure==null)failure=error else failure!!.addSuppressed(error)}
        failure?.let{throw it}
    }
    internal fun pending(context:Context)=preferences(context).getStringSet("pending",emptySet()).orEmpty().size
}
