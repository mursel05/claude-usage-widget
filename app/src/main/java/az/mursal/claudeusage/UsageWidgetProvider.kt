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
        if (intent.action == ACTION_REFRESH) {
            UsageStore(context).refreshing = true
            WidgetRenderer.renderAll(context)
            RefreshScheduler.refreshNow(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "az.mursal.claudeusage.ACTION_REFRESH"
        private const val MIN_AUTO_REFRESH_GAP_MS = 60_000L
    }
}
