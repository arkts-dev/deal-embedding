package dev.deal.embedding.observation

import dev.deal.embedding.ObservationStore
import dev.deal.embedding.RetainedHealth
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/**
 * The retained observation store of spec v0.8 "Observation", asserted from the contract rather than
 * from the implementation: an empty trace selects every retained event, a trace selects only its own,
 * a continuation reads the snapshot it was issued for even after an append, it is redeemed once, at
 * most sixteen survive, clearing invalidates them, an unreadable retained line reports the page
 * incomplete instead of a complete page that omits it, and the same for the select-all page, a stored
 * artifact reports its bytes, digest and truncation marker, recording loss is reported independently of
 * recovery, and the recorded health snapshot maps its four fields while a record the store cannot read
 * reports an unusable logger.
 */

private val created = mutableListOf<File>()

private fun rejects(what: String, block: () -> Unit) {
    try {
        block()
    } catch (_: Exception) {
        return
    }
    throw AssertionError("$what was accepted")
}

private fun line(trace: String, sequence: Int) = "{\"trace\":\"$trace\",\"sequence\":$sequence}"

/** The caller owns the event format; this store only asks it for the trace of a stored line. */
private fun traceOf(line: String): String? =
    Regex("\"trace\":\"([^\"]*)\"").find(line)?.groupValues?.get(1)

/** The production adapter reads the stored format; a line it cannot parse is recording loss. */
private fun jsonTraceOf(line: String): String? = JSONObject(line).optString("trace")

private fun store(events: List<String>): Pair<File, ObservationStore> {
    val directory = Files.createTempDirectory("observation-store").toFile().also { created.add(it) }
    if (events.isNotEmpty()) File(directory, "events.jsonl").writeText(events.joinToString("\n") + "\n")
    return directory to ObservationStore(directory)
}

