package com.example.weatherui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Locale

class WeatherDataTest {
    private fun fixture(): JSONObject {
        val start = 1_800_000_000L // Exact UTC hour boundary.
        return JSONObject().put("timezone", "Africa/Johannesburg")
            .put("current", JSONObject().put("time", start + 1800).put("temperature_2m", 22.4)
                .put("apparent_temperature", 23.1).put("relative_humidity_2m", 55)
                .put("wind_speed_10m", 16.1).put("weather_code", 2).put("is_day", 1))
            .put("hourly", JSONObject().put("time", JSONArray((0..48).map { start + (it - 2) * 3600 }))
                .put("temperature_2m", JSONArray((0..48).map { it.toDouble() }))
                .put("weather_code", JSONArray((0..48).map { 3 }))
                .put("precipitation_probability", JSONArray((0..48).map { 30 }))
                .put("is_day", JSONArray((0..48).map { 1 })))
            .put("daily", JSONObject().put("time", JSONArray((0..6).map { start + it * 86400 }))
                .put("temperature_2m_min", JSONArray((0..6).map { 12 }))
                .put("temperature_2m_max", JSONArray((0..6).map { 25 }))
                .put("weather_code", JSONArray((0..6).map { 2 }))
                .put("uv_index_max", JSONArray((0..6).map { 4.5 }))
                .put("precipitation_probability_max", JSONArray((0..6).map { 60 })))
    }

    @Test fun forecastStartsAtCurrentHourAndContainsSevenDays() {
        val result = WeatherParser.forecast(fixture().toString())
        assertEquals(24, result.hourly.size)
        assertEquals(1_800_000_000L, result.hourly.first().time)
        assertEquals(2.0, result.hourly.first().temperature!!, 0.001)
        assertEquals(7, result.daily.size)
        assertEquals(22.4, result.current.temperature!!, 0.001)
        assertEquals("Africa/Johannesburg", result.timezone)
    }

    @Test fun missingMeasurementsAreUnknownRatherThanZero() {
        val json = fixture()
        json.getJSONObject("current").put("temperature_2m", JSONObject.NULL)
        json.getJSONObject("hourly").put("temperature_2m", JSONArray())
        json.getJSONObject("daily").remove("uv_index_max")
        val result = WeatherParser.forecast(json.toString())
        assertNull(result.current.temperature)
        assertNull(result.hourly.first().temperature)
        assertNull(result.daily.first().uv)
        assertEquals("—", TemperatureUnit.CELSIUS.temperature(result.current.temperature))
    }

    @Test fun fractionalTimezoneKeepsTheCurrentLocalHour() {
        val json = fixture().put("timezone", "Asia/Kolkata")
        json.getJSONObject("current").put("time", 1_800_000_900L)
        json.getJSONObject("hourly").put("time", JSONArray((0..48).map { 1_799_998_200L + it * 3600 }))
        val result = WeatherParser.forecast(json.toString())
        assertEquals(1_799_998_200L, result.hourly.first().time)
        assertEquals(24, result.hourly.size)
    }

    @Test(expected = IllegalArgumentException::class) fun emptyForecastIsRejected() {
        val json = fixture()
        json.getJSONObject("hourly").put("time", JSONArray())
        WeatherParser.forecast(json.toString())
    }

    @Test(expected = IOException::class) fun apiErrorsDoNotBecomeWeather() {
        WeatherParser.forecast("""{"error":true,"reason":"Invalid request"}""")
    }

    @Test fun noGeocodingResultsAreEmpty() {
        assertTrue(WeatherParser.cities("{}").isEmpty())
    }

    @Test fun invalidCitiesAreSkippedAndCoordinatesAreDeduplicated() {
        val valid = City.JOHANNESBURG.toJson()
        val cities = JSONObject().put("results", JSONArray().put(JSONObject().put("name", "Broken"))
            .put(valid).put(valid).put(City.JOHANNESBURG.toJson().put("latitude", 100)))
        val result = WeatherParser.cities(cities.toString())
        assertEquals(listOf(City.JOHANNESBURG), result)
    }

    @Test fun cityCacheRoundTrips() {
        assertEquals(City.JOHANNESBURG, City.fromJson(City.JOHANNESBURG.toJson()))
    }

    @Test fun temperatureAndWindConversionsAreConsistent() {
        assertEquals("32°F", TemperatureUnit.FAHRENHEIT.temperature(0.0))
        assertEquals("-40°F", TemperatureUnit.FAHRENHEIT.temperature(-40.0))
        assertEquals("22°C", TemperatureUnit.CELSIUS.temperature(22.4))
        assertEquals("10 mph", TemperatureUnit.FAHRENHEIT.wind(16.09344))
        assertEquals("16 km/h", TemperatureUnit.CELSIUS.wind(16.09344))
        assertEquals("—", TemperatureUnit.CELSIUS.temperature(Double.NaN))
    }

    @Test fun weatherCodesCoverRainSnowAndThunderstorms() {
        assertEquals("Freezing rain", WeatherFormat.condition(67))
        assertEquals("Snow showers", WeatherFormat.condition(86))
        assertEquals("Thunderstorm with hail", WeatherFormat.condition(99))
        assertEquals("Clear night", WeatherFormat.condition(0, false))
        assertEquals("Conditions unavailable", WeatherFormat.condition(null))
    }

    @Test fun timestampsUseTheSelectedLocationTimezone() {
        assertEquals("02:00", WeatherFormat.time(0, "Africa/Johannesburg", "HH:mm"))
        assertEquals("09:00", WeatherFormat.time(0, "Asia/Tokyo", "HH:mm"))
    }

    @Test fun queriesAreTrimmedAndUrlEncoded() {
        var requested = ""
        WeatherRepository(WeatherHttp { requested = it; "{}" }).search("  São Paulo & city  ")
        assertTrue(requested.contains("name=S%C3%A3o+Paulo+%26+city&"))
    }

    @Test(expected = IllegalArgumentException::class) fun blankSearchDoesNotCallTheNetwork() {
        WeatherRepository(WeatherHttp { fail("Should not reach the network"); "{}" }).search(" ")
    }

    @Test fun forecastUrlUsesFixedMetricUnitsAndAbsoluteTimestamps() {
        var requested = ""
        val oldLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            WeatherRepository(WeatherHttp { requested = it; fixture().toString() }).fetch(City.JOHANNESBURG)
        } finally { Locale.setDefault(oldLocale) }
        assertTrue(requested.contains("latitude=-26.20227"))
        assertTrue(requested.contains("timeformat=unixtime"))
        assertTrue(requested.contains("temperature_unit=celsius"))
        assertTrue(requested.contains("forecast_days=7"))
    }
}
