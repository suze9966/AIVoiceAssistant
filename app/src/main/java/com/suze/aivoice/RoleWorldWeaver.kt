package com.suze.aivoice

import java.util.UUID

/**
 * 世界书自动编织：从对话里**自动挖出**值得固化成设定的片段。
 *
 * 酒馆原版要人手写世界书条目；这里反过来 —— 聊着聊着，把反复出现的专有名词、
 * 明确的设定句自动抓成「候选条目」，用户点一下就收进世界书。
 */
object RoleWorldWeaver {

    data class Candidate(
        val keys: String,
        val content: String,
        val reason: String,
        val hits: Int
    )

    private val settingMarkers = listOf(
        "我叫", "我的名字", "我是", "这里是", "这个世界", "我们的约定", "规则是",
        "我的身份", "我住在", "我来自", "我在", "我有一只", "我有一把", "我的师父",
        "我的家人", "我的能力", "我的职业", "我最喜欢", "我最讨厌", "我害怕", "我讨厌"
    )

    private val nameBrackets = listOf('「' to '」', '《' to '》', '【' to '】', '“' to '”')

    /** 从对话里挖候选世界书条目。已存在于世界书的 key 会被跳过。 */
    fun mine(character: RoleCharacter, history: List<ChatMessage>, limit: Int = 6): List<Candidate> {
        val existing = character.worldEntries
            .flatMap { splitKeys(it.keys) }
            .map { it.lowercase() }
            .toMutableSet()
        val found = LinkedHashMap<String, Candidate>()
        val messages = history.filter { it.content.isNotBlank() }
        val recent = messages.takeLast(400)

        // 1) 括号里的专有名词：出现两次以上才算「设定」
        val bracketCount = mutableMapOf<String, Int>()
        recent.forEach { msg ->
            nameBrackets.forEach { (open, close) ->
                var from = 0
                while (true) {
                    val s = msg.content.indexOf(open, from)
                    if (s < 0) break
                    val e = msg.content.indexOf(close, s + 1)
                    if (e < 0) break
                    val term = msg.content.substring(s + 1, e).trim()
                    if (term.length in 2..12 && !term.contains('\n')) {
                        bracketCount[term] = (bracketCount[term] ?: 0) + 1
                    }
                    from = e + 1
                }
            }
        }
        bracketCount.filter { it.value >= 2 }
            .forEach { (term, n) ->
                if (term.lowercase() in existing) return@forEach
                found[term] = Candidate(
                    keys = term,
                    content = "$term 是这段故事里出现过的名字，请把它当成既有设定继续使用。",
                    reason = "对话里提到 $n 次",
                    hits = n
                )
            }

        // 2) 明确的设定句：以「我是/这里是/规则是」等开头的整句
        recent.forEach { msg ->
            val sentence = msg.content.replace('\n', ' ').trim()
            sentence.split('。', '！', '？', '!', '?', '；', ';').forEach { raw ->
                val line = raw.trim()
                if (line.length !in 6..60) return@forEach
                val marker = settingMarkers.firstOrNull { line.contains(it) }
                if (marker == null) return@forEach
                if (line.lowercase().startsWith("你是")) return@forEach
                val key = keyOf(line, marker)
                if (key.isBlank() || key.lowercase() in existing) return@forEach
                if (found.containsKey(key)) return@forEach
                // 别的候选已收录同名 key（如括号名词），跳过避免重复
                if (found.values.any { it.keys.equals(key, ignoreCase = true) }) return@forEach
                found[key] = Candidate(
                    keys = key,
                    content = line,
                    reason = "看起来是一条设定（$marker）",
                    hits = 1
                )
            }
        }

        return found.values.sortedByDescending { it.hits }.take(limit)
    }

    private fun keyOf(line: String, marker: String): String {
        val idx = line.indexOf(marker)
        if (idx < 0) return ""
        val tail = line.substring(idx + marker.length).trim()
        val cut = tail.indexOfFirst { it in "，,。！？!?；;、 " }
        var head = if (cut > 0) tail.substring(0, cut) else tail
        // 去掉包裹的引号/括号，避免 key 带符号导致正文匹配不上
        val trimChars = "「」《》【】“”\"'（）()".toCharArray()
        head = head.trim().trim(*trimChars)
        val key = head.trim().take(100)
        return if (key.length >= 2) key else ""
    }

