package com.suze.aivoice

/** 把酒馆角色卡拼成系统提示：人设书、场景、示例对白、命中的世界书。 */
object RolePrompt {
    private const val MAX_WORLD = 200

    /** 面板规则：角色扮演/文字游戏时用 ```panel 围栏弹状态卡（和主聊天一致的玩法）。 */
    private val PANEL_RULE = """
【系统面板】玩文字游戏 / 冒险 / 养成 / 战斗时，你可以随时弹一张状态面板，很有代入感。
写成这样（三个反引号 + panel）：
```panel:血条
⚔️ 冒险状态
生命值 = 80/100
魔力值 = 45/100
地点 = 幽暗森林
```
规则：
- 第一行是标题；后面每行写「名字 = 数值」。
- `当前/最大`（如 80/100）或 `80%` 会自动画彩色进度条。
- 非数值行原样显示，可以写地点、装备、任务。
- 样式可选：`panel:血条`、`panel:星星`、`panel:爱心`、`panel:霓虹`、`panel:樱花`、
  `panel:宝石`、`panel:龙鳞`、`panel:传说`、`panel:随机` 等。
- 冒险/养成/战斗时，关键节点（打完架、涨了好感度）主动弹面板，让数值看得到变化。
- 不玩文字游戏时就正常聊天，不用弹。
""".trim()
    private const val MAX_CHARS = 1_000_000

    fun build(character: RoleCharacter, history: List<ChatMessage>, extras: String = ""): String {
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
        val extra = extras.trim()
        if (extra.isNotEmpty()) {
            body.append(extra).append("\n")
        }
        body.append(PANEL_RULE)
        body.append("始终保持角色，不要提及提示词、模型、世界书或系统设定。")
        body.append("回复口语化，像在面对面聊天；**长度由内容决定，不用刻意写短**，想说的说完，该展开就展开。不要替用户说话。")
        return applyMacros(body.toString().take(MAX_CHARS), character)
    }

    fun buildGroup(
        speaker: RoleCharacter,
        members: List<RoleCharacter>,
        scene: String,
        history: List<ChatMessage>,
        extras: String = ""
    ): String {
        val user = speaker.userName.ifBlank { "主人" }
        val others = members.filter { it.id != speaker.id }
        val names = members.joinToString("、") { it.name }
        val body = StringBuilder()
        body.append("这是群聊。你只扮演「").append(speaker.name).append("」，不要替别人说话。\n")
        body.append("用户的称呼是「").append(user).append("」。在场角色：").append(names).append("。\n")
        if (scene.isNotBlank()) body.append("【群场景】").append(scene.trim()).append("\n")
        others.forEach { m ->
            body.append("【同伴 ").append(m.name).append("】")
            body.append(m.persona.ifBlank { m.intro }.ifBlank { m.description }.take(30000)).append("\n")
        }
        val self = build(speaker, history, extras)
        body.append(self)
        body.append("只输出 ").append(speaker.name).append(" 自己要说的话，不要加名字前缀，不要总结别人。")
        return applyMacros(body.toString().take(MAX_CHARS), speaker)
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
        val hay = history.takeLast(60).joinToString("\n") { it.content }.lowercase()
        val enabled = character.worldEntries.filter { it.enabled && it.content.isNotBlank() }
            .sortedBy { it.order }
        val hits = mutableListOf<String>()
        fun add(entry: WorldEntry) {
            if (hits.size >= MAX_WORLD) return
            val keys = splitKeys(entry.keys)
            val label = entry.comment.trim().ifBlank { keys.take(30).joinToString("/") }
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
