package com.suze.aivoice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 硅基流动声音复刻：上传参考音频拿到 speech: URI，再交给克隆引擎合成。
 * 小沫可以变成主人指定的声音。
 */
class VoiceCloneClient(private val prefs: Prefs) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .build()

    data class RemoteVoice(val uri: String, val name: String)

    suspend fun upload(file: File, customName: String, transcript: String): Result<String> =
        withContext(Dispatchers.IO) {
            val key = prefs.siliconflowKey
            if (key.isBlank()) {
                return@withContext Result.failure(IllegalStateException(NEED_KEY))
            }
            if (!file.isFile || file.length() <= 0L) {
                return@withContext Result.failure(IllegalStateException("没有可用的参考音频"))
            }
            val text = transcript.trim()
            if (text.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("请填写录音里说的原文"))
            }
            val apiName = sanitizeName(customName)
            val mime = mimeOf(file)
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", MODEL)
                .addFormDataPart("customName", apiName)
                .addFormDataPart("text", text)
                .addFormDataPart("file", file.name, file.asRequestBody(mime.toMediaType()))
                .build()
            val req = Request.Builder()
                .url(UPLOAD_URL)
                .header("Authorization", "Bearer " + key)
                .post(body)
                .build()
            runCatching {
                client.newCall(req).execute().use { res ->
                    val raw = res.body?.string().orEmpty()
                    if (!res.isSuccessful) throw IllegalStateException(errorMessage(res.code, raw))
                    val uri = JSONObject(raw).optString("uri").trim()
                    if (!uri.startsWith("speech:")) {
                        throw IllegalStateException("接口没有返回音色 ID")
                    }
                    uri
                }
            }
        }

    suspend fun list(): Result<List<RemoteVoice>> = withContext(Dispatchers.IO) {
        val key = prefs.siliconflowKey
        if (key.isBlank()) return@withContext Result.failure(IllegalStateException(NEED_KEY))
        val req = Request.Builder()
            .url(LIST_URL)
            .header("Authorization", "Bearer " + key)
            .get()
            .build()
        runCatching {
            client.newCall(req).execute().use { res ->
                val raw = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw IllegalStateException(errorMessage(res.code, raw))
                parseVoiceList(raw)
            }
        }
    }

    suspend fun delete(uri: String): Result<Unit> = withContext(Dispatchers.IO) {
        val key = prefs.siliconflowKey
        if (key.isBlank()) return@withContext Result.failure(IllegalStateException(NEED_KEY))
        val target = uri.trim()
        if (!target.startsWith("speech:")) {
            return@withContext Result.failure(IllegalStateException("没有可删除的克隆音色"))
        }
        val json = JSONObject().put("uri", target).toString()
        val req = Request.Builder()
            .url(DELETE_URL)
            .header("Authorization", "Bearer " + key)
            .header("Content-Type", "application/json")
            .post(json.toRequestBody(JSON))
            .build()
        runCatching {
            client.newCall(req).execute().use { res ->
                val raw = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw IllegalStateException(errorMessage(res.code, raw))
            }
        }
    }

    companion object {
        const val MODEL = "FunAudioLLM/CosyVoice2-0.5B"
        const val NEED_KEY = "先在设置里填写硅基流动 Key，才能做声音克隆"
        private const val UPLOAD_URL = "https://api.siliconflow.cn/v1/uploads/audio/voice"
        private const val LIST_URL = "https://api.siliconflow.cn/v1/audio/voice/list"
        private const val DELETE_URL = "https://api.siliconflow.cn/v1/audio/voice/deletions"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun sanitizeName(raw: String): String {
            val kept = buildString {
                raw.trim().lowercase().forEach { c ->
                    if (c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_') append(c)
                }
            }.take(20)
            val stem = if (kept.length >= 2) kept else "xiaomo"
            return stem + "-" + (System.currentTimeMillis() % 1_000_000L).toString()
        }

        fun mimeOf(file: File): String {
            val name = file.name.lowercase()
            return when {
                name.endsWith(".mp3") || name.endsWith(".mpeg") -> "audio/mpeg"
                name.endsWith(".wav") -> "audio/wav"
                name.endsWith(".pcm") -> "audio/pcm"
                name.endsWith(".opus") || name.endsWith(".ogg") -> "audio/opus"
                else -> "audio/wav"
            }
        }

        fun errorMessage(code: Int, raw: String): String {
            val parsed = runCatching {
                val obj = JSONObject(raw)
                obj.optString("message").ifBlank { obj.optString("msg") }
            }.getOrDefault("")
            val detail = parsed.ifBlank { raw.take(2000) }
            return when {
                code == 401 -> "硅基流动 Key 无效，请回设置页检查"
                code == 403 -> if (detail.isBlank()) "需要硅基流动实名认证后才能克隆音色" else detail
                detail.isNotBlank() -> "克隆失败（$code）：$detail"
                else -> "克隆失败（HTTP $code）"
            }
        }

        fun parseVoiceList(raw: String): List<RemoteVoice> {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return emptyList()
            val out = ArrayList<RemoteVoice>()
            fun addObj(obj: JSONObject) {
                val uri = obj.optString("uri").ifBlank { obj.optString("voice") }
                if (!uri.startsWith("speech:")) return
                val name = obj.optString("customName").ifBlank {
                    obj.optString("name").ifBlank { uri.substringAfter("speech:").substringBefore(":") }
                }
                out.add(RemoteVoice(uri, name))
            }
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                for (i in 0 until arr.length()) {
                    val item = arr.opt(i)
                    if (item is JSONObject) addObj(item)
                    else if (item is String && item.startsWith("speech:")) {
                        out.add(RemoteVoice(item, item.substringAfter("speech:").substringBefore(":")))
                    }
                }
                return out
            }
            val obj = JSONObject(trimmed)
            addObj(obj)
            for (key in listOf("results", "data", "voices", "items")) {
                val arr = obj.optJSONArray(key) ?: continue
                for (i in 0 until arr.length()) {
                    val item = arr.opt(i)
                    if (item is JSONObject) addObj(item)
                    else if (item is String && item.startsWith("speech:")) {
                        out.add(RemoteVoice(item, item.substringAfter("speech:").substringBefore(":")))
                    }
                }
            }
            return out.distinctBy { it.uri }
        }
    }
}
