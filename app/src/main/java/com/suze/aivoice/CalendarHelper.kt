package com.suze.aivoice

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class CalendarHelper(private val context: Context) {
    fun upcoming(limit: Int = 5): String {
        val now = System.currentTimeMillis()
        val end = now + 7L * 24 * 3600_000L
        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.EVENT_LOCATION
        )
        val sel = CalendarContract.Events.DTSTART + ">=? AND " + CalendarContract.Events.DTSTART + "<=? AND " +
            CalendarContract.Events.DELETED + "!=1"
        val cur = try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                sel,
                arrayOf(now.toString(), end.toString()),
                CalendarContract.Events.DTSTART + " ASC"
            )
        } catch (_: SecurityException) {
            return "NO_PERM"
        } catch (_: Exception) {
            return ""
        } ?: return ""
        val fmt = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)
        val lines = mutableListOf<String>()
        cur.use {
            while (it.moveToNext() && lines.size < limit) {
                val title = it.getString(0).orEmpty().ifBlank { "日程" }
                val start = it.getLong(1)
                val loc = it.getString(2).orEmpty()
                val bit = fmt.format(Date(start)) + "  " + title + if (loc.isBlank()) "" else "（$loc）"
                lines.add(bit)
            }
        }
        return if (lines.isEmpty()) "近一周没有系统日程。" else lines.joinToString("\n")
    }

    fun add(title: String, atMillis: Long): Boolean {
        val calId = defaultCalendarId() ?: return false
        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, atMillis)
            put(CalendarContract.Events.DTEND, atMillis + 60 * 60_000L)
            put(CalendarContract.Events.TITLE, title.take(80).ifBlank { "小沫日程" })
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.DESCRIPTION, "由小沫添加")
        }
        return try {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            uri != null
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun defaultCalendarId(): Long? {
        val cur = try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
                CalendarContract.Calendars.VISIBLE + "=1",
                null,
                null
            )
        } catch (_: Exception) { null } ?: return null
        cur.use {
            var fallback: Long? = null
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val level = it.getInt(1)
                fallback = fallback ?: id
                if (level >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) return id
            }
            return fallback
        }
    }
}
