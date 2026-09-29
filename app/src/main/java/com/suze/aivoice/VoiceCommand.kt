package com.suze.aivoice

import java.util.Calendar

sealed class VoiceCommand {
    data class RemindAt(
        val atMillis: Long,
        val text: String,
        val repeatDaily: Boolean = false,
        val advanceMin: Int = 0
    ) : VoiceCommand()
    data class RemindIn(val atMillis: Long, val text: String) : VoiceCommand()
    data object ListReminders : VoiceCommand()
    data object CancelReminders : VoiceCommand()
    data class Search(val query: String) : VoiceCommand()
    data class AddTodo(val text: String) : VoiceCommand()
    data object ListTodos : VoiceCommand()
    data class DoneTodo(val text: String) : VoiceCommand()
    data object ClearTodos : VoiceCommand()
    data class Translate(val text: String, val target: String) : VoiceCommand()
    data class Convert(val amount: Double, val from: String, val to: String) : VoiceCommand()
    data class WorldClock(val place: String) : VoiceCommand()
    data object News : VoiceCommand()
    data object CalendarList : VoiceCommand()
    data class CalendarAdd(val atMillis: Long, val text: String) : VoiceCommand()
    data class FindChat(val query: String) : VoiceCommand()
    data object DailyBrief : VoiceCommand()
}

