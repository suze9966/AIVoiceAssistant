package com.suze.aivoice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 角色情绪快照。
 *
 * 与主聊天的 EmotionEngine 完全分开：这里是**每个角色独立**的长期情绪记忆，
 * 下次打开同一个角色时会接着上次的心情继续，而不是每次从零开始。
 */
data class RoleMood(
    val characterId: String,
    val mood: String = "平静",
    val affinity: Int = 0,
    val trust: Int = 0,
    val energy: Int = 70,
    val events: List<String> = emptyList(),
    val turns: Int = 0,
    val lastSeen: Long = 0L
) {
    fun summary(): String {
        val parts = mutableListOf<String>()
        parts.add("心情 $mood")
        parts.add("亲密 $affinity")
        parts.add("信任 $trust")
        parts.add("精力 $energy")
        return parts.joinToString(" · ")
    }

    fun bar(): String {
        fun cell(v: Int) = "█".repeat((v / 1000).coerceIn(0, 10)) + "░".repeat(10 - (v / 1000).coerceIn(0, 10))
        return "亲密 ${cell(affinity)} $affinity\n信任 ${cell(trust)} $trust\n精力 ${cell(energy)} $energy"
    }
}

/**
 * 情绪共振引擎：把角色的情绪当成**会生长、会回落、会记住**的活数据。
 *
 * 三条规则：
 *  1. 每轮对话按用户语气给亲密 / 信任加减分；
 *  2. 长时间不见面会自然回落（衰减），但不归零；
 *  3. 情绪事件只留最近若干条短句，拼进提示词时只说最近几条，避免提示词膨胀。
 */
class RoleMoodEngine(context: Context) {

    private val file = File(File(context.filesDir, "roles").apply { mkdirs() }, "moods.json")
    private val cache = mutableMapOf<String, RoleMood>()

    fun get(characterId: String): RoleMood {
        cache[characterId]?.let { return it }
        val loaded = loadAll()[characterId] ?: RoleMood(characterId = characterId)
        val decayed = decay(loaded)
        cache[characterId] = decayed
        return decayed
    }

    private fun decay(m: RoleMood): RoleMood {
        if (m.lastSeen <= 0L) return m
        val hours = ((System.currentTimeMillis() - m.lastSeen) / 3_600_000L).toInt()
        if (hours < 6) return m
        // 每 6 小时一档：精力回升、亲密缓慢回落（但不迅速归零）。
        val steps = (hours / 6).coerceAtMost(200)
        return m.copy(
            energy = (m.energy + steps * 60).coerceIn(0, 10000),
            affinity = (m.affinity - steps * 10).coerceIn(0, 10000),
            mood = if (m.energy > 8000) "精神" else m.mood
        )
    }

    /** 观察一轮对话，返回更新后的情绪。 */
    fun observe(characterId: String, userText: String, replyText: String): RoleMood {
        val now = get(characterId)
        var affinity = now.affinity
        var trust = now.trust
        var energy = (now.energy - 200).coerceAtLeast(0)

        val warm = listOf("谢谢", "喜欢", "辛苦", "陪你", "抱抱", "好可爱", "厉害", "开心", "爱你", "想你")
        val cold = listOf("讨厌", "滚", "闭嘴", "烦", "无聊", "垃圾", "笨", "别说了", "没用")
        val deep = listOf("为什么", "你觉得", "如果", "其实", "我记得", "以前", "将来", "害怕", "难过")

        warm.forEach { if (userText.contains(it)) { affinity += 300; trust += 200; energy += 100 } }
        cold.forEach { if (userText.contains(it)) { affinity -= 400; trust -= 300; energy -= 200 } }
        deep.forEach { if (userText.contains(it)) { trust += 200; energy += 100 } }
        if (userText.length >= 40) { trust += 100; affinity += 100 }

        affinity = affinity.coerceIn(0, 10000)
        trust = trust.coerceIn(0, 10000)
        energy = energy.coerceIn(0, 10000)

        val mood = when {
            energy <= 2000 -> "疲惫"
            affinity >= 8000 -> "亲昵"
            trust >= 7000 -> "信赖"
            affinity <= 1000 && trust <= 1000 -> "生疏"
            replyText.length > 120 -> "投入"
            else -> if (moodIsStale(now)) "平静" else now.mood
        }

        val event = eventOf(userText, affinity, trust)
        val events = (now.events + listOfNotNull(event)).takeLast(500)

        val updated = now.copy(
            mood = mood,
            affinity = affinity,
            trust = trust,
            energy = energy,
            events = events,
            turns = now.turns + 1,
            lastSeen = System.currentTimeMillis()
        )
        cache[characterId] = updated
        persist(updated)
        return updated
    }

