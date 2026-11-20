package az.mursal.claudeusage

import android.annotation.SuppressLint
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * claude.ai login in a WebView, opened by tapping the widget while signed out or expired.
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

            // Inject as soon as content is visible (less flash of the Google button) and again when
            // loading finishes. The script guards against double-installing itself.
            override fun onPageCommitVisible(view: WebView, url: String) {
                if (url.startsWith(CLAUDE_URL)) view.evaluateJavascript(LOGIN_PAGE_JS, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                // Any claude.ai page, not only /login: the login form can be reached by client-side routing.
                if (url.startsWith(CLAUDE_URL)) view.evaluateJavascript(LOGIN_PAGE_JS, null)
                // Fallback if no org API call was intercepted.
                lastActiveOrg(CookieManager.getInstance().getCookie(CLAUDE_URL))?.let { tryComplete(it) }
            }
        }

        // H3 · Dark banner in the widget's style; the email field itself is outlined by LOGIN_PAGE_JS.
        val dp = resources.displayMetrics.density
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(getColor(R.color.icon_bg))
            setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
            addView(ImageView(this@LoginActivity).apply {
                setImageResource(R.drawable.ic_mail)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams((22 * dp).toInt(), (22 * dp).toInt()).apply {
                    marginEnd = (12 * dp).toInt()
                    topMargin = (1 * dp).toInt()
                }
            })
            addView(LinearLayout(this@LoginActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@LoginActivity).apply {
                    text = getString(R.string.login_hint_title)
                    setTextColor(getColor(R.color.text_primary))
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(this@LoginActivity).apply {
                    text = getString(R.string.login_hint)
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 14f
                    setPadding(0, (2 * dp).toInt(), 0, 0)
                })
            })
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Dark behind the status/nav bars so the banner runs edge to edge (light bar icons).
            setBackgroundColor(getColor(R.color.icon_bg))
            addView(banner)
            addView(webView)
        }
        webView.setBackgroundColor(Color.WHITE)
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

        /**
         * Runs on claude.ai pages inside the login WebView (client-rendered, so it re-runs on DOM changes):
         * 1. Hides Google sign-in, which Google blocks in WebViews (403 disallowed_useragent). The button
         *    can read just "Continue with" + a Google logo, so the logo's alt/aria/<title>, ids, classes,
         *    links and Google's own sign-in iframes are checked too, not only the visible text.
         * 2. Outlines the email field in the widget's accent color (H3) so it's obvious where to type.
         */
        private val LOGIN_PAGE_JS = """
            (function () {
              if (window.__cuwLogin) return;
              window.__cuwLogin = true;
              var labelOf = function (el) {
                var parts = [el.textContent, el.getAttribute('aria-label'), el.getAttribute('title'),
                  el.getAttribute('data-testid'), el.getAttribute('data-provider'), el.id,
                  el.getAttribute('href'), typeof el.className === 'string' ? el.className : ''];
                el.querySelectorAll('img, svg, title, [aria-label], [data-testid]').forEach(function (c) {
                  parts.push(c.getAttribute('alt'), c.getAttribute('aria-label'), c.getAttribute('data-testid'));
                  if (c.tagName.toLowerCase() === 'title') parts.push(c.textContent);
                });
                return parts.filter(Boolean).join(' ');
              };
              var isGoogle = function (el) {
                var label = labelOf(el);
                if (/google/i.test(label)) return true;
                var text = (el.textContent || '').replace(/\s+/g, ' ').trim();
                return /^(continue|sign in|log in|sign up) with$/i.test(text) &&
                  !/apple|sso|email/i.test(label) && !!el.querySelector('svg, img');
              };
              var hide = function (el) { el.style.setProperty('display', 'none', 'important'); };
              var run = function () {
                document.querySelectorAll('button, a, [role="button"]').forEach(function (el) {
                  if (el.style.display !== 'none' && isGoogle(el)) hide(el);
                });
                document.querySelectorAll('iframe[src*="accounts.google.com"], #credential_picker_container')
                  .forEach(hide);
                document.querySelectorAll('input[type="email"], input[autocomplete="email"], ' +
                    'input[name*="email" i], input[placeholder*="email" i]').forEach(function (el) {
                  if (el.dataset.cuwHighlight) return;
                  el.dataset.cuwHighlight = '1';
                  el.style.setProperty('outline', '2px solid #D97757', 'important');
                  el.style.setProperty('outline-offset', '2px', 'important');
                });
              };
              var queued = false;
              var schedule = function () {
                if (queued) return;
                queued = true;
                requestAnimationFrame(function () { queued = false; run(); });
              };
              run();
              new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true });
            })();
        """.trimIndent()
    }
}
