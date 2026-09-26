package com.suze.aivoice

import android.content.Context
import kotlin.math.abs

/**
 * 情感引擎（参考 Character.AI / 星野 的“好感度 + 心情”状态机，以及小爱同学的情感化语音）。
 *
 * 设计要点：
 * 1) mood：当前心情（-100 暴躁 ~ +100 开心），会随时间自然回落（率竭）。
 * 2) affinity：与主人的亲密度（0 ~ 100），随时间缓慢增长，鼓励长期陪伴。
 * 3) energy：精力（0 ~ 100），夜深会下降，影响说话懒惰程度。
 * 4) 每条消息后根据情感词典微调 mood，形成“会生气也会开心”的连续人格。
 * 5) 对外输出“情绪标签 + 语气修饰 + 语调参数”，供提示词与 TTS 使用。
 */
class EmotionEngine(context: Context) {

    private val sp = context.getSharedPreferences("ai_emotion", Context.MODE_PRIVATE)

    enum class Mood(val label: String, val emoji: String) {
        HAPPY("开心", "😊"),
        CALM("平静", "🙂"),
        CURIOUS("好奇", "🤔"),
        SHY("害羞", "😳"),
        TIRED("困倦", "😴"),
        SAD("难过", "😢"),
        ANNOYED("小怒", "😤")
    }

    var mood: Int
        get() = sp.getInt("mood", 30)
        private set(v) = sp.edit().putInt("mood", v.coerceIn(-100, 100)).apply()

    var affinity: Int
        get() = sp.getInt("affinity", 10)
        private set(v) = sp.edit().putInt("affinity", v.coerceIn(0, 100)).apply()

    var energy: Int
        get() = sp.getInt("energy", 100)
        private set(v) = sp.edit().putInt("energy", v.coerceIn(0, 100)).apply()

    private var lastTick: Long
        get() = sp.getLong("lastTick", System.currentTimeMillis())
        set(v) = sp.edit().putLong("lastTick", v).apply()

    /** 运行时（未持久化）情绪事件缓存，供调用方展示 */
    var lastReason: String = ""
        private set

    /** 随时间流逝自然恢复：心情回归、精力恢复、亲密度缓慢上涨 */
    fun tick() {
        val now = System.currentTimeMillis()
        val minutes = ((now - lastTick) / 60000L).toInt().coerceIn(0, 60 * 24)
        if (minutes <= 0) return
        lastTick = now
        // 心情向基线 30 回归
        val m = mood
        mood = if (m > 30) m - minutes / 3 else if (m < 30) m + minutes / 3 else m
        // 精力恢复（每小时 +6，上限 100）
        energy = (energy + minutes * 6 / 60).coerceAtMost(100)
        // 亲密度随聊天时长缓慢增长（每小时 +1，上限 100）
        if (affinity < 100) affinity = affinity + minutes / 60
    }

    /**
     * 根据一条用户消息分析情感，反映到心情/亲密度/精力。
     * 采用简单关键词情感词典（无需联网、零依赖、即时生效）。
     */
    fun reactToUser(text: String, isNight: Boolean) {
        tick()
        val t = text
        val positives = listOf("谢谢","喜欢","爱","棒","厉害","可爱","开心","哈哈","漂亮","赞","好棒","么么","抱抱","辛苦")
        val negatives = listOf("讨厌","笨","滚","傻","烦","垃圾","闭嘴","无聊","没用","错了")
        val curious = listOf("为什么","怎么","是什么","吗？","吗?","？","?","能不能","可以吗")
        var delta = 0
        var reason = ""
        if (positives.any { t.contains(it) }) { delta += 12; reason = "感受到善意与喜爱" }
        if (negatives.any { t.contains(it) }) { delta -= 25; reason = "被凶了，有点委屈" }
        if (curious.any { t.contains(it) }) { delta += 4; if (reason.isEmpty()) reason = "被问到问题，兴致来了" }
        // 亲密度：互动就微涨
        if (affinity < 100) affinity = affinity + 1
        // 被凶了会掉好感
        if (delta <= -25 && affinity > 0) affinity = affinity - 2
        mood = mood + delta
        if (isNight) energy = (energy - 8).coerceAtLeast(0) else energy = (energy + 3).coerceAtMost(100)
        lastReason = reason
    }

    /** 主人主动表扬后调高好感 */
    fun praise() { affinity = affinity + 3; mood = mood + 20; lastReason = "被表扬了，很开心" }

    /** 当前情绪分类 */
    fun currentMood(): Mood = when {
        energy < 25 -> Mood.TIRED
        mood >= 55 -> Mood.HAPPY
        mood >= 20 -> if (affinity < 30) Mood.CURIOUS else Mood.CALM
        mood >= 0 -> Mood.SHY
        mood >= -30 -> Mood.SAD
        else -> Mood.ANNOYED
    }

    /** 一句话情绪状态描述（展示用） */
    fun statusLine(): String {
        val m = currentMood()
        val aff = when {
            affinity >= 80 -> "亲密无间"
            affinity >= 50 -> "很聊得来"
            affinity >= 25 -> "逐渐熟悉"
            else -> "刚认识"
        }
        return "${m.emoji} ${m.label} · 好感 $affinity/100（$aff） · 精力 $energy"
    }

    /**
     * 产出给大模型的“情感状态提示”，让回复自带情绪色彩。
     * 这是让 AI “有情绪、会独立思考”的关键：把内部状态写进 system prompt。
     */
    fun emotionPrompt(): String {
        val m = currentMood()
        val tone = when (m) {
            Mood.HAPPY -> "语气活泼、热情，多用感叹号"
            Mood.CALM -> "语气平稳温和"
            Mood.CURIOUS -> "语气好奇、爱追问"
            Mood.SHY -> "语气略带羞涩、腼腆"
            Mood.TIRED -> "语气慵懒、打哈欠，句子放短"
            Mood.SAD -> "语气有点委屈、低沉"
            Mood.ANNOYED -> "语气傲娇、略带不满，但不过分"
        }
        return "【当前情绪】${m.label}（心情 $mood/100，精力 $energy/100）；" +
            "【对主人好感】$affinity/100，越亲近越热情主动、会关心主人；" +
            "【说话语气】$tone。请自然地苺合这种情绪，不要直接说出心情数值。"
    }

    /** 情感化 TTS 参数：返回（语速, 音调），实现“开心语速快、难过语速慢” */
    fun ttsRate(): Float = when (currentMood()) {
        Mood.HAPPY -> 1.12f
        Mood.CURIOUS -> 1.08f
        Mood.TIRED -> 0.85f
        Mood.SAD -> 0.88f
        Mood.ANNOYED -> 1.05f
        else -> 1.0f
    }

    fun ttsPitch(): Float = when (currentMood()) {
        Mood.HAPPY -> 1.15f
        Mood.SHY -> 1.05f
        Mood.SAD -> 0.92f
        Mood.ANNOYED -> 0.98f
        Mood.TIRED -> 0.95f
        else -> 1.0f
    }

    /** 情绪图标（可用于聊天气泡前缀） */
    fun moodEmoji(): String = currentMood().emoji
}