    private fun moodIsStale(m: RoleMood): Boolean =
        System.currentTimeMillis() - m.lastSeen > 3_600_000L

    private fun eventOf(userText: String, affinity: Int, trust: Int): String? {
        val t = userText.trim().replace('\n', ' ')
        if (t.isEmpty()) return null
        val short = if (t.length > 120) t.take(120) + "…" else t
        return when {
            affinity >= 8000 -> "你说过「$short」，那一刻很近。"
            trust >= 7000 -> "你和我聊过「$short」，我记着。"
            affinity <= 1000 -> "「$short」——我们还有点生。"
            else -> "你说「$short」。"
        }
    }

    /** 拼进系统提示的情绪块。 */
    fun promptBlock(characterId: String): String {
        val m = get(characterId)
        if (m.turns <= 0 && m.events.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("【你的情绪状态】")
        sb.append("当前心情：").append(m.mood)
        sb.append("；对他的亲近度 ").append(m.affinity).append("/10000")
        sb.append("，信任度 ").append(m.trust).append("/10000")
        sb.append("，精力 ").append(m.energy).append("/10000。")
        if (m.events.isNotEmpty()) {
            sb.append("你记得的片段：")
            sb.append(m.events.takeLast(200).joinToString("；"))
            sb.append("。")
        }
        sb.append("请让情绪自然影响语气：亲近时更放松、更主动；生疏时保持礼貌的距离；疲惫时句子短一些。不要直接报数字。")
        return sb.toString()
    }

    fun setMood(characterId: String, mood: String): RoleMood {
        val m = get(characterId).copy(mood = mood, lastSeen = System.currentTimeMillis())
        cache[characterId] = m
        persist(m)
        return m
    }

    fun reset(characterId: String) {
        cache.remove(characterId)
        val all = loadAll().toMutableMap()
        all.remove(characterId)
        writeAll(all)
    }

    private fun loadAll(): Map<String, RoleMood> {
        val out = mutableMapOf<String, RoleMood>()
        try {
            if (!file.exists()) return out
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("characterId")
                if (id.isBlank()) continue
                val ev = mutableListOf<String>()
                o.optJSONArray("events")?.let { a ->
                    for (j in 0 until a.length()) a.optString(j).takeIf { it.isNotBlank() }?.let { ev.add(it) }
                }
                out[id] = RoleMood(
                    characterId = id,
                    mood = o.optString("mood", "平静"),
                    affinity = o.optInt("affinity", 0),
                    trust = o.optInt("trust", 0),
                    energy = o.optInt("energy", 70),
                    events = ev,
                    turns = o.optInt("turns", 0),
                    lastSeen = o.optLong("lastSeen", 0L)
                )
            }
        } catch (_: Exception) { }
        return out
    }

    private fun persist(m: RoleMood) {
        val all = loadAll().toMutableMap()
        all[m.characterId] = m
        writeAll(all)
    }

    private fun writeAll(all: Map<String, RoleMood>) {
        try {
            val arr = JSONArray()
            all.values.take(2000).forEach { m ->
                val ev = JSONArray()
                m.events.forEach { ev.put(it) }
                arr.put(
                    JSONObject()
                        .put("characterId", m.characterId)
                        .put("mood", m.mood)
                        .put("affinity", m.affinity)
                        .put("trust", m.trust)
                        .put("energy", m.energy)
                        .put("events", ev)
                        .put("turns", m.turns)
                        .put("lastSeen", m.lastSeen)
                )
            }
            file.writeText(arr.toString())
        } catch (_: Exception) { }
    }

    companion object {
        fun moodOptions(): List<String> =
            listOf("平静", "开心", "亲昵", "信赖", "害羞", "认真", "疲惫", "低落", "生疏", "投入")
    }
}