package com.suze.aivoice

/**
 * 精确的 emoji 判定工具。
 *
 * 之前 [ChatMessage] 和 [TtsHelper] 各自用「大区间一刀切」判断 emoji，
 * 把 `→ ← ↑ ↓ ⇒ ↔ ✓ ✔ ✗ ★ ☆ ☑ ⚡` 这些**常用符号**也当成 emoji，
 * 导致：① 朗读时这些符号被无声剥掉；② 整条消息全是这类符号时被放大成超大字表情。
 *
 * 这里改成**精确判定**：只认真正的 emoji 码点区间，常用符号（箭头 / 勾叉 / 星号等）正常保留。
 */
object EmojiDetector {

    /** 单个码点是否为 emoji。 */
    fun isEmoji(cp: Int): Boolean {
        return when {
            // 组合/修饰符：变体选择符、零宽连接符、肤色修饰符、键帽等
            cp == 0xFE0F || cp == 0xFE0E || cp == 0x200D -> true
            cp in 0x1F3FB..0x1F3FF -> true              // 肤色修饰符
            cp == 0x20E3 -> true                        // 组合圈（键帽）

            // 真正的主力 emoji 区（表情、人物、动物、食物、交通、旗帜等）
            cp in 0x1F300..0x1FAFF -> true
            cp in 0x1F000..0x1F2FF -> true

            // 区域指示符（国旗由两个组合）
            cp in 0x1F1E6..0x1F1FF -> true

            // 杂项符号与绘文字：只取确实是 emoji 的部分，跳过常用符号
            //   U+2600..U+26FF 混装着 ☀☂⚡ 等 emoji 与 ★☆✓✗ 等普通符号
            //   用「确实被 Unicode 定义为 Emoji 的码点」白名单
            //   注意：表情符号（带 U+FE0F 变体）走上面的组合符判定；
            //         裸字符一律按「普通符号」保留，宁可多留不误删。
            cp in EMOJI_2600 -> true

            // 装饰符号 U+2700..U+27BF：✓✔✗✘ 是普通符号，只认真正的 emoji
            cp in EMOJI_2700 -> true

            // 补充符号 U+2B00..U+2BFF：只认 emoji（⭐⬛⬜ 等），箭头类不算
            cp in EMOJI_2B00 -> true

            else -> false
        }
    }

    /**
     * 一段文本去掉空白后，是否「全由 emoji 构成」。
     * 注意：`→ ✓ ★` 这类常用符号**不算** emoji，因此不会被判成纯 emoji 放大。
     */
    fun isPureEmoji(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        var hasEmoji = false
        var i = 0
        while (i < t.length) {
            val cp = t.codePointAt(i)
            val cc = Character.charCount(cp)
            when {
                cp == 0x20 || cp == 0x09 || cp == 0x0A -> { i += cc; continue }
                isEmoji(cp) -> { hasEmoji = true; i += cc; continue }
                else -> return false
            }
        }
        return hasEmoji
    }

    /** 常见 CJK 全角/半角标点与常用符号：这些永远不该被当 emoji 剥掉。 */
    private val EMOJI_2600 = setOf(
        // 特意 **不包含** U+2611(☑) U+2610(☐) U+2612(☒) —— 复选框符号常作文字用
        0x2600, 0x2601, 0x2602, 0x2603, 0x2604, 0x260E, 0x2614, 0x2615,
        0x2618, 0x261D, 0x2620, 0x2622, 0x2623, 0x2626, 0x262A, 0x262E, 0x262F,
        0x2638, 0x2639, 0x263A, 0x2640, 0x2642, 0x2648, 0x2649, 0x264A, 0x264B,
        0x264C, 0x264D, 0x264E, 0x264F, 0x2650, 0x2651, 0x2652, 0x2653, 0x265F,
        0x2660, 0x2663, 0x2665, 0x2666, 0x2668, 0x267B, 0x267E, 0x267F, 0x2692,
        0x2693, 0x2694, 0x2695, 0x2696, 0x2697, 0x2699, 0x269B, 0x269C, 0x26A0,
        0x26A1, 0x26A7, 0x26AA, 0x26AB, 0x26B0, 0x26B1, 0x26BD, 0x26BE, 0x26C4,
        0x26C5, 0x26C8, 0x26CE, 0x26CF, 0x26D1, 0x26D3, 0x26D4, 0x26E9, 0x26EA,
        0x26F0, 0x26F1, 0x26F2, 0x26F3, 0x26F4, 0x26F5, 0x26F7, 0x26F8, 0x26F9,
        0x26FA, 0x26FD, 0x261D, 0x265F, 0x267E
    )

    private val EMOJI_2700 = setOf(
        // ✂ ✈ ✉ ✊ ✋ ✌ ✍ ✏ ✒ ✝ ✡ ✨ ✳ ✴ ❄ ❇ ❌ ❎ ❓ ❔ ❕ ❗ ❤ ➕ ➖ ➗ ➡ ➰ ➿
        // 特意 **不包含** U+2713(✓) U+2714(✔) U+2717(✗) U+2718(✘) —— 它们是普通勾叉符号
        0x2702, 0x2705, 0x2708, 0x2709, 0x270A, 0x270B, 0x270C, 0x270D, 0x270F,
        0x2712, 0x271D, 0x2721, 0x2728, 0x2733, 0x2734, 0x2744,
        0x2747, 0x274C, 0x274E, 0x2753, 0x2754, 0x2755, 0x2757, 0x2763, 0x2764,
        0x2795, 0x2796, 0x2797, 0x27A1, 0x27B0, 0x27BF
    )

    private val EMOJI_2B00 = setOf(
        0x2B05, 0x2B06, 0x2B07, 0x2B1B, 0x2B1C, 0x2B50, 0x2B55
    )
}
