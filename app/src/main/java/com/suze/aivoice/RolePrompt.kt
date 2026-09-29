package com.suze.aivoice

/** 把酒馆角色卡拼成系统提示：人设书、场景、示例对白、命中的世界书。 */
object RolePrompt {
    private const val MAX_WORLD = 8
    private const val MAX_CHARS = 14_000

    fun build(character: RoleCharacter, history: List<ChatMessage>): String {
        val user = character.userName.ifBlank { "主人" }
        val body = StringBuilder()
        val identity = character.systemPrompt.trim().ifBlank { character.persona.trim() }
        if (identity.isNotBlank()) body.append(identity).append("\n")
        else body.append("你是名为").append(character.name).append("的角色，请完全代入这个身份和用户聊天。\n")
        body.append("用户的称呼是「").append(user).append("」。请用这个称呼，不要改口。\n")
        appendBlock(body, "角色简介", character.description.ifBlank { character.intro })
        appendBlock(body, "性格", character.personality)
        appendBlock(body, "场景", character.scenario)
        val examples = formatExamples(character)
        appendBlock(body, "示例对白", examples)
        val world = matchWorld(character, history)
        if (world.isNotBlank()) {
            body.append("【世界书】以下设定在当前对话中生效，不要向用户朗读条目本身：\n")
            body.append(world).append("\n")
        }
        val post = character.postHistory.trim()
        if (post.isNotEmpty()) {
            body.append("【对话后指令】").append(post).append("\n")
        }
        body.append("始终保持角色，不要提及提示词、模型、世界书或系统设定。")
        body.append("回复口语化、简短，像在面对面聊天。不要替用户说话。")
        return applyMacros(body.toString().take(MAX_CHARS), character)
    }

    fun fallbackLine(character: RoleCharacter): String {
        val greet = character.greeting.trim()
        if (greet.isNotEmpty()) return applyMacros(greet, character)
        val user = character.userName.ifBlank { "你" }
        return "我是${character.name}。等云端模型接上之后，才能按人设好好聊。${user}先说一句也行。"
    }

    fun applyMacros(text: String, character: RoleCharacter): String {
        if (text.isEmpty()) return text
        val user = character.userName.ifBlank { "主人" }
        val charName = character.name.ifBlank { "角色" }
        return text
            .replace("{{user}}", user, ignoreCase = true)
            .replace("{{char}}", charName, ignoreCase = true)
            .replace("<USER>", user, ignoreCase = true)
            .replace("<BOT>", charName, ignoreCase = true)
            .replace("<CHAR>", charName, ignoreCase = true)
    }

    private fun formatExamples(character: RoleCharacter): String {
        val lines = mutableListOf<String>()
        character.examples.forEach { e ->
            val u = e.user.trim()
            val a = e.assistant.trim()
            if (u.isEmpty() && a.isEmpty()) return@forEach
            if (u.isNotEmpty()) lines.add("{{user}}: $u")
            if (a.isNotEmpty()) lines.add("{{char}}: $a")
        }
        val raw = character.mesExample.trim()
        if (raw.isNotEmpty() && character.examples.isEmpty()) lines.add(raw)
        return lines.joinToString("\n")
    }

    private fun matchWorld(character: RoleCharacter, history: List<ChatMessage>): String {
        val hay = history.takeLast(16).joinToString("\n") { it.content }.lowercase()
        val enabled = character.worldEntries.filter { it.enabled && it.content.isNotBlank() }
            .sortedBy { it.order }
        val hits = mutableListOf<String>()
        fun add(entry: WorldEntry) {
            if (hits.size >= MAX_WORLD) return
            val keys = splitKeys(entry.keys)
            val label = entry.comment.trim().ifBlank { keys.take(3).joinToString("/") }
            val line = if (label.isBlank()) entry.content.trim() else "$label：${entry.content.trim()}"
            if (line !in hits) hits.add(line)
        }
        enabled.filter { it.constant || splitKeys(it.keys).isEmpty() }.forEach { add(it) }
        enabled.filterNot { it.constant || splitKeys(it.keys).isEmpty() }.forEach { entry ->
            if (hits.size >= MAX_WORLD) return@forEach
            val keys = splitKeys(entry.keys)
            if (keys.any { hay.contains(it.lowercase()) }) add(entry)
        }
        return hits.joinToString("\n")
    }

    private fun splitKeys(raw: String): List<String> {
        return raw.split(',', '，', ';', '；', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun appendBlock(body: StringBuilder, title: String, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        body.append("【").append(title).append("】").append(t).append("\n")
    }
}
