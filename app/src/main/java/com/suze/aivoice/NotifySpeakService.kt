package com.suze.aivoice

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** 可选：把系统通知标题读出来。默认关，需在系统里授权通知使用权。 */
class NotifySpeakService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val prefs = Prefs(this)
        if (!prefs.notifySpeakEnabled) return
        if (sbn.packageName == packageName) return
        if (sbn.isOngoing) return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString().orEmpty().trim()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty().trim()
        val line = listOf(title, text).filter { it.isNotBlank() }.joinToString("，").take(3000)
        if (line.isBlank()) return
        val now = System.currentTimeMillis()
        if (line == lastLine && now - lastAt < 8_000L) return
        lastLine = line
        lastAt = now
        val open = android.content.Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_NOTIFY_SPEAK, line)
            .addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        runCatching { startActivity(open) }
    }

    companion object {
        private var lastLine: String = ""
        private var lastAt: Long = 0L
    }
}