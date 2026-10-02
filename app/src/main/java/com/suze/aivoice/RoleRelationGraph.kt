package com.suze.aivoice

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 角色关系图谱：群聊里角色之间会**互相记住**对彼此的态度。
 *
 * 酒馆原版角色只跟用户对话，多角色同场时彼此是空气。
 * 这里给每条「A → B」的边一个动态关系状态（称呼/态度/亲密度），
 * 依据群聊里 A 的发言内容自动更新，下次同场时作为**关系上下文**注入提示词，
 * 让角色之间的互动连贯起来（比如上一场吵过架，这场还带着别扭）。
 */
class RoleRelationGraph(context: android.content.Context) {

    data class Edge(
        val from: String,
        val to: String,
        var label: String = "陌生",
        var attitude: Int = 0,      // -10000 敌对 .. 10000 挚友
        var interactions: Int = 0,
        var lastNote: String = "",
        var updatedAt: Long = 0L
    ) {
        fun summary(): String {
            val tone = when {
                attitude >= 6000 -> "很亲近"
                attitude >= 2500 -> "友好"
                attitude <= -6000 -> "势不两立"
                attitude <= -2500 -> "有芥蒂"
                else -> "平淡"
            }
            val note = if (lastNote.isBlank()) "" else "（$lastNote）"
            return "$to：$label，$tone$note"
        }

        /** 亲密度进度条，-100..100 映射到 10 格 */
        fun bar(): String {
            val filled = ((attitude + 10000).toLong() * 10 / 20000).toInt().coerceIn(0, 10)
            return "█".repeat(filled) + "░".repeat(10 - filled)
        }

        fun toJson(): JSONObject = JSONObject().apply {
            put("from", from); put("to", to); put("label", label)
            put("attitude", attitude); put("interactions", interactions)
            put("lastNote", lastNote); put("updatedAt", updatedAt)
        }

        companion object {
            fun fromJson(o: JSONObject): Edge = Edge(
                from = o.optString("from"),
                to = o.optString("to"),
                label = o.optString("label", "陌生"),
                attitude = o.optInt("attitude", 0),
                interactions = o.optInt("interactions", 0),
                lastNote = o.optString("lastNote", ""),
                updatedAt = o.optLong("updatedAt", 0L)
            )
        }
    }

    private val dir = File(context.filesDir, "roles").apply { mkdirs() }
    private val file = File(dir, "relations.json")
    private val cache = HashMap<String, MutableMap<String, Edge>>()

    private val warmWords = listOf("谢谢", "喜欢", "亲近", "信任", "朋友", "帮你", "一起", "抱歉", "抱歉啦", "别怕", "保护")
    private val coldWords = listOf("别烦", "讨厌", "滚", "闭嘴", "废物", "愚蠢", "笨", "没用", "威胁", "小心点")

    private fun load(): MutableMap<String, MutableMap<String, Edge>> {
        if (cache.isNotEmpty()) return cache
        if (!file.exists()) return cache
        runCatching {
            val root = JSONObject(file.readText())
            root.keys().forEach { from ->
                val inner = root.optJSONObject(from) ?: return@forEach
                val m = HashMap<String, Edge>()
                inner.keys().forEach { to ->
                    inner.optJSONObject(to)?.let { m[to] = Edge.fromJson(it) }
                }
                if (m.isNotEmpty()) cache[from] = m
            }
        }
        return cache
    }

    private fun persist() {
        runCatching {
            val root = JSONObject()
            cache.forEach { (from, inner) ->
                val o = JSONObject()
                inner.forEach { (to, e) -> o.put(to, e.toJson()) }
                root.put(from, o)
            }
            file.writeText(root.toString())
        }
    }

    /** 取 A 对 B 的边，不存在则新建 */
    fun edge(from: String, to: String): Edge {
        val m = load().getOrPut(from) { HashMap() }
        return m.getOrPut(to) { Edge(from = from, to = to) }
    }

    /** A 对 B 的全部关系（用于展示） */
    fun outEdges(from: String): List<Edge> = load()[from]?.values?.sortedByDescending { it.updatedAt } ?: emptyList()

    /**
     * 群聊中「说话者 speaker」发了 text，观察在场其他人 onStage 是否被提及/评价。
     * 只有明确点到名字才算一次互动，避免误伤。
     */
    fun observeGroup(speaker: String, text: String, onStage: List<String>, allNames: List<String>) {
        val targets = HashSet<String>()
        // 1) 明确 @ 名字
        onStage.filter { it != speaker }.forEach { name -> if (text.contains(name)) targets.add(name) }
        // 2) 代词指向：只有一个其他人时，「你」就算指向他
        val others = onStage.filter { it != speaker }
        if (others.size == 1 && (text.contains("你") || text.contains("您"))) targets.add(others.first())
        if (targets.isEmpty()) return
        val warm = warmWords.any { text.contains(it) }
        val cold = coldWords.any { text.contains(it) }
        targets.forEach { other ->
            val e = edge(speaker, other)
            e.interactions++
            e.updatedAt = System.currentTimeMillis()
            when {
                cold -> { e.attitude = (e.attitude - 1200).coerceAtLeast(-10000); e.lastNote = "刚刚有些冲突"; e.label = if (e.attitude <= -6000) "敌对" else "不快" }
                warm -> { e.attitude = (e.attitude + 800).coerceAtMost(10000); e.lastNote = "刚刚很友好"; e.label = when { e.attitude >= 6000 -> "挚友"; e.attitude >= 2500 -> "朋友"; else -> "熟悉" } }
                else -> { e.attitude += if (e.attitude > 0) 100 else if (e.attitude < 0) -100 else 100; if (e.label == "陌生") e.label = "认识" }
            }
            e.lastNote = clampNote(text)
        }
        persist()
    }

    private fun clampNote(text: String): String {
        val t = text.replace("\n", " ").trim()
        return if (t.length <= 1000) t else t.take(1000) + "…"
    }

    /**
     * 生成给「当前说话角色」的关系提示块，只取他主动关注/互动过的其他人，最多 6 条。
     */
    fun promptBlock(speaker: String, onStage: List<String>): String {
        val related = onStage.filter { it != speaker }
            .map { edge(speaker, it) }
            .filter { it.interactions > 0 }
            .sortedByDescending { it.updatedAt }
            .take(300)
        if (related.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("【你与其他角色的关系】\n")
        related.forEach { sb.append("· ").append(it.summary()).append("\n") }
        sb.append("（请自然地体现这些态度，不要直接报数值）")
        return sb.toString()
    }

    /** 全部关系（做图谱展示用） */
    fun allEdges(): List<Edge> = load().values.flatMap { it.values }.sortedByDescending { it.updatedAt }

    fun clear() {
        cache.clear()
        runCatching { file.delete() }
    }
}
