package dev.deal.embedding

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Authenticated session-local revisions: constant retained space, no expiry of issued cursors. */
internal class UpdateCursor {
    private val secret=ByteArray(32).also{SecureRandom().nextBytes(it)}
    private fun signature(revision:String):String=Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(secret,"HmacSHA256"));doFinal(revision.toByteArray(Charsets.US_ASCII)).joinToString(""){"%02x".format(it)}
    }
    fun issue(revision:Long):String=revision.toString().let{"$it.${signature(it)}"}
    fun revision(cursor:String,current:Long):Long {
        val parts=cursor.split('.');val value=parts.firstOrNull()?.toLongOrNull()
        if(cursor.length>85||parts.size!=2||value==null||value<0||value>current||parts[0]!=value.toString()||
            !MessageDigest.isEqual(parts[1].toByteArray(),signature(parts[0]).toByteArray()))
            throw ProtocolFailure("INVALID_CURSOR","Unissued or foreign cursor")
        return value
    }
}
