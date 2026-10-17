package com.suze.aivoice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/**
 * 小沫聊天头像库。
 *
 * 内置 6 张矢量立绘，另外打包 37 张可更换的聊天头像（assets/avatars/av01.jpg … av37.jpg）。
 * 自定义相册头像仍然优先；没有自定义头像时，用这里选中的那一张。
 */
object PortraitLibrary {
    const val DEFAULT_ID = "xiaomo"
    const val ASSET_DIR = "avatars"
    const val ASSET_COUNT = 37

    data class Item(
        val id: String,
        val name: String,
        val resId: Int = 0,
        val asset: String = ""
    )

    fun all(): List<Item> {
        val builtin = listOf(
            Item("xiaomo", "小沫 · 立绘", R.drawable.portrait_xiaomo),
            Item("wanqing", "晚晴 · 立绘", R.drawable.portrait_wanqing),
            Item("abei", "阿北 · 立绘", R.drawable.portrait_abei),
            Item("shenheng", "沈衡 · 立绘", R.drawable.portrait_shenheng),
            Item("linxi", "林溪 · 立绘", R.drawable.portrait_linxi),
            Item("jiuyu", "酒羽 · 立绘", R.drawable.portrait_jiuyu)
        )
        val packed = (1..ASSET_COUNT).map { n ->
            val id = assetId(n)
            Item(id, "头像 %02d".format(n), asset = "$ASSET_DIR/$id.jpg")
        }
        return builtin + packed
    }

    fun assetId(index: Int): String = "av%02d".format(index)

    fun find(id: String): Item? = all().firstOrNull { it.id == id }

    fun resFor(id: String): Int? = find(id)?.resId?.takeIf { it != 0 }

    fun assetFor(id: String): String? = find(id)?.asset?.takeIf { it.isNotBlank() }

    fun has(id: String): Boolean = find(id) != null

    /**
     * 给通知栏用的头像位图：优先用户自定义头像，其次头像库选中项，最后回退小沫立绘。
     */
    fun iconFor(context: Context): Bitmap? {
        val custom = java.io.File(context.filesDir, "chat_ai_avatar.jpg")
        if (custom.isFile && custom.length() > 0L) {
            runCatching {
                BitmapFactory.decodeFile(custom.absolutePath)?.let { return it }
            }
        }
        val selected = Prefs(context).portraitId
        decodeAsset(context, assetFor(selected))?.let { return it }
        return drawableToBitmap(context, resFor(selected) ?: resFor(DEFAULT_ID) ?: R.drawable.portrait_xiaomo)
    }

    fun decodeAsset(context: Context, asset: String?, maxSide: Int = 256): Bitmap? {
        if (asset.isNullOrBlank()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(asset).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / sample > maxSide * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.assets.open(asset).use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Exception) {
            null
        }
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