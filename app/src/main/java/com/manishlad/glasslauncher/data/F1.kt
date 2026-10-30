package com.manishlad.glasslauncher.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

private const val TAG = "GlassLauncher"
private const val API = "https://api.jolpi.ca/ergast/f1"

data class F1Session(val name: String, val start: Instant)

data class F1Race(
    val season: String,
    val round: Int,
    val name: String,
    val circuit: String,
    val locality: String,
    val country: String,
    /** All sessions in order, race last. */
    val sessions: List<F1Session>,
) {
    val race: F1Session get() = sessions.last()

    /** The next session that hasn't started yet, or the one in progress (sessions run ~2 h). */
    fun nextSession(now: Instant): F1Session? =
        sessions.firstOrNull { it.start.plus(Duration.ofHours(2)).isAfter(now) }
}

data class F1Standing(val position: Int, val name: String, val code: String, val team: String, val points: String)

data class F1Podium(val raceName: String, val date: LocalDate, val top3: List<String>)

data class F1Data(
    val fetchedAt: Instant,
    val next: F1Race?,
    val afterRound: Int?,
    val drivers: List<F1Standing>,
    val constructors: List<F1Standing>,
    val lastRace: F1Podium?,
)

data class F1State(val data: F1Data? = null, val lastAttemptFailed: Boolean = false)

/**
 * Formula 1 from the Jolpica API (Ergast-compatible, free, no key): next race with session times,
 * driver and constructor standings, last race podium. Cached so the widget works offline.
 */
class F1Repository(context: Context, private val http: OkHttpClient) {
    private val dir = File(context.filesDir, "f1").apply { mkdirs() }
    private val files = mapOf(
        "next" to File(dir, "next.json"),
        "drivers" to File(dir, "drivers.json"),
        "constructors" to File(dir, "constructors.json"),
        "last" to File(dir, "last.json"),
    )
    private val stampFile = File(dir, "fetched_at")

    private val _state = MutableStateFlow(F1State())
    val state: StateFlow<F1State> = _state
    private var lastAttempt: Instant = Instant.EPOCH

    suspend fun loadCache() = withContext(Dispatchers.IO) {
        runCatching { parseCached() }.onSuccess { _state.value = F1State(it) }
            .onFailure { Log.w(TAG, "f1 cache unreadable: ${it.javaClass.simpleName}") }
    }

    /**
     * Hourly normally; every 15 min around a race weekend (results and standings change);
     * and straight away once the cached "next race" is over.
     */
    suspend fun refreshIfStale() {
        val now = Instant.now()
        val data = _state.value.data
        val fetchedAt = data?.fetchedAt ?: Instant.EPOCH
        val raceOver = data?.next?.race?.start?.plus(Duration.ofHours(3))?.isBefore(now) == true
        val weekend = data?.next?.sessions?.firstOrNull()?.start?.minus(Duration.ofHours(12))?.isBefore(now) == true
        val maxAge = if (raceOver || weekend) Duration.ofMinutes(15) else Duration.ofHours(1)
        if (Duration.between(fetchedAt, now) < maxAge) return
        if (Duration.between(lastAttempt, now) < Duration.ofMinutes(5)) return
        refresh()
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        lastAttempt = Instant.now()
        try {
            val bodies = mapOf(
                "next" to get("$API/current/next.json"),
                "drivers" to get("$API/current/driverStandings.json"),
                "constructors" to get("$API/current/constructorStandings.json"),
                "last" to runCatching { get("$API/current/last/results.json") }.getOrNull(),
            )
            for ((k, body) in bodies) if (body != null) files.getValue(k).writeText(body)
            stampFile.writeText(Instant.now().toEpochMilli().toString())
            _state.value = F1State(parseCached())
            Log.i(TAG, "f1 refreshed")
        } catch (e: Exception) {
            Log.w(TAG, "f1 refresh failed: ${e.javaClass.simpleName}: ${e.message}")
            _state.value = _state.value.copy(lastAttemptFailed = true)
        }
    }

