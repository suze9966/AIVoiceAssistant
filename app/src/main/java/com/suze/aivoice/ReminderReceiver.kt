package com.suze.aivoice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        val advance = intent.getBooleanExtra(EXTRA_ADVANCE, false)
        val raw = intent.getStringExtra(EXTRA_TEXT).orEmpty().ifBlank { "到时间啦" }
        val text = if (advance) context.getString(R.string.remind_advance_title) + "：" + raw else raw
        if (id.isNotBlank() && !advance) ReminderStore(context).markFired(id)
        else XiaomoWidgetProvider.refresh(context)
        ensureChannel(context)
        val open = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_REMIND_SPEAK, text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            context, 2, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.remind_notif_title))
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify((System.currentTimeMillis() % 100000).toInt() + 200, n)
        runCatching { context.startActivity(open) }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.remind_channel),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    companion object {
        const val CHANNEL = "xiaomo_remind"
        const val EXTRA_ID = "remind_id"
        const val EXTRA_TEXT = "remind_text"
        const val EXTRA_ADVANCE = "remind_advance"
    }
}

class BootRemindReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return
        ReminderStore(context).rescheduleAll()
    }
}
