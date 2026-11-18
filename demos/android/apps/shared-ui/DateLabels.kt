package dev.deal.shell

import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Presentation only: exact instants remain in storage, capability arguments and raw diagnostics. */
fun dateLabel(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
fun timeLabel(epochMillis: Long, zone: ZoneId): String = Instant.ofEpochMilli(epochMillis).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
fun dateTimeLabel(epochMillis: Long, zone: ZoneId): String = Instant.ofEpochMilli(epochMillis).atZone(zone).let { "${dateLabel(it.toLocalDate())}, ${it.format(DateTimeFormatter.ofPattern("HH:mm"))}" }
private val isoInstant = Regex("\\b\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?(?:Z|[+-]\\d{2}:\\d{2})(?:\\[[^\\]]+\\])?")
private val isoDate = Regex("\\b\\d{4}-\\d{2}-\\d{2}\\b")
fun friendlyDates(text: String, zone: ZoneId): String {
    val instants = isoInstant.replace(text) { match ->
        runCatching { dateTimeLabel(ZonedDateTime.parse(match.value).toInstant().toEpochMilli(), zone) }.getOrDefault(match.value)
    }
    return isoDate.replace(instants) { match -> runCatching { dateLabel(LocalDate.parse(match.value)) }.getOrDefault(match.value) }
}
