package com.suze.aivoice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 免费在线闲聊客户端：不需要任何 API Key、不需要注册、不花钱。
 *
 * 用于「联网但没接大模型」的场景：只要手机能上网，就能获得比本地规则更聪明的回复。
 *
 * 原理：调用公开的免费 OpenAI 兼容接口（ch.at），
 * 失败时自动尝试备用源（pollinations.ai POST 接口）。
 * 全部失败则返回 null，由上层继续降级到本地规则引擎。
 */
class FreeChatClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** 首选的免费 OpenAI 兼容端点（无需 Key） */
    private val primaryUrl = "https://ch.at/v1/chat/completions"

    /** 备用 OpenAI 兼容 POST 端点；禁止把提示词放进 URL。 */
    private val backupUrl = "https://text.pollinations.ai/openai"

    /**
     * 生成一条回复。
     * @param history 对话历史（含 system 人设 + 多轮）
     * @param timeoutMs 单次尝试的超时（毫秒）
     * @return 回复文本；全部失败返回 null
     */
    suspend fun reply(history: List<ChatMessage>, timeoutMs: Long = 40000L): String? =
        withContext(Dispatchers.IO) {
            tryPrimary(history, timeoutMs) ?: tryBackup(history, timeoutMs)
        }

    /** 首选：ch.at，OpenAI 兼容 POST，支持 system 人设与多轮 */
    private fun tryPrimary(history: List<ChatMessage>, timeoutMs: Long): String? {
        return try {
            val messages = JSONArray()
            history.forEach { m ->
                if (m.content.isNotBlank()) {
                    messages.put(JSONObject().put("role", m.role).put("content", m.content))
                }
            }
            val body = JSONObject()
                .put("model", "gpt-3.5-turbo")
                .put("messages", messages)
                .put("temperature", 0.8)

                .put("max_tokens", 2_048)
                .toString()
            val req = Request.Builder()
                .url(primaryUrl)
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful || resp.isRedirect) return null
                val text = resp.body?.readUtf8Limited(2 * 1024 * 1024) ?: return null
                val choices = JSONObject(text).optJSONArray("choices") ?: return null
                if (choices.length() == 0) return null
                choices.getJSONObject(0)
                    .optJSONObject("message")
                    ?.optString("content")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 备用：使用 POST JSON，避免 GET URL 被代理、历史记录或服务日志直接记录提示词。 */
    private fun tryBackup(history: List<ChatMessage>, timeoutMs: Long): String? {
        return try {
            val messages = JSONArray()
            history.takeLast(40).forEach { m ->
                if (m.content.isNotBlank()) messages.put(JSONObject().put("role", m.role).put("content", m.content.take(12000)))
            }
            val body = JSONObject().put("model", "openai").put("messages", messages).toString()
            val req = Request.Builder().url(backupUrl)
                .header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType())).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful || resp.isRedirect) return null
                val responseBody = resp.body ?: return null
                val text = responseBody.readUtf8Limited(2 * 1024 * 1024) ?: return null
                runCatching {
                    JSONObject(text).optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("message")?.optString("content")?.trim()
                }.getOrNull()?.takeIf { it.isNotEmpty() }
            }
        } catch (_: Exception) { null }
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

}
