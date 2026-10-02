package com.suze.aivoice

import android.content.Context
import android.graphics.Typeface

/**
 * 聊天字体设置：字号 / 字形 / 行距。
 *
 * 与气泡样式（BubbleStyleStore）解耦：
 *  - 气泡样式管「框」：颜色、圆角、描边
 *  - 这里管「字」：多大、什么体、行多疏
 *
 * 主人调字号/字形时，无论是否用无框模式都生效。
 */
object ChatFontStore {

    // ---- 字号（sp） ----
    const val SIZE_MIN = 11f
    const val SIZE_MAX = 40f
    const val SIZE_DEFAULT = 16f

    // ---- 行距倍率 ----
    const val LINE_MIN = 0.9f
    const val LINE_MAX = 2.2f
    const val LINE_DEFAULT = 1.35f

    // ---- 字形 id ----
    const val FONT_DEFAULT = "default"   // 系统默认
    const val FONT_SERIF = "serif"       // 宋体 / 衬线
    const val FONT_SANS = "sans"         // 黑体 / 无衬线
    const val FONT_MONO = "mono"         // 等宽
    const val FONT_CURSIVE = "cursive"   // 手写

    data class Font(
        val sizeSp: Float = SIZE_DEFAULT,
        val lineMul: Float = LINE_DEFAULT,
        val family: String = FONT_DEFAULT,
        val bold: Boolean = false,
        val italic: Boolean = false
    ) {
        val isDefault: Boolean
            get() = sizeSp == SIZE_DEFAULT && lineMul == LINE_DEFAULT &&
                    family == FONT_DEFAULT && !bold && !italic
    }

    private const val KEY_SIZE = "chatFontSizeSp"
    private const val KEY_LINE = "chatFontLineMul"
    private const val KEY_FAMILY = "chatFontFamily"
    private const val KEY_BOLD = "chatFontBold"
    private const val KEY_ITALIC = "chatFontItalic"

    private fun sp(context: Context) =
        context.getSharedPreferences("ai_voice_prefs", Context.MODE_PRIVATE)

    fun load(context: Context): Font {
        val p = sp(context)
        return Font(
            sizeSp = p.getFloat(KEY_SIZE, SIZE_DEFAULT).coerceIn(SIZE_MIN, SIZE_MAX),
            lineMul = p.getFloat(KEY_LINE, LINE_DEFAULT).coerceIn(LINE_MIN, LINE_MAX),
            family = p.getString(KEY_FAMILY, FONT_DEFAULT) ?: FONT_DEFAULT,
            bold = p.getBoolean(KEY_BOLD, false),
            italic = p.getBoolean(KEY_ITALIC, false)
        )
    }

    fun save(context: Context, font: Font) {
        sp(context).edit()
            .putFloat(KEY_SIZE, font.sizeSp.coerceIn(SIZE_MIN, SIZE_MAX))
            .putFloat(KEY_LINE, font.lineMul.coerceIn(LINE_MIN, LINE_MAX))
            .putString(KEY_FAMILY, font.family)
            .putBoolean(KEY_BOLD, font.bold)
            .putBoolean(KEY_ITALIC, font.italic)
            .apply()
    }

    fun reset(context: Context) {
        sp(context).edit()
            .remove(KEY_SIZE).remove(KEY_LINE).remove(KEY_FAMILY)
            .remove(KEY_BOLD).remove(KEY_ITALIC).apply()
    }

    /** 解析成 Android Typeface。 */
    fun typeface(font: Font): Typeface {
        val base = when (font.family) {
            FONT_SERIF -> Typeface.SERIF
            FONT_SANS -> Typeface.SANS_SERIF
            FONT_MONO -> Typeface.MONOSPACE
            FONT_CURSIVE -> Typeface.create("cursive", Typeface.NORMAL)
            else -> Typeface.DEFAULT
        }
        var style = Typeface.NORMAL
        if (font.bold) style = style or Typeface.BOLD
        if (font.italic) style = style or Typeface.ITALIC
        return Typeface.create(base, style)
    }

    /** 行距：按字号换算，比固定 dp 更自然。 */
    fun lineSpacingExtra(font: Font): Float =
        (font.sizeSp * (font.lineMul - 1f)).coerceAtLeast(0f)

    // ---- 文字口令解析 ----

    fun sizeFromText(text: String): Float? {
        Regex("(\\d+(?:\\.\\d+)?)\\s*(?:号字|号|sp)").find(text)?.let {
            return it.groupValues[1].toFloatOrNull()?.coerceIn(SIZE_MIN, SIZE_MAX)
        }
        return when {
            containsAny(text, "特大字", "超大字", "巨大字") -> 28f
            containsAny(text, "大字", "放大字", "字号大") -> 22f
            containsAny(text, "中字") -> SIZE_DEFAULT
            containsAny(text, "小字", "字号小") -> 13f
            else -> null
        }
    }

    fun lineFromText(text: String): Float? = when {
        containsAny(text, "行距大", "稀疏", "宽松行距") -> 1.8f
        containsAny(text, "行距小", "紧凑", "紧密行距") -> 1.1f
        containsAny(text, "行距正常", "默认行距") -> LINE_DEFAULT
        else -> null
    }

    fun familyFromText(text: String): String? = when {
        containsAny(text, "宋体", "衬线", "serif") -> FONT_SERIF
        containsAny(text, "黑体", "无衬线", "sans") -> FONT_SANS
        containsAny(text, "等宽", "代码体", "mono") -> FONT_MONO
        containsAny(text, "手写体", "手写", "cursive") -> FONT_CURSIVE
        containsAny(text, "默认字体", "系统字体") -> FONT_DEFAULT
        else -> null
    }

    fun boldFromText(text: String): Boolean? = when {
        containsAny(text, "取消加粗", "正常粗细", "不要加粗") -> false
        containsAny(text, "加粗", "粗体", "bold") -> true
        else -> null
    }

    fun italicFromText(text: String): Boolean? = when {
        containsAny(text, "取消斜体", "正体", "不要斜体") -> false
        containsAny(text, "斜体", "倾斜", "italic") -> true
        else -> null
    }

    /** 无框模式口令：开 / 关。 */
    fun framelessFromText(text: String): Boolean? = when {
        containsAny(text, "取消无框", "关闭无框", "恢复气泡", "要气泡", "加回气泡") -> false
        containsAny(text, "无框", "去掉气泡", "取消气泡", "不要气泡", "去掉文本框", "取消文本框") -> true
        else -> null
    }

    private fun containsAny(text: String, vararg keys: String) = keys.any { text.contains(it) }

    fun familyLabel(id: String): String = when (id) {
        FONT_SERIF -> "宋体（衬线）"
        FONT_SANS -> "黑体（无衬线）"
        FONT_MONO -> "等宽"
        FONT_CURSIVE -> "手写体"
        else -> "系统默认"
    }

    fun describe(font: Font): String {
        val parts = mutableListOf<String>()
        parts.add("字号 ${font.sizeSp}sp")
        parts.add(familyLabel(font.family))
        if (font.bold) parts.add("加粗")
        if (font.italic) parts.add("斜体")
        parts.add("行距 ${font.lineMul} 倍")
        return parts.joinToString("、")
    }
}
