package com.suze.aivoice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 天气客户端：基于 Open-Meteo（开源天气 App 的主流选型）。
 *
 * 特点：完全免费、无需 API Key、无需注册，与项目的「零成本」风格一致。
 * 流程：城市名 --地理编码--> 经纬度 --forecast--> 当前天气 + 未来几天预报。
 *
 * 返回结构见 [WeatherInfo]。
 */
class WeatherClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val geoUrl = "https://geocoding-api.open-meteo.com/v1/search"
    private val forecastUrl = "https://api.open-meteo.com/v1/forecast"

    /**
     * 查询某城市天气。
     * @param city 城市名（中文或英文均可，如「北京」「上海」「Tokyo」）
     * @return 成功返回 [WeatherInfo]，失败返回 null
     */
    suspend fun query(city: String): WeatherInfo? = withContext(Dispatchers.IO) {
        try {
            val keyword = city.trim().ifBlank { return@withContext null }
            // 1) 地理编码
            val geoReq = Request.Builder()
                .url(geoUrl + "?name=" + URLEncoder.encode(keyword, "UTF-8") +
                    "&count=1&language=zh&format=json")
                .get()
                .build()
            val geoText = client.newCall(geoReq).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.string() ?: return@withContext null
            }
            val results = JSONObject(geoText).optJSONArray("results") ?: return@withContext null
            if (results.length() == 0) return@withContext null
            val first = results.getJSONObject(0)
            val lat = first.optDouble("latitude", Double.NaN)
            val lon = first.optDouble("longitude", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) return@withContext null
            val name = first.optString("name").ifBlank { keyword }
            val admin = first.optString("admin1")
            val country = first.optString("country")
            val place = listOf(name, admin, country)
                .filter { it.isNotBlank() && it != "null" }
                .distinct()
                .joinToString(" ")

            // 2) 拉取天气
            val wxUrl = forecastUrl +
                "?latitude=" + lat + "&longitude=" + lon +
                "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=auto&forecast_days=5&language=zh"
            val wxReq = Request.Builder().url(wxUrl).get().build()
            val wxText = client.newCall(wxReq).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.string() ?: return@withContext null
            }
            val root = JSONObject(wxText)
            val cur = root.optJSONObject("current") ?: return@withContext null
            val curTemp = cur.optDouble("temperature_2m", Double.NaN)
            if (curTemp.isNaN()) return@withContext null
            val curCode = cur.optInt("weather_code", 0)
            val feels = cur.optDouble("apparent_temperature", Double.NaN)
            val humidity = cur.optInt("relative_humidity_2m", -1)
            val wind = cur.optDouble("wind_speed_10m", Double.NaN)

            // 3) 逐日预报
            val days = ArrayList<DayForecast>()
            val daily = root.optJSONObject("daily")
            if (daily != null) {
                val times = daily.optJSONArray("time")
                val codes = daily.optJSONArray("weather_code")
                val maxs = daily.optJSONArray("temperature_2m_max")
                val mins = daily.optJSONArray("temperature_2m_min")
                val pops = daily.optJSONArray("precipitation_probability_max")
                val n = times?.length() ?: 0
                for (i in 0 until n) {
                    val dCode = codes?.optInt(i, 0) ?: 0
                    val dMax = maxs?.optDouble(i, Double.NaN) ?: Double.NaN
                    val dMin = mins?.optDouble(i, Double.NaN) ?: Double.NaN
                    val dPop = pops?.optInt(i, -1) ?: -1
                    days.add(
                        DayForecast(
                            date = times?.optString(i) ?: "",
                            code = dCode,
                            desc = wmoDesc(dCode),
                            icon = wmoEmoji(dCode),
                            tempMax = dMax,
                            tempMin = dMin,
                            pop = dPop
                        )
                    )
                }
            }

            WeatherInfo(
                place = place,
                temp = curTemp,
                feelsLike = feels,
                humidity = humidity,
                windSpeed = wind,
                code = curCode,
                desc = wmoDesc(curCode),
                icon = wmoEmoji(curCode),
                days = days
            )
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        /** WMO 天气码 → 中文描述（对齐 Open-Meteo 官方文档） */
        fun wmoDesc(code: Int): String = when (code) {
            0 -> "晴"
            1 -> "多云转晴"
            2 -> "多云"
            3 -> "阴"
            45, 48 -> "雾"
            51, 53, 55 -> "毛毛雨"
            56, 57 -> "冻毛毛雨"
            61 -> "小雨"
            63 -> "中雨"
            65 -> "大雨"
            66, 67 -> "冻雨"
            71 -> "小雪"
            73 -> "中雪"
            75 -> "大雪"
            77 -> "雪粒"
            80 -> "小阵雨"
            81 -> "中阵雨"
            82 -> "强阵雨"
            85, 86 -> "阵雪"
            95 -> "雷阵雨"
            96, 99 -> "雷阵雨伴冰雹"
            else -> "未知"
        }

        /** WMO 天气码 → emoji 图标（参考开源天气 App 的直观表达） */
        fun wmoEmoji(code: Int): String = when (code) {
            0 -> "☀️"
            1 -> "🌤️"
            2 -> "⛅"
            3 -> "☁️"
            45, 48 -> "🌫️"
            51, 53, 55, 56, 57 -> "🌦️"
            61, 63, 65, 66, 67 -> "🌧️"
            71, 73, 75, 77 -> "❄️"
            80, 81, 82 -> "🌧️"
            85, 86 -> "🌨️"
            95, 96, 99 -> "⛈️"
            else -> "🌡️"
        }
    }
}

