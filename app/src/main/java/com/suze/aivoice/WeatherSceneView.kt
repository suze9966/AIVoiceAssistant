package com.suze.aivoice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin
import kotlin.random.Random

/** 轻量天气动态背景：晴天漂云、雨线、雪花、雾层与星空，不依赖图片或第三方动画库。 */
class WeatherSceneView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var skycon = "CLEAR_DAY"
    private var progress = 0f
    private val seeds = List(48) { Triple(Random.nextFloat(), Random.nextFloat(), Random.nextFloat()) }
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 9000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { progress = it.animatedValue as Float; invalidate() }
    }

    fun setWeather(code: String) {
        skycon = code
        invalidate()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        val night = skycon.contains("NIGHT")
        val rainy = skycon.contains("RAIN")
        val snowy = skycon.contains("SNOW")
        val foggy = skycon.contains("HAZE") || skycon == "FOG"
        val top = when { night -> Color.rgb(28, 35, 80); rainy -> Color.rgb(69, 91, 123); foggy -> Color.rgb(105, 119, 132); else -> Color.rgb(91, 111, 238) }
        val bottom = when { night -> Color.rgb(70, 56, 125); rainy -> Color.rgb(91, 111, 135); foggy -> Color.rgb(152, 162, 170); else -> Color.rgb(125, 190, 255) }
        paint.shader = android.graphics.LinearGradient(0f, 0f, 0f, h, top, bottom, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint); paint.shader = null
        if (night) drawStars(canvas, w, h) else drawSun(canvas, w)
        when {
            rainy -> drawRain(canvas, w, h)
            snowy -> drawSnow(canvas, w, h)
            foggy -> drawFog(canvas, w, h)
            else -> drawClouds(canvas, w, h)
        }
    }

    private fun drawSun(c: Canvas, w: Float) {
        val x = w * .82f; val y = height * .18f; val r = w * .15f
        paint.shader = RadialGradient(x, y, r, intArrayOf(0xFFFFF5B8.toInt(), 0x55FFF1A8, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, paint); paint.shader = null
    }
    private fun drawStars(c: Canvas, w: Float, h: Float) {
        paint.color = 0xCCFFFFFF.toInt()
        seeds.take(24).forEachIndexed { i, s ->
            paint.alpha = (90 + 140 * ((sin(progress * 6.28 + i) + 1) / 2)).toInt()
            c.drawCircle(s.first * w, s.second * h * .65f, 1f + s.third * 2f, paint)
        }
        paint.alpha = 255
    }
    private fun drawClouds(c: Canvas, w: Float, h: Float) {
        paint.color = 0x55FFFFFF
        repeat(4) { i ->
            val x = ((progress * w * .18f + i * w * .34f) % (w * 1.35f)) - w * .2f
            val y = h * (.25f + i * .12f)
            c.drawOval(x, y, x + w * .35f, y + h * .12f, paint)
        }
    }
    private fun drawRain(c: Canvas, w: Float, h: Float) {
        paint.color = 0x99D9EEFF.toInt(); paint.strokeWidth = 3f
        seeds.forEach { s ->
            val y = ((s.second + progress * (1.2f + s.third)) % 1f) * h
            val x = s.first * w
            c.drawLine(x, y, x - 10f, y + 34f, paint)
        }
    }
    private fun drawSnow(c: Canvas, w: Float, h: Float) {
        paint.color = 0xDDFFFFFF.toInt()
        seeds.forEachIndexed { i, s ->
            val y = ((s.second + progress * (.25f + s.third * .2f)) % 1f) * h
            val x = (s.first * w + sin(progress * 6.28 + i) * 18f)
            c.drawCircle(x, y, 2f + s.third * 5f, paint)
        }
    }
    private fun drawFog(c: Canvas, w: Float, h: Float) {
        paint.color = 0x30FFFFFF
        repeat(6) { i ->
            val shift = if (i % 2 == 0) progress * w * .12f else -progress * w * .12f
            c.drawOval(-w * .15f + shift, h * (.2f + i * .12f), w * 1.1f + shift, h * (.32f + i * .12f), paint)
        }
    }
}