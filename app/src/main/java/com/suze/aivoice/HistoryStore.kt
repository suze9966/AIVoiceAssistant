package com.suze.aivoice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 本地对话历史存储：以 JSON 文件持久化，重启 App 后仍可恢复。
 * 默认保留最近 200 条消息。
 */
class HistoryStore(context: Context) {

    private val file = File(context.filesDir, "chat_history.json")
    private val maxKeep = 200

    fun save(messages: List<ChatMessage>) {
        try {
            val arr = JSONArray()
            messages.takeLast(maxKeep).forEach { m ->
                arr.put(JSONObject()
                    .put("role", m.role)
                    .put("content", m.content)
                    .put("isMe", m.isMe))
            }
            file.writeText(arr.toString())
        } catch (_: Exception) { }
    }

    fun load(): MutableList<ChatMessage> {
        val list = mutableListOf<ChatMessage>()
        try {
            if (!file.exists()) return list
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    ChatMessage(
                        role = o.optString("role", "user"),
                        content = o.optString("content", ""),
                        isMe = o.optBoolean("isMe", false)
                    )
                )
            }
        } catch (_: Exception) { }
        return list
    }

    fun clear() {
        try { if (file.exists()) file.delete() } catch (_: Exception) { }
    }
}
