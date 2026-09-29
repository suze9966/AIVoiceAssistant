package com.suze.aivoice

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class XiaomoWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val prefs = Prefs(context)
        val next = ReminderStore(context).upcoming().firstOrNull()
        val remind = if (next == null) "暂无提醒"
        else ReminderStore(context).formatWhen(next.atMillis) + "  " + next.text
        val weather = prefs.lastWeatherBrief.ifBlank { prefs.lastCity + "天气点开查看" }
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            context, 21, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        appWidgetIds.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_xiaomo)
            views.setTextViewText(R.id.tvWidgetRemind, remind)
            views.setTextViewText(R.id.tvWidgetWeather, weather)
            views.setOnClickPendingIntent(R.id.widgetRoot, pi)
            views.setOnClickPendingIntent(R.id.btnWidgetOpen, pi)
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    companion object {
        fun refresh(context: Context) {
            val ids = AppWidgetManager.getInstance(context)
                .getAppWidgetIds(android.content.ComponentName(context, XiaomoWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val intent = Intent(context, XiaomoWidgetProvider::class.java)
                .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            context.sendBroadcast(intent)
        }
    }
}