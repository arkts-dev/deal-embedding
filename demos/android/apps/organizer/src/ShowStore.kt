package dev.deal.apps.organizer

import android.content.Context
import java.time.LocalDate

/** The show plan is the organizer's own record. Nothing here is derived from a generated workspace. */
class ShowStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("organizer", 0)

    fun load(): ShowState? = prefs.getString("show", null)?.let { runCatching { ShowState.parse(it) }.getOrNull() }

    fun save(state: ShowState) {
        check(prefs.edit().putString("show", state.toJson().toString()).commit()) { "Cannot save the show plan" }
    }

    fun initialize(date: LocalDate = nextFriday()): ShowState = ShowState.fixture(date).also(::save)

    fun clear() { prefs.edit().clear().commit() }

    companion object {
        fun nextFriday(today: LocalDate = LocalDate.now()): LocalDate {
            var candidate = today.plusDays(1)
            while (candidate.dayOfWeek.value != 5) candidate = candidate.plusDays(1)
            return candidate
        }
    }
}
