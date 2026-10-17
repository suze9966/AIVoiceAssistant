package com.suze.aivoice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 文游场景卡片：气泡里直接画播放器、朋友圈、聊天、资料、浏览器这些页面。
 * 不解析 LaTeX，只画能看见的界面。
 */
class SceneCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Model(
        val kind: String,
        val title: String,
        val lines: List<String>
    )

    private var model: Model? = null
    var onIconClick: ((Int) -> Unit)? = null
    private val iconHits = ArrayList<Pair<RectF, Int>>()
    private val baseDensity = resources.displayMetrics.density
    private var density = baseDensity
    private var uiScale = 1f

    /** 小窗放大缩小时，画面里的字和图形跟窗口一起变。聊天气泡不调用，保持原尺寸。 */
    fun setUiScale(scale: Float) {
        val next = scale.coerceIn(0.52f, 1.75f)
        if (kotlin.math.abs(density - baseDensity * next) < 0.02f) return
        uiScale = next
        density = baseDensity * next
        requestLayout()
        invalidate()
    }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val card = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mute = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        ink.color = Color.parseColor("#EBEBEB")
        ink.textSize = 15f * density
        mute.color = Color.parseColor("#A8A8A8")
        mute.textSize = 12f * density
        accent.color = Color.parseColor("#FF4D4D")
        track.color = Color.parseColor("#5A5A5A")
        track.strokeWidth = 3f * density
        track.style = Paint.Style.STROKE
        card.color = Color.parseColor("#323232")
        card.style = Paint.Style.FILL
    }

    fun bind(next: Model?) {
        model = next
        visibility = if (next == null) GONE else VISIBLE
        isClickable = next?.kind == "home"
        requestLayout()
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (model?.kind != "home") return super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val hit = iconHits.firstOrNull { it.first.contains(event.x, event.y) }
            if (hit != null) {
                onIconClick?.invoke(hit.second)
                return true
            }
        }
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val m = model
        val fallback = (280 * density).toInt()
        val w = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
            MeasureSpec.AT_MOST -> MeasureSpec.getSize(widthMeasureSpec).coerceAtMost(fallback).coerceAtLeast(1)
            else -> fallback
        }
        val h = if (m == null) 0 else when (m.kind) {
            "home" -> (300 * density).toInt()
            "player" -> (168 * density).toInt()
            "moments", "profile", "browser" -> (168 * density).toInt()
            "system", "danmaku" -> (150 * density).toInt()
            else -> (148 * density).toInt()
        }
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val m = model ?: return
        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val rad = 16f * density
        bg.color = when (m.kind) {
            "home" -> Color.parseColor("#7EB6E8")
            "system" -> Color.parseColor("#07110A")
            "profile", "browser", "contacts" -> Color.parseColor("#F4F4F4")
            "note" -> Color.parseColor("#FFF8E8")
            else -> Color.parseColor("#1E1E1E")
        }
        canvas.drawRoundRect(r, rad, rad, bg)
        when (m.kind) {
            "home" -> drawHome(canvas, m)
            "player" -> drawPlayer(canvas, m)
            "moments" -> drawMoments(canvas, m)
            "chat" -> drawChat(canvas, m)
            "profile" -> drawProfile(canvas, m)
            "browser" -> drawBrowser(canvas, m)
            "contacts" -> drawContacts(canvas, m)
            "note" -> drawNote(canvas, m)
            "system" -> drawSystem(canvas, m)
            "danmaku" -> drawDanmaku(canvas, m)
            "forum" -> drawForum(canvas, m)
            else -> drawPlain(canvas, m)
        }
    }

    private fun drawHome(canvas: Canvas, m: Model) {
        iconHits.clear()
        val clock = line(m, 0, "12:00")
        val battery = line(m, 1, "80%")
        val owner = line(m, 2, "苏泽的小手机")
        ink.color = Color.WHITE
        ink.textSize = 22f * density
        ink.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(cut(clock, 8), 16f * density, 36f * density, ink)
        ink.textSize = 12f * density
        ink.typeface = Typeface.DEFAULT
        val batt = cut(battery, 6)
        val battW = ink.measureText(batt)
        canvas.drawText(batt, width - 16f * density - battW, 28f * density, ink)
        mute.color = Color.parseColor("#F4FBFF")
        mute.textSize = 12f * density
        canvas.drawText(cut(owner, 16), 16f * density, 54f * density, mute)
        val apps = m.lines.drop(3)
        if (apps.isEmpty()) return
        val cols = 3
        val top = 72f * density
        val cellW = width / cols.toFloat()
        val cellH = 72f * density
        val colors = intArrayOf(
            Color.parseColor("#3A8DFF"),
            Color.parseColor("#F0A36B"),
            Color.parseColor("#7BC47F"),
            Color.parseColor("#C98AD6"),
            Color.parseColor("#5BC0DE"),
            Color.parseColor("#E07A5F"),
            Color.parseColor("#6F8CFF"),
            Color.parseColor("#E8B84A"),
            Color.parseColor("#4FA3E3")
        )
        apps.forEachIndexed { i, raw ->
            val col = i % cols
            val row = i / cols
            val cx = cellW * col + cellW / 2f
            val cy = top + row * cellH + 16f * density
            val icon = raw.takeWhile { !it.isWhitespace() }.ifBlank { "·" }
            val name = raw.dropWhile { !it.isWhitespace() }.trim().ifBlank { raw }
            card.color = colors[i % colors.size]
            canvas.drawRoundRect(
                cx - 18f * density,
                cy - 16f * density,
                cx + 18f * density,
                cy + 16f * density,
                10f * density,
                10f * density,
                card
            )
            ink.color = Color.WHITE
            ink.textSize = 14f * density
            ink.typeface = Typeface.DEFAULT
            val iconW = ink.measureText(icon)
            canvas.drawText(icon, cx - iconW / 2f, cy + 5f * density, ink)
            ink.color = Color.parseColor("#F7FBFF")
            ink.textSize = 11f * density
            val nameW = ink.measureText(cut(name, 4))
            canvas.drawText(cut(name, 4), cx - nameW / 2f, cy + 30f * density, ink)
            iconHits.add(
                RectF(
                    cellW * col,
                    top + row * cellH,
                    cellW * (col + 1),
                    top + (row + 1) * cellH
                ) to i
            )
        }
    }

    private fun drawPlayer(canvas: Canvas, m: Model) {
        val song = line(m, 0, "正在听")
        val singer = line(m, 1, "小沫")
        val time = line(m, 2, "00:08 / 03:54")
        val cx = width * 0.28f
        val cy = height * 0.42f
        card.color = Color.parseColor("#3A5A8C")
        canvas.drawCircle(cx, cy, 42f * density, card)
        card.color = Color.parseColor("#E8E8E8")
        canvas.drawCircle(cx, cy, 8f * density, card)
        ink.color = Color.parseColor("#EBEBEB")
        ink.textSize = 16f * density
        ink.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(cut(song, 10), width * 0.50f, height * 0.32f, ink)
        mute.color = Color.parseColor("#A8A8A8")
        mute.textSize = 12f * density
        canvas.drawText(cut(singer, 10), width * 0.50f, height * 0.46f, mute)
        accent.color = Color.parseColor("#FF4D4D")
        accent.textSize = 13f * density
        canvas.drawText("♥  评论  分享", width * 0.50f, height * 0.60f, accent)
        val y = height * 0.74f
        track.color = Color.parseColor("#666666")
        canvas.drawLine(18f * density, y, width - 18f * density, y, track)
        card.color = Color.WHITE
        canvas.drawCircle(width * 0.28f, y, 5f * density, card)
        mute.textSize = 11f * density
        canvas.drawText(cut(time, 16), 16f * density, y + 16f * density, mute)
        ink.color = Color.parseColor("#EBEBEB")
        ink.textSize = 12f * density
        ink.typeface = Typeface.DEFAULT
        canvas.drawText("循环   上一首   播放   下一首", 16f * density, height - 12f * density, ink)
    }

    private fun drawMoments(canvas: Canvas, m: Model) {
        ink.color = Color.parseColor("#EBEBEB")
        ink.textSize = 15f * density
        ink.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("朋友圈", 16f * density, 28f * density, ink)
        card.color = Color.parseColor("#3A5A8C")
        canvas.drawCircle(28f * density, 58f * density, 14f * density, card)
        mute.color = Color.parseColor("#8FB7FF")
        mute.textSize = 13f * density
        canvas.drawText(cut(line(m, 0, "小沫"), 8), 50f * density, 62f * density, mute)
        ink.textSize = 14f * density
        ink.typeface = Typeface.DEFAULT
        canvas.drawText(cut(line(m, 1, "刚刷到一条。"), 16), 16f * density, 96f * density, ink)
        card.color = Color.parseColor("#2A2A2A")
        canvas.drawRoundRect(16f * density, 110f * density, 92f * density, 146f * density, 8f * density, 8f * density, card)
        accent.color = Color.parseColor("#FF4D4D")
        accent.textSize = 12f * density
        canvas.drawText("♡  评论", 108f * density, 132f * density, accent)
    }

    private fun drawChat(canvas: Canvas, m: Model) {
        ink.color = Color.parseColor("#EBEBEB")
        ink.textSize = 14f * density
        ink.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(cut(m.title.ifBlank { "聊天" }, 10), 16f * density, 26f * density, ink)
        val lines = if (m.lines.isEmpty()) listOf("主人，我在。") else m.lines
        var y = 54f * density
        lines.take(3).forEachIndexed { i, line ->
            val mine = i % 2 == 1
            val bubble = RectF(
                if (mine) width * 0.38f else 12f * density,
                y - 16f * density,
                if (mine) width - 12f * density else width * 0.74f,
                y + 12f * density
            )
            card.color = if (mine) Color.parseColor("#2E8BD6") else Color.parseColor("#3A3A3A")
            canvas.drawRoundRect(bubble, 10f * density, 10f * density, card)
            ink.textSize = 13f * density
            ink.typeface = Typeface.DEFAULT
            canvas.drawText(cut(line, 12), bubble.left + 8f * density, y, ink)
            y += 36f * density
        }
    }

    private fun drawProfile(canvas: Canvas, m: Model) {
        ink.color = Color.parseColor("#181818")
        ink.textSize = 15f * density
        ink.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("资料", 16f * density, 28f * density, ink)
        card.color = Color.parseColor("#7AA2FF")
        canvas.drawCircle(36f * density, 68f * density, 20f * density, card)
        ink.textSize = 16f * density
        canvas.drawText(cut(line(m, 0, "小沫"), 8), 66f * density, 64f * density, ink)
        mute.color = Color.parseColor("#666666")
        mute.textSize = 12f * density
        canvas.drawText(cut(line(m, 1, "微信号"), 14), 66f * density, 84f * density, mute)
        var y = 118f * density
        listOf("备注", "地区", "签名").forEachIndexed { i, label ->
            mute.textSize = 12f * density
            canvas.drawText(label, 16f * density, y, mute)
            ink.textSize = 13f * density
            ink.typeface = Typeface.DEFAULT
            canvas.drawText(cut(line(m, i + 2, "未填写"), 10), 72f * density, y, ink)
            y += 22f * density
        }
    }

    private fun drawBrowser(canvas: Canvas, m: Model) {
        ink.color = Color.parseColor("#181818")
        ink.textSize = 15f * density
        ink.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("浏览器", 16f * density, 28f * density, ink)
        card.color = Color.WHITE
        canvas.drawRoundRect(16f * density, 40f * density, width - 16f * density, 68f * density, 14f * density, 14f * density, card)
        mute.color = Color.parseColor("#888888")
        mute.textSize = 12f * density
        canvas.drawText("搜索  " + cut(line(m, 0, "最近搜过"), 12), 28f * density, 58f * density, mute)
        var y = 96f * density
        val rows = if (m.lines.size <= 1) listOf("没有更多记录") else m.lines.drop(1)
        rows.take(3).forEach { row ->
            card.color = Color.parseColor("#2E8BD6")
            canvas.drawCircle(24f * density, y - 4f * density, 4f * density, card)
            ink.textSize = 13f * density
            ink.typeface = Typeface.DEFAULT
            canvas.drawText(cut(row, 16), 38f * density, y, ink)
            y += 24f * density
        }
    }

    private fun drawContacts(canvas: Canvas, m: Model) {
        ink.color = Color.parseColor("#181818")
        ink.textSize = 15f * density
        ink.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("通讯录", 16f * density, 28f * density, ink)
        val rows = if (m.lines.isEmpty()) listOf("小沫") else m.lines
        var y = 58f * density
        rows.take(4).forEachIndexed { i, row ->
            card.color = Color.parseColor(listOf("#7AA2FF", "#F0A36B", "#7BC47F", "#C98AD6")[i])
            canvas.drawCircle(28f * density, y - 4f * density, 12f * density, card)
            ink.textSize = 14f * density
            ink.typeface = Typeface.DEFAULT
            canvas.drawText(cut(row, 12), 50f * density, y, ink)
            y += 28f * density
        }
    }

    private fun drawNote(canvas: Canvas, m: Model) {
        ink.color = Color.parseColor("#5A4632")
        ink.textSize = 15f * density
        ink.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(cut(m.title.ifBlank { "便签" }, 10), 16f * density, 28f * density, ink)
        accent.color = Color.parseColor("#E07A5F")
        canvas.drawRect(16f * density, 40f * density, 42f * density, 44f * density, accent)
        var y = 68f * density
        val rows = if (m.lines.isEmpty()) listOf("空白的一页。") else m.lines
        ink.textSize = 13f * density
        ink.typeface = Typeface.DEFAULT
        rows.take(4).forEach { row ->
            canvas.drawText(cut(row, 16), 16f * density, y, ink)
            y += 22f * density
        }
    }

    private fun drawSystem(canvas: Canvas, m: Model) {
        ink.color = Color.parseColor("#33FF99")
        ink.textSize = 13f * density
        ink.typeface = Typeface.MONOSPACE
        canvas.drawText("· 系统管理员", 16f * density, 26f * density, ink)
        var y = 54f * density
        val rows = if (m.lines.isEmpty()) listOf("系统已就绪") else m.lines
        rows.take(4).forEach { row ->
            canvas.drawText("> " + cut(row, 16), 16f * density, y, ink)
            y += 22f * density
        }
        mute.color = Color.parseColor("#227755")
        mute.textSize = 11f * density
        canvas.drawText("【 系统时间 】", 16f * density, height - 14f * density, mute)
    }

    private fun drawDanmaku(canvas: Canvas, m: Model) {
        card.color = Color.parseColor("#101820")
        canvas.drawRoundRect(10f * density, 10f * density, width - 10f * density, height * 0.48f, 8f * density, 8f * density, card)
        ink.color = Color.WHITE
        ink.textSize = 13f * density
        ink.typeface = Typeface.DEFAULT
        canvas.drawText(cut(line(m, 0, "直播中"), 14), 20f * density, 34f * density, ink)
        accent.color = Color.parseColor("#FFE08A")
        accent.textSize = 12f * density
        canvas.drawText(cut(line(m, 1, "弹幕飘过"), 16), 20f * density, 56f * density, accent)
        mute.color = Color.parseColor("#D0D0D0")
        mute.textSize = 12f * density
        var y = height * 0.64f
        m.lines.drop(2).take(2).ifEmpty { listOf("主人来了") }.forEach { row ->
            canvas.drawText(cut(row, 16), 16f * density, y, mute)
            y += 20f * density
        }
    }

    private fun drawForum(canvas: Canvas, m: Model) {
        ink.color = Color.parseColor("#EBEBEB")
        ink.textSize = 15f * density
        ink.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(cut(m.title.ifBlank { "讨论" }, 12), 16f * density, 28f * density, ink)
        var y = 56f * density
        val rows = if (m.lines.isEmpty()) listOf("还没有回复") else m.lines
        rows.take(4).forEachIndexed { i, row ->
            mute.color = Color.parseColor("#8FB7FF")
            mute.textSize = 11f * density
            canvas.drawText(if (i == 0) "楼主" else "回复", 16f * density, y, mute)
            ink.textSize = 13f * density
            ink.typeface = Typeface.DEFAULT
            canvas.drawText(cut(row, 14), 52f * density, y, ink)
            y += 24f * density
        }
    }

    private fun drawPlain(canvas: Canvas, m: Model) {
        ink.color = Color.parseColor("#EBEBEB")
        ink.textSize = 15f * density
        ink.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(cut(m.title.ifBlank { "场景" }, 12), 16f * density, 28f * density, ink)
        var y = 56f * density
        val lines = if (m.lines.isEmpty()) listOf("这一页打开了。") else m.lines
        ink.textSize = 13f * density
        ink.typeface = Typeface.DEFAULT
        lines.take(4).forEach { line ->
            canvas.drawText(cut(line, 16), 16f * density, y, ink)
            y += 22f * density
        }
    }

    private fun line(m: Model, index: Int, fallback: String): String {
        return m.lines.getOrNull(index).orEmpty().ifBlank { fallback }
    }

    private fun cut(text: String, n: Int): String {
        val t = text.trim()
        return if (t.length <= n) t else t.take(n) + "…"
    }
}