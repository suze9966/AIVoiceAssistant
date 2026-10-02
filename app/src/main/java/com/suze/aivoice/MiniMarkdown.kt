package com.suze.aivoice

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.BulletSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * 轻量 Markdown 渲染器（不引第三方库，纯 Spannable 实现）。
 *
 * 主人希望小沫能像助手一样输出**结构化排版**，所以气泡里的文字现在会真正渲染：
 * - `# 标题` / `## 标题` → 加粗放大（一级更大）
 * - `**加粗**` / `*斜体*` / `__加粗__` → 字重变化
 * - `` `行内代码` `` → 等宽字体 + 淡底色
 * - ``` 围栏代码块 ``` → 等宽 + 淡底 + 整块左缩进
 * - `| 表格 |` → 按列对齐（用等宽字体保证不错位）
 * - `- 项目` / `1. 项目` → 圆点 / 序号 + 悬挂缩进
 * - `---` 分隔线 → 一行淡色横线
 * - `> 引用` → 左侧竖条 + 灰色字
 *
 * 设计取向：**渲染失败就当普通文本**，绝不让一条消息因为解析出错而消失。
 */
object MiniMarkdown {

    /** 行内代码底色。 */
    private const val CODE_BG = 0x14000000

    /** 引用条 / 分隔线颜色。 */
    private const val QUOTE_COLOR = 0xFF9AA0A6.toInt()

    /**
     * 把 Markdown 文本渲染成富文本。
     *
     * @param text 原始文本
     * @param baseColor 正文颜色（跟随气泡自定义颜色，保证对比度）
     */
    fun render(text: String, baseColor: Int = Color.BLACK, recallState: Boolean = false): CharSequence {
        if (text.isEmpty()) return text
        // 每渲染一条消息前重置「本回合是否已弹面板」标记
        PanelMemory.appearedThisMessage = false
        // 明显的非 Markdown 文本走快路径，省掉解析开销
        if (!mayContainMarkdown(text)) {
            // 快路径：原文里确实没有面板，但开启回填且历史有状态时，补上「当前状态」
            if (recallState && PanelMemory.has()) {
                return try {
                    renderInner(text, baseColor, recallState)
                } catch (e: Exception) {
                    text
                }
            }
            return text
        }
        return try {
            renderInner(text, baseColor, recallState)
        } catch (e: Exception) {
            // 解析永远不该让消息消失
            text
        }
    }

    /** 粗筛：没有这些符号就直接当纯文本。 */
    private fun mayContainMarkdown(t: String): Boolean =
        t.contains("**") || t.contains("*") || t.contains("`") || t.contains("#") ||
                t.contains("|") || t.contains("---") || t.contains("> ") ||
                t.contains("__") || t.contains("~~") || t.contains("\n- ") ||
                t.contains("\n1.") || t.startsWith("- ") || t.startsWith("#") ||
                t.contains("```panel") || t.contains("```status") ||
                t.contains("```系统面板") || t.contains("```面板") ||
                Regex("^\\d+[.)] ").containsMatchIn(t)

    private fun renderInner(text: String, baseColor: Int, recallState: Boolean = false): CharSequence {
        val sb = StringBuilder()
        val spans = mutableListOf<SpanSpec>()
        val lines = text.split('\n')

        var inFence = false
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // ---- 围栏：系统面板（```panel / ```status）优先识别 ----
            if (trimmed.startsWith("```")) {
                val rawLang = trimmed.removePrefix("```").trim()
                val lang = rawLang.lowercase()
                // 支持 ```panel / ```panel:血条 / ```面板:霓虹
                val styleName = if (rawLang.contains(":") || rawLang.contains("："))
                    rawLang.split(":", "：").drop(1).joinToString("").trim() else ""
                val head = lang.split(":", "：")[0].trim()
                if (!inFence && (head == "panel" || head == "status" ||
                            head == "系统面板" || head == "面板")) {
                    val body = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) {
                        body.add(lines[i])
                        i++
                    }
                    if (i < lines.size) i++   // 跳过结束围栏
                    appendPanel(sb, spans, body, baseColor, styleName)
                    continue
                }
                inFence = !inFence
                i++
                continue
            }
            if (inFence) {
                val start = sb.length
                sb.append(line)
                spans.add(SpanSpec(TypefaceSpan("monospace"), start, sb.length, 0))
                spans.add(SpanSpec(BackgroundColorSpan(CODE_BG), start, sb.length, 0))
                spans.add(SpanSpec(RelativeSizeSpan(0.92f), start, sb.length, 0))
                sb.append('\n')
                i++
                continue
            }

            // ---- 分隔线 ----
            if (isHr(trimmed)) {
                val start = sb.length
                sb.append("─".repeat(24))
                spans.add(SpanSpec(ForegroundColorSpan(QUOTE_COLOR), start, sb.length, 0))
                spans.add(SpanSpec(RelativeSizeSpan(0.7f), start, sb.length, 0))
                sb.append('\n')
                i++
                continue
            }

            // ---- 标题 ----
            val heading = headingLevel(trimmed)
            if (heading > 0) {
                val content = trimmed.drop(heading).trim().trimEnd('#').trim()
                val start = sb.length
                appendInline(sb, spans, content, baseColor)
                if (sb.length > start) {
                    spans.add(SpanSpec(StyleSpan(Typeface.BOLD), start, sb.length, 0))
                    spans.add(SpanSpec(
                        RelativeSizeSpan(if (heading == 1) 1.32f else 1.16f),
                        start, sb.length, 0
                    ))
                }
                sb.append('\n')
                i++
                continue
            }

