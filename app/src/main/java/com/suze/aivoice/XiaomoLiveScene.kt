package com.suze.aivoice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** 方案 B 桌面动态壁纸共用画布：全身小沫、心情姿势、天气夜景、触摸互动、玻璃卡。 */
class XiaomoLiveScene(private val context: Context) {
    private val prefs = Prefs(context)
    private val emotion = EmotionEngine(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val path = Path()
    private val rect = RectF()
    private val seeds = List(48) { Triple(Random.nextFloat(), Random.nextFloat(), Random.nextFloat()) }
    private val hearts = ArrayList<Heart>(12)
    private var progress = 0f
    private var waveUntil = 0L
    private var blinkUntil = 0L
    var charLeft = 0f; private set
    var charTop = 0f; private set
    var charRight = 0f; private set
    var charBottom = 0f; private set

    data class Heart(var x: Float, var y: Float, var life: Float, var seed: Float)

    fun tick() {
        progress = (progress + 0.006f) % 1f
        val now = System.currentTimeMillis()
        if (now > blinkUntil && Random.nextFloat() < 0.012f) blinkUntil = now + 140L
        val it = hearts.iterator()
        while (it.hasNext()) {
            val h = it.next()
            h.life -= 0.018f
            h.y -= 2.4f + h.seed
            h.x += sin((1f - h.life) * 8f) * 1.4f
            if (h.life <= 0f) it.remove()
        }
    }

    fun tap(x: Float, y: Float): Boolean {
        if (!prefs.wallpaperTouchEnabled) return false
        val hit = x in (charLeft - 24f)..(charRight + 24f) && y in (charTop - 24f)..(charBottom + 24f)
        if (!hit && !(x in charLeft..charRight && y in charTop..charBottom)) {
            if (x < charLeft - 80f || x > charRight + 80f) return false
        }
        waveUntil = System.currentTimeMillis() + 1600L
        repeat(7) {
            hearts.add(Heart(x + Random.nextFloat() * 36f - 18f, y - 8f, 1f, Random.nextFloat() * 2f))
        }
        return true
    }

    fun draw(canvas: Canvas, w: Float, h: Float, xOffset: Float = 0f) {
        if (w < 8f || h < 8f) return
        val snap = snapshot()
        val shift = xOffset * w * 0.08f
        drawSky(canvas, w, h, snap)
        drawWeather(canvas, w, h, snap)
        drawPlaza(canvas, w, h, snap)
        drawCharacter(canvas, w, h, shift, snap)
        if (snap.cardsOn) drawCards(canvas, w, h, snap)
        drawHearts(canvas)
    }

    data class Snap(
        val mood: EmotionEngine.Mood,
        val skycon: String,
        val weatherLine: String,
        val remindLine: String,
        val moodLine: String,
        val moodOn: Boolean,
        val weatherOn: Boolean,
        val cardsOn: Boolean,
        val night: Boolean,
        val rainy: Boolean,
        val snowy: Boolean,
        val foggy: Boolean
    )

    private fun snapshot(): Snap {
        val mood = if (prefs.emotionEnabled && prefs.wallpaperMoodEnabled) emotion.currentMood() else EmotionEngine.Mood.CALM
        val skycon = if (prefs.wallpaperWeatherEnabled) resolveSkycon() else hourSkycon()
        val next = runCatching {
            val item = ReminderStore(context).upcoming().firstOrNull()
            if (item == null) "暂无提醒" else ReminderStore(context).formatWhen(item.atMillis) + "  " + item.text
        }.getOrDefault("暂无提醒")
        val weather = prefs.lastWeatherBrief.ifBlank { prefs.lastCity.ifBlank { "桌面小沫" } }
        val moodLine = if (prefs.emotionEnabled) emotion.currentMood().emoji + " " + emotion.statusLine() else "小沫在这儿"
        return Snap(
            mood = mood,
            skycon = skycon,
            weatherLine = weather.take(22),
            remindLine = next.take(22),
            moodLine = moodLine.take(22),
            moodOn = prefs.wallpaperMoodEnabled,
            weatherOn = prefs.wallpaperWeatherEnabled,
            cardsOn = prefs.wallpaperCardsEnabled,
            night = skycon.contains("NIGHT") || hourSkycon().contains("NIGHT"),
            rainy = skycon.contains("RAIN"),
            snowy = skycon.contains("SNOW"),
            foggy = skycon.contains("HAZE") || skycon.contains("FOG")
        )
    }

    private fun hourSkycon(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return if (hour in 19..23 || hour in 0..5) "CLEAR_NIGHT" else "CLEAR_DAY"
    }

    private fun resolveSkycon(): String {
        val saved = prefs.lastWeatherSkycon.trim()
        if (saved.isNotBlank()) return saved
        val brief = prefs.lastWeatherBrief
        return when {
            brief.contains("雪") -> "SNOW"
            brief.contains("雨") || brief.contains("雷") -> "RAIN"
            brief.contains("雾") || brief.contains("霾") -> "FOG"
            brief.contains("云") -> "CLOUDY"
            brief.contains("夜") || brief.contains("月") -> "CLEAR_NIGHT"
            else -> hourSkycon()
        }
    }

    private fun drawSky(canvas: Canvas, w: Float, h: Float, snap: Snap) {
        val top = when {
            snap.night -> Color.rgb(18, 16, 48)
            snap.rainy -> Color.rgb(58, 78, 112)
            snap.foggy -> Color.rgb(92, 104, 122)
            snap.snowy -> Color.rgb(132, 154, 188)
            else -> Color.rgb(86, 78, 214)
        }
        val mid = when {
            snap.night -> Color.rgb(46, 36, 92)
            snap.rainy -> Color.rgb(78, 96, 132)
            snap.foggy -> Color.rgb(140, 148, 162)
            else -> Color.rgb(124, 92, 196)
        }
        val bottom = when {
            snap.night -> Color.rgb(12, 10, 22)
            snap.rainy -> Color.rgb(36, 42, 64)
            else -> Color.rgb(18, 14, 36)
        }
        paint.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(top, mid, bottom), floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        if (snap.night) {
            paint.color = 0xCCFFFFFF.toInt()
            seeds.take(28).forEachIndexed { i, s ->
                paint.alpha = (90 + 140 * ((sin(progress * 6.28 + i) + 1) / 2)).toInt()
                canvas.drawCircle(s.first * w, s.second * h * 0.55f, 1.2f + s.third * 2.2f, paint)
            }
            paint.alpha = 255
            val mx = w * 0.82f; val my = h * 0.14f
            paint.shader = RadialGradient(mx, my, w * 0.12f, intArrayOf(0xFFFFF4C8.toInt(), 0x55FFE9A0.toInt(), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(mx, my, w * 0.12f, paint)
            paint.shader = null
            paint.color = 0xFFF7E7A8.toInt()
            canvas.drawCircle(mx, my, w * 0.045f, paint)
        } else {
            val sx = w * 0.84f; val sy = h * 0.12f; val sr = w * 0.16f
            paint.shader = RadialGradient(sx, sy, sr, intArrayOf(0xFFFFF3B0.toInt(), 0x66FFD36A.toInt(), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(sx, sy, sr, paint)
            paint.shader = null
        }
    }

    private fun drawWeather(canvas: Canvas, w: Float, h: Float, snap: Snap) {
        when {
            snap.rainy -> {
                paint.color = 0x99D9EEFF.toInt(); paint.strokeWidth = 3f
                seeds.forEach { s ->
                    val y = ((s.second + progress * (1.2f + s.third)) % 1f) * h
                    val x = s.first * w
                    canvas.drawLine(x, y, x - 10f, y + 36f, paint)
                }
            }
            snap.snowy -> {
                paint.color = 0xDDFFFFFF.toInt()
                seeds.forEachIndexed { i, s ->
                    val y = ((s.second + progress * (0.22f + s.third * 0.2f)) % 1f) * h
                    val x = s.first * w + sin(progress * 6.28f + i) * 18f
                    canvas.drawCircle(x, y, 2.2f + s.third * 5f, paint)
                }
            }
            snap.foggy -> {
                paint.color = 0x28FFFFFF.toInt()
                repeat(6) { i ->
                    val shift = if (i % 2 == 0) progress * w * 0.12f else -progress * w * 0.12f
                    canvas.drawOval(-w * 0.15f + shift, h * (0.18f + i * 0.12f), w * 1.1f + shift, h * (0.32f + i * 0.12f), paint)
                }
            }
            else -> {
                paint.color = 0x33FFFFFF.toInt()
                repeat(4) { i ->
                    val x = ((progress * w * 0.16f + i * w * 0.34f) % (w * 1.35f)) - w * 0.2f
                    val y = h * (0.16f + i * 0.08f)
                    canvas.drawOval(x, y, x + w * 0.38f, y + h * 0.08f, paint)
                }
            }
        }
        paint.alpha = 255
    }

    private fun drawPlaza(canvas: Canvas, w: Float, h: Float, snap: Snap) {
        val y = h * 0.78f
        paint.shader = LinearGradient(0f, y, 0f, h, intArrayOf(0x33221C3A.toInt(), 0xCC08080C.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, y, w, h, paint)
        paint.shader = null
        val cx = w * 0.5f
        paint.color = 0x55FFFFFF.toInt()
        canvas.drawOval(cx - w * 0.28f, h * 0.80f, cx + w * 0.28f, h * 0.88f, paint)
        paint.color = if (snap.night) 0x667C6BFF.toInt() else 0x88D0BCFF.toInt()
        canvas.drawOval(cx - w * 0.22f, h * 0.815f, cx + w * 0.22f, h * 0.86f, paint)
    }

    private fun drawCharacter(canvas: Canvas, w: Float, h: Float, shift: Float, snap: Snap) {
        val unit = min(w, h)
        val s = unit * 0.00115f
        val bounce = when (snap.mood) {
            EmotionEngine.Mood.HAPPY -> sin(progress * 12.56f) * 10f * s / 0.00115f
            EmotionEngine.Mood.TIRED -> 18f
            EmotionEngine.Mood.SAD -> 12f
            else -> sin(progress * 6.28f) * 4f
        }
        val sway = when (snap.mood) {
            EmotionEngine.Mood.CURIOUS -> 10f
            EmotionEngine.Mood.ANNOYED -> -6f
            else -> sin(progress * 6.28f) * 3.2f
        }
        val cx = w * 0.5f + shift + sway
        val baseY = h * 0.82f + bounce
        val sit = snap.mood == EmotionEngine.Mood.TIRED
        val bodyH = if (sit) unit * 0.42f else unit * 0.52f
        val bodyW = unit * 0.22f
        charLeft = cx - bodyW * 0.85f
        charRight = cx + bodyW * 0.85f
        charTop = baseY - bodyH * 1.08f
        charBottom = baseY + unit * 0.02f

        paint.color = 0x66000000.toInt()
        canvas.drawOval(cx - bodyW * 0.55f, baseY - 8f, cx + bodyW * 0.55f, baseY + 18f, paint)

        val waving = System.currentTimeMillis() < waveUntil
        drawLegs(canvas, cx, baseY, bodyW, bodyH, sit, snap)
        drawDress(canvas, cx, baseY, bodyW, bodyH, sit, snap)
        drawArms(canvas, cx, baseY, bodyW, bodyH, sit, waving, snap)
        drawHead(canvas, cx, baseY, bodyW, bodyH, sit, snap)
    }

    private fun drawLegs(canvas: Canvas, cx: Float, baseY: Float, bodyW: Float, bodyH: Float, sit: Boolean, snap: Snap) {
        val skin = 0xFFF3C7B3.toInt()
        val shoe = 0xFF3B3358.toInt()
        if (sit) {
            paint.color = skin
            canvas.drawRoundRect(cx - bodyW * 0.62f, baseY - bodyH * 0.18f, cx - bodyW * 0.18f, baseY + 6f, 18f, 18f, paint)
            canvas.drawRoundRect(cx + bodyW * 0.18f, baseY - bodyH * 0.18f, cx + bodyW * 0.62f, baseY + 6f, 18f, 18f, paint)
            paint.color = shoe
            canvas.drawOval(cx - bodyW * 0.68f, baseY - 4f, cx - bodyW * 0.22f, baseY + 16f, paint)
            canvas.drawOval(cx + bodyW * 0.22f, baseY - 4f, cx + bodyW * 0.68f, baseY + 16f, paint)
        } else {
            val kick = if (snap.mood == EmotionEngine.Mood.HAPPY) sin(progress * 12.56f) * 8f else 0f
            paint.color = skin
            canvas.drawRoundRect(cx - bodyW * 0.28f, baseY - bodyH * 0.28f, cx - bodyW * 0.08f, baseY + kick, 16f, 16f, paint)
            canvas.drawRoundRect(cx + bodyW * 0.08f, baseY - bodyH * 0.28f, cx + bodyW * 0.28f, baseY - kick, 16f, 16f, paint)
            paint.color = shoe
            canvas.drawOval(cx - bodyW * 0.32f, baseY - 6f + kick, cx - bodyW * 0.04f, baseY + 16f + kick, paint)
            canvas.drawOval(cx + bodyW * 0.04f, baseY - 6f - kick, cx + bodyW * 0.32f, baseY + 16f - kick, paint)
        }
    }

    private fun drawDress(canvas: Canvas, cx: Float, baseY: Float, bodyW: Float, bodyH: Float, sit: Boolean, snap: Snap) {
        val top = baseY - bodyH * (if (sit) 0.62f else 0.72f)
        val bottom = baseY - bodyH * (if (sit) 0.12f else 0.22f)
        path.reset()
        path.moveTo(cx - bodyW * 0.18f, top)
        path.quadTo(cx - bodyW * 0.62f, (top + bottom) / 2f, cx - bodyW * 0.48f, bottom)
        path.quadTo(cx, bottom + 18f, cx + bodyW * 0.48f, bottom)
        path.quadTo(cx + bodyW * 0.62f, (top + bottom) / 2f, cx + bodyW * 0.18f, top)
        path.close()
        paint.shader = LinearGradient(cx, top, cx, bottom, intArrayOf(0xFFEDE9FF.toInt(), 0xFF7C6BFF.toInt(), 0xFF5746D8.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawPath(path, paint)
        paint.shader = null
        paint.color = 0x66FFFFFF.toInt()
        canvas.drawOval(cx - bodyW * 0.16f, top + 8f, cx + bodyW * 0.16f, top + bodyH * 0.18f, paint)
        paint.color = 0xFFF06491.toInt()
        canvas.drawCircle(cx, top + 14f, 7f, paint)
        if (snap.mood == EmotionEngine.Mood.HAPPY) {
            paint.color = 0x88FFF1A8.toInt()
            canvas.drawCircle(cx, top - 8f, 10f, paint)
        }
    }

    private fun drawArms(canvas: Canvas, cx: Float, baseY: Float, bodyW: Float, bodyH: Float, sit: Boolean, waving: Boolean, snap: Snap) {
        val skin = 0xFFF3C7B3.toInt()
        val shoulderY = baseY - bodyH * (if (sit) 0.58f else 0.68f)
        paint.color = skin
        paint.strokeWidth = bodyW * 0.16f
        paint.strokeCap = Paint.Cap.ROUND
        paint.style = Paint.Style.STROKE
        val shy = snap.mood == EmotionEngine.Mood.SHY
        val angry = snap.mood == EmotionEngine.Mood.ANNOYED
        val leftY = when {
            shy -> shoulderY - bodyH * 0.08f
            angry -> shoulderY + bodyH * 0.02f
            else -> shoulderY + bodyH * 0.16f
        }
        val rightLift = when {
            waving -> shoulderY - bodyH * 0.28f - sin(progress * 18f) * 18f
            snap.mood == EmotionEngine.Mood.HAPPY -> shoulderY - bodyH * 0.18f
            snap.mood == EmotionEngine.Mood.CURIOUS -> shoulderY - bodyH * 0.02f
            shy -> shoulderY - bodyH * 0.1f
            else -> shoulderY + bodyH * 0.16f
        }
        canvas.drawLine(cx - bodyW * 0.2f, shoulderY, cx - bodyW * 0.52f, leftY, paint)
        canvas.drawLine(cx + bodyW * 0.2f, shoulderY, cx + bodyW * 0.52f, rightLift, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx - bodyW * 0.52f, leftY, bodyW * 0.09f, paint)
        canvas.drawCircle(cx + bodyW * 0.52f, rightLift, bodyW * 0.09f, paint)
        if (waving || snap.mood == EmotionEngine.Mood.HAPPY) {
            paint.color = 0xFFF06491.toInt()
            canvas.drawCircle(cx + bodyW * 0.52f, rightLift, bodyW * 0.045f, paint)
        }
    }

    private fun drawHead(canvas: Canvas, cx: Float, baseY: Float, bodyW: Float, bodyH: Float, sit: Boolean, snap: Snap) {
        val headY = baseY - bodyH * (if (sit) 0.78f else 0.92f)
        val headR = bodyW * 0.62f
        val hair = 0xFF5746D8.toInt()
        val skin = 0xFFF3C7B3.toInt()
        val tilt = when (snap.mood) {
            EmotionEngine.Mood.CURIOUS -> 8f
            EmotionEngine.Mood.SHY -> -10f
            EmotionEngine.Mood.SAD -> 6f
            EmotionEngine.Mood.ANNOYED -> -4f
            else -> sin(progress * 6.28f) * 2f
        }
        canvas.save()
        canvas.rotate(tilt, cx, headY)
        paint.color = hair
        canvas.drawOval(cx - headR * 1.15f, headY - headR * 1.25f, cx + headR * 1.15f, headY + headR * 0.85f, paint)
        path.reset()
        path.moveTo(cx - headR * 1.2f, headY)
        path.quadTo(cx - headR * 1.55f, headY + headR * 1.4f, cx - headR * 0.7f, headY + headR * 1.7f)
        path.quadTo(cx - headR * 0.9f, headY + headR * 0.4f, cx - headR * 0.4f, headY + headR * 0.2f)
        path.close()
        canvas.drawPath(path, paint)
        path.reset()
        path.moveTo(cx + headR * 1.2f, headY)
        path.quadTo(cx + headR * 1.55f, headY + headR * 1.4f, cx + headR * 0.7f, headY + headR * 1.7f)
        path.quadTo(cx + headR * 0.9f, headY + headR * 0.4f, cx + headR * 0.4f, headY + headR * 0.2f)
        path.close()
        canvas.drawPath(path, paint)
        paint.color = 0xFF7C6BFF.toInt()
        canvas.drawCircle(cx - headR * 0.85f, headY - headR * 0.15f, headR * 0.22f, paint)
        canvas.drawCircle(cx + headR * 0.85f, headY - headR * 0.1f, headR * 0.2f, paint)
        paint.color = skin
        canvas.drawOval(cx - headR * 0.78f, headY - headR * 0.72f, cx + headR * 0.78f, headY + headR * 0.92f, paint)
        paint.color = 0xFFF06491.toInt()
        canvas.drawCircle(cx - headR * 0.48f, headY + headR * 0.28f, headR * 0.16f, paint)
        canvas.drawCircle(cx + headR * 0.48f, headY + headR * 0.28f, headR * 0.16f, paint)
        val blink = System.currentTimeMillis() < blinkUntil
        paint.color = 0xFF242231.toInt()
        if (blink || snap.mood == EmotionEngine.Mood.TIRED) {
            paint.strokeWidth = 4f; paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(cx - headR * 0.38f, headY - 2f, cx - headR * 0.18f, headY - 2f, paint)
            canvas.drawLine(cx + headR * 0.18f, headY - 2f, cx + headR * 0.38f, headY - 2f, paint)
            paint.style = Paint.Style.FILL
        } else {
            val eyeH = if (snap.mood == EmotionEngine.Mood.HAPPY) headR * 0.13f else headR * 0.16f
            canvas.drawOval(cx - headR * 0.38f, headY - eyeH, cx - headR * 0.14f, headY + eyeH * 0.7f, paint)
            canvas.drawOval(cx + headR * 0.14f, headY - eyeH, cx + headR * 0.38f, headY + eyeH * 0.7f, paint)
            paint.color = Color.WHITE
            canvas.drawCircle(cx - headR * 0.22f, headY - eyeH * 0.45f, 3.2f, paint)
            canvas.drawCircle(cx + headR * 0.30f, headY - eyeH * 0.45f, 3.2f, paint)
        }
        paint.color = 0xFFE07A8A.toInt()
        when (snap.mood) {
            EmotionEngine.Mood.HAPPY -> {
                path.reset()
                path.addArc(cx - headR * 0.22f, headY + headR * 0.28f, cx + headR * 0.22f, headY + headR * 0.62f, 10f, 160f)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 5f
                canvas.drawPath(path, paint); paint.style = Paint.Style.FILL
            }
            EmotionEngine.Mood.SAD, EmotionEngine.Mood.TIRED -> {
                path.reset()
                path.addArc(cx - headR * 0.18f, headY + headR * 0.42f, cx + headR * 0.18f, headY + headR * 0.62f, 200f, 140f)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f
                canvas.drawPath(path, paint); paint.style = Paint.Style.FILL
            }
            EmotionEngine.Mood.ANNOYED -> {
                canvas.drawOval(cx - headR * 0.12f, headY + headR * 0.38f, cx + headR * 0.12f, headY + headR * 0.5f, paint)
            }
            EmotionEngine.Mood.SHY -> {
                path.reset()
                path.addArc(cx - headR * 0.14f, headY + headR * 0.34f, cx + headR * 0.14f, headY + headR * 0.52f, 20f, 140f)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f
                canvas.drawPath(path, paint); paint.style = Paint.Style.FILL
            }
            else -> {
                path.reset()
                path.addArc(cx - headR * 0.16f, headY + headR * 0.32f, cx + headR * 0.16f, headY + headR * 0.54f, 20f, 140f)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 4.5f
                canvas.drawPath(path, paint); paint.style = Paint.Style.FILL
            }
        }
        if (snap.mood == EmotionEngine.Mood.SHY) {
            paint.color = 0x66F06491.toInt()
            canvas.drawCircle(cx - headR * 0.7f, headY + headR * 0.15f, headR * 0.28f, paint)
        }
        paint.color = 0xFFF06491.toInt()
        canvas.drawCircle(cx + headR * 0.72f, headY - headR * 0.55f, 7f, paint)
        canvas.restore()
    }

    private fun drawCards(canvas: Canvas, w: Float, h: Float, snap: Snap) {
        val cardW = w * 0.42f
        val cardH = h * 0.078f
        val left = 18f
        val top = h * 0.11f
        drawGlassCard(canvas, left, top, cardW, cardH, "天气", snap.weatherLine)
        drawGlassCard(canvas, left, top + cardH + 10f, cardW, cardH, "提醒", snap.remindLine)
        drawGlassCard(canvas, left, top + (cardH + 10f) * 2f, cardW, cardH, "心情", snap.moodLine)
    }

    private fun drawGlassCard(canvas: Canvas, x: Float, y: Float, cw: Float, ch: Float, title: String, body: String) {
        rect.set(x, y, x + cw, y + ch)
        paint.color = 0x9914141C.toInt()
        canvas.drawRoundRect(rect, 22f, 22f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f
        paint.color = 0x33FFFFFF.toInt()
        canvas.drawRoundRect(rect, 22f, 22f, paint)
        paint.style = Paint.Style.FILL
        textPaint.color = 0xFFD0BCFF.toInt()
        textPaint.textSize = ch * 0.22f
        canvas.drawText(title, x + 16f, y + ch * 0.38f, textPaint)
        textPaint.color = 0xFFF4F1FA.toInt()
        textPaint.textSize = ch * 0.28f
        canvas.drawText(body, x + 16f, y + ch * 0.74f, textPaint)
    }

    private fun drawHearts(canvas: Canvas) {
        hearts.forEach { heart ->
            paint.color = Color.argb((heart.life * 220).toInt().coerceIn(0, 220), 240, 100, 145)
            val r = 7f + (1f - heart.life) * 6f
            canvas.drawCircle(heart.x - r * 0.55f, heart.y, r, paint)
            canvas.drawCircle(heart.x + r * 0.55f, heart.y, r, paint)
            path.reset()
            path.moveTo(heart.x - r * 1.1f, heart.y + r * 0.2f)
            path.lineTo(heart.x, heart.y + r * 1.5f)
            path.lineTo(heart.x + r * 1.1f, heart.y + r * 0.2f)
            path.close()
            canvas.drawPath(path, paint)
        }
        paint.alpha = 255
    }
}
