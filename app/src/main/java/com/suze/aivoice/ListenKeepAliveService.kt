package com.suze.aivoice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** 前台保活：退到后台时继续占住麦克风聆听，避免系统把识别掐掉。 */
class ListenKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            this, 1, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.listen_fg_title))
            .setContentText(getString(R.string.listen_fg_text))
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(ID, n)
        return START_STICKY
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(
            CHANNEL,
            getString(R.string.listen_fg_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    companion object {
        const val CHANNEL = "xiaomo_listen"
        const val ID = 10086

        fun start(ctx: Context) {
            val i = Intent(ctx, ListenKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ListenKeepAliveService::class.java))
        }
    }
}
