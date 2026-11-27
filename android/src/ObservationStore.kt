package dev.deal.embedding

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** One bounded page of retained event lines and the single-use continuation that reads the next one. */
internal class RetainedPage(val lines: List<String>, val cursor: String, val incomplete: Boolean)

/** The classified state of one stored artifact: `captured`, `truncated` or `unavailable`. */
internal class RetainedArtifact(val status: String, val text: String)

/**
 * Recording loss of the observation logger, independent of application outcomes.
 *
 * `incomplete` and `lost` report recording loss, `active` reports whether the logger operation that
 * faulted is still open and `code` classifies it. A recovery closes the operation without erasing the
 * loss it reported, so a later healthy write never rewrites an earlier gap as success.
 */
internal data class RetainedHealth(
    val incomplete: Boolean = false,
    val active: Boolean = false,
    val code: String = "",
    val lost: Int = 0,
) {
    /** One fault: this many records were lost under [code], and the failing operation stays open. */
    fun fault(code: String, count: Int): RetainedHealth =
        copy(incomplete = true, active = true, code = code, lost = lost + count)

    /** The logger wrote again; the loss it already reported is retained. */
    fun recovered(): RetainedHealth = if (active) copy(active = false) else this
}

/**
 * The retained observation store over one directory: bounded paging with single-use continuations,
 * artifact bytes with their digest and truncation marker, and the recording-loss record.
 *
 * Nothing here needs a device: the directory is the only external resource, the event lines are kept
 * verbatim, and the trace of a line is supplied by the caller that owns the event format. A line that
 * reader cannot read is recording loss rather than a line to drop. Selection by trace (an empty trace
 * selects all), a page snapshot that appends cannot disturb, at most 16 issued continuations and their
 * invalidation on clear, and the recorded loss of the logger are therefore decided deterministically.
 */
internal class ObservationStore(private val directory: File) {
    private data class Page(val trace: String, val lines: List<String>, val index: Int)
    private val cursors = LinkedHashMap<String, Page>()

    private fun file(name: String) = File(directory, name)

    /**
     * One page of retained events. A nonempty [cursor] must be one this store issued for the same
     * [trace]; it is consumed whether or not its page still had room. [traceOf] returns the trace of a
     * stored line, or null when the line carries none. A line or directory the store cannot read is
     * recording loss, never a line it may drop quietly: that page is reported incomplete with no events,
     * so an unreadable record can never reach the host inside a page that looks complete. The reader is
     * asked about every retained line, the select-all page included, because a line that is never read
     * is a line whose damage cannot be reported.
     */
    @Synchronized
    fun events(trace: String, cursor: String, limit: Int, traceOf: (String) -> String?): RetainedPage {
        require(limit in 1..200) { "Observation page limit must be 1-200" }
        val page = if (cursor.isEmpty()) {
            val lines = try {
                listOf("previous.jsonl", "events.jsonl").flatMap { file(it).takeIf(File::isFile)?.readLines().orEmpty() }
                    .filter { line ->
                        val lineTrace = traceOf(line)
                        trace.isEmpty() || lineTrace == trace
                    }
            } catch (error: Exception) {
                // Either the retained files could not be read or the caller's reader could not read a line.
                // Both are recording loss, and neither may be reported as a complete page.
                return RetainedPage(emptyList(), "", true)
            }
            Page(trace, lines, 0)
        } else {
            val issued = cursors.remove(cursor) ?: error("Expired observation cursor")
            require(issued.trace == trace) { "Observation cursor belongs to another trace" }
            issued
        }
        val selected = page.lines.drop(page.index).take(limit)
        val next = if (page.index + selected.size < page.lines.size) issue(page.copy(index = page.index + selected.size)) else ""
        return RetainedPage(selected, next, false)
    }

    private fun issue(page: Page): String {
        val cursor = UUID.randomUUID().toString()
        // Sixteen single-use continuations survive appends; the oldest is reclaimed, never silently
        // kept, so a host can never hold seventeen pages of one store open.
        while (cursors.size >= MAX_CURSORS) { cursors.remove(cursors.keys.first()) }
        cursors[cursor] = page
        return cursor
    }

    /** The stored classification of one artifact; an absent store has no artifact to classify. */
    fun artifact(id: String): RetainedArtifact {
        require(id.matches(ARTIFACT_ID)) { "Observation artifact id is not a stored identity" }
        val stored = file("$id.json")
        if (!stored.isFile) return RetainedArtifact("unavailable", "")
        return try {
            val truncated = file("$id.truncated").isFile
            RetainedArtifact(if (truncated) "truncated" else "captured", stored.readText())
        } catch (error: Exception) {
            RetainedArtifact("unavailable", "")
        }
    }

    /**
     * Store one captured artifact under [id], reclaiming the oldest stored artifacts until the bytes fit
     * [storeLimit]. The identity the event record already names owns the bytes that were written.
     */
    @Synchronized
    fun store(bytes: ByteArray, id: String, truncated: Boolean, storeLimit: Int): String {
        require(id.matches(ARTIFACT_ID)) { "Captured artifact identity is not a stored identity" }
        require(bytes.size <= storeLimit) { "Captured artifact exceeds the configured artifact store" }
        val stored = directory.listFiles()?.filter { it.extension == "json" && it.name != "health.json" }
            ?.map { Triple(it, it.lastModified(), it.length()) }?.sortedBy { it.second }.orEmpty()
        var used = stored.sumOf { it.third }
        for ((old, _, length) in stored) {
            if (used + bytes.size <= storeLimit) break
            used -= length
            check(old.delete()) { "Cannot reclaim a stored artifact" }
            file("${old.nameWithoutExtension}.truncated").delete()
        }
        file("$id.json").writeBytes(bytes)
        if (truncated) file("$id.truncated").writeText("")
        return id
    }

    /**
     * The retained health record of this directory: recording loss as the logger last wrote it, or an
     * unusable logger when that record cannot be read. An absent record is a store that recorded no loss
     * yet, never a fault of the reader.
     */
    fun health(): RetainedHealth {
        val stored = file("health.json")
        if (!stored.isFile) return RetainedHealth()
        return try {
            val value = JSONObject(stored.readText())
            RetainedHealth(
                incomplete = value.optBoolean("incomplete"),
                active = value.optBoolean("active"),
                code = value.optString("code"),
                lost = value.optInt("lost"),
            )
        } catch (error: Exception) {
            RetainedHealth(incomplete = true, active = true, code = "LOGGER_READ", lost = 0)
        }
    }

    /** Invalidate every issued continuation. Retained events and artifacts stay. */
    @Synchronized
    fun clear() { cursors.clear() }

    /** The number of continuations a host can still redeem; exposed so the bound is observable. */
    @Synchronized
    fun issued(): Int = cursors.size

    internal companion object {
        const val MAX_CURSORS = 16
        val ARTIFACT_ID = Regex("[a-f0-9-]{36}")

        /** The digest of the stored artifact bytes, exactly as the observation contract records it. */
        fun digest(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
