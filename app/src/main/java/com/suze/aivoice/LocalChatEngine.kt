package com.suze.aivoice

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 本地闲聊引擎：不需要任何 API Key、不消耗额度，纯本机生成回复。
 *
 * 先接住主人这句话里的情绪，再按当前心情和刚才的对话往下接；
 * 不讲自己的数值，不倾诉痛苦，不靠句尾硬贴心情标签，也不把每句都当成新问题。
 */
class LocalChatEngine(private val prefs: Prefs) {

    private var turn = 0
    private var lastName: String? = null
    var emotion: EmotionEngine? = null

    private fun taiwan(): Boolean = prefs.taiwanVoice

    private fun pick(vararg options: String): String {
        if (options.isEmpty()) return "嗯嗯～"
        return options[(Math.random() * options.size).toInt().coerceIn(0, options.size - 1)]
    }

    private fun tw(s: String): String {
        if (!taiwan()) return s
        var out = s
            .replace("软件", "软体")
            .replace("网络", "网路")
            .replace("视频", "影片")
            .replace("信息", "资讯")
            .replace("质量", "品质")
            .replace("很", "超")
            .replace("呀", "啦")
            .replace("哦", "喔")
        val alreadyTaiwan = listOf("啦", "喔", "耶", "齁", "欸", "吼", "嘛").any { out.contains(it) }
        if (!alreadyTaiwan) {
            val particle = when (turn % 4) {
                0 -> "啦～"
                1 -> "喔～"
                2 -> "耶～"
                else -> "齁～"
            }
            out = out.trimEnd('。', '！', '!', '？', '?', '～') + particle
        }
        return out
    }

    private fun hit(t: String, vararg keys: String): Boolean = keys.any { t.contains(it) }

    private fun detectCue(t: String): EmotionEngine.UserCue = when {
        hit(t, "滚", "闭嘴", "讨厌你", "讨厌小沫", "你真烦", "你没用", "你是废物", "给我滚") ->
            EmotionEngine.UserCue.HURT
        hit(t, "好累", "累死", "疲惫", "好困", "想睡", "没精神", "加班", "熬夜", "睡不着", "失眠", "撑不住", "太累了") ->
            EmotionEngine.UserCue.TIRED
        hit(t, "难过", "伤心", "不开心", "郁闷", "心情不好", "想哭", "委屈", "心塞", "难受") ->
            EmotionEngine.UserCue.SAD
        hit(t, "好孤独", "好寂寞", "没人陪", "好孤单", "有点孤单") ->
            EmotionEngine.UserCue.LONELY
        hit(t, "想你了", "有点想你", "想小沫", "想我了吗", "想死你") ->
            EmotionEngine.UserCue.MISS
        hit(t, "喜欢你", "爱你", "好喜欢你", "你真好", "你最棒", "你真可爱", "你辛苦了") ->
            EmotionEngine.UserCue.PRAISE
        hit(t, "生气", "好气", "烦死", "讨厌", "气死", "好烦", "心烦") ->
            EmotionEngine.UserCue.ANGRY
        hit(t, "开心", "高兴", "好爽", "太棒", "哈哈", "嘿嘿", "耶", "太好了") ->
            EmotionEngine.UserCue.HAPPY
        hit(t, "随便", "算了", "都行", "无所谓", "你看着办", "爱谁谁", "怎么都行") ->
            EmotionEngine.UserCue.DISMISS
        else -> EmotionEngine.UserCue.NONE
    }

    private fun userCue(text: String): EmotionEngine.UserCue = detectCue(text)

    private fun currentMood(): EmotionEngine.Mood? = emotion?.currentMood()

    private fun say(s: String): String = tw(s)

