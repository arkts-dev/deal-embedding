package dev.deal.embedding

import java.io.File

/** Optional host receipts never determine operation success or renderer progress. */
object DiagnosticFiles {
    fun write(directory:File,name:String,text:String) {
        try {
            val temporary=File(directory,"$name.tmp")
            temporary.writeText(text)
            check(temporary.renameTo(File(directory,name))){"Diagnostic rename failed"}
        }catch(error:Exception){android.util.Log.w("DealDiagnostics","Cannot retain $name",error)}
    }
}
