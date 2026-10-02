package com.suze.aivoice

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 消息通知器（MessageNotifier）
 *
 * 让「小沫说的话」也能出现在系统通知栏和**锁屏**上：
 *   - 独立高优先级渠道（IMPORTANCE_HIGH），锁屏可见（VISIBILITY_PUBLIC）
 *   - 带小沫头像 + 名字，像一条真正的聊天消息
 *   - 点通知直达小沫聊天页（EXTRA_OPEN_CHAT）
 *
 * 只负责「发消息通知」，是否开启由 Prefs.lockScreenNotifyEnabled 决定，
 * 避免打扰主人（设置页可控）。
 */
object MessageNotifier {

    private const val CHANNEL_ID = "xiaomo_message"
    private const val CHANNEL_NAME = "小沫的消息"
    private const val CHANNEL_DESC = "小沫主动找你或回复你时，在通知栏和锁屏显示消息"
    private const val NOTIFY_ID = 1001

    /** 设置页/聊天页提前建好渠道，避免第一条消息延迟一瞬才出现。 */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
        ch.description = CHANNEL_DESC
        // 锁屏可见：显示完整内容（不因隐私隐藏成「一条新消息」）
        ch.lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        ch.enableLights(false)
        ch.enableVibration(false)
        ch.setShowBadge(true)
        nm.createNotificationChannel(ch)
    }

    /**
     * 发一条小沫的消息通知。
     * @param text 消息正文（会自动截断到 140 字）
     * @param fromProactive true=主动开口，false=聊天回复
     */
    fun show(context: Context, text: String, fromProactive: Boolean) {
        val prefs = Prefs(context)
        if (!prefs.lockScreenNotifyEnabled) return
        val body = text.trim()
        if (body.isEmpty()) return
        if (!hasNotifyPermission(context)) return

        ensureChannel(context)

        val open = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_CHAT, body)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        val pi = PendingIntent.getActivity(
            context, 11, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (fromProactive) "小沫 · 想你了" else "小沫"
        val big = NotificationCompat.BigTextStyle().bigText(body.take(4000))

        val b = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body.take(2000))
            .setStyle(big)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
        // 大图标可能取不到（没自定义头像且立绘解析失败），取到才设置，避免空值异常
        PortraitLibrary.iconFor(context)?.let { b.setLargeIcon(it) }

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFY_ID, b.build())
        }
    }

    /** 主人进聊天页后清掉这条消息通知。 */
    fun clear(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFY_ID) }
    }

    private fun hasNotifyPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
}
