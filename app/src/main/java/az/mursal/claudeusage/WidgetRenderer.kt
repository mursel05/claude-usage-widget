package az.mursal.claudeusage

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.view.View
import android.widget.RemoteViews
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object WidgetRenderer {

    fun renderAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, UsageWidgetProvider::class.java))
        if (ids.isEmpty()) return
        manager.updateAppWidget(ids, build(context, UsageStore(context).snapshot()))
    }

    private fun build(context: Context, s: Snapshot): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_usage)

        bindWindow(context, views, s.fiveHour, R.id.five_pct, R.id.five_bar, R.id.five_reset)
        bindWindow(context, views, s.weekly, R.id.week_pct, R.id.week_bar, R.id.week_reset)

        views.setTextViewText(
            R.id.updated,
            when {
                s.refreshing -> "Refreshing…"
                s.fetchedAt > 0 -> formatTime(context, s.fetchedAt)
                else -> ""
            },
        )

        val statusText = when (s.status) {
            Status.SIGNED_OUT -> "Tap to sign in"
            Status.AUTH_EXPIRED -> "Session expired. Tap to sign in"
            Status.ERROR -> s.errorMessage?.let { if (s.fetchedAt > 0) "$it · showing last data" else it }
            Status.OK -> null
        }
        if (statusText != null) {
            views.setTextViewText(R.id.status, statusText)
            views.setViewVisibility(R.id.status, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.status, View.GONE)
        }

        val needsLogin = s.status == Status.SIGNED_OUT || s.status == Status.AUTH_EXPIRED
        views.setOnClickPendingIntent(
            R.id.widget_root,
            if (needsLogin) loginIntent(context) else refreshIntent(context),
        )
        return views
    }

    private fun bindWindow(
        context: Context,
        views: RemoteViews,
        window: UsageWindow?,
        pctId: Int,
        barId: Int,
        resetId: Int,
    ) {
        val pct = window?.percent
        views.setTextViewText(pctId, pct?.let { "$it%" } ?: context.getString(R.string.placeholder))
        views.setTextColor(pctId, context.getColor(levelColor(pct)))
        views.setProgressBar(barId, 100, pct ?: 0, false)
        views.setTextViewText(resetId, resetLabel(context, window))
    }

    private fun levelColor(pct: Int?) = when {
        pct == null -> R.color.text_primary
        pct >= 90 -> R.color.level_critical
        pct >= 70 -> R.color.level_warn
        else -> R.color.text_primary
    }

    private fun resetLabel(context: Context, window: UsageWindow?): String {
        if (window == null) return ""
        val resetsAt = window.resetsAt
            ?: return if (window.percent == 0) "Not started" else ""
        if (resetsAt <= System.currentTimeMillis()) return "Reset, tap to refresh"

        val zone = ZoneId.systemDefault()
        val resetDate = Instant.ofEpochMilli(resetsAt).atZone(zone).toLocalDate()
        val time = formatTime(context, resetsAt)
        return if (resetDate == LocalDate.now(zone)) {
            "Resets $time"
        } else {
            val day = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
                .format(Instant.ofEpochMilli(resetsAt).atZone(zone))
            "Resets $day $time"
        }
    }

    private fun formatTime(context: Context, epochMillis: Long): String {
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
        return DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
            .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
    }

    private fun refreshIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, UsageWidgetProvider::class.java).setAction(UsageWidgetProvider.ACTION_REFRESH),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun loginIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        1,
        Intent(context, LoginActivity::class.java)
            .putExtra(LoginActivity.EXTRA_FORCE_LOGIN, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
