package com.manishlad.glasslauncher.data

import android.content.Context
import android.util.Log
import biweekly.Biweekly
import biweekly.component.VEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import java.util.UUID

private const val TAG = "GlassLauncher"

/** An Apple iCloud public calendar link. [url] is a secret: never log it. */
data class CalendarSource(val id: String, val name: String, val color: Long, val url: String)

data class CalendarStatus(val lastSync: Instant? = null, val error: String? = null)

data class CalEvent(
    val calendarId: String,
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val allDay: Boolean,
    val location: String?,
)

data class CalendarState(
    val sources: List<CalendarSource> = emptyList(),
    val status: Map<String, CalendarStatus> = emptyMap(),
    /** Merged events from all calendars, sorted by start. */
    val events: List<CalEvent> = emptyList(),
    /** False until the stored calendars have been read (avoids flashing "No calendars yet"). */
    val loaded: Boolean = true,
) {
    fun source(id: String) = sources.firstOrNull { it.id == id }

    /** Age of the oldest successful sync, for "Synced N min ago". */
    val syncedAt: Instant? get() = status.values.mapNotNull { it.lastSync }.minOrNull()

    fun upcoming(now: LocalDateTime) = events.filter { it.end.isAfter(now) && !(it.allDay && it.start.toLocalDate().isBefore(now.toLocalDate())) }

    fun on(date: LocalDate) = events.filter { e ->
        if (e.allDay) !date.isBefore(e.start.toLocalDate()) && date.isBefore(e.end.toLocalDate())
        else e.start.toLocalDate() == date
    }
}

/**
 * Apple iCloud published calendars (ICS over https). Sources and the cached ICS live only in
 * app-private storage on the TV. Refreshed every 30 min; cached copies keep the widget alive offline.
 */
class CalendarRepository(context: Context, private val http: OkHttpClient) {
    private val dir = File(context.filesDir, "calendars").apply { mkdirs() }
    private val sourcesFile = File(dir, "sources.json")
    private val statusFile = File(dir, "status.json")
    private val mutex = Mutex()

    private val _state = MutableStateFlow(CalendarState(loaded = false))
    val state: StateFlow<CalendarState> = _state
    private var lastAttempt: Instant = Instant.EPOCH

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val sources = readSources()
            _state.value = CalendarState(sources, readStatus(), expandAll(sources))
        }
    }

    suspend fun add(name: String, url: String, color: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val sources = readSources() + CalendarSource(UUID.randomUUID().toString(), name, color, normalise(url))
            writeSources(sources)
            _state.value = _state.value.copy(sources = sources)
        }
        refresh()
    }

    suspend fun remove(name: String) = removeWhere { it.name.equals(name, ignoreCase = true) }

    suspend fun removeById(id: String) = removeWhere { it.id == id }

    private suspend fun removeWhere(match: (CalendarSource) -> Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val (gone, kept) = readSources().partition(match)
            gone.forEach { File(dir, "${it.id}.ics").delete() }
            writeSources(kept)
            val status = readStatus() - gone.map { it.id }.toSet()
            writeStatus(status)
            _state.value = CalendarState(kept, status, expandAll(kept))
        }
    }

    suspend fun refreshIfStale(maxAge: Duration = Duration.ofMinutes(30)) {
        val s = _state.value
        if (s.sources.isEmpty()) return
        val now = Instant.now()
        val oldest = s.sources.minOf { s.status[it.id]?.lastSync ?: Instant.EPOCH }
        if (Duration.between(oldest, now) < maxAge) return
        if (Duration.between(lastAttempt, now) < Duration.ofMinutes(5)) return
        refresh()
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        lastAttempt = Instant.now()
        mutex.withLock {
            val sources = readSources()
            val status = readStatus().toMutableMap()
            for (src in sources) {
                status[src.id] = try {
                    val body = http.newCall(Request.Builder().url(src.url).build()).execute().use { r ->
                        if (!r.isSuccessful) throw HttpError(r.code)
                        r.body!!.string()
                    }
                    Biweekly.parse(body).first() ?: throw IOException("not a calendar")
                    File(dir, "${src.id}.ics").writeText(body)
                    CalendarStatus(lastSync = Instant.now())
                } catch (e: HttpError) {
                    Log.w(TAG, "calendar '${src.name}' sync failed: HTTP ${e.code}")
                    (status[src.id] ?: CalendarStatus()).copy(error = "Couldn't sync · link may have been reset")
                } catch (e: Exception) {
                    Log.w(TAG, "calendar '${src.name}' sync failed: ${e.javaClass.simpleName}")
                    (status[src.id] ?: CalendarStatus()).copy(error = "Couldn't sync · check the connection")
                }
            }
            writeStatus(status)
            _state.value = CalendarState(sources, status, expandAll(sources))
        }
    }

    private class HttpError(val code: Int) : IOException()

    private fun normalise(url: String) = url.trim().replaceFirst(Regex("^webcals?://", RegexOption.IGNORE_CASE), "https://")

    private fun expandAll(sources: List<CalendarSource>): List<CalEvent> {
        val today = LocalDate.now()
        val from = today.minusDays(14)
        val to = today.plusDays(35)
        return sources.flatMap { src ->
            val file = File(dir, "${src.id}.ics")
            if (!file.exists()) emptyList()
            else runCatching { IcsExpander.expand(src.id, file.readText(), from, to) }
                .onFailure { Log.w(TAG, "calendar '${src.name}' parse failed: ${it.javaClass.simpleName}") }
                .getOrDefault(emptyList())
        }.sortedWith(compareBy({ it.start }, { !it.allDay }))
    }

    private fun readSources(): List<CalendarSource> {
        if (!sourcesFile.exists()) return emptyList()
        val a = JSONArray(sourcesFile.readText())
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            CalendarSource(o.getString("id"), o.getString("name"), o.getLong("color"), o.getString("url"))
        }
    }

    private fun writeSources(sources: List<CalendarSource>) {
        sourcesFile.writeText(JSONArray(sources.map {
            JSONObject().put("id", it.id).put("name", it.name).put("color", it.color).put("url", it.url)
        }).toString())
    }

    private fun readStatus(): Map<String, CalendarStatus> {
        if (!statusFile.exists()) return emptyMap()
        val o = JSONObject(statusFile.readText())
        return o.keys().asSequence().associateWith { id ->
            val s = o.getJSONObject(id)
            CalendarStatus(
                lastSync = if (s.has("lastSync")) Instant.ofEpochMilli(s.getLong("lastSync")) else null,
                error = if (s.has("error")) s.getString("error") else null,
            )
        }
    }

    private fun writeStatus(status: Map<String, CalendarStatus>) {
        val o = JSONObject()
        for ((id, s) in status) o.put(id, JSONObject().apply {
            s.lastSync?.let { put("lastSync", it.toEpochMilli()) }
            s.error?.let { put("error", it) }
        })
        statusFile.writeText(o.toString())
    }
}

