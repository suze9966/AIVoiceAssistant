package com.suze.aivoice

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 天气客户端：支持 Open-Meteo / wttr.in / 小米系统天气 / 彩云，可在设置中切换。 */
class WeatherClient(context: Context, private val prefs: Prefs) {
    private val appContext = context.applicationContext
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(22, TimeUnit.SECONDS)
        .build()

    suspend fun query(city: String): WeatherInfo? = withContext(Dispatchers.IO) {
        val requested = city.trim().ifBlank { prefs.lastCity }
        runCatching {
            when (prefs.weatherSource) {
                "openmeteo" -> queryOpenMeteo(requested)
                "wttr" -> queryWttr(requested)
                "xiaomi" -> queryXiaomi(requested)
                "caiyun" -> queryCaiyun(requested)
                else -> queryAuto(requested)
            }
        }.getOrNull()
    }

    private fun queryAuto(city: String): WeatherInfo? {
        queryOpenMeteo(city)?.let { return it }
        queryWttr(city)?.let { return it }
        queryXiaomi(city)?.let { return it }
        return queryCaiyun(city)
    }

    private fun queryCaiyun(city: String): WeatherInfo? {
        val location = geocode(city) ?: return null
        val json = requestCaiyun(location.longitude, location.latitude) ?: return null
        return parse(location, json)
    }

    private fun queryOpenMeteo(city: String): WeatherInfo? {
        val location = geocode(city) ?: return null
        val url = "https://api.open-meteo.com/v1/forecast?latitude=" + location.latitude +
            "&longitude=" + location.longitude +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,wind_direction_10m" +
            "&hourly=temperature_2m,weather_code,precipitation_probability" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=7"
        val root = getJson(Request.Builder().url(url).get().build()) ?: return null
        return parseOpenMeteo(location, root)
    }

    private fun queryWttr(city: String): WeatherInfo? {
        val url = "https://wttr.in/" + URLEncoder.encode(city, "UTF-8") + "?format=j1&lang=zh"
        val root = getJson(
            Request.Builder().url(url)
                .header("User-Agent", "curl/8.0")
                .header("Accept", "application/json")
                .get().build()
        ) ?: return null
        return parseWttr(city, root)
    }