    private fun get(url: String): String =
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body!!.string()
        }

    private fun read(key: String): JSONObject? = files.getValue(key).takeIf { it.exists() }?.let { JSONObject(it.readText()) }

    private fun parseCached(): F1Data? {
        val nextJson = read("next") ?: return null
        val races = nextJson.getJSONObject("MRData").getJSONObject("RaceTable").getJSONArray("Races")
        val next = if (races.length() == 0) null else F1Parse.race(races.getJSONObject(0))
        val (afterRound, drivers) = read("drivers")?.let { F1Parse.drivers(it) } ?: (null to emptyList())
        val constructors = read("constructors")?.let { F1Parse.constructors(it) } ?: emptyList()
        val last = read("last")?.let { F1Parse.lastRace(it) }
        val fetchedAt = stampFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()?.let(Instant::ofEpochMilli)
            ?: Instant.ofEpochMilli(files.getValue("next").lastModified())
        return F1Data(fetchedAt, next, afterRound, drivers, constructors, last)
    }
}

/** Parsing for the Ergast-format JSON (kept separate for unit tests). */
object F1Parse {
    private val SESSIONS = listOf(
        "FirstPractice" to "Practice 1",
        "SecondPractice" to "Practice 2",
        "ThirdPractice" to "Practice 3",
        "SprintQualifying" to "Sprint Qualifying",
        "SprintShootout" to "Sprint Shootout",
        "Sprint" to "Sprint",
        "Qualifying" to "Qualifying",
    )

    private fun instant(o: JSONObject): Instant {
        val date = LocalDate.parse(o.getString("date"))
        val time = o.optString("time").takeIf { it.isNotBlank() }?.let { LocalTime.parse(it.removeSuffix("Z")) } ?: LocalTime.NOON
        return date.atTime(time).toInstant(ZoneOffset.UTC)
    }

    fun race(r: JSONObject): F1Race {
        val loc = r.getJSONObject("Circuit").getJSONObject("Location")
        val sessions = SESSIONS.mapNotNull { (key, label) -> r.optJSONObject(key)?.let { F1Session(label, instant(it)) } }
            .sortedBy { it.start } + F1Session("Race", instant(r))
        return F1Race(
            season = r.getString("season"),
            round = r.getString("round").toInt(),
            name = r.getString("raceName"),
            circuit = r.getJSONObject("Circuit").getString("circuitName"),
            locality = loc.getString("locality"),
            country = loc.getString("country"),
            sessions = sessions,
        )
    }

    fun drivers(o: JSONObject): Pair<Int?, List<F1Standing>> {
        val lists = o.getJSONObject("MRData").getJSONObject("StandingsTable").getJSONArray("StandingsLists")
        if (lists.length() == 0) return null to emptyList()
        val l = lists.getJSONObject(0)
        val a = l.getJSONArray("DriverStandings")
        return l.optString("round").toIntOrNull() to (0 until a.length()).map { i ->
            val s = a.getJSONObject(i)
            val d = s.getJSONObject("Driver")
            val teams = s.getJSONArray("Constructors")
            F1Standing(
                position = s.optString("position", "${i + 1}").toIntOrNull() ?: (i + 1),
                name = "${d.optString("givenName")} ${d.getString("familyName")}".trim(),
                code = d.optString("code").ifBlank { d.getString("familyName").take(3).uppercase() },
                team = if (teams.length() > 0) teams.getJSONObject(teams.length() - 1).getString("name") else "",
                points = s.getString("points"),
            )
        }
    }

    fun constructors(o: JSONObject): List<F1Standing> {
        val lists = o.getJSONObject("MRData").getJSONObject("StandingsTable").getJSONArray("StandingsLists")
        if (lists.length() == 0) return emptyList()
        val a = lists.getJSONObject(0).getJSONArray("ConstructorStandings")
        return (0 until a.length()).map { i ->
            val s = a.getJSONObject(i)
            val c = s.getJSONObject("Constructor")
            F1Standing(s.optString("position", "${i + 1}").toIntOrNull() ?: (i + 1), c.getString("name"), "", c.getString("name"), s.getString("points"))
        }
    }

    fun lastRace(o: JSONObject): F1Podium? {
        val races = o.getJSONObject("MRData").getJSONObject("RaceTable").getJSONArray("Races")
        if (races.length() == 0) return null
        val r = races.getJSONObject(0)
        val res = r.getJSONArray("Results")
        val top3 = (0 until minOf(3, res.length())).map { i ->
            val d = res.getJSONObject(i).getJSONObject("Driver")
            "${d.optString("givenName")} ${d.getString("familyName")}".trim()
        }
        return F1Podium(r.getString("raceName"), LocalDate.parse(r.getString("date")), top3)
    }
}