/** Expands VEVENTs (RRULE/RDATE/EXDATE, RECURRENCE-ID overrides, time zones) into a date window. */
object IcsExpander {
    private const val MAX_PER_EVENT = 400

    fun expand(calendarId: String, ics: String, from: LocalDate, to: LocalDate): List<CalEvent> {
        val zone = ZoneId.systemDefault()
        val windowStart = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val windowEnd = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val out = ArrayList<CalEvent>()
        for (ical in Biweekly.parse(ics).all()) {
            val tzInfo = ical.timezoneInfo
            val events = ical.events
            // Instances replaced by a RECURRENCE-ID override: (uid, original start millis).
            val overridden = events.filter { it.recurrenceId != null }
                .mapNotNull { e -> e.uid?.value?.let { it to e.recurrenceId.value.time } }.toSet()

            for (ev in events) {
                if (ev.status?.isCancelled == true) continue
                val dtStart = ev.dateStart ?: continue
                val start = dtStart.value ?: continue
                val allDay = !start.hasTime()
                val durationMs = ev.dateEnd?.value?.let { it.time - start.time }
                    ?: ev.duration?.value?.toMillis()
                    ?: if (allDay) 86_400_000L else 0L
                val title = ev.summary?.value?.takeIf { it.isNotBlank() } ?: "Busy"
                val location = ev.location?.value?.takeIf { it.isNotBlank() }

                fun emit(startMs: Long) {
                    val s = toLocal(startMs, zone)
                    val e = toLocal(startMs + durationMs, zone)
                    out += CalEvent(calendarId, title, s, e, allDay, location)
                }

                val recurs = ev.recurrenceRule != null || ev.recurrenceDates.isNotEmpty()
                if (!recurs || ev.recurrenceId != null) {
                    if (start.time < windowEnd && start.time + durationMs > windowStart) emit(start.time)
                    continue
                }
                val tz: TimeZone = if (allDay) TimeZone.getDefault()
                    else tzInfo.getTimezone(dtStart)?.timeZone ?: TimeZone.getDefault()
                val uid = ev.uid?.value
                val it = ev.getDateIterator(tz)
                it.advanceTo(java.util.Date(windowStart - durationMs))
                var n = 0
                while (it.hasNext() && n < MAX_PER_EVENT) {
                    val t = it.next().time
                    if (t >= windowEnd) break
                    n++
                    if (uid != null && (uid to t) in overridden) continue
                    if (t + durationMs > windowStart) emit(t)
                }
            }
        }
        return out
    }

    private fun toLocal(ms: Long, zone: ZoneId) = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)
}