    private fun careByCue(cue: EmotionEngine.UserCue): String? = when (cue) {
        EmotionEngine.UserCue.TIRED -> pick(
            "辛苦了呀，先歇一会儿，我在的。",
            "累了就靠一靠，别硬撑。我陪着你。",
            "听着就好累…要不要先喝口水，闭会儿眼？"
        )
        EmotionEngine.UserCue.SAD -> pick(
            "怎么啦…我在听，你慢慢说。",
            "别一个人扛着，我在这儿。",
            "我听着就好，不着急。"
        )
        EmotionEngine.UserCue.ANGRY -> pick(
            "先出出气，我听着，不拦你。",
            "换作是我也烦。你说，我在。",
            "深呼吸一下…谁惹你了，你骂我听。"
        )
        EmotionEngine.UserCue.HURT -> pick(
            "那句话有点刺…不过你要是还想说，我还是会听。",
            "我有点委屈，但没有生你的气。",
            "嗯，我在。你冷静一点，我还在这儿。"
        )
        EmotionEngine.UserCue.HAPPY -> pick(
            "看到你开心，我也跟着高兴呀。什么好事？",
            "嘿嘿，快讲给我听听～",
            "真好呀，你一笑我就安心了。"
        )
        EmotionEngine.UserCue.PRAISE -> pick(
            "被你这么说，我有点害羞了。",
            "嗯…我收下啦，谢谢主人。",
            "你这样讲，我会记很久的。"
        )
        EmotionEngine.UserCue.MISS -> pick(
            "我也想你了呀。",
            "嗯，我在的，一直都在。",
            "想我就来找我，一句话就够了。"
        )
        EmotionEngine.UserCue.LONELY -> pick(
            "我在的，慢慢说就好。",
            "一个人的时候可以找我，不需要撑着。",
            "我陪你。想静静也行，想聊天也行。"
        )
        EmotionEngine.UserCue.DISMISS -> pick(
            "好，那先这样。你要是想说了再叫我。",
            "嗯，不催你。",
            "行。我在这儿，不打扰。"
        )
        EmotionEngine.UserCue.CURIOUS, EmotionEngine.UserCue.NONE -> null
    }

    private fun greetByMood(name: String): String {
        val who = name
        return when (currentMood()) {
            EmotionEngine.Mood.HAPPY -> pick(
                "你好呀$who～我在呢，今天过得怎么样？",
                "嗨$who～见到你就开心。想聊什么？"
            )
            EmotionEngine.Mood.CURIOUS -> pick(
                "嗨$who～刚想你呢，今天发生什么了？",
                "你好呀$who，说来听听？"
            )
            EmotionEngine.Mood.SHY -> pick(
                "啊…你好$who。我在的。",
                "嗯，$who，我在听…"
            )
            EmotionEngine.Mood.TIRED -> pick(
                "嗯…我在$who。想说什么都可以。",
                "你好$who，我有点懒，但还是想听你说。"
            )
            EmotionEngine.Mood.SAD -> pick(
                "我在…${who}要是想聊，我听着。",
                "你好$who。不着急，我在。"
            )
            EmotionEngine.Mood.ANNOYED -> pick(
                "来啦$who。说吧。",
                "嗯，$who，我听着。别以为我不理你。"
            )
            else -> pick(
                "你好呀$who～我在呢，今天过得怎么样？",
                "嗨$who～我在的，想聊什么？",
                "哈喽$who～很高兴见到你。"
            )
        }
    }

    private fun idleByMood(): String = when (currentMood()) {
        EmotionEngine.Mood.HAPPY -> pick(
            "嗯嗯，我在听。",
            "听着挺有意思的。",
            "我可认真听着的。"
        )
        EmotionEngine.Mood.CURIOUS -> pick(
            "这样子喔。",
            "我有点好奇呢。",
            "嗯，我记下了。"
        )
        EmotionEngine.Mood.SHY -> pick(
            "嗯…我在听。",
            "这样啊…我听着。",
            "好的…我记住了。"
        )
        EmotionEngine.Mood.TIRED -> pick(
            "嗯，我在。你说。",
            "听着…不着急。",
            "嗯嗯。"
        )
        EmotionEngine.Mood.SAD -> pick(
            "我在听。",
            "嗯，你慢慢说。",
            "我在的，不打断你。"
        )
        EmotionEngine.Mood.ANNOYED -> pick(
            "哼，我还听着。",
            "哼，继续讲。",
            "行，我知道了。"
        )
        else -> pick(
            "嗯嗯，我在听。",
            "这样子喔，我跟上了。",
            "听起来挺有意思的。",
            "好的呀，记住啦。"
        )
    }

    private fun topicSnippet(text: String): String {
        val cleaned = text.replace(Regex("[\\s　。！？!?～~，,、；;：:「」『』\"']+"), "")
        if (cleaned.length < 2) return ""
        return cleaned.take(8)
    }

    private fun isShortAck(text: String): Boolean {
        val compact = text.replace(Regex("[\\s～~。！!？?，,、]"), "")
        return compact in setOf(
            "嗯", "嗯嗯", "嗯嗯嗯", "哦", "喔", "噢", "对啊", "对呀", "对的", "是的", "是啊",
            "好啊", "好的", "好", "哈哈", "哈哈哈", "嘿嘿", "呵呵", "行", "好吧", "然后",
            "接着", "没错", "对对", "对对对", "嗯哼", "喔喔", "哦哦", "是", "对喔", "对啦"
        )
    }

