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

    /** 本地闲聊引擎：未配置大模型 / 网络失败时的兜底，保证离线也能聊天 */
    private val local = LocalChatEngine(prefs)

    /** 免费在线闲聊：联网但没接大模型时使用（无需 Key、不花钱） */
    private val free = FreeChatClient()

    /** 外部注入情绪引擎（本地引擎据此调整语气） */
    fun bindEmotion(e: EmotionEngine) { local.emotion = e }

    /** 是否已配置可用的大模型（有 Key 才认为可用） */
    private fun llmConfigured(): Boolean =
        prefs.apiKey.isNotBlank() && prefs.baseUrl.isNotBlank() && prefs.model.isNotBlank()

    /** 是否启用免费在线闲聊（默认开，可在设置里关） */
    private fun freeChatEnabled(): Boolean = prefs.freeChatEnabled

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
            // 图片表情的 content 是 URL，不应作为自然语言上下文发送给模型。
            if (m.type != ChatMessage.TYPE_IMAGE && m.content.isNotBlank()) {
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

    /** 免费聊天端点也带上当前人设，并排除图片 URL 消息。 */
    private fun freeHistory(history: List<ChatMessage>): List<ChatMessage> {
        var sys = systemPromptOverride?.takeIf { it.isNotBlank() } ?: prefs.systemPrompt
        extraSystemPrompt?.takeIf { it.isNotBlank() }?.let { sys += "\n" + it }
        val clean = history.filter { it.type != ChatMessage.TYPE_IMAGE && it.content.isNotBlank() }
        return if (sys.isBlank()) clean
        else listOf(ChatMessage("system", sys, isMe = false)) + clean
    }

    /** 非流式：一次性返回完整回复（未配大模型时走免费在线，再不行走本地引擎） */
    suspend fun chat(history: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        val lastUser = history.lastOrNull { it.role == "user" }?.content ?: ""
        // 未配置大模型：优先免费在线，失败再落本地规则引擎
        if (!llmConfigured()) {
            if (freeChatEnabled()) {
                val r = free.reply(freeHistory(history))
                if (!r.isNullOrBlank()) return@withContext r
            }
            return@withContext local.reply(lastUser)
        }
        try {
            client.newCall(buildRequest(false, buildBody(history, false))).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    // 请求失败：免费在线兜底，再不行落本地
                    if (freeChatEnabled()) {
                        val r = free.reply(freeHistory(history))
                        if (!r.isNullOrBlank()) return@withContext r
                    }
                    return@withContext local.reply(lastUser)
                }
                val choices = JSONObject(text).optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    choices.getJSONObject(0).optJSONObject("message")?.optString("content") ?: "（无内容）"
                } else {
                    if (freeChatEnabled()) {
                        val r = free.reply(freeHistory(history))
                        if (!r.isNullOrBlank()) return@withContext r
                    }
                    local.reply(lastUser)
                }
            }
        } catch (e: Exception) {
            // 网络异常：免费在线兜底，再不行落本地
            if (freeChatEnabled()) {
                val r = free.reply(freeHistory(history))
                if (!r.isNullOrBlank()) return@withContext r
            }
            local.reply(lastUser)
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
        val lastUser = history.lastOrNull { it.role == "user" }?.content ?: ""
        // 未配置大模型：优先免费在线（联网），失败再落本地规则引擎
        if (!llmConfigured()) {
            var r: String? = null
            if (freeChatEnabled()) r = free.reply(freeHistory(history))
            val ans = if (!r.isNullOrBlank()) r!! else local.reply(lastUser)
            onDelta(ans)
            return@withContext ans
        }
        try {
            client.newCall(buildRequest(true, buildBody(history, true))).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // 请求失败：免费在线兜底，再不行落本地
                    var r: String? = null
                    if (freeChatEnabled()) r = free.reply(freeHistory(history))
                    val ans = if (!r.isNullOrBlank()) r!! else local.reply(lastUser)
                    onDelta(ans)
                    return@withContext ans
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
            // 网络异常：若还没吐任何内容，用本地引擎兜底
            if (sb.isEmpty()) {
                val r = local.reply(lastUser)
                onDelta(r)
                return@withContext r
            }
        }
        sb.toString()
    }
}