    /**
     * 小米主题官方公开的本机天气 ContentProvider。
     * URI /1/1 表示：优先本地缓存，超过一小时由系统天气更新；城市名只返回城市级名称。
     * Provider 不支持指定任意城市，因此仅在请求城市与系统天气城市一致时采用；
     * “当前位置/本地/当前城市”则直接使用系统天气当前城市。
     */
    private fun queryXiaomi(requestedCity: String): WeatherInfo? {
        val projection = arrayOf(
            "publish_time", "city_name", "description", "temperature", "aqilevel",
            "weather_type", "humidity", "wind", "day", "tmphighs", "tmplows",
            "forecast_type", "weathernamesfrom", "weathernamesto", "water"
        )
        val uri = Uri.parse("content://weather/actualWeatherData/1/1")
        return try {
            appContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val cityName = cursor.text("city_name").ifBlank { return@use null }
                val request = requestedCity.trim()
                val generic = request.isBlank() || request in listOf("当前位置", "本地", "当前城市", "我的位置")
                if (!generic && !sameCity(request, cityName)) return@use null
                val description = cursor.text("description").ifBlank { "天气变化" }
                val weatherType = cursor.intValue("weather_type", -1)
                val skycon = xiaomiSkycon(weatherType, description)
                val currentTemp = number(cursor.text("temperature"))
                if (currentTemp.isNaN()) return@use null
                val publishMillis = cursor.longValue("publish_time", 0L)
                val days = ArrayList<DayForecast>()
                do {
                    val index = cursor.intValue("day", days.size + 1).coerceAtLeast(1) - 1
                    val fromName = cursor.text("weathernamesfrom")
                    val toName = cursor.text("weathernamesto")
                    val dayDesc = listOf(fromName, toName).filter { it.isNotBlank() }.distinct()
                        .joinToString("转").ifBlank { description }
                    val forecastType = cursor.intValue("forecast_type", weatherType)
                    val daySky = xiaomiSkycon(forecastType, dayDesc)
                    days += DayForecast(
                        date = relativeDate(index), skycon = daySky, desc = dayDesc,
                        icon = skyconEmoji(daySky), tempMax = number(cursor.text("tmphighs")),
                        tempMin = number(cursor.text("tmplows")), pop = number(cursor.text("water")).toIntOrMinusOne()
                    )
                    } while (cursor.moveToNext() && days.size < 5)
                WeatherInfo(
                    place = cityName, temp = currentTemp, feelsLike = currentTemp,
                    humidity = cursor.firstInt("humidity", -1), windSpeed = Double.NaN,
                    windDirection = Double.NaN, skycon = skycon, desc = description,
                    icon = skyconEmoji(skycon), aqi = cursor.firstInt("aqilevel", -1),
                    comfort = cursor.firstText("wind"),
                    minutely = "系统天气数据已同步",
                    alert = "", hourly = emptyList(), days = days.distinctBy { it.date },
                    source = "小米天气", updatedAt = if (publishMillis > 0L)
                        SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(normalizeEpochMillis(publishMillis)))
                    else SimpleDateFormat("HH:mm", Locale.CHINA).format(Date())
                )
            }?.let { info ->
                val hourly = runCatching { queryOpenMeteo(info.place)?.hourly }.getOrNull().orEmpty()
                if (hourly.isEmpty()) info
                else info.copy(hourly = hourly, minutely = "系统天气数据已同步 · 逐小时来自 Open-Meteo")
            }
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun Cursor.column(name: String): Int = getColumnIndex(name)
    private fun Cursor.text(name: String): String = column(name).takeIf { it >= 0 }?.let { getString(it) }.orEmpty()
    private fun Cursor.intValue(name: String, fallback: Int): Int = text(name).filter { it == '-' || it.isDigit() }.toIntOrNull() ?: fallback
    private fun Cursor.longValue(name: String, fallback: Long): Long = text(name).filter(Char::isDigit).toLongOrNull() ?: fallback
    private fun Cursor.firstText(name: String): String {
        val originalPosition = this.position
        return try { moveToFirst(); text(name) } finally { moveToPosition(originalPosition) }
    }
    private fun Cursor.firstInt(name: String, fallback: Int): Int {
        val originalPosition = this.position
        return try { moveToFirst(); intValue(name, fallback) } finally { moveToPosition(originalPosition) }
    }
    private fun number(value: String): Double = Regex("-?\\d+(?:\\.\\d+)?").find(value)?.value?.toDoubleOrNull() ?: Double.NaN
    private fun Double.toIntOrMinusOne(): Int = if (isNaN()) -1 else toInt().coerceIn(0, 100)
    /** MIUI 版本间 publish_time 有秒/毫秒两种形式，统一为毫秒。 */
    private fun normalizeEpochMillis(value: Long): Long = if (value in 1..9_999_999_999L) value * 1000L else value
    private fun sameCity(request: String, actual: String): Boolean {
        fun clean(v: String) = v.lowercase(Locale.CHINA).replace(Regex("[市区县省·\\s]"), "")
        val a = clean(request); val b = clean(actual)
        return a.isNotBlank() && b.isNotBlank() && (a.contains(b) || b.contains(a))
    }
    private fun relativeDate(offset: Int): String {
        val calendar = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, offset) }
        return SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(calendar.time)
    }
    private fun xiaomiSkycon(type: Int, desc: String): String {
        val text = desc.lowercase(Locale.CHINA)
        return when {
            "暴雨" in text || "雷" in text -> "STORM_RAIN"
            "大雨" in text -> "HEAVY_RAIN"
            "中雨" in text -> "MODERATE_RAIN"
            "雨" in text -> "LIGHT_RAIN"
            "暴雪" in text -> "STORM_SNOW"
            "大雪" in text -> "HEAVY_SNOW"
            "中雪" in text -> "MODERATE_SNOW"
            "雪" in text -> "LIGHT_SNOW"
            "雾" in text -> "FOG"
            "霾" in text -> "MODERATE_HAZE"
            "沙" in text || "尘" in text -> "SAND"
            "风" in text && type >= 18 -> "WIND"
            "阴" in text -> "CLOUDY"
            "多云" in text -> "PARTLY_CLOUDY_DAY"
            "晴" in text -> "CLEAR_DAY"
            else -> when (type) {
                0 -> "CLEAR_DAY"; 1, 2, 3 -> "PARTLY_CLOUDY_DAY"; 4, 5 -> "CLOUDY"
                in 6..12 -> "LIGHT_RAIN"; in 13..17 -> "LIGHT_SNOW"
                in 18..21 -> "FOG"; in 22..24 -> "SAND"; else -> "CLEAR_DAY"
            }
        }
    }

    private fun parseOpenMeteo(point: GeoPoint, root: JSONObject): WeatherInfo? {
        val current = root.optJSONObject("current") ?: return null
        val temp = current.optDouble("temperature_2m", Double.NaN)
        if (temp.isNaN()) return null
        val code = current.optInt("weather_code", 0)
        val skycon = wmoSkycon(code)
        val hourlyObj = root.optJSONObject("hourly")
        val times = hourlyObj?.optJSONArray("time")
        val temps = hourlyObj?.optJSONArray("temperature_2m")
        val codes = hourlyObj?.optJSONArray("weather_code")
        val pops = hourlyObj?.optJSONArray("precipitation_probability")
        val hourly = ArrayList<HourForecast>()
        if (times != null && temps != null) {
            val limit = minOf(times.length(), temps.length(), 24)
            for (i in 0 until limit) {
                val sky = wmoSkycon(codes?.optInt(i, 0) ?: 0)
                hourly += HourForecast(
                    hourLabel(times.optString(i)), temps.optDouble(i, Double.NaN), sky,
                    skyconDesc(sky), skyconEmoji(sky), pops?.optInt(i, -1) ?: -1
                )
            }
        }
        val dailyObj = root.optJSONObject("daily")
        val dates = dailyObj?.optJSONArray("time")
        val maxes = dailyObj?.optJSONArray("temperature_2m_max")
        val mins = dailyObj?.optJSONArray("temperature_2m_min")
        val dCodes = dailyObj?.optJSONArray("weather_code")
        val dPops = dailyObj?.optJSONArray("precipitation_probability_max")
        val days = ArrayList<DayForecast>()
        if (dates != null && maxes != null && mins != null) {
            val limit = minOf(dates.length(), maxes.length(), mins.length(), 7)
            for (i in 0 until limit) {
                val sky = wmoSkycon(dCodes?.optInt(i, 0) ?: 0)
                days += DayForecast(
                    dates.optString(i), sky, skyconDesc(sky), skyconEmoji(sky),
                    maxes.optDouble(i, Double.NaN), mins.optDouble(i, Double.NaN),
                    dPops?.optInt(i, -1) ?: -1
                )
            }
        }
        return WeatherInfo(
            place = point.name, temp = temp,
            feelsLike = current.optDouble("apparent_temperature", temp),
            humidity = current.optInt("relative_humidity_2m", -1),
            windSpeed = current.optDouble("wind_speed_10m", Double.NaN),
            windDirection = current.optDouble("wind_direction_10m", Double.NaN),
            skycon = skycon, desc = skyconDesc(skycon), icon = skyconEmoji(skycon), aqi = -1,
            comfort = "", minutely = "数据来自 Open-Meteo 开源气象", alert = "",
            hourly = hourly, days = days, source = "Open-Meteo",
            updatedAt = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date())
        )
    }

    private fun parseWttr(city: String, root: JSONObject): WeatherInfo? {
        val current = root.optJSONArray("current_condition")?.optJSONObject(0) ?: return null
        val temp = current.optString("temp_C").toDoubleOrNull() ?: return null
        val desc = current.optJSONArray("lang_zh")?.optJSONObject(0)?.optString("value")
            ?: current.optJSONArray("weatherDesc")?.optJSONObject(0)?.optString("value")
            ?: "天气变化"
        val skycon = xiaomiSkycon(-1, desc)
        val area = root.optJSONArray("nearest_area")?.optJSONObject(0)
        val place = area?.optJSONArray("areaName")?.optJSONObject(0)?.optString("value").orEmpty()
            .ifBlank { city }
        val hourly = ArrayList<HourForecast>()
        val todayHours = root.optJSONArray("weather")?.optJSONObject(0)?.optJSONArray("hourly")
        if (todayHours != null) {
            for (i in 0 until minOf(todayHours.length(), 8)) {
                val item = todayHours.optJSONObject(i) ?: continue
                val hourDesc = item.optJSONArray("lang_zh")?.optJSONObject(0)?.optString("value")
                    ?: item.optJSONArray("weatherDesc")?.optJSONObject(0)?.optString("value")
                    ?: desc
                val sky = xiaomiSkycon(-1, hourDesc)
                val timeCode = item.optString("time").padStart(4, '0')
                val label = if (timeCode.length >= 4) timeCode.substring(0, 2) + ":00" else timeCode
                hourly += HourForecast(
                    label, item.optString("tempC").toDoubleOrNull() ?: Double.NaN, sky,
                    hourDesc, skyconEmoji(sky), item.optString("chanceofrain").toIntOrNull() ?: -1
                )
            }
        }
        val days = ArrayList<DayForecast>()
        val weatherDays = root.optJSONArray("weather")
        if (weatherDays != null) {
            for (i in 0 until minOf(weatherDays.length(), 7)) {
                val item = weatherDays.optJSONObject(i) ?: continue
                val dayDesc = item.optJSONArray("hourly")?.optJSONObject(4)?.optJSONArray("lang_zh")
                    ?.optJSONObject(0)?.optString("value") ?: desc
                val sky = xiaomiSkycon(-1, dayDesc)
                days += DayForecast(
                    item.optString("date"), sky, dayDesc, skyconEmoji(sky),
                    item.optString("maxtempC").toDoubleOrNull() ?: Double.NaN,
                    item.optString("mintempC").toDoubleOrNull() ?: Double.NaN,
                    item.optJSONArray("hourly")?.optJSONObject(4)?.optString("chanceofrain")?.toIntOrNull() ?: -1
                )
            }
        }
        return WeatherInfo(
            place = place, temp = temp,
            feelsLike = current.optString("FeelsLikeC").toDoubleOrNull() ?: temp,
            humidity = current.optString("humidity").toIntOrNull() ?: -1,
            windSpeed = current.optString("windspeedKmph").toDoubleOrNull() ?: Double.NaN,
            windDirection = current.optString("winddirDegree").toDoubleOrNull() ?: Double.NaN,
            skycon = skycon, desc = desc, icon = skyconEmoji(skycon), aqi = -1,
            comfort = current.optString("winddir16Point"),
            minutely = "数据来自 wttr.in 免费天气", alert = "",
            hourly = hourly, days = days, source = "wttr.in",
            updatedAt = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date())
        )
    }

    private fun wmoSkycon(code: Int): String = when (code) {
        0 -> "CLEAR_DAY"
        1, 2 -> "PARTLY_CLOUDY_DAY"
        3 -> "CLOUDY"
        45, 48 -> "FOG"
        51, 53, 55, 56, 57, 61, 80 -> "LIGHT_RAIN"
        63, 81 -> "MODERATE_RAIN"
        65, 66, 67, 82 -> "HEAVY_RAIN"
        95, 96, 99 -> "STORM_RAIN"
        71, 77, 85 -> "LIGHT_SNOW"
        73 -> "MODERATE_SNOW"
        75, 86 -> "HEAVY_SNOW"
        else -> if (code in 70..79) "LIGHT_SNOW" else if (code in 50..69) "LIGHT_RAIN" else "CLEAR_DAY"
    }

    private fun geocode(city: String): GeoPoint? {
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=" +
            URLEncoder.encode(city, "UTF-8") + "&count=1&language=zh&format=json"
        val root = getJson(Request.Builder().url(url).get().build()) ?: return null
        val first = root.optJSONArray("results")?.optJSONObject(0) ?: return null
        val lat = first.optDouble("latitude", Double.NaN)
        val lon = first.optDouble("longitude", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return null
        val place = listOf(first.optString("name", city), first.optString("admin1"))
            .filter { it.isNotBlank() && it != "null" }.distinct().joinToString(" · ")
        return GeoPoint(place.ifBlank { city }, lon, lat)
    }

    private fun requestCaiyun(lon: Double, lat: Double): JSONObject? {
        val appKey = prefs.caiyunAppKey.trim()
        val secret = prefs.caiyunAppSecret.trim()
        val token = prefs.caiyunToken.trim()
        if (appKey.isBlank() && token.isBlank()) return null
        val credential = if (appKey.isNotBlank()) appKey else token
        val coordinate = String.format(Locale.US, "%.4f,%.4f", lon, lat)
        val path = "/v2.6/$credential/$coordinate/weather"
        val query = "alert=true&dailysteps=7&hourlysteps=24"
        val builder = Request.Builder().url("https://api.caiyunapp.com$path?$query").get()
        if (appKey.isNotBlank() && secret.isNotBlank()) {
            val nonce = UUID.randomUUID().toString()
            val timestamp = System.currentTimeMillis() / 1000L
            val content = "GET:$path:$query:$appKey:$nonce:$timestamp"
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val signature = Base64.encodeToString(mac.doFinal(content.toByteArray(Charsets.UTF_8)), Base64.URL_SAFE or Base64.NO_WRAP)
            builder.header("x-cy-nonce", nonce)
                .header("x-cy-timestamp", timestamp.toString())
                .header("x-cy-signature", signature)
        }
        return getJson(builder.build())
    }

    private fun getJson(request: Request): JSONObject? =
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.string()?.let(::JSONObject)
        }

    private fun parse(point: GeoPoint, root: JSONObject): WeatherInfo? {
        if (root.optString("status") != "ok") return null
        val result = root.optJSONObject("result") ?: return null
        val realtime = result.optJSONObject("realtime") ?: return null
        val skycon = realtime.optString("skycon", "CLEAR_DAY")
        val temp = realtime.optDouble("temperature", Double.NaN)
        if (temp.isNaN()) return null
        val apparent = result.optJSONObject("hourly")?.optJSONArray("apparent_temperature")
            ?.optJSONObject(0)?.optDouble("value", temp) ?: temp
        val humidityRaw = realtime.optDouble("humidity", Double.NaN)
        val humidity = if (humidityRaw.isNaN()) -1 else (humidityRaw * 100).toInt().coerceIn(0, 100)
        val wind = realtime.optJSONObject("wind")
        val windSpeed = wind?.optDouble("speed", Double.NaN) ?: Double.NaN
        val windDirection = wind?.optDouble("direction", Double.NaN) ?: Double.NaN
        val air = realtime.optJSONObject("air_quality")
        val aqi = air?.optJSONObject("aqi")?.optInt("chn", -1) ?: -1
        val comfort = realtime.optJSONObject("life_index")?.optJSONObject("comfort")?.optString("desc", "") ?: ""
        val minutely = result.optJSONObject("minutely")?.optString("description", "") ?: ""
        val alert = result.optJSONObject("alert")?.optJSONArray("content")?.optJSONObject(0)?.optString("description", "") ?: ""
        return WeatherInfo(
            place = point.name, temp = temp, feelsLike = apparent, humidity = humidity,
            windSpeed = windSpeed, windDirection = windDirection, skycon = skycon,
            desc = skyconDesc(skycon), icon = skyconEmoji(skycon), aqi = aqi,
            comfort = comfort, minutely = minutely, alert = alert,
            hourly = parseHourly(result.optJSONObject("hourly")), days = parseDaily(result.optJSONObject("daily")),
            source = "彩云天气", updatedAt = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date())
        )
    }

    private fun parseHourly(hourly: JSONObject?): List<HourForecast> {
        if (hourly == null) return emptyList()
        val temps = hourly.optJSONArray("temperature") ?: return emptyList()
        val skies = hourly.optJSONArray("skycon") ?: JSONArray()
        val rain = hourly.optJSONArray("precipitation") ?: JSONArray()
        return (0 until minOf(temps.length(), 24)).mapNotNull { i ->
            val t = temps.optJSONObject(i) ?: return@mapNotNull null
            val sky = skies.optJSONObject(i)?.optString("value", "CLEAR_DAY") ?: "CLEAR_DAY"
            val probability = rain.optJSONObject(i)?.optDouble("probability", Double.NaN) ?: Double.NaN
            HourForecast(hourLabel(t.optString("datetime")), t.optDouble("value", Double.NaN), sky,
                skyconDesc(sky), skyconEmoji(sky), if (probability.isNaN()) -1 else (probability * 100).toInt().coerceIn(0, 100))
        }
    }

    private fun parseDaily(daily: JSONObject?): List<DayForecast> {
        if (daily == null) return emptyList()
        val temps = daily.optJSONArray("temperature") ?: return emptyList()
        val skies = daily.optJSONArray("skycon") ?: JSONArray()
        val rain = daily.optJSONArray("precipitation") ?: JSONArray()
        return (0 until minOf(temps.length(), 7)).mapNotNull { i ->
            val t = temps.optJSONObject(i) ?: return@mapNotNull null
            val sky = skies.optJSONObject(i)?.optString("value", "CLEAR_DAY") ?: "CLEAR_DAY"
            val probability = rain.optJSONObject(i)?.optDouble("probability", Double.NaN) ?: Double.NaN
            DayForecast(t.optString("date"), sky, skyconDesc(sky), skyconEmoji(sky),
                t.optDouble("max", Double.NaN), t.optDouble("min", Double.NaN),
                if (probability.isNaN()) -1 else (probability * 100).toInt().coerceIn(0, 100))
        }
    }

    companion object {
        fun skyconDesc(code: String): String = when (code) {
            "CLEAR_DAY" -> "晴"; "CLEAR_NIGHT" -> "晴夜"; "PARTLY_CLOUDY_DAY" -> "多云"
            "PARTLY_CLOUDY_NIGHT" -> "夜间多云"; "CLOUDY" -> "阴"; "LIGHT_HAZE" -> "轻度雾霾"
            "MODERATE_HAZE" -> "中度雾霾"; "HEAVY_HAZE" -> "重度雾霾"; "LIGHT_RAIN" -> "小雨"
            "MODERATE_RAIN" -> "中雨"; "HEAVY_RAIN" -> "大雨"; "STORM_RAIN" -> "暴雨"
            "FOG" -> "雾"; "LIGHT_SNOW" -> "小雪"; "MODERATE_SNOW" -> "中雪"
            "HEAVY_SNOW" -> "大雪"; "STORM_SNOW" -> "暴雪"; "DUST" -> "浮尘"
            "SAND" -> "沙尘"; "WIND" -> "大风"; else -> "天气变化"
        }
        fun skyconEmoji(code: String): String = when (code) {
            "CLEAR_DAY" -> "☀️"; "CLEAR_NIGHT" -> "🌙"; "PARTLY_CLOUDY_DAY" -> "🌤️"
            "PARTLY_CLOUDY_NIGHT", "CLOUDY" -> "☁️"
            "LIGHT_RAIN", "MODERATE_RAIN" -> "🌧️"; "HEAVY_RAIN", "STORM_RAIN" -> "⛈️"
            "LIGHT_SNOW", "MODERATE_SNOW", "HEAVY_SNOW", "STORM_SNOW" -> "❄️"
            "FOG", "LIGHT_HAZE", "MODERATE_HAZE", "HEAVY_HAZE" -> "🌫️"
            "WIND" -> "💨"; "DUST", "SAND" -> "🏜️"; else -> "🌡️"
        }
        private fun hourLabel(value: String): String {
            val match = Regex("T(\\d{2}):(\\d{2})").find(value)
            return match?.groupValues?.get(1)?.plus(":00") ?: value.takeLast(5)
        }
    }
}