    /** 把候选条目转成世界书条目。 */
    fun toEntry(c: Candidate, order: Int = 100): WorldEntry = WorldEntry(
        id = UUID.randomUUID().toString(),
        keys = c.keys,
        content = c.content,
        enabled = true,
        constant = false,
        comment = "自动编织",
        order = order
    )

    /** 把一条候选写进角色卡，返回新角色卡。 */
    fun apply(character: RoleCharacter, candidates: List<Candidate>): RoleCharacter {
        if (candidates.isEmpty()) return character
        val next = character.worldEntries.toMutableList()
        var order = (next.maxOfOrNull { it.order } ?: 90) + 10
        candidates.forEach { c ->
            if (next.none { it.keys.equals(c.keys, ignoreCase = true) }) {
                next.add(toEntry(c, order))
                order += 10
            }
        }
        return character.copy(worldEntries = next, updatedAt = System.currentTimeMillis())
    }

    private fun splitKeys(raw: String): List<String> =
        raw.split(',', '，', ';', '；', '\n').map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * 角色卡体检：像体检报告一样列出卡片的问题，并能一键修复。
 *
 * 酒馆原版不会告诉你「你的卡哪里写坏了」；这里把常见坑都查一遍。
 */
object RoleCardLint {

    data class Issue(
        val level: String,
        val title: String,
        val detail: String,
        val fixable: Boolean = false
    )

    private const val LEVEL_BAD = "问题"
    private const val LEVEL_WARN = "建议"
    private const val LEVEL_OK = "良好"

    fun levelBad(): String = LEVEL_BAD
    fun levelWarn(): String = LEVEL_WARN

