package com.suze.aivoice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** OpenAI 兼容大模型客户端。API Key 只允许通过 HTTPS 发往用户明确配置的主机。 */
class LlmClient(private val prefs: Prefs) {
    private val local = LocalChatEngine(prefs)
    private val free = FreeChatClient()
    fun bindEmotion(e: EmotionEngine) { local.emotion = e }

    var systemPromptOverride: String? = null
    var extraSystemPrompt: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // 禁止 30x 跳转，避免 Authorization 被意外带往非预期端点或降级链路。
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** 严格限制凭证目标：仅 HTTPS，不允许 URL 用户信息、查询串或片段。 */
    private fun endpointUrl(): HttpUrl? {
        val base = prefs.baseUrl.trim().toHttpUrlOrNull() ?: return null
        if (!base.isHttps || base.username.isNotEmpty() || base.password.isNotEmpty()) return null
        if (base.query != null || base.fragment != null) return null
        return base.newBuilder()
            .addPathSegments("chat/completions")
            .build()
    }

    private fun apiKey(): String = prefs.apiKey.trim()
    private fun llmConfigured(): Boolean =
        apiKey().isNotEmpty() && endpointUrl() != null && prefs.model.isNotBlank()
    private fun freeChatEnabled(): Boolean = prefs.freeChatEnabled

    /** 限制历史数量与单条长度，降低隐私暴露、异常流量和意外高额 token 消耗。 */
    private fun safeHistory(history: List<ChatMessage>): List<ChatMessage> = history
        .filter { it.type != ChatMessage.TYPE_IMAGE && it.content.isNotBlank() }
        .takeLast(MAX_HISTORY_MESSAGES)
        .map { it.copy(content = it.content.take(MAX_MESSAGE_CHARS)) }

    private fun buildBody(history: List<ChatMessage>, stream: Boolean): String {
        val messages = JSONArray()
        var sys = systemPromptOverride?.takeIf { it.isNotBlank() } ?: prefs.systemPrompt
        extraSystemPrompt?.takeIf { it.isNotBlank() }?.let { sys += "\n" + it }
        if (sys.isNotBlank()) messages.put(JSONObject().put("role", "system").put("content", sys.take(MAX_SYSTEM_CHARS)))
        safeHistory(history).forEach { m ->
            messages.put(JSONObject().put("role", m.role).put("content", m.content))
        }
        return JSONObject()
            .put("model", prefs.model.take(MAX_MODEL_CHARS))
            .put("messages", messages)
            .put("temperature", 0.8)
            .put("stream", stream)
            .toString()
    }

    private fun buildRequest(stream: Boolean, body: String): Request {
        val endpoint = endpointUrl() ?: throw SecurityException("大模型接口必须是有效的 HTTPS 地址")
        val key = apiKey()
        if (key.isEmpty()) throw SecurityException("API Key 为空")
        return Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .header("Accept", if (stream) "text/event-stream" else "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun freeHistory(history: List<ChatMessage>): List<ChatMessage> {
        var sys = systemPromptOverride?.takeIf { it.isNotBlank() } ?: prefs.systemPrompt
        extraSystemPrompt?.takeIf { it.isNotBlank() }?.let { sys += "\n" + it }
        val clean = safeHistory(history)
        return if (sys.isBlank()) clean else listOf(ChatMessage("system", sys.take(MAX_SYSTEM_CHARS), isMe = false)) + clean
    }

    private fun localReply(lastUser: String): String = local.reply(lastUser)

    private fun ResponseBody.readUtf8Limited(maxBytes: Int): String? {
        if (contentLength() > maxBytes.toLong()) return null
        val output = ByteArrayOutputStream(minOf(maxBytes, 32 * 1024))
        val buffer = ByteArray(8192)
        byteStream().use { input ->
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) return null
                output.write(buffer, 0, read)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    suspend fun chat(history: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        val lastUser = history.lastOrNull { it.role == "user" }?.content.orEmpty()
        if (!llmConfigured()) {
            if (freeChatEnabled()) free.reply(freeHistory(history))?.takeIf { it.isNotBlank() }?.let { return@withContext it }
            return@withContext localReply(lastUser)
        }
        try {
            client.newCall(buildRequest(false, buildBody(history, false))).execute().use { resp ->
                // 已配置私有模型后，任何失败都只回本地，不再把对话转交公共免费服务。
                if (!resp.isSuccessful || resp.isRedirect) return@withContext localReply(lastUser)
                val body = resp.body ?: return@withContext localReply(lastUser)
                val text = body.readUtf8Limited(MAX_RESPONSE_BYTES) ?: return@withContext localReply(lastUser)
                val choices = JSONObject(text).optJSONArray("choices")
                choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                    ?.take(MAX_OUTPUT_CHARS)?.takeIf { it.isNotBlank() } ?: localReply(lastUser)
            }
        } catch (_: Exception) {
            localReply(lastUser)
        }
    }

    suspend fun chatStream(history: List<ChatMessage>, onDelta: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val sb = StringBuilder()
            val lastUser = history.lastOrNull { it.role == "user" }?.content.orEmpty()
            if (!llmConfigured()) {
                val online = if (freeChatEnabled()) free.reply(freeHistory(history)) else null
                val ans = online?.takeIf { it.isNotBlank() } ?: localReply(lastUser)
                onDelta(ans); return@withContext ans
            }
            try {
                client.newCall(buildRequest(true, buildBody(history, true))).execute().use { resp ->
                    if (!resp.isSuccessful || resp.isRedirect || resp.body == null) {
                        val ans = localReply(lastUser); onDelta(ans); return@withContext ans
                    }
                    val reader: BufferedReader = resp.body!!.source().inputStream().bufferedReader(Charsets.UTF_8)
                    while (sb.length < MAX_OUTPUT_CHARS) {
                        val line = reader.readLine() ?: break
                        if (line.length > MAX_SSE_LINE_CHARS || !line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload == "[DONE]") break
                        if (payload.isEmpty()) continue
                        runCatching {
                            JSONObject(payload).optJSONArray("choices")?.optJSONObject(0)
                                ?.optJSONObject("delta")?.optString("content")
                        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { delta ->
                            val safe = delta.take(MAX_OUTPUT_CHARS - sb.length)
                            sb.append(safe); onDelta(safe)
                        }
                    }
                }
            } catch (_: Exception) {
                if (sb.isEmpty()) {
                    val ans = localReply(lastUser); onDelta(ans); return@withContext ans
                }
            }
            sb.toString()
        }

    companion object {
        private const val MAX_HISTORY_MESSAGES = 40
        private const val MAX_MESSAGE_CHARS = 12_000
        private const val MAX_SYSTEM_CHARS = 16_000
        private const val MAX_MODEL_CHARS = 200
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MAX_OUTPUT_CHARS = 200_000
        private const val MAX_SSE_LINE_CHARS = 256_000
    }
}
