package com.suze.aivoice

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.util.concurrent.ConcurrentHashMap
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
 */
object ImageLoader {

    private val cache = ConcurrentHashMap<String, Bitmap>()
    private const val MAX_CACHE = 80

    private val pool = Executors.newFixedThreadPool(3)
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
        cache[url]?.let {
            view.setImageBitmap(it)
            return
        }
        pool.execute {
            try {
                val bmp = download(url)
                if (bmp != null) {
                    if (cache.size >= MAX_CACHE) cache.clear()
                    cache[url] = bmp
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
        if (url.isBlank() || cache.containsKey(url)) return
        pool.execute {
            try {
                val bmp = download(url) ?: return@execute
                if (cache.size >= MAX_CACHE) cache.clear()
                cache[url] = bmp
            } catch (e: Exception) {
            }
        }
    }
}