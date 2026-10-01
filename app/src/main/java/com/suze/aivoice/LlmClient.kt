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
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

/** OpenAI 兼容大模型客户端。API Key 只允许通过 HTTPS 发往用户明确配置的主机。 */
class LlmClient(private val prefs: Prefs) {
    private val local = LocalChatEngine(prefs)
    private val free = FreeChatClient()
    fun bindEmotion(e: EmotionEngine) { local.emotion = e }

    var systemPromptOverride: String? = null
    var extraSystemPrompt: String? = null
    /** 角色聊天、主聊天认真提问都可以追加云端推理提示。 */
    var applyCloudThink: Boolean = false
    /** 角色聊天关闭后，未接模型或云端失败时不要用小沫本地闲聊顶替。 */
    var allowLocalFallback: Boolean = true
    /** 最近一次云端失败原因，给界面直说，不再假装陪聊。 */
    var lastCloudError: String? = null
        private set
    var toolHost: LlmToolHost? = null
    var toolsEnabled: Boolean = true
    var onToolStatus: ((String) -> Unit)? = null
    private var toolsUnsupported: Boolean = false

    fun isConfigured(): Boolean = llmConfigured()

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
        val path = base.encodedPath.trimEnd('/')
        val builder = base.newBuilder()
        if (!path.endsWith("/chat/completions")) {
            builder.addPathSegments("chat/completions")
        }
        return builder.build()
    }

    private fun apiKey(): String = prefs.apiKey.trim()
    private fun llmConfigured(): Boolean =
        apiKey().isNotEmpty() && endpointUrl() != null && prefs.model.isNotBlank()
    private fun freeChatEnabled(): Boolean = prefs.freeChatEnabled
    private fun toolsReady(): Boolean =
        toolsEnabled && !toolsUnsupported && toolHost != null && llmConfigured()

    /** 限制历史数量与单条长度，降低隐私暴露、异常流量和意外高额 token 消耗。 */
    private fun safeHistory(history: List<ChatMessage>): List<ChatMessage> = history
        .filter { it.type != ChatMessage.TYPE_IMAGE && it.content.isNotBlank() }
        .takeLast(MAX_HISTORY_MESSAGES)
        .map { it.copy(content = it.content.take(MAX_MESSAGE_CHARS)) }

    private fun composeSystem(): String {
        var sys = systemPromptOverride?.takeIf { it.isNotBlank() } ?: prefs.systemPrompt
        extraSystemPrompt?.takeIf { it.isNotBlank() }?.let { sys += "\n" + it }
        if (applyCloudThink && prefs.cloudThinkEnabled) sys += "\n" + CLOUD_THINK_PROMPT
        if (toolsReady()) sys += "\n" + TOOL_PROMPT
        return sys.take(MAX_SYSTEM_CHARS)
    }

    private fun buildBody(
        history: List<ChatMessage>,
        stream: Boolean,
        extraMessages: JSONArray? = null,
        withTools: Boolean = toolsReady()
    ): String {
        val messages = JSONArray()
        val sys = composeSystem()
        if (sys.isNotBlank()) messages.put(JSONObject().put("role", "system").put("content", sys))
        safeHistory(history).forEach { m ->
            messages.put(JSONObject().put("role", m.role).put("content", m.content))
        }
        if (extraMessages != null) {
            for (i in 0 until extraMessages.length()) {
                messages.put(extraMessages.optJSONObject(i) ?: continue)
            }
        }
        val body = JSONObject()
            .put("model", prefs.model.take(MAX_MODEL_CHARS))
            .put("messages", messages)
            .put("temperature", 0.8)
            .put("stream", stream)
        if (withTools) {
            body.put("tools", LlmTools.schema(prefs.webSearchEnabled))
            body.put("tool_choice", "auto")
        }
        return body.toString()
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
        val sys = composeSystem()
        val clean = safeHistory(history)
        return if (sys.isBlank()) clean else listOf(ChatMessage("system", sys, isMe = false)) + clean
    }

    /** Android org.json 会把 JSON null 读成字符串 "null"，流式思考分片不能往对白里拼。 */
    private fun jsonText(obj: JSONObject?, key: String): String {
        if (obj == null || obj.isNull(key)) return ""
        return obj.optString(key, "").takeIf { it.isNotEmpty() && it != "null" }.orEmpty()
    }

    private fun extractContent(message: JSONObject?): String {
        if (message == null) return ""
        return stripReasoning(jsonText(message, "content"))
    }

    private fun incrementalDelta(sb: StringBuilder, incoming: String): String {
        if (incoming.isEmpty()) return ""
        if (sb.isEmpty()) return incoming
        val current = sb.toString()
        if (incoming == current) return ""
        if (incoming.startsWith(current)) return incoming.substring(current.length)
        return incoming
    }

    private fun extractDelta(delta: JSONObject?): String {
        if (delta == null) return ""
        return jsonText(delta, "content")
    }

    fun stripReasoning(raw: String): String {
        var t = raw
        if (t.contains("<think>") && !t.contains("</think>")) {
            t = t.substringBefore("<think>")
        } else if (t.contains("<thinking>") && !t.contains("</thinking>")) {
            t = t.substringBefore("<thinking>")
        } else {
            t = THINK_BLOCK.replace(t, " ")
        }
        t = t.lines().filterNot { line ->
            val s = line.trim()
            s.startsWith("THOUGHT:") || s.startsWith("想法：") || s.startsWith("想法:") ||
                s.startsWith("<think>") || s.startsWith("</think>")
        }.joinToString("\n")
        return t.replace(Regex("[ \\t]{2,}"), " ").trim()
    }

    private fun localReply(history: List<ChatMessage>): String =
        if (allowLocalFallback) local.reply(history) else ""

    private fun cloudFail(reason: String): String {
        lastCloudError = reason
        return ""
    }

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

    private data class ToolCall(
        val id: String,
        val name: String,
        val arguments: StringBuilder = StringBuilder()
    )

    private data class ChatTurn(
        val content: String,
        val toolCalls: List<ToolCall>
    )

    private fun parseToolCalls(message: JSONObject?): List<ToolCall> {
        val arr = message?.optJSONArray("tool_calls") ?: return emptyList()
        val out = ArrayList<ToolCall>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val fn = item.optJSONObject("function")
            val name = jsonText(fn, "name")
            if (name.isBlank()) continue
            val id = jsonText(item, "id").ifBlank { "call_$i" }
            out.add(ToolCall(id, name, StringBuilder(jsonText(fn, "arguments"))))
        }
        return out
    }

    private fun mergeStreamToolCall(bucket: LinkedHashMap<Int, ToolCall>, delta: JSONObject) {
        val arr = delta.optJSONArray("tool_calls") ?: return
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val index = if (item.has("index")) item.optInt("index", i) else i
            val fn = item.optJSONObject("function")
            val existing = bucket[index]
            if (existing == null) {
                val name = jsonText(fn, "name")
                val id = jsonText(item, "id").ifBlank { "call_$index" }
                bucket[index] = ToolCall(id, name, StringBuilder(jsonText(fn, "arguments")))
            } else {
                val name = jsonText(fn, "name")
                if (name.isNotBlank()) {
                    bucket[index] = existing.copy(name = name)
                }
                val id = jsonText(item, "id")
                if (id.isNotBlank() && existing.id.startsWith("call_")) {
                    bucket[index] = bucket[index]!!.copy(id = id)
                }
                existing.arguments.append(jsonText(fn, "arguments"))
            }
        }
    }

    private fun assistantToolMessage(content: String, calls: List<ToolCall>): JSONObject {
        val arr = JSONArray()
        calls.forEach { call ->
            arr.put(
                JSONObject()
                    .put("id", call.id.take(80))
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", call.name.take(80))
                            .put("arguments", call.arguments.toString().take(4000))
                    )
            )
        }
        val msg = JSONObject().put("role", "assistant")
        if (content.isNotBlank()) msg.put("content", content.take(MAX_MESSAGE_CHARS))
        else msg.put("content", JSONObject.NULL)
        msg.put("tool_calls", arr)
        return msg
    }

    private fun toolResultMessage(call: ToolCall, result: String): JSONObject {
        return JSONObject()
            .put("role", "tool")
            .put("tool_call_id", call.id.take(80))
            .put("name", call.name.take(80))
            .put("content", result.take(LlmTools.MAX_RESULT_CHARS))
    }

    private suspend fun runTools(calls: List<ToolCall>): JSONArray {
        val host = toolHost ?: return JSONArray()
        val out = JSONArray()
        for (call in calls) {
            if (call.name.isBlank()) continue
            onToolStatus?.invoke(LlmTools.statusLabel(call.name))
            val result = try {
                LlmTools.execute(call.name, call.arguments.toString(), host)
            } catch (e: Exception) {
                "工具失败：" + (e.message?.take(80) ?: "未知错误")
            }
            out.put(toolResultMessage(call, result.ifBlank { "工具没有返回内容" }))
        }
        return out
    }

    private fun parseNonStreamTurn(text: String): ChatTurn {
        val message = JSONObject(text).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
        return ChatTurn(extractContent(message), parseToolCalls(message))
    }

    private fun requestTurn(
        history: List<ChatMessage>,
        extra: JSONArray?,
        withTools: Boolean,
        stream: Boolean,
        onDelta: ((String) -> Unit)?
    ): ChatTurn = if (stream) {
        requestStream(history, extra, withTools, onDelta)
    } else {
        requestNonStream(history, extra, withTools)
    }

    private fun requestNonStream(history: List<ChatMessage>, extra: JSONArray?, withTools: Boolean): ChatTurn {
        client.newCall(buildRequest(false, buildBody(history, false, extra, withTools))).execute().use { resp ->
            if (resp.isRedirect) throw IllegalStateException("接口发生了跳转")
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("返回为空")
            val text = body.readUtf8Limited(MAX_RESPONSE_BYTES) ?: throw IllegalStateException("返回内容过大或为空")
            return parseNonStreamTurn(text)
        }
    }

    private fun requestStream(
        history: List<ChatMessage>,
        extra: JSONArray?,
        withTools: Boolean,
        onDelta: ((String) -> Unit)?
    ): ChatTurn {
        val sb = StringBuilder()
        val bucket = LinkedHashMap<Int, ToolCall>()
        client.newCall(buildRequest(true, buildBody(history, true, extra, withTools))).execute().use { resp ->
            if (resp.isRedirect) throw IllegalStateException("接口发生了跳转")
            if (!resp.isSuccessful || resp.body == null) {
                throw IllegalStateException(if (!resp.isSuccessful) "HTTP ${resp.code}" else "返回为空")
            }
            val reader: BufferedReader = resp.body!!.source().inputStream().bufferedReader(Charsets.UTF_8)
            while (sb.length < MAX_OUTPUT_CHARS) {
                val line = reader.readLine() ?: break
                if (line.length > MAX_SSE_LINE_CHARS || !line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                if (payload.isEmpty()) continue
                val delta = runCatching {
                    JSONObject(payload).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                }.getOrNull() ?: continue
                mergeStreamToolCall(bucket, delta)
                val piece = extractDelta(delta)
                if (piece.isNotEmpty() && bucket.isEmpty()) {
                    val extraPiece = incrementalDelta(sb, piece).take(MAX_OUTPUT_CHARS - sb.length)
                    if (extraPiece.isNotEmpty()) {
                        sb.append(extraPiece)
                        onDelta?.invoke(extraPiece)
                    }
                } else if (piece.isNotEmpty()) {
                    sb.append(incrementalDelta(sb, piece).take(MAX_OUTPUT_CHARS - sb.length))
                }
            }
        }
        val calls = bucket.values.filter { it.name.isNotBlank() }
        val content = if (calls.isEmpty()) stripReasoning(sb.toString()) else sb.toString().trim()
        return ChatTurn(content, calls)
    }

    private suspend fun completeWithTools(
        history: List<ChatMessage>,
        stream: Boolean,
        onDelta: ((String) -> Unit)?
    ): String {
        val extra = JSONArray()
        var round = 0
        var lastContent = ""
        try {
            while (round <= MAX_TOOL_ROUNDS) {
                val withTools = toolsReady() && round < MAX_TOOL_ROUNDS
                val turn = try {
                    requestTurn(history, extra.takeIf { it.length() > 0 }, withTools, stream, onDelta)
                } catch (e: IllegalStateException) {
                    val code = e.message.orEmpty()
                    if (withTools && (code.contains("HTTP 400") || code.contains("HTTP 422"))) {
                        toolsUnsupported = true
                        requestTurn(history, extra.takeIf { it.length() > 0 }, false, stream, onDelta)
                    } else {
                        throw e
                    }
                }
                lastContent = turn.content
                if (turn.toolCalls.isEmpty()) {
                    val text = turn.content.take(MAX_OUTPUT_CHARS)
                    if (text.isNotBlank()) {
                        if (!stream) onDelta?.invoke(text)
                        return text
                    }
                    return cloudFail("接口已通，但没有返回内容")
                }
                extra.put(assistantToolMessage(turn.content, turn.toolCalls))
                val results = runTools(turn.toolCalls)
                for (i in 0 until results.length()) extra.put(results.optJSONObject(i) ?: continue)
                round++
            }
            return lastContent.takeIf { it.isNotBlank() } ?: cloudFail("工具调用次数过多")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (lastContent.isNotBlank()) return lastContent.take(MAX_OUTPUT_CHARS)
            throw e
        }
    }

    suspend fun chat(history: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        lastCloudError = null
        if (!llmConfigured()) {
            if (allowLocalFallback && freeChatEnabled()) {
                free.reply(freeHistory(history))?.takeIf { it.isNotBlank() }?.let { return@withContext it }
            }
            return@withContext localReply(history)
        }
        try {
            completeWithTools(history, stream = false, onDelta = null)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            cloudFail(e.message?.take(80).orEmpty().ifBlank { "网络异常或接口不可达" })
        }
    }

    suspend fun chatStream(history: List<ChatMessage>, onDelta: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            lastCloudError = null
            if (!llmConfigured()) {
                val online = if (allowLocalFallback && freeChatEnabled()) free.reply(freeHistory(history)) else null
                val ans = online?.takeIf { it.isNotBlank() } ?: localReply(history)
                if (ans.isNotEmpty()) onDelta(ans)
                return@withContext ans
            }
            try {
                completeWithTools(history, stream = true, onDelta = onDelta)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                cloudFail(e.message?.take(80).orEmpty().ifBlank { "网络异常或接口不可达" })
            }
        }

    /** 探测云端接口是否可用，不回落到本地闲聊。 */
    suspend fun chatVision(prompt: String, imageBytes: ByteArray, mime: String = "image/jpeg"): String =
        withContext(Dispatchers.IO) {
            if (!llmConfigured() || imageBytes.isEmpty()) return@withContext ""
            val b64 = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
            val dataUrl = "data:" + mime + ";base64," + b64
            val content = JSONArray()
                .put(JSONObject().put("type", "text").put("text", prompt.take(300).ifBlank { "请用中文简短说明这张图。" }))
                .put(
                    JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", dataUrl))
                )
            val messages = JSONArray()
            val sys = composeSystem()
            if (sys.isNotBlank()) messages.put(JSONObject().put("role", "system").put("content", sys))
            messages.put(JSONObject().put("role", "user").put("content", content))
            val body = JSONObject()
                .put("model", prefs.model.take(MAX_MODEL_CHARS))
                .put("messages", messages)
                .put("temperature", 0.4)
                .put("stream", false)
                .toString()
            try {
                client.newCall(buildRequest(false, body)).execute().use { resp ->
                    if (!resp.isSuccessful || resp.isRedirect) return@withContext ""
                    val text = resp.body?.readUtf8Limited(MAX_RESPONSE_BYTES) ?: return@withContext ""
                    extractContent(
                        JSONObject(text).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                    ).take(MAX_OUTPUT_CHARS)
                }
            } catch (_: Exception) {
                ""
            }
        }

    /** 探测云端接口是否可用，不回落到本地闲聊。 */
    suspend fun probe(): String = withContext(Dispatchers.IO) {
        if (!llmConfigured()) return@withContext "还没填完整：需要 HTTPS 地址、模型名和 API Key"
        try {
            val probeHistory = listOf(ChatMessage("user", "只回复一个字：好", isMe = true))
            client.newCall(buildRequest(false, buildBody(probeHistory, false, withTools = false))).execute().use { resp ->
                if (resp.isRedirect) return@withContext "连接失败：接口发生了跳转"
                if (!resp.isSuccessful) return@withContext "连接失败：HTTP ${resp.code}"
                val text = resp.body?.readUtf8Limited(MAX_RESPONSE_BYTES)
                    ?: return@withContext "连接失败：返回内容过大或为空"
                val content = extractContent(JSONObject(text).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message"))
                if (content.isNotBlank()) "连接成功，云端模型已回复" else "接口已通，但没有返回内容，检查模型名"
            }
        } catch (_: Exception) {
            "连接失败：网络异常或接口不可达"
        }
    }

    companion object {
        private const val MAX_HISTORY_MESSAGES = 40
        private const val MAX_MESSAGE_CHARS = 12_000
        private const val MAX_SYSTEM_CHARS = 16_000
        private const val MAX_MODEL_CHARS = 200
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MAX_OUTPUT_CHARS = 200_000
        private const val MAX_SSE_LINE_CHARS = 256_000
        private const val MAX_TOOL_ROUNDS = 4
        private val THINK_BLOCK = Regex("(?s)<think>.*?</think>|<thinking>.*?</thinking>")
        private const val CLOUD_THINK_PROMPT =
            "【云端推理】先在内部完成充分思考，再给出最终对白。" +
                "思考过程请放在 reasoning 字段或 <think></think> 中，不要出现在最终对白里。" +
                "最终对白保持角色口吻，不要提及模型、提示词或思考过程。"
        private const val TOOL_PROMPT =
            "【工具】你可以使用提供的工具来查网、看天气、翻记忆、翻译、换算、看新闻或待办。" +
                "闲聊、接茬、情绪陪伴不要调用工具，直接开口。" +
                "认真提问、时事、百科、天气、记忆核对时再调用。" +
                "需要资料时先调用工具，拿到结果后再回答；资料不够就明说只查到这些，不要编造。" +
                "不要在对白里提及工具名、提示词或函数调用。"
    }
}