private data class GeoPoint(val name: String, val longitude: Double, val latitude: Double)
data class HourForecast(val time: String, val temp: Double, val skycon: String, val desc: String, val icon: String, val rainProbability: Int)
data class DayForecast(val date: String, val skycon: String, val desc: String, val icon: String, val tempMax: Double, val tempMin: Double, val pop: Int)
data class WeatherInfo(
    val place: String, val temp: Double, val feelsLike: Double, val humidity: Int,
    val windSpeed: Double, val windDirection: Double, val skycon: String, val desc: String,
    val icon: String, val aqi: Int, val comfort: String, val minutely: String, val alert: String,
    val hourly: List<HourForecast>, val days: List<DayForecast>, val source: String, val updatedAt: String
) {
    fun toSpeakText(): String {
        val details = mutableListOf("体感 ${fmt(feelsLike)}℃")
        if (humidity in 0..100) details += "湿度 $humidity%"
        if (!windSpeed.isNaN()) details += "风速 ${fmt(windSpeed)} km/h"
        if (aqi >= 0) details += "空气质量 $aqi"
        val next = days.drop(1).firstOrNull()?.let { "明天${it.desc}，${fmt(it.tempMin)}到${fmt(it.tempMax)}℃。" }.orEmpty()
        return "$icon $place 现在 ${fmt(temp)}℃，$desc。${details.joinToString("，")}。${minutely.ifBlank { comfort }}。$next"
    }
    private fun fmt(v: Double): String = if (v.isNaN()) "—" else String.format(Locale.CHINA, "%.0f", v)
}