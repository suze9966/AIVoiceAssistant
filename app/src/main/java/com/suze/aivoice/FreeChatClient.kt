package com.suze.aivoice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 免费在线闲聊客户端：不需要任何 API Key、不需要注册、不花钱。
 *
 * 用于「联网但没接大模型」的场景：只要手机能上网，就能获得比本地规则更聪明的回复。
 *
 * 原理：调用公开的免费 OpenAI 兼容接口（ch.at），
 * 失败时自动尝试备用源（pollinations.ai 纯文本接口）。
 * 全部失败则返回 null，由上层继续降级到本地规则引擎。
 */
class FreeChatClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 首选的免费 OpenAI 兼容端点（无需 Key） */
    private val primaryUrl = "https://ch.at/v1/chat/completions"

    /** 备用免费纯文本端点（GET，无需 Key） */
    private val backupUrl = "https://text.pollinations.ai/"

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
                .toString()
            val req = Request.Builder()
                .url(primaryUrl)
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val text = resp.body?.string() ?: return null
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

    /** 备用：pollinations.ai 纯文本 GET，只取最后一条用户消息 */
    private fun tryBackup(history: List<ChatMessage>, timeoutMs: Long): String? {
        return try {
            val lastUser = history.lastOrNull { it.role == "user" }?.content ?: return null
            val sys = history.firstOrNull { it.role == "system" }?.content
            val prompt = if (sys.isNullOrBlank()) lastUser else "$sys\n\n用户说：$lastUser"
            val encoded = java.net.URLEncoder.encode(prompt, "UTF-8").replace("+", "%20")
            val req = Request.Builder()
                .url(backupUrl + encoded)
                .addHeader("Accept", "text/plain")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()?.trim()?.takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            null
        }
    }
}
