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
    val createdAt: Long = System.currentTimeMillis(),
    val repeatDaily: Boolean = false,
    val advanceMin: Int = 0
)

class ReminderStore(private val context: Context) {
    private val file = File(context.filesDir, "reminders.json")
    private val maxKeep = 80

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
                        createdAt = o.optLong("createdAt"),
                        repeatDaily = o.optBoolean("repeatDaily", false),
                        advanceMin = o.optInt("advanceMin", 0)
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
                        .put("repeatDaily", r.repeatDaily)
                        .put("advanceMin", r.advanceMin)
                )
            }
            file.writeText(arr.toString())
        } catch (_: Exception) { }
    }

    fun upcoming(): List<ReminderItem> {
        val now = System.currentTimeMillis() - 15_000L
        return load().filter { it.repeatDaily || it.atMillis >= now }.sortedBy { it.atMillis }
    }

    fun formatItem(item: ReminderItem): String {
        val extra = buildString {
            if (item.repeatDaily) append(" ·每天")
            if (item.advanceMin > 0) append(" ·提前").append(item.advanceMin).append("分钟")
        }
        return formatWhen(item.atMillis) + extra + "  " + item.text
    }

    fun add(text: String, atMillis: Long, repeatDaily: Boolean = false, advanceMin: Int = 0): ReminderItem {
        val item = ReminderItem(
            id = UUID.randomUUID().toString(),
            text = text.trim().ifBlank { "到时间啦" }.take(80),
            atMillis = atMillis,
            repeatDaily = repeatDaily,
            advanceMin = advanceMin.coerceIn(0, 60)
        )
        val now = System.currentTimeMillis() - 15_000L
        val list = load().filter { it.atMillis >= now || it.repeatDaily }.toMutableList()
        list.add(item)
        save(list)
        schedule(item)
        XiaomoWidgetProvider.refresh(context)
        return item
    }

    fun update(id: String, text: String, atMillis: Long, repeatDaily: Boolean, advanceMin: Int): ReminderItem? {
        val list = load()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return null
        unschedule(list[idx])
        val item = list[idx].copy(
            text = text.trim().ifBlank { list[idx].text }.take(80),
            atMillis = atMillis,
            repeatDaily = repeatDaily,
            advanceMin = advanceMin.coerceIn(0, 60)
        )
        list[idx] = item
        save(list)
        schedule(item)
        XiaomoWidgetProvider.refresh(context)
        return item
    }

    fun cancel(id: String): Boolean {
        val list = load()
        val found = list.firstOrNull { it.id == id } ?: return false
        unschedule(found)
        save(list.filterNot { it.id == id })
        XiaomoWidgetProvider.refresh(context)
        return true
    }

    fun cancelAll() {
        load().forEach { unschedule(it) }
        save(emptyList())
        XiaomoWidgetProvider.refresh(context)
    }

    fun markFired(id: String) {
        val list = load()
        val found = list.firstOrNull { it.id == id } ?: return
        if (found.repeatDaily) {
            val next = found.copy(atMillis = found.atMillis + 24L * 3600_000L)
            save(list.map { if (it.id == id) next else it })
            schedule(next)
        } else {
            unschedule(found)
            save(list.filterNot { it.id == id })
        }
        XiaomoWidgetProvider.refresh(context)
    }

    fun rescheduleAll() {
        upcoming().forEach { schedule(it) }
    }

    fun summary(): String {
        val n = upcoming().size
        return if (n == 0) "现在没有提醒" else "还有 $n 条提醒"
    }

    fun formatWhen(atMillis: Long): String {
        return SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(atMillis))
    }

    private fun schedule(item: ReminderItem) {
        setAlarm(pending(item, false), item.atMillis)
        if (item.advanceMin > 0) {
            val early = item.atMillis - item.advanceMin * 60_000L
            if (early > System.currentTimeMillis() + 5_000L) {
                setAlarm(pending(item, true), early)
            }
        }
    }

    private fun setAlarm(pi: PendingIntent, atMillis: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
                }
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            }
        } catch (_: Exception) {
            runCatching { am.set(AlarmManager.RTC_WAKEUP, atMillis, pi) }
        }
    }

    private fun unschedule(item: ReminderItem) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { am.cancel(pending(item, false)) }
        runCatching { am.cancel(pending(item, true)) }
    }

    private fun pending(item: ReminderItem, advance: Boolean): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction("com.suze.aivoice.REMIND")
            .putExtra(ReminderReceiver.EXTRA_ID, item.id)
            .putExtra(ReminderReceiver.EXTRA_TEXT, item.text)
            .putExtra(ReminderReceiver.EXTRA_ADVANCE, advance)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val code = item.id.hashCode() xor if (advance) 0x51ED else 0
        return PendingIntent.getBroadcast(context, code, intent, flags)
    }
}
