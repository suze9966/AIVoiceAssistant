package com.suze.aivoice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 学习成长引擎（MemoryEngine）
 *
 * 参考经验：
 *  - MemGPT / Letta：分层记忆（短期对话 <-> 长期沉淀），把重要的东西“记下来”。
 *  - Mem0：从对话中自动抽取原子记忆（偏好 / 事实 / 画像）。
 *  - Stanford Generative Agents：把零散观察沉淀为更高层的长期认知。
 *  - Duolingo / 养成类产品：用等级 + 经验值让成长“看得见”。
 *
 * 全部数据存在手机本地文件 memory.json，不依赖任何服务器。
 */
class MemoryEngine(context: Context) {

    private val sp = context.getSharedPreferences("ai_memory", Context.MODE_PRIVATE)
    private val file = File(context.filesDir, "memory.json")

    companion object {
        const val TYPE_PREF = "pref"     // 偏好：喜欢 / 讨厌
        const val TYPE_FACT = "fact"     // 事实：关于主人的信息
        const val TYPE_LEARN = "learn"   // 知识：学到的内容 / 技能
        private const val MAX_ITEMS = 10000
        private const val EXP_PER_LEVEL = 100
    }

    /** 单条记忆 */
    data class Mem(val type: String, val content: String, val weight: Int, val time: Long)

    private val items = mutableListOf<Mem>()

    /** 经验值（成长值），每轮对话都会增长 */
    var exp: Int
        get() = sp.getInt("exp", 0)
        private set(v) = sp.edit().putInt("exp", v).apply()

    /** 等级 = 经验 / 100 + 1 */
    val level: Int get() = exp / EXP_PER_LEVEL + 1

    /** 距离下一级还差多少经验 */
    val expToNext: Int get() = EXP_PER_LEVEL - (exp % EXP_PER_LEVEL)

    /** 累计对话轮数 */
    var turns: Int
        get() = sp.getInt("turns", 0)
        private set(v) = sp.edit().putInt("turns", v).apply()

    init {
        load()
    }

    // ---------------- 持久化 ----------------

