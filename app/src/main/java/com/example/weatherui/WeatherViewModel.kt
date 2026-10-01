package com.example.weatherui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class WeatherUiState(
    val weather: SavedWeather? = null,
    val cities: List<City> = emptyList(),
    val loading: Boolean = false,
    val message: String? = null,
    val cached: Boolean = false,
    val unit: TemperatureUnit = TemperatureUnit.CELSIUS
)

class WeatherViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("weather", 0)
    private val repository = WeatherRepository()
    private var selectedCity = City.JOHANNESBURG
    private var request: Job? = null
    private var generation = 0
    private val mutableState = MutableStateFlow(WeatherUiState(unit = if (prefs.getBoolean("fahrenheit", false)) TemperatureUnit.FAHRENHEIT else TemperatureUnit.CELSIUS))
    val state = mutableState.asStateFlow()

    init {
        val saved = runCatching {
            val city = City.fromJson(JSONObject(prefs.getString("city", null) ?: return@runCatching null))
            val forecast = WeatherParser.forecast(prefs.getString("forecast", null) ?: return@runCatching null)
            SavedWeather(city, forecast, prefs.getLong("fetched_at", 0))
        }.getOrNull()
        if (saved != null) {
            selectedCity = saved.city
            mutableState.value = mutableState.value.copy(weather = saved, cached = true)
        }
        refresh()
    }

    fun search(query: String) {
        if (query.trim().length !in 2..100) {
            mutableState.value = mutableState.value.copy(message = "Enter a city name between 2 and 100 characters.")
            return
        }
        beginRequest { id ->
            try {
                val cities = withContext(Dispatchers.IO) { repository.search(query) }
                if (id == generation) mutableState.value = mutableState.value.copy(
                    loading = false, cities = cities,
                    message = if (cities.isEmpty()) "No matching cities. Try a nearby city or include the country." else "Choose a location below."
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (id == generation) mutableState.value = mutableState.value.copy(loading = false, message = "Could not search cities. Check your connection and try again.")
            }
        }
    }

    fun select(city: City) {
        selectedCity = city
        refresh()
    }

    fun refresh() {
        val city = selectedCity
        beginRequest { id ->
            try {
                val (forecast, raw) = withContext(Dispatchers.IO) { repository.fetch(city) }
                if (id != generation) return@beginRequest
                val saved = SavedWeather(city, forecast, System.currentTimeMillis())
                prefs.edit().putString("city", city.toJson().toString()).putString("forecast", raw)
                    .putLong("fetched_at", saved.fetchedAt).apply()
                mutableState.value = mutableState.value.copy(weather = saved, loading = false, cached = false, message = null)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (id == generation) mutableState.value = mutableState.value.copy(
                    loading = false, cached = mutableState.value.weather != null,
                    message = "Could not update ${city.name}. Check your connection and tap Refresh." +
                        if (mutableState.value.weather != null) " Showing the last saved forecast below." else ""
                )
            }
        }
    }

    private fun beginRequest(work: suspend (Int) -> Unit) {
        request?.cancel()
        val id = ++generation
        mutableState.value = mutableState.value.copy(loading = true, cities = emptyList(), message = null)
        request = viewModelScope.launch { work(id) }
    }

    fun toggleUnits() {
        val unit = if (mutableState.value.unit == TemperatureUnit.CELSIUS) TemperatureUnit.FAHRENHEIT else TemperatureUnit.CELSIUS
        prefs.edit().putBoolean("fahrenheit", unit == TemperatureUnit.FAHRENHEIT).apply()
        mutableState.value = mutableState.value.copy(unit = unit)
    }
}
