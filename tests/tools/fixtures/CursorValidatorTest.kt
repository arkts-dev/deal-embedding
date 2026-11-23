package dev.deal.embedding

/**
 * Deterministic items for the Session cursor of spec v0.8 "Host protocol": `next(session, cursor)`
 * reads the current state and registers a wait for a later state or fault, and a cursor is accepted
 * only for the Session that issued it and only at or below that Session's current revision. Every
 * rejection is the public `INVALID_CURSOR` failure, so a tampered, foreign, malformed or advanced
 * cursor can never read another Session's state or establish a wait that has already passed.
 *
 * The validator is the one `Embedding.nextAsync` calls: it owns the per-Session secret and the revision
 * bound, so this file exercises the production code and invents no second cursor format.
 */
private fun refused(body: () -> Unit): Boolean =
    (runCatching(body).exceptionOrNull() as? ProtocolFailure)?.code == "INVALID_CURSOR"

fun main() {
    val session = UpdateCursor()
    val issued = session.issue(7)
    check(session.revision(issued, 7) == 7L) { "an issued cursor is accepted at its own revision" }
    check(session.revision(issued, 9) == 7L) { "a cursor stays valid while its revision is still current" }

    val value = issued.substringBefore('.')
    val signature = issued.substringAfter('.')
    val flipped = (if (signature.first() == '0') '1' else '0') + signature.drop(1)
    check(refused { session.revision("$value.$flipped", 7) }) { "a tampered signature is refused" }
    check(refused { session.revision("$value.${signature.dropLast(1)}", 7) }) { "a truncated signature is refused" }
    check(refused { session.revision("$value.", 7) }) { "an empty signature is refused" }
    check(refused { UpdateCursor().revision(issued, 7) }) { "another Session's cursor is refused" }
    check(refused { session.revision(issued, 6) }) { "a cursor ahead of the current revision is refused" }
    check(refused { session.revision("-1.$signature", 7) }) { "a negative revision is refused" }
    check(refused { session.revision("0$value.$signature", 7) }) { "a leading-zero revision is refused" }
    check(refused { session.revision("7", 7) }) { "a cursor without a signature is refused" }
    check(refused { session.revision("$issued.extra", 7) }) { "a cursor with an extra part is refused" }
    check(refused { session.revision("seven.$signature", 7) }) { "a nonnumeric revision is refused" }
    check(refused { session.revision(issued + "0".repeat(20), 7) }) { "a cursor over the 85 character bound is refused" }

    val earlier = session.issue(3)
    check(earlier != issued) { "two revisions of one Session are distinct cursors" }
    check(session.revision(earlier, 7) == 3L) { "the earlier revision reads the state it was issued for" }
    check(session.revision(issued, 7) == 7L) { "the later revision is still accepted after an earlier one" }
    println("session cursors: an issued cursor is accepted at and below its Session revision; a tampered, foreign, malformed or over-bound cursor is refused with INVALID_CURSOR")
}
