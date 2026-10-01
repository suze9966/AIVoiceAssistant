package com.suze.aivoice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/** 体积很小的内置立绘：6 张矢量图，不引入整包 WebP。 */
object PortraitLibrary {
    const val DEFAULT_ID = "xiaomo"

    data class Item(val id: String, val name: String, val resId: Int)

    fun all(): List<Item> = listOf(
        Item("xiaomo", "小沫", R.drawable.portrait_xiaomo),
        Item("wanqing", "晚晴", R.drawable.portrait_wanqing),
        Item("abei", "阿北", R.drawable.portrait_abei),
        Item("shenheng", "沈衡", R.drawable.portrait_shenheng),
        Item("linxi", "林溪", R.drawable.portrait_linxi),
        Item("jiuyu", "酒羽", R.drawable.portrait_jiuyu)
    )

    fun resFor(id: String): Int? = all().firstOrNull { it.id == id }?.resId

    /**
     * 给通知栏用的头像位图：优先用户自定义头像，否则用内置小沫立绘。
     * 矢量图通过 Drawable 绘制成 Bitmap，不额外打包图片资源。
     */
    fun iconFor(context: Context): Bitmap? {
        // 自定义头像文件优先（相册选的头像）
        val custom = java.io.File(context.filesDir, "chat_ai_avatar.jpg")
        if (custom.isFile && custom.length() > 0L) {
            runCatching {
                android.graphics.BitmapFactory.decodeFile(custom.absolutePath)?.let { return it }
            }
        }
        return drawableToBitmap(context, resFor(DEFAULT_ID) ?: R.drawable.portrait_xiaomo)
    }

    private fun drawableToBitmap(context: Context, resId: Int, size: Int = 192): Bitmap? {
        val d: Drawable = runCatching {
            androidx.core.content.ContextCompat.getDrawable(context, resId)
        }.getOrNull() ?: return null
        if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(canvas)
        return bmp
    }
}
