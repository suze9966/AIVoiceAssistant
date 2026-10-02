package com.suze.aivoice

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class CalendarEvent(
    val id: Long,
    val title: String,
    val start: Long,
    val location: String
)

class CalendarHelper(private val context: Context) {
    var lastDenied: Boolean = false
        private set

    fun upcomingEvents(limit: Int = 30): List<CalendarEvent> {
        val now = System.currentTimeMillis()
        val end = now + 30L * 24 * 3600_000L
        val projection = arrayOf(
            CalendarContract.Events._ID,
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
            lastDenied = true
            return emptyList()
        } catch (_: Exception) {
            lastDenied = false
            return emptyList()
        } ?: run {
            lastDenied = false
            return emptyList()
        }
        lastDenied = false
        val out = mutableListOf<CalendarEvent>()
        cur.use {
            while (it.moveToNext() && out.size < limit) {
                out.add(
                    CalendarEvent(
                        id = it.getLong(0),
                        title = it.getString(1).orEmpty().ifBlank { "日程" },
                        start = it.getLong(2),
                        location = it.getString(3).orEmpty()
                    )
                )
            }
        }
        return out
    }

    fun upcoming(limit: Int = 12): String {
        val events = upcomingEvents(limit)
        if (lastDenied) return "NO_PERM"
        if (events.isEmpty()) return "近一个月没有系统日程。"
        val fmt = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)
        return events.joinToString("\n") { e ->
            fmt.format(Date(e.start)) + "  " + e.title + if (e.location.isBlank()) "" else "（${e.location}）"
        }
    }

    fun delete(id: Long): Boolean {
        if (id <= 0L) return false
        return try {
            val n = context.contentResolver.delete(
                CalendarContract.Events.CONTENT_URI,
                CalendarContract.Events._ID + "=?",
                arrayOf(id.toString())
            )
            n > 0
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun add(title: String, atMillis: Long): Boolean {
        val calId = defaultCalendarId() ?: return false
        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, atMillis)
            put(CalendarContract.Events.DTEND, atMillis + 60 * 60_000L)
            put(CalendarContract.Events.TITLE, title.take(500).ifBlank { "小沫日程" })
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