    fun check(character: RoleCharacter): List<Issue> {
        val issues = mutableListOf<Issue>()

        if (character.persona.isBlank() && character.systemPrompt.isBlank()) {
            issues.add(Issue(LEVEL_BAD, "缺少人设", "人设和系统提示都是空的，角色会没有性格。"))
        }
        if (character.greeting.isBlank()) {
            issues.add(Issue(LEVEL_BAD, "缺少开场白", "第一次进入对话时没有开场，体验会很空。"))
        }
        if (character.description.isBlank() && character.intro.isBlank()) {
            issues.add(Issue(LEVEL_WARN, "缺少简介", "角色列表里会看不到这个角色的介绍。"))
        }
        if (character.examples.isEmpty() && character.mesExample.isBlank()) {
            issues.add(Issue(LEVEL_WARN, "缺少示例对白", "示例对白能明显提升说话风格的一致性。"))
        }
        if (character.scenario.isBlank()) {
            issues.add(Issue(LEVEL_WARN, "缺少场景", "没有场景时，模型容易漂移成通用助手。"))
        }
        if (character.userName.isBlank()) {
            issues.add(Issue(LEVEL_WARN, "未设置用户称呼", "角色不知道该怎么称呼你。", fixable = true))
        }
        if (character.persona.length > 8000) {
            issues.add(Issue(LEVEL_WARN, "人设过长", "人设 ${character.persona.length} 字，过长会挤占对话上下文。"))
        }

        // 世界书体检
        val world = character.worldEntries
        val dupKeys = world.flatMap { it.keys.split(',', '，', ';', '；') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .groupingBy { it.lowercase() }
            .eachCount()
            .filter { it.value > 1 }
            .keys
        if (dupKeys.isNotEmpty()) {
            issues.add(
                Issue(LEVEL_WARN, "世界书 key 重复", "这些触发词出现了多次：" + dupKeys.take(50).joinToString("、"))
            )
        }
        val emptyContent = world.count { it.content.isBlank() && it.keys.isNotBlank() }
        if (emptyContent > 0) {
            issues.add(
                Issue(LEVEL_BAD, "世界书条目为空", "有 $emptyContent 条只有触发词、没有内容，永远不会生效。", fixable = true)
            )
        }
        val noKeyNotConstant = world.count { it.keys.isBlank() && !it.constant }
        if (noKeyNotConstant > 0) {
            issues.add(
                Issue(LEVEL_WARN, "无触发词又非常驻", "有 $noKeyNotConstant 条既没有触发词、也没设常驻，永远匹配不到。", fixable = true)
            )
        }
        val tooMany = world.count { it.enabled }
        if (tooMany > 24) {
            issues.add(Issue(LEVEL_WARN, "世界书过多", "启用中的条目有 $tooMany 条，建议精简到 24 条以内。"))
        }

        // 开场白重复：备用里出现了和主开场白相同的，或备用之间互相重复
        val mainGreet = character.greeting.trim()
        val rawAlt = character.alternateGreetings.map { it.trim() }.filter { it.isNotEmpty() }
        val dupAlt = rawAlt.size != rawAlt.distinct().size || (mainGreet.isNotEmpty() && rawAlt.contains(mainGreet))
        if (dupAlt) {
            issues.add(Issue(LEVEL_WARN, "备用开场白重复", "备用开场白里有和主开场白重复（或彼此重复）的内容。", fixable = true))
        }

        // 宏检查
        val macroText = character.persona + character.greeting + character.systemPrompt + character.postHistory
        if (macroText.contains("{{user}}") && character.userName.isBlank()) {
            issues.add(Issue(LEVEL_BAD, "宏无法替换", "用了 {{user}} 但没设置用户称呼。", fixable = true))
        }

        if (issues.isEmpty()) {
            issues.add(Issue(LEVEL_OK, "卡片很完整", "人设、开场白、示例、世界书都齐了，可以直接开聊。"))
        }
        return issues
    }

    fun score(character: RoleCharacter): Int {
        var s = 100
        check(character).forEach {
            when (it.level) {
                LEVEL_BAD -> s -= 18
                LEVEL_WARN -> s -= 7
            }
        }
        return s.coerceIn(5, 100)
    }

    /** 一键修复：返回修好的角色卡 + 修复说明。 */
    fun autoFix(character: RoleCharacter): Pair<RoleCharacter, List<String>> {
        val fixes = mutableListOf<String>()
        var c = character

        if (c.userName.isBlank()) {
            c = c.copy(userName = "主人")
            fixes.add("补上用户称呼「主人」")
        }
        if (c.persona.isBlank() && c.systemPrompt.isBlank()) {
            val base = listOfNotNull(
                c.description.takeIf { it.isNotBlank() },
                c.intro.takeIf { it.isNotBlank() },
                "你是${c.name}。"
            ).joinToString(" ")
            c = c.copy(persona = base)
            fixes.add("用简介拼出一段基础人设")
        }
        if (c.greeting.isBlank()) {
            c = c.copy(greeting = "我是${c.name}。你来了。")
            fixes.add("补了一句默认开场白")
        }

        // 清掉空内容的世界书条目
        val before = c.worldEntries.size
        c = c.copy(worldEntries = c.worldEntries.filterNot { it.content.isBlank() && it.keys.isNotBlank() })
        if (c.worldEntries.size != before) fixes.add("删掉 ${before - c.worldEntries.size} 条空内容的世界书条目")

        // 无 key 又非常驻 → 设为常驻
        var converted = 0
        c = c.copy(worldEntries = c.worldEntries.map {
            if (it.keys.isBlank() && !it.constant) {
                converted++
                it.copy(constant = true)
            } else it
        })
        if (converted > 0) fixes.add("把 $converted 条无触发词的条目改成常驻")

        // 去掉重复的备用开场白
        val seen = mutableSetOf<String>()
        val dedup = mutableListOf<String>()
        c.alternateGreetings.forEach { g ->
            val t = g.trim()
            if (t.isNotEmpty() && t != c.greeting.trim() && seen.add(t)) dedup.add(t)
        }
        if (dedup.size != c.alternateGreetings.size) {
            c = c.copy(alternateGreetings = dedup)
            fixes.add("去掉重复的备用开场白")
        }

        // 世界书 key 去重
        var keyFixed = 0
        c = c.copy(worldEntries = c.worldEntries.map { e ->
            val parts = e.keys.split(',', '，', ';', '；').map { it.trim() }.filter { it.isNotEmpty() }
            val uniq = LinkedHashSet<String>()
            parts.forEach { uniq.add(it) }
            if (uniq.size != parts.size) keyFixed++
            e.copy(keys = uniq.joinToString(","))
        })
        if (keyFixed > 0) fixes.add("整理 $keyFixed 条世界书的重复触发词")

        if (fixes.isEmpty()) fixes.add("没有需要修复的问题")
        return c.copy(updatedAt = System.currentTimeMillis()) to fixes
    }
}