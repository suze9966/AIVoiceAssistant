package com.suze.aivoice

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build

/** API 31+ 的 GPU 模糊，单独放以免旧系统加载 RenderNode。 */
internal object LiquidGlassGpu {
    fun draw(canvas: Canvas, bmp: Bitmap, width: Int, height: Int, paint: Paint, nodeHolder: Array<Any?>) {
        if (Build.VERSION.SDK_INT < 31) return
        val node = nodeHolder[0] as? RenderNode ?: RenderNode("liquid-glass").also { nodeHolder[0] = it }
        node.setPosition(0, 0, width, height)
        node.setRenderEffect(RenderEffect.createBlurEffect(28f, 28f, Shader.TileMode.CLAMP))
        val rec = node.beginRecording()
        rec.drawBitmap(bmp, null, Rect(0, 0, width, height), paint)
        node.endRecording()
        canvas.drawRenderNode(node)
    }
}
