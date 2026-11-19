package az.mursal.claudeusage

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object WidgetRenderer {

    private const val RING_PX = 240
    private const val RING_STROKE_PX = 24f
    private const val TICK_MS = 60_000L
    private const val USAGE_PAGE = "https://claude.ai/settings/usage"

    fun renderAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, UsageWidgetProvider::class.java))
        if (ids.isEmpty()) {
            cancelTick(context)
            return
        }
        manager.updateAppWidget(ids, build(context, UsageStore(context).snapshot()))
        scheduleTick(context)
    }

    private fun build(context: Context, s: Snapshot): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_usage)
        val now = System.currentTimeMillis()

        bindRing(context, views, s.fiveHour, R.id.five_ring, R.id.five_pct, R.id.five_reset, now)
        bindRing(context, views, s.weekly, R.id.week_ring, R.id.week_pct, R.id.week_reset, now)

        val statusText = when (s.status) {
            Status.SIGNED_OUT -> "Tap to sign in"
            Status.AUTH_EXPIRED -> "Session expired · tap to sign in"
            Status.ERROR -> s.errorMessage
            Status.OK -> if (s.fetchedAt == 0L && s.refreshing) "Loading…" else null
        }

        val resets = s.resets
        if (statusText != null) {
            views.setTextViewText(R.id.status, statusText)
            views.setViewVisibility(R.id.status, View.VISIBLE)
            views.setViewVisibility(R.id.resets_row, View.GONE)
        } else {
            views.setViewVisibility(R.id.status, View.GONE)
            if (resets != null && resets.count > 0) {
                views.setTextViewText(
                    R.id.resets_count,
                    if (resets.count == 1) "1 free reset" else "${resets.count} free resets",
                )
                views.setTextViewText(R.id.resets_until, resets.expiresAt?.let { "until ${formatDay(it)}" } ?: "")
                views.setOnClickPendingIntent(R.id.resets_row, usagePageIntent(context))
                views.setViewVisibility(R.id.resets_row, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.resets_row, View.GONE)
            }
        }

        val needsLogin = s.status == Status.SIGNED_OUT || s.status == Status.AUTH_EXPIRED
        views.setOnClickPendingIntent(
            R.id.widget_root,
            if (needsLogin) loginIntent(context) else refreshIntent(context),
        )
        return views
    }

    private fun bindRing(
        context: Context,
        views: RemoteViews,
        window: UsageWindow?,
        ringId: Int,
        pctId: Int,
        resetId: Int,
        now: Long,
    ) {
        val pct = window?.percent
        val level = levelColor(context, pct)
        views.setImageViewBitmap(ringId, ringBitmap(pct, level ?: context.getColor(R.color.accent)))
        views.setTextViewText(pctId, pct?.let { "$it%" } ?: context.getString(R.string.placeholder))
        views.setTextColor(pctId, level ?: context.getColor(R.color.text_primary))
        views.setTextViewText(resetId, resetLabel(window, now))
    }

    /** null = normal level (accent ring, white text). */
    private fun levelColor(context: Context, pct: Int?): Int? = when {
        pct == null -> null
        pct >= 90 -> context.getColor(R.color.level_critical)
        pct >= 70 -> context.getColor(R.color.level_warn)
        else -> null
    }

    private fun ringBitmap(pct: Int?, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(RING_PX, RING_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val inset = RING_STROKE_PX / 2f
        val rect = RectF(inset, inset, RING_PX - inset, RING_PX - inset)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = RING_STROKE_PX
            strokeCap = Paint.Cap.ROUND
        }
        paint.color = 0x1FFFFFFF // track: 12% white
        canvas.drawArc(rect, 0f, 360f, false, paint)
        if (pct != null && pct > 0) {
            paint.color = color
            canvas.drawArc(rect, -90f, 360f * pct.coerceAtMost(100) / 100f, false, paint)
        }
        return bmp
    }

    private fun resetLabel(window: UsageWindow?, now: Long): String {
        if (window == null) return ""
        val resetsAt = window.resetsAt ?: return if (window.percent == 0) "not started" else ""
        val remaining = resetsAt - now
        return if (remaining <= 0) "resetting…" else "in ${formatDuration(remaining)}"
    }

    /** Ceil to the minute: "42m", "2h 13m", "4d 3h". */
    internal fun formatDuration(ms: Long): String {
        val totalMin = (ms + 59_999) / 60_000
        val d = totalMin / 1440
        val h = (totalMin % 1440) / 60
        val m = totalMin % 60
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m}m"
            else -> "${m}m"
        }
    }

    private fun formatDay(epochMillis: Long): String =
        DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
            .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

    // --- Minute tick: re-renders countdowns from cache, no network. ---
    // Non-wakeup + inexact: never wakes the phone, simply skipped while the screen is off.

    private fun scheduleTick(context: Context) {
        context.getSystemService(AlarmManager::class.java)
            .set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + TICK_MS, tickIntent(context))
    }

    fun cancelTick(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(tickIntent(context))
    }

    private fun tickIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        2,
        Intent(context, UsageWidgetProvider::class.java).setAction(UsageWidgetProvider.ACTION_TICK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

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

    private fun usagePageIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        3,
        Intent(Intent.ACTION_VIEW, Uri.parse(USAGE_PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
