package com.example.weatherui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.weatherui.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val model: WeatherViewModel by viewModels()
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.rvHourly.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        binding.rvDaily.layoutManager = LinearLayoutManager(this)
        binding.btnSearch.setOnClickListener { search() }
        binding.etCity.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false
        }
        binding.btnRefresh.setOnClickListener { model.refresh() }
        binding.btnUnits.setOnClickListener { model.toggleUnits() }
        binding.tvAttribution.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://open-meteo.com/")))
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect { render(it) } }
        }
    }

    private fun search() {
        model.search(binding.etCity.text.toString())
    }

    private fun render(state: WeatherUiState) {
        binding.progress.isVisible = state.loading
        binding.btnSearch.isEnabled = !state.loading
        binding.btnRefresh.isEnabled = !state.loading
        binding.btnUnits.text = getString(if (state.unit == TemperatureUnit.CELSIUS) R.string.switch_fahrenheit else R.string.switch_celsius)
        binding.tvStatus.isVisible = state.loading || state.message != null
        binding.tvStatus.text = if (state.loading) getString(R.string.loading) else state.message
        binding.cityResults.removeAllViews()
        state.cities.forEach { city ->
            binding.cityResults.addView(Button(this).apply {
                text = city.label
                isAllCaps = false
                setOnClickListener {
                    binding.etCity.setText(city.name)
                    model.select(city)
                }
            })
        }
        val saved = state.weather
        binding.forecastContent.isVisible = saved != null
        if (saved == null) return
        val forecast = saved.forecast
        val current = forecast.current
        binding.tvLocation.text = saved.city.label
        binding.tvUpdated.text = getString(
            if (state.cached) R.string.cached_at else R.string.updated_at,
            WeatherFormat.time(saved.fetchedAt / 1000, forecast.timezone, "EEE d MMM, HH:mm"), forecast.timezone
        )
        binding.currentWeatherSection.tvTemp.text = state.unit.temperature(current.temperature)
        binding.currentWeatherSection.tvCondition.text = WeatherFormat.condition(current.code, current.isDay)
        binding.currentWeatherSection.tvFeelsLike.text = getString(R.string.feels_like, state.unit.temperature(current.feelsLike))
        binding.currentWeatherSection.ivWeather.setImageResource(weatherIcon(current.code, current.isDay))
        binding.weatherDetails.tvHumidityValue.text = WeatherFormat.percent(current.humidity)
        binding.weatherDetails.tvWindValue.text = state.unit.wind(current.wind)
        binding.weatherDetails.tvUvValue.text = forecast.daily.firstOrNull()?.uv?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "—"
        binding.tvForecastTime.text = getString(R.string.forecast_time, WeatherFormat.time(current.time, forecast.timezone, "EEE d MMM, HH:mm"))
        binding.rvHourly.adapter = HourAdapter(forecast.hourly, forecast.timezone, state.unit)
        binding.rvDaily.adapter = DayAdapter(forecast.daily, forecast.timezone, state.unit)
    }
}

private fun weatherIcon(code: Int?, isDay: Boolean = true): Int = when (code) {
    0, 1 -> if (isDay) R.drawable.sunny_24dp_1f1f1f_fill0_wght400_grad0_opsz24 else R.drawable.ic_night
    51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> R.drawable.ic_rain
    71, 73, 75, 77, 85, 86 -> R.drawable.ic_snow
    95, 96, 99 -> R.drawable.ic_storm
    else -> R.drawable.cloud_24dp_1f1f1f_fill0_wght400_grad0_opsz24
}

private class ForecastHolder(view: View) : RecyclerView.ViewHolder(view)

private class HourAdapter(private val hours: List<HourForecast>, private val timezone: String, private val unit: TemperatureUnit) : RecyclerView.Adapter<ForecastHolder>() {
    override fun getItemCount() = hours.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ForecastHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_hourly_forecast, parent, false))
    override fun onBindViewHolder(holder: ForecastHolder, position: Int) {
        val hour = hours[position]
        holder.itemView.apply {
            findViewById<TextView>(R.id.tvHourLabel).text = WeatherFormat.time(hour.time, timezone, "EEE HH:mm")
            findViewById<TextView>(R.id.tvHourTemp).text = unit.temperature(hour.temperature)
            findViewById<TextView>(R.id.tvHourRain).text = context.getString(R.string.rain_chance, WeatherFormat.percent(hour.rainChance))
            findViewById<ImageView>(R.id.ivHour).apply {
                setImageResource(weatherIcon(hour.code, hour.isDay))
                contentDescription = WeatherFormat.condition(hour.code, hour.isDay)
            }
        }
    }
}

private class DayAdapter(private val days: List<DayForecast>, private val timezone: String, private val unit: TemperatureUnit) : RecyclerView.Adapter<ForecastHolder>() {
    override fun getItemCount() = days.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ForecastHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_daily_forecast, parent, false))
    override fun onBindViewHolder(holder: ForecastHolder, position: Int) {
        val day = days[position]
        holder.itemView.apply {
            findViewById<TextView>(R.id.tvDayLabel).text = WeatherFormat.time(day.time, timezone, "EEE d MMM")
            findViewById<TextView>(R.id.tvDayLow).text = unit.temperature(day.low)
            findViewById<TextView>(R.id.tvDayHigh).text = unit.temperature(day.high)
            findViewById<TextView>(R.id.tvDayRain).text = context.getString(R.string.rain_chance, WeatherFormat.percent(day.rainChance))
            findViewById<ImageView>(R.id.ivDay).apply {
                setImageResource(weatherIcon(day.code))
                contentDescription = WeatherFormat.condition(day.code)
            }
        }
    }
}
