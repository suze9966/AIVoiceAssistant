package com.suze.aivoice

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** 免费联网检索：维基摘要优先，DuckDuckGo 兜底。不走付费搜索 SDK。 */
class SearchClient {
    /** 给大模型看的资料包：维基 + DuckDuckGo 摘要拼在一起。 */
    fun gatherNotes(query: String): String {
        val q = query.trim().take(500)
        if (q.isEmpty()) return ""
        val parts = ArrayList<String>(2)
        wiki(q)?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        ddg(q)?.takeIf { it.isNotBlank() && !parts.contains(it) }?.let { parts.add(it) }
        return parts.joinToString("\n").take(30000)
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun search(query: String): String {
        val q = query.trim().take(500)
        if (q.isEmpty()) return ""
        wiki(q)?.takeIf { it.isNotBlank() }?.let { return it }
        ddg(q)?.takeIf { it.isNotBlank() }?.let { return it }
        return ""
    }

    private fun wiki(q: String): String? {
        val encoded = URLEncoder.encode(q, "UTF-8")
        val url = "https://zh.wikipedia.org/api/rest_v1/page/summary/$encoded"
        val text = get(url) ?: return null
        return try {
            val o = JSONObject(text)
            val title = o.optString("title")
            val extract = o.optString("extract")
            if (extract.isBlank()) null
            else buildString {
                if (title.isNotBlank()) append(title).append("：")
                append(extract.take(4000))
            }
        } catch (_: Exception) { null }
    }

    private fun ddg(q: String): String? {
        val url = "https://api.duckduckgo.com/".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("q", q)
            ?.addQueryParameter("format", "json")
            ?.addQueryParameter("no_redirect", "1")
            ?.addQueryParameter("no_html", "1")
            ?.addQueryParameter("skip_disambig", "1")
            ?.build()
            ?: return null
        val text = get(url.toString()) ?: return null
        return try {
            val o = JSONObject(text)
            val abstract = o.optString("AbstractText")
            if (abstract.isNotBlank()) {
                val src = o.optString("Heading").ifBlank { q }
                return "$src：$abstract".take(6000)
            }
            val related = o.optJSONArray("RelatedTopics")
            val bits = mutableListOf<String>()
            if (related != null) {
                for (i in 0 until related.length()) {
                    if (bits.size >= 6) break
                    val item = related.optJSONObject(i) ?: continue
                    val t = item.optString("Text")
                    if (t.isNotBlank()) bits.add(t.take(2000))
                    val topics = item.optJSONArray("Topics")
                    if (topics != null) {
                        for (j in 0 until topics.length()) {
                            if (bits.size >= 6) break
                            val nested = topics.optJSONObject(j)?.optString("Text").orEmpty()
                            if (nested.isNotBlank()) bits.add(nested.take(2000))
                        }
                    }
                }
            }
            if (bits.isEmpty()) null else bits.joinToString("；").take(8000)
        } catch (_: Exception) { null }
    }

    private fun get(url: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "XiaomoAssistant/1.0")
                .header("Accept", "application/json")
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
