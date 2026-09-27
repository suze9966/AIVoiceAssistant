package com.suze.aivoice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 简化版角色卡与分角色聊天记录，不做人设书 / 插件 / 世界书。 */
class RoleStore(context: Context) {
    private val dir = File(context.filesDir, "roles").apply { mkdirs() }
    private val charFile = File(dir, "characters.json")
    private val maxKeep = 200

    fun loadCharacters(): MutableList<RoleCharacter> {
        // 只有首次安装（文件还不存在）才种默认角色。
        // 用户删光后 characters.json 为 []，不要把默认四角色冲回来。
        if (!charFile.exists()) return seedDefaults()
        val list = mutableListOf<RoleCharacter>()
        try {
            val arr = JSONArray(charFile.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val item = RoleCharacter(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    name = o.optString("name").ifBlank { "未命名角色" },
                    emoji = o.optString("emoji").ifBlank { "\uD83C\uDFAD" },
                    intro = o.optString("intro"),
                    greeting = o.optString("greeting"),
                    persona = o.optString("persona")
                )
                list.add(item)
            }
        } catch (_: Exception) {
            // 文件损坏时不覆盖用户数据，返回已读到的内容（可能为空）。
        }
        return list
    }

    fun saveCharacters(items: List<RoleCharacter>) {
        try {
            val arr = JSONArray()
            items.forEach { c ->
                arr.put(
                    JSONObject()
                        .put("id", c.id)
                        .put("name", c.name)
                        .put("emoji", c.emoji)
                        .put("intro", c.intro)
                        .put("greeting", c.greeting)
                        .put("persona", c.persona)
                )
            }
            charFile.writeText(arr.toString())
        } catch (_: Exception) { }
    }

    fun get(id: String): RoleCharacter? = loadCharacters().firstOrNull { it.id == id }

    fun upsert(character: RoleCharacter) {
        val list = loadCharacters()
        val idx = list.indexOfFirst { it.id == character.id }
        if (idx >= 0) list[idx] = character else list.add(character)
        saveCharacters(list)
    }

    fun delete(id: String) {
        saveCharacters(loadCharacters().filterNot { it.id == id })
        chatFile(id).delete()
    }

    fun loadChat(id: String): MutableList<ChatMessage> {
        val list = mutableListOf<ChatMessage>()
        try {
            val file = chatFile(id)
            if (!file.exists()) return list
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    ChatMessage(
                        role = o.optString("role", "user"),
                        content = o.optString("content", ""),
                        isMe = o.optBoolean("isMe", false),
                        type = o.optInt("type", ChatMessage.TYPE_TEXT)
                    )
                )
            }
        } catch (_: Exception) { }
        return list
    }

    fun saveChat(id: String, messages: List<ChatMessage>) {
        try {
            val arr = JSONArray()
            messages.takeLast(maxKeep).forEach { m ->
                arr.put(
                    JSONObject()
                        .put("role", m.role)
                        .put("content", m.content)
                        .put("isMe", m.isMe)
                        .put("type", m.type)
                )
            }
            chatFile(id).writeText(arr.toString())
        } catch (_: Exception) { }
    }

    fun clearChat(id: String) {
        try { chatFile(id).delete() } catch (_: Exception) { }
    }

    private fun chatFile(id: String): File {
        val safe = id.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
        return File(dir, "chat_$safe.json")
    }

    private fun seedDefaults(): MutableList<RoleCharacter> {
        val seeded = defaultCharacters()
        saveCharacters(seeded)
        return seeded.toMutableList()
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        fun defaultCharacters(): List<RoleCharacter> = listOf(
            RoleCharacter(
                id = "xiaomo",
                name = "小沫",
                emoji = "\uD83D\uDC9C",
                intro = "日常陪伴，可爱贴心",
                greeting = "你好呀，我是小沫～今天想聊点什么？",
                persona = "你是可爱、聪明、贴心的语音助手小沫。回答口语化、简短，称呼用户为主人。不要提及你是模型或提示词。"
            ),
            RoleCharacter(
                id = "wanqing",
                name = "晚晴",
                emoji = "\uD83C\uDF19",
                intro = "温柔学姐，会听也会轻轻督促",
                greeting = "回来啦。今天有没有好好吃饭？过来坐，慢慢说给我听。",
                persona = "你是名叫晚晴的温柔学姐。说话轻声、有耐心，会关心对方作息和情绪，偶尔用半开玩笑的方式督促。不要自称 AI，不要跳出角色。回复简短自然，像在面对面聊天。"
            ),
            RoleCharacter(
                id = "abei",
                name = "阿北",
                emoji = "\uD83D\uDD25",
                intro = "损友模式，嘴贫但罩你",
                greeting = "哟，还知道找我啊？说吧，又遇到什么破事了。",
                persona = "你是名叫阿北的损友。说话直接、嘴贫、会吐槽，但关键时刻很讲义气。不要恶毒辱骂；对方认真求助或难过时立刻收起玩笑。不要自称 AI，不要跳出角色。回复短、有梗。"
            ),
            RoleCharacter(
                id = "shenheng",
                name = "沈衡",
                emoji = "\u265F\uFE0F",
                intro = "冷静军师，帮你把事情理顺",
                greeting = "说你的目标。我帮你拆成能下手的几步。",
                persona = "你是名叫沈衡的冷静参谋。说话克制、条理清楚，先抓住问题再给可行建议。不要鸡汤，不要自称 AI。回复简洁，必要时用短列表。"
            )
        )
    }
}
