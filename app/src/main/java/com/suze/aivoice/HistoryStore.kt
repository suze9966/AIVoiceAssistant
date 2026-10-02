package com.suze.aivoice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class ChatSession(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val preview: String = ""
)

/**
 * 主聊天多会话：index.json + chat_{id}.json。
 * 兼容旧版单一 chat_history.json，首次启动自动迁成默认会话。
 */
class HistoryStore(context: Context) {
    private val dir = File(context.filesDir, "chats").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val legacyFile = File(context.filesDir, "chat_history.json")
    private val maxKeep = 20000
    private val maxSessions = 2000

    fun migrateAndActive(prefs: Prefs): String {
        val list = loadSessions()
        if (list.isEmpty()) {
            val id = newId()
            val legacy = loadMessages(legacyFile)
            val preview = legacy.lastOrNull { it.content.isNotBlank() }?.content.orEmpty()
            val title = if (legacy.isEmpty()) "日常聊天" else titleFrom(legacy)
            saveSessions(listOf(ChatSession(id, title, System.currentTimeMillis(), SafeCut.takeUnitsSafe(preview, 2000))))
            if (legacy.isNotEmpty()) {
                writeMessages(chatFile(id), legacy)
                runCatching { legacyFile.delete() }
            }
            prefs.activeChatId = id
            return id
        }
        var id = prefs.activeChatId
        if (id.isBlank() || list.none { it.id == id }) {
            id = list.maxByOrNull { it.updatedAt }?.id ?: list.first().id
            prefs.activeChatId = id
        }
        return id
    }

    fun loadSessions(): MutableList<ChatSession> {
        val list = mutableListOf<ChatSession>()
        try {
            if (!indexFile.exists()) return list
            val arr = JSONArray(indexFile.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(
                    ChatSession(
                        id = o.optString("id").ifBlank { newId() },
                        title = o.optString("title").ifBlank { "对话" },
                        updatedAt = o.optLong("updatedAt"),
                        preview = o.optString("preview")
                    )
                )
            }
        } catch (_: Exception) { }
        return list
    }

    fun saveSessions(items: List<ChatSession>) {
        try {
            val arr = JSONArray()
            items.takeLast(maxSessions).forEach { s ->
                arr.put(
                    JSONObject()
                        .put("id", s.id)
                        .put("title", s.title)
                        .put("updatedAt", s.updatedAt)
                        .put("preview", s.preview)
                )
            }
            indexFile.writeText(arr.toString())
        } catch (_: Exception) { }
    }

    fun load(id: String): MutableList<ChatMessage> = loadMessages(chatFile(id))

    fun save(id: String, messages: List<ChatMessage>) {
        writeMessages(chatFile(id), messages.takeLast(maxKeep))
        val preview = messages.lastOrNull { it.content.isNotBlank() }?.content.orEmpty().take(2000)
        val list = loadSessions()
        val idx = list.indexOfFirst { it.id == id }
        val prev = list.getOrNull(idx)
        val title = when {
            prev == null -> titleFrom(messages)
            prev.title.isBlank() || prev.title == "日常聊天" || prev.title == "新对话" ->
                titleFrom(messages).ifBlank { prev.title }
            else -> prev.title
        }
        val now = ChatSession(id, title, System.currentTimeMillis(), preview)
        if (idx >= 0) list[idx] = now else list.add(now)
        saveSessions(list)
    }

    fun clear(id: String) {
        writeMessages(chatFile(id), emptyList())
        val list = loadSessions()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(preview = "", updatedAt = System.currentTimeMillis())
            saveSessions(list)
        }
    }

    fun create(title: String = "新对话"): ChatSession {
        val item = ChatSession(newId(), title.ifBlank { "新对话" }, System.currentTimeMillis(), "")
        val list = loadSessions()
        list.add(item)
        saveSessions(list)
        writeMessages(chatFile(item.id), emptyList())
        return item
    }

    fun rename(id: String, title: String) {
        val t = title.trim().take(200)
        if (t.isEmpty()) return
        val list = loadSessions()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        list[idx] = list[idx].copy(title = t, updatedAt = System.currentTimeMillis())
        saveSessions(list)
    }

    fun delete(id: String): String? {
        val list = loadSessions()
        if (list.size <= 1) return null
        runCatching { chatFile(id).delete() }
        val remain = list.filterNot { it.id == id }
        saveSessions(remain)
        return remain.maxByOrNull { it.updatedAt }?.id ?: remain.first().id
    }

    private fun chatFile(id: String): File =
        File(dir, "chat_${id.replace(Regex("[^A-Za-z0-9_\\-]"), "_")}.json")

    private fun loadMessages(file: File): MutableList<ChatMessage> {
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
                        isMe = o.optBoolean("isMe", false),
                        type = o.optInt("type", ChatMessage.TYPE_TEXT),
                        speakerId = o.optString("speakerId"),
                        speakerName = o.optString("speakerName"),
                        speakerEmoji = o.optString("speakerEmoji"),
                        at = o.optLong("at", 0L)
                    )
                )
            }
        } catch (_: Exception) { }
        return list
    }

    private fun writeMessages(file: File, messages: List<ChatMessage>) {
        try {
            val arr = JSONArray()
            messages.forEach { m ->
                arr.put(
                    JSONObject()
                        .put("role", m.role)
                        .put("content", m.content)
                        .put("isMe", m.isMe)
                        .put("type", m.type)
                        .put("speakerId", m.speakerId)
                        .put("speakerName", m.speakerName)
                        .put("speakerEmoji", m.speakerEmoji)
                        .put("at", m.at)
                )
            }
            file.writeText(arr.toString())
        } catch (_: Exception) { }
    }

    private fun titleFrom(messages: List<ChatMessage>): String {
        val first = messages.firstOrNull { it.role == "user" && it.content.isNotBlank() }?.content.orEmpty()
        if (first.isNotBlank()) return SafeCut.takeUnitsSafe(first, 200)
        return SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date())
    }

    private fun newId(): String = UUID.randomUUID().toString()
}