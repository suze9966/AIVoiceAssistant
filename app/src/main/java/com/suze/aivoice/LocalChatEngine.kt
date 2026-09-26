package com.suze.aivoice

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 本地闲聊引擎：不需要任何 API Key、不消耗额度，纯本机生成回复。
 *
 * 用途：当未配置大模型（Base URL / API Key 为空）或网络请求失败时，
 * 由本引擎接管对话，保证「只要装了 App 就能聊天」。
 *
 * 设计：规则匹配（正则/关键词）+ 情绪陪伴 + 简单记忆 + 兜底话术，
 * 尽量口语化、像真人，且能配合 EmotionEngine 表现出不同语气。
 */
class LocalChatEngine(private val prefs: Prefs) {

    /** 对话轮数，用于让回复有变化、不呆板 */
    private var turn = 0

    /** 最近一次提到的名字/昵称（轻量记忆） */
    private var lastName: String? = null

    /** 情绪引擎（可空）：有则根据心情/好感度调整语气 */
    var emotion: EmotionEngine? = null

    /** 是否使用台湾腔说话风格 */
    private fun taiwan(): Boolean = prefs.taiwanVoice

    private fun pick(vararg options: String): String {
        if (options.isEmpty()) return "嗯嗯～"
        return options[(Math.random() * options.size).toInt().coerceIn(0, options.size - 1)]
    }

    /** 台湾腔语气词辅助 */
    private fun tw(s: String): String =
        if (taiwan()) s.replace("呀", "喔").replace("哦", "喔").replace("吗", "吗").replace("很", "超") else s

    /**
     * 主入口：根据用户输入生成一条本地回复。
     */
    fun reply(input: String): String {
        turn++
        val text = input.trim()
        if (text.isEmpty()) return tw("我在的呀，主人想说点什么？")

        val lower = text.lowercase(Locale.ROOT)

        // 记录称呼（“我叫/我是/叫我 XXX”）
        Regex("(?:我叫|我是|叫我|我的名字叫)\\s*([\\u4e00-\\u9fa5A-Za-z0-9]{1,8})")
            .find(text)?.let { lastName = it.groupValues[1] }

        // ===== 1) 时间 / 日期 =====
        if (matchAny(lower, "几点", "现在时间", "什么时间", "报时", "现在是")) {
            val hm = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date())
            return tw(pick("现在是 $hm 喔～", "让我看看…现在是 $hm 呀。", "目前时间 $hm～"))
        }
        if (matchAny(lower, "几号", "星期几", "周几", "今天日期", "今天几号", "什么日子")) {
            val cal = Calendar.getInstance()
            val week = arrayOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")[cal.get(Calendar.DAY_OF_WEEK) - 1]
            val md = SimpleDateFormat("M月d日", Locale.CHINA).format(Date())
            return tw(pick("今天是 $md，$week 喔～", "今天 $md，$week 呀。"))
        }

        // ===== 天气（离线兜底：提示需联网） =====
        if (matchAny(lower, "天气", "气温", "下雨", "冷不冷", "热不热")) {
            return tw(pick("查天气需要联网哦～连上网我就能告诉你啦！", "嗯…现在没联网，等有网了我立刻帮你查天气好不好？"))
        }
        // ===== 2) 问候 =====
        if (matchAny(lower, "你好", "您好", "hi", "hello", "哈喽", "嗨")) {
            val name = lastName?.let { "，$it" } ?: "，主人"
            return tw(pick("你好呀$name～我在呢，今天过得怎么样？", "嗨$name～我在的喔，想聊什么？", "哈喽$name～很高兴见到你。"))
        }
        if (matchAny(lower, "早上好", "早安", "morning")) return tw("早安呀主人～今天也要元气满满喔！")
        if (matchAny(lower, "晚上好", "晚安", "good night")) return tw("晚安～记得盖好被子，做个好梦呀。")
        if (matchAny(lower, "午安", "中午好")) return tw("午安呀～吃过饭了吗？")

