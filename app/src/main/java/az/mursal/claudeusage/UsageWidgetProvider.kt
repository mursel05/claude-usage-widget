package az.mursal.claudeusage

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

class UsageWidgetProvider : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        RefreshScheduler.schedulePeriodic(context)
    }

    override fun onDisabled(context: Context) {
        RefreshScheduler.cancelAll(context)
        WidgetRenderer.cancelTick(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetRenderer.renderAll(context)
        // Keeping the periodic job scheduled also prevents the known WorkManager ↔ onUpdate loop.
        RefreshScheduler.schedulePeriodic(context)

        // onUpdate also fires on reboot / app update / launcher restarts: throttle extra fetches.
        val store = UsageStore(context)
        if (System.currentTimeMillis() - store.lastAttemptAt > MIN_AUTO_REFRESH_GAP_MS) {
            RefreshScheduler.refreshNow(context)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> {
                UsageStore(context).refreshing = true
                WidgetRenderer.renderAll(context)
                RefreshScheduler.refreshNow(context)
            }
            ACTION_TICK -> {
                WidgetRenderer.renderAll(context)
                // A window just reset: fetch fresh numbers instead of showing the old percent.
                val store = UsageStore(context)
                val s = store.snapshot()
                val now = System.currentTimeMillis()
                val passed = listOfNotNull(s.fiveHour?.resetsAt, s.weekly?.resetsAt).any { it <= now }
                if (passed && s.status == Status.OK && now - store.lastAttemptAt > MIN_AUTO_REFRESH_GAP_MS) {
                    RefreshScheduler.refreshNow(context)
                }
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "az.mursal.claudeusage.ACTION_REFRESH"
        const val ACTION_TICK = "az.mursal.claudeusage.ACTION_TICK"
        private const val MIN_AUTO_REFRESH_GAP_MS = 60_000L
    }
}
