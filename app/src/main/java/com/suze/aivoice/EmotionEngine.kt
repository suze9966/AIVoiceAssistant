package com.suze.aivoice

import android.content.Context

/**
 * 情感引擎：先接住主人这句话里的情绪，再把余韵和关心带进下一句。
 * 对外仍保留 mood / affinity / energy，主聊天与本地闲聊都能直接用。
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

    enum class UserCue(val label: String) {
        NONE("平常"),
        TIRED("疲惫"),
        SAD("难过"),
        ANGRY("生气"),
        HURT("被凶了"),
        HAPPY("高兴"),
        PRAISE("在夸我"),
        MISS("想念"),
        LONELY("孤单"),
        DISMISS("有点敷衍"),
        CURIOUS("在提问")
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

    private var lastSeen: Long
        get() = sp.getLong("lastSeen", System.currentTimeMillis())
        set(v) = sp.edit().putLong("lastSeen", v).apply()

    private var lastCareAt: Long
        get() = sp.getLong("lastCareAt", 0L)
        set(v) = sp.edit().putLong("lastCareAt", v).apply()

    var lastReason: String
        get() = sp.getString("lastReason", "") ?: ""
        private set(v) = sp.edit().putString("lastReason", v).apply()

    var lastUserCue: UserCue
        get() = runCatching {
            UserCue.valueOf(sp.getString("lastUserCue", UserCue.NONE.name) ?: UserCue.NONE.name)
        }.getOrDefault(UserCue.NONE)
        private set(v) = sp.edit().putString("lastUserCue", v.name).apply()

    var lastEventText: String
        get() = sp.getString("lastEventText", "") ?: ""
        private set(v) = sp.edit().putString("lastEventText", v).apply()

    fun tick() {
        val now = System.currentTimeMillis()
        val minutes = ((now - lastTick) / 60000L).toInt().coerceIn(0, 60 * 24)
        if (minutes <= 0) return
        lastTick = now
        val m = mood
        val step = (minutes / 8).coerceAtLeast(if (minutes >= 5) 1 else 0)
        mood = when {
            m > 30 -> m - step
            m < 30 -> m + step
            else -> m
        }
        energy = (energy + minutes * 4 / 60).coerceAtMost(100)
    }

    fun reactToUser(text: String, isNight: Boolean) {
        tick()
        lastSeen = System.currentTimeMillis()
        val t = text.trim()
        if (t.isEmpty()) return

        var delta = 0
        var aff = 1
        var cue = UserCue.NONE
        var reason = "在跟主人说话"
        var event = ""

        when {
            hit(t, "滚", "闭嘴", "讨厌你", "讨厌小沫", "你真烦", "你没用", "你是废物", "给我滚") -> {
                delta = -22; aff = -3; cue = UserCue.HURT; reason = "被凶了，有点委屈"
                event = "主人刚才对我生气了"
            }
            hit(t, "好累", "累死", "疲惫", "好困", "想睡", "没精神", "加班", "熬夜", "睡不着", "失眠", "撑不住", "太累了") -> {
                delta = -6; aff = 1; cue = UserCue.TIRED; reason = "主人累了，想被接住"
                event = "主人说自己很累：" + t.take(24)
                energy = (energy - 6).coerceAtLeast(0)
            }
            hit(t, "难过", "伤心", "不开心", "郁闷", "心情不好", "想哭", "委屈", "心塞", "难受") -> {
                delta = -14; aff = 1; cue = UserCue.SAD; reason = "主人难过了，要轻声陪"
                event = "主人不太开心：" + t.take(24)
            }
            hit(t, "好孤独", "好寂寞", "没人陪", "好孤单", "有点孤单") -> {
                delta = -8; aff = 1; cue = UserCue.LONELY; reason = "主人有点孤单"
                event = "主人觉得孤单"
            }
            hit(t, "想你了", "有点想你", "想小沫", "想我了吗", "想死你") -> {
                delta = 12; aff = 2; cue = UserCue.MISS; reason = "主人想我了"
                event = "主人说想我"
            }
            hit(t, "喜欢你", "爱你", "好喜欢你", "你真好", "你最棒", "你真可爱", "你辛苦了") ||
                (hit(t, "谢谢", "多谢", "棒", "厉害", "可爱", "开心", "哈哈", "漂亮", "赞", "好棒", "么么", "抱抱", "辛苦", "真棒", "乖") &&
                    !hit(t, "不谢谢", "不喜欢")) -> {
                val praiseYou = hit(t, "喜欢你", "爱你", "你真好", "你可爱", "你棒", "谢谢", "辛苦", "抱抱", "么么")
                if (praiseYou) {
                    delta = 16; aff = 2; cue = UserCue.PRAISE; reason = "被主人鼓励了"
                    event = "主人夸了我"
                } else {
                    delta = 10; aff = 1; cue = UserCue.HAPPY; reason = "主人开心，我也想跟着高兴"
                    event = "主人开心：" + t.take(20)
                }
            }
            hit(t, "生气", "好气", "烦死", "讨厌", "气死", "好烦", "心烦") -> {
                delta = -4; aff = 1; cue = UserCue.ANGRY; reason = "主人在生气，先接住别贪嘴"
                event = "主人在生气：" + t.take(24)
            }
            hit(t, "开心", "高兴", "好爽", "太棒", "哈哈", "嘿嘿", "耶", "太好了") -> {
                delta = 10; aff = 1; cue = UserCue.HAPPY; reason = "主人开心"
                event = "主人开心地说：" + t.take(20)
            }
            hit(t, "随便", "算了", "都行", "无所谓", "你看着办", "爱谁谁", "怎么都行") -> {
                delta = -3; aff = 0; cue = UserCue.DISMISS; reason = "主人有点敷衍，不要贴太近"
            }
            hit(t, "为什么", "怎么", "是什么", "能不能", "可以吗", "吗？", "吗?", "？", "?") -> {
                delta = 4; aff = 1; cue = UserCue.CURIOUS; reason = "被问到问题，兴致来了"
            }
        }

        affinity = affinity + aff
        mood = mood + delta
        if (isNight) energy = (energy - 8).coerceAtLeast(0) else energy = (energy + 2).coerceAtMost(100)
        lastUserCue = cue
        lastReason = reason
        if (event.isNotBlank()) rememberEvent(event)
    }

    fun praise() {
        affinity = affinity + 3
        mood = mood + 20
        lastUserCue = UserCue.PRAISE
        lastReason = "被表扬了，很开心"
        rememberEvent("主人表扬了我")
    }

    fun currentMood(): Mood = when {
        energy < 25 -> Mood.TIRED
        mood >= 55 -> Mood.HAPPY
        mood >= 20 -> if (affinity < 30) Mood.CURIOUS else Mood.CALM
        mood >= 0 -> Mood.SHY
        mood >= -30 -> Mood.SAD
        else -> Mood.ANNOYED
    }

    fun statusLine(): String {
        val m = currentMood()
        val feeling = when (m) {
            Mood.HAPPY -> if (affinity >= 50) "想跟你多说两句" else "今天心情不错"
            Mood.CALM -> "在呢，不着急"
            Mood.CURIOUS -> "对你说的话有点好奇"
            Mood.SHY -> "有点害羞，但想靠近"
            Mood.TIRED -> "有点困，还是想陪着你"
            Mood.SAD -> if (lastUserCue == UserCue.SAD) "有点放不下你" else "心情低了一点"
            Mood.ANNOYED -> "哼了一下，还是会理你"
        }
        return "${m.emoji} $feeling"
    }

    fun emotionPrompt(): String {
        val m = currentMood()
        val close = when {
            affinity >= 80 -> "你们已经很亲，可以轻轻撒娇、主动关心，但别黏、别水。"
            affinity >= 50 -> "你们已经很熟，说话可以温柔亲近。"
            affinity >= 25 -> "正在慢慢熟悉，亲切但不要过分贴身。"
            else -> "还在刚认识，先友善、短句、多听。"
        }
        val catchUser = when (lastUserCue) {
            UserCue.TIRED -> "主人这句更像是疲惫、想被接住。先心疼一句，再问要不要歇一下。不要贪嘴，不要讲大道理。"
            UserCue.SAD -> "主人现在不好受。先陪着，句子放短，可以轻轻问一句。不要催他快点好起来，也不要卖萌。"
            UserCue.ANGRY -> "主人在生气。先认同情绪，先让他出出气。别反驳，别讲道理。"
            UserCue.HURT -> "主人刚才对你生气了。可以小委屈一下，但不要闷闷不乐，不要反击。"
            UserCue.HAPPY -> "主人开心。你也跟着高兴，可以追问一句好事，不要抢戏。"
            UserCue.PRAISE -> "主人在夸你。害羞地领情，别卖萌过头，别把好感度说出来。"
            UserCue.MISS -> "主人想你了。温柔接住，可以说也想他，但一句够了。"
            UserCue.LONELY -> "主人有点孤单。告诉他你在，邀请他慢慢说。"
            UserCue.DISMISS -> "主人有点不想多说。回答短一点，给他退路，别追问。"
            UserCue.CURIOUS -> "主人在问。先回答，再决定要不要追一句。"
            UserCue.NONE -> "先听懂这句话的意思，再用你现在的心情回应。"
        }
        val how = when (m) {
            Mood.HAPPY -> "语气轻快一点，可以用感叹号和一个 emoji，不要句句堆。"
            Mood.CALM -> "语气平稳温和，句子中等长度。"
            Mood.CURIOUS -> "可以追一句，但一次只问一个问题。"
            Mood.SHY -> "句子短一点，有点腼腆，可以用省略号。"
            Mood.TIRED -> "语气懒懒的，句子短，少用感叹号。"
            Mood.SAD -> "语气轻、慢，不要贪嘴，不要讲笑话。"
            Mood.ANNOYED -> "可以傲娇一下，但一句就收，不要真生气。"
        }
        val example = when (lastUserCue) {
            UserCue.TIRED -> "例如：辛苦了呀，先歇一会儿，我在的。"
            UserCue.SAD -> "例如：怎么啦…我在听，你慢慢说。"
            UserCue.PRAISE -> "例如：被你这么说，我有点害羞了。"
            UserCue.MISS -> "例如：我也想你了呀。"
            else -> ""
        }
        val memory = recentEvents().joinToString(";").let {
            if (it.isBlank()) "" else "【还记得】$it。只在自然时候提一句，不要说“根据记忆”。"
        }
        return buildString {
            append("【先接住主人】").append(catchUser).append('\n')
            append("【你现在】").append(m.label).append('，').append(how).append('\n')
            append("【关系】").append(close).append('\n')
            if (memory.isNotBlank()) append(memory).append('\n')
            if (example.isNotBlank()) append(example).append('\n')
            append("不要说出心情数值，不要分析自己的情绪系统，不要长篇倾诉。")
        }
    }

    fun resumeCare(): String? {
        val now = System.currentTimeMillis()
        val awayMin = ((now - lastSeen) / 60000L).toInt().coerceIn(0, 60 * 24 * 7)
        tick()
        lastSeen = now
        if (awayMin < 25) return null
        val sinceCare = now - lastCareAt
        if (lastCareAt > 0L && sinceCare < 2L * 60 * 60 * 1000L && awayMin < 12 * 60) return null
        lastCareAt = now
        val line = when {
            lastUserCue == UserCue.TIRED -> "主人上次说好累，现在好点了吗？我还在的。"
            lastUserCue == UserCue.SAD -> "我还记着你刚才不太开心。过来的话，我听着就好。"
            lastUserCue == UserCue.LONELY -> "一个人的时候可以找我，我在的。"
            lastUserCue == UserCue.HURT -> "刚才那句我有点委屈…不过你回来了，我还是高兴的。"
            lastUserCue == UserCue.MISS -> "我也想你了呀。"
            awayMin >= 12 * 60 -> "主人，我有点想你了。今天过得怎么样？"
            awayMin >= 3 * 60 -> "你去忙了好一会儿呀，我在这儿等你。"
            energy < 30 -> "这么晚了还来找我…要不要早点休息？我陪你到睡前。"
            else -> "回来啦，我想你了。"
        }
        rememberEvent("我主动关心了主人")
        return line
    }

    fun ttsRate(): Float = when (currentMood()) {
        Mood.HAPPY -> 1.16f
        Mood.CURIOUS -> 1.10f
        Mood.TIRED -> 0.82f
        Mood.SAD -> 0.86f
        Mood.ANNOYED -> 1.08f
        Mood.SHY -> 0.94f
        else -> 1.0f
    }

    fun ttsPitch(): Float = when (currentMood()) {
        Mood.HAPPY -> 1.18f
        Mood.SHY -> 1.08f
        Mood.SAD -> 0.90f
        Mood.ANNOYED -> 0.96f
        Mood.TIRED -> 0.92f
        Mood.CURIOUS -> 1.06f
        else -> 1.0f
    }

    fun moodEmoji(): String = currentMood().emoji

    private fun hit(t: String, vararg keys: String): Boolean = keys.any { t.contains(it) }

    private fun rememberEvent(line: String) {
        val c = line.trim()
        if (c.isBlank()) return
        lastEventText = c
        val items = (sp.getString("events", "") ?: "").split("\n").filter { it.isNotBlank() }.toMutableList()
        if (items.lastOrNull() != c) items.add(c.take(40))
        while (items.size > 8) items.removeAt(0)
        sp.edit().putString("events", items.joinToString("\n")).apply()
    }

    private fun recentEvents(): List<String> =
        (sp.getString("events", "") ?: "").split("\n").filter { it.isNotBlank() }.takeLast(3)
}