private fun digest(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun main() {
    // The one digest the contract names: SHA-256 of the stored artifact bytes.
    check(ObservationStore.digest("abc".toByteArray()) == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad") {
        "the stored artifact digest is not the SHA-256 of its bytes"
    }

    // Selection: an empty trace selects all, a trace selects only its own events, and order is kept.
    val (directory, retained) = store(listOf(line("t1", 1), line("t2", 2), line("t1", 3), line("t2", 4)))
    val all = retained.events("", "", 200, ::traceOf)
    check(all.lines == listOf(line("t1", 1), line("t2", 2), line("t1", 3), line("t2", 4))) {
        "an empty trace must select every retained event in append order"
    }
    check(all.cursor.isEmpty() && !all.incomplete) { "a complete page must issue no continuation and report no loss" }
    val selected = retained.events("t1", "", 200, ::traceOf)
    check(selected.lines == listOf(line("t1", 1), line("t1", 3))) { "a trace must select only its own events" }
    check(retained.events("missing", "", 200, ::traceOf).lines.isEmpty()) { "an unrecorded trace must select nothing" }

    // A page continuation reads its snapshot: appends after issue neither extend nor reorder it, and the
    // continuation is single use.
    val first = retained.events("t1", "", 1, ::traceOf)
    check(first.lines == listOf(line("t1", 1)) && first.cursor.isNotEmpty()) { "a bounded first page must issue a continuation" }
    File(directory, "events.jsonl").appendText(line("t1", 5) + "\n")
    val second = retained.events("t1", first.cursor, 200, ::traceOf)
    check(second.lines == listOf(line("t1", 3))) { "a continuation must read the snapshot it was issued for" }
    check(second.cursor.isEmpty()) { "the last page must issue no continuation" }
    rejects("a redeemed continuation") { retained.events("t1", first.cursor, 200, ::traceOf) }
    // A continuation is bound to its trace, never to whatever trace the caller asks for next.
    val scoped = retained.events("", "", 1, ::traceOf)
    rejects("a continuation of another trace") { retained.events("t1", scoped.cursor, 1, ::traceOf) }
    // A page limit outside 1..200 is refused before it reads anything.
    rejects("a zero page limit") { retained.events("", "", 0, ::traceOf) }
    rejects("a page limit above 200") { retained.events("", "", 201, ::traceOf) }
    check(retained.events("", "", 200, ::traceOf).lines.size == 5) { "the 200 limit must be inside the domain" }

    // A retained line the caller cannot read is recording loss: the page is reported incomplete with no
    // events instead of a complete page that silently omits the line. "No trace on this line" stays a
    // different answer, so a reader that returns null still selects the other events of the trace.
    val (_, torn) = store(listOf(line("t1", 1), "{\"trace\":\"t1\",\"sequence\":"))
    val unreadable = torn.events("t1", "", 200, ::jsonTraceOf)
    check(unreadable.lines.isEmpty() && unreadable.incomplete) {
        "an unreadable retained line must report the page incomplete, never a complete page that omits it"
    }
    // The select-all page asks its reader about every retained line too, so a torn record cannot ride
    // inside a complete page just because the host asked for every trace.
    val selectAll = torn.events("", "", 200, ::jsonTraceOf)
    check(selectAll.lines.isEmpty() && selectAll.incomplete) {
        "an unreadable retained line must report the select-all page incomplete, never a complete page that carries it"
    }
    val traceless = torn.events("t1", "", 200) { null }
    check(traceless.lines.isEmpty() && !traceless.incomplete) {
        "a line that carries no trace is not recording loss"
    }

    // At most sixteen single-use continuations survive appends; issuing a seventeenth reclaims the oldest.
    // The expected value is the spec's own sixteen, checked against the store's declaration, so a store
    // that kept seventeen continuations fails here instead of agreeing with itself.
    check(ObservationStore.MAX_CURSORS == 16) { "the continuation bound must be the spec's sixteen" }
    val (_, bounded) = store((1..40).map { line("t1", it) })
    val issued = (1..16).map { bounded.events("", "", 1, ::traceOf).cursor }
    check(issued.all { it.isNotEmpty() } && bounded.issued() == 16) {
        "the store must hold sixteen continuations"
    }
    val seventeenth = bounded.events("", "", 1, ::traceOf).cursor
    check(bounded.issued() == 16) { "issuing past the bound must reclaim, never grow" }
    rejects("the oldest continuation after the seventeenth was issued") { bounded.events("", issued.first(), 1, ::traceOf) }
    check(bounded.events("", seventeenth, 1, ::traceOf).lines == listOf(line("t1", 2))) {
        "the continuation issued last must be redeemable"
    }

    // Clearing invalidates every continuation and keeps the retained events.
    val (clearDirectory, cleared) = store(listOf(line("t1", 1), line("t1", 2)))
    val outstanding = cleared.events("t1", "", 1, ::traceOf).cursor
    check(outstanding.isNotEmpty()) { "the first of two events must issue a continuation" }
    cleared.clear()
    rejects("a continuation after clear") { cleared.events("t1", outstanding, 1, ::traceOf) }
    check(File(clearDirectory, "events.jsonl").readText().contains("\"sequence\":2")) { "clear must keep the retained events" }

    // Artifacts: an absent identity is unavailable with empty text, a stored one reports its exact bytes,
    // and the truncation marker classifies it. An unissued identity is refused, never read.
    val (artifactDirectory, artifacts) = store(emptyList())
    val absent = artifacts.artifact("11111111-2222-3333-4444-555555555555")
    check(absent.status == "unavailable" && absent.text.isEmpty()) { "an absent artifact must be unavailable with empty text" }
    val identity = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    File(artifactDirectory, "$identity.json").writeBytes("captured 12".toByteArray())
    val captured = artifacts.artifact(identity)
    check(captured.status == "captured" && captured.text == "captured 12") { "a stored artifact must report captured bytes" }
    File(artifactDirectory, "$identity.truncated").writeText("")
    check(artifacts.artifact(identity).status == "truncated") { "a truncation marker must classify the artifact" }
    rejects("an unissued artifact identity") { artifacts.artifact("../../etc/passwd") }

    // The store writes the identity the event record names, keeps the store inside its bound by
    // reclaiming the oldest artifacts, and records the digest of what it wrote.
    val boundedStore = ObservationStore(artifactDirectory)
    fun storeArtifact(id: String, text: String, stamp: Long) {
        boundedStore.store(text.toByteArray(), id, false, 64)
        File(artifactDirectory, "$id.json").setLastModified(stamp)
    }
    val oldest = "00000000-0000-0000-0000-000000000001"
    val middle = "00000000-0000-0000-0000-000000000002"
    val newest = "00000000-0000-0000-0000-000000000003"
    storeArtifact(oldest, "o".repeat(20), 1_000)
    storeArtifact(middle, "m".repeat(20), 2_000)
    boundedStore.store("n".repeat(20).toByteArray(), newest, true, 64)
    check(!File(artifactDirectory, "$oldest.json").exists()) { "the oldest artifact must be reclaimed to stay inside the bound" }
    check(File(artifactDirectory, "$middle.json").isFile && File(artifactDirectory, "$newest.json").isFile) {
        "reclaiming must stop once the capture fits"
    }
    check(boundedStore.artifact(newest).status == "truncated") { "a truncated capture must keep its marker" }
    check(ObservationStore.digest("n".repeat(20).toByteArray()) == digest("n".repeat(20).toByteArray())) {
        "the recorded digest must be the SHA-256 of the stored bytes"
    }
    rejects("a capture above the configured store") { boundedStore.store(ByteArray(65), "00000000-0000-0000-0000-000000000004", false, 64) }

    // Recording loss is reported independently of recovery: a fault marks the logger incomplete and
    // active with its classification and the records it lost, faults accumulate, and recovery closes the
    // operation without erasing the loss it already reported.
    val healthy = RetainedHealth()
    check(!healthy.incomplete && !healthy.active && healthy.lost == 0) { "a logger with no fault reports no loss" }
    val faulted = healthy.fault("QUEUE_LIMIT", 3)
    check(faulted.incomplete && faulted.active && faulted.code == "QUEUE_LIMIT" && faulted.lost == 3) {
        "a fault must report loss, its classification and that the operation is open"
    }
    val twice = faulted.fault("LOGGER_FAILED", 1)
    check(twice.incomplete && twice.active && twice.code == "LOGGER_FAILED" && twice.lost == 4) {
        "faults must accumulate the records they lost"
    }
    val recovered = twice.recovered()
    check(!recovered.active && recovered.incomplete && recovered.lost == 4) {
        "recovery must close the operation without erasing the reported loss"
    }
    check(recovered.recovered() == recovered) { "recovery of a closed logger must change nothing" }

    // The recorded health snapshot is the store's read of the health record: its four loss fields decide
    // it, and a record the store cannot read reports an incomplete, active logger rather than a healthy
    // zero. An absent record is a store that recorded no loss yet, not a fault of the reader.
    val (_, unrecorded) = store(emptyList())
    val absentHealth = unrecorded.health()
    check(!absentHealth.incomplete && !absentHealth.active && absentHealth.code.isEmpty() && absentHealth.lost == 0) { "a store with no health record reports no loss" }
    val (healthDirectory, recorded) = store(emptyList())
    File(healthDirectory, "health.json").writeText("""{"incomplete":true,"active":true,"code":"QUEUE_LIMIT","lost":4}""")
    check(recorded.health() == RetainedHealth(incomplete = true, active = true, code = "QUEUE_LIMIT", lost = 4)) {
        "the recorded health snapshot is its incomplete, active, code and lost fields"
    }
    File(healthDirectory, "health.json").writeText("{not a record")
    check(recorded.health() == RetainedHealth(incomplete = true, active = true, code = "LOGGER_READ", lost = 0)) {
        "an unreadable health record reports the logger incomplete and active, never a healthy zero"
    }
    created.forEach { it.deleteRecursively() }
    println("observation store: trace selection, page continuations, their bound and invalidation, artifact bytes, digest and truncation, independent recording loss, the health record read, and an unreadable retained line")
}
