package com.suze.aivoice

import java.util.Calendar
import java.util.Locale
import java.util.regex.Pattern

sealed class VoiceCommand {
    data class RemindAt(val atMillis: Long, val text: String) : VoiceCommand()
    data class RemindIn(val atMillis: Long, val text: String) : VoiceCommand()
    data object ListReminders : VoiceCommand()
    data object CancelReminders : VoiceCommand()
    data class Search(val query: String) : VoiceCommand()
}

object VoiceCommandParser {
    fun parse(raw: String): VoiceCommand? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        parseSearch(text)?.let { return it }
        parseListOrCancel(text)?.let { return it }
        parseCountdown(text)?.let { return it }
        parseClock(text)?.let { return it }
        return null
    }

    private fun parseSearch(text: String): VoiceCommand.Search? {
        val prefixes = listOf("搜一下", "搜一搜", "搜索", "查一下", "查一查", "查查", "百度一下")
        for (p in prefixes) {
            if (text.startsWith(p)) {
                val q = text.removePrefix(p).trim().trimStart('：', ':', '，', ',', ' ')
                if (q.isNotEmpty() && !q.contains("天气")) return VoiceCommand.Search(q.take(80))
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
        if (!looksLikeRemind(text)) return null
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
        return VoiceCommand.RemindAt(cal.timeInMillis, topic(text))
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
            "一下", "一下下"
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