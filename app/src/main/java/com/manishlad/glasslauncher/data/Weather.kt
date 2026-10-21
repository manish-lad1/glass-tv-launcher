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
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

private const val TAG = "GlassLauncher"

/** Location is fixed to Pune for now (editable from the phone later). */
object WeatherPlace {
    const val NAME = "Pune"
    const val LAT = 18.5204
    const val LON = 73.8567
    const val TZ = "Asia/Kolkata"
}

data class CurrentWeather(
    val time: LocalDateTime,
    val temp: Double,
    val feelsLike: Double,
    val humidity: Int,
    val windKmh: Double,
    val windDegrees: Int,
    val code: Int,
    val isDay: Boolean,
)

data class HourForecast(val time: LocalDateTime, val temp: Double, val code: Int, val isDay: Boolean, val rainChance: Int?)

data class DayForecast(
    val date: LocalDate,
    val code: Int,
    val min: Double,
    val max: Double,
    val rainChance: Int?,
    val sunrise: LocalDateTime,
    val sunset: LocalDateTime,
)

data class AirSample(val time: LocalDateTime, val pm25: Double?, val pm10: Double?)

data class WeatherData(
    val fetchedAt: Instant,
    val current: CurrentWeather,
    val hourly: List<HourForecast>,
    val daily: List<DayForecast>,
    val air: List<AirSample>,
) {
    /** "Now" plus the next 11 hours, from whatever is cached. */
    fun next12(now: LocalDateTime): List<HourForecast> {
        val hour = now.truncatedTo(ChronoUnit.HOURS)
        return hourly.filter { !it.time.isBefore(hour) }.take(12)
    }

    fun today(now: LocalDateTime) = daily.firstOrNull { it.date == now.toLocalDate() } ?: daily.firstOrNull()

    fun rainChanceNow(now: LocalDateTime) = hourly.firstOrNull { it.time == now.truncatedTo(ChronoUnit.HOURS) }?.rainChance

    fun indianAqi(now: LocalDateTime): IndianAqi.Reading? = IndianAqi.fromHourly(air, now)
}

data class WeatherState(
    val data: WeatherData? = null,
    /** True when the most recent refresh failed (offline, server error). Cached data is still shown. */
    val lastAttemptFailed: Boolean = false,
)

/**
 * Open-Meteo forecast + air quality (free, no key, CC BY 4.0). Raw responses are cached
 * in app storage so the widget never goes blank offline.
 */
class WeatherRepository(context: Context, private val http: OkHttpClient) {
    private val dir = File(context.filesDir, "weather").apply { mkdirs() }
    private val forecastFile = File(dir, "forecast.json")
    private val airFile = File(dir, "air.json")
    private val stampFile = File(dir, "fetched_at")

    private val _state = MutableStateFlow(WeatherState())
    val state: StateFlow<WeatherState> = _state
    private var lastAttempt: Instant = Instant.EPOCH

    suspend fun loadCache() = withContext(Dispatchers.IO) {
        runCatching { parseCached() }.onSuccess { _state.value = WeatherState(it) }
            .onFailure { Log.w(TAG, "weather cache unreadable", it) }
    }

    /** Fetch if the data is older than [maxAge]; after a failure, retry at most every 5 min. */
    suspend fun refreshIfStale(maxAge: Duration = Duration.ofMinutes(30)) {
        val now = Instant.now()
        val fetchedAt = _state.value.data?.fetchedAt ?: Instant.EPOCH
        if (Duration.between(fetchedAt, now) < maxAge) return
        if (Duration.between(lastAttempt, now) < Duration.ofMinutes(5)) return
        refresh()
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        lastAttempt = Instant.now()
        try {
            val forecast = get(forecastUrl())
            val air = runCatching { get(airUrl()) }.getOrNull() // AQI is optional; keep weather if it fails
            forecastFile.writeText(forecast)
            if (air != null) airFile.writeText(air)
            stampFile.writeText(Instant.now().toEpochMilli().toString())
            _state.value = WeatherState(parseCached())
            Log.i(TAG, "weather refreshed")
        } catch (e: Exception) {
            Log.w(TAG, "weather refresh failed: ${e.javaClass.simpleName}: ${e.message}")
            _state.value = _state.value.copy(lastAttemptFailed = true)
        }
    }

