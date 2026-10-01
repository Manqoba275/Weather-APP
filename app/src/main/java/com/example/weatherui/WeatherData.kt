package com.example.weatherui

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

data class City(val name: String, val country: String, val region: String, val latitude: Double, val longitude: Double) {
    val label: String get() = listOf(name, region, country).filter { it.isNotBlank() }.distinct().joinToString(", ")
    fun toJson(): JSONObject = JSONObject().put("name", name).put("country", country)
        .put("region", region).put("latitude", latitude).put("longitude", longitude)

    companion object {
        val JOHANNESBURG = City("Johannesburg", "South Africa", "Gauteng", -26.20227, 28.04363)
        fun fromJson(json: JSONObject): City {
            val lat = json.getDouble("latitude")
            val lon = json.getDouble("longitude")
            require(lat.isFinite() && lat in -90.0..90.0 && lon.isFinite() && lon in -180.0..180.0)
            val name = json.getString("name")
            require(name.isNotBlank())
            return City(name, json.optString("country", ""), json.optString("region", json.optString("admin1", "")), lat, lon)
        }
    }
}

data class CurrentWeather(val time: Long, val temperature: Double?, val feelsLike: Double?, val humidity: Double?, val wind: Double?, val code: Int?, val isDay: Boolean)
data class HourForecast(val time: Long, val temperature: Double?, val rainChance: Double?, val code: Int?, val isDay: Boolean)
data class DayForecast(val time: Long, val low: Double?, val high: Double?, val rainChance: Double?, val uv: Double?, val code: Int?)
data class Forecast(val current: CurrentWeather, val hourly: List<HourForecast>, val daily: List<DayForecast>, val timezone: String)
data class SavedWeather(val city: City, val forecast: Forecast, val fetchedAt: Long)

enum class TemperatureUnit { CELSIUS, FAHRENHEIT;
    fun temperature(celsius: Double?): String {
        if (celsius == null || !celsius.isFinite()) return "—"
        val value = if (this == FAHRENHEIT) celsius * 9 / 5 + 32 else celsius
        return "${value.roundToInt()}°${if (this == CELSIUS) "C" else "F"}"
    }
    fun wind(kmh: Double?): String {
        if (kmh == null || !kmh.isFinite()) return "—"
        return if (this == CELSIUS) "${kmh.roundToInt()} km/h" else "${(kmh / 1.609344).roundToInt()} mph"
    }
}

object WeatherFormat {
    fun percent(value: Double?): String = value?.let { "${it.roundToInt()}%" } ?: "—"
    fun time(seconds: Long, timezone: String, pattern: String): String = SimpleDateFormat(pattern, Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone(timezone)
    }.format(Date(seconds * 1000))
    fun condition(code: Int?, isDay: Boolean = true): String = when (code) {
        0 -> if (isDay) "Clear sky" else "Clear night"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61, 63, 65 -> "Rain"
        66, 67 -> "Freezing rain"
        71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Rain showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Conditions unavailable"
    }
}

object WeatherParser {
    fun cities(raw: String): List<City> {
        val root = JSONObject(raw)
        if (root.optBoolean("error")) throw IOException("Location service rejected the request.")
        val results = root.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { index ->
            runCatching { City.fromJson(results.getJSONObject(index)) }.getOrNull()
        }.distinctBy { Pair(it.latitude, it.longitude) }
    }

    fun forecast(raw: String): Forecast {
        val root = JSONObject(raw)
        if (root.optBoolean("error")) throw IOException("Weather service rejected the request.")
        val current = root.getJSONObject("current")
        val time = current.getLong("time")
        require(time > 0)
        val hourly = root.getJSONObject("hourly")
        val hourTimes = hourly.getJSONArray("time")
        val hours = (0 until hourTimes.length()).map { i ->
            HourForecast(hourTimes.getLong(i), hourly.number("temperature_2m", i), hourly.number("precipitation_probability", i), hourly.number("weather_code", i)?.toInt(), hourly.number("is_day", i) != 0.0)
        }.filter { it.time >= time - time % 3600 }.take(24)
        val daily = root.getJSONObject("daily")
        val dayTimes = daily.getJSONArray("time")
        val days = (0 until dayTimes.length()).take(7).map { i ->
            DayForecast(dayTimes.getLong(i), daily.number("temperature_2m_min", i), daily.number("temperature_2m_max", i), daily.number("precipitation_probability_max", i), daily.number("uv_index_max", i), daily.number("weather_code", i)?.toInt())
        }
        require(hours.isNotEmpty() && days.isNotEmpty()) { "Forecast contains no upcoming hours or days" }
        return Forecast(CurrentWeather(time, current.number("temperature_2m"), current.number("apparent_temperature"), current.number("relative_humidity_2m"), current.number("wind_speed_10m"), current.number("weather_code")?.toInt(), current.optInt("is_day", 1) == 1), hours, days, root.getString("timezone"))
    }

    private fun JSONObject.number(key: String): Double? = if (isNull(key)) null else optDouble(key).takeIf { it.isFinite() }
    private fun JSONObject.number(key: String, index: Int): Double? {
        val array: JSONArray = optJSONArray(key) ?: return null
        return if (index >= array.length() || array.isNull(index)) null else array.optDouble(index).takeIf { it.isFinite() }
    }
}

fun interface WeatherHttp { fun get(url: String): String }

class UrlWeatherHttp : WeatherHttp {
    override fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) throw IOException("Service returned HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

class WeatherRepository(private val http: WeatherHttp = UrlWeatherHttp()) {
    fun search(query: String): List<City> {
        val name = query.trim()
        require(name.length in 2..100) { "Enter a city name between 2 and 100 characters." }
        val encoded = URLEncoder.encode(name, "UTF-8")
        return WeatherParser.cities(http.get("https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=8&language=en&format=json"))
    }

    fun fetch(city: City): Pair<Forecast, String> {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${city.latitude}&longitude=${city.longitude}" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,weather_code,is_day" +
            "&hourly=temperature_2m,precipitation_probability,weather_code,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,uv_index_max" +
            "&timezone=auto&timeformat=unixtime&forecast_days=7&temperature_unit=celsius&wind_speed_unit=kmh"
        val raw = http.get(url)
        return WeatherParser.forecast(raw) to raw
    }
}
