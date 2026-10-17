package com.suze.aivoice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper

/**
 * 主聊天和场景卡片共用的联网播放。
 * 同一时间只放一首，离开页面或再点一次会停。
 */
object ChatMusic {

    data class Now(val title: String, val artist: String, val live: Boolean)

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var tracks: List<PocketMusic.Track> = emptyList()
    private var index = 0
    private var token = 0
    private var appContext: Context? = null
    var onChange: ((Now?) -> Unit)? = null

    fun isPlaying(): Boolean = player?.isPlaying == true

    fun current(): Now? {
        val track = tracks.getOrNull(index) ?: return null
        if (player == null) return null
        return Now(track.title, track.artist, track.live)
    }

    fun play(context: Context, wish: String, onReady: (Now?) -> Unit) {
        val app = context.applicationContext
        appContext = app
        val mine = ++token
        Thread {
            val found = runCatching { PocketMusic.search(wish) }.getOrDefault(emptyList())
            main.post {
                if (mine != token) return@post
                tracks = found
                index = 0
                if (found.isEmpty()) {
                    stop()
                    onReady(null)
                } else {
                    startAt(app, 0, onReady)
                }
            }
        }.start()
    }

    fun toggle(context: Context, wish: String, onReady: (Now?) -> Unit) {
        val cur = player
        if (cur != null && tracks.isNotEmpty()) {
            if (cur.isPlaying) {
                runCatching { cur.pause() }
                onChange?.invoke(current())
            } else {
                runCatching { cur.start() }
                onChange?.invoke(current())
            }
            onReady(current())
            return
        }
        play(context, wish, onReady)
    }

    fun next(context: Context) {
        if (tracks.isEmpty()) {
            play(context, "", {})
            return
        }
        startAt(context.applicationContext, index + 1, {})
    }

    fun stop() {
        token += 1
        val old = player
        player = null
        runCatching { old?.stop() }
        runCatching { old?.release() }
        onChange?.invoke(null)
    }

    private fun startAt(app: Context, at: Int, onReady: (Now?) -> Unit) {
        if (tracks.isEmpty()) {
            onReady(null)
            return
        }
        val i = ((at % tracks.size) + tracks.size) % tracks.size
        index = i
        val track = tracks[i]
        val old = player
        player = null
        runCatching { old?.release() }
        val mp = MediaPlayer()
        player = mp
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        mp.setOnPreparedListener {
            if (player !== mp) return@setOnPreparedListener
            it.start()
            val now = Now(track.title, track.artist, track.live)
            remember(app, track.title)
            onChange?.invoke(now)
            onReady(now)
        }
        mp.setOnCompletionListener {
            if (player !== mp) return@setOnCompletionListener
            if (!track.live && tracks.size > 1) startAt(app, index + 1, {})
        }
        mp.setOnErrorListener { failed, _, _ ->
            if (player === failed) {
                player = null
                runCatching { failed.release() }
                val next = index + 1
                if (next < tracks.size) startAt(app, next, onReady) else onReady(null)
            }
            true
        }
        runCatching {
            mp.setDataSource(track.url)
            mp.prepareAsync()
        }.onFailure {
            if (player === mp) player = null
            runCatching { mp.release() }
            onReady(null)
        }
    }

    private fun remember(app: Context, title: String) {
        runCatching {
            MemoryEngine(app).rememberPocket("我在主聊天里正在听「" + title + "」")
        }
    }
}
