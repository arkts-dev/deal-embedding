package dev.deal.apps.organizer

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Staging prepares native review; it does not execute a provider write. */
internal class ProposalService(private val store: ProposalStore) {
    fun stage(kind: String, provider: String, title: String, detail: String, price: String,
              deadline: String, experience: String?): JSONObject {
        val reference = UUID.randomUUID().toString()
        val record = JSONObject().put("kind", kind).put("provider", provider).put("title", title)
            .put("detail", detail).put("price", price).put("deadline", deadline)
        store.append(record, reference, experience)
        return JSONObject().put("staged", true).put("reference", reference)
    }
    fun staged(): JSONArray = store.records()
}
