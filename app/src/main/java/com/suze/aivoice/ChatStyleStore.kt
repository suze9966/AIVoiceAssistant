package com.suze.aivoice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Outline
import android.media.ExifInterface
import android.net.Uri
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/** 聊天头像与背景：相册选图后复制到应用私有目录。 */
object ChatStyleStore {
    private const val AVATAR_FILE = "chat_ai_avatar.jpg"
    private const val BACKGROUND_FILE = "chat_background.jpg"

    @Volatile private var avatarCache: Bitmap? = null
    @Volatile private var backgroundCache: Bitmap? = null

    fun avatarFile(context: Context): File = File(context.filesDir, AVATAR_FILE)
    fun backgroundFile(context: Context): File = File(context.filesDir, BACKGROUND_FILE)

    fun hasAvatar(context: Context): Boolean {
        val f = avatarFile(context)
        return f.isFile && f.length() > 0L
    }

    fun hasBackground(context: Context): Boolean {
        val f = backgroundFile(context)
        return f.isFile && f.length() > 0L
    }

    fun saveAvatar(context: Context, uri: Uri): Boolean {
        avatarCache = null
        return copyFromUri(context, uri, avatarFile(context), 512)
    }

    fun saveBackground(context: Context, uri: Uri): Boolean {
        backgroundCache = null
        return copyFromUri(context, uri, backgroundFile(context), 1600)
    }

    fun clearAvatar(context: Context) {
        avatarCache = null
        avatarFile(context).delete()
    }

    fun clearBackground(context: Context) {
        backgroundCache = null
        backgroundFile(context).delete()
    }

    fun applyAvatar(view: ImageView, defaultRes: Int = R.drawable.ic_bot) {
        roundClip(view)
        val bmp = loadAvatar(view.context)
        if (bmp != null) {
            view.setPadding(0, 0, 0, 0)
            view.scaleType = ImageView.ScaleType.CENTER_CROP
            view.setImageBitmap(bmp)
            return
        }
        val portrait = PortraitLibrary.resFor(Prefs(view.context).portraitId) ?: defaultRes
        view.setPadding(0, 0, 0, 0)
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        view.setImageResource(portrait)
    }

    fun applyBackground(view: ImageView) {
        val bmp = loadBackground(view.context, 1080)
        if (bmp != null) {
            view.visibility = View.VISIBLE
            view.scaleType = ImageView.ScaleType.CENTER_CROP
            view.setImageBitmap(bmp)
            return
        }
        view.setImageDrawable(null)
        view.visibility = View.GONE
    }

    fun applyBackgroundPreview(view: ImageView) {
        val bmp = loadBackground(view.context, 720)
        if (bmp != null) {
            view.visibility = View.VISIBLE
            view.scaleType = ImageView.ScaleType.CENTER_CROP
            view.setImageBitmap(bmp)
            return
        }
        view.setImageResource(R.drawable.glass_page_bg)
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        view.visibility = View.VISIBLE
    }

    private fun loadAvatar(context: Context): Bitmap? {
        avatarCache?.let { return it }
        val file = avatarFile(context)
        if (!file.isFile || file.length() <= 0L) return null
        val bmp = decodeSampled(file, 256) ?: return null
        avatarCache = bmp
        return bmp
    }

    /** 对外提供壁纸 Bitmap（供气泡跟随壁纸取色用）。 */
    fun loadBackgroundBitmap(context: Context, maxSide: Int = 240): Bitmap? =
        loadBackground(context, maxSide)

    private fun loadBackground(context: Context, maxSide: Int): Bitmap? {
        backgroundCache?.let { return it }
        val file = backgroundFile(context)
        if (!file.isFile || file.length() <= 0L) return null
        val bmp = decodeSampled(file, maxSide) ?: return null
        backgroundCache = bmp
        return bmp
    }

    private fun roundClip(view: ImageView) {
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                val size = minOf(v.width, v.height).coerceAtLeast(1)
                outline.setOval(0, 0, size, size)
            }
        }
        if (view.width == 0 || view.height == 0) view.post { view.invalidateOutline() }
        else view.invalidateOutline()
    }

    private fun copyFromUri(context: Context, uri: Uri, dest: File, maxSide: Int): Boolean {
        return try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (longest / sample > maxSide * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return false
            val oriented = applyExif(resolver.openInputStream(uri), raw)
            val scaled = scaleDown(oriented, maxSide)
            dest.parentFile?.mkdirs()
            FileOutputStream(dest).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
            if (oriented !== raw) raw.recycle()
            if (scaled !== oriented) oriented.recycle()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun applyExif(stream: InputStream?, src: Bitmap): Bitmap {
        if (stream == null) return src
        return try {
            stream.use { input ->
                val exif = ExifInterface(input)
                val degrees = when (exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
                if (degrees == 0f) src
                else {
                    val matrix = Matrix().apply { postRotate(degrees) }
                    Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
                }
            }
        } catch (_: Exception) {
            src
        }
    }

    private fun scaleDown(src: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxSide) return src
        val scale = maxSide.toFloat() / longest.toFloat()
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun decodeSampled(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var sample = 1
        while (longest / sample > maxSide * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }
}