    private fun load() {
        try {
            if (!file.exists()) return
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items.add(
                    Mem(
                        o.optString("type", TYPE_LEARN),
                        o.optString("content", ""),
                        o.optInt("weight", 1),
                        o.optLong("time", 0L)
                    )
                )
            }
        } catch (_: Exception) { }
    }

    private fun persist() {
        try {
            val arr = JSONArray()
            items.takeLast(MAX_ITEMS).forEach { m ->
                arr.put(
                    JSONObject()
                        .put("type", m.type)
                        .put("content", m.content)
                        .put("weight", m.weight)
                        .put("time", m.time)
                )
            }
            file.writeText(arr.toString())
        } catch (_: Exception) { }
    }

    // ---------------- 学习 ----------------

    /** 记住一条内容；相同内容合并权重而不是重复堆叠 */
    fun remember(type: String, content: String) {
        val c = content.trim()
        if (c.isBlank()) return
        val idx = items.indexOfFirst { it.content == c }
        if (idx >= 0) {
            val old = items[idx]
            items[idx] = old.copy(weight = old.weight + 1, time = System.currentTimeMillis())
        } else {
            items.add(Mem(type, c, 1, System.currentTimeMillis()))
            while (items.size > MAX_ITEMS) items.removeAt(0)
        }
        persist()
    }

    /**
     * 从主人一句话里“学习”：抽取偏好 / 事实。
     * 规则式冷启动（不需要额外模型调用），覆盖最常用的自述句式。
     */
    fun learnFromUser(text: String) {
        turns = turns + 1
        exp = exp + 10
        val t = text.trim()
        if (t.isBlank()) return

        // 1) 偏好：我喜欢 / 我最喜欢 / 我讨厌 / 我不喜欢 ...
        val prefPatterns = listOf("我喜欢", "我最喜欢", "我超喜欢", "我爱", "我讨厌", "我不喜欢", "我恨")
        for (k in prefPatterns) {
            val i = t.indexOf(k)
            if (i >= 0) {
                val v = t.substring(i + k.length).trim().take(400)
                if (v.isNotBlank()) remember(TYPE_PREF, "主人${k}${v}")
            }
        }

        // 2) 事实：我叫 / 我是 / 我的 ... 是 ... / 我在 ... / 我住 ...
        val factPatterns = listOf("我叫", "我的名字是", "我是", "我在", "我住在", "我来自", "我的生日")
        for (k in factPatterns) {
            val i = t.indexOf(k)
            if (i >= 0) {
                val v = t.substring(i).trim().take(500)
                if (v.length > k.length) remember(TYPE_FACT, v)
            }
        }

        // 3) 明确要求记住的：记住 / 别忘了
        if (t.contains("记住") || t.contains("别忘")) {
            remember(TYPE_FACT, "主人要求记住：" + t.take(500))
        }

        // 4) 情绪事件：下次还能自然接上，不靠挂机涨好感
        when {
            listOf("好累", "累死", "加班", "失眠", "睡不着", "太累", "熬夜").any { t.contains(it) } ->
                remember(TYPE_FACT, "主人最近很累：" + t.take(300))
            listOf("难过", "伤心", "想哭", "不开心", "委屈", "心情不好").any { t.contains(it) } ->
                remember(TYPE_FACT, "主人心情低落过：" + t.take(300))
            listOf("想你了", "有点想你", "想小沫").any { t.contains(it) } ->
                remember(TYPE_PREF, "主人会想念小沫")
            listOf("好孤独", "好寂寞", "没人陪", "好孤单").any { t.contains(it) } ->
                remember(TYPE_FACT, "主人说过自己有点孤单")
        }
    }

    /** 当 AI 学到新知识时调用 */
    fun learnKnowledge(content: String) {
        if (content.isBlank()) return
        exp = exp + 5
        remember(TYPE_LEARN, content.take(1000))
    }

    /** 成长提示：等级 + 熟悉度 + 记忆中提取的关键信息 */
    fun growthPrompt(): String {
        val lv = level
        val levelDesc = when {
            lv <= 1 -> "刚开始认识主人"
            lv <= 3 -> "已经比较熟悉主人"
            lv <= 6 -> "很了解主人的习惯了"
            lv <= 10 -> "和主人是很有默契的老朋友"
            else -> "像家人一样了解主人"
        }
        val prefs = items.filter { it.type == TYPE_PREF }.takeLast(1000).joinToString("；") { it.content }
        val facts = items.filter { it.type == TYPE_FACT }.takeLast(1000).joinToString("；") { it.content }
        val sb = StringBuilder()
        sb.append("【学习成长】你和主人已经聊了${turns}轮，当前成长等级 Lv.$lv（$levelDesc）。")
        sb.append("请在合适的时候自然体现出你记得主人，不要说“根据我的记忆”之类的机械话。")
        if (prefs.isNotBlank()) sb.append("记忆中主人的偏好：$prefs。")
        if (facts.isNotBlank()) sb.append("记忆中关于主人的事实：$facts。")
        return sb.toString()
    }

    /** 记忆摘要，供设置页 / 状态栏展示 */
    fun summary(): String {
        return "Lv.$level · 经验${exp} · 对话${turns}轮 · 记忆${items.size}条"
    }

    /** 从磁盘重新读入，设置页改完后主聊天要拿到最新条目 */
    fun reload() {
        items.clear()
        load()
    }

    fun listItems(): List<Mem> = items.toList()

    /** 按关键词找回本机记忆，不把整段聊天当记忆。 */
    fun search(query: String, limit: Int = 8): List<Mem> {
        val q = query.trim()
        if (q.isEmpty()) return items.takeLast(limit).reversed()
        val keys = q.split(Regex("[\\s,，、]+")).map { it.trim() }.filter { it.length >= 1 }
        return items.asReversed().filter { m ->
            m.content.contains(q, ignoreCase = true) ||
                keys.any { key -> m.content.contains(key, ignoreCase = true) }
        }.take(limit)
    }

    fun addManual(type: String, content: String): Boolean {
        val c = content.trim()
        if (c.isBlank()) return false
        remember(normalizeType(type), c.take(2000))
        return true
    }

    fun updateAt(index: Int, type: String, content: String): Boolean {
        if (index !in items.indices) return false
        val c = content.trim()
        if (c.isBlank()) return false
        val old = items[index]
        items[index] = old.copy(
            type = normalizeType(type),
            content = c.take(2000),
            time = System.currentTimeMillis()
        )
        persist()
        return true
    }

    fun deleteAt(index: Int): Boolean {
        if (index !in items.indices) return false
        items.removeAt(index)
        persist()
        return true
    }

    /** 导出为可读文本（本地备份用） */
    fun exportText(): String {
        val sb = StringBuilder()
        sb.append("等级 Lv.$level  经验 $exp  对话 $turns 轮\n")
        sb.append("----- 记忆（${items.size} 条）-----\n")
        items.forEach { sb.append("[").append(it.type).append("] ").append(it.content).append("\n") }
        return sb.toString()
    }

    /** 专用 JSON：含等级经验和全部条目，不是整包备份 */
    fun exportJson(): String {
        val arr = JSONArray()
        items.forEach { m ->
            arr.put(
                JSONObject()
                    .put("type", m.type)
                    .put("content", m.content)
                    .put("weight", m.weight)
                    .put("time", m.time)
            )
        }
        return JSONObject()
            .put("app", "xiaomo-memory")
            .put("version", 1)
            .put("exportedAt", System.currentTimeMillis())
            .put("exp", exp)
            .put("turns", turns)
            .put("items", arr)
            .toString()
    }

    /**
     * 从 JSON 导入。支持本页导出的对象，也支持旧的 memory.json 数组。
     * @return 写入条数；解析失败返回 -1
     */
    fun importJson(text: String, replace: Boolean): Int {
        val parsed = parseImport(text) ?: return -1
        val incoming = parsed.first
        if (replace) {
            items.clear()
            parsed.second?.let { exp = it.coerceAtLeast(0) }
            parsed.third?.let { turns = it.coerceAtLeast(0) }
            incoming.forEach { m ->
                val c = m.content.trim()
                if (c.isNotBlank()) {
                    items.add(
                        m.copy(
                            type = normalizeType(m.type),
                            content = c.take(2000)
                        )
                    )
                }
            }
            while (items.size > MAX_ITEMS) items.removeAt(0)
            persist()
            return items.size
        }
        var n = 0
        incoming.forEach { m ->
            val c = m.content.trim()
            if (c.isNotBlank()) {
                remember(normalizeType(m.type), c.take(2000))
                n++
            }
        }
        return n
    }

    /** 清空所有本地记忆 */
    fun clearAll() {
        items.clear()
        exp = 0
        turns = 0
        try { if (file.exists()) file.delete() } catch (_: Exception) { }
    }

    private fun normalizeType(type: String): String {
        return when (type.trim().lowercase()) {
            TYPE_PREF, "偏好", "preference" -> TYPE_PREF
            TYPE_FACT, "事实" -> TYPE_FACT
            TYPE_LEARN, "知识", "knowledge" -> TYPE_LEARN
            else -> TYPE_LEARN
        }
    }

    private data class ImportPack(
        val first: List<Mem>,
        val second: Int?,
        val third: Int?
    )

    private fun parseImport(text: String): ImportPack? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return try {
            if (trimmed.startsWith("[")) {
                ImportPack(parseArray(JSONArray(trimmed)), null, null)
            } else {
                val o = JSONObject(trimmed)
                val arr = o.optJSONArray("items") ?: o.optJSONArray("memories")
                val list = if (arr != null) parseArray(arr) else emptyList()
                if (list.isEmpty() && !o.has("exp") && !o.has("turns")) return null
                ImportPack(
                    list,
                    if (o.has("exp")) o.optInt("exp") else null,
                    if (o.has("turns")) o.optInt("turns") else null
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseArray(arr: JSONArray): List<Mem> {
        val list = mutableListOf<Mem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val content = o.optString("content").trim()
            if (content.isBlank()) continue
            list.add(
                Mem(
                    o.optString("type", TYPE_LEARN),
                    content,
                    o.optInt("weight", 1).coerceAtLeast(1),
                    o.optLong("time", 0L)
                )
            )
        }
        return list
    }
}