    private fun get(url: String): String =
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body!!.string()
        }

    private fun forecastUrl() = "https://api.open-meteo.com/v1/forecast" +
        "?latitude=${WeatherPlace.LAT}&longitude=${WeatherPlace.LON}&timezone=${WeatherPlace.TZ}" +
        "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,weather_code,wind_speed_10m,wind_direction_10m" +
        "&hourly=temperature_2m,weather_code,precipitation_probability,is_day" +
        "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,precipitation_probability_max" +
        "&forecast_days=7"

    private fun airUrl() = "https://air-quality-api.open-meteo.com/v1/air-quality" +
        "?latitude=${WeatherPlace.LAT}&longitude=${WeatherPlace.LON}&timezone=${WeatherPlace.TZ}" +
        "&hourly=pm10,pm2_5&past_days=1&forecast_days=1"

    private fun parseCached(): WeatherData? {
        if (!forecastFile.exists()) return null
        val f = JSONObject(forecastFile.readText())
        val c = f.getJSONObject("current")
        val current = CurrentWeather(
            time = LocalDateTime.parse(c.getString("time")),
            temp = c.getDouble("temperature_2m"),
            feelsLike = c.getDouble("apparent_temperature"),
            humidity = c.getInt("relative_humidity_2m"),
            windKmh = c.getDouble("wind_speed_10m"),
            windDegrees = c.optInt("wind_direction_10m"),
            code = c.getInt("weather_code"),
            isDay = c.getInt("is_day") == 1,
        )
        val h = f.getJSONObject("hourly")
        val hTime = h.getJSONArray("time")
        val hourly = (0 until hTime.length()).map { i ->
            HourForecast(
                time = LocalDateTime.parse(hTime.getString(i)),
                temp = h.getJSONArray("temperature_2m").getDouble(i),
                code = h.getJSONArray("weather_code").getInt(i),
                isDay = h.getJSONArray("is_day").getInt(i) == 1,
                rainChance = h.getJSONArray("precipitation_probability").optIntOrNull(i),
            )
        }
        val d = f.getJSONObject("daily")
        val dTime = d.getJSONArray("time")
        val daily = (0 until dTime.length()).map { i ->
            DayForecast(
                date = LocalDate.parse(dTime.getString(i)),
                code = d.getJSONArray("weather_code").getInt(i),
                min = d.getJSONArray("temperature_2m_min").getDouble(i),
                max = d.getJSONArray("temperature_2m_max").getDouble(i),
                rainChance = d.getJSONArray("precipitation_probability_max").optIntOrNull(i),
                sunrise = LocalDateTime.parse(d.getJSONArray("sunrise").getString(i)),
                sunset = LocalDateTime.parse(d.getJSONArray("sunset").getString(i)),
            )
        }
        val air = if (airFile.exists()) {
            val a = JSONObject(airFile.readText()).getJSONObject("hourly")
            val aTime = a.getJSONArray("time")
            (0 until aTime.length()).map { i ->
                AirSample(
                    LocalDateTime.parse(aTime.getString(i)),
                    a.getJSONArray("pm2_5").optDoubleOrNull(i),
                    a.getJSONArray("pm10").optDoubleOrNull(i),
                )
            }
        } else emptyList()
        val fetchedAt = stampFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
            ?.let(Instant::ofEpochMilli) ?: Instant.ofEpochMilli(forecastFile.lastModified())
        return WeatherData(fetchedAt, current, hourly, daily, air)
    }
}

private fun org.json.JSONArray.optIntOrNull(i: Int) = if (isNull(i)) null else optDouble(i).roundToInt()
private fun org.json.JSONArray.optDoubleOrNull(i: Int) = if (isNull(i)) null else optDouble(i)

