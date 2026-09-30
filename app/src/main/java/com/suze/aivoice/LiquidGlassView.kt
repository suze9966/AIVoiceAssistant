package com.suze.aivoice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider

/**
 * 真模糊液态玻璃：拍下层内容，Android 12+ 走 GPU RenderEffect，更低版本用盒式模糊。
 */
class LiquidGlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        @Volatile var capturingAny = false
    }

    private var target: View? = null
    private var snapshot: Bitmap? = null
    private var snapshotCanvas: Canvas? = null
    private var snapW = 0
    private var snapH = 0
    private var capturing = false
    private var cornerTop = dp(28f)
    private var cornerBottom = 0f
    private val downsample = 6
    private val overlayColor = 0x9914141C.toInt()
    private val tintColor = 0x40221C3A
    private val strokeColor = 0x33FFFFFF
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val dest = RectF()
    private val locThis = IntArray(2)
    private val locTarget = IntArray(2)
    private val gpuNode = arrayOfNulls<Any>(1)

    init {
        isClickable = false
        isFocusable = false
        setWillNotDraw(false)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val r = maxOf(cornerTop, cornerBottom)
                outline.setRoundRect(0, 0, view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), r)
            }
        }
        clipToOutline = true
    }

    fun headerStyle() {
        cornerTop = 0f
        cornerBottom = dp(28f)
        invalidateOutline()
        invalidate()
    }

    fun footerStyle() {
        cornerTop = dp(28f)
        cornerBottom = 0f
        invalidateOutline()
        invalidate()
    }

    fun panelStyle() {
        cornerTop = 0f
        cornerBottom = 0f
        invalidateOutline()
        invalidate()
    }

    fun bind(scene: View) {
        target = scene
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        snapshot?.recycle()
        snapshot = null
        snapshotCanvas = null
        snapW = 0
        snapH = 0
        target = null
    }

    override fun onDraw(canvas: Canvas) {
        if (capturingAny) return
        if (width <= 1 || height <= 1) return
        capture()
        path.reset()
        val radii = floatArrayOf(
            cornerTop, cornerTop, cornerTop, cornerTop,
            cornerBottom, cornerBottom, cornerBottom, cornerBottom
        )
        dest.set(0f, 0f, width.toFloat(), height.toFloat())
        path.addRoundRect(dest, radii, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(path)
        val bmp = snapshot
        if (bmp != null && !bmp.isRecycled) {
            drawSnapshot(canvas, bmp)
        }
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = overlayColor
        canvas.drawRect(dest, fillPaint)
        fillPaint.color = tintColor
        canvas.drawRect(dest, fillPaint)
        highlightPaint.shader = LinearGradient(
            0f, 0f, 0f, height * 0.42f,
            0x28FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP
        )
        canvas.drawRect(dest, highlightPaint)
        highlightPaint.shader = LinearGradient(
            0f, height * 0.72f, 0f, height.toFloat(),
            0x00000000, 0x33000000, Shader.TileMode.CLAMP
        )
        canvas.drawRect(dest, highlightPaint)
        highlightPaint.shader = null
        canvas.restore()
        fillPaint.style = Paint.Style.STROKE
        fillPaint.strokeWidth = dp(1f)
        fillPaint.color = strokeColor
        canvas.drawPath(path, fillPaint)
        fillPaint.style = Paint.Style.FILL
    }

    private fun capture() {
        val src = target ?: return
        if (src.width <= 1 || src.height <= 1) return
        val bw = (width / downsample).coerceAtLeast(2)
        val bh = (height / downsample).coerceAtLeast(2)
        ensureSnapshot(bw, bh)
        val bmp = snapshot ?: return
        val c = snapshotCanvas ?: return
        bmp.eraseColor(Color.TRANSPARENT)
        if (capturing || capturingAny) return
        capturing = true
        capturingAny = true
        c.save()
        getLocationOnScreen(locThis)
        src.getLocationOnScreen(locTarget)
        val dx = (locThis[0] - locTarget[0]).toFloat()
        val dy = (locThis[1] - locTarget[1]).toFloat()
        c.scale(bw.toFloat() / width, bh.toFloat() / height)
        c.translate(-dx, -dy)
        try {
            src.draw(c)
        } catch (_: Exception) {
        }
        c.restore()
        capturing = false
        capturingAny = false
        if (Build.VERSION.SDK_INT < 31) {
            boxBlur(bmp, 2)
            boxBlur(bmp, 2)
        }
    }

    private fun drawSnapshot(canvas: Canvas, bmp: Bitmap) {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                LiquidGlassGpu.draw(canvas, bmp, width, height, paint, gpuNode)
                return
            } catch (_: Throwable) {
            }
        }
        canvas.drawBitmap(bmp, null, dest, paint)
    }

    private fun ensureSnapshot(w: Int, h: Int) {
        val old = snapshot
        if (old != null && !old.isRecycled && snapW == w && snapH == h) return
        old?.recycle()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        snapshot = bmp
        snapshotCanvas = Canvas(bmp)
        snapW = w
        snapH = h
    }

    private fun boxBlur(bitmap: Bitmap, radius: Int) {
        if (radius < 1) return
        val w = bitmap.width
        val h = bitmap.height
        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)
        val src = pix.copyOf()
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                var n = 0
                var k = -radius
                while (k <= radius) {
                    val xx = (x + k).coerceIn(0, w - 1)
                    val p = src[y * w + xx]
                    a += p ushr 24
                    r += (p shr 16) and 0xff
                    g += (p shr 8) and 0xff
                    b += p and 0xff
                    n++
                    k++
                }
                pix[y * w + x] = (a / n shl 24) or (r / n shl 16) or (g / n shl 8) or (b / n)
                x++
            }
            y++
        }
        val mid = pix.copyOf()
        var x = 0
        while (x < w) {
            var yy = 0
            while (yy < h) {
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                var n = 0
                var k = -radius
                while (k <= radius) {
                    val yk = (yy + k).coerceIn(0, h - 1)
                    val p = mid[yk * w + x]
                    a += p ushr 24
                    r += (p shr 16) and 0xff
                    g += (p shr 8) and 0xff
                    b += p and 0xff
                    n++
                    k++
                }
                pix[yy * w + x] = (a / n shl 24) or (r / n shl 16) or (g / n shl 8) or (b / n)
                yy++
            }
            x++
        }
        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
