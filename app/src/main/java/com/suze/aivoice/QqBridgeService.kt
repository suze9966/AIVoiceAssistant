package com.suze.aivoice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * QQ 桥接的常驻载体。
 *
 * 为什么必须是前台服务：
 *   targetSdk 34 下，App 退到后台后普通线程会被系统挂起甚至杀掉，
 *   AstrBot 发过来的请求就没人接了。开成前台服务能活下来，
 *   通知栏也会显示「小沫在 QQ 上线中」，状态一眼可见。
 *
 * 用法：
 *   QqBridgeService.start(context)   // 开
 *   QqBridgeService.stop(context)    // 关
 */
class QqBridgeService : Service() {

    companion object {
        private const val TAG = "QqBridgeService"
        private const val CHANNEL_ID = "qq_bridge"
        private const val NOTI_ID = 8801

        fun start(ctx: Context) {
            try {
                val i = Intent(ctx, QqBridgeService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
            } catch (e: Exception) {
                Log.w(TAG, "start failed: ${e.message}")
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, QqBridgeService::class.java))
            } catch (_: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        try {
            startForeground(NOTI_ID, buildNotification("正在启动…"))
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY 被系统重启时 intent 为 null，这里不能依赖 intent 做判断
        try {
            Prefs(this).qqBridgeEnabled = true
        } catch (e: Exception) {
            Log.w(TAG, "persist enabled failed: ${e.message}")
        }
        val msg = QqBridge.start(this)
        Log.i(TAG, "bridge: $msg")
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTI_ID, buildNotification(msg))
        } catch (_: Exception) {}
        return START_STICKY
    }

    override fun onDestroy() {
        QqBridge.stop()
        // 只有在「用户主动关」时才清状态；系统杀进程重建时不能误标成用户关闭
        try {
            if (QqBridge.userStopped) Prefs(this).qqBridgeEnabled = false
        } catch (_: Exception) {}
        try { stopForeground(true) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, "QQ 桥接", NotificationManager.IMPORTANCE_LOW).apply {
            description = "让 QQ 上的小沫连到手机 App"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        b.setContentTitle("小沫在 QQ 上线中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(open)
            .setOngoing(true)
        return b.build()
    }
}