        // ===== 3) 关心 / 情绪陪伴 =====
        if (matchAny(lower, "好累", "累死", "疲惫", "好困", "想睡")) {
            return tw(pick("辛苦啦，先歇一会儿吧，我陪着你～", "累了就靠一靠呀，别硬撑喔。"))
        }
        if (matchAny(lower, "难过", "伤心", "不开心", "郁闷", "心情不好", "想哭", "委屈")) {
            return tw(pick("怎么啦…跟我说说嘛，我在听。", "别难过呀，有我在呢，抱抱你～", "不开心的话，我陪你聊聊天好不好？"))
        }
        if (matchAny(lower, "开心", "高兴", "好爽", "太棒", "哈哈", "嘿嘿")) {
            return tw(pick("看到你开心我也超开心的呀！", "嘿嘿，什么好事呀？快讲给我听听～"))
        }
        if (matchAny(lower, "生气", "好气", "烦死", "讨厌", "气死")) {
            return tw(pick("别气啦别气啦，深呼吸～", "谁惹你啦？跟我说，我帮你骂他！"))
        }
        if (matchAny(lower, "无聊", "没意思", "好闲")) {
            return tw(pick("那我陪你聊天呀～想聊什么？", "无聊的话，要不要听我讲个冷笑话？"))
        }

        // ===== 4) 能力 / 身份类问答 =====
        if (matchAny(lower, "你叫什么", "你是谁", "你的名字")) {
            return tw("我是小沫呀，主人的贴心语音助手～")
        }
        if (matchAny(lower, "你会什么", "能做什么", "有什么功能", "能干嘛")) {
            return tw("我能陪你聊天、报时、讲笑话，还能在你设了大模型后变得更聪明喔～")
        }
        if (matchAny(lower, "谢谢", "多谢", "thank")) return tw(pick("不客气呀～", "嘿嘿，小事一桩！"))
        if (matchAny(lower, "再见", "拜拜", "bye", "先走了")) return tw("拜拜～有空再来找我喔！")

        // ===== 5) 简单计算（个位数四则运算） =====
        calc(text)?.let { return tw(it) }

        // ===== 6) 讲笑话 =====
        if (matchAny(lower, "笑话", "讲个笑话", "搞笑")) {
            return tw(pick(
                "为什么程序员总分不清万圣节和圣诞节？因为 Oct 31 等于 Dec 25 呀～",
                "有一天，0 对 8 说：胖就胖吧，还系什么腰带呀？",
                "我问冰箱：你冷不冷？冰箱说：我不冷，我冻。"
            ))
        }

        // ===== 7) 提问兜底（带问号） =====
        if (text.endsWith("?") || text.endsWith("？") ||
            matchAny(lower, "为什么", "怎么", "如何", "什么是", "能不能", "可不可以")) {
            return tw(pick(
                "这个问题有点难，等我接上大模型就懂啦～现在先陪你聊聊别的？",
                "唔…我还在学习喔，不过你可以先把想法说给我听。",
                "这个我暂时答不上来呀，等主人给我配个大脑就厉害啦！"
            ))
        }

        // ===== 8) 情绪引擎联动：根据心情决定结尾语气 =====
        val emo = emotion
        val moodTail = when {
            emo == null -> ""
            emo.mood >= 70 -> tw(" 今天心情超好的喔～")
            emo.mood <= 30 -> tw(" 唔…我有点点没精神呢。")
            else -> ""
        }

        // ===== 9) 通用兜底：重复/肯定/自由闲聊 =====
        return tw(pick(
            "嗯嗯，我在听～你继续说呀。",
            "这样子呀，然后呢？",
            "我懂你的意思喔～",
            "听起来挺有意思的，多跟我说说嘛。",
            "好的呀，记住啦～"
        )) + moodTail
    }

    /** 匹配任意关键词 */
    private fun matchAny(text: String, vararg keys: String): Boolean =
        keys.any { text.contains(it) }

    /** 简单四则运算：支持 “3+5”“12 减 4”“6乘7” 等 */
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
