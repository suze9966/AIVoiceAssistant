package com.suze.aivoice

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** 翻译 / 换算 / 世界时钟 / 新闻：免费接口，不引入付费 SDK。 */
class UtilityClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun translate(text: String, target: String): String {
        val q = text.trim().take(300)
        if (q.isEmpty()) return ""
        val lang = langCode(target)
        val url = "https://translate.googleapis.com/translate_a/single".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("client", "gtx")
            ?.addQueryParameter("sl", "auto")
            ?.addQueryParameter("tl", lang)
            ?.addQueryParameter("dt", "t")
            ?.addQueryParameter("q", q)
            ?.build()
            ?: return ""
        val body = get(url.toString()) ?: return ""
        return try {
            val arr = org.json.JSONArray(body)
            val lines = arr.optJSONArray(0) ?: return ""
            val sb = StringBuilder()
            for (i in 0 until lines.length()) {
                sb.append(lines.optJSONArray(i)?.optString(0).orEmpty())
            }
            val out = sb.toString().trim()
            if (out.isBlank()) "" else "译成" + langName(lang) + "：\n" + out.take(400)
        } catch (_: Exception) { "" }
    }

    fun convert(amount: Double, from: String, to: String): String {
        val src = currencyCode(from)
        val dst = currencyCode(to)
        if (src == dst) return formatMoney(amount, src) + " 就是 " + formatMoney(amount, dst)
        val url = "https://open.er-api.com/v6/latest/$src"
        val body = get(url) ?: return ""
        return try {
            val o = JSONObject(body)
            if (o.optString("result") != "success") return ""
            val rate = o.optJSONObject("rates")?.optDouble(dst, Double.NaN) ?: Double.NaN
            if (rate.isNaN() || rate <= 0) return ""
            val out = amount * rate
            formatMoney(amount, src) + " 约等于 " + formatMoney(out, dst)
        } catch (_: Exception) { "" }
    }

    fun worldClock(place: String): String {
        val zone = zoneOf(place)
        val fmt = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)
        fmt.timeZone = TimeZone.getTimeZone(zone.first)
        val now = fmt.format(Date())
        return place.ifBlank { zone.second } + "现在是 " + now + "（" + zone.second + "）"
    }

    fun news(): String {
        val feeds = listOf(
            "https://www.thepaper.cn/rss_news.aspx",
            "https://feeds.bbci.co.uk/zhongwen/simp/rss.xml"
        )
        for (url in feeds) {
            val xml = get(url) ?: continue
            val items = parseRss(xml)
            if (items.isNotEmpty()) {
                return items.take(3).mapIndexed { i, t -> (i + 1).toString() + ". " + t }.joinToString("\n")
            }
        }
        return ""
    }

    private fun parseRss(xml: String): List<String> {
        val out = mutableListOf<String>()
        return try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(xml.reader())
            var event = parser.eventType
            var inItem = false
            var title = ""
            while (event != XmlPullParser.END_DOCUMENT && out.size < 5) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name?.lowercase(Locale.ROOT).orEmpty()
                        if (name == "item" || name == "entry") {
                            inItem = true
                            title = ""
                        } else if (inItem && name == "title") {
                            title = parser.nextText().trim()
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val name = parser.name?.lowercase(Locale.ROOT).orEmpty()
                        if (name == "item" || name == "entry") {
                            if (title.isNotBlank()) out.add(title.take(80))
                            inItem = false
                        }
                    }
                }
                event = parser.next()
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    private fun langCode(raw: String): String {
        val t = raw.lowercase(Locale.CHINA)
        return when {
            "英" in t || "english" in t -> "en"
            "日" in t || "japanese" in t -> "ja"
            "韩" in t || "korean" in t -> "ko"
            "法" in t || "french" in t -> "fr"
            "德" in t || "german" in t -> "de"
            "西" in t || "spanish" in t -> "es"
            "俄" in t -> "ru"
            "繁" in t || "台湾" in t -> "zh-TW"
            "粤" in t -> "zh-TW"
            else -> "en"
        }
    }

    private fun langName(code: String): String = when (code) {
        "en" -> "英语"
        "ja" -> "日语"
        "ko" -> "韩语"
        "fr" -> "法语"
        "de" -> "德语"
        "es" -> "西班牙语"
        "ru" -> "俄语"
        "zh-TW" -> "繁体中文"
        else -> code
    }

    private fun currencyCode(raw: String): String {
        val t = raw.lowercase(Locale.CHINA)
        return when {
            "美元" in t || "美金" in t || t == "usd" || t.contains("$") -> "USD"
            "欧元" in t || t == "eur" -> "EUR"
            "日元" in t || t == "jpy" -> "JPY"
            "英镑" in t || t == "gbp" -> "GBP"
            "港币" in t || "港元" in t || t == "hkd" -> "HKD"
            "台币" in t || t == "twd" -> "TWD"
            "韩元" in t || t == "krw" -> "KRW"
            else -> "CNY"
        }
    }

    private fun formatMoney(v: Double, code: String): String {
        val n = if (code == "JPY" || code == "KRW") String.format(Locale.CHINA, "%.0f", v)
        else String.format(Locale.CHINA, "%.2f", v)
        val name = when (code) {
            "USD" -> "美元"
            "EUR" -> "欧元"
            "JPY" -> "日元"
            "GBP" -> "英镑"
            "HKD" -> "港币"
            "TWD" -> "台币"
            "KRW" -> "韩元"
            else -> "人民币"
        }
        return n + name
    }

    private fun zoneOf(place: String): Pair<String, String> {
        val t = place.trim()
        return when {
            t.contains("纽约") || t.contains("美国东") -> "America/New_York" to "纽约"
            t.contains("洛杉矶") || t.contains("旧金山") || t.contains("美西") -> "America/Los_Angeles" to "洛杉矶"
            t.contains("伦敦") || t.contains("英国") -> "Europe/London" to "伦敦"
            t.contains("巴黎") || t.contains("法国") -> "Europe/Paris" to "巴黎"
            t.contains("东京") || t.contains("日本") -> "Asia/Tokyo" to "东京"
            t.contains("首尔") || t.contains("韩国") -> "Asia/Seoul" to "首尔"
            t.contains("悉尼") || t.contains("澳洲") || t.contains("澳大利亚") -> "Australia/Sydney" to "悉尼"
            t.contains("新加坡") -> "Asia/Singapore" to "新加坡"
            t.contains("香港") -> "Asia/Hong_Kong" to "香港"
            t.contains("台北") || t.contains("台湾") -> "Asia/Taipei" to "台北"
            t.contains("莫斯科") -> "Europe/Moscow" to "莫斯科"
            t.contains("迪拜") -> "Asia/Dubai" to "迪拜"
            t.contains("北京") || t.contains("上海") || t.contains("中国") || t.isBlank() -> "Asia/Shanghai" to "北京"
            else -> "Asia/Shanghai" to t.ifBlank { "北京" }
        }
    }

    private fun get(url: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "XiaomoAssistant/1.0")
                .header("Accept", "*/*")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                if (body.length > 400_000) null else body
            }
        } catch (_: Exception) { null }
    }
}
