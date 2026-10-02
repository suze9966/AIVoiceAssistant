package com.suze.aivoice

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一条聊天消息。
 *
 * @param role     "user" | "assistant" | "system"
 * @param content  文本内容；图片消息时存图片 URL
 * @param isMe     是否为使用者发送
 * @param type     消息类型：
 *                 - [TYPE_TEXT]  普通文字（含 emoji 时由气泡自动放大显示）
 *                 - [TYPE_EMOJI] 纯 emoji 表情（大字号显示，像微信表情）
 *                 - [TYPE_IMAGE] 图片表情包（气泡内显示网络图片）
 * @param at       发送时刻（毫秒）。默认取创建瞬间，气泡下方会显示小字日期时间。
 *                 旧数据没有该字段时读出来是 0，此时不显示时间。
 */
data class ChatMessage(
    val role: String,
    val content: String,
    val isMe: Boolean,
    val type: Int = TYPE_TEXT,
    val speakerId: String = "",
    val speakerName: String = "",
    val speakerEmoji: String = "",
    val at: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_TEXT = 0
        const val TYPE_EMOJI = 1
        const val TYPE_IMAGE = 2

        /**
         * 把时间戳格式化成气泡下方的小字。
         *
         * 智能化显示：
         *  - 今天      -> `HH:mm`
         *  - 昨天      -> `昨天 HH:mm`
         *  - 今年内    -> `M月d日 HH:mm`
         *  - 跨年份    -> `yyyy年M月d日 HH:mm`
         *
         * @return 空字符串表示不显示（at <= 0 的旧数据）
         */
        fun stamp(at: Long): String {
            if (at <= 0L) return ""
            val now = System.currentTimeMillis()
            val fmtDay = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
            val msgDay = fmtDay.format(Date(at))
            val today = fmtDay.format(Date(now))
            val hm = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(at))
            val yesterday = fmtDay.format(Date(now - 24L * 60 * 60 * 1000))
            return when (msgDay) {
                today -> hm
                yesterday -> "昨天 " + hm
                else -> {
                    val sameYear = SimpleDateFormat("yyyy", Locale.CHINA).format(Date(at)) ==
                            SimpleDateFormat("yyyy", Locale.CHINA).format(Date(now))
                    if (sameYear) SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(at))
                    else SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA).format(Date(at))
                }
            }
        }

        /**
         * 判断一段文本是否「纯 emoji」（去掉空白后，全部由 emoji / 常见符号构成）。
         * 是的话，气泡里用超大字号渲染，形成表情包效果。
         */
        fun isPureEmoji(text: String): Boolean {
            val t = text.trim()
            if (t.isEmpty()) return false
            var hasEmoji = false
            var i = 0
            while (i < t.length) {
                val cp = t.codePointAt(i)
                val cc = Character.charCount(cp)
                if (cp == 0x20 || cp == 0x09 || cp == 0x0A) {
                    i += cc; continue
                }
                if (cp == 0xFE0F || cp == 0x200D || cp == 0xFE0E) {
                    i += cc; continue
                }
                val isEmoji = (cp in 0x1F300..0x1FAFF) ||
                        (cp in 0x2600..0x27BF) ||
                        (cp in 0x1F000..0x1F2FF) ||
                        (cp in 0x2190..0x21FF) ||
                        (cp in 0x2B00..0x2BFF) ||
                        (cp in 0x1F1E6..0x1F1FF)
                if (!isEmoji) return false
                hasEmoji = true
                i += cc
            }
            return hasEmoji
        }
    }
}