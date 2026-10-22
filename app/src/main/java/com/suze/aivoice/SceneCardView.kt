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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        val lines: List<String>,
        val inkColor: Int = 0,
        val paperColor: Int = 0,
        val accentColor: Int = 0,
        val pageColor: Int = 0,
        val frame: String = "",
        val ornament: String = "",
        val corner: String = "",
        val skin: String = ""
    )

    private var model: Model? = null
    var onIconClick: ((Int) -> Unit)? = null
    var onSizeChanged: ((Float, Float) -> Unit)? = null
    private val iconHits = ArrayList<Pair<RectF, Int>>()
    private val baseDensity = resources.displayMetrics.density
    private var density = baseDensity
    private var uiScale = 1f
    private var userW = 0
    private var userH = 0
    private var dragW = 0
    private var dragH = 0
    private var dragX = 0f
    private var dragY = 0f
    private var resizing = false
    /** 只有聊天气泡打开。小手机铺满的那张卡不记尺寸，避免拖手柄把整页缩成一块。 */
    var rememberSize: Boolean = false

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
        isClickable = next != null
        requestLayout()
        invalidate()
    }

    /** 主人拖过的宽高，单位是 dp。0 表示还没调过。 */
    fun applyUserSize(widthDp: Float, heightDp: Float) {
        userW = if (widthDp > 0f) (widthDp * baseDensity).toInt() else 0
        userH = if (heightDp > 0f) (heightDp * baseDensity).toInt() else 0
        requestLayout()
        invalidate()
    }

    private fun handleSize(): RectF {
        val s = 36f * baseDensity
        return RectF((width - s).coerceAtLeast(0f), (height - s).coerceAtLeast(0f), width.toFloat(), height.toFloat())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val m = model ?: return super.onTouchEvent(event)
        val handle = handleSize()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (rememberSize && handle.contains(event.x, event.y)) {
                    resizing = true
                    dragW = if (width > 0) width else userW
                    dragH = if (height > 0) height else userH
                    dragX = event.rawX
                    dragY = event.rawY
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!resizing) return m.kind == "home"
                val minW = (160 * baseDensity).toInt()
                val minH = (96 * baseDensity).toInt()
                val maxW = (resources.displayMetrics.widthPixels * 0.92f).toInt()
                val maxH = if (m.kind == "beauty") {
                    (resources.displayMetrics.heightPixels * 200f).toInt()
                } else {
                    (resources.displayMetrics.heightPixels * 0.72f).toInt()
                }
                userW = (dragW + (event.rawX - dragX)).toInt().coerceIn(minW, maxW)
                userH = (dragH + (event.rawY - dragY)).toInt().coerceIn(minH, maxH)
                requestLayout()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (resizing) {
                    resizing = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    if (userW > 0 && userH > 0) {
                        onSizeChanged?.invoke(userW / baseDensity, userH / baseDensity)
                    }
                    return true
                }
            }
        }
        if (m.kind != "home") return super.onTouchEvent(event)
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
        val fallback = (300 * baseDensity).toInt()
        val maxW = (resources.displayMetrics.widthPixels * 0.92f).toInt()
        val w = when {
            userW > 0 -> userW.coerceIn((160 * baseDensity).toInt(), maxW)
            MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY ->
                MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
            MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.AT_MOST ->
                MeasureSpec.getSize(widthMeasureSpec).coerceAtMost(fallback).coerceAtLeast(1)
            else -> fallback
        }
        val natural = if (m == null) 0 else when (m.kind) {
            "home" -> homeHeight(m)
            "beauty" -> beautyHeight(m, w)
            "player" -> (196 * baseDensity).toInt()
            "moments", "profile", "browser" -> (188 * baseDensity).toInt()
            "system", "danmaku" -> (168 * baseDensity).toInt()
            else -> (176 * baseDensity).toInt()
        }
        if (w > 0 && userW > 0) {
            val fit = (w / 280f / baseDensity).coerceIn(0.55f, 2.4f)
            if (kotlin.math.abs(density - baseDensity * fit) >= 0.02f) {
                uiScale = fit
                density = baseDensity * fit
            }
        }
        val cap = if (m?.kind == "beauty") {
            // 美化卡不再用「屏高 4 倍」封顶；换机后像素不同也不该被压矮。
            (resources.displayMetrics.heightPixels * 200f).toInt()
        } else {
            (resources.displayMetrics.heightPixels * 0.72f).toInt()
        }
        val h = when {
            m == null -> 0
            m.kind == "beauty" -> {
                val need = beautyHeight(m, w)
                if (userH > 0) maxOf(userH, need).coerceIn((96 * baseDensity).toInt(), cap) else need
            }
            userH > 0 -> userH.coerceIn((96 * baseDensity).toInt(), cap)
            else -> natural
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
            "beauty" -> if (m.paperColor != 0) blendPage(m.paperColor) else Color.parseColor("#F4F4F4")
            "profile", "browser", "contacts" -> Color.parseColor("#F4F4F4")
            "note" -> Color.parseColor("#FFF8E8")
            else -> Color.parseColor("#1E1E1E")
        }
        canvas.drawRoundRect(r, rad, rad, bg)
        drawResizeHandle(canvas)
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
            "beauty" -> drawBeauty(canvas, m)
            "douyin" -> drawDouyinApp(canvas, m)
            "taobao" -> drawTaobaoApp(canvas, m)
            "alipay" -> drawAlipayApp(canvas, m)
            "maps" -> drawMapsApp(canvas, m)
            "meituan" -> drawMeituanApp(canvas, m)
            "jd" -> drawJdApp(canvas, m)
            "bilibili" -> drawBilibiliApp(canvas, m)
            "weibo" -> drawWeiboApp(canvas, m)
            "xiaohongshu" -> drawXiaohongshuApp(canvas, m)
            else -> drawPlain(canvas, m)
        }
    }

    private fun drawResizeHandle(canvas: Canvas) {
        if (!rememberSize || width < 8 || height < 8) return
        val s = 16f * baseDensity
        val x = width - 8f * baseDensity
        val y = height - 8f * baseDensity
        track.color = Color.parseColor("#CCFFFFFF")
        track.strokeWidth = 2.4f * baseDensity
        canvas.drawLine(x - s, y, x, y, track)
        canvas.drawLine(x, y - s, x, y, track)
        canvas.drawLine(x - s * 0.55f, y, x, y - s * 0.55f, track)
    }

    private fun homeHeight(m: Model): Int {
        val apps = (m.lines.size - 3).coerceAtLeast(1)
        val rows = (apps + 2) / 3
        return ((96 + rows * 72) * baseDensity).toInt()
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

    private fun drawBeauty(canvas: Canvas, m: Model) {
        val frame = m.frame.ifBlank { "card" }
        when (frame) {
            "ornament" -> drawOrnament(canvas, m)
            "note" -> drawNotePack(canvas, m)
            "terminal" -> drawTerminalCard(canvas, m)
            "bilingual", "wing", "bunny" -> drawTranslateCard(canvas, m)
            "face" -> drawFaceCard(canvas, m)
            "role" -> drawRoleCard(canvas, m)
            "oval" -> drawSkinCard(canvas, m)
            "basic", "wechat" -> drawBasicBubbles(canvas, m)
            "wxhome", "wxfind", "wxme", "wxbook", "wxadd", "wxdetail", "wxchat", "wxedit", "wxfriend", "wxmoments" ->
                drawWechatPage(canvas, m)
            else -> drawCardRows(canvas, m)
        }
    }

    private fun drawOrnament(canvas: Canvas, m: Model) {
        val paper = colorOr(m.paperColor, "#F8FCFE")
        val textInk = colorOr(m.pageColor, "#305058")
        val lineInk = colorOr(m.accentColor, "#88B8C8")
        val marks = m.ornament.split("|").map { it.trim() }.filter { it.isNotEmpty() }
        val top = marks.getOrElse(0) { "◌" }
        val mid = marks.getOrElse(1) { "～" }
        val side = marks.getOrElse(2) { "❋" }
        val rightTop = marks.getOrElse(3) { top }
        val rightBottom = marks.getOrElse(4) { "∼" }
        val bottomLeft = marks.getOrElse(5) { side }
        val sign = marks.getOrElse(6) { m.corner.ifBlank { "sy." } }
        val rows = beautyRows(m)
        ink.textSize = 13f * density
        val maxW = (width - 92f * density).coerceAtLeast(40f * density)
        val wrapped = rows.map { wrapText(it, ink, maxW) }
        val lineH = 18f * density
        var y = 34f * density
        mute.color = lineInk
        mute.textSize = 12f * density
        canvas.drawText(top, width * 0.34f, 16f * density, mute)
        canvas.drawText(mid, width * 0.48f, 18f * density, mute)
        wrapped.forEachIndexed { i, parts ->
            val blockH = parts.size * lineH + 12f * density
            if (y + blockH > height - 28f * density && i > 0) return@forEachIndexed
            val bubble = RectF(28f * density, y, width - 28f * density, y + blockH)
            fillRound(canvas, bubble, paper, 12f * density)
            strokeRound(canvas, bubble, lineInk, 12f * density, 1.4f * density)
            canvas.drawText(side, 8f * density, y + 16f * density, mute)
            val sideW = mute.measureText(side)
            canvas.drawText(side, width - 8f * density - sideW, y + 16f * density, mute)
            ink.color = textInk
            ink.textSize = 13f * density
            ink.typeface = Typeface.DEFAULT
            var ty = y + 16f * density
            parts.forEach { part ->
                canvas.drawText(part, bubble.left + 10f * density, ty, ink)
                ty += lineH
            }
            y += blockH + 8f * density
        }
        mute.textSize = 11f * density
        canvas.drawText(rightTop, width - 22f * density, 16f * density, mute)
        canvas.drawText(rightBottom, width - 28f * density, (y - 4f * density).coerceAtMost(height - 28f * density), mute)
        canvas.drawText(bottomLeft, 10f * density, height - 14f * density, mute)
        val signW = mute.measureText(sign)
        canvas.drawText(sign, (width - signW) / 2f, height - 12f * density, mute)
    }

    private fun drawNotePack(canvas: Canvas, m: Model) {
        val palette = notePalette()
        val rows = beautyRows(m)
        var y = 14f * density
        val lineH = 16f * density
        rows.forEachIndexed { i, raw ->
            if (y > height - 18f * density && i > 0) return@forEachIndexed
            val set = palette[i % palette.size]
            val parts = splitNote(raw, i)
            ink.textSize = 12f * density
            ink.typeface = Typeface.DEFAULT_BOLD
            ink.color = set[0]
            bubbleLines(parts[0], width - 24f * density).forEach { part ->
                canvas.drawText(part, 12f * density, y + 12f * density, ink)
                y += 16f * density
            }
            ink.typeface = Typeface.DEFAULT
            bubbleLines(parts[1], width - 36f * density).forEach { part ->
                canvas.drawText(part, 22f * density, y + 12f * density, ink)
                y += 16f * density
            }
            y += 2f * density
            val talk = wrapText(parts[2], ink, width - 58f * density)
            val boxH = talk.size * lineH + 10f * density
            accent.color = set[0]
            canvas.drawRect(12f * density, y, 20f * density, y + boxH, accent)
            val outer = RectF(24f * density, y, width - 12f * density, y + boxH)
            fillRound(canvas, outer, set[1], 6f * density)
            val inner = RectF(outer.left + 4f * density, outer.top + 3f * density, outer.right - 4f * density, outer.bottom - 3f * density)
            fillRound(canvas, inner, set[2], 4f * density)
            ink.color = set[3]
            var ty = y + 14f * density
            talk.forEach { line ->
                canvas.drawText(line, inner.left + 6f * density, ty, ink)
                ty += lineH
            }
            y += boxH + 4f * density
            ink.color = set[4]
            ink.textSize = 11f * density
            bubbleLines("> " + parts[3], width - 24f * density).forEach { part ->
                canvas.drawText(part, 12f * density, y + 12f * density, ink)
                y += 16f * density
            }
            y += 6f * density
        }
    }

    private fun drawTerminalCard(canvas: Canvas, m: Model) {
        bg.color = Color.parseColor("#07110A")
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 16f * density, 16f * density, bg)
        ink.color = Color.parseColor("#E8FFE8")
        ink.textSize = 13f * density
        ink.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("· 系统管理员", 14f * density, 24f * density, ink)
        accent.color = Color.parseColor("#33FF99")
        canvas.drawRect(14f * density, 32f * density, 24f * density, 40f * density, accent)
        val box = RectF(12f * density, 46f * density, width - 12f * density, height - 28f * density)
        fillRound(canvas, box, Color.parseColor("#0A0F0A"), 4f * density)
        ink.color = Color.parseColor("#33FF99")
        ink.textSize = 12f * density
        ink.typeface = Typeface.MONOSPACE
        var y = 64f * density
        beautyRows(m).forEach { row ->
            wrapText("> " + row.removePrefix(">").trim(), ink, width - 40f * density).forEach { line ->
                if (y > height - 36f * density) return@forEach
                canvas.drawText(line, 20f * density, y, ink)
                y += 16f * density
            }
        }
        mute.color = Color.parseColor("#227755")
        mute.textSize = 11f * density
        canvas.drawText(clockLine(), 14f * density, height - 12f * density, mute)
    }

    private fun drawTranslateCard(canvas: Canvas, m: Model) {
        val frame = m.frame
        val head = when (frame) {
            "wing" -> "🪽"
            "bunny" -> "🐰"
            else -> ""
        }
        val spark = when (frame) {
            "wing" -> "闪光"
            "bunny" -> "₊ ⊹★"
            else -> ""
        }
        val paper = when (frame) {
            "wing" -> colorOr(m.paperColor, "#FFF0F5")
            "bunny" -> colorOr(m.paperColor, "#E8F4F8")
            else -> Color.TRANSPARENT
        }
        val sparkInk = if (frame == "wing") Color.parseColor("#F5A0A0") else Color.parseColor("#A8D0E6")
        ink.color = Color.BLACK
        ink.textSize = 14f * density
        ink.typeface = Typeface.DEFAULT_BOLD
        val name = m.title.ifBlank { "角色名" }
        val nameW = ink.measureText(name)
        canvas.drawText(name, width - 16f * density - nameW, 22f * density, ink)
        mute.color = Color.parseColor("#8A8A8A")
        mute.textSize = 10f * density
        val meta = "信息 · 短信"
        canvas.drawText(meta, width - 16f * density - mute.measureText(meta), 36f * density, mute)
        val date = beautyRows(m).firstOrNull { it.contains("月") && it.contains(":") } ?: "8月4日 周二 14:23"
        canvas.drawText(date, width - 16f * density - mute.measureText(date), 50f * density, mute)
        var y = 62f * density
        val body = beautyRows(m).filterNot { it == date }
        ink.textSize = 13f * density
        ink.typeface = Typeface.DEFAULT
        body.forEachIndexed { i, row ->
            if (y > height - 20f * density && i > 0) return@forEachIndexed
            val pair = splitTranslate(row)
            if (head.isNotEmpty()) {
                card.color = paper
                canvas.drawRoundRect(10f * density, y, 34f * density, y + 22f * density, 6f * density, 6f * density, card)
                ink.color = Color.BLACK
                canvas.drawText(head, 13f * density, y + 15f * density, ink)
            }
            val left = if (head.isEmpty()) 12f * density else 40f * density
            ink.color = Color.BLACK
            val parts = bubbleLines(pair.first, width * 0.62f)
            val textW = parts.maxOf { ink.measureText(it) }
            val blockH = parts.size * 18f * density + 12f * density
            val bubble = RectF(left, y, left + textW + 18f * density, y + blockH)
            if (paper == Color.TRANSPARENT) {
                strokeRound(canvas, bubble, Color.BLACK, bubble.height() / 2f, 1.2f * density)
            } else {
                fillRound(canvas, bubble, paper, 8f * density)
            }
            var ty = y + 16f * density
            parts.forEach { part ->
                canvas.drawText(part, bubble.left + 8f * density, ty, ink)
                ty += 18f * density
            }
            if (spark.isNotEmpty()) {
                mute.color = sparkInk
                mute.textSize = 9f * density
                canvas.drawText(spark, bubble.left, y - 2f * density, mute)
            }
            mute.color = Color.parseColor("#B0B0B0")
            mute.textSize = 10f * density
            canvas.drawText("已读", bubble.right + 4f * density, bubble.bottom - 6f * density, mute)
            y += blockH + 8f * density
            if (pair.second.isNotEmpty()) {
                mute.color = Color.parseColor("#87CEEB")
                val under = bubbleLines(pair.second, width - left - 16f * density)
                under.forEach { part ->
                    canvas.drawText(part, left + 8f * density, y, mute)
                    track.color = Color.parseColor("#A0A0A0")
                    track.strokeWidth = 1f * density
                    val uw = mute.measureText(part)
                    canvas.drawLine(left + 8f * density, y + 3f * density, left + 8f * density + uw, y + 3f * density, track)
                    y += 16f * density
                }
            }
        }
    }

    private fun drawFaceCard(canvas: Canvas, m: Model) {
        ink.color = Color.parseColor("#1A2A3A")
        ink.textSize = 22f * density
        ink.typeface = Typeface.DEFAULT_BOLD
        val face = "ᗜ - ᗜ"
        val faceW = ink.measureText(face)
        val faceBox = RectF(width - faceW - 28f * density, 12f * density, width - 12f * density, 46f * density)
        fillRound(canvas, faceBox, Color.parseColor("#D0E4F0"), 8f * density)
        canvas.drawText(face, faceBox.left + 8f * density, 36f * density, ink)
        val phone = m.lines.firstOrNull { it.contains("sy_") || it.contains("电话") } ?: "sy_020723"
        ink.textSize = 12f * density
        ink.typeface = Typeface.DEFAULT
        val phoneBox = RectF(width * 0.42f, 54f * density, width - 12f * density, 74f * density)
        fillRound(canvas, phoneBox, Color.parseColor("#F4F6F8"), 6f * density)
        ink.color = Color.BLACK
        canvas.drawText(fit(phone, phoneBox.width() - 16f * density), phoneBox.left + 8f * density, 68f * density, ink)
        mute.color = Color.parseColor("#8A8A8A")
        mute.textSize = 10f * density
        canvas.drawText("信息 · 短信", width - 16f * density - mute.measureText("信息 · 短信"), 90f * density, mute)
        canvas.drawText("8月4日 周二 14:23", width - 16f * density - mute.measureText("8月4日 周二 14:23"), 104f * density, mute)
        var y = 118f * density
        val rows = beautyRows(m).filterNot { it.contains("电话") || it.contains("sy_") || it.contains("信息") }
        rows.forEachIndexed { i, row ->
            if (y > height - 16f * density && i > 0) return@forEachIndexed
            ink.textSize = 13f * density
            val parts = bubbleLines(row, width * 0.62f)
            val w = parts.maxOf { ink.measureText(it) } + 20f * density
            val blockH = parts.size * 18f * density + 12f * density
            val bubble = RectF(18f * density, y, 18f * density + w, y + blockH)
            fillRound(canvas, bubble, Color.parseColor("#E5F0F8"), 8f * density)
            accent.color = Color.parseColor("#E5F0F8")
            canvas.drawPath(triangle(bubble.left - 6f * density, bubble.centerY(), true), accent)
            ink.color = Color.BLACK
            var ty = y + 16f * density
            parts.forEach { part ->
                canvas.drawText(part, bubble.left + 8f * density, ty, ink)
                ty += 18f * density
            }
            mute.color = Color.parseColor("#8A8A8A")
            mute.textSize = 10f * density
            canvas.drawText("已读", bubble.right + 4f * density, y + 16f * density, mute)
            y += blockH + 8f * density
        }
    }

    private fun drawRoleCard(canvas: Canvas, m: Model) {
        var y = 16f * density
        var named = false
        beautyRows(m).forEachIndexed { i, raw ->
            if (y > height - 18f * density && i > 0) return@forEachIndexed
            val bits = splitRole(raw, named)
            if (!named && bits.name.isNotEmpty()) {
                ink.color = Color.parseColor("#222222")
                ink.textSize = 13f * density
                ink.typeface = Typeface.DEFAULT_BOLD
                canvas.drawText(bits.name, 14f * density, y + 12f * density, ink)
                named = true
                y += 18f * density
            }
            if (bits.action.isNotEmpty()) {
                mute.color = Color.parseColor("#666666")
                mute.textSize = 12f * density
                bubbleLines("> " + bits.action, width - 28f * density).forEach { part ->
                    canvas.drawText(part, 14f * density, y + 12f * density, mute)
                    y += 16f * density
                }
                y += 2f * density
            }
            ink.textSize = 13f * density
            ink.typeface = Typeface.DEFAULT
            val parts = bubbleLines(bits.talk, width - 86f * density)
            val w = parts.maxOf { ink.measureText(it) } + 18f * density
            val blockH = parts.size * 18f * density + 12f * density
            val bubble = RectF(22f * density, y, 22f * density + w, y + blockH)
            fillRound(canvas, bubble, Color.parseColor("#2A2A2A"), 8f * density)
            accent.color = Color.parseColor("#2A2A2A")
            canvas.drawPath(triangle(bubble.left - 6f * density, bubble.centerY(), true), accent)
            ink.color = Color.parseColor("#F5EDE0")
            var ty = y + 16f * density
            parts.forEach { part ->
                canvas.drawText(part, bubble.left + 8f * density, ty, ink)
                ty += 18f * density
            }
            mute.color = Color.parseColor("#888888")
            mute.textSize = 10f * density
            canvas.drawText(bits.time, bubble.right + 6f * density, y + 16f * density, mute)
            y += blockH + 8f * density
            if (bits.os.isNotEmpty()) {
                mute.textSize = 11f * density
                bubbleLines("｜" + bits.os, width - 24f * density).forEach { part ->
                    canvas.drawText(part, 14f * density, y + 10f * density, mute)
                    y += 16f * density
                }
            }
        }
    }

    private fun drawSkinCard(canvas: Canvas, m: Model) {
        val skin = skinMark(m.skin)
        // 五套椭圆皮肤各有自己的框色、字色、拍一拍色。
        // m.accentColor / m.pageColor 是从整份原文扫出来的，而一份原文里写着全部五套皮肤的色码，
        // 扫出来的总是同一组。这里只要认得出皮肤，就用皮肤自己的颜色，不让扫色码覆盖。
        val known = m.skin in setOf("berry", "mint", "pudding", "taro", "orange")
        val lineInk = if (known) Color.parseColor(skin.color) else colorOr(m.accentColor, skin.color)
        val textInk = if (known) Color.parseColor(skin.textColor) else colorOr(m.pageColor, "#2F4F4F")
        val patInk = if (known) Color.parseColor(skin.patColor) else lineInk
        val hollow = m.skin == "taro" || m.title.contains("2.0") || m.title.contains("2_0")
        var y = 12f * density
        beautyRows(m).forEachIndexed { i, row ->
            if (y > height - 16f * density && i > 0) return@forEachIndexed
            val bits = splitNamed(row)
            if (bits.first.isNotEmpty()) {
                ink.color = textInk
                ink.textSize = 12f * density
                ink.typeface = Typeface.DEFAULT_BOLD
                canvas.drawText(bits.first, 16f * density, y + 12f * density, ink)
                y += 16f * density
            }
            ink.textSize = 13f * density
            ink.typeface = Typeface.DEFAULT
            val kind = messageKind(bits.second)
            val shown = skinBody(kind, bits.second, skin)
            if (kind == "pat") {
                mute.color = patInk
                mute.textSize = 11f * density
                bubbleLines(shown, width - 36f * density).forEach { part ->
                    val pw = mute.measureText(part)
                    canvas.drawText(part, (width - pw) / 2f, y + 12f * density, mute)
                    y += 16f * density
                }
                y += 6f * density
            } else if (kind == "call") {
                y = drawCallBlock(canvas, bits.first.ifBlank { m.title }, bits.second, lineInk, textInk, y)
            } else if (kind == "voice") {
                y = drawVoiceBar(canvas, bits.second, lineInk, textInk, hollow, y)
            } else {
                val parts = bubbleLines(shown, width - 78f * density)
                val textW = parts.maxOf { ink.measureText(it) }
                val blockH = parts.size * 18f * density + 12f * density
                val bubble = RectF(14f * density, y, 14f * density + textW + 22f * density, y + blockH)
                val fill = when (kind) {
                    "pay" -> Color.parseColor("#F7D48B")
                    "paid" -> Color.parseColor("#F8E8C8")
                    "call" -> Color.parseColor("#E8F6EE")
                    else -> blendPage(lineInk)
                }
                if (!hollow || kind == "pay" || kind == "paid" || kind == "call") {
                    fillRound(canvas, bubble, fill, bubble.height() / 2f)
                }
                strokeRound(canvas, bubble, lineInk, bubble.height() / 2f, 1.5f * density)
                ink.color = textInk
                var ty = y + 16f * density
                parts.forEach { part ->
                    canvas.drawText(part, bubble.left + 10f * density, ty, ink)
                    ty += 18f * density
                }
                if (hollow && kind == "text") {
                    mute.color = Color.parseColor("#888888")
                    mute.textSize = 10f * density
                    canvas.drawText(clockShort(), bubble.right + 4f * density, bubble.bottom - 4f * density, mute)
                }
                y += blockH + 8f * density
                if (kind == "image") {
                    val desc = imageCaption(bits.second)
                    mute.color = Color.parseColor("#888888")
                    mute.textSize = 11f * density
                    bubbleLines(desc, width - 48f * density).forEach { part ->
                        canvas.drawText(part, 22f * density, y + 10f * density, mute)
                        y += 16f * density
                    }
                    y += 4f * density
                }
            }
        }
    }

    private fun drawBasicBubbles(canvas: Canvas, m: Model) {
        val green = m.skin != "white" && (m.skin == "green" || m.frame == "basic" || m.accentColor == 0)
        var y = 16f * density
        beautyRows(m).forEachIndexed { i, row ->
            if (y > height - 16f * density && i > 0) return@forEachIndexed
            val mine = if (m.frame == "basic") i % 2 == 0 else i % 2 == 1
            val fill = if (mine && green) Color.parseColor("#76E858") else Color.WHITE
            val inkColor = Color.BLACK
            ink.textSize = 13f * density
            ink.typeface = Typeface.DEFAULT
            val parts = bubbleLines(row, width * 0.62f)
            val w = parts.maxOf { ink.measureText(it) } + 20f * density
            val blockH = parts.size * 18f * density + 12f * density
            val left = if (mine) width - 18f * density - w else 20f * density
            val bubble = RectF(left, y, left + w, y + blockH)
            fillRound(canvas, bubble, fill, 8f * density)
            accent.color = fill
            canvas.drawPath(triangle(if (mine) bubble.right else bubble.left, bubble.centerY(), !mine), accent)
            ink.color = inkColor
            var ty = y + 16f * density
            parts.forEach { part ->
                canvas.drawText(part, bubble.left + 8f * density, ty, ink)
                ty += 18f * density
            }
            mute.color = Color.parseColor("#B0B0B0")
            mute.textSize = 10f * density
            val mark = if (i % 2 == 0) "已读" else "未读"
            val markX = if (mine) bubble.left - mute.measureText(mark) - 4f * density else bubble.right + 4f * density
            canvas.drawText(mark, markX, y + 16f * density, mute)
            y += blockH + 8f * density
        }
    }

    private fun drawWechatPage(canvas: Canvas, m: Model) {
        bg.color = Color.parseColor("#EDEDED")
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 16f * density, 16f * density, bg)
        drawStatus(canvas, "#181818")
        val title = pageTitle(m)
        ink.color = Color.parseColor("#181818")
        ink.textSize = 15f * density
        ink.typeface = Typeface.DEFAULT_BOLD
        val titleW = ink.measureText(title)
        canvas.drawText(title, (width - titleW) / 2f, 46f * density, ink)
        if (m.frame == "wxhome" || m.frame == "wxbook") {
            ink.textSize = 16f * density
            canvas.drawText("··", 12f * density, 46f * density, ink)
            canvas.drawText("＋", width - 24f * density, 46f * density, ink)
        }
        if (m.frame == "wxchat" || m.frame == "wxdetail" || m.frame == "wxadd") {
            canvas.drawText("<", 12f * density, 46f * density, ink)
        }
        if (m.frame == "wxchat") canvas.drawText("⋯", width - 22f * density, 46f * density, ink)
        var y = 58f * density
        if (m.frame == "wxhome" || m.frame == "wxbook" || m.frame == "wxadd") {
            val search = RectF(12f * density, y, width - 12f * density, y + 26f * density)
            fillRound(canvas, search, Color.parseColor("#F5F5F5"), 8f * density)
            mute.color = Color.parseColor("#8E8E93")
            mute.textSize = 12f * density
            canvas.drawText(if (m.frame == "wxadd") "搜索 账号/手机号" else "搜索", 22f * density, y + 17f * density, mute)
            y += 34f * density
        }
        val rows = pageRows(m)
        val colors = pageColors(m.frame)
        rows.forEachIndexed { i, row ->
            if (y > height - 36f * density) return@forEachIndexed
            if (m.frame == "wxhome" || m.frame == "wxbook" || m.frame == "wxmoments") {
                card.color = colors[i % colors.size]
                canvas.drawRoundRect(12f * density, y, 36f * density, y + 24f * density, 4f * density, 4f * density, card)
                ink.color = Color.parseColor("#181818")
                ink.textSize = 13f * density
                ink.typeface = Typeface.DEFAULT
                val bits = row.split(" ", limit = 3)
                canvas.drawText(fit(bits.getOrElse(0) { row }, width * 0.46f), 42f * density, y + 12f * density, ink)
                mute.color = Color.parseColor("#8E8E93")
                mute.textSize = 11f * density
                if (bits.size > 1) canvas.drawText(fit(previewOf(bits[1]), width * 0.42f), 42f * density, y + 24f * density, mute)
                if (bits.size > 2) {
                    val tw = mute.measureText(bits[2])
                    canvas.drawText(bits[2], width - 12f * density - tw, y + 12f * density, mute)
                }
                y += 34f * density
            } else if (m.frame == "wxchat") {
                val mine = i % 2 == 1
                val kind = messageKind(row)
                val fill = when {
                    kind == "pay" -> Color.parseColor("#F7D48B")
                    kind == "paid" -> Color.parseColor("#F8E8C8")
                    mine -> Color.parseColor("#76E858")
                    else -> Color.WHITE
                }
                ink.textSize = 13f * density
                val parts = bubbleLines(row, width * 0.58f)
                val w = parts.maxOf { ink.measureText(it) } + 18f * density
                val blockH = parts.size * 18f * density + 10f * density
                val left = if (mine) width - 42f * density - w else 40f * density
                card.color = if (mine) Color.parseColor("#F0D8E8") else Color.parseColor("#D8E8F0")
                canvas.drawCircle(if (mine) width - 20f * density else 20f * density, y + 12f * density, 10f * density, card)
                val bubble = RectF(left, y, left + w, y + blockH)
                fillRound(canvas, bubble, fill, 6f * density)
                ink.color = Color.parseColor("#333333")
                var ty = y + 16f * density
                parts.forEach { part ->
                    canvas.drawText(part, bubble.left + 8f * density, ty, ink)
                    ty += 18f * density
                }
                y += blockH + 8f * density
            } else if (m.frame == "wxedit") {
                if (i == 0) {
                    mute.color = Color.parseColor("#8E8E93")
                    mute.textSize = 14f * density
                    canvas.drawText(row.ifBlank { "这一刻的想法…" }, 14f * density, y + 16f * density, mute)
                    y += 28f * density
                    repeat(3) { n ->
                        val cell = 42f * density
                        val left = 14f * density + n * (cell + 6f * density)
                        fillRound(canvas, RectF(left, y, left + cell, y + cell), Color.parseColor("#F0F0F0"), 4f * density)
                        if (n == 2) {
                            ink.color = Color.parseColor("#8E8E93")
                            ink.textSize = 20f * density
                            canvas.drawText("+", left + 14f * density, y + 28f * density, ink)
                        }
                    }
                    y += 52f * density
                } else {
                    drawMenuRow(canvas, row, y, false)
                    y += 28f * density
                }
            } else if (m.frame == "wxdetail") {
                val on = row.endsWith("开")
                drawMenuRow(canvas, row.removeSuffix("开").removeSuffix("关"), y, on)
                y += 28f * density
            } else {
                drawMenuRow(canvas, row, y, m.frame == "wxfind" && i == 0)
                y += 28f * density
            }
        }
        if (m.frame in setOf("wxhome", "wxbook", "wxfind", "wxme")) {
            drawTabBar(canvas, when (m.frame) {
                "wxbook" -> 1
                "wxfind" -> 2
                "wxme" -> 3
                else -> 0
            })
        }
    }

    private fun drawCardRows(canvas: Canvas, m: Model) {
        val paper = colorOr(m.paperColor, "#F7F1FB")
        val textInk = colorOr(m.pageColor, "#2F4F4F")
        val lineInk = colorOr(m.accentColor, "#C7A8E0")
        ink.color = colorOr(m.inkColor, "#2F2440")
        ink.textSize = 15f * density
        ink.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(fit(m.title.ifBlank { "聊天美化" }, width - 28f * density), 16f * density, 26f * density, ink)
        accent.color = lineInk
        canvas.drawRect(16f * density, 34f * density, 58f * density, 37f * density, accent)
        var y = 48f * density
        ink.textSize = 13f * density
        ink.typeface = Typeface.DEFAULT
        beautyRows(m).forEachIndexed { i, row ->
            val parts = wrapText(row, ink, width - 48f * density)
            val blockH = parts.size * 18f * density + 10f * density
            if (y + blockH > height - 12f * density && i > 0) return@forEachIndexed
            val bubble = RectF(14f * density, y, width - 14f * density, y + blockH)
            fillRound(canvas, bubble, paper, 12f * density)
            ink.color = textInk
            var ty = y + 16f * density
            parts.forEach { part ->
                canvas.drawText(part, 24f * density, ty, ink)
                ty += 18f * density
            }
            y += blockH + 8f * density
        }
    }

    private fun drawStatus(canvas: Canvas, hex: String) {
        ink.color = Color.parseColor(hex)
        ink.textSize = 11f * density
        ink.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(clockShort(), 12f * density, 16f * density, ink)
        mute.color = Color.parseColor(hex)
        mute.textSize = 10f * density
        val right = "电量"
        canvas.drawText(right, width - 12f * density - mute.measureText(right), 16f * density, mute)
    }

    private fun drawMenuRow(canvas: Canvas, text: String, y: Float, highlight: Boolean) {
        ink.color = if (highlight) Color.parseColor("#07C160") else Color.parseColor("#262626")
        ink.textSize = 13f * density
        ink.typeface = Typeface.DEFAULT
        canvas.drawText(fit(text, width - 42f * density), 16f * density, y + 16f * density, ink)
        mute.color = Color.parseColor("#8E8E93")
        mute.textSize = 12f * density
        canvas.drawText(">", width - 20f * density, y + 16f * density, mute)
        track.color = Color.parseColor("#E0E0E0")
        track.strokeWidth = 1f * density
        canvas.drawLine(16f * density, y + 24f * density, width - 12f * density, y + 24f * density, track)
    }

    private fun drawTabBar(canvas: Canvas, active: Int) {
        val tabs = listOf("微信", "通讯录", "发现", "我")
        val y = height - 18f * density
        track.color = Color.parseColor("#D0D0D0")
        canvas.drawLine(8f * density, y - 14f * density, width - 8f * density, y - 14f * density, track)
        ink.textSize = 11f * density
        ink.typeface = Typeface.DEFAULT
        tabs.forEachIndexed { i, tab ->
            ink.color = if (i == active) Color.parseColor("#07C160") else Color.parseColor("#181818")
            val x = width * (i + 0.5f) / tabs.size - ink.measureText(tab) / 2f
            canvas.drawText(tab, x, y, ink)
        }
    }

    private fun fillRound(canvas: Canvas, rect: RectF, color: Int, rad: Float) {
        if (Color.alpha(color) == 0) return
        card.color = color
        card.style = Paint.Style.FILL
        canvas.drawRoundRect(rect, rad, rad, card)
    }

    private fun strokeRound(canvas: Canvas, rect: RectF, color: Int, rad: Float, widthPx: Float) {
        track.color = color
        track.style = Paint.Style.STROKE
        track.strokeWidth = widthPx
        canvas.drawRoundRect(rect, rad, rad, track)
        track.style = Paint.Style.FILL
    }

    private fun triangle(x: Float, y: Float, pointLeft: Boolean): android.graphics.Path {
        val p = android.graphics.Path()
        val s = 6f * density
        if (pointLeft) {
            p.moveTo(x + s, y - s)
            p.lineTo(x, y)
            p.lineTo(x + s, y + s)
        } else {
            p.moveTo(x - s, y - s)
            p.lineTo(x, y)
            p.lineTo(x - s, y + s)
        }
        p.close()
        return p
    }

    private fun beautyRows(m: Model): List<String> {
        val rows = m.lines.map { it.trim() }.filter { it.isNotEmpty() }
        return if (rows.isEmpty()) listOf("这一页打开了") else rows
    }

    private fun colorOr(color: Int, hex: String): Int {
        return if (color != 0) color else Color.parseColor(hex)
    }

    private fun fit(text: String, maxWidth: Float): String {
        val src = text.trim().ifBlank { " " }
        if (ink.measureText(src) <= maxWidth) return src
        return wrapText(src, ink, maxWidth).firstOrNull().orEmpty()
    }

    /** 气泡按折行长高，不再只留第一行。 */
    private fun bubbleLines(text: String, maxWidth: Float): List<String> {
        val src = text.trim().ifBlank { " " }
        return wrapText(src, ink, maxWidth.coerceAtLeast(24f * density))
    }

    private fun splitTranslate(row: String): Pair<String, String> {
        val marks = listOf(" / ", "｜", "|", " 翻译")
        marks.forEach { mark ->
            val i = row.indexOf(mark)
            if (i > 0) return row.substring(0, i).trim() to row.substring(i + mark.length).trim()
        }
        return row to ""
    }

    private fun splitNote(row: String, index: Int): List<String> {
        val bits = row.split("|").map { it.trim() }
        if (bits.size >= 4) return bits
        return listOf(
            "· 地点·角色名",
            if (bits.size > 1) bits[0] else "走近一点",
            bits.getOrElse(0) { row },
            bits.getOrElse(1) { "心里闪过一句" }
        )
    }

    private data class RoleBits(val name: String, val action: String, val talk: String, val time: String, val os: String)

    private fun splitRole(row: String, named: Boolean): RoleBits {
        val bits = row.split("|").map { it.trim() }
        if (bits.size >= 4) {
            return RoleBits(if (named) "" else bits[0], bits[1], bits[2], bits.getOrElse(3) { "19:02" }, bits.getOrElse(4) { "" })
        }
        return RoleBits(if (named) "" else "沈渡", "", row, "19:02", "")
    }

    private fun splitNamed(row: String): Pair<String, String> {
        val i = row.indexOf("：")
        if (i in 1..8) return row.substring(0, i) to row.substring(i + 1)
        val j = row.indexOf(":")
        if (j in 1..12 && row.substring(0, j).all { it.code < 128 }) return row.substring(0, j) to row.substring(j + 1)
        return "" to row
    }
    /** 椭圆皮肤和微信页共用：认出语音、转账、已收款、图片、拍一拍和通话。 */
    private fun messageKind(text: String): String {
        val s = text.trim()
        if (s.contains("拍了拍") || s.contains("拍一拍")) return "pat"
        if (s.contains("已收款") || s.contains("已被接收") || s.contains("已退还") || s.contains("已被退还")) return "paid"
        if (s.contains("请收款") || s.contains("待收款") || s.contains("转账")) return "pay"
        if (s.contains("语音") || s.contains("转文字") || s.contains("▶")) return "voice"
        if (s.contains("图片") || s.contains("表情") || s.contains(".jpg") || s.contains(".png")) return "image"
        if (s.contains("通话") || s.contains("待接听") || s.contains("挂断")) return "call"
        return "text"
    }


    /** 语音条按原文画波形，下面另起转文字，不再把整句塞进一个三角。 */
    private fun drawVoiceBar(
        canvas: Canvas,
        text: String,
        lineInk: Int,
        textInk: Int,
        hollow: Boolean,
        startY: Float
    ): Float {
        var y = startY
        val bubble = RectF(14f * density, y, 14f * density + 132f * density, y + 32f * density)
        if (!hollow) fillRound(canvas, bubble, blendPage(lineInk), bubble.height() / 2f)
        strokeRound(canvas, bubble, lineInk, bubble.height() / 2f, 1.5f * density)
        ink.color = textInk
        ink.textSize = 13f * density
        canvas.drawText("▶", bubble.left + 10f * density, y + 21f * density, ink)
        accent.color = lineInk
        val bars = intArrayOf(6, 10, 14, 8, 16, 11, 7, 13, 9)
        var x = bubble.left + 28f * density
        bars.forEach { h ->
            val top = bubble.centerY() - h * density * 0.45f
            canvas.drawRoundRect(x, top, x + 3f * density, bubble.centerY() + h * density * 0.45f, 1.5f * density, 1.5f * density, accent)
            x += 8f * density
        }
        mute.color = Color.parseColor("#888888")
        mute.textSize = 10f * density
        canvas.drawText("0:03", bubble.right - 28f * density, y + 20f * density, mute)
        y += 40f * density
        val body = text.replace("语音", "").replace("转文字", "").trim()
        mute.textSize = 11f * density
        val caption = if (body.isBlank()) "[转文字]" else body
        bubbleLines(caption, width - 48f * density).forEach { part ->
            canvas.drawText(part, 22f * density, y + 10f * density, mute)
            y += 16f * density
        }
        if (hollow) {
            canvas.drawText(clockShort(), bubble.right + 4f * density, bubble.bottom - 4f * density, mute)
        }
        return y + 8f * density
    }
    /** 通话按原文画四段：时长、名字、转文字、挂断。 */
    private fun drawCallBlock(
        canvas: Canvas,
        name: String,
        text: String,
        lineInk: Int,
        textInk: Int,
        startY: Float
    ): Float {
        var y = startY
        val hung = text.contains("挂断") || text.contains("ᴅ")
        val waiting = text.contains("待接听") || text.contains("未应答") || text.contains("已拒绝") || text.contains("已取消")
        val head = if (hung) "ᴅ 已挂断" else if (waiting) "待接听" else "通话中"
        ink.color = textInk
        ink.textSize = 12f * density
        ink.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(head, 16f * density, y + 14f * density, ink)
        mute.color = lineInk
        mute.textSize = 11f * density
        val time = clockShort()
        canvas.drawText(time, width - 16f * density - mute.measureText(time), y + 14f * density, mute)
        y += 22f * density
        val who = name.ifBlank { "小沫" }
        val box = RectF(16f * density, y, 16f * density + ink.measureText(who) + 20f * density, y + 24f * density)
        strokeRound(canvas, box, lineInk, 8f * density, 1.2f * density)
        canvas.drawText(who, box.left + 10f * density, y + 16f * density, ink)
        y += 32f * density
        val body = text.replace("待接听", "").replace("通话中", "").replace("已挂断", "").trim().ifBlank { " " }
        bubbleLines(body, width - 48f * density).forEach { part ->
            mute.color = Color.parseColor("#666666")
            mute.textSize = 12f * density
            canvas.drawText(part, 18f * density, y + 12f * density, mute)
            y += 16f * density
        }
        y += 6f * density
        val btn = "ᴅ 挂断"
        val bw = mute.measureText(btn) + 24f * density
        val button = RectF((width - bw) / 2f, y, (width + bw) / 2f, y + 26f * density)
        fillRound(canvas, button, Color.parseColor("#F3D0D0"), 12f * density)
        ink.color = Color.parseColor("#A33A3A")
        ink.textSize = 12f * density
        canvas.drawText(btn, button.left + 12f * density, y + 17f * density, ink)
        return y + 36f * density
    }
    private fun skinBody(kind: String, text: String, skin: SkinMark): String {
        val body = text.trim().ifBlank { " " }
        return when (kind) {
            "voice" -> "▶ " + body
            "pay" -> "⇋ 请收款  " + body
            "paid" -> if (body.contains("退")) "↺ 已被退还  " + body else "✓ 已被接收  " + body
            "image" -> "[图片] " + body
            "call" -> "☎ " + body
            "pat" -> body
            else -> skin.left + " " + body + " " + skin.right
        }
    }

    private fun imageCaption(text: String): String {
        val s = text.trim()
        val marks = listOf("描述", "图片", ".jpg", ".png")
        var rest = s
        marks.forEach { mark ->
            val i = rest.indexOf(mark)
            if (i >= 0) rest = rest.substring(i + mark.length)
        }
        rest = rest.trim().trim('：', ':', ']', '】', ' ')
        return if (rest.isBlank() || rest == s) "[图片描述]" else rest
    }
    private fun previewOf(text: String): String {
        val s = text.trim()
        return when (messageKind(s)) {
            "voice" -> if (s.startsWith("[")) s else "[语音] " + s
            "image" -> if (s.startsWith("[")) s else "[图片] " + s
            "pay" -> if (s.contains("转账") || s.contains("收款")) s else "[转账] " + s
            "call" -> if (s.contains("通话")) s else "[通话] " + s
            else -> s
        }
    }
    private fun clockLine(): String {
        val now = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        return "【 系统时间 " + now + " 】"
    }
    private fun clockShort(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    /**
     * 一套椭圆皮肤的全部视觉要素。
     *
     * color     框线色（原文给的色码）
     * left/right 框内固定颜文字
     * patColor  拍一拍文字色（原文要求与主色同系、略深）
     * textColor 框内正文色
     */
    private data class SkinMark(
        val color: String,
        val left: String,
        val right: String,
        val patColor: String,
        val textColor: String
    )

    private fun skinMark(skin: String): SkinMark = when (skin) {
        "berry" -> SkinMark("#F2B5B5", "⪩. .⪨", "⪩. .⪨", "#C97E7E", "#6B3A3A")
        "mint" -> SkinMark("#B0D9B0", "⋆｡°✩", "✩°｡⋆", "#6B8E6B", "#3E5B3E")
        "pudding" -> SkinMark("#F7D48B", "૮₍ ˃ ꇴ ˂ ₎ა", "૮₍ ˃ ꇴ ˂ ₎ა", "#C9A05A", "#7A5A22")
        "taro" -> SkinMark("#C7A8E0", "・✭・", "・✭・", "#8F6FB0", "#4A3560")
        "orange" -> SkinMark("#F4B886", "ʚɞ", "ʚɞ", "#C97E4E", "#7A4A22")
        else -> SkinMark("#C7A8E0", "・✭・", "・✭・", "#8F6FB0", "#4A3560")
    }

    private fun pageTitle(m: Model): String = when (m.frame) {
        "wxhome" -> "微信"
        "wxfind" -> "发现"
        "wxme" -> "我"
        "wxbook" -> "通讯录"
        "wxadd" -> "添加朋友"
        "wxdetail" -> "聊天详情"
        "wxchat" -> m.title.ifBlank { "聊天" }
        "wxedit" -> ""
        "wxfriend" -> m.title.ifBlank { "朋友圈" }
        "wxmoments" -> "朋友圈"
        else -> m.title
    }

    private fun pageRows(m: Model): List<String> {
        if (m.lines.isNotEmpty()) return m.lines
        return when (m.frame) {
            "wxhome" -> listOf("小柒 图片 14:30", "肆野 在吗 13:15", "江屿 语音 12:48", "沈倦 链接 11:20", "林予 视频 昨天")
            "wxfind" -> listOf("朋友圈", "扫一扫", "听一听", "看一看", "搜一搜", "游戏", "小程序")
            "wxme" -> listOf("服务", "收藏", "朋友圈", "作品", "小店与卡包", "表情", "设置")
            "wxbook" -> listOf("新的朋友", "仅聊天的朋友", "群聊", "标签", "公众号", "服务号", "企业微信联系人")
            "wxadd" -> listOf("扫一扫", "手机联系人", "雷达", "企业微信联系人", "面对面建群", "公众号", "服务号")
            "wxdetail" -> listOf("查找聊天内容", "消息免打扰关", "置顶聊天关", "提醒关", "清空聊天记录", "投诉")
            "wxchat" -> listOf("主人，我在。", "我也在。", "想听你说话。")
            "wxedit" -> listOf("这一刻的想法…", "所在位置", "提醒谁看", "谁可以看 公开")
            "wxfriend" -> listOf("置顶", "今天 晚风刚好", "昨天 云很软")
            else -> listOf("朋友圈", "点赞")
        }
    }

    private fun pageColors(frame: String): IntArray {
        val hexes = when (frame) {
            "wxhome" -> arrayOf("#E8F0FE", "#FDE8E8", "#F0E8F8", "#FEF8E8", "#E8F8EE")
            "wxbook" -> arrayOf("#F7941D", "#F5A623", "#07C160", "#2772E6", "#2185F5", "#00A5E0", "#2478E8")
            else -> arrayOf("#D8E8F0", "#F0D8E8", "#E8F0FE")
        }
        return IntArray(hexes.size) { Color.parseColor(hexes[it]) }
    }

    private fun notePalette(): List<IntArray> {
        val rows = listOf(
            listOf("#D45A5A", "#F5D0D0", "#FFF0F0", "#B34444", "#CC8888"),
            listOf("#E8835A", "#F5F0EB", "#FFF8F0", "#7A4A3A", "#B09070"),
            listOf("#D48A6A", "#F0D0B8", "#FFF5E8", "#A06040", "#C09070"),
            listOf("#4A7A5A", "#E8F0E0", "#F5FAF0", "#2A5A3A", "#6A9A7A"),
            listOf("#5A8A8A", "#E0F0F0", "#F0FAFA", "#2A5A5A", "#7AAAAA"),
            listOf("#5A8AAA", "#B8D8E8", "#D6E8F5", "#2A5A7A", "#7AAAC8"),
            listOf("#6A9AC9", "#D0E8F8", "#F0F8FF", "#3A6A8A", "#8AB0D0"),
            listOf("#5A7AC0", "#C8D8F0", "#E8EEF8", "#2A4A7A", "#7A9AC0"),
            listOf("#6A5A7A", "#D8D0E0", "#F0EAF5", "#3A2A4A", "#9A8AAA"),
            listOf("#A0A8B0", "#D8DCE0", "#F0F2F5", "#5A6A7A", "#A0AAB0")
        )
        return rows.map { it.map { hex -> Color.parseColor(hex) }.toIntArray() }
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

    private fun drawDouyinApp(canvas: Canvas, m: Model) {
        // 深色背景
        bg.color = Color.parseColor("#1A1A2E")
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        // 顶部标题
        ink.color = Color.WHITE
        ink.textSize = 16f * density
        ink.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("抖音", width * 0.5f - ink.measureText("抖音") / 2f, 28f * density, ink)
        // 视频区域
        card.color = Color.parseColor("#2A2A3E")
        val videoTop = 40f * density
        val videoH = height * 0.45f
        canvas.drawRoundRect(16f * density, videoTop, width - 16f * density, videoTop + videoH, 12f * density, 12f * density, card)
        // 视频中央播放按钮
        accent.color = Color.WHITE
        val cx = width * 0.5f
        val cy = videoTop + videoH * 0.5f
        val tri = android.graphics.Path().apply {
            moveTo(cx - 8f * density, cy - 12f * density)
            lineTo(cx - 8f * density, cy + 12f * density)
            lineTo(cx + 10f * density, cy)
            close()
        }
        canvas.drawPath(tri, accent)
        // 右侧操作栏
        val rx = width - 24f * density
        listOf("♥ 4.2w", "💬 328", "↗ 1.1w", "⋯").forEachIndexed { i, txt ->
            accent.textSize = 11f * density
            canvas.drawText(cut(txt, 6), rx - accent.measureText(txt), videoTop + videoH + 24f * density + i * 22f * density, accent)
        }
        // 底部描述和导航
        ink.color = Color.WHITE
        ink.textSize = 12f * density
        canvas.drawText(cut(line(m, 0, "推荐"), 16), 16f * density, videoTop + videoH + 24f * density, ink)
        mute.color = Color.parseColor("#AAAAAA")
        mute.textSize = 11f * density
        canvas.drawText(cut(line(m, 1, "分享日常"), 20), 16f * density, videoTop + videoH + 44f * density, mute)
        // 底部导航
        ink.color = Color.WHITE
        ink.textSize = 13f * density
        val navY = height - 18f * density
        val navItems = listOf("首页", "朋友", "＋", "消息", "我")
        navItems.forEachIndexed { i, nav ->
            val navX = width * (i + 0.5f) / navItems.size
            val navW = ink.measureText(nav)
            canvas.drawText(nav, navX - navW / 2f, navY, ink)
        }
    }

    private fun drawTaobaoApp(canvas: Canvas, m: Model) {
        // 橙色背景
        bg.color = Color.parseColor("#FFF5E6")
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        // 顶部橙色搜索栏
        card.color = Color.parseColor("#FF5000")
        canvas.drawRect(0f, 0f, width.toFloat(), 48f * density, card)
        ink.color = Color.WHITE
        ink.textSize = 16f * density
        ink.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("淘宝", 16f * density, 30f * density, ink)
        // 搜索框
        card.color = Color.WHITE
        val searchBox = RectF(width * 0.30f, 12f * density, width - 16f * density, 36f * density)
        canvas.drawRoundRect(searchBox, 12f * density, 12f * density, card)
        mute.color = Color.parseColor("#999999")
        mute.textSize = 12f * density
        canvas.drawText("搜索商品", searchBox.left + 12f * density, 28f * density, mute)
        // 内容区
        ink.color = Color.parseColor("#333333")
        ink.textSize = 14f * density
        ink.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(cut(line(m, 0, "购物车"), 10), 16f * density, 70f * density, ink)
        // 商品列表
        val items = m.lines.drop(1).ifEmpty { listOf("猫粮 2袋", "新衣服", "喜欢的书") }
        items.take(3).forEachIndexed { i, item ->
            val itemY = 96f * density + i * 32f * density
            card.color = Color.parseColor("#FFE0B2")
            canvas.drawRoundRect(16f * density, itemY, 52f * density, itemY + 26f * density, 6f * density, 6f * density, card)
            ink.color = Color.parseColor("#333333")
            ink.textSize = 13f * density
            ink.typeface = Typeface.DEFAULT
            canvas.drawText(cut(item, 14), 62f * density, itemY + 18f * density, ink)
            accent.color = Color.parseColor("#FF5000")
            accent.textSize = 11f * density
            canvas.drawText("加入购物车", width - 76f * density, itemY + 18f * density, accent)
        }
        // 底部导航
        card.color = Color.parseColor("#FFFFFF")
        canvas.drawRect(0f, height - 40f * density, width.toFloat(), height.toFloat(), card)
        ink.color = Color.parseColor("#666666")
        ink.textSize = 12f * density
        val navItems = listOf("首页", "分类", "购物车", "我的")
        navItems.forEachIndexed { i, nav ->
            val navX = width * (i + 0.5f) / navItems.size
            canvas.drawText(nav, navX - ink.measureText(nav) / 2f, height - 16f * density, ink)
        }
    }

    private fun drawAlipayApp(canvas: Canvas, m: Model) {
        // 支付宝蓝背景
        bg.color = Color.parseColor("#1677FF")
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        // 顶部标题
        ink.color = Color.WHITE
        ink.textSize = 18f * density
        ink.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("支付宝", 16f * density, 30f * density, ink)
        // 余额卡片
        card.color = Color.parseColor("#4A9AFF")
        val balCard = RectF(16f * density, 48f * density, width - 16f * density, 100f * density)
        canvas.drawRoundRect(balCard, 12f * density, 12f * density, card)
        ink.color = Color.WHITE
        ink.textSize = 12f * density
        ink.typeface = Typeface.DEFAULT
        canvas.drawText("账户余额(元)", balCard.left + 16f * density, balCard.top + 20f * density, ink)
        ink.textSize = 24f * density
        ink.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(cut(line(m, 0, "128.50"), 10), balCard.left + 16f * density, balCard.top + 42f * density, ink)
        // 功能网格
        val features = listOf("扫一扫" to "💳", "收付款" to "💰", "花呗" to "💳", "余额宝" to "📈", "账单" to "📋", "更多" to "⋯")
        features.forEachIndexed { i, (name, icon) ->
            val col = i % 3
            val row = i / 3
            val cx = width * (col + 0.5f) / 3f
            val cy = 136f * density + row * 52f * density
            accent.color = Color.WHITE
            accent.textSize = 20f * density
            canvas.drawText(icon, cx - accent.measureText(icon) / 2f, cy, accent)
            mute.color = Color.parseColor("#E8F0FF")
            mute.textSize = 11f * density
            canvas.drawText(name, cx - mute.measureText(name) / 2f, cy + 18f * density, mute)
        }
    }

    private fun drawMapsApp(canvas: Canvas, m: Model) {
        // 地图绿背景
        bg.color = Color.parseColor("#E8F5E9")
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        // 顶部搜索栏
        card.color = Color.WHITE
        val searchBox = RectF(16f * density, 16f * density, width - 16f * density, 44f * density)
        canvas.drawRoundRect(searchBox, 14f * density, 14f * density, card)
        mute.color = Color.parseColor("#666666")
        mute.textSize = 12f * density
        canvas.drawText("搜索地点", searchBox.left + 24f * density, 34f * density, mute)
        // 模拟地图
        card.color = Color.parseColor("#A5D6A7")
        canvas.drawRect(0f, 52f * density, width.toFloat(), height - 40f * density, card)
        // 当前位置标记
        accent.color = Color.parseColor("#4285F4")
        val cx = width * 0.5f
        val cy = height * 0.5f
        canvas.drawCircle(cx, cy, 8f * density, accent)
        card.color = Color.WHITE
        canvas.drawCircle(cx, cy, 4f * density, card)
        // 底部功能
        ink.color = Color.parseColor("#333333")
        ink.textSize = 13f * density
        ink.typeface = Typeface.DEFAULT
        listOf("回家", "附近", "收藏").forEachIndexed { i, txt ->
            val fx = width * (i + 0.5f) / 3f
            canvas.drawText(txt, fx - ink.measureText(txt) / 2f, height - 16f * density, ink)
        }
    }

    private fun drawMeituanApp(canvas: Canvas, m: Model) {
        // 美团黄背景
        bg.color = Color.parseColor("#FFD700")
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        // 顶部标题
        ink.color = Color.parseColor("#333333")
        ink.textSize = 18f * density
        ink.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("美团", 16f * density, 30f * density, ink)
        // 搜索框
        card.color = Color.WHITE
        val searchBox = RectF(width * 0.28f, 12f * density, width - 16f * density, 36f * density)
        canvas.drawRoundRect(searchBox, 12f * density, 12f * density, card)
        mute.color = Color.parseColor("#999999")
        mute.textSize = 12f * density
        canvas.drawText("搜索商家", searchBox.left + 12f * density, 28f * density, mute)
        // 分类标签
        ink.color = Color.parseColor("#333333")
        ink.textSize = 12f * density
        ink.typeface = Typeface.DEFAULT
        val categories = listOf("外卖", "美食", "酒店", "电影")
        categories.forEachIndexed { i, cat ->
            val cx = width * (i + 0.5f) / categories.size
            canvas.drawText(cat, cx - ink.measureText(cat) / 2f, 58f * density, ink)
        }
        // 商家列表
        val shops = m.lines.ifEmpty { listOf("川菜 月销1200+", "奶茶 起送¥0", "火锅 4.8分") }
        shops.take(3).forEachIndexed { i, shop ->
            val shopY = 80f * density + i * 34f * density
            card.color = Color.WHITE
            canvas.drawRoundRect(16f * density, shopY, width - 16f * density, shopY + 28f * density, 8f * density, 8f * density, card)
            ink.color = Color.parseColor("#333333")
            ink.textSize = 13f * density
            canvas.drawText(cut(shop, 14), 28f * density, shopY + 18f * density, ink)
            accent.color = Color.parseColor("#FF5000")
            accent.textSize = 11f * density
            canvas.drawText("立即点餐", width - 72f * density, shopY + 18f * density, accent)
        }
    }

    private fun drawJdApp(canvas: Canvas, m: Model) {
        // 京东红背景
        bg.color = Color.parseColor("#E4393C")
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        // 顶部标题
        ink.color = Color.WHITE
        ink.textSize = 18f * density
        ink.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("京东", 16f * density, 30f * density, ink)
        // 搜索框
        card.color = Color.WHITE
        val searchBox = RectF(width * 0.28f, 12f * density, width - 16f * density, 36f * density)
        canvas.drawRoundRect(searchBox, 12f * density, 12f * density, card)
        mute.color = Color.parseColor("#999999")
        mute.textSize = 12f * density
        canvas.drawText("搜索商品", searchBox.left + 12f * density, 28f * density, mute)
        // 分类
        ink.color = Color.WHITE
        ink.textSize = 12f * density
        val categories = listOf("数码", "家电", "服饰", "食品")
        categories.forEachIndexed { i, cat ->
            val cx = width * (i + 0.5f) / categories.size
            canvas.drawText(cat, cx - ink.measureText(cat) / 2f, 58f * density, ink)
        }
        // 商品
        val goods = m.lines.ifEmpty { listOf("手机", "笔记本", "耳机") }
        goods.take(3).forEachIndexed { i, item ->
            val itemY = 80f * density + i * 32f * density
            card.color = Color.WHITE
            canvas.drawRoundRect(16f * density, itemY, width - 16f * density, itemY + 26f * density, 8f * density, 8f * density, card)
            ink.color = Color.parseColor("#333333")
            ink.textSize = 13f * density
            canvas.drawText(cut(item, 14), 28f * density, itemY + 18f * density, ink)
        }
    }

    private fun drawBilibiliApp(canvas: Canvas, m: Model) {
        // 弹幕蓝粉背景
        bg.color = Color.parseColor("#FB7299")
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        // 顶部标题
        ink.color = Color.WHITE
        ink.textSize = 18f * density
        ink.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("哔哩哔哩", 16f * density, 30f * density, ink)
        // 视频区域
        card.color = Color.parseColor("#1E1E1E")
        val videoTop = 40f * density
        val videoH = height * 0.42f
        canvas.drawRoundRect(16f * density, videoTop, width - 16f * density, videoTop + videoH, 12f * density, 12f * density, card)
        // 弹幕文字
        accent.color = Color.WHITE
        accent.textSize = 12f * density
        canvas.drawText("前方高能", videoTop + 20f * density, 20f * density, accent)
        canvas.drawText("233333", videoTop + videoH * 0.4f, 20f * density, accent)
        canvas.drawText("已三连", videoTop + videoH * 0.7f, 20f * density, accent)
        // 底部推荐
        ink.color = Color.WHITE
        ink.textSize = 13f * density
        ink.typeface = Typeface.DEFAULT
        canvas.drawText(cut(line(m, 0, "首页"), 10), 16f * density, videoTop + videoH + 24f * density, ink)
        // 底部导航
        mute.color = Color.WHITE
        mute.textSize = 12f * density
        val navItems = listOf("首页", "动态", "消息", "我的")
        navItems.forEachIndexed { i, nav ->
            val navX = width * (i + 0.5f) / navItems.size
            canvas.drawText(nav, navX - mute.measureText(nav) / 2f, height - 16f * density, mute)
        }
    }

    private fun drawWeiboApp(canvas: Canvas, m: Model) {
        // 微博橙背景
        bg.color = Color.parseColor("#E6162D")
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        // 顶部标题
        ink.color = Color.WHITE
        ink.textSize = 18f * density
        ink.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("微博", 16f * density, 30f * density, ink)
        // 热搜列表
        val hotSearches = m.lines.ifEmpty { listOf("今日热搜第一", "关注动态", "推荐话题") }
        var y = 56f * density
        hotSearches.take(4).forEachIndexed { i, item ->
            ink.color = if (i == 0) Color.parseColor("#FFD700") else Color.WHITE
            ink.textSize = if (i == 0) 14f * density else 13f * density
            ink.typeface = Typeface.DEFAULT
            canvas.drawText(cut(item, 16), 16f * density, y, ink)
            y += 26f * density
        }
        // 底部导航
        mute.color = Color.parseColor("#FFFFFF")
        mute.textSize = 12f * density
        val navItems = listOf("首页", "发现", "消息", "我")
        navItems.forEachIndexed { i, nav ->
            val navX = width * (i + 0.5f) / navItems.size
            canvas.drawText(nav, navX - mute.measureText(nav) / 2f, height - 16f * density, mute)
        }
    }

    private fun drawXiaohongshuApp(canvas: Canvas, m: Model) {
        // 小红书红背景
        bg.color = Color.parseColor("#FF2442")
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        // 顶部标题
        ink.color = Color.WHITE
        ink.textSize = 18f * density
        ink.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("小红书", 16f * density, 30f * density, ink)
        // 搜索框
        card.color = Color.WHITE
        val searchBox = RectF(width * 0.32f, 12f * density, width - 16f * density, 36f * density)
        canvas.drawRoundRect(searchBox, 12f * density, 12f * density, card)
        mute.color = Color.parseColor("#999999")
        mute.textSize = 12f * density
        canvas.drawText("搜索", searchBox.left + 12f * density, 28f * density, mute)
        // 内容瀑布流
        val items = m.lines.ifEmpty { listOf("好物分享", "穿搭灵感", "美食探店") }
        items.take(3).forEachIndexed { i, item ->
            val col = i % 2
            val row = i / 2
            val cardX = 16f * density + col * (width * 0.48f)
            val cardY = 52f * density + row * 56f * density
            val cardW = width * 0.44f
            val cardH = 48f * density
            card.color = Color.parseColor("#FFFFFF")
            canvas.drawRoundRect(cardX, cardY, cardX + cardW, cardY + cardH, 8f * density, 8f * density, card)
            ink.color = Color.parseColor("#333333")
            ink.textSize = 12f * density
            ink.typeface = Typeface.DEFAULT
            canvas.drawText(cut(item, 8), cardX + 8f * density, cardY + 20f * density, ink)
            accent.color = Color.parseColor("#FF6B6B")
            accent.textSize = 10f * density
            canvas.drawText("♡ 点赞", cardX + 8f * density, cardY + 38f * density, accent)
        }
    }

    private fun line(m: Model, index: Int, fallback: String): String {
        return m.lines.getOrNull(index).orEmpty().ifBlank { fallback }
    }

    private fun cut(text: String, n: Int): String {
        val t = text.trim()
        val room = ((width / baseDensity - 36f) / 13f).toInt()
        val limit = maxOf(n, room).coerceIn(4, 80)
        return if (t.length <= limit) t else t.take(limit) + "…"
    }

    private fun blendPage(color: Int): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return Color.rgb(
            (r * 0.72f + 255 * 0.28f).toInt().coerceIn(0, 255),
            (g * 0.72f + 255 * 0.28f).toInt().coerceIn(0, 255),
            (b * 0.72f + 255 * 0.28f).toInt().coerceIn(0, 255)
        )
    }

    /** 没拖过高度时，美化卡按实际版式长高，装饰圈、便签和整页不再被旧公式裁掉。 */
    /**
     * 按每种页真实占的行高预测高度，宁可高一点，绝不比实际画出来的矮。
     * 旧算法只按 13dp 字号估，遇到 14~12dp 混排、通话块、语音条时算矮，
     * 绘制就会在底部提前停笔，看起来像吞字。
     */
    private fun beautyHeight(m: Model, widthPx: Int): Int {
        val frame = m.frame.ifBlank { "card" }
        val rows = if (frame.startsWith("wx") && m.lines.isEmpty()) pageRows(m) else beautyRows(m)
        val rowCount = rows.size.coerceAtLeast(1)
        ink.textSize = 13f * density
        ink.typeface = Typeface.DEFAULT
        val textW = (widthPx - 40f * density).coerceAtLeast(48f * density)
        val wide = bubbleLines(rows.joinToString(" "), textW).size
        val body = when (frame) {
            "ornament" -> 56f + rowCount * 16f + wide * 24f
            "note" -> 24f + rowCount * 104f
            "terminal" -> 96f + rowCount * 22f + wide * 16f
            "bilingual", "wing", "bunny" -> 72f + rowCount * 64f + wide * 22f
            "face" -> 140f + rowCount * 46f + wide * 18f
            "role" -> 40f + rowCount * 68f + wide * 22f
            "oval" -> 36f + rowCount * 90f + wide * 24f
            "basic", "wechat" -> 40f + rowCount * 46f + wide * 20f
            "wxchat" -> 96f + rowCount * 42f + wide * 20f
            "wxedit" -> 170f + rowCount * 34f
            "wxhome", "wxbook", "wxmoments" -> 120f + rowCount * 40f
            "wxadd" -> 116f + rowCount * 34f
            "wxfind", "wxme" -> 100f + rowCount * 34f
            "wxdetail", "wxfriend" -> 110f + rowCount * 36f
            else -> 64f + rowCount * 46f + wide * 20f
        }
        val h = (body * density).toInt()
        val min = (160 * baseDensity).toInt()
        return h.coerceAtLeast(min)
    }

    /** 按画笔实际宽度折行，不把超出的字丢掉。 */
    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val src = text.trim().ifBlank { " " }
        if (paint.measureText(src) <= maxWidth) return listOf(src)
        val out = ArrayList<String>()
        var start = 0
        while (start < src.length) {
            val count = paint.breakText(src, start, src.length, true, maxWidth, null).coerceAtLeast(1)
            out.add(src.substring(start, start + count))
            start += count
        }
        return out
    }
}