package az.mursal.claudeusage

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import kotlin.math.roundToInt

/**
 * Reads the same data as claude.ai → Settings → Usage.
 *
 * NOTE: this is claude.ai's internal web endpoint, not a public API. Anthropic can change it
 * at any time; if the widget starts showing "Unexpected response", check this file first.
 */
object ClaudeApi {

    sealed interface Result {
        data class Ok(val fiveHour: UsageWindow, val weekly: UsageWindow) : Result
        data object AuthExpired : Result
        data class Failed(val message: String) : Result
    }

    private const val TIMEOUT_MS = 15_000

    /** Blocking; call off the main thread. */
    fun fetchUsage(credentials: Credentials): Result {
        val url = URL("https://claude.ai/api/organizations/${credentials.orgId}/usage")
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Cookie", credentials.cookie)
            // Must match the WebView that obtained the cookies, or Cloudflare may reject them.
            conn.setRequestProperty("User-Agent", credentials.userAgent)
            conn.setRequestProperty("Accept", "application/json")

            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK ->
                    parse(conn.inputStream.bufferedReader().use { it.readText() })
                // 401/403: session expired, or a Cloudflare challenge. Both are fixed by signing in
                // again in the WebView. 3xx: redirected to the login page.
                401, 403, in 300..399 -> Result.AuthExpired
                429 -> Result.Failed("Rate limited, will retry")
                else -> Result.Failed("Server error ($code)")
            }
        } catch (e: IOException) {
            Result.Failed("Offline")
        } catch (e: JSONException) {
            Result.Failed("Unexpected response")
        } finally {
            conn.disconnect()
        }
    }

    /** Expected shape: {"five_hour":{"utilization":42.5,"resets_at":"2026-03-23T13:00:00.886839+00:00"},"seven_day":{...}} */
    internal fun parse(body: String): Result.Ok {
        val root = JSONObject(body)
        if (!root.has("five_hour") && !root.has("seven_day")) {
            throw JSONException("Missing usage windows")
        }
        return Result.Ok(
            fiveHour = parseWindow(root.optJSONObject("five_hour")),
            weekly = parseWindow(root.optJSONObject("seven_day")),
        )
    }

    private fun parseWindow(obj: JSONObject?): UsageWindow {
        if (obj == null) return UsageWindow(percent = null, resetsAt = null)

        val utilization = if (obj.isNull("utilization")) Double.NaN else obj.optDouble("utilization")
        val percent = if (utilization.isNaN()) null else utilization.roundToInt().coerceIn(0, 100)

        val resetsAt = obj.optString("resets_at")
            .takeIf { !obj.isNull("resets_at") && it.isNotBlank() }
            ?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

        return UsageWindow(percent, resetsAt)
    }
}
