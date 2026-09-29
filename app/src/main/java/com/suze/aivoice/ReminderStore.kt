package com.suze.aivoice

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class ReminderItem(
    val id: String,
    val text: String,
    val atMillis: Long,
    val createdAt: Long = System.currentTimeMillis()
)

class ReminderStore(private val context: Context) {
    private val file = File(context.filesDir, "reminders.json")
    private val maxKeep = 40

    fun load(): MutableList<ReminderItem> {
        val list = mutableListOf<ReminderItem>()
        try {
            if (!file.exists()) return list
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(
                    ReminderItem(
                        id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                        text = o.optString("text"),
                        atMillis = o.optLong("atMillis"),
                        createdAt = o.optLong("createdAt")
                    )
                )
            }
        } catch (_: Exception) { }
        return list
    }

    fun save(items: List<ReminderItem>) {
        try {
            val arr = JSONArray()
            items.takeLast(maxKeep).forEach { r ->
                arr.put(
                    JSONObject()
                        .put("id", r.id)
                        .put("text", r.text)
                        .put("atMillis", r.atMillis)
                        .put("createdAt", r.createdAt)
                )
            }
            file.writeText(arr.toString())
        } catch (_: Exception) { }
    }

    fun upcoming(): List<ReminderItem> {
        val now = System.currentTimeMillis() - 15_000L
        return load().filter { it.atMillis >= now }.sortedBy { it.atMillis }
    }

    fun add(text: String, atMillis: Long): ReminderItem {
        val item = ReminderItem(
            id = UUID.randomUUID().toString(),
            text = text.trim().ifBlank { "到时间啦" }.take(80),
            atMillis = atMillis
        )
        val list = load().filter { it.atMillis >= System.currentTimeMillis() - 15_000L }.toMutableList()
        list.add(item)
        save(list)
        schedule(item)
        return item
    }

    fun cancel(id: String): Boolean {
        val list = load()
        val found = list.firstOrNull { it.id == id } ?: return false
        unschedule(found)
        save(list.filterNot { it.id == id })
        return true
    }

    fun cancelAll() {
        load().forEach { unschedule(it) }
        save(emptyList())
    }

    fun markFired(id: String) {
        save(load().filterNot { it.id == id })
    }

    fun rescheduleAll() {
        upcoming().forEach { schedule(it) }
    }

    fun formatWhen(atMillis: Long): String {
        return SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(atMillis))
    }

    private fun schedule(item: ReminderItem) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(item)
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.atMillis, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.atMillis, pi)
                }
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.atMillis, pi)
            }
        } catch (_: Exception) {
            runCatching { am.set(AlarmManager.RTC_WAKEUP, item.atMillis, pi) }
        }
    }

    private fun unschedule(item: ReminderItem) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { am.cancel(pending(item)) }
    }

    private fun pending(item: ReminderItem): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction("com.suze.aivoice.REMIND")
            .putExtra(ReminderReceiver.EXTRA_ID, item.id)
            .putExtra(ReminderReceiver.EXTRA_TEXT, item.text)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, item.id.hashCode(), intent, flags)
    }
}
