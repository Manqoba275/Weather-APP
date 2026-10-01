package com.example.weatherui

import android.app.Application
import android.widget.Button
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class WeatherDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("weather", 0)
    private val store = ViewModelStore()
    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    @Before fun resetPreferences() { prefs.edit().clear().commit() }
    @After fun cleanUp() { onMain { store.clear() }; prefs.edit().clear().commit() }

    private fun model(http: WeatherHttp): WeatherViewModel {
        lateinit var result: WeatherViewModel
        onMain { result = WeatherViewModel(app, WeatherRepository(http)); store.put("weather", result) }
        return result
    }

    private fun settled(model: WeatherViewModel): WeatherUiState = runBlocking {
        withTimeout(5_000) { model.state.first { !it.loading } }
    }

    @Test fun failedRefreshKeepsCachedCityAndValues() {
        prefs.edit().putString("city", City.JOHANNESBURG.toJson().toString())
            .putString("forecast", fixture).putLong("fetched_at", 123_000).commit()
        val model = model(WeatherHttp { throw IOException("Offline") })
        val state = settled(model)
        assertTrue(state.cached)
        assertEquals(City.JOHANNESBURG, state.weather!!.city)
        assertEquals(22.0, state.weather!!.forecast.current.temperature!!, 0.01)
        assertEquals(123_000L, state.weather!!.fetchedAt)
        assertTrue(state.message!!.contains("last saved forecast"))
        onMain { model.select(City("Tokyo", "Japan", "", 35.68, 139.69)) }
        val failedSelection = settled(model)
        assertEquals(City.JOHANNESBURG, failedSelection.weather!!.city)
        assertTrue(failedSelection.message!!.contains("Tokyo"))
    }

    @Test fun successfulRefreshPersistsTheSelectedCity() {
        val model = model(WeatherHttp { fixture })
        settled(model)
        val city = City("Cape Town", "South Africa", "Western Cape", -33.92, 18.42)
        onMain { model.select(city) }
        val result = settled(model)
        assertFalse(result.cached)
        assertNull(result.message)
        assertEquals(city, result.weather!!.city)
        assertEquals(city.toJson().toString(), prefs.getString("city", null))
        onMain { model.toggleUnits() }
        assertTrue(prefs.getBoolean("fahrenheit", false))
    }

    @Test fun corruptedCacheAndNetworkFailureProduceAnEmptyErrorState() {
        prefs.edit().putString("city", "broken json").putString("forecast", "{}").commit()
        val model = model(WeatherHttp { throw IOException("Offline") })
        val result = settled(model)
        assertNull(result.weather)
        assertNotNull(result.message)
        onMain { model.search(" ") }
        assertTrue(model.state.value.message!!.contains("2 and 100"))
    }

    @Test fun activityUnitControlSurvivesRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val units = activity.findViewById<Button>(R.id.btnUnits)
                assertEquals(activity.getString(R.string.switch_fahrenheit), units.text.toString())
                units.performClick()
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(activity.getString(R.string.switch_celsius), activity.findViewById<Button>(R.id.btnUnits).text.toString())
            }
        }
    }

    private val fixture = """{
      "timezone":"Africa/Johannesburg",
      "current":{"time":1800000000,"temperature_2m":22,"weather_code":0,"is_day":1},
      "hourly":{"time":[1800000000],"temperature_2m":[22],"weather_code":[0],"is_day":[1]},
      "daily":{"time":[1799964000],"temperature_2m_min":[12],"temperature_2m_max":[25],"weather_code":[0]}
    }"""
}