object VoiceCommandParser {
    fun parse(raw: String): VoiceCommand? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        parseNews(text)?.let { return it }
        parseBrief(text)?.let { return it }
        parseWorldClock(text)?.let { return it }
        parseTranslate(text)?.let { return it }
        parseConvert(text)?.let { return it }
        parseFindChat(text)?.let { return it }
        parseTodo(text)?.let { return it }
        parseCalendar(text)?.let { return it }
        parseSearch(text)?.let { return it }
        parseListOrCancel(text)?.let { return it }
        parseCountdown(text)?.let { return it }
        parseClock(text)?.let { return it }
        return null
    }

    private fun parseNews(text: String): VoiceCommand? {
        if (hit(text, "今天有什么新闻", "今日新闻", "新闻快讯", "最近新闻", "热点新闻", "播报新闻")) {
            return VoiceCommand.News
        }
        return null
    }

    private fun parseBrief(text: String): VoiceCommand? {
        if (hit(text, "今日摘要", "今天摘要", "今日概览", "今天安排", "早上好小沫")) {
            return VoiceCommand.DailyBrief
        }
        return null
    }

    private fun parseWorldClock(text: String): VoiceCommand.WorldClock? {
        if (!hit(text, "几点", "现在几点", "当地时间", "现在是几点")) return null
        if (hit(text, "提醒", "闹钟", "倒计时")) return null
        val place = listOf(
            "纽约", "洛杉矶", "旧金山", "伦敦", "巴黎", "东京", "首尔", "悉尼",
            "新加坡", "香港", "台北", "莫斯科", "迪拜", "北京", "上海"
        ).firstOrNull { text.contains(it) }
        if (place != null) return VoiceCommand.WorldClock(place)
        if (text.length <= 10 && hit(text, "现在几点", "几点了")) return VoiceCommand.WorldClock("北京")
        return null
    }

    private fun parseTranslate(text: String): VoiceCommand.Translate? {
        val m1 = Regex("(?:把|将)?(.+?)翻译成(.+)").find(text)
        if (m1 != null) {
            val src = m1.groupValues[1].trim()
            val dst = m1.groupValues[2].trim()
            if (src.isNotEmpty() && dst.isNotEmpty()) return VoiceCommand.Translate(src.take(300), dst)
        }
        val prefixes = listOf("翻译成英语", "翻译成英文", "翻译成日语", "翻译成韩语", "翻译一下", "翻译")
        for (p in prefixes) {
            if (text.startsWith(p)) {
                val rest = text.removePrefix(p).trim().trimStart('：', ':', '，', ',', ' ')
                if (rest.isNotEmpty()) {
                    val target = when {
                        p.contains("英") -> "英语"
                        p.contains("日") -> "日语"
                        p.contains("韩") -> "韩语"
                        else -> "英语"
                    }
                    return VoiceCommand.Translate(rest.take(300), target)
                }
            }
        }
        return null
    }

    private fun parseConvert(text: String): VoiceCommand.Convert? {
        if (!hit(text, "美元", "欧元", "日元", "英镑", "港币", "台币", "韩元", "人民币", "美金")) return null
        val m = Regex("(\\d+(?:\\.\\d+)?)\\s*(美元|美金|欧元|日元|英镑|港币|港元|台币|韩元|人民币|块钱|块)").find(text)
            ?: return null
        val amount = m.groupValues[1].toDoubleOrNull() ?: return null
        val from = m.groupValues[2]
        val to = when {
            hit(text, "人民币") && from != "人民币" && from != "块" && from != "块钱" -> "人民币"
            hit(text, "美元", "美金") && !from.contains("美") -> "美元"
            else -> if (from.contains("人民") || from == "块" || from == "块钱") "美元" else "人民币"
        }
        return VoiceCommand.Convert(amount, from, to)
    }

    private fun parseFindChat(text: String): VoiceCommand.FindChat? {
        val prefixes = listOf("对话里找", "聊天记录找", "找聊天", "搜索对话", "在对话里搜")
        for (p in prefixes) {
            if (text.contains(p)) {
                val q = text.replace(p, " ").replace(Regex("[：:,，]"), " ").trim()
                if (q.isNotEmpty()) return VoiceCommand.FindChat(q.take(40))
            }
        }
        return null
    }

    private fun parseTodo(text: String): VoiceCommand? {
        if (hit(text, "清空待办", "待办清空", "清空备忘")) return VoiceCommand.ClearTodos
        if (hit(text, "我的待办", "待办有哪些", "有哪些待办", "待办列表", "我待办有哪些", "备忘录")) {
            return VoiceCommand.ListTodos
        }
        val donePrefixes = listOf("完成待办", "待办完成", "勾掉待办", "待办勾掉")
        for (p in donePrefixes) {
            if (text.startsWith(p)) {
                val q = text.removePrefix(p).trim().trimStart('：', ':', ' ')
                if (q.isNotEmpty()) return VoiceCommand.DoneTodo(q.take(40))
            }
        }
        val addPrefixes = listOf("记住", "记一下", "记着", "待办", "备忘")
        for (p in addPrefixes) {
            if (text.startsWith(p)) {
                var q = text.removePrefix(p).trim().trimStart('：', ':', '，', ',', ' ')
                q = q.replace(Regex("^(帮我|给我|一下)"), "").trim()
                if (q.length in 1..80 && !q.contains("天气") && !looksLikeRemind(q) && !q.startsWith("密码")) {
                    return VoiceCommand.AddTodo(q)
                }
            }
        }
        return null
    }

    private fun parseCalendar(text: String): VoiceCommand? {
        if (hit(text, "我的日程", "有哪些日程", "日程列表", "日历安排", "今天有什么安排")) {
            return VoiceCommand.CalendarList
        }
        if (hit(text, "加到日历", "写入日历", "添加到日历", "记到日历")) {
            val clock = parseClock(
                text.replace("加到日历", "提醒我")
                    .replace("写入日历", "提醒我")
                    .replace("添加到日历", "提醒我")
                    .replace("记到日历", "提醒我")
            )
            if (clock != null) return VoiceCommand.CalendarAdd(clock.atMillis, clock.text)
        }
        return null
    }

    private fun parseSearch(text: String): VoiceCommand.Search? {
        val prefixes = listOf("搜一下", "搜一搜", "搜索", "查一下", "查一查", "查查", "百度一下")
        for (p in prefixes) {
            if (text.startsWith(p)) {
                val q = text.removePrefix(p).trim().trimStart('：', ':', '，', ',', ' ')
                if (q.isNotEmpty() && !q.contains("天气") && !q.contains("待办") && !q.contains("日程")) {
                    return VoiceCommand.Search(q.take(80))
                }
            }
        }
        if (text.startsWith("什么是") || text.startsWith("谁是")) {
            val q = text.removePrefix("什么是").removePrefix("谁是").trim()
            if (q.length in 1..40) return VoiceCommand.Search(q)
        }
        return null
    }

    private fun parseListOrCancel(text: String): VoiceCommand? {
        if (hit(text, "取消全部提醒", "取消所有提醒", "清空提醒", "提醒全取消")) {
            return VoiceCommand.CancelReminders
        }
        if (hit(text, "取消提醒", "删掉提醒", "关掉提醒") && !text.contains("分钟") && !text.contains("小时")) {
            return VoiceCommand.CancelReminders
        }
        if (hit(text, "我的提醒", "有哪些提醒", "提醒列表", "还有什么提醒", "查看提醒")) {
            return VoiceCommand.ListReminders
        }
        return null
    }

    private fun parseCountdown(text: String): VoiceCommand.RemindIn? {
        if (!looksLikeRemind(text)) return null
        val hourMin = Regex("(\\d{1,2})\\s*小时(?:零|又)?(\\d{1,2})\\s*分钟").find(text)
        if (hourMin != null) {
            val h = hourMin.groupValues[1].toInt()
            val m = hourMin.groupValues[2].toInt()
            val ms = (h * 60 + m) * 60_000L
            if (ms in 30_000L..24 * 3600_000L) {
                return VoiceCommand.RemindIn(System.currentTimeMillis() + ms, topic(text))
            }
        }
        val hour = Regex("(\\d{1,2})\\s*小时").find(text)
        if (hour != null && !text.contains("点")) {
            val h = hour.groupValues[1].toInt()
            val ms = h * 3600_000L
            if (ms in 30_000L..24 * 3600_000L) {
                return VoiceCommand.RemindIn(System.currentTimeMillis() + ms, topic(text))
            }
        }
        val min = Regex("(\\d{1,3})\\s*分钟").find(text)
        if (min != null) {
            val m = min.groupValues[1].toInt()
            val ms = m * 60_000L
            if (ms in 30_000L..12 * 3600_000L) {
                return VoiceCommand.RemindIn(System.currentTimeMillis() + ms, topic(text))
            }
        }
        val sec = Regex("(\\d{1,3})\\s*秒").find(text)
        if (sec != null && hit(text, "倒计时", "提醒", "叫我")) {
            val s = sec.groupValues[1].toInt().coerceAtLeast(10)
            return VoiceCommand.RemindIn(System.currentTimeMillis() + s * 1000L, topic(text))
        }
        return null
    }

    private fun parseClock(text: String): VoiceCommand.RemindAt? {
        if (!looksLikeRemind(text) && !hit(text, "每天", "重复")) return null
        val m = Regex("(?:早上|上午|中午|下午|晚上|傍晚)?\\s*(\\d{1,2})\\s*(?:点|:|：)\\s*(\\d{1,2})?").find(text)
            ?: return null
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].toIntOrNull() ?: 0
        if (hour !in 0..23 || minute !in 0..59) return null
        if ((text.contains("下午") || text.contains("晚上") || text.contains("傍晚")) && hour in 1..11) hour += 12
        if (text.contains("中午") && hour in 1..2) hour = 12
        val cal = Calendar.getInstance()
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        if (cal.timeInMillis <= System.currentTimeMillis() + 20_000L) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        if (text.contains("明天")) {
            val today = Calendar.getInstance()
            if (cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        val repeat = hit(text, "每天", "每日", "重复闹钟", "天天")
        val advance = when {
            text.contains("提前10分钟") || text.contains("提前十分钟") -> 10
            text.contains("提前5分钟") || text.contains("提前五分钟") -> 5
            else -> 0
        }
        return VoiceCommand.RemindAt(cal.timeInMillis, topic(text), repeat, advance)
    }

    private fun looksLikeRemind(text: String): Boolean {
        return hit(
            text,
            "提醒我", "叫我", "喊我", "闹钟", "倒计时",
            "分钟后", "小时后", "秒后", "点提醒", "点叫我"
        )
    }

    private fun topic(text: String): String {
        var t = text
        listOf(
            "提醒我", "叫我", "喊我", "设个闹钟", "定个闹钟", "设闹钟", "倒计时",
            "一下", "一下下", "每天", "每日", "重复", "提前5分钟", "提前五分钟",
            "提前10分钟", "提前十分钟", "加到日历", "写入日历", "添加到日历"
        ).forEach { t = t.replace(it, " ") }
        t = t.replace(Regex("\\d+\\s*(小时|分钟|秒|点|：|:)\\s*\\d*"), " ")
        t = t.replace(Regex("(早上|上午|中午|下午|晚上|傍晚|明天|今天|后天)"), " ")
        t = t.replace(Regex("[，。！？,\\.!\\?]+"), " ").trim()
        t = t.replace(Regex("\\s+"), " ").trim()
        return t.take(40).ifBlank { "到时间啦" }
    }

    private fun hit(text: String, vararg keys: String): Boolean =
        keys.any { text.contains(it) }
}