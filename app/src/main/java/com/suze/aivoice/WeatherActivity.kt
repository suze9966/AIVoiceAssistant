package com.suze.aivoice

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class WeatherActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var client: WeatherClient
    private lateinit var scene: WeatherSceneView
    private val city: String get() = intent.getStringExtra(EXTRA_CITY)?.trim().orEmpty().ifBlank { prefs.lastCity }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_weather)
        prefs = Prefs(this)
        client = WeatherClient(this, prefs)
        scene = findViewById(R.id.weatherScene)
        findViewById<View>(R.id.btnWeatherBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnWeatherRefresh).setOnClickListener { load() }
        pendingInfo?.let {
            pendingInfo = null
            render(it)
        } ?: load()
    }

    private fun load() {
        findViewById<TextView>(R.id.tvWeatherPlace).text = city
        findViewById<TextView>(R.id.tvWeatherUpdated).text = getString(R.string.weather_loading)
        findViewById<View>(R.id.btnWeatherRefresh).animate().rotationBy(360f).setDuration(700L).start()
        lifecycleScope.launch {
            val info = try {
                client.query(city)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            }
            if (isFinishing || isDestroyed) return@launch
            if (info == null) {
                val message = getString(R.string.weather_fail)
                findViewById<TextView>(R.id.tvWeatherUpdated).text = message
                findViewById<TextView>(R.id.tvWeatherMinutely).text = message
                Toast.makeText(this@WeatherActivity, message, Toast.LENGTH_LONG).show()
                return@launch
            }
            prefs.lastCity = city
            render(info)
        }
    }

    private fun render(info: WeatherInfo) {
        scene.setWeather(info.skycon)
        findViewById<TextView>(R.id.tvWeatherPlace).text = info.place
        findViewById<TextView>(R.id.tvWeatherUpdated).text = "${info.updatedAt} 更新 · ${info.source}"
        findViewById<TextView>(R.id.tvWeatherTemp).text = "${fmt(info.temp)}°"
        findViewById<TextView>(R.id.tvWeatherIcon).text = info.icon
        findViewById<TextView>(R.id.tvWeatherDesc).text = info.desc
        findViewById<TextView>(R.id.tvWeatherMinutely).text = info.minutely.ifBlank { info.comfort.ifBlank { "天气数据已更新" } }
        findViewById<TextView>(R.id.tvWeatherFeels).text = "体感 ${fmt(info.feelsLike)}°"
        findViewById<TextView>(R.id.tvWeatherHumidity).text = if (info.humidity >= 0) "湿度 ${info.humidity}%" else "湿度 —"
        findViewById<TextView>(R.id.tvWeatherAqi).text = if (info.aqi >= 0) "AQI ${info.aqi}" else "AQI —"
        renderHourly(info.hourly)
        renderDaily(info.days)
        findViewById<TextView>(R.id.tvWeatherAlert).apply {
            visibility = if (info.alert.isBlank()) View.GONE else View.VISIBLE
            text = if (info.alert.isBlank()) "" else "⚠️ ${info.alert}"
        }
    }

    private fun renderHourly(items: List<HourForecast>) {
        val row = findViewById<LinearLayout>(R.id.hourlyContainer)
        row.removeAllViews()
        if (items.isEmpty()) {
            row.addView(TextView(this).apply {
                text = "小米系统天气暂不提供逐小时明细"
                textSize = 13f; gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt())
                setPadding(dp(16), dp(14), dp(16), dp(14)); background = getDrawable(R.drawable.weather_chip)
            })
            return
        }
        items.forEach { item ->
            val rain = if (item.rainProbability >= 0) "\n💧${item.rainProbability}%" else ""
            row.addView(TextView(this).apply {
                text = "${item.time}\n${item.icon}\n${fmt(item.temp)}°$rain"
                textSize = 14f; gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt())
                setPadding(dp(15), dp(12), dp(15), dp(12)); background = getDrawable(R.drawable.weather_chip)
                layoutParams = LinearLayout.LayoutParams(dp(82), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
            })
        }
    }

    private fun renderDaily(items: List<DayForecast>) {
        val box = findViewById<LinearLayout>(R.id.dailyContainer)
        box.removeAllViews()
        items.forEachIndexed { index, item ->
            val label = when (index) { 0 -> "今天"; 1 -> "明天"; 2 -> "后天"; else -> dateLabel(item.date) }
            val rain = if (item.pop >= 0) "  💧${item.pop}%" else ""
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                addView(TextView(this@WeatherActivity).apply {
                    text = "$label   ${item.icon}  ${item.desc}$rain"
                    textSize = 14f; setTextColor(0xFFFFFFFF.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(this@WeatherActivity).apply {
                    text = "${fmt(item.tempMin)}° / ${fmt(item.tempMax)}°"
                    textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.END
                })
            })
        }
    }

    private fun fmt(v: Double) = if (v.isNaN()) "—" else String.format(Locale.CHINA, "%.0f", v)
    private fun dateLabel(date: String): String = runCatching {
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(date)
        SimpleDateFormat("M/d", Locale.CHINA).format(parsed!!)
    }.getOrDefault(date)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_CITY = "city"
        @Volatile var pendingInfo: WeatherInfo? = null
    }
}