            // ---- 表格：连续以 | 开头的行整块处理 ----
            if (trimmed.startsWith("|")) {
                val tableLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    tableLines.add(lines[i].trim())
                    i++
                }
                appendTable(sb, spans, tableLines, baseColor)
                continue
            }

            // ---- 引用 ----
            if (trimmed.startsWith(">")) {
                val content = trimmed.drop(1).trimStart()
                val start = sb.length
                appendInline(sb, spans, content, baseColor)
                if (sb.length > start) {
                    spans.add(SpanSpec(ForegroundColorSpan(QUOTE_COLOR), start, sb.length, 0))
                    spans.add(SpanSpec(LeadingMarginSpan.Standard(24), start, sb.length, 0))
                    spans.add(SpanSpec(StyleSpan(Typeface.ITALIC), start, sb.length, 0))
                }
                sb.append('\n')
                i++
                continue
            }

            // ---- 无序列表 ----
            val bullet = bulletText(trimmed)
            if (bullet != null) {
                val start = sb.length
                // 只由 appendInline 写入正文，避免内容出现两遍
                appendInline(sb, spans, bullet, baseColor)
                if (sb.length > start) {
                    spans.add(SpanSpec(BulletSpan(24), start, sb.length, 0))
                }
                sb.append('\n')
                i++
                continue
            }

            // ---- 有序列表 ----
            val ordered = orderedText(trimmed)
            if (ordered != null) {
                val start = sb.length
                // 只由 appendInline 写入正文，避免内容出现两遍
                appendInline(sb, spans, ordered, baseColor)
                if (sb.length > start) {
                    spans.add(SpanSpec(LeadingMarginSpan.Standard(32), start, sb.length, 0))
                }
                sb.append('\n')
                i++
                continue
            }

            // ---- 普通段落 ----
            val start = sb.length
            // 只由 appendInline 写入，避免正文重复
            appendInline(sb, spans, line, baseColor)
            sb.append('\n')
            i++
        }

        // 历史状态回填：本消息没弹过面板，但历史里有状态 → 补一个「当前状态（续）」
        if (recallState && !PanelMemory.appearedThisMessage && PanelMemory.has()) {
            if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
            val body = mutableListOf("📌 当前状态（续）")
            body.addAll(PanelMemory.fields)
            appendPanel(sb, spans, body, baseColor, PanelMemory.styleName, record = false)
        }

        // 去掉末尾多余换行
        while (sb.isNotEmpty() && sb.last() == '\n') sb.setLength(sb.length - 1)

        val out = SpannableString(sb.toString())
        for (spec in spans) {
            if (spec.start >= spec.end || spec.end > out.length) continue
            // 段落级样式（行底、缩进、项目符号）用 SPAN_PARAGRAPH，避免被行边界截断
            val flags = if (spec.what is LineBackgroundSpan ||
                spec.what is LeadingMarginSpan || spec.what is BulletSpan
            ) Spanned.SPAN_PARAGRAPH else Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            out.setSpan(spec.what, spec.start, spec.end, flags)
        }
        return out
    }

    private data class SpanSpec(val what: Any, val start: Int, val end: Int, val flags: Int)

    private fun isHr(t: String): Boolean {
        if (t.length < 3) return false
        val c = t[0]
        if (c != '-' && c != '*' && c != '_') return false
        return t.all { it == c } && t.length >= 3
    }

    private fun headingLevel(t: String): Int {
        var n = 0
        while (n < t.length && t[n] == '#' && n < 6) n++
        return if (n in 1..6 && n < t.length && t[n] == ' ') n else 0
    }

    /** `- xxx` / `* xxx` / `+ xxx` 返回内容，其它返回 null。 */
    private fun bulletText(t: String): String? {
        if (t.length < 3) return null
        val c = t[0]
        if ((c == '-' || c == '*' || c == '+') && t[1] == ' ') return t.drop(2)
        return null
    }

    /** `1. xxx` 返回内容，其它返回 null。 */
    private fun orderedText(t: String): String? {
        var i = 0
        while (i < t.length && t[i].isDigit()) i++
        if (i == 0 || i >= t.length - 1) return null
        if (t[i] != '.' && t[i] != ')') return null
        if (t[i + 1] != ' ') return null
        return t.drop(i + 2)
    }

    // ---------------- 系统面板（文字游戏状态栏） ----------------

    /** 面板整行底色。 */
    private const val PANEL_BG = 0x144A90D9

    /** 面板标题行底色。 */
    private const val PANEL_TITLE_BG = 0x2E4A90D9

    /** 进度条颜色档位。 */
    private const val BAR_HIGH = 0xFF43A047.toInt()
    private const val BAR_MID = 0xFFFB8C00.toInt()
    private const val BAR_LOW = 0xFFE53935.toInt()

    /** 进度条格数。 */
    private const val BAR_CELLS = 14

    /** 面板行的「键 = 值」分隔符。 */
    private val PANEL_SEP = Regex("\\s*[=:：]\\s*")

    /** 请求渐变条：`60/100 ~渐变`。 */
    private val TAG_GRADIENT = Regex("(?:~|～)\\s*(?:渐变|gradient)?", RegexOption.IGNORE_CASE)

    /** 请求迷你条：`62/100 mini`。 */
    private val TAG_MINI = Regex("(?:mini|迷你|短条)", RegexOption.IGNORE_CASE)

    /** 自定义阈值配色：`80/100 #FF5722#43A047`（低色#高色）。 */
    private val TAG_THRESH = Regex("#([0-9A-Fa-f]{6})\\s*#([0-9A-Fa-f]{6})")

    /** 字段副说明：`攻击 42 | 双手大剑`。 */
    private val PANEL_NOTE_SEP = Regex("\\s*[|｜]\\s*")

    /** 迷你条格数。 */
    private const val BAR_CELLS_MINI = 8


    /** 进度条数值形态：`当前/最大`。 */
    private val BAR_VALUE = Regex("^(\\d+)\\s*/\\s*(\\d+)$")

    /** 百分比形态：`80%`。 */
    private val PERCENT_VALUE = Regex("^(\\d+)\\s*%$")

    /** 外部模板解析器：由 App 注入（PanelStore），把「模板名」换成字段行。 */
    var externalTemplateResolver: ((String) -> List<String>?)? = null

    /** `"FF5722"` → 0xFFFF5722。解析失败返回 0。 */
    private fun parseHexColor(hex: String): Int =
        try { (0xFF000000.toInt() or hex.toLong(16).toInt()) } catch (e: Exception) { 0 }

    /** 两色线性插值，t ∈ [0,1]。 */
    private fun blend(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        val ar = (a shr 16) and 0xFF; val ag = (a shr 8) and 0xFF; val ab = a and 0xFF
        val br = (b shr 16) and 0xFF; val bg = (b shr 8) and 0xFF; val bb = b and 0xFF
        val r = (ar + (br - ar) * k).toInt().coerceIn(0, 255)
        val g = (ag + (bg - ag) * k).toInt().coerceIn(0, 255)
        val bl = (ab + (bb - ab) * k).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    /**
     * 系统面板：文字游戏里的状态卡。
     *
     * 第一行当标题；`键 = 当前/最大` 自动画进度条；其余当键值行。
     */
    private fun appendPanel(
        sb: StringBuilder,
        spans: MutableList<SpanSpec>,
        body: List<String>,
        baseColor: Int,
        styleName: String = "",
        record: Boolean = true
    ) {
        var rows = body.map { it.trim() }.filter { it.isNotEmpty() }
        if (rows.isEmpty()) return

        // 取样式：点名样式 → 猜名字（包含匹配）→ 默认
        val style = resolvePanelStyle(styleName)

        // 预设展开：标题行写 `preset:战斗` / `预设:战斗`，或标题本身就是预设名
        var titleRaw = rows.first().trim('-', '=', ' ', '#', '*', '【', '】')
        val presetKey = when {
            titleRaw.startsWith("preset:", true) -> titleRaw.substringAfter(':').trim()
            titleRaw.startsWith("预设:", true) || titleRaw.startsWith("预设：", true) ->
                titleRaw.substringAfter(':').substringAfter('：').trim()
            else -> ""
        }
        // 先查外部自定义模板（主人在设置里存的），再退回内置预设
        val external = if (presetKey.isNotEmpty())
            try { externalTemplateResolver?.invoke(presetKey) } catch (e: Exception) { null } else null
        if (external != null && external.isNotEmpty()) {
            rows = external
            titleRaw = external.first()
        } else {
            resolvePreset(presetKey)?.let { fields ->
                // 预设：标题用预设名，字段全部展开，等小沫填值（默认 0）
                val head = if (presetKey.isNotEmpty()) "$presetKey 状态" else titleRaw
                rows = listOf(head) + fields.map { "$it = 0/100" }
                titleRaw = head
            }
        }

        val title = titleRaw
        if (title.isNotEmpty()) {
            val start = sb.length
            sb.append(style.prefix).append(title).append(style.suffix)
            spans.add(SpanSpec(StyleSpan(Typeface.BOLD), start, sb.length, 0))
            spans.add(SpanSpec(RelativeSizeSpan(1.1f), start, sb.length, 0))
            spans.add(SpanSpec(LineBgSpan(style.titleBg), start, sb.length, 0))
            sb.append('\n')
        }

        val items = rows.drop(1).map { parsePanelRow(it) }
        // 状态记忆：仅记录「真实回合面板」，避免渲染历史消息时覆盖当前状态，
        // 也避免回填自身被反复记成新状态。
        if (record) {
            PanelMemory.remember(rows.drop(1), title, styleName)
            PanelMemory.appearedThisMessage = true
        }
        val keyWidth = items.maxOfOrNull { displayWidth(it.first) } ?: 0
        for ((key, rawValue) in items) {
            val start = sb.length
            sb.append(key)
            spans.add(SpanSpec(ForegroundColorSpan(QUOTE_COLOR), start, sb.length, 0))
            sb.append(" ".repeat((keyWidth - displayWidth(key)).coerceAtLeast(0) + 2))

            // 副说明：`攻击 42 | 双手大剑`
            val noteSplit = PANEL_NOTE_SEP.split(rawValue, limit = 2)
            var value = noteSplit[0].trim()
            val note = if (noteSplit.size == 2) noteSplit[1].trim() else ""

            // 尾部标记：自定义阈值配色 / 渐变 / 迷你条（顺序无关，逐个剥）
            var grad = false
            var mini = false
            var loColor = 0
            var hiColor = 0
            // 先剥「#低#高」（最常见在末尾），再剥 ~渐变 / mini，顺序无关都能认
            TAG_THRESH.find(value)?.let {
                loColor = parseHexColor(it.groupValues[1])
                hiColor = parseHexColor(it.groupValues[2])
                value = value.removeRange(it.range).trim()
            }
            TAG_GRADIENT.find(value)?.let { value = value.removeRange(it.range).trim(); grad = true }
            TAG_MINI.find(value)?.let { value = value.removeRange(it.range).trim(); mini = true }

            val bar = BAR_VALUE.find(value)
            val pctOnly = if (bar == null) PERCENT_VALUE.find(value) else null
            if (bar != null || pctOnly != null) {
                val cells = (if (mini) BAR_CELLS_MINI else style.cells).coerceIn(4, 40)
                val pct = if (bar != null) {
                    val cur = bar.groupValues[1].toIntOrNull() ?: 0
                    val max = bar.groupValues[2].toIntOrNull() ?: 0
                    if (max > 0) (cur * 100 / max).coerceIn(0, 100) else 0
                } else {
                    (pctOnly!!.groupValues[1].toIntOrNull() ?: 0).coerceIn(0, 100)
                }
                val filled = (pct * cells / 100.0).toInt().coerceIn(0, cells)

                if (grad) {
                    // 渐变条：每一格从低色渐变到高色（参考 ST-StatusTracking）
                    val gLow = if (loColor != 0) loColor else BAR_LOW
                    val gHigh = if (hiColor != 0) hiColor else style.color
                    for (c in 0 until cells) {
                        val cs = sb.length
                        sb.append(if (c < filled) style.full else style.empty)
                        val t = if (cells <= 1) 0f else c.toFloat() / (cells - 1)
                        val col = blend(gLow, gHigh, t)
                        spans.add(SpanSpec(ForegroundColorSpan(col), cs, sb.length, 0))
                    }
                } else {
                    // 单色条：自定义阈值优先，否则按 60/30 档位
                    val barStart = sb.length
                    sb.append(style.full.repeat(filled)).append(style.empty.repeat(cells - filled))
                    val col = when {
                        loColor != 0 && hiColor != 0 ->
                            if (pct >= 60) hiColor else if (pct >= 30) blend(loColor, hiColor, 0.5f) else loColor
                        pct >= 60 -> style.color
                        pct >= 30 -> BAR_MID
                        else -> BAR_LOW
                    }
                    spans.add(SpanSpec(ForegroundColorSpan(col), barStart, sb.length, 0))
                }
                sb.append("  ")
                val pctStart = sb.length
                sb.append(pct).append('%')
                spans.add(SpanSpec(ForegroundColorSpan(baseColor), pctStart, sb.length, 0))
            } else if (value.isNotEmpty()) {
                appendInline(sb, spans, value, baseColor)
            }

            if (note.isNotEmpty()) {
                val noteStart = sb.length
                sb.append("  ").append(note)
                spans.add(SpanSpec(ForegroundColorSpan(QUOTE_COLOR), noteStart, sb.length, 0))
                spans.add(SpanSpec(RelativeSizeSpan(0.85f), noteStart, sb.length, 0))
            }

            spans.add(SpanSpec(LineBgSpan(style.bodyBg), start, sb.length, 0))
            sb.append('\n')
        }
    }

    /** 按名字取样式：精确 → 包含 → 默认。 */
    private fun resolvePanelStyle(name: String): PanelStyle {
        val key = name.trim()
        if (key.isEmpty()) return PANEL_STYLES["默认"]!!
        // 随机：每次换一种，玩起来有惊喜
        if (key == "随机" || key.equals("random", true) || key == "随便") {
            return PANEL_STYLES.values.elementAt((Math.random() * PANEL_STYLES.size).toInt()
                .coerceIn(0, PANEL_STYLES.size - 1))
        }
        PANEL_STYLES[key]?.let { return it }
        // 模糊：样式名包含关键词，或关键词包含样式名
        for ((k, v) in PANEL_STYLES) {
            if (k.contains(key) || key.contains(k)) return v
        }
        return PANEL_STYLES["默认"]!!
    }

    /** 面板样式：一套进度条字符 + 配色 + 标题装饰。 */
    private data class PanelStyle(
        val full: String,
        val empty: String,
        val color: Int,
        val prefix: String,
        val suffix: String,
        val bodyBg: Int = PANEL_BG,
        val titleBg: Int = PANEL_TITLE_BG,
        /** 进度条格数，样式可自定义。 */
        val cells: Int = BAR_CELLS
    )

    /** 面板样式表：100 种，按名字取；名字不认识就退回「默认」。 */
    private val PANEL_STYLES: Map<String, PanelStyle> = mapOf(
        "默认" to PanelStyle("█", "░", 0xFF4A90D9.toInt(), "", ""),
        "血条" to PanelStyle("▓", "░", 0xFFE53935.toInt(), "❤ ", ""),
        "魔法" to PanelStyle("▒", "░", 0xFF7E57C2.toInt(), "✦ ", ""),
        "经验" to PanelStyle("■", "□", 0xFF43A047.toInt(), "◆ ", ""),
        "体力" to PanelStyle("▰", "▱", 0xFFFFB300.toInt(), "⚡ ", ""),
        "星星" to PanelStyle("★", "☆", 0xFFFFC107.toInt(), "✦ ", ""),
        "爱心" to PanelStyle("♥", "♡", 0xFFE91E63.toInt(), "❤ ", ""),
        "方块" to PanelStyle("▣", "▢", 0xFF00ACC1.toInt(), "◈ ", ""),
        "霓虹" to PanelStyle("▮", "▯", 0xFF00E5FF.toInt(), "◉ ", ""),
        "像素" to PanelStyle("█", "▒", 0xFF8BC34A.toInt(), "▔ ", ""),
        "火焰" to PanelStyle("▲", "△", 0xFFFF5722.toInt(), "🔥 ", ""),
        "冰霜" to PanelStyle("❄", "·", 0xFF81D4FA.toInt(), "❄ ", ""),
        "雷电" to PanelStyle("ϟ", "·", 0xFFFFEB3B.toInt(), "⚡ ", ""),
        "樱花" to PanelStyle("❀", "·", 0xFFFF80AB.toInt(), "✿ ", ""),
        "宝箱" to PanelStyle("◼", "◻", 0xFFFFA000.toInt(), "📦 ", ""),
        "金币" to PanelStyle("●", "○", 0xFFFFD54F.toInt(), "💰 ", ""),
        "宝石" to PanelStyle("♦", "♢", 0xFF00E676.toInt(), "💎 ", ""),
        "月光" to PanelStyle("☾", "☽", 0xFFB39DDB.toInt(), "🌙 ", ""),
        "太阳" to PanelStyle("☀", "○", 0xFFFF9800.toInt(), "☀ ", ""),
        "云朵" to PanelStyle("☁", "○", 0xFF90CAF9.toInt(), "☁ ", ""),
        "水滴" to PanelStyle("●", "○", 0xFF29B6F6.toInt(), "💧 ", ""),
        "叶子" to PanelStyle("❧", "·", 0xFF66BB6A.toInt(), "🍃 ", ""),
        "玫瑰" to PanelStyle("✹", "·", 0xFFEC407A.toInt(), "🌹 ", ""),
        "音符" to PanelStyle("♪", "·", 0xFFAB47BC.toInt(), "🎵 ", ""),
        "书本" to PanelStyle("▤", "▥", 0xFF8D6E63.toInt(), "📖 ", ""),
        "药水" to PanelStyle("⬤", "◯", 0xFF26A69A.toInt(), "🧪 ", ""),
        "钥匙" to PanelStyle("⚿", "·", 0xFFFFCA28.toInt(), "🔑 ", ""),
        "王冠" to PanelStyle("♛", "♕", 0xFFFFD700.toInt(), "👑 ", ""),
        "盾牌" to PanelStyle("⬢", "⬡", 0xFF78909C.toInt(), "🛡 ", ""),
        "利剑" to PanelStyle("†", "·", 0xFFB0BEC5.toInt(), "⚔ ", ""),
        "弓箭" to PanelStyle("➤", "·", 0xFF8D6E63.toInt(), "🏹 ", ""),
        "卷轴" to PanelStyle("≡", "·", 0xFFD7CCC8.toInt(), "📜 ", ""),
        "沙漏" to PanelStyle("▼", "▽", 0xFFBCAAA4.toInt(), "⏳ ", ""),
        "时钟" to PanelStyle("◷", "◶", 0xFF90A4AE.toInt(), "⏰ ", ""),
        "地图" to PanelStyle("▧", "▨", 0xFF81C784.toInt(), "🗺 ", ""),
        "罗盘" to PanelStyle("➤", "·", 0xFF4DB6AC.toInt(), "🧭 ", ""),
        "战旗" to PanelStyle("▴", "▵", 0xFFEF5350.toInt(), "🚩 ", ""),
        "城堡" to PanelStyle("▦", "▩", 0xFFA1887F.toInt(), "🏰 ", ""),
        "森林" to PanelStyle("♣", "♧", 0xFF388E3C.toInt(), "🌲 ", ""),
        "海洋" to PanelStyle("≈", "·", 0xFF0288D1.toInt(), "🌊 ", ""),
        "火山" to PanelStyle("▲", "·", 0xFFD84315.toInt(), "🌋 ", ""),
        "沙漠" to PanelStyle("▬", "─", 0xFFFBC02D.toInt(), "🏜 ", ""),
        "雪原" to PanelStyle("▭", "─", 0xFFE0F7FA.toInt(), "🏔 ", ""),
        "星空" to PanelStyle("✩", "·", 0xFF5C6BC0.toInt(), "🌌 ", ""),
        "彩虹" to PanelStyle("▰", "▱", 0xFFFF7043.toInt(), "🌈 ", ""),
        "闪电战" to PanelStyle("◤", "◥", 0xFFFFFF00.toInt(), "⚡ ", ""),
        "暗影" to PanelStyle("▓", "░", 0xFF424242.toInt(), "🌑 ", ""),
        "圣光" to PanelStyle("✧", "·", 0xFFFFF59D.toInt(), "✨ ", ""),
        "诅咒" to PanelStyle("✟", "·", 0xFF6A1B9A.toInt(), "💀 ", ""),
        "龙鳞" to PanelStyle("◈", "◇", 0xFF00897B.toInt(), "🐉 ", ""),
        "凤羽" to PanelStyle("❥", "·", 0xFFFF8A65.toInt(), "🦅 ", ""),
        "狼牙" to PanelStyle("▾", "▿", 0xFF757575.toInt(), "🐺 ", ""),
        "猫爪" to PanelStyle("●", "·", 0xFFFFB74D.toInt(), "🐾 ", ""),
        "兔耳" to PanelStyle("∧", "·", 0xFFF8BBD0.toInt(), "🐰 ", ""),
        "熊猫" to PanelStyle("◒", "◓", 0xFFEEEEEE.toInt(), "🐼 ", ""),
        "狐狸" to PanelStyle("◆", "◇", 0xFFFF7043.toInt(), "🦊 ", ""),
        "企鹅" to PanelStyle("◓", "◒", 0xFF37474F.toInt(), "🐧 ", ""),
        "青蛙" to PanelStyle("◉", "◎", 0xFF7CB342.toInt(), "🐸 ", ""),
        "花朵" to PanelStyle("✽", "·", 0xFFF06292.toInt(), "🌸 ", ""),
        "藤蔓" to PanelStyle("⌇", "·", 0xFF558B2F.toInt(), "🌿 ", ""),
        "蘑菇" to PanelStyle("◍", "○", 0xFFBCAAA4.toInt(), "🍄 ", ""),
        "蜜蜂" to PanelStyle("▥", "▤", 0xFFFFC107.toInt(), "🐝 ", ""),
        "蝴蝶" to PanelStyle("❃", "·", 0xFF9C27B0.toInt(), "🦋 ", ""),
        "鲸鱼" to PanelStyle("◠", "◡", 0xFF039BE5.toInt(), "🐳 ", ""),
        "鲨鱼" to PanelStyle("▲", "▽", 0xFF546E7A.toInt(), "🦈 ", ""),
        "海豚" to PanelStyle("⌒", "⌣", 0xFF00BCD4.toInt(), "🐬 ", ""),
        "企鹅冰" to PanelStyle("❅", "·", 0xFFB3E5FC.toInt(), "❄ ", ""),
        "岩浆" to PanelStyle("▰", "▱", 0xFFFF3D00.toInt(), "🌋 ", ""),
        "极光" to PanelStyle("▬", "─", 0xFF00E5FF.toInt(), "🌌 ", ""),
        "日出" to PanelStyle("☀", "·", 0xFFFFAB40.toInt(), "🌅 ", ""),
        "日落" to PanelStyle("☾", "·", 0xFFFF6E40.toInt(), "🌇 ", ""),
        "流星" to PanelStyle("➹", "·", 0xFF7986CB.toInt(), "☄ ", ""),
        "银河" to PanelStyle("✳", "·", 0xFF3F51B5.toInt(), "🌠 ", ""),
        "黑洞" to PanelStyle("●", "·", 0xFF212121.toInt(), "🕳 ", ""),
        "量子" to PanelStyle("◍", "◎", 0xFF00BFA5.toInt(), "⚛ ", ""),
        "机械" to PanelStyle("▣", "▢", 0xFF607D8B.toInt(), "⚙ ", ""),
        "齿轮" to PanelStyle("◘", "◙", 0xFF455A64.toInt(), "⚙ ", ""),
        "电路" to PanelStyle("▤", "·", 0xFF00E676.toInt(), "🔌 ", ""),
        "数据" to PanelStyle("▥", "·", 0xFF29B6F6.toInt(), "📊 ", ""),
        "代码" to PanelStyle("▧", "·", 0xFF66BB6A.toInt(), "💻 ", ""),
        "终端" to PanelStyle("▮", "·", 0xFF00FF00.toInt(), ">_ ", ""),
        "像素心" to PanelStyle("♥", "·", 0xFFFF1744.toInt(), "❤ ", ""),
        "复古" to PanelStyle("▩", "▦", 0xFF8D6E63.toInt(), "🎮 ", ""),
        "街机" to PanelStyle("▨", "▧", 0xFFFF4081.toInt(), "🕹 ", ""),
        "赛博" to PanelStyle("▰", "▱", 0xFFE040FB.toInt(), "🤖 ", ""),
        "蒸汽" to PanelStyle("◈", "◇", 0xFF795548.toInt(), "⚙ ", ""),
        "炼金" to PanelStyle("⬡", "⬢", 0xFF9E9D24.toInt(), "⚗ ", ""),
        "魔药" to PanelStyle("⬤", "◯", 0xFF00C853.toInt(), "🧪 ", ""),
        "符文" to PanelStyle("ᛟ", "·", 0xFF5E35B1.toInt(), "🔮 ", ""),
        "水晶" to PanelStyle("◆", "◇", 0xFF18FFFF.toInt(), "💠 ", ""),
        "星尘" to PanelStyle("✫", "·", 0xFFB388FF.toInt(), "✨ ", ""),
        "梦境" to PanelStyle("☁", "·", 0xFFCE93D8.toInt(), "💤 ", ""),
        "回忆" to PanelStyle("❀", "·", 0xFFF48FB1.toInt(), "📷 ", ""),
        "时光" to PanelStyle("◷", "·", 0xFFA1887F.toInt(), "⏳ ", ""),
        "命运" to PanelStyle("✵", "·", 0xFF7C4DFF.toInt(), "🎲 ", ""),
        "荣耀" to PanelStyle("✪", "✩", 0xFFFFD600.toInt(), "🏆 ", ""),
        "传说" to PanelStyle("❖", "·", 0xFFFF6F00.toInt(), "👑 ", ""),
        "神话" to PanelStyle("✵", "·", 0xFFFFD740.toInt(), "🌟 ", ""),
        "风暴" to PanelStyle("≋", "·", 0xFF4DD0E1.toInt(), "🌪 ", ""),
        "曙光" to PanelStyle("✺", "·", 0xFFFFF176.toInt(), "🌄 ", ""),
    )

    /** 面板行 → (键, 值)；没有分隔符时整行当值，键留空。 */
    private fun parsePanelRow(line: String): Pair<String, String> {
        val cleaned = line.trim('-', ' ', '*')
        val parts = PANEL_SEP.split(cleaned, limit = 2)
        return if (parts.size == 2) {
            parts[0].trim().trim('【', '】', '*', '`') to parts[1].trim().trim('*', '`')
        } else {
            "" to cleaned
        }
    }

    /** 整行底色：段落级 LineBackgroundSpan。 */
    private class LineBgSpan(private val color: Int) : LineBackgroundSpan {
        override fun drawBackground(
            canvas: Canvas,
            paint: Paint,
            left: Int,
            right: Int,
            top: Int,
            baseline: Int,
            bottom: Int,
            text: CharSequence,
            start: Int,
            end: Int,
            lineNumber: Int
        ) {
            val old = paint.color
            paint.color = color
            canvas.drawRect(
                (left - 8).toFloat(), top.toFloat(),
                (right + 8).toFloat(), bottom.toFloat(), paint
            )
            paint.color = old
        }
    }

    /** 表格：按 | 分列，用等宽字体 + 列宽对齐，让它在气泡里也整齐。 */
    // ---------------- 面板状态记忆 & 预设（参考 ST-StatusTracking 历史状态 / 配置管理） ----------------

    /**
     * 面板状态记忆：记住最近一次面板块的标题、样式和字段行。
     *
     * 对应 GitHub ST-StatusTracking 的「历史状态记忆」——小沫某回合忘了弹面板时，
     * 可以把最近一次状态回填到当前气泡底部，数值不会凭空断档。
     */
    object PanelMemory {
        /** 最近一次面板的原始字段行（`键 = 值`）。 */
        var fields: List<String> = emptyList()
            private set

        /** 最近一次面板的标题。 */
        var title: String = ""
            private set

        /** 最近一次面板的样式名。 */
        var styleName: String = ""
            private set

        /** 当前消息是否已经出现过面板块（本回合渲染期间临时标记）。 */
        var appearedThisMessage: Boolean = false

        fun remember(fields: List<String>, title: String, styleName: String) {
            if (fields.isEmpty()) return
            this.fields = fields
            this.title = title
            this.styleName = styleName
        }

        fun has(): Boolean = fields.isNotEmpty()

        fun clear() {
            fields = emptyList()
            title = ""
            styleName = ""
        }
    }

    /**
     * 面板预设：一句话就能套出整套字段（对应 stquickstatusbar 的「AI 生成模板」思路）。
     *
     * 用法：```panel 战斗  （标题行写该预设名，或者直接写 `preset:战斗`）
     */
    val PANEL_PRESETS: Map<String, List<String>> = mapOf(
        "战斗" to listOf("生命值", "魔力值", "怒气值", "防御力", "连击数", "必杀技"),
        "冒险" to listOf("生命值", "体力", "背包重量", "声望", "当前位置", "当前任务"),
        "养成" to listOf("等级", "经验值", "攻击力", "防御力", "敏捷", "智力"),
        "日常" to listOf("心情", "精力", "饥饿", "好感度", "当前地点", "今日天气"),
        "恋爱" to listOf("好感度", "心动值", "信任度", "亲密度", "关系阶段", "当前心情"),
        "探索" to listOf("生命值", "体力", "照明", "氧气", "探索进度", "发现物"),
        "对决" to listOf("我方生命", "敌方生命", "气势", "破防值", "剩余回合", "场上形势"),
        "经营" to listOf("金币", "口碑", "客流", "库存", "租金", "本月业绩"),
        "修仙" to listOf("气血", "灵力", "境界", "修为", "道心", "当前洞府"),
        "赛博" to listOf("生命值", "电量", "算力", "入侵进度", "防火墙", "当前节点")
    )

    /** 匹配预设名：精确 → 包含。 */
    fun resolvePreset(name: String): List<String>? {
        val k = name.trim()
        if (k.isEmpty()) return null
        PANEL_PRESETS[k]?.let { return it }
        for ((pk, v) in PANEL_PRESETS) {
            if (k.contains(pk) || pk.contains(k)) return v
        }
        return null
    }

    private fun appendTable(
        sb: StringBuilder,
        spans: MutableList<SpanSpec>,
        rows: List<String>,
        baseColor: Int
    ) {
        val cells = rows
            .filterNot { it.replace("|", "").replace("-", "").replace(":", "").trim().isEmpty() }
            .map { row ->
                row.trim().trim('|').split('|').map { it.trim() }
            }
        if (cells.isEmpty()) return
        val cols = cells.maxOf { it.size }.coerceIn(1, 6)
        // 每列取最大显示宽度
        val widths = IntArray(cols)
        for (r in cells) {
            for (c in 0 until cols) {
                widths[c] = maxOf(widths[c], displayWidth(r.getOrElse(c) { "" }))
            }
        }
        val gap = "  "
        for ((ri, r) in cells.withIndex()) {
            val lineStart = sb.length
            for (c in 0 until cols) {
                val raw = r.getOrElse(c) { "" }
                val pad = widths[c] - displayWidth(raw)
                val cellStart = sb.length
                // 用 appendInline 写入（同时处理 **加粗** / `代码` 等行内标记），
                // 注意不要再 sb.append(raw)，否则内容会重复一遍
                appendInline(sb, spans, raw, baseColor)
                if (ri == 0 && sb.length > cellStart) {
                    spans.add(SpanSpec(StyleSpan(Typeface.BOLD), cellStart, sb.length, 0))
                }
                if (c < cols - 1 || pad > 0) sb.append(" ".repeat(maxOf(pad, 0)))
                if (c < cols - 1) sb.append(gap)
            }
            spans.add(SpanSpec(TypefaceSpan("monospace"), lineStart, sb.length, 0))
            sb.append('\n')
        }
    }

    /** 粗略显示宽度：CJK 算 2，其余算 1（表格对齐足够用）。 */
    private fun displayWidth(s: String): Int {
        var w = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            w += if (isWideCodePoint(cp)) 2 else 1
        }
        return w
    }

    /** 是否为「双宽」码点：CJK 汉字/全角标点/假名/emoji 等。按码点判定，代理对不会被重复计数。 */
    private fun isWideCodePoint(cp: Int): Boolean {
        return when {
            cp in 0x1100..0x115F -> true            // 谚文字母
            cp in 0x2E80..0x303E -> true            // CJK 部首/符号
            cp in 0x3041..0x33FF -> true            // 假名/注音/CJK 兼容
            cp in 0x3400..0x4DBF -> true            // CJK 扩展 A
            cp in 0x4E00..0x9FFF -> true            // CJK 基本区
            cp in 0xA000..0xA4CF -> true            // 彝文
            cp in 0xAC00..0xD7A3 -> true            // 谚文音节
            cp in 0xF900..0xFAFF -> true            // CJK 兼容汉字
            cp in 0xFE30..0xFE6F -> true            // CJK 兼容形式
            cp in 0xFF00..0xFF60 -> true            // 全角形式
            cp in 0xFFE0..0xFFE6 -> true            // 全角符号变体
            cp in 0x1F300..0x1FAFF -> true          // emoji（单个码点，算 2 格）
            cp in 0x1F000..0x1F2FF -> true
            cp in 0x20000..0x3FFFD -> true          // CJK 扩展 B 及以上
            else -> false
        }
    }

    /** 处理行内语法：**加粗**、*斜体*、`代码`、~~删除线~~。 */
    private fun appendInline(
        sb: StringBuilder,
        spans: MutableList<SpanSpec>,
        line: String,
        baseColor: Int
    ) {
        var i = 0
        while (i < line.length) {
            // 行内代码
            if (line[i] == '`') {
                val end = line.indexOf('`', i + 1)
                if (end > i) {
                    val start = sb.length
                    sb.append(line, i + 1, end)
                    spans.add(SpanSpec(TypefaceSpan("monospace"), start, sb.length, 0))
                    spans.add(SpanSpec(BackgroundColorSpan(CODE_BG), start, sb.length, 0))
                    i = end + 1
                    continue
                }
            }
            // 加粗
            if (line.startsWith("**", i) || line.startsWith("__", i)) {
                val marker = line.substring(i, i + 2)
                val end = line.indexOf(marker, i + 2)
                if (end > i + 2) {
                    val start = sb.length
                    sb.append(line, i + 2, end)
                    spans.add(SpanSpec(StyleSpan(Typeface.BOLD), start, sb.length, 0))
                    i = end + 2
                    continue
                }
            }
            // 删除线
            if (line.startsWith("~~", i)) {
                val end = line.indexOf("~~", i + 2)
                if (end > i + 2) {
                    val start = sb.length
                    sb.append(line, i + 2, end)
                    spans.add(SpanSpec(
                        android.text.style.StrikethroughSpan(), start, sb.length, 0
                    ))
                    i = end + 2
                    continue
                }
            }
            // 斜体（单星号，且不是加粗）
            if (line[i] == '*' && !line.startsWith("**", i)) {
                val end = line.indexOf('*', i + 1)
                if (end > i + 1) {
                    val start = sb.length
                    sb.append(line, i + 1, end)
                    spans.add(SpanSpec(StyleSpan(Typeface.ITALIC), start, sb.length, 0))
                    i = end + 1
                    continue
                }
            }
            // 按码点追加：代理对（emoji/生僻字）必须一次写完整，
            // 否则逐 code unit 追加会在中途留下半个字符 → 显示成乱码。
            val cp = line.codePointAt(i)
            sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
    }

    /**
     * 给「朗读」用的清洗：把 Markdown 标记去掉，只留能读的文字。
     * 否则 TTS 会念出「星号星号」「反引号」这种噪音。
     */
    fun stripForSpeech(text: String): String {
        if (text.isEmpty()) return text
        var t = text
        // 面板块整块去掉（状态卡念出来是一串符号，没意义）
        t = t.replace(Regex("```(?:panel|status|系统面板|面板)[\\s\\S]*?```"), " ")
        // 代码块整块去掉（代码念出来没有意义）
        t = t.replace(Regex("```[\\s\\S]*?```"), " ")
        // 表格分隔行
        t = t.replace(Regex("(?m)^\\s*\\|?[\\s:|-]+\\|[\\s:|-]*$"), " ")
        // 表格竖线与分隔线
        t = t.replace("|", " ")
        t = t.replace(Regex("(?m)^\\s*[-*_]{3,}\\s*$"), " ")
        // 标题井号
        t = t.replace(Regex("(?m)^\\s*#{1,6}\\s*"), "")
        // 引用
        t = t.replace(Regex("(?m)^\\s*>+\\s?"), "")
        // 列表符号
        t = t.replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
        t = t.replace(Regex("(?m)^\\s*\\d+[.)]\\s+"), "")
        // 行内标记
        t = t.replace("**", "").replace("__", "").replace("~~", "").replace("`", "")
        t = t.replace(Regex("(?m)^\\s*\\*\\s+"), "")
        // 压缩空白
        t = t.replace(Regex("[ \\t]{2,}"), " ")
        t = t.replace(Regex("\\n{2,}"), "\n")
        return t.trim()
    }
}
