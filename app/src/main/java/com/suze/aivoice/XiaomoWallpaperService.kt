package com.suze.aivoice

import android.content.SharedPreferences
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder

/** 方案 B：桌面动态壁纸。全身小沫、心情姿势、天气夜景、触摸招手。 */
class XiaomoWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = XiaomoEngine()

    inner class XiaomoEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {
        private val scene = XiaomoLiveScene(this@XiaomoWallpaperService)
        private val handler = Handler(Looper.getMainLooper())
        private val prefsSp = getSharedPreferences("ai_voice_prefs", MODE_PRIVATE)
        private val emotionSp = getSharedPreferences("ai_emotion", MODE_PRIVATE)
        private var visible = false
        private var xOffset = 0f
        private val frame = object : Runnable {
            override fun run() {
                if (!visible) return
                scene.tick()
                drawFrame()
                handler.postDelayed(this, 32L)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            prefsSp.registerOnSharedPreferenceChangeListener(this)
            emotionSp.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onDestroy() {
            handler.removeCallbacks(frame)
            prefsSp.unregisterOnSharedPreferenceChangeListener(this)
            emotionSp.unregisterOnSharedPreferenceChangeListener(this)
            super.onDestroy()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            handler.removeCallbacks(frame)
            if (visible) handler.post(frame)
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int
        ) {
            this.xOffset = xOffset - 0.5f
            if (visible) drawFrame()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            if (visible) drawFrame()
        }

        override fun onTouchEvent(event: MotionEvent) {
            if (event.action == MotionEvent.ACTION_DOWN) {
                scene.tap(event.x, event.y)
                drawFrame()
            }
            super.onTouchEvent(event)
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            if (visible) drawFrame()
        }

        private fun drawFrame() {
            val holder = surfaceHolder ?: return
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    scene.draw(canvas, canvas.width.toFloat(), canvas.height.toFloat(), xOffset)
                }
            } catch (_: Exception) {
            } finally {
                if (canvas != null) {
                    runCatching { holder.unlockCanvasAndPost(canvas) }
                }
            }
        }
    }
}