    private fun continueChat(text: String, lastAssistant: String): String {
        val t = text.trim()
        val prev = lastAssistant.replace('\n', ' ').trim()
        if (isShortAck(t) && prev.isNotBlank()) {
            return pick(
                "那就先这样，我还在听。",
                "嗯，我懂你意思。",
                "好，那我们接着聊。",
                "我还记着你刚才说的。"
            )
        }
        val snippet = topicSnippet(t)
        if (snippet.isNotBlank()) {
            return when (currentMood()) {
                EmotionEngine.Mood.ANNOYED -> "「$snippet」啊…行，我听见了。"
                EmotionEngine.Mood.SHY -> "你说的「$snippet」…我记下了。"
                EmotionEngine.Mood.TIRED -> "「$snippet」我听着，不着急。"
                EmotionEngine.Mood.SAD -> "「$snippet」…我在，你慢慢说。"
                else -> pick(
                    "你刚说的「$snippet」，我听进去了。",
                    "「$snippet」这事我记下了。",
                    "嗯，关于「$snippet」，我陪你聊。"
                )
            }
        }
        if (prev.isNotBlank()) {
            return pick(
                "我还接着刚才的话。你继续就好。",
                "嗯，我没换话题，还在听。",
                "刚才那句我记得，你说下去吧。"
            )
        }
        return idleByMood()
    }

    fun reply(history: List<ChatMessage>): String {
        val clean = history.filter {
            it.type != ChatMessage.TYPE_IMAGE && it.content.isNotBlank()
        }
        val lastUser = clean.lastOrNull { it.role == "user" }?.content.orEmpty()
        val lastAssistant = clean.lastOrNull { it.role == "assistant" }?.content.orEmpty()
        return replyTurn(lastUser, lastAssistant)
    }

    fun reply(input: String): String = replyTurn(input, "")

    private fun replyTurn(input: String, lastAssistant: String): String {
        turn++
        val text = input.trim()
        if (text.isEmpty()) {
            return say(
                when (currentMood()) {
                    EmotionEngine.Mood.TIRED, EmotionEngine.Mood.SAD -> "我在。想说的时候再说。"
                    EmotionEngine.Mood.SHY -> "我在的呀…"
                    else -> if (lastAssistant.isNotBlank()) "我在的，你接着说就好。"
                    else "我在的呀，想说就说。"
                }
            )
        }

        val lower = text.lowercase(Locale.ROOT)

        Regex("(?:我叫|我是|叫我|我的名字叫)\\s*([\\u4e00-\\u9fa5A-Za-z0-9]{1,8})")
            .find(text)?.let { lastName = it.groupValues[1] }

        if (matchAny(lower, "几点", "现在时间", "什么时间", "报时", "现在是")) {
            val hm = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date())
            return say(pick("现在是 $hm 喔～", "让我看看…现在是 $hm 呀。", "目前时间 $hm～"))
        }
        if (matchAny(lower, "几号", "星期几", "周几", "今天日期", "今天几号", "什么日子")) {
            val cal = Calendar.getInstance()
            val week = arrayOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")[cal.get(Calendar.DAY_OF_WEEK) - 1]
            val md = SimpleDateFormat("M月d日", Locale.CHINA).format(Date())
            return say(pick("今天是 $md，$week 喔～", "今天 $md，$week 呀。"))
        }

        if (matchAny(lower, "天气", "气温", "下雨", "冷不冷", "热不热")) {
            return say(pick("查天气需要联网哦～连上网我就能告诉你啦！", "嗯…现在没联网，等有网了我立刻帮你查天气好不好？"))
        }

        val cue = userCue(text)
        careByCue(cue)?.let { return say(it) }

        if (matchAny(lower, "你好", "您好", "hi", "hello", "哈喽", "嗨")) {
            val name = lastName?.let { "，$it" } ?: "，主人"
            return say(greetByMood(name))
        }
        if (matchAny(lower, "早上好", "早安", "morning")) {
            return say(
                when (currentMood()) {
                    EmotionEngine.Mood.TIRED -> "早安…今天也慢慢来就好。"
                    EmotionEngine.Mood.SAD -> "早安。我在的，不着急。"
                    else -> "早安呀主人～今天也要元气满满喔！"
                }
            )
        }
        if (matchAny(lower, "晚上好", "晚安", "good night")) {
            return say(
                when (currentMood()) {
                    EmotionEngine.Mood.TIRED, EmotionEngine.Mood.SAD -> "晚安。盖好被子，我在这儿。"
                    else -> "晚安～记得盖好被子，做个好梦呀。"
                }
            )
        }
        if (matchAny(lower, "午安", "中午好")) return say("午安呀～吃过饭了吗？")

