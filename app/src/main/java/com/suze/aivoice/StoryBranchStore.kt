package com.suze.aivoice

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 剧情锚点分支：把「读档/存档」带进角色扮演。
 *
 * 普通酒馆一旦生成就不回头，说错一句只能清空重来。
 * 这里允许在任意一条消息上打**锚点**（自动记住当时前 40 条上下文），
 * 之后任何时候都能「回到这个锚点」，并分裂出**另一条剧情线**，
 * 原来的世界线原封不动保留 —— 于是可以同一个人物走出「投降路线」和「抗争路线」，
 * 两条线的聊天记录并存、可随时切换。
 */
class StoryBranchStore(context: android.content.Context) {

    data class Anchor(
        val id: String,
        val name: String,
        val characterId: String,
        val atIndex: Int,
        val snapshot: String,      // 打锚点时的上下文（序列化）
        val createdAt: Long,
        val note: String = ""
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id); put("name", name); put("characterId", characterId)
            put("atIndex", atIndex); put("snapshot", snapshot)
            put("createdAt", createdAt); put("note", note)
        }

        companion object {
            fun fromJson(o: JSONObject): Anchor = Anchor(
                id = o.optString("id", UUID.randomUUID().toString()),
                name = o.optString("name", "锚点"),
                characterId = o.optString("characterId"),
                atIndex = o.optInt("atIndex", 0),
                snapshot = o.optString("snapshot", "[]"),
                createdAt = o.optLong("createdAt", 0L),
                note = o.optString("note", "")
            )
        }
    }

    private val dir = File(context.filesDir, "roles").apply { mkdirs() }
    private val file = File(dir, "anchors.json")
    private val cache = ArrayList<Anchor>()
    private var loaded = false

    private fun load(): List<Anchor> {
        if (loaded) return cache
        loaded = true
        if (!file.exists()) return cache
        runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { cache.add(Anchor.fromJson(it)) }
            }
        }
        return cache
    }

    private fun persist() {
        runCatching {
            val arr = JSONArray()
            cache.forEach { arr.put(it.toJson()) }
            file.writeText(arr.toString())
        }
    }

    /** 打一个锚点；history 是当时的消息列表（会被序列化保存） */
    fun mark(characterId: String, name: String, atIndex: Int, history: List<ChatMessage>, note: String = ""): Anchor {
        load()
        val a = Anchor(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "锚点 " + (cache.count { it.characterId == characterId } + 1) },
            characterId = characterId,
            atIndex = atIndex,
            snapshot = encode(history),
            createdAt = System.currentTimeMillis(),
            note = note
        )
        cache.add(0, a)
        if (cache.size > 1000) { while (cache.size > 1000) cache.removeAt(cache.size - 1) }
        persist()
        return a
    }

    fun list(characterId: String): List<Anchor> = load().filter { it.characterId == characterId }.sortedByDescending { it.createdAt }

    fun listAll(): List<Anchor> = load().sortedByDescending { it.createdAt }

    fun delete(id: String) {
        load()
        cache.removeAll { it.id == id }
        persist()
    }

    fun rename(id: String, newName: String) {
        load()
        val i = cache.indexOfFirst { it.id == id }
        if (i >= 0) { cache[i] = cache[i].copy(name = newName); persist() }
    }

    /** 取出锚点里保存的上下文，用于「回到这里」 */
    fun restore(anchor: Anchor): List<ChatMessage> = decode(anchor.snapshot)

    /**
     * 从锚点分叉：生成一条新剧情线的名字（自动加序号），
     * 返回新锚点 + 上下文，调用方负责写入新会话。
     */
    fun fork(anchor: Anchor, lineName: String, history: List<ChatMessage>): Pair<Anchor, List<ChatMessage>> {
        val name = lineName.ifBlank { anchor.name + " · 分支" }
        val newAnchor = mark(anchor.characterId, name, anchor.atIndex, history, "从「${anchor.name}」分叉")
        return newAnchor to history
    }

    private fun encode(history: List<ChatMessage>): String {
        val arr = JSONArray()
        history.forEach { m ->
            val o = JSONObject()
            o.put("role", m.role)
            o.put("content", m.content)
            o.put("isMe", m.isMe)
            o.put("type", m.type)
            o.put("speakerId", m.speakerId)
            o.put("speakerName", m.speakerName)
            o.put("speakerEmoji", m.speakerEmoji)
            o.put("at", m.at)
            arr.put(o)
        }
        return arr.toString()
    }

    private fun decode(s: String): List<ChatMessage> {
        val out = ArrayList<ChatMessage>()
        runCatching {
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    ChatMessage(
                        role = o.optString("role", "user"),
                        content = o.optString("content"),
                        isMe = o.optBoolean("isMe", o.optString("role") == "user"),
                        type = o.optInt("type", ChatMessage.TYPE_TEXT),
                        speakerId = o.optString("speakerId", ""),
                        speakerName = o.optString("speakerName", ""),
                        speakerEmoji = o.optString("speakerEmoji", ""),
                        at = o.optLong("at", 0L)
                    )
                )
            }
        }
        return out
    }

    fun clear() {
        load()
        cache.clear()
        persist()
    }
}