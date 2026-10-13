package dev.deal.embedding

import org.json.JSONArray
import org.json.JSONObject

/** Bounded error evidence only: never handles, authority or stack/local filesystem data. */
internal object FailureEvidence {
    fun encode(error:Throwable,code:(Throwable)->String,depth:Int=0):JSONObject =
        JSONObject().put("code",code(error)).put("message",(error.message?:"Native mechanism failed").take(4000)).also{value->
            if(depth<4)value.put("suppressed",JSONArray(error.suppressed.take(8).map{encode(it,code,depth+1)}))
        }
    fun decode(value:JSONObject,depth:Int=0):ProtocolFailure=
        ProtocolFailure(value.optString("code","EMBEDDING_FAILED"),value.optString("message","Embedding failed")).also{error->
            if(depth<4)value.optJSONArray("suppressed")?.let{items->
                for(i in 0 until minOf(items.length(),8))items.optJSONObject(i)?.let{error.addSuppressed(decode(it,depth+1))}
            }
        }
}