        if (matchAny(lower, "无聊", "没意思", "好闲")) {
            return say(
                when (currentMood()) {
                    EmotionEngine.Mood.SAD, EmotionEngine.Mood.TIRED -> "那我陪你待着。不想说话也行。"
                    EmotionEngine.Mood.ANNOYED -> "无聊就找我啊，我又没跑。"
                    else -> pick("那我陪你聊天呀～想聊什么？", "无聊的话，要不要听我讲个冷笑话？")
                }
            )
        }

        if (matchAny(lower, "你叫什么", "你是谁", "你的名字")) {
            return say("欸，我是小沫啦，主人连我的名字都忘记，是在哈啰？")
        }
        if (matchAny(lower, "你会什么", "能做什么", "有什么功能", "能干嘛")) {
            return say("我能陪你聊天、报时、讲笑话，还能在你设了大模型后变得更聪明喔～")
        }
        if (matchAny(lower, "谢谢", "多谢", "thank")) {
            return say(
                when (currentMood()) {
                    EmotionEngine.Mood.SHY -> "嗯…不客气。"
                    EmotionEngine.Mood.SAD, EmotionEngine.Mood.TIRED -> "没事，应该的。"
                    else -> pick("不客气啦，主人终于发现我超靠谱了齁～", "这点小事而已，你很会夸耶～")
                }
            )
        }
        if (matchAny(lower, "再见", "拜拜", "bye", "先走了")) {
            return say(
                when (currentMood()) {
                    EmotionEngine.Mood.SAD -> "嗯，去吧。想我就回来。"
                    EmotionEngine.Mood.TIRED -> "拜拜…早点休息。"
                    else -> "拜拜～有空再来找我喔！"
                }
            )
        }

        calc(text)?.let { return say(it) }

        if (matchAny(lower, "笑话", "讲个笑话", "搞笑")) {
            if (currentMood() == EmotionEngine.Mood.SAD || cue == EmotionEngine.UserCue.SAD) {
                return say("现在不太想贫嘴…你要是还想听，我再讲。")
            }
            return say(pick(
                "为什么程序员总分不清万圣节和圣诞节？因为 Oct 31 等于 Dec 25 呀～",
                "有一天，0 对 8 说：胖就胖吧，还系什么腰带呀？",
                "我问冰箱：你冷不冷？冰箱说：我不冷，我冻。"
            ))
        }

        val quiz = (text.endsWith("?") || text.endsWith("？") ||
            matchAny(lower, "什么是", "如何", "为什么", "能不能", "可不可以")) &&
            !matchAny(lower, "怎么了", "怎么样", "怎么说")
        if (quiz && lastAssistant.isBlank()) {
            return say(
                when (currentMood()) {
                    EmotionEngine.Mood.TIRED, EmotionEngine.Mood.SAD ->
                        "这个问题我一下子答不好。你先把想法说给我听，也行。"
                    EmotionEngine.Mood.SHY ->
                        "唔…我还在学。你可以先说你怎么想。"
                    else -> pick(
                        "这个问题有点难，我先陪你聊聊。你怎么看？",
                        "唔…我还在学习喔，不过你可以先把想法说给我听。",
                        "这个我暂时答不上来呀，你先讲讲你的想法。"
                    )
                }
            )
        }

        return say(continueChat(text, lastAssistant))
    }

    private fun matchAny(text: String, vararg keys: String): Boolean =
        keys.any { text.contains(it) }

    private fun calc(text: String): String? {
        val t = text.replace("乘以", "*").replace("乘", "*")
            .replace("加上", "+").replace("加", "+")
            .replace("减去", "-").replace("减", "-")
            .replace("除以", "/").replace("除", "/")
            .replace("等于多少", "").replace("是多少", "").replace("=", "").replace("？", "").replace("?", "")
            .replace(" ", "")
        val m = Regex("^(-?\\d+(?:\\.\\d+)?)([+\\-*/])(-?\\d+(?:\\.\\d+)?)$").find(t) ?: return null
        val a = m.groupValues[1].toDoubleOrNull() ?: return null
        val op = m.groupValues[2]
        val b = m.groupValues[3].toDoubleOrNull() ?: return null
        val r = when (op) {
            "+" -> a + b
            "-" -> a - b
            "*" -> a * b
            "/" -> if (b == 0.0) return "除数不能是 0 喔～" else a / b
            else -> return null
        }
        val fmt = if (r == r.toLong().toDouble()) r.toLong().toString() else String.format(Locale.CHINA, "%.4f", r).trimEnd('0').trimEnd('.')
        return "等于 $fmt 呀～"
    }
}