/** 单日预报 */
data class DayForecast(
    val date: String,
    val code: Int,
    val desc: String,
    val icon: String,
    val tempMax: Double,
    val tempMin: Double,
    val pop: Int
)

/** 一次天气查询的完整结果 */
data class WeatherInfo(
    val place: String,
    val temp: Double,
    val feelsLike: Double,
    val humidity: Int,
    val windSpeed: Double,
    val code: Int,
    val desc: String,
    val icon: String,
    val days: List<DayForecast>
) {
    /** 生成一份适合语音播报 / 聊天气泡的纯文本（带 emoji） */
    fun toSpeakText(): String {
        val sb = StringBuilder()
        sb.append(icon).append(" ").append(place).append(" 现在 ").append(fmt(temp)).append("℃")
        sb.append("（").append(desc).append("）")
        sb.append("\n")
        sb.append("体感 ").append(fmt(feelsLike)).append("℃")
        if (humidity in 0..100) sb.append(" · 湿度 ").append(humidity).append("%")
        if (!windSpeed.isNaN()) sb.append(" · 风速 ").append(fmt(windSpeed)).append(" m/s")
        if (days.isNotEmpty()) {
            sb.append("\n\n未来几天：\n")
            days.take(5).forEachIndexed { idx, d ->
                val dayLabel = when (idx) {
                    0 -> "今天"
                    1 -> "明天"
                    2 -> "后天"
                    else -> shortDate(d.date)
                }
                sb.append(dayLabel).append(" ").append(d.icon).append(" ")
                    .append(fmt(d.tempMin)).append("~").append(fmt(d.tempMax)).append("℃ ")
                    .append(d.desc)
                if (d.pop in 0..100) sb.append(" 降水").append(d.pop).append("%")
                sb.append("\n")
            }
        }
        return sb.toString().trim()
    }

    private fun fmt(v: Double): String =
        if (v.isNaN()) "—" else String.format("%.1f", v).removeSuffix(".0")

    private fun shortDate(d: String): String {
        // "2026-09-30" -> "9/30"
        val parts = d.split("-")
        return if (parts.size == 3) {
            val m = parts[1].trimStart('0').ifBlank { "0" }
            val day = parts[2].trimStart('0').ifBlank { "0" }
            m + "/" + day
        } else d
    }
}