/** WMO weather codes → words and an icon family. */
object WeatherCodes {
    enum class Kind { Clear, PartlyCloudy, Cloudy, Fog, Drizzle, Rain, Thunder, Snow }

    fun kind(code: Int) = when (code) {
        0, 1 -> Kind.Clear
        2 -> Kind.PartlyCloudy
        3 -> Kind.Cloudy
        45, 48 -> Kind.Fog
        in 51..57 -> Kind.Drizzle
        in 61..67, in 80..82 -> Kind.Rain
        in 71..77, 85, 86 -> Kind.Snow
        in 95..99 -> Kind.Thunder
        else -> Kind.Cloudy
    }

    fun label(code: Int, isDay: Boolean) = when (code) {
        0 -> if (isDay) "Sunny" else "Clear night"
        1 -> if (isDay) "Mostly sunny" else "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55, 56, 57 -> "Drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        in 71..77 -> "Snow"
        80 -> "Light showers"
        81 -> "Showers"
        82 -> "Heavy showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm, hail"
        else -> "—"
    }

    fun compass(degrees: Int): String =
        listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[((degrees % 360 + 22.5) / 45).toInt() % 8]
}

/**
 * Indian AQI (CPCB) from PM2.5 and PM10: 24-hour rolling means, sub-index by linear
 * interpolation within each band, AQI = max of the sub-indices. A model forecast, not a station reading.
 */
object IndianAqi {
    data class Reading(val value: Int, val category: String)

    private class Band(val cLo: Double, val cHi: Double, val iLo: Int, val iHi: Int)

    // Top band upper concentrations (380 / 510) are the conventional caps used by CPCB calculators.
    private val PM25 = listOf(
        Band(0.0, 30.0, 0, 50), Band(31.0, 60.0, 51, 100), Band(61.0, 90.0, 101, 200),
        Band(91.0, 120.0, 201, 300), Band(121.0, 250.0, 301, 400), Band(251.0, 380.0, 401, 500),
    )
    private val PM10 = listOf(
        Band(0.0, 50.0, 0, 50), Band(51.0, 100.0, 51, 100), Band(101.0, 250.0, 101, 200),
        Band(251.0, 350.0, 201, 300), Band(351.0, 430.0, 301, 400), Band(431.0, 510.0, 401, 500),
    )

    private fun subIndex(concentration: Double, bands: List<Band>): Int {
        val c = concentration.roundToInt().toDouble()
        val band = bands.firstOrNull { c <= it.cHi } ?: return 500
        val clamped = c.coerceAtLeast(band.cLo)
        return (band.iLo + (band.iHi - band.iLo) * (clamped - band.cLo) / (band.cHi - band.cLo)).roundToInt()
    }

    fun pm25SubIndex(c: Double) = subIndex(c, PM25)
    fun pm10SubIndex(c: Double) = subIndex(c, PM10)

    fun category(aqi: Int) = when {
        aqi <= 50 -> "Good"
        aqi <= 100 -> "Satisfactory"
        aqi <= 200 -> "Moderate"
        aqi <= 300 -> "Poor"
        aqi <= 400 -> "Very Poor"
        else -> "Severe"
    }

    /** Needs at least 16 of the last 24 hourly values (CPCB's minimum) for each pollutant used. */
    fun fromHourly(samples: List<AirSample>, now: LocalDateTime): Reading? {
        val end = now.truncatedTo(ChronoUnit.HOURS)
        val window = samples.filter { it.time.isAfter(end.minusHours(24)) && !it.time.isAfter(end) }
        fun mean(values: List<Double>) = if (values.size >= 16) values.average() else null
        val pm25 = mean(window.mapNotNull { it.pm25 })
        val pm10 = mean(window.mapNotNull { it.pm10 })
        val subs = listOfNotNull(pm25?.let(::pm25SubIndex), pm10?.let(::pm10SubIndex))
        if (subs.isEmpty()) return null
        val aqi = subs.max()
        return Reading(aqi, category(aqi))
    }
}
