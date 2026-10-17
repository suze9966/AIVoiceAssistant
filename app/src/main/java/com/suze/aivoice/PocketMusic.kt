package com.suze.aivoice

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 小手机播放器的联网曲源。
 * 歌曲走公开试听，电台走公开音频流。只播能直接出声的地址。
 */
object PocketMusic {

    data class Track(
        val title: String,
        val artist: String,
        val url: String,
        val live: Boolean
    )

    fun search(wish: String): List<Track> {
        val q = wish.trim().ifBlank { "piano" }
        val songs = runCatching { searchSongs(q) }.getOrDefault(emptyList())
        if (songs.isNotEmpty()) return songs
        return runCatching { searchRadios(q) }.getOrDefault(emptyList())
    }

    private fun searchSongs(q: String): List<Track> {
        val encoded = URLEncoder.encode(q, "UTF-8")
        val out = mutableListOf<Track>()
        for (country in listOf("HK", "TW", "JP", "US")) {
            val body = get("https://itunes.apple.com/search?term=$encoded&entity=song&limit=8&country=$country")
            val arr = JSONObject(body).optJSONArray("results") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("previewUrl")
                val title = o.optString("trackName")
                if (!url.startsWith("https://") || title.isBlank()) continue
                val artist = o.optString("artistName").ifBlank { "未知" }
                if (out.any { it.url == url }) continue
                out.add(Track(title.take(40), artist.take(40), url, false))
                if (out.size >= 8) return out
            }
            if (out.isNotEmpty()) return out
        }
        return out
    }

    private fun searchRadios(q: String): List<Track> {
        val encoded = URLEncoder.encode(q, "UTF-8")
        val body = get(
            "https://de1.api.radio-browser.info/json/stations/search?name=$encoded&limit=12&hidebroken=true&order=votes&reverse=true"
        )
        val arr = JSONArray(body)
        val out = mutableListOf<Track>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url_resolved").ifBlank { o.optString("url") }
            if (!url.startsWith("https://")) continue
            val name = o.optString("name").ifBlank { "电台" }
            if (out.any { it.url == url }) continue
            out.add(Track(name.take(40), "电台", url, true))
            if (out.size >= 8) break
        }
        return out
    }

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 12000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Xiaomo/1.1")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code")
            return text
        } finally {
            conn.disconnect()
        }
    }
}
