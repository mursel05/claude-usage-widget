package az.mursal.claudeusage

import android.annotation.SuppressLint
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Widget configuration screen = claude.ai login in a WebView.
 *
 * Once the logged-in web app calls /api/organizations/{uuid}/..., we know the session is valid,
 * so we keep the cookies + org id and close. The app never sees the password.
 */
class LoginActivity : Activity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var webView: WebView
    @Volatile private var finished = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // If the user backs out, the launcher must not add the widget.
        setResult(RESULT_CANCELED, resultIntent())

        // Adding a second widget while already signed in: no need to log in again.
        val store = UsageStore(this)
        val forceLogin = intent?.getBooleanExtra(EXTRA_FORCE_LOGIN, false) == true
        if (!forceLogin && store.credentials() != null && store.snapshot().status != Status.AUTH_EXPIRED) {
            finishWithSuccess()
            return
        }

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true) // Cloudflare Turnstile
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                ORG_REGEX.find(request.url.toString())?.let { tryComplete(it.groupValues[1]) }
                return null
            }

            override fun onPageFinished(view: WebView, url: String) {
                // Google blocks OAuth in WebViews (403 disallowed_useragent): hide its button so only
                // working sign-in options show.
                if (url.startsWith("$CLAUDE_URL/login") || url.startsWith("$CLAUDE_URL/signup")) {
                    view.evaluateJavascript(HIDE_GOOGLE_JS, null)
                }
                // Fallback if no org API call was intercepted.
                lastActiveOrg(CookieManager.getInstance().getCookie(CLAUDE_URL))?.let { tryComplete(it) }
            }
        }

        val hint = TextView(this).apply {
            text = getString(R.string.login_hint)
            setTextColor(Color.DKGRAY)
            textSize = 13f
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(hint)
            addView(webView)
        }
        // targetSdk 35 is edge-to-edge on Android 15+: pad for status/nav bars and the keyboard.
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom,
                )
            }
            insets
        }
        setContentView(root)

        if (forceLogin) {
            // Expired cookies would be re-captured instantly; start from a clean session.
            CookieManager.getInstance().removeAllCookies {
                CookieManager.getInstance().flush()
                webView.loadUrl("$CLAUDE_URL/login")
            }
        } else {
            webView.loadUrl("$CLAUDE_URL/login")
        }
    }

    /** Called from the WebView's background thread. */
    private fun tryComplete(orgId: String) {
        if (finished) return
        val cookie = CookieManager.getInstance().getCookie(CLAUDE_URL) ?: return
        if (!cookie.contains("sessionKey=")) return
        runOnUiThread {
            if (finished || isFinishing) return@runOnUiThread
            CookieManager.getInstance().flush()
            UsageStore(this).saveCredentials(
                Credentials(cookie = cookie, orgId = orgId, userAgent = webView.settings.userAgentString)
            )
            finishWithSuccess()
        }
    }

    private fun finishWithSuccess() {
        finished = true
        val store = UsageStore(this)
        store.lastAttemptAt = System.currentTimeMillis()
        store.refreshing = true
        // With a configure activity, onUpdate isn't guaranteed on add, so kick things off here.
        WidgetRenderer.renderAll(this)
        RefreshScheduler.schedulePeriodic(this)
        RefreshScheduler.refreshNow(this)
        setResult(RESULT_OK, resultIntent())
        finish()
    }

    private fun resultIntent() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun lastActiveOrg(cookie: String?): String? = cookie
        ?.split(';')
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("lastActiveOrg=") }
        ?.substringAfter('=')
        ?.takeIf { UUID_REGEX.matches(it) }

    companion object {
        const val EXTRA_FORCE_LOGIN = "force_login"
        private const val CLAUDE_URL = "https://claude.ai"
        private const val UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
        private val UUID_REGEX = Regex(UUID)
        private val ORG_REGEX = Regex("^https://claude\\.ai/api/organizations/($UUID)(?:[/?]|$)")

        /** The login page renders client-side, so keep hiding Google buttons as the DOM changes. */
        private val HIDE_GOOGLE_JS = """
            (function () {
              if (window.__cuwHideGoogle) return;
              window.__cuwHideGoogle = true;
              var hide = function () {
                document.querySelectorAll('button, a, [role="button"]').forEach(function (el) {
                  var label = (el.textContent || '') + ' ' + (el.getAttribute('aria-label') || '');
                  if (/google/i.test(label)) el.style.display = 'none';
                });
              };
              hide();
              new MutationObserver(hide).observe(document.documentElement, { childList: true, subtree: true });
            })();
        """.trimIndent()
    }
}
