package com.suze.aivoice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.util.concurrent.TimeUnit

/**
 * 通用大模型客户端：OpenAI 兼容接口 /chat/completions
 * 支持流式（SSE）输出，用于「打字机效果」。填 Base URL + Key + 模型名即可对接任意兼容服务。
 */
class LlmClient(private val prefs: Prefs) {

    /** 运行时覆盖系统提示词（台湾腔人设切换用） */
    var systemPromptOverride: String? = null

    /** 运行时附加提示（情感状态 / 独立思考 / 内心独白），会拼接在系统提示后 */
    var extraSystemPrompt: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun buildBody(history: List<ChatMessage>, stream: Boolean): String {
        val messages = JSONArray()
        var sys = systemPromptOverride?.takeIf { it.isNotBlank() } ?: prefs.systemPrompt
        val extra = extraSystemPrompt?.takeIf { it.isNotBlank() }
        if (extra != null) sys = sys + "\n" + extra
        if (sys.isNotBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", sys))
        }
        history.forEach { m ->
            if (m.content.isNotBlank()) {
                messages.put(JSONObject().put("role", m.role).put("content", m.content))
            }
        }
        return JSONObject()
            .put("model", prefs.model)
            .put("messages", messages)
            .put("temperature", 0.8)
            .put("stream", stream)
            .toString()
    }

    private fun buildRequest(stream: Boolean, body: String): Request =
        Request.Builder()
            .url(prefs.baseUrl.trimEnd('/') + "/chat/completions")
            .addHeader("Authorization", "Bearer " + prefs.apiKey)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", if (stream) "text/event-stream" else "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

    /** 非流式：一次性返回完整回复 */
    suspend fun chat(history: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        try {
            client.newCall(buildRequest(false, buildBody(history, false))).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@withContext "【请求失败 ${resp.code}】$text"
                val choices = JSONObject(text).optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    choices.getJSONObject(0).optJSONObject("message")?.optString("content") ?: "（无内容）"
                } else "（返回格式异常）$text"
            }
        } catch (e: Exception) {
            "【网络错误】${e.message}"
        }
    }

    /**
     * 流式：逐段回调增量文本。
     * @param onDelta 每收到一小段就回调一次（实现打字机效果）
     * @return 完整回复文本
     */
    suspend fun chatStream(
        history: List<ChatMessage>,
        onDelta: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        try {
            client.newCall(buildRequest(true, buildBody(history, true))).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string() ?: ""
                    val msg = "【请求失败 ${resp.code}】$err"
                    onDelta(msg)
                    return@withContext msg
                }
                val reader: BufferedReader = resp.body!!.source().inputStream()
                    .bufferedReader(Charsets.UTF_8)
                var line: String?
                while (true) {
                    line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    if (payload.isEmpty()) continue
                    try {
                        val delta = JSONObject(payload)
                            .optJSONArray("choices")
                            ?.takeIf { it.length() > 0 }
                            ?.getJSONObject(0)
                            ?.optJSONObject("delta")
                            ?.optString("content")
                        if (!delta.isNullOrEmpty()) {
                            sb.append(delta)
                            onDelta(delta)
                        }
                    } catch (_: Exception) { }
                }
            }
        } catch (e: Exception) {
            val msg = if (sb.isEmpty()) "【网络错误】${e.message}" else ""
            if (msg.isNotEmpty()) onDelta(msg)
            return@withContext if (sb.isEmpty()) msg else sb.toString()
        }
        sb.toString()
    }
}
