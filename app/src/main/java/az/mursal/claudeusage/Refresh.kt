package az.mursal.claudeusage

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object RefreshScheduler {
    private const val PERIODIC = "usage_periodic"
    private const val ONE_TIME = "usage_now"

    /** 15 minutes is the Android minimum for periodic background work. */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** No network constraint on purpose: when offline the worker fails fast and the widget says so. */
    fun refreshNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_TIME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<RefreshWorker>().build(),
        )
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(PERIODIC)
            cancelUniqueWork(ONE_TIME)
        }
    }
}

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = UsageStore(applicationContext)
        try {
            val credentials = store.credentials() ?: return Result.success()
            store.lastAttemptAt = System.currentTimeMillis()

            when (val result = withContext(Dispatchers.IO) { ClaudeApi.fetchUsage(credentials) }) {
                is ClaudeApi.Result.Ok ->
                    store.saveUsage(result.fiveHour, result.weekly, System.currentTimeMillis())
                ClaudeApi.Result.AuthExpired -> store.markAuthExpired()
                is ClaudeApi.Result.Failed -> store.markError(result.message)
            }
        } finally {
            store.refreshing = false
            WidgetRenderer.renderAll(applicationContext)
        }
        // Always success: the periodic schedule is the retry mechanism, no backoff storms.
        return Result.success()
    }
}
