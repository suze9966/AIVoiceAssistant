package com.suze.aivoice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 极简网络图片加载器（项目未引入 Glide/Picasso，这里手写一个够用的）。
 *
 * 特性：
 * - 后台线程池下载 + 主线程回填 ImageView
 * - 内存缓存（URL -> Bitmap），LRU 简化版：超过上限清空重来
 * - 通过 tag 防止 RecyclerView 复用导致的图片错位
 * - 支持 `asset://` 开头的本地图片表情（打包进 APK 的 assets，不联网）
 */
object ImageLoader {

    /**
     * 内存缓存按**字节数**封顶（取可用堆的 1/8），而不是按张数。
     * 618px 的表情原图解码后单张约 1.5MB，按张数封顶很容易 OOM。
     */
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(8 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 气泡里表情的显示边长（dp），用于解码时采样，避免原图整张进内存。 */
    private const val TARGET_DP = 150

    private val pool = Executors.newFixedThreadPool(3)

    /** 本地表情专用的小线程池：只解码、不联网，和网络池分开，互不阻塞。 */
    private val assetPool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * 异步加载图片到 ImageView。
     * @param url 图片地址
     */
    fun load(url: String, view: ImageView) {
        if (url.isBlank()) return
        // 用 tag 绑定 URL，防止列表复用错位
        view.tag = url
        cache.get(url)?.let {
            view.setImageBitmap(it)
            return
        }
        // 本地图片表情：不联网，但解码仍放到后台线程，
        // 避免 618px 原图解码卡住主线程（列表滚动时尤其明显）。
        if (url.startsWith(StickerLibrary.LOCAL_PREFIX)) {
            val appCtx = view.context.applicationContext
            assetPool.execute {
                val bmp = decodeAsset(appCtx, url) ?: return@execute
                cache.put(url, bmp)
                main.post {
                    if (view.tag == url) view.setImageBitmap(bmp)
                }
            }
            return
        }
        pool.execute {
            try {
                val bmp = download(url)
                if (bmp != null) {
                    cache.put(url, bmp)
                    main.post {
                        // 再次校验：只有当该 ImageView 仍在显示这张图时才回填
                        if (view.tag == url) view.setImageBitmap(bmp)
                    }
                }
            } catch (e: Exception) {
                // 忽略：加载失败就不显示
            }
        }
    }

    /**
     * 从 APK 内置 assets 解码图片表情。
     *
     * 内置图是 618×618 的高清原图，直接整张解码单张就要约 1.5MB 内存，
     * 所以先读一次边界，再按气泡实际显示尺寸做 inSampleSize 采样。
     */
    private fun decodeAsset(context: Context, url: String): Bitmap? {
        val path = url.removePrefix(StickerLibrary.LOCAL_PREFIX)
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val target = (TARGET_DP * context.resources.displayMetrics.density).toInt()
                .coerceAtLeast(64)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, target)
            }
            context.assets.open(path).use { input ->
                BitmapFactory.decodeStream(input, null, opts)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 计算 2 的幂采样率：让解码后的短边不小于 target，同时尽量省内存。 */
    private fun sampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= target && h / 2 >= target) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    private fun download(url: String): Bitmap? {
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val bytes = resp.body?.bytes() ?: return null
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    /** 预取（例如发送前先缓存，显示更顺滑） */
    fun prefetch(url: String) {
        if (url.isBlank() || cache.get(url) != null) return
        if (url.startsWith(StickerLibrary.LOCAL_PREFIX)) return
        pool.execute {
            try {
                val bmp = download(url) ?: return@execute
                cache.put(url, bmp)
            } catch (e: Exception) {
            }
        }
    }
}