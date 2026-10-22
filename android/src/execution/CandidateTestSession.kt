package dev.deal.embedding

import org.json.JSONObject

/** Explicit trusted host test hook. Never supplied by generated code; tests run real sessions.
 * Empty feedback means the supplied tests passed, not universal semantic correctness.
 */
class CandidateTestSession internal constructor(private val session: UiSession, private val cancellation: GenerationCancellation, val restored: Boolean = false) {
    fun snapshot(): JSONObject { cancellation.check();return session.advance() }
    fun dispatch(slot: Int, payload: String?) { cancellation.check();session.dispatch(slot,payload) }
    fun pause() { cancellation.check();Thread.sleep(10) }
}
