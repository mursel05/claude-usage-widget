package az.mursal.claudeusage

import android.content.Context
import android.content.SharedPreferences

/** One rate-limit window. [percent] is null when the API doesn't report it; [resetsAt] is epoch millis. */
data class UsageWindow(val percent: Int?, val resetsAt: Long?)

data class Credentials(val cookie: String, val orgId: String, val userAgent: String)

enum class Status { SIGNED_OUT, OK, AUTH_EXPIRED, ERROR }

data class Snapshot(
    val status: Status,
    val fiveHour: UsageWindow?,
    val weekly: UsageWindow?,
    val fetchedAt: Long,
    val errorMessage: String?,
    val refreshing: Boolean,
)

/**
 * App-private persistence. Lives in the app sandbox with backup disabled; the session cookie
 * never leaves the device except in requests to claude.ai.
 */
class UsageStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun credentials(): Credentials? {
        val cookie = prefs.getString(KEY_COOKIE, null) ?: return null
        val orgId = prefs.getString(KEY_ORG, null) ?: return null
        val ua = prefs.getString(KEY_UA, null) ?: return null
        return Credentials(cookie, orgId, ua)
    }

    fun saveCredentials(credentials: Credentials) {
        prefs.edit()
            .putString(KEY_COOKIE, credentials.cookie)
            .putString(KEY_ORG, credentials.orgId)
            .putString(KEY_UA, credentials.userAgent)
            .putString(KEY_STATUS, Status.OK.name)
            .remove(KEY_ERROR)
            .apply()
    }

    fun saveUsage(fiveHour: UsageWindow, weekly: UsageWindow, fetchedAt: Long) {
        prefs.edit()
            .putWindow(PREFIX_FIVE, fiveHour)
            .putWindow(PREFIX_WEEK, weekly)
            .putLong(KEY_FETCHED_AT, fetchedAt)
            .putString(KEY_STATUS, Status.OK.name)
            .remove(KEY_ERROR)
            .apply()
    }

    fun markAuthExpired() {
        prefs.edit().putString(KEY_STATUS, Status.AUTH_EXPIRED.name).remove(KEY_ERROR).apply()
    }

    fun markError(message: String) {
        prefs.edit().putString(KEY_STATUS, Status.ERROR.name).putString(KEY_ERROR, message).apply()
    }

    var refreshing: Boolean
        get() = prefs.getBoolean(KEY_REFRESHING, false)
        set(value) = prefs.edit().putBoolean(KEY_REFRESHING, value).apply()

    var lastAttemptAt: Long
        get() = prefs.getLong(KEY_LAST_ATTEMPT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_ATTEMPT, value).apply()

    fun snapshot(): Snapshot {
        val fetchedAt = prefs.getLong(KEY_FETCHED_AT, 0L)
        val hasData = fetchedAt > 0L
        val status = when {
            credentials() == null -> Status.SIGNED_OUT
            else -> runCatching { Status.valueOf(prefs.getString(KEY_STATUS, Status.OK.name)!!) }
                .getOrDefault(Status.OK)
        }
        return Snapshot(
            status = status,
            fiveHour = if (hasData) readWindow(PREFIX_FIVE) else null,
            weekly = if (hasData) readWindow(PREFIX_WEEK) else null,
            fetchedAt = fetchedAt,
            errorMessage = prefs.getString(KEY_ERROR, null),
            refreshing = refreshing,
        )
    }

    private fun SharedPreferences.Editor.putWindow(prefix: String, w: UsageWindow) = apply {
        putInt("${prefix}_pct", w.percent ?: -1)
        putLong("${prefix}_reset", w.resetsAt ?: 0L)
    }

    private fun readWindow(prefix: String) = UsageWindow(
        percent = prefs.getInt("${prefix}_pct", -1).takeIf { it >= 0 },
        resetsAt = prefs.getLong("${prefix}_reset", 0L).takeIf { it > 0L },
    )

    private companion object {
        const val PREFS = "claude_usage"
        const val KEY_COOKIE = "cookie"
        const val KEY_ORG = "org_id"
        const val KEY_UA = "user_agent"
        const val KEY_STATUS = "status"
        const val KEY_ERROR = "error"
        const val KEY_FETCHED_AT = "fetched_at"
        const val KEY_REFRESHING = "refreshing"
        const val KEY_LAST_ATTEMPT = "last_attempt"
        const val PREFIX_FIVE = "five"
        const val PREFIX_WEEK = "week"
    }
}
